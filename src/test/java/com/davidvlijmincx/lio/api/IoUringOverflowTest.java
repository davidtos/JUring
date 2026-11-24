package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the CQ-ring overflow path. With a depth-8 ring the CQ holds 16 entries;
 * submitting three bursts of eight NOPs makes the kernel park the remaining completions in its
 * CQ-overflow list. Before the fix the library never flushed that list: reaping stopped after
 * 16 completions, {@code waitForBatchResult(1)} hung forever and the remaining work was stuck
 * in flight.
 *
 * <p>Each test is bounded by a {@link Timeout} so a regression fails instead of hanging the
 * build. No file I/O is required; NOP completions exercise the same CQ accounting as real I/O.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class IoUringOverflowTest {

    private static final int QUEUE_DEPTH = 8;
    private static final int BURSTS = 3;
    private static final int TOTAL = QUEUE_DEPTH * BURSTS;

    private static void submitBursts(JUring ring) {
        for (int burst = 0; burst < BURSTS; burst++) {
            for (int i = 0; i < QUEUE_DEPTH; i++) {
                ring.prepareNop();
            }
            ring.submit();
        }
    }

    @Test
    void emptyPeekFlushesOverflowAndReapsEveryCompletion() {
        try (JUring ring = new JUring(QUEUE_DEPTH)) {
            submitBursts(ring);

            int reaped = 0;
            // A single peek sees only the 16 visible CQEs; the next peek must flush the
            // kernel overflow list instead of reporting "nothing available".
            for (int pass = 0; pass < 8 && ring.ringMetrics().inFlight() > 0; pass++) {
                reaped += ring.forEachBatchResult(1024, result -> { });
            }

            assertEquals(TOTAL, reaped, "all NOP completions must be visible after overflow flush");
            assertEquals(0L, ring.ringMetrics().inFlight(), "no work may remain stuck in flight");
            assertTrue(ring.ringMetrics().cqFlushEnters() > 0,
                    "the empty-peek path must have issued at least one non-blocking flush enter");
        }
    }

    @Test
    void blockingWaitAfterPartialReapDoesNotHang() {
        try (JUring ring = new JUring(QUEUE_DEPTH)) {
            submitBursts(ring);

            int first = ring.forEachBatchResult(1024, result -> { });
            assertEquals(16, first, "the visible CQ holds exactly 16 entries");

            // Pre-fix this call never returned: the remaining 8 completions were parked in
            // the kernel overflow list and waitForBatchResult(1) blocked on min_complete=1.
            int second = ring.waitForBatchResult(1, result -> { });
            assertEquals(8, second, "the overflow flush must expose the remaining completions");
            assertEquals(0L, ring.ringMetrics().inFlight());
        }
    }

    @Test
    void submitAndWaitReturnsVisibleBatchAndOverflowRemainsDrainable() {
        try (JUring ring = new JUring(QUEUE_DEPTH)) {
            submitBursts(ring);

            AtomicInteger reaped = new AtomicInteger();
            int first = ring.submitAndWaitForBatchResult(1, result -> reaped.incrementAndGet());
            assertEquals(16, first, "the visible CQ batch is reaped first");
            assertEquals(16, reaped.get());

            int total = first;
            for (int pass = 0; pass < 8 && ring.ringMetrics().inFlight() > 0; pass++) {
                total += ring.forEachBatchResult(1024, result -> { });
            }
            assertEquals(TOTAL, total);
            assertEquals(0L, ring.ringMetrics().inFlight());
        }
    }

    @Test
    void deepOverflowNeedsRepeatedFlushesAndIsFullyDrained() {
        // 100 completions on a depth-8 ring = 16 CQ slots + 84 overflow-list entries. A single
        // flush exposes only one CQ-worth of entries, so the risk condition must stay true while
        // outstanding work exceeds the CQ depth; otherwise a non-blocking consumer stops at 32.
        try (JUring ring = new JUring(QUEUE_DEPTH)) {
            int total = 100;
            int prepared = 0;
            while (prepared < total) {
                ring.prepareNop();
                prepared++;
                if (prepared % QUEUE_DEPTH == 0) {
                    ring.submit();
                }
            }
            ring.submit();

            int reaped = 0;
            for (int pass = 0; pass < 200 && ring.ringMetrics().inFlight() > 0; pass++) {
                reaped += ring.forEachBatchResult(1024, result -> { });
            }

            assertEquals(total, reaped, "deep overflow must be drained by repeated non-blocking peeks");
            assertEquals(0L, ring.ringMetrics().inFlight());
        }
    }

    @Test
    void blockingWaitDoesNotBusySpinWhenOverflowRiskIsSticky() {
        // 5 immediately-completing NOPs + 12 x 1.5 s timeouts = 17 in flight on a CQ of 16,
        // so overflow risk is latched while only 5 completions are visible. waitForBatchResult
        // must block in enter(GETEVENTS) and not busy-spin issuing non-blocking flushes.
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        try (JUring ring = new JUring(QUEUE_DEPTH);
             java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment timespec = arena.allocate(JUring.TIMESPEC_BYTES, 8);
            timespec.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0L, 1L);
            timespec.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8L, 1_500_000_000L);
            // SQ depth is 8, so submit in batches; 17 requests total puts us above the CQ's 16.
            for (int i = 0; i < 5; i++) {
                ring.prepareNop();
            }
            for (int i = 0; i < 3; i++) {
                ring.prepareTimeout(timespec);
            }
            ring.submit();
            for (int i = 0; i < 8; i++) {
                ring.prepareTimeout(timespec);
            }
            ring.submit();
            ring.prepareTimeout(timespec);
            ring.submit();

            long cpuBefore = bean.getCurrentThreadCpuTime();
            long start = System.nanoTime();
            // Deliberately the List overload: it is the waitCqeNr path that busy-spun. The
            // consumer overload uses waitForBatchResult(min, consumer) and would not guard it.
            java.util.List<Result> results = ring.waitForBatchResult(16);
            long wallNanos = System.nanoTime() - start;
            long cpuNanos = bean.getCurrentThreadCpuTime() - cpuBefore;

            assertEquals(16, results.size());
            for (Result result : results) {
                result.close();
            }
            assertTrue(wallNanos >= 1_200_000_000L, "the wait must actually block for the timeouts");
            assertTrue(cpuNanos < 600_000_000L,
                    () -> "blocking wait burned CPU (busy-spin): cpu=" + (cpuNanos / 1_000_000) + " ms, wall="
                            + (wallNanos / 1_000_000) + " ms");
        }
    }

    @Test
    void multishotTimeoutIsRejectedBeforeConsumingAnSqe() {
        try (JUring ring = new JUring(QUEUE_DEPTH);
             java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment timespec = arena.allocate(JUring.TIMESPEC_BYTES, 8);
            timespec.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0L, 1L);
            timespec.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8L, 1_000_000L);

            assertThrows(IllegalArgumentException.class,
                    () -> ring.prepareTimeout(timespec, 1, JUring.IORING_TIMEOUT_MULTISHOT));
            assertEquals(0L, ring.ringMetrics().inFlight(),
                    "a rejected multishot timeout must not consume an SQE/in-flight slot");
        }
    }

    @Test
    void minCompleteBeyondCqSizeIsRejectedInsteadOfHanging() {
        try (JUring ring = new JUring(QUEUE_DEPTH)) {
            int tooMany = QUEUE_DEPTH * 2 + 1;
            assertThrows(IllegalArgumentException.class, () -> ring.submitAndWait(tooMany));
            assertThrows(IllegalArgumentException.class, () -> ring.waitForBatchResult(tooMany, result -> { }));
            assertThrows(IllegalArgumentException.class, () -> ring.submitAndWaitForBatchResult(tooMany, result -> { }));
        }
    }

    @Test
    void singleResultWaitSeesOverflowCompletions() {
        try (JUring ring = new JUring(QUEUE_DEPTH)) {
            submitBursts(ring);

            ring.forEachBatchResult(1024, result -> { });
            // waitForResult() uses the waitCqe path, which must also flush before blocking.
            try (Result first = ring.waitForResult()) {
                assertTrue(first.result() >= 0, "NOP completion should succeed");
            }
            try (Result second = ring.waitForResult()) {
                assertTrue(second.result() >= 0);
            }
            assertTrue(ring.ringMetrics().inFlight() > 0,
                    "only two of the eight overflow completions were reaped explicitly");
        }
    }

    @Test
    void boundedCloseDoesNotAbandonOverflowCompletions() {
        JUring ring = new JUring(QUEUE_DEPTH);
        try {
            submitBursts(ring);
            ring.forEachBatchResult(1024, result -> { });
        } finally {
            ring.close(500);
        }
        assertEquals(0, ring.abandonedAtClose(),
                "overflow completions must be drained, not abandoned, by bounded close");
    }
}
