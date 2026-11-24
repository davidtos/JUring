package com.davidvlijmincx.lio.api;

import java.util.Objects;

/**
 * Configuration for a {@link JUringBlocking} instance.
 *
 * <p>The options separate the three quantities that used to be coupled through the requested
 * queue depth:
 * <ul>
 *   <li>{@link #withQueueDepth(int) queue depth} — submission-queue entries of every worker
 *       ring (the hardware in-flight budget);</li>
 *   <li>{@link #withPollers(int) pollers} — number of worker rings and poller threads;</li>
 *   <li>{@link #withMaxConcurrentCalls(int) max concurrent calls} — how many blocking calls
 *       may be admitted (parked or in flight) at once. Every admitted call owns one
 *       pre-allocated record, so this value also fixes the memory bound: roughly
 *       {@link #estimatedBytes()} bytes.</li>
 * </ul>
 *
 * <p>Capacity is rounded up to a power of two per worker and is at least {@value #MIN_RECORDS}
 * records per worker. {@link #estimatedBytes()} reports the resulting heap footprint, which is
 * dominated by the call records ({@value #BYTES_PER_RECORD} bytes each, including the
 * submission-queue entry).
 *
 * <p>Instances are immutable; every {@code with*} method returns a new instance.
 *
 * @see JUringBlocking
 * @see OverflowPolicy
 */
public final class BlockingOptions {

    /** Default per-worker {@code io_uring} submission-queue entries. */
    public static final int DEFAULT_QUEUE_DEPTH = 256;

    /** Smallest call-record pool per worker (keeps the lock-free queues comfortably sized). */
    static final int MIN_RECORDS = 256;

    /** Guard against a byte-size arithmetic overflow in {@link #estimatedBytes()}. */
    private static final int MAX_RECORDS_PER_WORKER = 1 << 22;

    /** Largest total concurrent-call budget accepted (about 1.5 GiB of records). */
    private static final int MAX_TOTAL_RECORDS = 16 * (1 << 20);

    /** Highest poller count accepted (each poller is one platform thread and one ring). */
    static final int MAX_POLLERS = 1024;

    /** Largest queue depth accepted (the kernel's {@code IORING_MAX_ENTRIES} is 32768). */
    static final int MAX_QUEUE_DEPTH = 32768;

    /**
     * Estimated heap bytes per pre-allocated call record: the 13 request/result arrays
     * ({@code byte/byte/int/long/long/int/int/short/int/long/long/int/Thread} = 64 bytes), the
     * free-list entry ({@code int + long} = 12 bytes) and the MPSC entry
     * ({@code int + long} = 12 bytes).
     */
    static final long BYTES_PER_RECORD = 88L;

    /**
     * Rough per-worker overhead of the queue/free-list array headers, the ready-cancellation
     * ring and the worker object.
     */
    private static final long BYTES_PER_WORKER = 4096L;

    private static final BlockingOptions DEFAULTS =
            new BlockingOptions(DEFAULT_QUEUE_DEPTH, 0, 0, OverflowPolicy.BLOCK, 0, false);

    private final int queueDepth;
    private final int pollers;
    private final int maxConcurrentCalls;
    private final OverflowPolicy overflow;
    private final int preParkSpins;
    /** {@code true} when {@link #pollers} is an exact count (a caller-supplied ring group). */
    private final boolean pollersExact;

    private BlockingOptions(int queueDepth, int pollers, int maxConcurrentCalls,
                            OverflowPolicy overflow, int preParkSpins, boolean pollersExact) {
        this.queueDepth = queueDepth;
        this.pollers = pollers;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.overflow = overflow;
        this.preParkSpins = preParkSpins;
        this.pollersExact = pollersExact;
    }

    /**
     * Options for a {@link RingGroup} adopted by {@link JUringBlocking}: the group's own shard
     * count is authoritative and is reported verbatim by {@link #resolvedPollers()}.
     *
     * @param queueDepth   per-worker ring entries of the adopted group
     * @param pollers      the group's shard count
     * @return options whose poller count is exact
     */
    static BlockingOptions forGroup(int queueDepth, int pollers) {
        return new BlockingOptions(queueDepth, Math.max(1, pollers), 0,
                OverflowPolicy.BLOCK, 0, true);
    }

