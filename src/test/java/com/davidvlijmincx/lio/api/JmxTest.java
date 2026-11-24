package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JMX runtime tests: standalone and group registration
 * (deterministic ObjectNames, pull-based attributes matching the snapshots, microsecond
 * latency units), duplicate registration, idempotent close, mid-flight/after-close reads,
 * group rollback on a partial failure, and no thread/fd leak across cycles.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class JmxTest {

    private static final MBeanServer SERVER = ManagementFactory.getPlatformMBeanServer();

    private static final String[] SHARD_ATTRIBUTES = {
            "SqesPrepared", "Submitted", "Reaped", "Completed", "Failed", "Cancelled",
            "TimedOut", "InFlight", "Enters", "SubmitBatches", "SubmitErrors", "SubmitRetries",
            "SqFullEvents", "SqQueued", "SqEntries", "CqReady", "CqEntries", "CqOverflowEvents",
            "TasksSubmitted", "TasksExecuted", "TaskFailures", "TasksRejected",
            "CompletionsRegistered", "CompletionsDispatched", "CompletionsUnattributed",
            "CompletionFailures", "HandoffsSent", "HandoffsInlineFallback", "EventfdWrites",
            "RingSubmits", "CancelledAtClose", "AbandonedAtClose", "CloseTimedOut",
            "LatencySamples", "LatencyMeanMicros", "LatencyP50Micros", "LatencyP90Micros",
            "LatencyP99Micros", "LatencyP999Micros", "LatencyMaxMicros", "LatencyMinMicros",
            "ShardId", "ThreadName", "CurrentCpu", "AffinityApplied", "Started", "Stopping",
            "Closed"
    };

    private static final String[] GROUP_ATTRIBUTES = {
            "Shards", "Submitted", "Reaped", "Completed", "Failed", "Cancelled", "TimedOut",
            "InFlight", "SqQueued", "CqReady", "LatencySamples", "LatencyMeanMicros",
            "LatencyP50Micros", "LatencyP90Micros", "LatencyP99Micros", "LatencyP999Micros",
            "LatencyMaxMicros", "LatencyMinMicros"
    };

    // ------------------------------------------------------------------
    // register(IoShard)
    // ------------------------------------------------------------------

    @Test
    void standaloneShardBeanMatchesSnapshotsAndLifecycle() throws Exception {
        IoShard shard = IoShard.create(3, ShardOptions.defaults()
                .withRingEntries(128)
                .withLatencySampleEvery(1));
        MetricsJmx.Handle handle = null;
        try {
            shard.start();
            runNops(shard, 32);
            shard.close(1_000L);
            ShardMetrics expected = shard.metrics();

            handle = MetricsJmx.register(shard);
            ObjectName name = handle.name();
            assertThat(handle.names()).containsExactly(name);
            assertThat(name.getDomain()).isEqualTo(MetricsJmx.DOMAIN);
            assertThat(name.getKeyProperty("type")).isEqualTo("IoShard");
            assertThat(name.getKeyProperty("id")).isEqualTo("3");
            assertThat(name.getKeyProperty("instance")).isNotBlank();
            assertThat(SERVER.isRegistered(name)).isTrue();
            assertThat(SERVER.getMBeanInfo(name).getClassName()).isNotBlank();
            assertThat(SERVER.getMBeanInfo(name).getAttributes()).isNotEmpty();

            assertThat(((Number) attr(name, "ShardId")).intValue()).isEqualTo(3);
            assertThat(attr(name, "ThreadName")).isEqualTo(expected.stats().threadName());
            assertThat(((Number) attr(name, "CurrentCpu")).intValue())
                    .isEqualTo(expected.stats().currentCpu());
            assertThat(attr(name, "AffinityApplied")).isEqualTo(expected.stats().affinityApplied());
            assertThat(attr(name, "Started")).isEqualTo(false);
            assertThat(attr(name, "Closed")).isEqualTo(true);
            assertThat(attr(name, "CloseTimedOut")).isEqualTo(false);
            assertRingAttributes(name, expected.ring());
            assertShardCounterAttributes(name, expected.stats());
            assertLatencyAttributes(name, expected.dispatchLatency());
            // Reads after shard close must stay OK (this whole block reads a closed shard).
            assertThat(((Number) attr(name, "Submitted")).longValue())
                    .isGreaterThanOrEqualTo(32L);

            // Idempotent close + unregistration.
            handle.close();
            assertThat(SERVER.isRegistered(name)).isFalse();
            handle.close();
            assertThat(SERVER.isRegistered(name)).isFalse();
            handle = null;

            // Duplicate live registration is rejected; after close a new handle is allowed.
            MetricsJmx.Handle first = MetricsJmx.register(shard);
            try {
                assertThatThrownBy(() -> MetricsJmx.register(shard))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("already registered");
            } finally {
                first.close();
            }
            MetricsJmx.Handle second = MetricsJmx.register(shard);
            second.close();
        } finally {
            if (handle != null) {
                handle.close();
            }
            shard.close(1_000L);
        }
    }

    // ------------------------------------------------------------------
    // register(RingGroup)
    // ------------------------------------------------------------------

    @Test
    void groupBeansNamesOrderAggregatesAndDistinctInstanceIds() throws Exception {
        RingGroup group = RingGroup.create(2, ShardOptions.defaults()
                .withName("jmx-group")
                .withRingEntries(128)
                .withLatencySampleEvery(1));
        RingGroup otherGroup = RingGroup.create(1, ShardOptions.defaults().withName("jmx-other"));
        MetricsJmx.Handle handle = null;
        MetricsJmx.Handle otherHandle = null;
        try {
            group.start();
            runNops(group.shard(0), 16);
            runNops(group.shard(1), 24);
            group.close(1_000L);
            List<ShardMetrics> expectedShards = group.metrics();
            GroupMetrics expectedAggregate = group.aggregateMetrics();

            handle = MetricsJmx.register(group);
            List<ObjectName> names = handle.names();
            assertThat(names).hasSize(3);
            assertThat(handle.name()).isEqualTo(names.get(0));
            ObjectName groupName = names.get(0);
            assertThat(groupName.getDomain()).isEqualTo(MetricsJmx.DOMAIN);
            assertThat(groupName.getKeyProperty("type")).isEqualTo("RingGroup");
            long instance = Long.parseLong(groupName.getKeyProperty("instance"));
            for (int i = 0; i < 2; i++) {
                ObjectName shardName = names.get(i + 1);
                assertThat(shardName.getKeyProperty("type")).isEqualTo("IoShard");
                assertThat(shardName.getKeyProperty("group")).isEqualTo(Long.toString(instance));
                assertThat(shardName.getKeyProperty("id")).isEqualTo(Integer.toString(i));
                assertThat(SERVER.isRegistered(shardName)).isTrue();
                assertThat(SERVER.getMBeanInfo(shardName).getAttributes()).isNotEmpty();
                // Per-shard beans expose that shard's exact frozen counters.
                assertRingAttributes(shardName, expectedShards.get(i).ring());
                assertShardCounterAttributes(shardName, expectedShards.get(i).stats());
                assertLatencyAttributes(shardName, expectedShards.get(i).dispatchLatency());
            }
            // Group bean aggregate attributes equal the aggregated snapshot.
            assertThat(((Number) attr(groupName, "Shards")).intValue()).isEqualTo(2);
            assertThat(((Number) attr(groupName, "Submitted")).longValue())
                    .isEqualTo(expectedAggregate.submitted());
            assertThat(((Number) attr(groupName, "Reaped")).longValue())
                    .isEqualTo(expectedAggregate.reaped());
            assertThat(((Number) attr(groupName, "Completed")).longValue())
                    .isEqualTo(expectedAggregate.completed());
            assertThat(((Number) attr(groupName, "InFlight")).longValue())
                    .isEqualTo(expectedAggregate.inFlight());
            assertThat(((Number) attr(groupName, "SqQueued")).longValue())
                    .isEqualTo(expectedAggregate.sqQueued());
            assertThat(((Number) attr(groupName, "CqReady")).longValue())
                    .isEqualTo(expectedAggregate.cqReady());
            assertLatencyAttributes(groupName, expectedAggregate.dispatchLatency());
            // Mid-flight-safe full attribute read (all attributes exist and never throw).
            AttributeList all = SERVER.getAttributes(groupName, GROUP_ATTRIBUTES);
            assertThat(all).hasSize(GROUP_ATTRIBUTES.length);
            AttributeList allShard = SERVER.getAttributes(names.get(1), SHARD_ATTRIBUTES);
            assertThat(allShard).hasSize(SHARD_ATTRIBUTES.length);

            handle.close();
            handle = null;
            for (ObjectName name : names) {
                assertThat(SERVER.isRegistered(name)).isFalse();
            }

            // Two independent groups get distinct instance ids.
            otherHandle = MetricsJmx.register(otherGroup);
            long otherInstance = Long.parseLong(otherHandle.name().getKeyProperty("instance"));
            assertThat(otherInstance).isNotEqualTo(instance);
            assertThat(otherHandle.names()).hasSize(2); // group + 1 shard
        } finally {
            if (handle != null) {
                handle.close();
            }
            if (otherHandle != null) {
                otherHandle.close();
            }
            group.close(1_000L);
            otherGroup.close(1_000L);
        }
    }

    @Test
    void liveAttributeReadsDuringLoadNeverThrowAndDisabledLatencyIsZero() throws Exception {
        RingGroup group = RingGroup.create(2, ShardOptions.defaults()
                .withName("jmx-live")
                .withRingEntries(512)
                .withLatencySampleEvery(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong reads = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        MetricsJmx.Handle handle = null;
        try {
            group.start();
            handle = MetricsJmx.register(group);
            List<ObjectName> names = handle.names();
            Thread sampler = Thread.ofPlatform().name("jmx-sampler").daemon(true).start(() -> {
                try {
                    while (!stop.get()) {
                        for (ObjectName name : names) {
                            SERVER.getAttributes(name, name.getKeyProperty("type").equals("RingGroup")
                                    ? GROUP_ATTRIBUTES : SHARD_ATTRIBUTES);
                        }
                        reads.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            try {
                int perShard = 200;
                CountDownLatch done = new CountDownLatch(2 * perShard);
                for (int i = 0; i < 2; i++) {
                    group.shard(i).execute(ctx -> {
                        for (int k = 0; k < perShard; k++) {
                            long handleId = ctx.ring().prepareNop();
                            ctx.onCompletion(handleId, result -> done.countDown());
                        }
                        ctx.submit();
                    });
                }
                assertTrue(done.await(10, TimeUnit.SECONDS), "live burst did not drain");
                waitUntil(() -> group.stats().stream()
                        .allMatch(stats -> stats.completionsDispatched() == perShard), 5_000L);
            } finally {
                stop.set(true);
                sampler.join(5_000L);
            }
            assertThat(failure.get()).as("live JMX attribute reads threw: %s", failure.get()).isNull();
            assertThat(reads.get()).isGreaterThan(0L);
        } finally {
            if (handle != null) {
                handle.close();
            }
            group.close(1_000L);
        }

        // Disabled sampling => all latency attributes exactly 0.
        RingGroup noSampling = RingGroup.create(2, ShardOptions.defaults()
                .withName("jmx-nosampling").withRingEntries(64));
        MetricsJmx.Handle noSamplingHandle = null;
        try {
            noSampling.start();
            noSamplingHandle = MetricsJmx.register(noSampling);
            for (ObjectName name : noSamplingHandle.names()) {
                assertThat(((Number) attr(name, "LatencySamples")).longValue()).isZero();
                assertThat(((Number) attr(name, "LatencyMeanMicros")).doubleValue()).isZero();
                assertThat(((Number) attr(name, "LatencyP99Micros")).doubleValue()).isZero();
                assertThat(((Number) attr(name, "LatencyMaxMicros")).doubleValue()).isZero();
                assertThat(((Number) attr(name, "LatencyMinMicros")).doubleValue()).isZero();
            }
        } finally {
            if (noSamplingHandle != null) {
                noSamplingHandle.close();
            }
            noSampling.close(1_000L);
        }
    }

    // ------------------------------------------------------------------
    // group registration rollback on partial failure
    // ------------------------------------------------------------------

    @Test
    void failedGroupRegistrationRollsBackAlreadyRegisteredBeans() throws Exception {
        RingGroup group = RingGroup.create(2, ShardOptions.defaults()
                .withName("jmx-rollback").withRingEntries(64));
        ObjectName colliding = null;
        try {
            // Instance ids are allocated strictly monotonically by one on each registration, so
            // the next group instance is the one right after a probe registration (white-box for
            // our own deterministic counter; the frozen contract only requires monotonicity).
            IoShard probe = IoShard.create(0, ShardOptions.defaults());
            MetricsJmx.Handle probeHandle = MetricsJmx.register(probe);
            long probeInstance = Long.parseLong(probeHandle.name().getKeyProperty("instance"));
            probeHandle.close();
            long nextInstance = probeInstance + 1L;

            // Pre-occupy the FIRST SHARD bean name of the upcoming group registration: the group
            // bean registration succeeds, then the shard bean registration fails mid-way.
            colliding = new ObjectName(MetricsJmx.DOMAIN
                    + ":type=IoShard,group=" + nextInstance + ",id=0");
            SERVER.registerMBean(new Dummy(), colliding);

            assertThatThrownBy(() -> MetricsJmx.register(group))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to register RingGroup")
                    .hasCauseInstanceOf(javax.management.InstanceAlreadyExistsException.class);
            ObjectName groupBean = new ObjectName(MetricsJmx.DOMAIN
                    + ":type=RingGroup,instance=" + nextInstance);
            assertThat(SERVER.isRegistered(groupBean))
                    .as("partially registered group bean must be rolled back")
                    .isFalse();
            assertThat(SERVER.isRegistered(colliding)).isTrue();

            // The failed call left no registration state behind: after removing the collision
            // the same group registers cleanly.
            SERVER.unregisterMBean(colliding);
            colliding = null;
            MetricsJmx.Handle recovered = MetricsJmx.register(group);
            try {
                assertThat(recovered.names()).hasSize(3);
                for (ObjectName name : recovered.names()) {
                    assertThat(SERVER.isRegistered(name)).isTrue();
                }
            } finally {
                recovered.close();
            }
        } finally {
            if (colliding != null) {
                try {
                    SERVER.unregisterMBean(colliding);
                } catch (Exception ignored) {
                    // best effort
                }
            }
            group.close(1_000L);
        }
    }

    // ------------------------------------------------------------------
    // no thread/fd leak across register/unregister cycles
    // ------------------------------------------------------------------

    @Test
    void registerUnregisterCyclesDoNotLeakThreadsOrFds() throws Exception {
        IoShard shard = IoShard.create(0, ShardOptions.defaults().withRingEntries(64));
        IoShard standalone = IoShard.create(7, ShardOptions.defaults().withRingEntries(64));
        RingGroup group = RingGroup.create(1, ShardOptions.defaults()
                .withName("jmx-leak").withRingEntries(64));
        try {
            // Warm up platform-MBeanServer/class-loading one-offs before taking the baselines.
            MetricsJmx.register(shard).close();
            MetricsJmx.register(group).close();
            MetricsJmx.register(standalone).close();
            Thread.sleep(50L);
            int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
            long fdsBefore = countOpenFds();

            for (int i = 0; i < 50; i++) {
                try (MetricsJmx.Handle handle = MetricsJmx.register(shard)) {
                    assertThat(SERVER.isRegistered(handle.name())).isTrue();
                }
            }
            for (int i = 0; i < 20; i++) {
                try (MetricsJmx.Handle handle = MetricsJmx.register(group)) {
                    assertThat(handle.names()).hasSize(2);
                }
            }
            for (int i = 0; i < 20; i++) {
                try (MetricsJmx.Handle handle = MetricsJmx.register(standalone)) {
                    assertThat(SERVER.isRegistered(handle.name())).isTrue();
                }
            }
            // Trigger no new threads/fds; allow a short settle for platform cleanup.
            Thread.sleep(100L);
            int threadsAfter = ManagementFactory.getThreadMXBean().getThreadCount();
            long fdsAfter = countOpenFds();
            assertThat(threadsAfter)
                    .as("thread count must not grow across JMX register/unregister cycles")
                    .isLessThanOrEqualTo(threadsBefore);
            assertThat(fdsAfter)
                    .as("open fd count must not grow across JMX register/unregister cycles")
                    .isBetween(fdsBefore - 1L, fdsBefore + 1L);
        } finally {
            shard.close(1_000L);
            standalone.close(1_000L);
            group.close(1_000L);
        }
    }

    // ------------------------------------------------------------------
    // Helpers / fixtures
    // ------------------------------------------------------------------

    private static Object attr(ObjectName name, String attribute) throws Exception {
        return SERVER.getAttribute(name, attribute);
    }

    private static void assertRingAttributes(ObjectName name, RingMetrics ring) throws Exception {
        assertThat(((Number) attr(name, "SqesPrepared")).longValue()).isEqualTo(ring.sqesPrepared());
        assertThat(((Number) attr(name, "Submitted")).longValue()).isEqualTo(ring.submitted());
        assertThat(((Number) attr(name, "Reaped")).longValue()).isEqualTo(ring.reaped());
        assertThat(((Number) attr(name, "Completed")).longValue()).isEqualTo(ring.completed());
        assertThat(((Number) attr(name, "Failed")).longValue()).isEqualTo(ring.failed());
        assertThat(((Number) attr(name, "Cancelled")).longValue()).isEqualTo(ring.cancelled());
        assertThat(((Number) attr(name, "TimedOut")).longValue()).isEqualTo(ring.timedOut());
        assertThat(((Number) attr(name, "InFlight")).longValue()).isEqualTo(ring.inFlight());
        assertThat(((Number) attr(name, "Enters")).longValue()).isEqualTo(ring.enters());
        assertThat(((Number) attr(name, "SubmitBatches")).longValue())
                .isEqualTo(ring.submitBatches());
        assertThat(((Number) attr(name, "SubmitErrors")).longValue()).isEqualTo(ring.submitErrors());
        assertThat(((Number) attr(name, "SubmitRetries")).longValue())
                .isEqualTo(ring.submitRetries());
        assertThat(((Number) attr(name, "SqFullEvents")).longValue()).isEqualTo(ring.sqFullEvents());
        assertThat(((Number) attr(name, "SqQueued")).longValue()).isEqualTo(ring.sqQueued());
        assertThat(((Number) attr(name, "SqEntries")).intValue()).isEqualTo(ring.sqEntries());
        assertThat(((Number) attr(name, "CqReady")).longValue()).isEqualTo(ring.cqReady());
        assertThat(((Number) attr(name, "CqEntries")).intValue()).isEqualTo(ring.cqEntries());
        assertThat(((Number) attr(name, "CqOverflowEvents")).longValue())
                .isEqualTo(ring.cqOverflowEvents());
    }

    private static void assertShardCounterAttributes(ObjectName name, ShardStats stats)
            throws Exception {
        assertThat(((Number) attr(name, "TasksSubmitted")).longValue())
                .isEqualTo(stats.tasksSubmitted());
        assertThat(((Number) attr(name, "TasksExecuted")).longValue())
                .isEqualTo(stats.tasksExecuted());
        assertThat(((Number) attr(name, "TaskFailures")).longValue())
                .isEqualTo(stats.taskFailures());
        assertThat(((Number) attr(name, "TasksRejected")).longValue())
                .isEqualTo(stats.tasksRejected());
        assertThat(((Number) attr(name, "CompletionsRegistered")).longValue())
                .isEqualTo(stats.completionsRegistered());
        assertThat(((Number) attr(name, "CompletionsDispatched")).longValue())
                .isEqualTo(stats.completionsDispatched());
        assertThat(((Number) attr(name, "CompletionsUnattributed")).longValue())
                .isEqualTo(stats.completionsUnattributed());
        assertThat(((Number) attr(name, "CompletionFailures")).longValue())
                .isEqualTo(stats.completionFailures());
        assertThat(((Number) attr(name, "HandoffsSent")).longValue())
                .isEqualTo(stats.handoffsSent());
        assertThat(((Number) attr(name, "HandoffsInlineFallback")).longValue())
                .isEqualTo(stats.handoffsInlineFallback());
        assertThat(((Number) attr(name, "EventfdWrites")).longValue())
                .isEqualTo(stats.eventfdWrites());
        assertThat(((Number) attr(name, "RingSubmits")).longValue())
                .isEqualTo(stats.ringSubmits());
        assertThat(((Number) attr(name, "CancelledAtClose")).longValue())
                .isEqualTo(stats.cancelledAtClose());
        assertThat(((Number) attr(name, "AbandonedAtClose")).longValue())
                .isEqualTo(stats.abandonedAtClose());
        assertThat(attr(name, "CloseTimedOut")).isEqualTo(stats.closeTimedOut());
    }

    private static void assertLatencyAttributes(ObjectName name, LatencySnapshot latency)
            throws Exception {
        assertThat(((Number) attr(name, "LatencySamples")).longValue()).isEqualTo(latency.samples());
        assertThat(((Number) attr(name, "LatencyMeanMicros")).doubleValue())
                .isEqualTo(latency.meanNanos() / 1_000.0);
        assertThat(((Number) attr(name, "LatencyP50Micros")).doubleValue())
                .isEqualTo(latency.p50Nanos() / 1_000.0);
        assertThat(((Number) attr(name, "LatencyP90Micros")).doubleValue())
                .isEqualTo(latency.p90Nanos() / 1_000.0);
        assertThat(((Number) attr(name, "LatencyP99Micros")).doubleValue())
                .isEqualTo(latency.p99Nanos() / 1_000.0);
        assertThat(((Number) attr(name, "LatencyP999Micros")).doubleValue())
                .isEqualTo(latency.p999Nanos() / 1_000.0);
        assertThat(((Number) attr(name, "LatencyMaxMicros")).doubleValue())
                .isEqualTo(latency.maxNanos() / 1_000.0);
        assertThat(((Number) attr(name, "LatencyMinMicros")).doubleValue())
                .isEqualTo(latency.minNanos() / 1_000.0);
    }

    private static long countOpenFds() throws Exception {
        try (java.util.stream.Stream<Path> entries = Files.list(Path.of("/proc/self/fd"))) {
            return entries.count();
        }
    }

    private static void runNops(IoShard shard, int operations) throws Exception {
        CountDownLatch done = new CountDownLatch(operations);
        shard.execute(ctx -> {
            for (int i = 0; i < operations; i++) {
                long handle = ctx.ring().prepareNop();
                ctx.onCompletion(handle, result -> done.countDown());
            }
            ctx.submit();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "NOP burst did not complete");
        waitUntil(() -> shard.stats().completionsDispatched() == operations, 10_000L);
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

    /** Standard MBean used only to pre-occupy an ObjectName and force a partial failure. */
    public interface DummyMBean {
        int getValue();
    }

    /** Standard MBean used only to pre-occupy an ObjectName and force a partial failure. */
    public static final class Dummy implements DummyMBean {
        @Override
        public int getValue() {
            return 1;
        }
    }
}
