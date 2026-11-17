package com.davidvlijmincx.lio.api;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the {@code JUringBlocking} allocation claim.
 *
 * <p>The blocking read/write methods used to end in a {@code SqeOptions...} parameter, so every
 * call allocated an empty {@code SqeOptions[]} at the call site (measured: 16 bytes/op on the
 * caller thread). The exact-arity overloads delegate with a shared empty array; this test pins
 * the caller-thread allocation of the no-option hot path below 4 bytes/op.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JUringBlockingZeroAllocationTest {

    private static final int WARMUP_OPS = 20_000;
    private static final int MEASURED_OPS = 20_000;

    private static final ThreadMXBean THREAD_BEAN;

    static {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean sunBean)) {
            throw new UnsupportedOperationException("com.sun.management.ThreadMXBean not available");
        }
        if (!sunBean.isThreadAllocatedMemorySupported()) {
            throw new UnsupportedOperationException("Thread allocated memory measurement is not supported");
        }
        if (!sunBean.isThreadAllocatedMemoryEnabled()) {
            sunBean.setThreadAllocatedMemoryEnabled(true);
        }
        THREAD_BEAN = sunBean;
    }

    @Test
    void noOptionBlockingReadDoesNotAllocate(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("zero-alloc-read.bin");
        Files.write(file, new byte[1 << 20]);

        try (Arena arena = Arena.ofConfined();
             FileDescriptor fd = new FileDescriptor(file, READ);
             JUringBlocking io = new JUringBlocking(64, 1)) {
            MemorySegment buffer = arena.allocate(4096, 4096);

            for (int i = 0; i < WARMUP_OPS; i++) {
                io.read(fd, buffer, 0);
            }

            long threadId = Thread.currentThread().threadId();
            long before = THREAD_BEAN.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < MEASURED_OPS; i++) {
                io.read(fd, buffer, 0);
            }
            long after = THREAD_BEAN.getThreadAllocatedBytes(threadId);

            double bytesPerOp = (after - before) / (double) MEASURED_OPS;
            assertTrue(bytesPerOp < 4.0,
                    () -> "no-option blocking read must be allocation-free, measured " + bytesPerOp + " bytes/op");
        }
    }

    private static FileDescriptor newBlockingEventFd() {
        int fd = NativeDispatcher.C.eventfd(0, LibCDispatcher.EFD_CLOEXEC | LibCDispatcher.EFD_NONBLOCK);
        if (fd < 0) {
            throw new IllegalStateException("eventfd() failed");
        }
        return new FileDescriptor(fd);
    }

    private static void awaitParked(JUringBlocking io, long atLeast) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (io.parkedCallers() >= atLeast) {
                return;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        throw new AssertionError("only " + io.parkedCallers() + " of " + atLeast + " calls parked");
    }

    /**
     * The saturated admission path parks on the worker's monitor instead of allocating a queue
     * node: a call that has to wait for a record is still allocation-free on the caller thread.
     * (A {@code Future}/node-based design would allocate ~100 bytes per blocked call.)
     */
    @Test
    void saturatedAdmissionDoesNotAllocateOnTheCallerThread(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("saturated-read.bin");
        Files.write(file, new byte[4096]);

        JUringBlocking io = new JUringBlocking(BlockingOptions.defaults()
                .withQueueDepth(1024)
                .withPollers(1)
                .withMaxConcurrentCalls(256));
        try (FileDescriptor blockingFd = newBlockingEventFd();
             FileDescriptor fileFd = new FileDescriptor(file, READ);
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor();
             Arena sharedArena = Arena.ofShared()) {
            List<Future<Object>> blocked = new ArrayList<>(256);
            for (int i = 0; i < 256; i++) {
                blocked.add(vts.submit(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment buffer = arena.allocate(8);
                        return io.read(blockingFd, buffer, 8, 0);
                    }
                }));
            }
            awaitParked(io, 256);

            MemorySegment fileBuffer = sharedArena.allocate(8);
            MemorySegment eightBytes = sharedArena.allocate(8);
            eightBytes.set(JAVA_LONG, 0, 8L);
            long threadId = Thread.currentThread().threadId();

            // Three saturation rounds; the first is warm-up and is not measured.
            double measuredBytesPerCall = 0;
            int measuredRounds = 0;
            for (int round = 0; round < 4; round++) {
                if (round > 0) {
                    // The previous round consumed one eventfd read; re-saturate the pool.
                    blocked.add(vts.submit(() -> {
                        try (Arena arena = Arena.ofConfined()) {
                            MemorySegment buffer = arena.allocate(8);
                            return io.read(blockingFd, buffer, 8, 0);
                        }
                    }));
                    awaitParked(io, 256);
                }
                Thread signaller = Thread.ofPlatform().daemon(true).start(() -> {
                    LockSupport.parkNanos(25_000_000L);
                    NativeDispatcher.C.write(blockingFd.getFd(), eightBytes.address(), 8L);
                });
                long before = THREAD_BEAN.getThreadAllocatedBytes(threadId);
                assertEquals(8L, io.read(fileFd, fileBuffer, 8, 0),
                        "the saturated call must be admitted and complete");
                long after = THREAD_BEAN.getThreadAllocatedBytes(threadId);
                signaller.join(10_000);
                if (round > 0) {
                    measuredBytesPerCall += (after - before);
                    measuredRounds++;
                }
            }
            double bytesPerCall = measuredBytesPerCall / measuredRounds;
            assertTrue(bytesPerCall < 128.0,
                    () -> "saturated admission must not allocate on the caller thread, measured "
                            + bytesPerCall + " bytes/call");

            io.close();
            for (Future<Object> future : blocked) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            io.close();
        }
    }

    @Test
    void noOptionBlockingFixedReadDoesNotAllocate(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("zero-alloc-fixed-read.bin");
        Files.write(file, new byte[1 << 20]);

        try (FileDescriptor fd = new FileDescriptor(file, READ);
             JUringBlocking io = new JUringBlocking(64, 1)) {
            io.registerBuffers(4096, 1);

            for (int i = 0; i < WARMUP_OPS; i++) {
                io.readFixed(fd, 4096, 0, 0);
            }

            long threadId = Thread.currentThread().threadId();
            long before = THREAD_BEAN.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < MEASURED_OPS; i++) {
                io.readFixed(fd, 4096, 0, 0);
            }
            long after = THREAD_BEAN.getThreadAllocatedBytes(threadId);

            double bytesPerOp = (after - before) / (double) MEASURED_OPS;
            assertTrue(bytesPerOp < 4.0,
                    () -> "no-option blocking fixed read must be allocation-free, measured " + bytesPerOp + " bytes/op");
        }
    }
}
