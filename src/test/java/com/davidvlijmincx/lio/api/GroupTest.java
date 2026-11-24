package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RingGroup} lifecycle — stats sizes/ids, affinity base CPU,
 * bounded close with idle and in-flight work, rejection after close.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GroupTest {

    @Test
    void sizeIdsAndStatsSnapshots() {
        RingGroup group = RingGroup.create(3, ShardOptions.defaults().withName("group"));
        try {
            assertThat(group.size()).isEqualTo(3);
            for (int i = 0; i < 3; i++) {
                assertThat(group.shard(i).id()).isEqualTo(i);
            }
            List<ShardStats> stats = group.stats();
            assertThat(stats).hasSize(3);
            for (int i = 0; i < 3; i++) {
                assertThat(stats.get(i).shardId()).isEqualTo(i);
                assertThat(stats.get(i).started()).isFalse();
                assertThat(stats.get(i).closed()).isFalse();
            }
            group.start();
            assertThat(group.stats()).allSatisfy(shardStats -> {
                assertThat(shardStats.started()).isTrue();
                assertThat(shardStats.threadName()).startsWith("juring-shard-");
            });
        } finally {
            group.close(500);
        }
        assertThat(group.stats()).allSatisfy(shardStats -> assertThat(shardStats.closed()).isTrue());
        assertThatThrownBy(() -> group.execute(ctx -> {
        })).isInstanceOf(IllegalStateException.class);
        group.close(500); // idempotent
    }

    @Test
    void groupAffinityUsesBaseCpuPlusIndex() {
        // The GitHub runner exposes four vCPUs (0-3) while developer hosts usually expose
        // many more: pick an adjacent pair from the process's allowed CPUs instead of
        // hard-coding 3/4, which fails on hosts without a CPU 4.
        int[] cpus = CpuTopology.adjacentPair();
        Assumptions.assumeTrue(cpus != null, "needs two adjacent allowed CPUs to test base+index affinity");
        int base = cpus[0];
        RingGroup group = RingGroup.create(2, ShardOptions.defaults().withCpuAffinity(base));
        try {
            group.start();
            List<ShardStats> stats = group.stats();
            assertThat(stats.get(0).requestedCpu()).isEqualTo(base);
            assertThat(stats.get(1).requestedCpu()).isEqualTo(base + 1);
            assertThat(stats.get(0).affinityApplied()).isTrue();
            assertThat(stats.get(0).currentCpu()).isEqualTo(base);
            assertThat(stats.get(1).affinityApplied()).isTrue();
            assertThat(stats.get(1).currentCpu()).isEqualTo(base + 1);
        } finally {
            group.close(500);
        }
    }

    @Test
    void explicitNegativeFirstCpuDisablesAffinity() {
        RingGroup group = RingGroup.create(2, ShardOptions.defaults().withCpuAffinity(3), -1);
        try {
            group.start();
            assertThat(group.stats()).allSatisfy(stats -> {
                assertThat(stats.requestedCpu()).isEqualTo(ShardOptions.NO_CPU_AFFINITY);
                assertThat(stats.affinityApplied()).isFalse();
            });
        } finally {
            group.close(500);
        }
    }

    @Test
    void closeIdleGroupIsBounded() {
        RingGroup group = RingGroup.create(4, ShardOptions.defaults());
        group.start();
        try {
            Thread.sleep(20L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long start = System.nanoTime();
        group.close(500);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMillis).isLessThan(1500L);
        assertThat(group.stats()).allSatisfy(stats -> {
            assertThat(stats.closed()).isTrue();
            assertThat(stats.closeTimedOut()).isFalse();
        });
    }

    @Test
    void closeGroupWithInFlightWorkCancelsEveryShardBounded() throws Exception {
        RingGroup group = RingGroup.create(2, ShardOptions.defaults());
        CountDownLatch registered = new CountDownLatch(2);
        CountDownLatch handlersRan = new CountDownLatch(2);
        AtomicInteger canceledHandlers = new AtomicInteger();
        try {
            for (int shard = 0; shard < 2; shard++) {
                group.shard(shard).execute(ctx -> {
                    MemorySegment timespec = Arena.ofAuto().allocate(16L, 8L);
                    timespec.set(JAVA_LONG, 0L, 30L);
                    timespec.set(JAVA_LONG, 8L, 0L);
                    long handle = ctx.ring().prepareTimeout(timespec, 0, 0);
                    ctx.onCompletion(handle, result -> {
                        if (result.result() == -125) {
                            canceledHandlers.incrementAndGet();
                        }
                        handlersRan.countDown();
                    });
                    ctx.submit();
                    registered.countDown();
                });
            }
            assertTrue(registered.await(1, TimeUnit.SECONDS));
            long start = System.nanoTime();
            group.close(1000);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(handlersRan.await(1, TimeUnit.SECONDS), "cancellation handlers did not run");
            assertThat(canceledHandlers.get()).isEqualTo(2);
            assertThat(elapsedMillis).isLessThan(2000L);
            long cancelled = group.stats().stream().mapToLong(ShardStats::cancelledAtClose).sum();
            assertThat(cancelled).isGreaterThanOrEqualTo(2);
            assertThat(group.stats()).allSatisfy(stats -> assertThat(stats.closeTimedOut()).isFalse());
        } finally {
            group.close(1000);
        }
    }

    @Test
    void groupAwaitTerminationAfterRequestStop() {
        RingGroup group = RingGroup.create(2, ShardOptions.defaults());
        try {
            group.start();
            group.requestStop();
            assertThat(group.isStopping()).isTrue();
            assertThat(group.awaitTermination(2000L)).isTrue();
        } finally {
            group.close(500);
        }
    }
}
