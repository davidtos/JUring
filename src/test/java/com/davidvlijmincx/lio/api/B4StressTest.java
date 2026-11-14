package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §12.12: 10k mixed operations across 4 shards with tasks and handoffs — no loss,
 * no hang, exactly one dispatch per operation.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class B4StressTest {

    @Test
    void tenThousandMixedOperationsAcrossFourShards() throws Exception {
        int shards = 4;
        int operations = 10_000;
        int handoffs = 8;
        RingGroup group = RingGroup.create(shards, ShardOptions.defaults().withRingEntries(256));
        try {
            CountDownLatch completions = new CountDownLatch(operations);
            AtomicInteger dispatched = new AtomicInteger();
            AtomicInteger handoffsRan = new AtomicInteger();
            CountDownLatch allHandoffs = new CountDownLatch(handoffs);

            for (int i = 0; i < operations; i++) {
                final int index = i;
                group.execute(ctx -> {
                    long handle = ctx.ring().prepareNop();
                    ctx.onCompletion(handle, result -> {
                        dispatched.incrementAndGet();
                        completions.countDown();
                    });
                    // A few completion-path handoffs to another shard (round-robin target).
                    if (index % (operations / handoffs) == 0) {
                        IoShard target = group.shard((ctx.shardId() + 1) % shards);
                        ctx.handoff(target, () -> {
                            handoffsRan.incrementAndGet();
                            allHandoffs.countDown();
                        });
                    }
                    ctx.submit();
                });
            }

            assertTrue(completions.await(20, TimeUnit.SECONDS), "stress burst did not complete");
            assertTrue(allHandoffs.await(5, TimeUnit.SECONDS), "handoffs did not complete");
            assertThat(dispatched.get()).isEqualTo(operations);
            assertThat(handoffsRan.get()).isEqualTo(handoffs);
            long submitted = group.stats().stream().mapToLong(ShardStats::tasksSubmitted).sum();
            long executed = group.stats().stream().mapToLong(ShardStats::tasksExecuted).sum();
            long completionDispatches = group.stats().stream().mapToLong(ShardStats::completionsDispatched).sum();
            assertThat(submitted).isEqualTo(operations);
            assertThat(executed).isEqualTo(operations);
            assertThat(completionDispatches).isEqualTo(operations);
            assertThat(group.stats().stream().mapToLong(ShardStats::taskFailures).sum()).isZero();
            assertThat(group.stats().stream().mapToLong(ShardStats::completionFailures).sum()).isZero();
        } finally {
            long start = System.nanoTime();
            group.close(2000);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertThat(elapsedMillis).as("stress group close must be bounded").isLessThan(3000L);
        }
        assertThat(group.stats()).allSatisfy(stats -> {
            assertThat(stats.closed()).isTrue();
            assertThat(stats.closeTimedOut()).isFalse();
            assertThat(stats.inFlightRequests()).isZero();
        });
    }
}
