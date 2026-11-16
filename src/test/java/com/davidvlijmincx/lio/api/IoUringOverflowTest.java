package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
