package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 design §12.2: CPU affinity is applied on the shard thread and reported through
 * {@link ShardStats}; invalid CPUs are a non-fatal, recorded best-effort failure.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class B4AffinityTest {

    @Test
    void shardPinsToRequestedCpuAndReportsIt() {
        IoShard shard = IoShard.create(0, ShardOptions.defaults().withCpuAffinity(3));
        try {
            shard.start();
            assertThat(shard.shardThread()).isNotNull();
            ShardStats stats = shard.stats();
            // The B4 design probe (scratch/b4-lead/AffinityProbe) proved rc=0 for CPU 3 on
            // this host; if a cgroup ever denies it the assertion records the environment
            // change rather than silently weakening the contract.
            assertThat(stats.requestedCpu()).isEqualTo(3);
            assertThat(stats.affinityApplied())
                    .as("sched_setaffinity(CPU 3) expected to succeed (design §1 probe)")
                    .isTrue();
            assertThat(stats.currentCpu()).isEqualTo(3);
            assertThat(shard.shardThread().getName()).startsWith("juring-shard-0-");
        } finally {
            shard.close();
        }
    }

    @Test
    void outOfRangeCpuIsRecordedNonFatally() {
        IoShard shard = IoShard.create(1, ShardOptions.defaults().withCpuAffinity(9999));
        try {
            shard.start();
            assertThat(shard.isStarted()).isTrue();
            ShardStats stats = shard.stats();
            assertThat(stats.requestedCpu()).isEqualTo(9999);
            assertThat(stats.affinityApplied()).isFalse();
            // sched_getcpu() is still recorded so operators can see where the thread runs.
            assertThat(stats.currentCpu()).isGreaterThanOrEqualTo(-1);
        } finally {
            shard.close();
        }
    }

    @Test
    void noAffinityIsRecordedAsNotApplied() {
        IoShard shard = IoShard.create(2, ShardOptions.defaults());
        try {
            shard.start();
            assertThat(shard.stats().requestedCpu()).isEqualTo(ShardOptions.NO_CPU_AFFINITY);
            assertThat(shard.stats().affinityApplied()).isFalse();
        } finally {
            shard.close();
        }
    }
}
