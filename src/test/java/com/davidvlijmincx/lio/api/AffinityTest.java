package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CPU affinity is applied on the shard thread and reported through
 * {@link ShardStats}; invalid CPUs are a non-fatal, recorded best-effort failure.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AffinityTest {

    @Test
    void shardPinsToRequestedCpuAndReportsIt() {
        // Pick a CPU from the process's allowed set: developer hosts and CI runners expose
        // different ids/counts (the runner has four vCPUs, 0-3).
        int cpu = CpuTopology.firstCpu();
        Assumptions.assumeTrue(cpu >= 0, "needs a discoverable allowed CPU");
        IoShard shard = IoShard.create(0, ShardOptions.defaults().withCpuAffinity(cpu));
        try {
            shard.start();
            assertThat(shard.shardThread()).isNotNull();
            ShardStats stats = shard.stats();
            // Affinity is best effort (cgroups/cpusets may deny it); pinning to an allowed
            // CPU makes the assertion test the contract rather than the host layout.
            assertThat(stats.requestedCpu()).isEqualTo(cpu);
            assertThat(stats.affinityApplied())
                    .as("sched_setaffinity(CPU %d) expected to succeed for an allowed CPU", cpu)
                    .isTrue();
            assertThat(stats.currentCpu()).isEqualTo(cpu);
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