    /**
     * Returns the defaults: queue depth {@value #DEFAULT_QUEUE_DEPTH}, a CPU-derived poller
     * count, a call-record pool derived from the queue depth, {@link OverflowPolicy#BLOCK}
     * and no pre-park spinning.
     *
     * @return the default options
     */
    public static BlockingOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different per-worker submission-queue depth (ring entries).
     *
     * @param queueDepth entries in {@code 1..32768}; the effective ring has at least 8
     * @return a new options instance
     * @throws IllegalArgumentException when the depth is out of range
     */
    public BlockingOptions withQueueDepth(int queueDepth) {
        if (queueDepth < 1 || queueDepth > MAX_QUEUE_DEPTH) {
            throw new IllegalArgumentException("queueDepth must be in 1.." + MAX_QUEUE_DEPTH + ": " + queueDepth);
        }
        return new BlockingOptions(queueDepth, pollers, maxConcurrentCalls, overflow, preParkSpins, false);
    }

    /**
     * Returns a copy with an explicit poller count. The value is rounded down to a power of two
     * (minimum one), matching the {@link JUringBlocking#JUringBlocking(int, int,
     * IoUringOptions...)} constructor.
     *
     * @param pollers requested pollers, {@code 1..1024}
     * @return a new options instance
     * @throws IllegalArgumentException when the value is out of range
     */
    public BlockingOptions withPollers(int pollers) {
        if (pollers < 1 || pollers > MAX_POLLERS) {
            throw new IllegalArgumentException("pollers must be in 1.." + MAX_POLLERS + ": " + pollers);
        }
        return new BlockingOptions(queueDepth, pollers, maxConcurrentCalls, overflow, preParkSpins, false);
    }

    /**
     * Returns a copy with an explicit concurrent-call budget: how many blocking calls may be
     * admitted (parked or in flight) across all workers. The budget is split evenly over the
     * workers and each share is rounded up to a power of two with a {@value #MIN_RECORDS}
     * minimum, so the effective total can exceed the requested value.
     *
     * @param maxConcurrentCalls total admitted calls, or {@code 0} to derive the budget from
     *                           the queue depth (the 0.1.0 behaviour)
     * @return a new options instance
     * @throws IllegalArgumentException when negative or above the safety limit
     */
    public BlockingOptions withMaxConcurrentCalls(int maxConcurrentCalls) {
        if (maxConcurrentCalls < 0) {
            throw new IllegalArgumentException("maxConcurrentCalls must be >= 0: " + maxConcurrentCalls);
        }
        if (maxConcurrentCalls > MAX_TOTAL_RECORDS) {
            throw new IllegalArgumentException("maxConcurrentCalls must be <= " + MAX_TOTAL_RECORDS
                    + " (that is already ~1.5 GiB of call records): " + maxConcurrentCalls);
        }
        return new BlockingOptions(queueDepth, pollers, maxConcurrentCalls, overflow, preParkSpins, false);
    }

    /**
     * Returns a copy with a different oversubscription policy.
     *
     * @param overflow the policy, must not be {@code null}
     * @return a new options instance
     */
    public BlockingOptions withOverflow(OverflowPolicy overflow) {
        return new BlockingOptions(queueDepth, pollers, maxConcurrentCalls,
                Objects.requireNonNull(overflow, "overflow"), preParkSpins, false);
    }

    /**
     * Returns a copy with a bounded spin before a blocked caller parks. A short spin avoids a
     * park/unpark round trip for completions that arrive within a few hundred nanoseconds
     * (page-cache hits) at the cost of burning the carrier while spinning; the default
     * {@code 0} keeps the previous behaviour.
     *
     * @param preParkSpins iterations of {@link Thread#onSpinWait()}, {@code >= 0}
     * @return a new options instance
     * @throws IllegalArgumentException when negative
     */
    public BlockingOptions withPreParkSpins(int preParkSpins) {
        if (preParkSpins < 0) {
            throw new IllegalArgumentException("preParkSpins must be >= 0: " + preParkSpins);
        }
        return new BlockingOptions(queueDepth, pollers, maxConcurrentCalls, overflow, preParkSpins, false);
    }

