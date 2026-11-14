package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §12.15: {@link ShardStats} invariants — counters are monotone, non-negative and
 * {@code inFlightRequests} returns to 0 after draining (excluding the internal wakeup read).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class B4StatsTest {

    @Test
    void countersAreMonotoneAndInFlightDrainsToZero() throws Exception {
        IoShard shard = IoShard.create(0, ShardOptions.defaults().withRingEntries(64));
        try {
            shard.start();
            runNops(shard, 50);
            ShardStats first = shard.stats();
            runNops(shard, 50);
            ShardStats second = shard.stats();

            assertThat(second.tasksSubmitted()).isGreaterThanOrEqualTo(first.tasksSubmitted());
            assertThat(second.tasksExecuted()).isGreaterThanOrEqualTo(first.tasksExecuted());
            assertThat(second.completionsRegistered()).isGreaterThanOrEqualTo(first.completionsRegistered());
            assertThat(second.completionsDispatched()).isGreaterThanOrEqualTo(first.completionsDispatched());
            assertThat(second.eventfdWrites()).isGreaterThanOrEqualTo(first.eventfdWrites());
            assertThat(second.ringSubmits()).isGreaterThanOrEqualTo(first.ringSubmits());
            assertThat(second.completionsDispatched()).isEqualTo(100);
            assertThat(second.completionsUnattributed()).isZero();

            // Once idle, the loop arms its internal wakeup read. inFlightRequests must still be 0
            // because the wakeup READ is excluded from the user-facing count.
            waitUntil(() -> shard.stats().wakeupArmed() && shard.stats().inFlightRequests() == 0, 2000);
            ShardStats idle = shard.stats();
            assertThat(idle.wakeupArmed()).isTrue();
            assertThat(idle.inFlightRequests()).isZero();
            assertThat(idle.tasksSubmitted()).isEqualTo(2); // two tasks, 100 NOPs total
            assertThat(idle.tasksExecuted()).isEqualTo(2);
        } finally {
            shard.close();
        }
        ShardStats afterClose = shard.stats();
        assertThat(afterClose.inFlightRequests()).isZero();
        assertThat(afterClose.closed()).isTrue();
        assertThat(afterClose.started()).isFalse();
        assertThat(afterClose.closeTimedOut()).isFalse();
    }

    @Test
    void everyCountedStatisticIsNonNegative() throws Exception {
        IoShard shard = IoShard.create(1, ShardOptions.defaults());
        try {
            runNops(shard, 10);
            ShardStats stats = shard.stats();
            assertThat(stats.tasksSubmitted()).isNotNegative();
            assertThat(stats.tasksExecuted()).isNotNegative();
            assertThat(stats.taskFailures()).isNotNegative();
            assertThat(stats.tasksRejected()).isNotNegative();
            assertThat(stats.completionsRegistered()).isNotNegative();
            assertThat(stats.completionsDispatched()).isNotNegative();
            assertThat(stats.completionsUnattributed()).isNotNegative();
            assertThat(stats.completionFailures()).isNotNegative();
            assertThat(stats.handoffsSent()).isNotNegative();
            assertThat(stats.handoffsInlineFallback()).isNotNegative();
            assertThat(stats.eventfdWrites()).isNotNegative();
            assertThat(stats.ringSubmits()).isNotNegative();
            assertThat(stats.inFlightRequests()).isNotNegative();
            assertThat(stats.cancelledAtClose()).isNotNegative();
            assertThat(stats.abandonedAtClose()).isNotNegative();
        } finally {
            shard.close();
        }
    }

    private static void runNops(IoShard shard, int operations) throws Exception {
        CountDownLatch done = new CountDownLatch(operations);
        AtomicInteger dispatched = new AtomicInteger();
        shard.execute(ctx -> {
            for (int i = 0; i < operations; i++) {
                long handle = ctx.ring().prepareNop();
                ctx.onCompletion(handle, result -> {
                    dispatched.incrementAndGet();
                    done.countDown();
                });
            }
            ctx.submit();
        });
        assertTrue(done.await(2, TimeUnit.SECONDS), "NOP burst did not complete");
        assertThat(dispatched.get()).isEqualTo(operations);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(1L);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition not met within " + timeoutMillis + " ms");
        }
    }
}
