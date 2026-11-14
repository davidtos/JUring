package com.davidvlijmincx.lio.api;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed pool of {@link IoShard}s — one ring per shard — with round-robin and
 * hash-based work distribution and aggregate lifecycle/statistics.
 *
 * <p>The group <b>is</b> the ring pool: {@link #create(int, ShardOptions)} allocates shard
 * objects only, and each shard creates its one ring on its own thread when started (lazily
 * through {@link #execute(ShardTask)} or eagerly through {@link #start()}). Work
 * distribution is fixed at creation time; there is no rebalancing or dynamic resizing.
 *
 * <p>Affinity: {@code create(n, options)} pins shard {@code i} to
 * {@code options.cpuAffinity() + i} when {@code options.cpuAffinity() != NO_CPU_AFFINITY};
 * {@code create(n, options, firstCpu)} makes that base explicit ({@code firstCpu < 0} = no
 * affinity). Out-of-range or denied CPUs are a non-fatal, recorded best-effort failure.
 *
 * <p>Distribution: {@link #nextShard()} round-robins with an {@link AtomicInteger} and
 * {@link Math#floorMod}; {@link #shardFor(long)} and {@link #execute(long, ShardTask)} mix
 * the key with the splitmix64 finalizer, so the same key always targets the same shard for
 * the group's lifetime. Per-producer FIFO ordering holds within one shard's inbound queue;
 * cross-producer total order is unspecified.
 *
 * <p>B5 observability: {@link #metrics()} returns per-shard {@link ShardMetrics} snapshots in
 * shard-id order and {@link #aggregateMetrics()} returns their frozen aggregation
 * ({@code docs/b5-design.md} §2.6); both are safe from any thread at any life stage.
 *
 * <p>See {@code docs/b4-design.md} §2.4/§8.
 */
public final class RingGroup implements AutoCloseable {

    private static final int MAX_SHARDS = 1024;

    private final IoShard[] shards;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final Object lifecycle = new Object();
    private volatile boolean closed;
    private volatile boolean stopping;

    private RingGroup(IoShard[] shards) {
        this.shards = shards;
    }

    /**
     * Creates a group of {@code shardCount} unstarted shards.
     *
     * @param shardCount number of shards/rings, {@code 1..1024}
     * @param options    per-shard options; a non-{@code NO_CPU_AFFINITY} cpu affinity is
     *                   used as the base CPU (shard {@code i} pinned to {@code base + i})
     */
    public static RingGroup create(int shardCount, ShardOptions options) {
        Objects.requireNonNull(options, "options");
        int firstCpu = options.cpuAffinity() == ShardOptions.NO_CPU_AFFINITY
                ? -1 : options.cpuAffinity();
        return create(shardCount, options, firstCpu);
    }

    /**
     * Creates a group of {@code shardCount} unstarted shards with an explicit affinity base
     * CPU: shard {@code i} is pinned to {@code firstCpu + i}. A negative {@code firstCpu}
     * means no affinity (even when {@code options.cpuAffinity()} is set).
     */
    public static RingGroup create(int shardCount, ShardOptions options, int firstCpu) {
        Objects.requireNonNull(options, "options");
        if (shardCount < 1 || shardCount > MAX_SHARDS) {
            throw new IllegalArgumentException("shardCount must be in 1.." + MAX_SHARDS + ": " + shardCount);
        }
        IoShard[] created = new IoShard[shardCount];
        for (int i = 0; i < shardCount; i++) {
            ShardOptions shardOptions = firstCpu < 0
                    ? options.withCpuAffinity(ShardOptions.NO_CPU_AFFINITY)
                    : options.withCpuAffinity(firstCpu + i);
            created[i] = IoShard.create(i, shardOptions);
        }
        return new RingGroup(created);
    }

    /** @return the number of shards (and rings) in the group */
    public int size() {
        return shards.length;
    }

    /**
     * @param index shard index
     * @return the shard
     * @throws IllegalArgumentException when {@code index} is out of range
     */
    public IoShard shard(int index) {
        if (index < 0 || index >= shards.length) {
            throw new IllegalArgumentException("shard index out of range [0, " + shards.length + "): " + index);
        }
        return shards[index];
    }

    /**
     * Stable hash distribution: the same key always maps to the same shard for the group's
     * lifetime (splitmix64 finalizer, {@link Math#floorMod}).
     */
    public IoShard shardFor(long key) {
        return shards[Math.floorMod(mix64(key), shards.length)];
    }

    /** Round-robin distribution; never creates or reorders shards. */
    public IoShard nextShard() {
        int index = (int) Math.floorMod(roundRobin.getAndIncrement(), shards.length);
        return shards[index];
    }

    /** Starts all shards eagerly; on failure closes the already-started shards and rethrows. */
    public void start() {
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("RingGroup is closed");
            }
        }
        int started = 0;
        try {
            for (IoShard shard : shards) {
                shard.start();
                started++;
            }
        } catch (Throwable t) {
            for (int i = 0; i < started; i++) {
                try {
                    shards[i].close();
                } catch (Throwable ignored) {
                    // best effort: propagate the original failure
                }
            }
            throw t;
        }
    }

    /** Enqueues a task on the next shard (round-robin); lazily starts it. */
    public void execute(ShardTask task) {
        Objects.requireNonNull(task, "task");
        nextShard().execute(task);
    }

    /** Enqueues a task on the shard selected by {@code hash(key)}; lazily starts it. */
    public void execute(long key, ShardTask task) {
        Objects.requireNonNull(task, "task");
        shardFor(key).execute(task);
    }

    /** Enqueues a continuation on an explicit target shard (MPSC + eventfd). */
    public void handoff(IoShard target, Runnable continuation) {
        Objects.requireNonNull(target, "target");
        target.handoff(continuation);
    }

    /** @return one stats snapshot per shard, in shard order */
    public List<ShardStats> stats() {
        ShardStats[] snapshot = new ShardStats[shards.length];
        for (int i = 0; i < shards.length; i++) {
            snapshot[i] = shards[i].stats();
        }
        return List.of(snapshot);
    }

    /**
     * Per-shard B5 metrics in shard-id order (unstarted and closed shards included). Safe
     * from any thread; never throws.
     */
    public List<ShardMetrics> metrics() {
        ShardMetrics[] snapshot = new ShardMetrics[shards.length];
        for (int i = 0; i < shards.length; i++) {
            snapshot[i] = shards[i].metrics();
        }
        return List.of(snapshot);
    }

    /**
     * Aggregate of the group's shard metrics: summed ring counters/occupancy and the
     * bucket-wise merged dispatch-latency histogram ({@code docs/b5-design.md} §2.6). Safe
     * from any thread; never throws. A group of unstarted shards aggregates to zeroes and
     * {@link LatencySnapshot#empty()}.
     */
    public GroupMetrics aggregateMetrics() {
        long submitted = 0L;
        long reaped = 0L;
        long completed = 0L;
        long failed = 0L;
        long cancelled = 0L;
        long timedOut = 0L;
        long inFlight = 0L;
        long sqQueued = 0L;
        long cqReady = 0L;
        LatencyHistogram mergedLatency = new LatencyHistogram();
        for (IoShard shard : shards) {
            ShardMetrics metrics;
            try {
                metrics = shard.metrics();
            } catch (Throwable ignored) {
                // Snapshots are total by contract; keep the aggregate total defensively.
                continue;
            }
            RingMetrics ring = metrics.ring();
            submitted += ring.submitted();
            reaped += ring.reaped();
            completed += ring.completed();
            failed += ring.failed();
            cancelled += ring.cancelled();
            timedOut += ring.timedOut();
            inFlight += ring.inFlight();
            sqQueued += ring.sqQueued();
            cqReady += ring.cqReady();
            shard.mergeDispatchLatencyInto(mergedLatency);
        }
        LatencySnapshot dispatchLatency;
        try {
            dispatchLatency = mergedLatency.snapshot();
        } catch (Throwable ignored) {
            dispatchLatency = LatencySnapshot.empty();
        }
        return new GroupMetrics(shards.length, submitted, reaped, completed, failed, cancelled,
                timedOut, inFlight, sqQueued, cqReady, dispatchLatency);
    }

    /** Non-blocking stop request for every shard. */
    public void requestStop() {
        synchronized (lifecycle) {
            stopping = true;
        }
        for (IoShard shard : shards) {
            shard.requestStop();
        }
    }

    /**
     * Waits for every shard thread to end against one shared timeout budget.
     *
     * @return whether all shard threads ended within {@code timeoutMillis}
     */
    public boolean awaitTermination(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        boolean all = true;
        for (IoShard shard : shards) {
            long remainingNanos = Math.max(0L, deadline - System.nanoTime());
            boolean ended = shard.awaitTermination(remainingNanos / 1_000_000L);
            if (!ended) {
                all = false;
            }
        }
        return all;
    }

    /**
     * Closes the group with the largest per-shard shutdown budget (see
     * {@link #close(long)}).
     */
    @Override
    public void close() {
        long maxBudget = 0L;
        for (IoShard shard : shards) {
            maxBudget = Math.max(maxBudget, shard.options().shutdownTimeoutMillis());
        }
        close(maxBudget);
    }

    /**
     * Closes every shard against one shared deadline: requests a stop on all shards first,
     * then joins each against the same absolute deadline so group shutdown is bounded by
     * {@code timeoutMillis + 250 ms} (not {@code N * timeoutMillis}). Idempotent.
     *
     * @param timeoutMillis shared graceful-shutdown budget in milliseconds ({@code >= 0})
     */
    public void close(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            stopping = true;
        }
        for (IoShard shard : shards) {
            shard.requestStop();
        }
        long budgetDeadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        long hardDeadline = budgetDeadline + 250_000_000L;
        for (IoShard shard : shards) {
            shard.closeUntil(hardDeadline, budgetDeadline);
        }
    }

    // ------------------------------------------------------------------
    // Package-private body-install seam (B4 §9, JUringBlocking integration)
    // ------------------------------------------------------------------

    /**
     * Package-private: installs a custom loop body on one unstarted shard. Only valid before
     * the group (and shard) is started.
     */
    void installBody(int index, ShardBody loopBody) {
        Objects.requireNonNull(loopBody, "body");
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("RingGroup is closed");
            }
            shard(index).setBody(loopBody);
        }
    }

    /** Package-private: {@code true} once any shard has been started. */
    boolean isStarted() {
        for (IoShard shard : shards) {
            if (shard.isStarted()) {
                return true;
            }
        }
        return false;
    }

    /** Package-private: {@code true} once {@link #close(long)} (or a shard close) ran. */
    boolean isClosed() {
        if (closed) {
            return true;
        }
        for (IoShard shard : shards) {
            if (shard.isClosed()) {
                return true;
            }
        }
        return false;
    }

    /** Package-private: {@code true} when a stop was requested. */
    boolean isStopping() {
        return stopping;
    }

    /** Splitmix64 finalizer (stable across JVMs, no rebalancing). */
    private static int mix64(long key) {
        long z = key + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (int) (z ^ (z >>> 32));
    }
}
