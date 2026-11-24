package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scale evidence for the blocking facade: {@code juring.scale.vt} virtual threads (10,000 by
 * default) all block in one {@link JUringBlocking} at the same time against a pre-sized record
 * pool. Every call must complete with the expected result and no lost wakeups.
 *
 * <p>The admission saturation path itself is covered deterministically by
 * {@link JUringBlockingAdmissionTest}; this test is about scale and resource release.
 *
 * <p>The committed suite runs the default 10,000. The 1,000,000-thread run is opt-in (it needs
 * a large heap and a few minutes):
 * <pre>
 * bench/blocking/run-scale.sh
 * </pre>
 * which sets {@code -Djuring.scale.vt=1000000} and {@code -Xmx6g} on the forked JVM.
 */
@Timeout(600)
class JUringBlockingScaleTest {

    private static final int VIRTUAL_THREADS = Integer.getInteger("juring.scale.vt", 10_000);
    /** -Djuring.scale.pollers=1 runs the whole fan-out on one ring; 0 derives from the CPUs. */
    private static final int POLLERS = Integer.getInteger("juring.scale.pollers", 0);
    /** -Djuring.scale.budget=N overrides the record pool; 0 sizes it to exercise admission. */
    private static final int BUDGET = Integer.getInteger("juring.scale.budget", 0);

    @TempDir
    Path tempDir;

    @Test
    void manyVirtualThreadsBlockAndComplete() throws Exception {
        Path file = tempDir.resolve("scale-read.bin");
        Files.write(file, new byte[1 << 16]);

        int pollers = POLLERS > 0 ? POLLERS : Math.max(1, Math.min(4, Integer.highestOneBit(
                Math.max(1, Runtime.getRuntime().availableProcessors() / 4))));
        // A small pool relative to the thread count: the scale run should have far more callers
        // than records. An explicit budget is a diagnostic configuration.
        int budget = BUDGET > 0 ? BUDGET : pollers * 256;
        BlockingOptions options = BlockingOptions.defaults()
                .withQueueDepth(256)
                .withPollers(pollers)
                .withMaxConcurrentCalls(budget);

        long startNanos = System.nanoTime();
        try (JUringBlocking io = new JUringBlocking(options,
                IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN);
             FileDescriptor fd = new FileDescriptor(file, READ);
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {

            CountDownLatch ready = new CountDownLatch(VIRTUAL_THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>(VIRTUAL_THREADS);
            for (int i = 0; i < VIRTUAL_THREADS; i++) {
                int index = i;
                futures.add(vts.submit(() -> {
                    ready.countDown();
                    go.await(60, TimeUnit.SECONDS);
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment buffer = arena.allocate(8);
                        long offset = (long) (index & 7) * 8L;
                        return io.read(fd, buffer, 8, offset);
                    }
                }));
            }
            assertTrue(ready.await(60, TimeUnit.SECONDS), "virtual threads did not start");
            go.countDown();

            for (Future<Long> future : futures) {
                assertEquals(8L, future.get(300, TimeUnit.SECONDS),
                        "every blocked call must complete");
            }
            System.out.printf("JUringBlockingScaleTest: overflowWaits=%d, parkedCallers=%d%n",
                    io.overflowWaits(), io.parkedCallers());
            assertEquals(0, io.parkedCallers(), "every call record must be released");
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        System.out.printf("JUringBlockingScaleTest: %d virtual threads, budget=%d, pollers=%d, "
                        + "records=%d (%d bytes estimated), %d ms%n",
                VIRTUAL_THREADS, budget, pollers,
                options.resolvedPollers() * options.perWorkerCapacity(pollers),
                options.estimatedBytes(), elapsedMillis);
    }
}
