package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * B4 design §12.18 — bounded close with in-flight requests: returns within
 * {@code timeout + 250 ms} (+ small test slack), unblocks every caller (real
 * {@code -ECANCELED} completion or {@link IllegalStateException}), and leaks no shard
 * threads / file descriptors.
 *
 * <p>In-flight requests are deterministic: blocking 8-byte reads on an {@code eventfd} whose
 * counter is zero (the same kernel primitive {@code JUringBlocking} uses for its wakeup read;
 * the read stays pending until the eventfd is signalled or cancelled).
 */
@Timeout(60)
class B4BlockingBoundedCloseTest {

    private static final int IN_FLIGHT = 32;

    @TempDir
    Path tempDir;

    private static RingGroup newGroup() {
        return RingGroup.create(2, ShardOptions.defaults()
                .withName("b4-blocking-close")
                .withRingEntries(64)
                .withRingOptions(IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN));
    }

    private static FileDescriptor newBlockingEventFd() {
        int fd = NativeDispatcher.C.eventfd(0, LibCDispatcher.EFD_CLOEXEC | LibCDispatcher.EFD_NONBLOCK);
        if (fd < 0) {
            throw new IllegalStateException("eventfd() failed");
        }
        return new FileDescriptor(fd);
    }

    /** Submits {@value #IN_FLIGHT} blocking 8-byte reads and waits until they are in flight. */
    private static List<Future<Object>> startInFlightReads(JUringBlocking blocking, RingGroup group,
                                                          FileDescriptor fd, CountDownLatch started,
                                                          java.util.concurrent.ExecutorService vts) {
        List<Future<Object>> futures = new ArrayList<>(IN_FLIGHT);
        for (int i = 0; i < IN_FLIGHT; i++) {
            futures.add(vts.submit(() -> {
                started.countDown();
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(8);
                    return blocking.read(fd, buffer, 8, 0);
                } catch (IllegalStateException e) {
                    return e;
                }
            }));
        }
        awaitLatch(started);
        awaitInFlight(group, IN_FLIGHT / 2);
        return futures;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "reads did not start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for reads to start");
        }
    }

    private static void awaitInFlight(RingGroup group, long atLeast) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            long inFlight = 0;
            for (ShardStats stats : group.stats()) {
                inFlight += stats.inFlightRequests();
            }
            if (inFlight >= atLeast) {
                return;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        fail("blocking reads did not accumulate in flight");
    }

    /** Every caller must have unblocked: a real -ECANCELED result or IllegalStateException. */
    private static void assertAllUnblocked(List<Future<Object>> futures) {
        int cancelled = 0;
        int ise = 0;
        for (Future<Object> future : futures) {
            Object value;
            try {
                value = future.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for an unblocked caller");
                return;
            } catch (ExecutionException e) {
                fail("caller failed with unexpected exception: " + e.getCause());
                return;
            } catch (java.util.concurrent.TimeoutException e) {
                fail("caller stayed blocked after close (bounded-close contract violated)");
                return;
            }
            if (value instanceof Long result) {
                assertEquals(-125L, result.longValue(), "expected -ECANCELED for a cancelled read");
                cancelled++;
            } else {
                assertInstanceOf(IllegalStateException.class, value);
                ise++;
            }
        }
        assertEquals(IN_FLIGHT, cancelled + ise, "all in-flight callers must unblock");
    }

    private static long countShardThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("juring-shard-"))
                .count();
    }

    private static void awaitNoShardThreads() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (countShardThreads() == 0) {
                return;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        fail("shard threads still alive after bounded close: " + countShardThreads());
    }

    private static int countFds() throws IOException {
        try (var entries = Files.list(Path.of("/proc/self/fd"))) {
            return (int) entries.count();
        }
    }

    private static int awaitFdCountAtMost(int max) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        int count = Integer.MAX_VALUE;
        while (System.nanoTime() < deadline) {
            try {
                count = countFds();
            } catch (IOException e) {
                return max;
            }
            if (count <= max) {
                return count;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return count;
    }

    @Test
    void boundedCloseWithInFlightReads() throws Exception {
        int fdsBefore = countFds();
        RingGroup group = newGroup();
        JUringBlocking blocking = new JUringBlocking(group);
        var vts = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch started = new CountDownLatch(IN_FLIGHT);
        List<Future<Object>> futures;
        FileDescriptor fd = newBlockingEventFd();
        try {
            futures = startInFlightReads(blocking, group, fd, started, vts);

            long startNanos = System.nanoTime();
            blocking.close(2000);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(elapsedMillis <= 2000 + 250 + 500,
                    "close(2000) with in-flight reads took " + elapsedMillis + " ms (bound 2250 ms)");
            assertTrue(group.isClosed(), "group must be closed by JUringBlocking.close()");
            assertAllUnblocked(futures);
        } finally {
            fd.close();
            vts.shutdown();
            vts.awaitTermination(5, TimeUnit.SECONDS);
        }
        awaitNoShardThreads();
        assertTrue(awaitFdCountAtMost(fdsBefore + 2) <= fdsBefore + 2,
                "file descriptors leaked: before=" + fdsBefore + " after=" + countFds());
    }

    @Test
    void closeZeroIsBounded() throws Exception {
        int fdsBefore = countFds();
        RingGroup group = newGroup();
        JUringBlocking blocking = new JUringBlocking(group);
        var vts = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch started = new CountDownLatch(IN_FLIGHT);
        FileDescriptor fd = newBlockingEventFd();
        try {
            List<Future<Object>> futures = startInFlightReads(blocking, group, fd, started, vts);

            long startNanos = System.nanoTime();
            blocking.close(0);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(elapsedMillis <= 750,
                    "close(0) with in-flight reads took " + elapsedMillis + " ms (must be bounded by the 250 ms grace)");
            assertAllUnblocked(futures);
        } finally {
            fd.close();
            vts.shutdown();
            vts.awaitTermination(5, TimeUnit.SECONDS);
        }
        awaitNoShardThreads();
        assertTrue(awaitFdCountAtMost(fdsBefore + 2) <= fdsBefore + 2,
                "file descriptors leaked: before=" + fdsBefore + " after=" + countFds());
    }

    @Test
    void repeatedCreateCloseLeaksNothing() throws Exception {
        Path file = tempDir.resolve("read_file");
        Files.write(file, "Hello, World!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int fdsBefore = countFds();
        long threadsBefore = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("juring-shard-")).count();
        assertEquals(0L, threadsBefore);

        for (int cycle = 0; cycle < 3; cycle++) {
            RingGroup group = newGroup();
            JUringBlocking blocking = new JUringBlocking(group);
            try (FileDescriptor fd = new FileDescriptor(file, READ, 0);
                 Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(14);
                assertEquals(13L, blocking.read(fd, buffer, 14, 0));
            }
            blocking.close();
            blocking.close(); // idempotent
            assertTrue(group.isClosed());
        }
        awaitNoShardThreads();
        assertTrue(awaitFdCountAtMost(fdsBefore + 2) <= fdsBefore + 2,
                "file descriptors leaked over create/close cycles: before=" + fdsBefore
                        + " after=" + countFds());
    }
}
