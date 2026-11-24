package com.davidvlijmincx.lio.api;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JDK-only JMX binding for the shard metrics.
 *
 * <p>Registers one {@link IoShardMetricsMXBean} per standalone shard and, for a group, one
 * {@link RingGroupMetricsMXBean} plus one shard bean per shard on the platform
 * {@link MBeanServer} ({@link ManagementFactory#getPlatformMBeanServer()}). Attributes are
 * pull-based snapshots, so there is no exporter thread and no background memory cost.
 *
 * <p><b>ObjectName scheme (frozen):</b>
 * <ul>
 *   <li>group: {@code com.davidvlijmincx.lio.api:type=RingGroup,instance=<N>}</li>
 *   <li>shards of a registered group: {@code com.davidvlijmincx.lio.api:type=IoShard,group=<N>,id=<shardId>}</li>
 *   <li>standalone shard: {@code com.davidvlijmincx.lio.api:type=IoShard,instance=<N>,id=<shardId>}</li>
 * </ul>
 * {@code <N>} is a monotonically increasing per-JVM registration instance id.
 *
 * <p><b>Semantics (frozen):</b> registering the same shard/group twice (two live handles) is
 * an {@link IllegalStateException} ("already registered"); a failed JMX registration is an
 * {@link IllegalStateException} carrying the cause, and a group registration that fails
 * mid-way unregisters everything it already registered. {@link Handle#close()} is idempotent
 * and unregisters every bean of the handle; after it returns, a new registration of the same
 * shard/group is allowed again. Attribute reads never throw, including for closed shards.
 *
 * <p>JMX is optional in spirit: a user who never touches this class pays nothing and does not
 * create an {@code MBeanServer} reference.
 */
public final class MetricsJmx {

    /** JMX domain of every bean registered by this class. */
    public static final String DOMAIN = "com.davidvlijmincx.lio.api";

    private static final Object LOCK = new Object();
    private static final MBeanServer SERVER = ManagementFactory.getPlatformMBeanServer();
    /** Monotonically increasing per-JVM registration instance id (frozen scheme). */
    private static final AtomicLong NEXT_INSTANCE = new AtomicLong();

    /** Shards with a live handle (standalone or as part of a registered group) — identity set. */
    private static final Set<IoShard> REGISTERED_SHARDS = ConcurrentHashMap.newKeySet();
    /** Groups with a live handle — identity set. */
    private static final Set<RingGroup> REGISTERED_GROUPS = ConcurrentHashMap.newKeySet();

    /**
     * Handle to one JMX registration: owns every bean registered by the corresponding
     * {@code register} call. {@link #close()} unregisters all of them and is idempotent.
     */
    public interface Handle extends AutoCloseable {

        /**
         * Returns the primary bean name of this handle: the shard bean for a shard handle and
         * the group bean for a group handle.
         *
         * @return the primary bean name
         */
        ObjectName name();

        /**
         * Returns every bean registered by this handle, in registration order with the group
         * bean first.
         *
         * @return an immutable list of the registered bean names
         */
        List<ObjectName> names();

        @Override
        void close();
    }

    private MetricsJmx() {
    }

    /**
     * Registers the shard's MXBean on the platform {@code MBeanServer}.
     *
     * @param shard shard to expose through JMX; must not be {@code null}
     * @return a handle owning the bean; {@link Handle#close()} unregisters it
     * @throws IllegalStateException when the shard is already registered by a live handle or
     *                               the platform server rejects the registration
     */
    public static Handle register(IoShard shard) {
        Objects.requireNonNull(shard, "shard");
        synchronized (LOCK) {
            if (REGISTERED_SHARDS.contains(shard)) {
                throw new IllegalStateException("IoShard " + shard.id() + " is already registered with JMX");
            }
            long instance = NEXT_INSTANCE.getAndIncrement();
            ObjectName name = shardName(instance, shard.id());
            REGISTERED_SHARDS.add(shard);
            try {
                SERVER.registerMBean(new ShardBean(shard), name);
            } catch (Exception e) {
                REGISTERED_SHARDS.remove(shard);
                throw new IllegalStateException("Failed to register IoShard MXBean " + name, e);
            }
            List<ObjectName> names = List.of(name);
            return new HandleImpl(name, names, () -> {
                synchronized (LOCK) {
                    if (REGISTERED_SHARDS.remove(shard)) {
                        unregisterAll(names);
                    }
                }
            });
        }
    }

    /**
     * Registers the group's MXBean and one shard MXBean per shard on the platform
     * {@code MBeanServer}. The group bean is registered first; a mid-way failure unregisters
     * everything already registered by this call and rethrows an {@link IllegalStateException}
     * carrying the cause.
     *
     * @param group group to expose through JMX; must not be {@code null}
     * @return a handle owning all beans (group bean first in {@link Handle#names()})
     * @throws IllegalStateException when the group (or one of its shards) is already
     *                               registered by a live handle, or registration fails
     */
    public static Handle register(RingGroup group) {
        Objects.requireNonNull(group, "group");
        synchronized (LOCK) {
            if (REGISTERED_GROUPS.contains(group)) {
                throw new IllegalStateException("RingGroup is already registered with JMX");
            }
            int shardCount = group.size();
            for (int i = 0; i < shardCount; i++) {
                IoShard shard = group.shard(i);
                if (REGISTERED_SHARDS.contains(shard)) {
                    throw new IllegalStateException("IoShard " + shard.id()
                            + " of the group is already registered with JMX");
                }
            }
            long instance = NEXT_INSTANCE.getAndIncrement();
            ObjectName groupName = groupName(instance);
            List<ObjectName> names = new ArrayList<>(shardCount + 1);
            REGISTERED_GROUPS.add(group);
            for (int i = 0; i < shardCount; i++) {
                REGISTERED_SHARDS.add(group.shard(i));
            }
            try {
                SERVER.registerMBean(new GroupBean(group), groupName);
                names.add(groupName);
                for (int i = 0; i < shardCount; i++) {
                    IoShard shard = group.shard(i);
                    ObjectName name = groupedShardName(instance, shard.id());
                    SERVER.registerMBean(new ShardBean(shard), name);
                    names.add(name);
                }
            } catch (Exception e) {
                unregisterAllReversed(names);
                for (int i = 0; i < shardCount; i++) {
                    REGISTERED_SHARDS.remove(group.shard(i));
                }
                REGISTERED_GROUPS.remove(group);
                throw new IllegalStateException("Failed to register RingGroup MXBeans for instance "
                        + instance, e);
            }
            List<ObjectName> frozen = List.copyOf(names);
            return new HandleImpl(groupName, frozen, () -> {
                synchronized (LOCK) {
                    if (REGISTERED_GROUPS.remove(group)) {
                        for (int i = 0; i < shardCount; i++) {
                            REGISTERED_SHARDS.remove(group.shard(i));
                        }
                        unregisterAll(frozen);
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // ObjectName helpers
    // ------------------------------------------------------------------

    private static ObjectName shardName(long instance, int shardId) {
        return objectName(DOMAIN + ":type=IoShard,instance=" + instance + ",id=" + shardId);
    }

    private static ObjectName groupedShardName(long instance, int shardId) {
        return objectName(DOMAIN + ":type=IoShard,group=" + instance + ",id=" + shardId);
    }

    private static ObjectName groupName(long instance) {
        return objectName(DOMAIN + ":type=RingGroup,instance=" + instance);
    }

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (MalformedObjectNameException e) {
            throw new IllegalStateException("Invalid JMX ObjectName: " + value, e);
        }
    }

    private static void unregisterAll(List<ObjectName> names) {
        for (ObjectName name : names) {
            try {
                SERVER.unregisterMBean(name);
            } catch (Exception ignored) {
                // Already unregistered / never registered: unregistration is best effort.
            }
        }
    }

    private static void unregisterAllReversed(List<ObjectName> names) {
        for (int i = names.size() - 1; i >= 0; i--) {
            try {
                SERVER.unregisterMBean(names.get(i));
            } catch (Exception ignored) {
                // Rollback is best effort; never mask the original registration failure.
            }
        }
    }

    // ------------------------------------------------------------------
    // Handles
    // ------------------------------------------------------------------

    private static final class HandleImpl implements Handle {

        private final ObjectName name;
        private final List<ObjectName> names;
        private Runnable closeAction;

        private HandleImpl(ObjectName name, List<ObjectName> names, Runnable closeAction) {
            this.name = name;
            this.names = names;
            this.closeAction = closeAction;
        }

        @Override
        public ObjectName name() {
            return name;
        }

        @Override
        public List<ObjectName> names() {
            return names;
        }

        @Override
        public void close() {
            Runnable action;
            synchronized (this) {
                action = closeAction;
                closeAction = null;
            }
            if (action != null) {
                action.run();
            }
        }
    }

    // ------------------------------------------------------------------
    // MXBean implementations (pull-based snapshots)
    // ------------------------------------------------------------------

    /**
     * Package-private adapter (only the frozen public surface of {@code MetricsJmx} is public):
     * JMX introspects the public {@link IoShardMetricsMXBean} interface, so the bean class itself
     * does not need to be part of the public API.
     */
    static final class ShardBean implements IoShardMetricsMXBean {

        private final IoShard shard;

        ShardBean(IoShard shard) {
            this.shard = Objects.requireNonNull(shard, "shard");
        }

        @Override
        public int getShardId() {
            return shard.id();
        }

        @Override
        public String getThreadName() {
            return shard.stats().threadName();
        }

        @Override
        public int getCurrentCpu() {
            return shard.stats().currentCpu();
        }

        @Override
        public boolean isAffinityApplied() {
            return shard.stats().affinityApplied();
        }

        @Override
        public boolean isStarted() {
            return shard.isStarted();
        }

        @Override
        public boolean isStopping() {
            return shard.isStopping();
        }

        @Override
        public boolean isClosed() {
            return shard.isClosed();
        }

        @Override
        public long getSqesPrepared() {
            return shard.metrics().ring().sqesPrepared();
        }

        @Override
        public long getSubmitted() {
            return shard.metrics().ring().submitted();
        }

        @Override
        public long getReaped() {
            return shard.metrics().ring().reaped();
        }

        @Override
        public long getCompleted() {
            return shard.metrics().ring().completed();
        }

        @Override
        public long getFailed() {
            return shard.metrics().ring().failed();
        }

        @Override
        public long getCancelled() {
            return shard.metrics().ring().cancelled();
        }

        @Override
        public long getTimedOut() {
            return shard.metrics().ring().timedOut();
        }

        @Override
        public long getInFlight() {
            return shard.metrics().ring().inFlight();
        }

        @Override
        public long getEnters() {
            return shard.metrics().ring().enters();
        }

        @Override
        public long getSubmitBatches() {
            return shard.metrics().ring().submitBatches();
        }

        @Override
        public long getSubmitErrors() {
            return shard.metrics().ring().submitErrors();
        }

        @Override
        public long getSubmitRetries() {
            return shard.metrics().ring().submitRetries();
        }

        @Override
        public long getSqFullEvents() {
            return shard.metrics().ring().sqFullEvents();
        }

        @Override
        public long getSqQueued() {
            return shard.metrics().ring().sqQueued();
        }

        @Override
        public int getSqEntries() {
            return shard.metrics().ring().sqEntries();
        }

        @Override
        public long getCqReady() {
            return shard.metrics().ring().cqReady();
        }

        @Override
        public int getCqEntries() {
            return shard.metrics().ring().cqEntries();
        }

        @Override
        public long getCqOverflowEvents() {
            return shard.metrics().ring().cqOverflowEvents();
        }

        @Override
        public long getTasksSubmitted() {
            return shard.stats().tasksSubmitted();
        }

        @Override
        public long getTasksExecuted() {
            return shard.stats().tasksExecuted();
        }

        @Override
        public long getTaskFailures() {
            return shard.stats().taskFailures();
        }

        @Override
        public long getTasksRejected() {
            return shard.stats().tasksRejected();
        }

        @Override
        public long getCompletionsRegistered() {
            return shard.stats().completionsRegistered();
        }

        @Override
        public long getCompletionsDispatched() {
            return shard.stats().completionsDispatched();
        }

        @Override
        public long getCompletionsUnattributed() {
            return shard.stats().completionsUnattributed();
        }

        @Override
        public long getCompletionFailures() {
            return shard.stats().completionFailures();
        }

        @Override
        public long getHandoffsSent() {
            return shard.stats().handoffsSent();
        }

        @Override
        public long getHandoffsInlineFallback() {
            return shard.stats().handoffsInlineFallback();
        }

        @Override
        public long getEventfdWrites() {
            return shard.stats().eventfdWrites();
        }

        @Override
        public long getRingSubmits() {
            return shard.stats().ringSubmits();
        }

        @Override
        public long getCancelledAtClose() {
            return shard.stats().cancelledAtClose();
        }

        @Override
        public long getAbandonedAtClose() {
            return shard.stats().abandonedAtClose();
        }

        @Override
        public boolean isCloseTimedOut() {
            return shard.stats().closeTimedOut();
        }

        @Override
        public long getLatencySamples() {
            return shard.metrics().dispatchLatency().samples();
        }

        @Override
        public double getLatencyMeanMicros() {
            return micros(shard.metrics().dispatchLatency().meanNanos());
        }

        @Override
        public double getLatencyP50Micros() {
            return micros(shard.metrics().dispatchLatency().p50Nanos());
        }

        @Override
        public double getLatencyP90Micros() {
            return micros(shard.metrics().dispatchLatency().p90Nanos());
        }

        @Override
        public double getLatencyP99Micros() {
            return micros(shard.metrics().dispatchLatency().p99Nanos());
        }

        @Override
        public double getLatencyP999Micros() {
            return micros(shard.metrics().dispatchLatency().p999Nanos());
        }

        @Override
        public double getLatencyMaxMicros() {
            return micros(shard.metrics().dispatchLatency().maxNanos());
        }

        @Override
        public double getLatencyMinMicros() {
            return micros(shard.metrics().dispatchLatency().minNanos());
        }
    }

    /**
     * Package-private adapter (only the frozen public surface of {@code MetricsJmx} is public):
     * JMX introspects the public {@link RingGroupMetricsMXBean} interface, so the bean class
     * itself does not need to be part of the public API.
     */
    static final class GroupBean implements RingGroupMetricsMXBean {

        private final RingGroup group;

        GroupBean(RingGroup group) {
            this.group = Objects.requireNonNull(group, "group");
        }

        @Override
        public int getShards() {
            return group.size();
        }

        @Override
        public long getSubmitted() {
            return group.aggregateMetrics().submitted();
        }

        @Override
        public long getReaped() {
            return group.aggregateMetrics().reaped();
        }

        @Override
        public long getCompleted() {
            return group.aggregateMetrics().completed();
        }

        @Override
        public long getFailed() {
            return group.aggregateMetrics().failed();
        }

        @Override
        public long getCancelled() {
            return group.aggregateMetrics().cancelled();
        }

        @Override
        public long getTimedOut() {
            return group.aggregateMetrics().timedOut();
        }

        @Override
        public long getInFlight() {
            return group.aggregateMetrics().inFlight();
        }

        @Override
        public long getSqQueued() {
            return group.aggregateMetrics().sqQueued();
        }

        @Override
        public long getCqReady() {
            return group.aggregateMetrics().cqReady();
        }

        @Override
        public long getLatencySamples() {
            return group.aggregateMetrics().dispatchLatency().samples();
        }

        @Override
        public double getLatencyMeanMicros() {
            return micros(group.aggregateMetrics().dispatchLatency().meanNanos());
        }

        @Override
        public double getLatencyP50Micros() {
            return micros(group.aggregateMetrics().dispatchLatency().p50Nanos());
        }

        @Override
        public double getLatencyP90Micros() {
            return micros(group.aggregateMetrics().dispatchLatency().p90Nanos());
        }

        @Override
        public double getLatencyP99Micros() {
            return micros(group.aggregateMetrics().dispatchLatency().p99Nanos());
        }

        @Override
        public double getLatencyP999Micros() {
            return micros(group.aggregateMetrics().dispatchLatency().p999Nanos());
        }

        @Override
        public double getLatencyMaxMicros() {
            return micros(group.aggregateMetrics().dispatchLatency().maxNanos());
        }

        @Override
        public double getLatencyMinMicros() {
            return micros(group.aggregateMetrics().dispatchLatency().minNanos());
        }
    }

    private static double micros(double nanos) {
        return nanos / 1_000.0;
    }
}
