package com.davidvlijmincx.lio.api;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5 §11 tests 1-7: {@link RingMetrics} exact counters, error accounting, occupancy,
 * enter/batch/retry/SQ-full counters, lifecycle (before/after close, no throw), cross-thread
 * snapshots and zero allocation on the metrics-on hot path.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class B5RingMetricsTest {

    private static final int ECANCELED = 125;
    private static final int ETIME = 62;
    private static final int ETIMEDOUT = 110;

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

    private static final ResultConsumer NOP_CONSUMER = result -> {
        if (result.result() != 0L) {
            throw new IllegalStateException("NOP failed: " + result.result());
        }
    };

    @TempDir
    Path tempDir;

    private JUring ring;
    private long nopLoopOperations;

    @BeforeEach
    void setUp() {
        ring = new JUring(64, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close(1000);
        }
    }

    // --- test 1: exact counters for known workloads --------------------------

    @Test
    void exactCountersForNopWorkload() {
        int operations = 32;
        for (int i = 0; i < operations; i++) {
            ring.prepareNop();
        }
        ring.submit();
        for (int i = 0; i < operations; i++) {
            ring.waitForResult().close();
        }

        RingMetrics metrics = ring.ringMetrics();

        assertEquals(operations, metrics.sqesPrepared());
        assertEquals(operations, metrics.submitted());
        assertEquals(operations, metrics.reaped());
        assertEquals(operations, metrics.completed());
        assertEquals(0L, metrics.failed());
        assertEquals(0L, metrics.cancelled());
        assertEquals(0L, metrics.timedOut());
        assertEquals(0L, metrics.inFlight());
        assertEquals(0L, metrics.sqQueued());
        assertEquals(0L, metrics.cqReady());
        assertTrue(metrics.enters() > 0L);
        assertTrue(metrics.submitBatches() > 0L);
        assertEquals(0L, metrics.submitErrors());
        assertEquals(0L, metrics.sqFullEvents());
        assertInvariants(metrics);
    }

    @Test
    void exactCountersForFileReadWorkload() throws IOException {
        Path file = tempDir.resolve("read.bin");
        byte[] content = new byte[4_096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Files.write(file, content);

        int operations = 16;
        List<Long> handles = new ArrayList<>(operations);
        try (FileDescriptor fd = new FileDescriptor(file, READ)) {
            for (int i = 0; i < operations; i++) {
                handles.add(ring.prepareRead(fd, 256, (long) i * 256L));
            }
            ring.submit();
            List<Long> completedHandles = new ArrayList<>(operations);
            for (int i = 0; i < operations; i++) {
                try (Result result = ring.waitForResult()) {
                    assertEquals(256L, result.result());
                    completedHandles.add(result.id());
                }
            }
            assertEquals(operations, completedHandles.size());
            assertTrue(completedHandles.containsAll(handles), "every prepared read must complete exactly once");
        }

        RingMetrics metrics = ring.ringMetrics();

        assertEquals(operations, metrics.sqesPrepared());
        assertEquals(operations, metrics.submitted());
        assertEquals(operations, metrics.reaped());
        assertEquals(operations, metrics.completed());
        assertEquals(0L, metrics.failed());
        assertEquals(0L, metrics.inFlight());
        assertInvariants(metrics);
    }

    @Test
    void exactCountersForMixedNopAndReadWorkload() throws IOException {
        Path file = tempDir.resolve("mixed.bin");
        Files.write(file, new byte[4_096]);

        int nops = 16;
        int reads = 16;
        try (FileDescriptor fd = new FileDescriptor(file, READ)) {
            for (int i = 0; i < nops; i++) {
                ring.prepareNop();
            }
            for (int i = 0; i < reads; i++) {
                ring.prepareRead(fd, 128, (long) i * 128L);
            }
            ring.submit();
            for (int i = 0; i < nops + reads; i++) {
                ring.waitForResult().close();
            }
        }

        RingMetrics metrics = ring.ringMetrics();

        assertEquals(nops + reads, metrics.sqesPrepared());
        assertEquals(nops + reads, metrics.submitted());
        assertEquals(nops + reads, metrics.reaped());
        assertEquals(nops + reads, metrics.completed());
        assertEquals(0L, metrics.failed());
        assertEquals(0L, metrics.inFlight());
        assertInvariants(metrics);
    }

    // --- test 2: error accounting --------------------------------------------

    @Test
    void invalidFileDescriptorIsCountedAsFailed() {
        try (FileDescriptor invalid = new FileDescriptor(Integer.MAX_VALUE / 2)) {
            ring.prepareRead(invalid, 64, 0);
            ring.submit();
            try (Result result = ring.waitForResult()) {
                assertTrue(result.result() < 0L, "read on an invalid fd must fail");
            }
        }

        RingMetrics metrics = ring.ringMetrics();

        assertEquals(1L, metrics.reaped());
        assertEquals(0L, metrics.completed());
        assertEquals(1L, metrics.failed());
        assertEquals(0L, metrics.cancelled());
        assertEquals(0L, metrics.timedOut());
        assertInvariants(metrics);
    }

    @Test
    void cancelledRequestIsCountedAsCancelled() {
        try (Arena arena = Arena.ofConfined()) {
            long target = ring.prepareTimeout(relative(arena, 60_000_000_000L), 0, 0);
            ring.submit();
            long cancel = ring.prepareCancelByUserData(target);
            ring.submit();

            List<Result> results = new ArrayList<>(2);
            results.add(ring.waitForResult());
            results.add(ring.waitForResult());
            boolean sawCancelledTarget = false;
            for (Result result : results) {
                if (result.id() == target) {
                    assertEquals(-ECANCELED, (int) result.result());
                    sawCancelledTarget = true;
                } else {
                    assertEquals(cancel, result.id());
                }
                result.close();
            }
            assertTrue(sawCancelledTarget);
        }

        RingMetrics metrics = ring.ringMetrics();

        assertEquals(2L, metrics.reaped());
        assertEquals(1L, metrics.failed());
        assertEquals(1L, metrics.cancelled());
        assertEquals(0L, metrics.timedOut());
        assertInvariants(metrics);
    }

    @Test
    void kernelTimeoutIsCountedAsTimedOut() {
        // Amendment A1.1: the raw ring's IORING_OP_TIMEOUT completion is -ETIME (62); the
        // JuringFile layer maps it to ETIMEDOUT (110), which never reaches raw ring metrics.
        try (Arena arena = Arena.ofConfined()) {
            ring.prepareTimeout(relative(arena, 1_000_000L), 0, 0);
            ring.submit();
            try (Result result = ring.waitForResult()) {
                assertEquals(-ETIME, (int) result.result(), "raw ring timeout completion must be -ETIME");
            }
        }

        RingMetrics metrics = ring.ringMetrics();

        assertEquals(1L, metrics.reaped());
        assertEquals(1L, metrics.failed());
        assertEquals(1L, metrics.timedOut());
        assertEquals(0L, metrics.cancelled());
        assertTrue(metrics.timedOut() <= metrics.failed());
        assertInvariants(metrics);
    }

    @Test
    void cqeClassificationRuleIsExact() {
        // A live raw ring cannot deterministically produce every errno (-ETIMEDOUT in
        // particular), so the frozen classification rule itself is unit-tested directly.
        IoUringDispatcher.RingCounters counters = new IoUringDispatcher.RingCounters();
        counters.classifyCqe(0);
        counters.classifyCqe(512);
        counters.classifyCqe(-9);       // EBADF: failed only
        counters.classifyCqe(-ECANCELED);
        counters.classifyCqe(-ETIME);
        counters.classifyCqe(-ETIMEDOUT);

        assertEquals(6L, counters.get(IoUringDispatcher.RingCounters.REAPED));
        assertEquals(2L, counters.get(IoUringDispatcher.RingCounters.COMPLETED));
        assertEquals(4L, counters.get(IoUringDispatcher.RingCounters.FAILED));
        assertEquals(1L, counters.get(IoUringDispatcher.RingCounters.CANCELLED));
        assertEquals(2L, counters.get(IoUringDispatcher.RingCounters.TIMED_OUT));
    }

    // --- test 3: occupancy ---------------------------------------------------

    @Test
    void occupancyTracksPreparedSubmittedAndReapableCompletions() {
        RingMetrics fresh = ring.ringMetrics();
        assertEquals(0L, fresh.sqesPrepared());
        assertEquals(0L, fresh.submitted());
        assertEquals(0L, fresh.sqQueued());
        assertEquals(0L, fresh.cqReady());
        assertTrue(fresh.sqEntries() > 0);
        assertTrue(fresh.cqEntries() > 0);
        assertInvariants(fresh);

        int queued = 5;
        for (int i = 0; i < queued; i++) {
            ring.prepareNop();
        }

        RingMetrics pending = ring.ringMetrics();
        assertEquals(queued, pending.sqQueued(), "prepared-but-not-submitted SQEs are SQ occupancy");
        assertEquals(0L, pending.submitted());
        assertEquals(queued, pending.sqesPrepared());
        assertEquals(queued, ring.pendingSqes(), "live SQ occupancy must match pendingSqes()");

        ring.submitAndWait(queued);
        RingMetrics ready = ring.ringMetrics();
        assertTrue(ready.cqReady() >= queued, "completions must be visible in the CQ ring before reaping");
        assertEquals(0L, ready.sqQueued());
        assertEquals(queued, ready.submitted());

        for (int i = 0; i < queued; i++) {
            ring.waitForResult().close();
        }

        RingMetrics drained = ring.ringMetrics();
        assertEquals(0L, drained.cqReady());
        assertEquals(0L, drained.sqQueued());
        assertEquals(queued, drained.reaped());
        assertEquals(0L, drained.inFlight());
        assertInvariants(drained);
    }

    // --- test 4: enter/batch/retry/SQ-full counters --------------------------

    @Test
    void sqFullEventsAndFlushCountersAreAccounted() {
        int prepared = 0;
        for (int i = 0; i < 4_096; i++) {
            try {
                ring.prepareNop();
                prepared++;
            } catch (IllegalStateException full) {
                break;
            }
        }

        assertTrue(prepared > 0, "at least one SQE must fit in the SQ ring");
        RingMetrics full = ring.ringMetrics();
        assertTrue(full.sqFullEvents() >= 1L, "deliberately overfilling the SQ must count sqFullEvents");
        assertEquals(prepared, full.sqesPrepared());
        assertEquals(0L, full.submitted());
        assertEquals(prepared, full.sqQueued(), "every prepared SQE is SQ occupancy before the flush");

        ring.submit();
        RingMetrics flushed = ring.ringMetrics();
        assertTrue(flushed.submitBatches() >= 1L, "a flush of a non-empty SQ tail is a submit batch");
        assertTrue(flushed.enters() >= 1L);

        ring.submitAndWait(prepared);
        for (int i = 0; i < prepared; i++) {
            ring.waitForResult().close();
        }

        RingMetrics drained = ring.ringMetrics();
        assertEquals(prepared, drained.reaped());
        assertEquals(prepared, drained.completed());
        assertEquals(0L, drained.inFlight());
        assertTrue(drained.sqFullEvents() >= 1L);
        assertTrue(drained.enters() > 0L);
        assertTrue(drained.submitBatches() > 0L);
        assertTrue(drained.submitRetries() >= 0L);
        assertTrue(drained.submitErrors() >= 0L);
        assertInvariants(drained);
    }

    // --- test 5: lifecycle ---------------------------------------------------

    @Test
    void metricsBeforeAndAfterCloseNeverThrowAndFreeze() throws Exception {
        RingMetrics before = ring.ringMetrics();
        assertEquals(0L, before.sqesPrepared());
        assertTrue(before.sqEntries() > 0);

        ring.prepareNop();
        ring.submit();
        ring.waitForResult().close();
        RingMetrics open = ring.ringMetrics();

        ring.close();

        RingMetrics closed = ring.ringMetrics();
        assertEquals(open.sqesPrepared(), closed.sqesPrepared());
        assertEquals(open.submitted(), closed.submitted());
        assertEquals(open.reaped(), closed.reaped());
        assertEquals(open.completed(), closed.completed());
        assertEquals(0L, closed.sqQueued(), "occupancy is reported as 0 for a closed ring");
        assertEquals(0L, closed.cqReady(), "occupancy is reported as 0 for a closed ring");
        assertEquals(open.sqEntries(), closed.sqEntries(), "effective ring depths are retained after close");
        assertEquals(open.cqEntries(), closed.cqEntries());
        assertEquals(closed, ring.ringMetrics(), "repeated snapshots of a closed ring are frozen");

        // The B1 use-after-close contract for native paths is unchanged.
        assertThrows(IllegalStateException.class, ring::prepareNop);
        assertThrows(IllegalStateException.class, ring::submit);

        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
        AtomicReference<RingMetrics> foreignSnapshot = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                foreignSnapshot.set(ring.ringMetrics());
            } catch (Throwable t) {
                foreignFailure.set(t);
            }
        });
        foreign.start();
        foreign.join(5_000L);
        assertNull(foreignFailure.get());
        assertNotNull(foreignSnapshot.get());
        assertEquals(closed, foreignSnapshot.get());
    }

    // --- test 6: cross-thread snapshots --------------------------------------

    @Test
    void crossThreadSnapshotsAreMonotoneAndNeverThrow() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong sampleCount = new AtomicLong();

        Thread sampler = new Thread(() -> {
            RingMetrics previous = null;
            try {
                while (!stop.get()) {
                    RingMetrics current = ring.ringMetrics();
                    sampleCount.incrementAndGet();
                    if (previous != null) {
                        assertMonotone(previous, current);
                    }
                    assertTrue(current.sqesPrepared() >= 0L);
                    assertTrue(current.submitted() >= 0L);
                    assertTrue(current.reaped() >= 0L);
                    assertTrue(current.inFlight() >= 0L);
                    assertTrue(current.sqQueued() >= 0L && current.sqQueued() <= current.sqEntries());
                    assertTrue(current.cqReady() >= 0L);
                    previous = current;
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "b5-ring-metrics-sampler");
        sampler.start();
        try {
            for (int round = 0; round < 200; round++) {
                for (int i = 0; i < 8; i++) {
                    ring.prepareNop();
                }
                ring.submit();
                for (int i = 0; i < 8; i++) {
                    ring.waitForResult().close();
                }
            }
        } finally {
            stop.set(true);
            sampler.join(10_000L);
        }

        assertNull(failure.get(), "cross-thread sampler failed: " + failure.get());
        assertTrue(sampleCount.get() > 0L, "the sampler must have observed at least one snapshot");

        ring.close();
        RingMetrics closed = ring.ringMetrics();
        assertEquals(0L, closed.sqQueued());
        assertEquals(0L, closed.cqReady());
        assertEquals(closed.reaped(), closed.completed() + closed.failed());
    }

    // --- close with abandonment / foreign sampler during close --------------

    @Test
    void closedRingWithAbandonedInFlightKeepsCountersConsistent() throws Exception {
        int eventFd = NativeDispatcher.C.eventfd(0, 0);
        try {
            FileDescriptor fd = new FileDescriptor(eventFd);
            // Blocking reads on an empty eventfd stay in flight; bounded close(0) may abandon
            // their completions (design §2.1: reaped == completed + failed must still hold).
            for (int i = 0; i < 4; i++) {
                ring.prepareRead(fd, 8, 0);
            }
            ring.submit();

            ring.close(0);

            RingMetrics frozen = ring.ringMetrics();
            assertEquals(frozen.reaped(), frozen.completed() + frozen.failed(),
                    "reaped must equal completed + failed after a bounded close with abandonment");
            assertEquals(0L, frozen.sqQueued());
            assertEquals(0L, frozen.cqReady());
            assertEquals(Math.max(0L, frozen.submitted() - frozen.reaped()), frozen.inFlight());
            assertInvariants(frozen);
            assertEquals(frozen, ring.ringMetrics(), "the after-close snapshot is frozen");

            AtomicReference<RingMetrics> foreign = new AtomicReference<>();
            AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    foreign.set(ring.ringMetrics());
                } catch (Throwable t) {
                    foreignFailure.set(t);
                }
            });
            reader.start();
            reader.join(5_000L);
            assertNull(foreignFailure.get());
            assertEquals(frozen, foreign.get(), "a foreign thread sees the same frozen snapshot");
        } finally {
            NativeDispatcher.C.close(eventFd);
        }
    }

    @Test
    void foreignSamplerDuringCloseNeverThrowsAndSeesFrozenSnapshot() throws Exception {
        int eventFd = NativeDispatcher.C.eventfd(0, 0);
        try {
            FileDescriptor fd = new FileDescriptor(eventFd);
            for (int i = 0; i < 4; i++) {
                ring.prepareRead(fd, 8, 0);
            }
            ring.submit();

            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicLong sampleCount = new AtomicLong();
            Thread sampler = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        RingMetrics current = ring.ringMetrics();
                        sampleCount.incrementAndGet();
                        assertTrue(current.sqQueued() >= 0L && current.sqQueued() <= current.sqEntries(),
                                "sqQueued must stay within [0, sqEntries]");
                        assertTrue(current.cqReady() >= 0L);
                        assertTrue(current.inFlight() >= 0L);
                        assertTrue(current.reaped() >= 0L);
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "b5-ring-metrics-close-sampler");
            sampler.start();
            Thread.sleep(20L);

            ring.close(0);
            Thread.sleep(20L);
            stop.set(true);
            sampler.join(10_000L);

            assertNull(failure.get(), "foreign-thread ringMetrics() during close failed: " + failure.get());
            assertTrue(sampleCount.get() > 0L);

            RingMetrics closed = ring.ringMetrics();
            assertEquals(0L, closed.sqQueued());
            assertEquals(0L, closed.cqReady());
            assertEquals(closed.reaped(), closed.completed() + closed.failed());
            assertEquals(closed, ring.ringMetrics());
        } finally {
            NativeDispatcher.C.close(eventFd);
        }
    }

    // --- test 7: zero allocation on the metrics-on hot path ------------------

    @Test
    void metricsOnHotPathDoesNotAllocate() {
        long threadId = Thread.currentThread().threadId();

        int zeroRuns = 0;
        for (int warmup = 0; warmup < 100 && zeroRuns < 3; warmup++) {
            long before = THREAD_BEAN.getThreadAllocatedBytes(threadId);
            runNopMetricsLoop(1_000);
            long after = THREAD_BEAN.getThreadAllocatedBytes(threadId);
            if (after == before) {
                zeroRuns++;
            } else {
                zeroRuns = 0;
            }
        }
        assertTrue(zeroRuns >= 3, "warmup must reach a steady state with 0 allocations per metrics-on NOP");

        long before = THREAD_BEAN.getThreadAllocatedBytes(threadId);
        runNopMetricsLoop(50_000);
        long after = THREAD_BEAN.getThreadAllocatedBytes(threadId);

        assertEquals(0L, after - before, "the metrics-instrumented hot path must not allocate");
        RingMetrics metrics = ring.ringMetrics();
        assertEquals(nopLoopOperations, metrics.reaped(), "every metrics-on NOP is still counted exactly once");
        assertEquals(0L, metrics.inFlight());
        assertInvariants(metrics);
    }

    private void runNopMetricsLoop(int iterations) {
        for (int i = 0; i < iterations; i++) {
            ring.prepareNop();
            ring.submitAndWaitForBatchResult(1, NOP_CONSUMER);
            nopLoopOperations++;
        }
    }

    // --- helpers -------------------------------------------------------------

    private static void assertInvariants(RingMetrics metrics) {
        assertEquals(metrics.reaped(), metrics.completed() + metrics.failed(),
                "reaped must equal completed + failed");
        assertTrue(metrics.cancelled() <= metrics.failed(), "cancelled must be a subset of failed");
        assertTrue(metrics.timedOut() <= metrics.failed(), "timedOut must be a subset of failed");
        assertEquals(Math.max(0L, metrics.submitted() - metrics.reaped()), metrics.inFlight(),
                "inFlight must be max(0, submitted - reaped)");
        assertTrue(metrics.sqesPrepared() >= 0L);
        assertTrue(metrics.submitted() >= 0L);
        assertTrue(metrics.reaped() >= 0L);
        assertTrue(metrics.completed() >= 0L);
        assertTrue(metrics.failed() >= 0L);
        assertTrue(metrics.enters() >= 0L);
        assertTrue(metrics.sqQueued() >= 0L && metrics.sqQueued() <= Math.max(0, metrics.sqEntries()),
                "sqQueued must be within [0, sqEntries]");
        assertTrue(metrics.cqReady() >= 0L);
        assertTrue(metrics.sqEntries() >= 0);
        assertTrue(metrics.cqEntries() >= 0);
    }

    private static void assertMonotone(RingMetrics previous, RingMetrics current) {
        assertTrue(current.sqesPrepared() >= previous.sqesPrepared(), "sqesPrepared decreased");
        assertTrue(current.submitted() >= previous.submitted(), "submitted decreased");
        assertTrue(current.reaped() >= previous.reaped(), "reaped decreased");
        assertTrue(current.completed() >= previous.completed(), "completed decreased");
        assertTrue(current.failed() >= previous.failed(), "failed decreased");
        assertTrue(current.cancelled() >= previous.cancelled(), "cancelled decreased");
        assertTrue(current.timedOut() >= previous.timedOut(), "timedOut decreased");
        assertTrue(current.enters() >= previous.enters(), "enters decreased");
        assertTrue(current.submitBatches() >= previous.submitBatches(), "submitBatches decreased");
        assertTrue(current.submitErrors() >= previous.submitErrors(), "submitErrors decreased");
        assertTrue(current.submitRetries() >= previous.submitRetries(), "submitRetries decreased");
        assertTrue(current.sqFullEvents() >= previous.sqFullEvents(), "sqFullEvents decreased");
    }

    private static MemorySegment relative(Arena arena, long nanos) {
        MemorySegment timespec = arena.allocate(JUring.TIMESPEC_BYTES, 8);
        timespec.set(JAVA_LONG, 0L, nanos / 1_000_000_000L);
        timespec.set(JAVA_LONG, 8L, nanos % 1_000_000_000L);
        return timespec;
    }
}
