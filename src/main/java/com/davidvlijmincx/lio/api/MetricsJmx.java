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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * JDK-only JMX binding for the shard metrics.
 *
 * <p>Registers one {@link IoShardMetricsMXBean} per standalone shard and, for a group, one
 * {@link RingGroupMetricsMXBean} plus one shard bean per shard on the platform
 * {@link MBeanServer} ({@link ManagementFactory#getPlatformMBeanServer()}). Attributes are
 * pull-based snapshots, so there is no exporter thread and no background memory cost.
 *
 * <p><b>Coherent snapshots, refreshed at most once per window:</b> each bean caches one
 * coherent snapshot of its shard/group metrics and serves <em>every</em> attribute getter from
 * that snapshot instead of re-snapshotting per attribute. The cache is refreshed lazily on the
 * first getter call after a short refresh window (50 ms), so a
 * bean takes at most one {@link IoShard#metrics()} / {@link RingGroup#aggregateMetrics()}
 * snapshot per window: a 20-30 attribute scrape merges the dispatch-latency histograms once,
 * not once per attribute, and the attributes of one scrape describe one instant instead of a
 * collage of 20-30 different instants. The first snapshot is taken when the bean is
 * registered.
 *
 * <p><b>Bounded staleness:</b> when a getter finds the cached snapshot older than the refresh
 * window it refreshes before returning, so every attribute value is at most one window
 * (50 ms) old at the moment it is read. All attributes read from
 * one bean inside one window come from that single snapshot; a scrape that spans a window
 * boundary can mix two adjacent snapshots (a JVM-local scrape reads 20-30 attributes in
 * microseconds, so in practice it is coherent). Lifecycle flags
 * ({@code Started}/{@code Stopping}/{@code Closed}) share the same bound. Each bean caches
 * independently, so a group bean and its per-shard beans are not coordinated with each other,
 * and concurrent readers never block a shard and never throw. No background thread, executor,
 * or timer is involved: refreshes happen on the reading thread. If a refresh ever fails the
 * last good snapshot is kept and the next attempt is deferred by one window;
 * {@link IoShard#metrics()} and {@link RingGroup#aggregateMetrics()} are total by contract, so
 * that path is defensive only.
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

    /**
     * Refresh window (milliseconds) of the per-bean metrics snapshot cache: a bean re-snapshots
     * its shard/group at most once per window. Package-private so the JMX tests can reason
     * about the documented staleness bound without duplicating the number.
     */
    static final long SNAPSHOT_WINDOW_MILLIS = 50L;

    /** {@link #SNAPSHOT_WINDOW_MILLIS} in nanoseconds, as used by the cache. */
    static final long SNAPSHOT_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(SNAPSHOT_WINDOW_MILLIS);

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

    /** One cached metrics snapshot and the {@link System#nanoTime()} at which it was taken. */
    private record Snapshot<T>(T metrics, long takenNanos) {
    }

    /**
     * Thread-safe one-snapshot cache dedicated to a single bean. The first snapshot is taken at
     * construction (registration); afterwards a getter refreshes it at most once per
     * {@link #SNAPSHOT_WINDOW_NANOS} and serves every attribute from the same snapshot.
     *
     * <p>The fast path is a volatile read plus a {@link System#nanoTime()} compare. Only a
     * getter that finds the snapshot stale takes the per-bean lock, and only one of the
     * concurrent readers does the refresh while the others wait for it (single-flight refresh).
     * The refresh runs on the reading thread; there is no background thread or timer.
     *
     * <p>Refresh failures never propagate: the last good snapshot is served and the next
     * attempt is deferred by one window. Both snapshot suppliers are total by contract, so this
     * is defense in depth for the "attribute reads never throw" guarantee.
     */
    private static final class SnapshotCache<T> {

        private final Supplier<T> snapshotter;
        private final Object refreshLock = new Object();
        private volatile Snapshot<T> current;

        SnapshotCache(Supplier<T> snapshotter) {
            this.snapshotter = snapshotter;
            this.current = new Snapshot<>(snapshotter.get(), System.nanoTime());
        }

        T get() {
            Snapshot<T> snapshot = current;
            long now = System.nanoTime();
            if (now - snapshot.takenNanos() < SNAPSHOT_WINDOW_NANOS) {
                return snapshot.metrics();
            }
            synchronized (refreshLock) {
                snapshot = current;
                now = System.nanoTime();
                if (now - snapshot.takenNanos() < SNAPSHOT_WINDOW_NANOS) {
                    return snapshot.metrics();
                }
                T metrics;
                try {
                    metrics = snapshotter.get();
                } catch (Throwable t) {
                    metrics = snapshot.metrics();
                }
                snapshot = new Snapshot<>(metrics, now);
                current = snapshot;
                return snapshot.metrics();
            }
        }
    }

    /**
     * Package-private adapter (only the frozen public surface of {@code MetricsJmx} is public):
     * JMX introspects the public {@link IoShardMetricsMXBean} interface, so the bean class itself
     * does not need to be part of the public API.
     */
    static final class ShardBean implements IoShardMetricsMXBean {

        private final IoShard shard;
        /** One coherent shard snapshot served to every attribute; see {@link SnapshotCache}. */
        private final SnapshotCache<ShardMetrics> snapshot;

        ShardBean(IoShard shard) {
            this.shard = Objects.requireNonNull(shard, "shard");
            this.snapshot = new SnapshotCache<>(shard::metrics);
        }

        @Override
        public int getShardId() {
            // Identity, not a metric: immutable for the lifetime of the bean.
            return shard.id();
        }

        @Override
        public String getThreadName() {
            return snapshot.get().stats().threadName();
        }

        @Override
        public int getCurrentCpu() {
            return snapshot.get().stats().currentCpu();
        }

        @Override
        public boolean isAffinityApplied() {
            return snapshot.get().stats().affinityApplied();
        }

        @Override
        public boolean isStarted() {
            return snapshot.get().stats().started();
        }

        @Override
        public boolean isStopping() {
            return snapshot.get().stats().stopping();
        }

        @Override
        public boolean isClosed() {
            return snapshot.get().stats().closed();
        }

        @Override
        public long getSqesPrepared() {
            return snapshot.get().ring().sqesPrepared();
        }

        @Override
        public long getSubmitted() {
            return snapshot.get().ring().submitted();
        }

        @Override
        public long getReaped() {
            return snapshot.get().ring().reaped();
        }

        @Override
        public long getCompleted() {
            return snapshot.get().ring().completed();
        }

        @Override
        public long getFailed() {
            return snapshot.get().ring().failed();
        }

        @Override
        public long getCancelled() {
            return snapshot.get().ring().cancelled();
        }

        @Override
        public long getTimedOut() {
            return snapshot.get().ring().timedOut();
        }

        @Override
        public long getInFlight() {
            return snapshot.get().ring().inFlight();
        }

        @Override
        public long getEnters() {
            return snapshot.get().ring().enters();
        }

        @Override
        public long getSubmitBatches() {
            return snapshot.get().ring().submitBatches();
        }

        @Override
        public long getSubmitErrors() {
            return snapshot.get().ring().submitErrors();
        }

        @Override
        public long getSubmitRetries() {
            return snapshot.get().ring().submitRetries();
        }

        @Override
        public long getSqFullEvents() {
            return snapshot.get().ring().sqFullEvents();
        }

        @Override
        public long getSqQueued() {
            return snapshot.get().ring().sqQueued();
        }

        @Override
        public int getSqEntries() {
            return snapshot.get().ring().sqEntries();
        }

        @Override
        public long getCqReady() {
            return snapshot.get().ring().cqReady();
        }

        @Override
        public int getCqEntries() {
            return snapshot.get().ring().cqEntries();
        }

        @Override
        public long getCqOverflowEvents() {
            return snapshot.get().ring().cqOverflowEvents();
        }

        @Override
        public long getTasksSubmitted() {
            return snapshot.get().stats().tasksSubmitted();
        }

        @Override
        public long getTasksExecuted() {
            return snapshot.get().stats().tasksExecuted();
        }

        @Override
        public long getTaskFailures() {
            return snapshot.get().stats().taskFailures();
        }

        @Override
        public long getTasksRejected() {
            return snapshot.get().stats().tasksRejected();
        }

        @Override
        public long getCompletionsRegistered() {
            return snapshot.get().stats().completionsRegistered();
        }

        @Override
        public long getCompletionsDispatched() {
            return snapshot.get().stats().completionsDispatched();
        }

        @Override
        public long getCompletionsUnattributed() {
            return snapshot.get().stats().completionsUnattributed();
        }

        @Override
        public long getCompletionFailures() {
            return snapshot.get().stats().completionFailures();
        }

        @Override
        public long getHandoffsSent() {
            return snapshot.get().stats().handoffsSent();
        }

        @Override
        public long getHandoffsInlineFallback() {
            return snapshot.get().stats().handoffsInlineFallback();
        }

        @Override
        public long getEventfdWrites() {
            return snapshot.get().stats().eventfdWrites();
        }

        @Override
        public long getRingSubmits() {
            return snapshot.get().stats().ringSubmits();
        }

        @Override
        public long getCancelledAtClose() {
            return snapshot.get().stats().cancelledAtClose();
        }

        @Override
        public long getAbandonedAtClose() {
            return snapshot.get().stats().abandonedAtClose();
        }

        @Override
        public boolean isCloseTimedOut() {
            return snapshot.get().stats().closeTimedOut();
        }

        @Override
        public long getLatencySamples() {
            return snapshot.get().dispatchLatency().samples();
        }

        @Override
        public double getLatencyMeanMicros() {
            return micros(snapshot.get().dispatchLatency().meanNanos());
        }

        @Override
        public double getLatencyP50Micros() {
            return micros(snapshot.get().dispatchLatency().p50Nanos());
        }

        @Override
        public double getLatencyP90Micros() {
            return micros(snapshot.get().dispatchLatency().p90Nanos());
        }

        @Override
        public double getLatencyP99Micros() {
            return micros(snapshot.get().dispatchLatency().p99Nanos());
        }

        @Override
        public double getLatencyP999Micros() {
            return micros(snapshot.get().dispatchLatency().p999Nanos());
        }

        @Override
        public double getLatencyMaxMicros() {
            return micros(snapshot.get().dispatchLatency().maxNanos());
        }

        @Override
        public double getLatencyMinMicros() {
            return micros(snapshot.get().dispatchLatency().minNanos());
        }
    }

    /**
     * Package-private adapter (only the frozen public surface of {@code MetricsJmx} is public):
     * JMX introspects the public {@link RingGroupMetricsMXBean} interface, so the bean class
     * itself does not need to be part of the public API.
     */
    static final class GroupBean implements RingGroupMetricsMXBean {

        private final RingGroup group;
        /** One coherent group-aggregate snapshot served to every attribute. */
        private final SnapshotCache<GroupMetrics> snapshot;

        GroupBean(RingGroup group) {
            this.group = Objects.requireNonNull(group, "group");
            this.snapshot = new SnapshotCache<>(group::aggregateMetrics);
        }

        @Override
        public int getShards() {
            // Group size, not a metric: immutable for the lifetime of the bean.
            return group.size();
        }

        @Override
        public long getSubmitted() {
            return snapshot.get().submitted();
        }

        @Override
        public long getReaped() {
            return snapshot.get().reaped();
        }

        @Override
        public long getCompleted() {
            return snapshot.get().completed();
        }

        @Override
        public long getFailed() {
            return snapshot.get().failed();
        }

        @Override
        public long getCancelled() {
            return snapshot.get().cancelled();
        }

        @Override
        public long getTimedOut() {
            return snapshot.get().timedOut();
        }

        @Override
        public long getInFlight() {
            return snapshot.get().inFlight();
        }

        @Override
        public long getSqQueued() {
            return snapshot.get().sqQueued();
        }

        @Override
        public long getCqReady() {
            return snapshot.get().cqReady();
        }

        @Override
        public long getLatencySamples() {
            return snapshot.get().dispatchLatency().samples();
        }

        @Override
        public double getLatencyMeanMicros() {
            return micros(snapshot.get().dispatchLatency().meanNanos());
        }

        @Override
        public double getLatencyP50Micros() {
            return micros(snapshot.get().dispatchLatency().p50Nanos());
        }

        @Override
        public double getLatencyP90Micros() {
            return micros(snapshot.get().dispatchLatency().p90Nanos());
        }

        @Override
        public double getLatencyP99Micros() {
            return micros(snapshot.get().dispatchLatency().p99Nanos());
        }

        @Override
        public double getLatencyP999Micros() {
            return micros(snapshot.get().dispatchLatency().p999Nanos());
        }

        @Override
        public double getLatencyMaxMicros() {
            return micros(snapshot.get().dispatchLatency().maxNanos());
        }

        @Override
        public double getLatencyMinMicros() {
            return micros(snapshot.get().dispatchLatency().minNanos());
        }
    }

    private static double micros(double nanos) {
        return nanos / 1_000.0;
    }
}