    /**
     * Returns the configured per-worker submission-queue depth.
     *
     * @return the queue depth
     */
    public int queueDepth() {
        return queueDepth;
    }

    /**
     * Returns the configured poller count, or {@code 0} when it is derived from the available
     * processors (and the {@code juring.blocking.pollers} system property).
     *
     * @return the configured poller count or {@code 0}
     */
    public int pollers() {
        return pollers;
    }

    /**
     * Returns the configured total concurrent-call budget, or {@code 0} when it is derived
     * from the queue depth.
     *
     * @return the configured budget or {@code 0}
     */
    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    /**
     * Returns the oversubscription policy.
     *
     * @return the policy
     */
    public OverflowPolicy overflow() {
        return overflow;
    }

    /**
     * Returns the bounded pre-park spin count.
     *
     * @return the spin count, {@code 0} when disabled
     */
    public int preParkSpins() {
        return preParkSpins;
    }

    /**
     * Resolves the number of workers (rings and poller threads) these options create. An
     * explicit {@link #withPollers(int)} is rounded down to a power of two; a
     * {@link RingGroup}-adopted configuration reports the group's shard count verbatim.
     *
     * @return the resolved poller count, {@code >= 1}
     */
    public int resolvedPollers() {
        if (pollers > 0) {
            return pollersExact ? pollers : Math.max(1, Integer.highestOneBit(pollers));
        }
        return defaultPollers(queueDepth);
    }

    /**
     * Returns the call-record capacity of every worker for the given worker count: a power of
     * two, at least {@value #MIN_RECORDS}.
     *
     * @param workerCount resolved worker count
     * @return the per-worker record capacity
     */
    int perWorkerCapacity(int workerCount) {
        return perWorkerCapacityFor(workerCount, queueDepth, maxConcurrentCalls);
    }

    private static int perWorkerCapacityFor(int workerCount, int queueDepth, int maxConcurrentCalls) {
        int workers = Math.max(1, workerCount);
        long perWorker;
        if (maxConcurrentCalls <= 0) {
            perWorker = Math.max((long) MIN_RECORDS, (long) queueDepth * 8L);
        } else {
            perWorker = ((long) maxConcurrentCalls + workers - 1) / workers;
            perWorker = Math.max((long) MIN_RECORDS, perWorker);
        }
        perWorker = Math.min(perWorker, MAX_RECORDS_PER_WORKER);
        return nextPowerOfTwo((int) perWorker);
    }

    /**
     * Estimates the heap footprint of the call-record pools these options create (queue entries
     * included). This is the dominant, tunable cost; the caller's own buffers are extra.
     *
     * @return estimated heap bytes
     */
    public long estimatedBytes() {
        int workers = resolvedPollers();
        long perWorker = perWorkerCapacity(workers);
        return workers * (perWorker * BYTES_PER_RECORD + BYTES_PER_WORKER);
    }

    /**
     * Derives the default poller count from the queue depth, the available processors and the
     * {@code juring.blocking.pollers} system property (which is clamped to 1..16).
     *
     * @param queueDepth per-worker ring entries
     * @return the default poller count, {@code >= 1}
     */
    static int defaultPollers(int queueDepth) {
        String prop = System.getProperty("juring.blocking.pollers");
        if (prop != null && !prop.isBlank()) {
            try {
                int p = Integer.parseInt(prop.trim());
                if (p > 0) return Integer.highestOneBit(Math.min(16, p));
            } catch (NumberFormatException ignored) {
                // fall through to the CPU-derived default
            }
        }
        if (queueDepth < 64) {
            return 1;
        }
        int procs = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, Integer.highestOneBit(procs / 4)));
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) {
            return 1;
        }
        int highest = Integer.highestOneBit(value);
        return highest == value ? highest : highest << 1;
    }

    @Override
    public String toString() {
        return "BlockingOptions{queueDepth=" + queueDepth
                + ", pollers=" + (pollers > 0 ? pollers : "auto")
                + ", maxConcurrentCalls=" + (maxConcurrentCalls > 0 ? maxConcurrentCalls : "auto")
                + ", overflow=" + overflow
                + ", preParkSpins=" + preParkSpins
                + ", resolvedPollers=" + resolvedPollers()
                + ", estimatedBytes=" + estimatedBytes()
                + '}';
    }
}
