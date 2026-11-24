package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-producer FIFO ordering and no task loss for bursts from one thread
 * (cross-producer total order is unspecified by contract).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OrderingTest {

    @Test
    void perProducerFifoOrderAndAllTasksExecuted() throws Exception {
        int tasks = 200;
        IoShard shard = IoShard.create(0, ShardOptions.defaults().withTaskBatch(8));
        try {
            int[] received = new int[tasks];
            AtomicInteger position = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(tasks);
            for (int i = 1; i <= tasks; i++) {
                final int sequence = i;
                shard.execute(ctx -> {
                    int slot = position.getAndIncrement();
                    if (slot < received.length) {
                        received[slot] = sequence;
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS), "not all tasks ran");
            for (int i = 0; i < tasks; i++) {
                assertThat(received[i]).as("task position %d", i).isEqualTo(i + 1);
            }
            ShardStats stats = shard.stats();
            assertThat(stats.tasksSubmitted()).isEqualTo(tasks);
            assertThat(stats.tasksExecuted()).isEqualTo(tasks);
            assertThat(stats.taskFailures()).isZero();
        } finally {
            shard.close();
        }
    }

    @Test
    void multipleProducersLoseNoTasks() throws Exception {
        int producers = 4;
        int perProducer = 100;
        IoShard shard = IoShard.create(1, ShardOptions.defaults().withInboundCapacity(128));
        try {
            CountDownLatch done = new CountDownLatch(producers * perProducer);
            AtomicInteger ran = new AtomicInteger();
            Thread[] threads = new Thread[producers];
            for (int p = 0; p < producers; p++) {
                threads[p] = Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < perProducer; i++) {
                        shard.execute(ctx -> {
                            ran.incrementAndGet();
                            done.countDown();
                        });
                    }
                });
            }
            for (Thread thread : threads) {
                thread.join(2000);
            }
            assertTrue(done.await(2, TimeUnit.SECONDS), "not all tasks ran");
            assertThat(ran.get()).isEqualTo(producers * perProducer);
            assertThat(shard.stats().tasksExecuted()).isEqualTo(producers * perProducer);
            assertThat(shard.stats().tasksRejected()).isZero();
        } finally {
            shard.close();
        }
    }
}
