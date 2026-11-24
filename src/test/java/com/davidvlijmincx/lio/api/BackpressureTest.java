package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backpressure policy: foreign {@code execute} blocks while the
 * inbound queue is full (then rejects after stop) and a shard-thread {@code execute} that
 * would block on itself throws {@link IllegalStateException} and is counted.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BackpressureTest {

    @Test
    void tinyInboundBlocksForeignProducersButLosesNothing() throws Exception {
        IoShard shard = IoShard.create(0, ShardOptions.defaults().withInboundCapacity(2));
        try {
            CountDownLatch slowStarted = new CountDownLatch(1);
            AtomicInteger executed = new AtomicInteger();
            shard.execute(ctx -> {
                slowStarted.countDown();
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executed.incrementAndGet();
            });
            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));

            int producers = 4;
            int perProducer = 25;
            int total = producers * perProducer;
            CountDownLatch done = new CountDownLatch(total);
            Thread[] threads = new Thread[producers];
            for (int p = 0; p < producers; p++) {
                threads[p] = Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < perProducer; i++) {
                        shard.execute(ctx -> {
                            executed.incrementAndGet();
                            done.countDown();
                        });
                    }
                });
            }
            for (Thread thread : threads) {
                thread.join(3000);
            }
            assertTrue(done.await(3, TimeUnit.SECONDS), "blocked producers lost tasks");
            assertThat(executed.get()).isEqualTo(total + 1);
            assertThat(shard.stats().tasksRejected()).isZero();
            assertThat(shard.stats().tasksExecuted()).isEqualTo(total + 1);
        } finally {
            shard.close();
        }
    }

    @Test
    void shardThreadExecuteOnFullQueueThrowsAndIsCounted() throws Exception {
        IoShard shard = IoShard.create(1, ShardOptions.defaults().withInboundCapacity(2));
        try {
            CountDownLatch taskStarted = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            shard.execute(ctx -> {
                taskStarted.countDown();
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    ctx.execute(inner -> {
                    });
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(taskStarted.await(1, TimeUnit.SECONDS));
            // Fill the capacity-2 inbound queue while the first task is still running.
            CountDownLatch twoMore = new CountDownLatch(2);
            shard.execute(ctx -> twoMore.countDown());
            shard.execute(ctx -> twoMore.countDown());
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertThat(twoMore.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(thrown.get()).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.get()).hasMessageContaining("full");
            assertThat(shard.stats().tasksRejected()).isGreaterThanOrEqualTo(1);
        } finally {
            shard.close();
        }
    }

    @Test
    void blockedForeignExecuteRejectsWithIseAfterRequestStop() throws Exception {
        IoShard shard = IoShard.create(2, ShardOptions.defaults().withInboundCapacity(2));
        try {
            CountDownLatch slowStarted = new CountDownLatch(1);
            shard.execute(ctx -> {
                slowStarted.countDown();
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
            // Fill the queue: the next execute has to block.
            shard.execute(ctx -> {
            });
            shard.execute(ctx -> {
            });
            AtomicReference<Throwable> blockedResult = new AtomicReference<>();
            CountDownLatch blockedReturned = new CountDownLatch(1);
            Thread blockedProducer = Thread.ofPlatform().start(() -> {
                try {
                    shard.execute(ctx -> {
                    });
                } catch (Throwable t) {
                    blockedResult.set(t);
                } finally {
                    blockedReturned.countDown();
                }
            });
            Thread.sleep(50L); // ensure the producer is parked in backpressure
            shard.requestStop();
            assertTrue(blockedReturned.await(1, TimeUnit.SECONDS),
                    "blocked foreign execute did not return after requestStop");
            assertThat(blockedResult.get()).isInstanceOf(IllegalStateException.class);
            blockedProducer.join(1000);
        } finally {
            shard.close();
        }
    }

    @Test
    void nullArgumentsAreRejected() {
        IoShard shard = IoShard.create(3, ShardOptions.defaults());
        try {
            assertThatThrownBy(() -> shard.execute(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> shard.handoff(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> shard.call(null)).isInstanceOf(NullPointerException.class);
        } finally {
            shard.close();
        }
    }
}
