package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §12.7/§12.8: cross-thread completion handoff (idle wake, FIFO, counters,
 * completion→handoff continuation) and handoff overflow inline fallback (never blocks,
 * never drops).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class B4HandoffTest {

    @Test
    void handoffWakesAnIdleTargetAndRunsOnItsThread() throws Exception {
        IoShard target = IoShard.create(0, ShardOptions.defaults());
        try {
            target.start();
            Thread.sleep(50L); // let the target reach the kernel-blocked idle state
            CountDownLatch ran = new CountDownLatch(1);
            AtomicReference<Thread> continuationThread = new AtomicReference<>();
            target.handoff(() -> {
                continuationThread.set(Thread.currentThread());
                ran.countDown();
            });
            assertTrue(ran.await(1, TimeUnit.SECONDS), "idle target was not woken by the handoff");
            assertThat(continuationThread.get()).isSameAs(target.shardThread());
            assertThat(target.stats().handoffsSent()).isEqualTo(1);
        } finally {
            target.close();
        }
    }

    @Test
    void handoffContinuationsRunInFifoOrder() throws Exception {
        int count = 100;
        IoShard target = IoShard.create(1, ShardOptions.defaults());
        try {
            target.start();
            int[] received = new int[count];
            AtomicInteger position = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(count);
            for (int i = 0; i < count; i++) {
                final int sequence = i;
                target.handoff(() -> {
                    int slot = position.getAndIncrement();
                    if (slot < received.length) {
                        received[slot] = sequence;
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS), "not all handoffs ran");
            for (int i = 0; i < count; i++) {
                assertThat(received[i]).as("handoff position %d", i).isEqualTo(i);
            }
        } finally {
            target.close();
        }
    }

    @Test
    void completionOnOneShardHandsOffToAnother() throws Exception {
        IoShard shardA = IoShard.create(0, ShardOptions.defaults());
        IoShard shardB = IoShard.create(1, ShardOptions.defaults());
        try {
            shardB.start();
            CountDownLatch continuationRan = new CountDownLatch(1);
            AtomicReference<Thread> continuationThread = new AtomicReference<>();
            CountDownLatch taskRan = new CountDownLatch(1);
            shardA.execute(ctx -> {
                long handle = ctx.ring().prepareNop();
                ctx.onCompletion(handle, result -> ctx.handoff(shardB, () -> {
                    continuationThread.set(Thread.currentThread());
                    continuationRan.countDown();
                }));
                ctx.submit();
                taskRan.countDown();
            });
            assertTrue(taskRan.await(1, TimeUnit.SECONDS));
            assertTrue(continuationRan.await(1, TimeUnit.SECONDS), "cross-shard handoff did not run");
            assertThat(continuationThread.get()).isSameAs(shardB.shardThread());
            assertThat(shardB.stats().handoffsSent()).isEqualTo(1);
            assertThat(shardA.stats().completionsDispatched()).isGreaterThanOrEqualTo(1);
        } finally {
            shardA.close();
            shardB.close();
        }
    }

    @Test
    void handoffOverflowFallsBackInlineWithoutLoss() throws Exception {
        IoShard target = IoShard.create(2, ShardOptions.defaults().withInboundCapacity(2));
        try {
            // Occupy the single shard thread so its inbound queue (capacity 2) fills up.
            CountDownLatch slowStarted = new CountDownLatch(1);
            target.execute(ctx -> {
                slowStarted.countDown();
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));

            int continuations = 50;
            AtomicInteger executed = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(continuations);
            for (int i = 0; i < continuations; i++) {
                target.handoff(() -> {
                    executed.incrementAndGet();
                    done.countDown();
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS), "handoff overflow dropped continuations");
            assertThat(executed.get()).isEqualTo(continuations);
            ShardStats stats = target.stats();
            assertThat(stats.handoffsInlineFallback())
                    .as("queue capacity 2 with a busy consumer must force inline fallbacks")
                    .isPositive();
            waitUntil(() -> target.stats().handoffsSent() + target.stats().handoffsInlineFallback() == continuations, 1000);
            assertThat(stats.handoffsSent() + stats.handoffsInlineFallback()).isEqualTo(continuations);
        } finally {
            target.close();
        }
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
