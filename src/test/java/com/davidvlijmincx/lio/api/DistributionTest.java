package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-robin and hash distribution, stable {@code shardFor(key)},
 * {@code floorMod} edge keys, single-shard group.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DistributionTest {

    @Test
    void roundRobinCoversAllShardsEvenly() {
        RingGroup group = RingGroup.create(4, ShardOptions.defaults());
        int[] counts = new int[4];
        for (int i = 0; i < 400; i++) {
            counts[group.nextShard().id()]++;
        }
        assertThat(counts).containsExactly(100, 100, 100, 100);
    }

    @Test
    void hashIsStableForAKeySetIncludingFloorModEdgeCases() {
        RingGroup group = RingGroup.create(4, ShardOptions.defaults());
        long[] keys = {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 42L, 123456789L, -9999999999L};
        for (long key : keys) {
            int first = group.shardFor(key).id();
            assertThat(first).isBetween(0, 3);
            for (int i = 0; i < 10; i++) {
                assertThat(group.shardFor(key).id())
                        .as("shardFor(%d) must be stable", key)
                        .isEqualTo(first);
            }
        }
    }

    @Test
    void hashDistributionCoversShards() {
        RingGroup group = RingGroup.create(4, ShardOptions.defaults());
        boolean[] seen = new boolean[4];
        for (long key = 0; key < 1000; key++) {
            seen[group.shardFor(key).id()] = true;
        }
        assertThat(seen).containsOnly(true);
    }

    @Test
    void singleShardGroupAlwaysMapsToShardZero() {
        RingGroup group = RingGroup.create(1, ShardOptions.defaults());
        assertThat(group.size()).isEqualTo(1);
        assertThat(group.nextShard().id()).isZero();
        assertThat(group.shardFor(0L).id()).isZero();
        assertThat(group.shardFor(Long.MIN_VALUE).id()).isZero();
        assertThat(group.shardFor(Long.MAX_VALUE).id()).isZero();
    }

    @Test
    void executeRoundRobinDistributesTasksEvenlyAcrossShards() throws Exception {
        RingGroup group = RingGroup.create(4, ShardOptions.defaults());
        try {
            int perShard = 10;
            int total = 4 * perShard;
            CountDownLatch done = new CountDownLatch(total);
            ConcurrentHashMap<Integer, AtomicInteger> perShardCounts = new ConcurrentHashMap<>();
            for (int i = 0; i < total; i++) {
                group.execute(ctx -> {
                    perShardCounts.computeIfAbsent(ctx.shardId(), id -> new AtomicInteger()).incrementAndGet();
                    done.countDown();
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertThat(perShardCounts).hasSize(4);
            for (int shard = 0; shard < 4; shard++) {
                assertThat(perShardCounts.get(shard).get()).isEqualTo(perShard);
            }
        } finally {
            group.close(500);
        }
    }

    @Test
    void executeByKeyAlwaysTargetsTheSameShard() throws Exception {
        RingGroup group = RingGroup.create(4, ShardOptions.defaults());
        try {
            long key = 0xDEADBEEFL;
            int expected = group.shardFor(key).id();
            int count = 8;
            CountDownLatch done = new CountDownLatch(count);
            AtomicInteger wrong = new AtomicInteger();
            for (int i = 0; i < count; i++) {
                group.execute(key, ctx -> {
                    if (ctx.shardId() != expected) {
                        wrong.incrementAndGet();
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertThat(wrong.get()).isZero();
        } finally {
            group.close(500);
        }
    }

    @Test
    void shardIndexAndCountValidation() {
        RingGroup group = RingGroup.create(2, ShardOptions.defaults());
        assertThatThrownBy(() -> group.shard(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> group.shard(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RingGroup.create(0, ShardOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RingGroup.create(1025, ShardOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        group.close(100);
    }
}
