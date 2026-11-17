package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link JUringBlocking} admission contract: the pre-allocated call-record pool is sized from
 * {@link BlockingOptions#withMaxConcurrentCalls(int)}, oversubscription follows
 * {@link OverflowPolicy}, an interrupt cancels the request and returns {@code -EINTR}, and a
 * non-power-of-two {@link RingGroup} no longer strands workers.
 *
 * <p>Blocked requests are deterministic: 8-byte reads on an {@code eventfd} whose counter is
 * zero stay pending until the counter is signalled or the request is cancelled (the same
 * primitive the wrapper uses for its own wakeup read).
 */
@Timeout(60)
class JUringBlockingAdmissionTest {

    private static final int RECORDS = 256; // BlockingOptions.MIN_RECORDS per worker

    @TempDir
    Path tempDir;

    private static FileDescriptor newBlockingEventFd() {
        int fd = NativeDispatcher.C.eventfd(0, LibCDispatcher.EFD_CLOEXEC | LibCDispatcher.EFD_NONBLOCK);
        if (fd < 0) {
            throw new IllegalStateException("eventfd() failed");
        }
        return new FileDescriptor(fd);
    }

    private static BlockingOptions options(OverflowPolicy overflow) {
        return BlockingOptions.defaults()
                .withQueueDepth(1024)
                .withPollers(1)
                .withMaxConcurrentCalls(RECORDS)
                .withOverflow(overflow);
    }

    private static void awaitParked(JUringBlocking io, long atLeast) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (io.parkedCallers() >= atLeast) {
                return;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        fail("only " + io.parkedCallers() + " of " + atLeast + " calls parked");
    }

    private static void awaitOverflowWait(JUringBlocking io) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (io.overflowWaits() > 0) {
                return;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        fail("no call ever took the saturation path");
    }

    /**
     * Starts {@value #RECORDS} blocking eventfd reads, one per virtual thread, and waits until
     * they are parked. Returns their futures.
     */
    private static List<Future<Object>> saturate(JUringBlocking io, FileDescriptor fd,
                                                 ExecutorService vts) {
        List<Future<Object>> futures = new ArrayList<>(RECORDS);
        for (int i = 0; i < RECORDS; i++) {
            futures.add(vts.submit(() -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(8);
                    return io.read(fd, buffer, 8, 0);
                } catch (IllegalStateException e) {
                    return e;
                }
            }));
        }
        awaitParked(io, RECORDS);
        return futures;
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
            } catch (TimeoutException e) {
                fail("caller stayed blocked after close (admission wake contract violated)");
                return;
            }
            if (value instanceof Long result) {
                assertEquals(-JuringException.ECANCELED, result.longValue(),
                        "expected -ECANCELED for a cancelled read");
                cancelled++;
            } else {
                assertInstanceOf(IllegalStateException.class, value);
                ise++;
            }
        }
        assertEquals(futures.size(), cancelled + ise, "all callers must unblock");
    }

    @Test
    void accessorsReportTheEffectiveCapacity() {
        try (JUringBlocking io = new JUringBlocking(options(OverflowPolicy.BLOCK))) {
            assertEquals(1, io.options().resolvedPollers());
            assertEquals(RECORDS, io.recordsPerWorker());
            assertEquals(RECORDS, io.maxConcurrentCalls());
            assertEquals(0, io.overflowWaits());
            assertEquals(0, io.parkedCallers());
        }
    }

    @Test
    void failPolicyRejectsTheCallBeyondCapacity() throws Exception {
        JUringBlocking io = new JUringBlocking(options(OverflowPolicy.FAIL));
        try (FileDescriptor fd = newBlockingEventFd();
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor();
             Arena arena = Arena.ofShared()) {
            List<Future<Object>> futures = saturate(io, fd, vts);
            MemorySegment buffer = arena.allocate(8);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> io.read(fd, buffer, 8, 0));
            assertTrue(e.getMessage().contains("capacity"), e.getMessage());
            io.close();
            assertAllUnblocked(futures);
        } finally {
            io.close();
        }
    }

    @Test
    void blockPolicyWaitsForAReleasedRecordAndThenProceeds() throws Exception {
        Path file = tempDir.resolve("read-file.bin");
        Files.write(file, new byte[64]);

        JUringBlocking io = new JUringBlocking(options(OverflowPolicy.BLOCK));
        try (FileDescriptor blockingFd = newBlockingEventFd();
             FileDescriptor fileFd = new FileDescriptor(file, READ);
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor();
             Arena arena = Arena.ofShared()) {
            List<Future<Object>> blocked = saturate(io, blockingFd, vts);

            // One call beyond capacity: it must park on the admission monitor.
            Future<Long> beyondCapacity = vts.submit(() -> {
                try (Arena callArena = Arena.ofConfined()) {
                    MemorySegment buffer = callArena.allocate(8);
                    return io.read(fileFd, buffer, 8, 0);
                }
            });
            awaitOverflowWait(io);

            // Signal the eventfd once: exactly one pending 8-byte read completes and frees its
            // record, which admits the waiting call.
            MemorySegment eightBytes = arena.allocate(8);
            eightBytes.set(JAVA_LONG, 0, 8L);
            long written = NativeDispatcher.C.write(blockingFd.getFd(), eightBytes.address(), 8L);
            assertTrue(written >= 0, "eventfd write failed: " + written);

            assertEquals(8L, beyondCapacity.get(10, TimeUnit.SECONDS),
                    "the saturated call must be admitted and complete after a record is freed");
            io.close();
            int signalled = 0;
            int cancelled = 0;
            int ise = 0;
            for (Future<Object> future : blocked) {
                Object value = future.get(10, TimeUnit.SECONDS);
                if (value instanceof Long result) {
                    if (result == 8L) {
                        signalled++;
                    } else {
                        assertEquals(-JuringException.ECANCELED, result.longValue());
                        cancelled++;
                    }
                } else {
                    assertInstanceOf(IllegalStateException.class, value);
                    ise++;
                }
            }
            assertEquals(1, signalled, "exactly the signalled eventfd read completes");
            assertEquals(RECORDS, signalled + cancelled + ise);
        } finally {
            io.close();
        }
    }

    @Test
    void interruptCancelsTheRequestAndReturnsEintr() throws Exception {
        try (JUringBlocking io = new JUringBlocking(64, 1);
             FileDescriptor fd = newBlockingEventFd()) {
            AtomicLong result = new AtomicLong(Long.MIN_VALUE);
            boolean[] interruptFlag = new boolean[1];

            Thread vt = Thread.ofVirtual().unstarted(() -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(8);
                    result.set(io.read(fd, buffer, 8, 0));
                } finally {
                    interruptFlag[0] = Thread.currentThread().isInterrupted();
                }
            });
            vt.start();
            awaitParked(io, 1);

            vt.interrupt();
            vt.join(10_000);
            assertFalse(vt.isAlive(), "the interrupted caller must return");
            assertEquals(-JuringException.EINTR, result.get(),
                    "an interrupted blocking call returns -EINTR");
            assertTrue(interruptFlag[0], "the interrupt flag must be preserved");
            assertEquals(0, io.parkedCallers(), "the call record must be released");
        }
    }

    @Test
    void interruptOfAQueuedRequestIsCancelledAfterItIsSubmitted() throws Exception {
        // queueDepth 8 -> at most 7 requests in flight, so the 8th request stays queued in the
        // worker's MPSC and there is nothing in the kernel to cancel yet. The interrupt must be
        // remembered and the cancellation issued once the request reaches the ring.
        try (JUringBlocking io = new JUringBlocking(BlockingOptions.defaults()
                .withQueueDepth(8)
                .withPollers(1)
                .withMaxConcurrentCalls(256));
             FileDescriptor fd = newBlockingEventFd();
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor();
             Arena sharedArena = Arena.ofShared()) {
            List<Future<Object>> inFlight = new ArrayList<>(7);
            for (int i = 0; i < 7; i++) {
                inFlight.add(vts.submit(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        return io.read(fd, arena.allocate(8), 8, 0);
                    }
                }));
            }
            awaitParked(io, 7);

            AtomicLong result = new AtomicLong(Long.MIN_VALUE);
            Thread queued = Thread.ofVirtual().unstarted(() -> {
                try (Arena arena = Arena.ofConfined()) {
                    result.set(io.read(fd, arena.allocate(8), 8, 0));
                }
            });
            queued.start();
            awaitParked(io, 8);

            queued.interrupt();
            // Free one in-flight slot so the queued request is submitted (and then cancelled).
            MemorySegment eightBytes = sharedArena.allocate(8);
            eightBytes.set(JAVA_LONG, 0, 8L);
            assertTrue(NativeDispatcher.C.write(fd.getFd(), eightBytes.address(), 8L) >= 0);

            queued.join(10_000);
            assertFalse(queued.isAlive(), "the interrupted queued caller must return");
            assertEquals(-JuringException.EINTR, result.get());

            io.close();
            for (Future<Object> future : inFlight) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void manyQueuedInterruptsAreAllCancelledThroughTheOverflowQueue() throws Exception {
        // More than CANCEL_RING_SIZE (64) queued calls are interrupted at once, so the
        // cancellations do not fit in the poller's ring and use the overflow queue. One
        // eventfd signal frees the first in-flight slot; from there each queued request is
        // submitted, cancelled and frees the next slot, so every caller must return -EINTR.
        int queuedCount = 100;
        try (JUringBlocking io = new JUringBlocking(BlockingOptions.defaults()
                .withQueueDepth(8)
                .withPollers(1)
                .withMaxConcurrentCalls(256));
             FileDescriptor fd = newBlockingEventFd();
             Arena sharedArena = Arena.ofShared()) {
            List<Thread> inFlight = new ArrayList<>(7);
            for (int i = 0; i < 7; i++) {
                Thread vt = Thread.ofVirtual().start(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        io.read(fd, arena.allocate(8), 8, 0);
                    }
                });
                inFlight.add(vt);
            }
            awaitParked(io, 7);

            AtomicLong[] results = new AtomicLong[queuedCount];
            List<Thread> queued = new ArrayList<>(queuedCount);
            for (int i = 0; i < queuedCount; i++) {
                AtomicLong result = new AtomicLong(Long.MIN_VALUE);
                results[i] = result;
                Thread vt = Thread.ofVirtual().unstarted(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        result.set(io.read(fd, arena.allocate(8), 8, 0));
                    }
                });
                vt.start();
                queued.add(vt);
            }
            awaitParked(io, 7 + queuedCount);

            for (Thread vt : queued) {
                vt.interrupt();
            }
            // One completion frees the first slot; the deferred cancellations cascade.
            MemorySegment eightBytes = sharedArena.allocate(8);
            eightBytes.set(JAVA_LONG, 0, 8L);
            assertTrue(NativeDispatcher.C.write(fd.getFd(), eightBytes.address(), 8L) >= 0);

            for (Thread vt : queued) {
                vt.join(20_000);
                assertFalse(vt.isAlive(), "an interrupted queued caller stayed blocked");
            }
            for (int i = 0; i < queuedCount; i++) {
                assertEquals(-JuringException.EINTR, results[i].get(),
                        "queued caller " + i + " must report the interrupt");
            }

            io.close();
            for (Thread vt : inFlight) {
                vt.join(10_000);
            }
        }
    }

    @Test
    void deferredInterruptDoesNotSpinThePoller() throws Exception {
        // A queued request that is interrupted while the ring is full cannot be cancelled
        // until it is submitted. Until then the poller must sleep, not spin on the deferred
        // cancellation (a regression guard for the in-kernel wake condition).
        RingGroup group = RingGroup.create(1, ShardOptions.defaults()
                .withName("admission-no-spin")
                .withRingEntries(8)
                .withRingOptions(IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN));
        try (JUringBlocking io = new JUringBlocking(group);
             FileDescriptor fd = newBlockingEventFd();
             var vts = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Thread> inFlight = new ArrayList<>(7);
            for (int i = 0; i < 7; i++) {
                Thread vt = Thread.ofVirtual().start(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        io.read(fd, arena.allocate(8), 8, 0);
                    }
                });
                inFlight.add(vt);
            }
            awaitParked(io, 7);

            Thread queued = Thread.ofVirtual().unstarted(() -> {
                try (Arena arena = Arena.ofConfined()) {
                    io.read(fd, arena.allocate(8), 8, 0);
                }
            });
            queued.start();
            awaitParked(io, 8);
            queued.interrupt();
            // Give the caller time to enqueue its deferred cancellation, then measure the
            // poller while no completion can arrive.
            Thread.sleep(50);
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            Thread poller = group.shard(0).shardThread();
            assertNotNull(poller);
            long cpuBefore = bean.getThreadCpuTime(poller.threadId());
            Thread.sleep(200);
            long cpuAfter = bean.getThreadCpuTime(poller.threadId());
            long usedMillis = TimeUnit.NANOSECONDS.toMillis(cpuAfter - cpuBefore);
            assertTrue(usedMillis < 80,
                    "the poller burned " + usedMillis + " ms of CPU while a cancellation was deferred");

            io.close();
            queued.join(10_000);
            for (Thread vt : inFlight) {
                vt.join(10_000);
            }
        }
    }

    @Test
    void nonPowerOfTwoGroupRoutesEveryWorker() throws Exception {
        Path file = tempDir.resolve("route.bin");
        Files.write(file, new byte[8192]);

        RingGroup group = RingGroup.create(3, ShardOptions.defaults()
                .withName("admission-non-pow2")
                .withRingEntries(64)
                .withRingOptions(IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN));
        try (JUringBlocking io = new JUringBlocking(group);
             FileDescriptor fd = new FileDescriptor(file, READ);
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals(3, io.options().resolvedPollers(),
                    "an adopted group reports its shard count, not a rounded CPU default");
            assertEquals(3 * io.recordsPerWorker(), io.maxConcurrentCalls());
            List<Future<Long>> futures = new ArrayList<>(600);
            for (int i = 0; i < 600; i++) {
                futures.add(vts.submit(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        return io.read(fd, arena.allocate(8), 8, 0);
                    }
                }));
            }
            for (Future<Long> future : futures) {
                assertEquals(8L, future.get(20, TimeUnit.SECONDS));
            }
            long[] ringSubmits = new long[3];
            for (int i = 0; i < 3; i++) {
                ringSubmits[i] = group.stats().get(i).ringSubmits();
            }
            for (int i = 0; i < 3; i++) {
                assertTrue(ringSubmits[i] > 0,
                        "worker " + i + " received no submissions: " + java.util.Arrays.toString(ringSubmits));
            }
        }
    }

    @Test
    void closeUnblocksSaturatedWaiters() throws Exception {
        JUringBlocking io = new JUringBlocking(options(OverflowPolicy.BLOCK));
        try (FileDescriptor fd = newBlockingEventFd();
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor();
             Arena arena = Arena.ofShared()) {
            List<Future<Object>> parked = saturate(io, fd, vts);

            // Calls beyond capacity park on the admission monitor; close must release them all.
            List<Future<Object>> overflow = new ArrayList<>(32);
            MemorySegment buffer = arena.allocate(8);
            for (int i = 0; i < 32; i++) {
                overflow.add(vts.submit(() -> {
                    try {
                        return io.read(fd, buffer, 8, 0);
                    } catch (IllegalStateException e) {
                        return e;
                    }
                }));
            }
            awaitOverflowWait(io);
            io.close();

            assertAllUnblocked(parked);
            for (Future<Object> future : overflow) {
                Object value = future.get(10, TimeUnit.SECONDS);
                assertTrue(value instanceof Long || value instanceof IllegalStateException,
                        "unexpected result " + value);
            }
        } finally {
            io.close();
        }
    }
}
