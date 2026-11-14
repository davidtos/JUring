package com.davidvlijmincx.lio.api;

import java.util.Arrays;
import java.util.Objects;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;

/**
 * Immutable configuration for one {@link IoShard} (and, through {@link RingGroup}, for a
 * fixed pool of shards).
 *
 * <p>Instances are built either with {@link #defaults()} plus {@code with*} copy methods or,
 * for the default values, through the public constants. Every {@code with*} method returns
 * a new instance; validation failures are {@link IllegalArgumentException}s.
 *
 * <p>See {@code docs/b4-design.md} §2.1 for the frozen contract.
 */
public final class ShardOptions {

    /** Requested SQE ring depth ({@code >= 8}); the kernel may round it up. */
    public static final int DEFAULT_RING_ENTRIES = 256;
    /** Inbound tasks/handoffs executed per loop pass (completions are serviced between passes). */
    public static final int DEFAULT_TASK_BATCH = 64;
    /** Completions reaped and dispatched per loop pass. */
    public static final int DEFAULT_CQE_BATCH = 256;
    /** Idle {@link Thread#onSpinWait()} iterations before the loop blocks in the kernel. */
    public static final int DEFAULT_SPIN_ITERATIONS = 1024;
    /** Inbound MPSC slots (power of two, {@code >= 2}). */
    public static final int DEFAULT_INBOUND_CAPACITY = 1024;
    /** Default graceful-shutdown budget used by {@link IoShard#close()}. */
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 2000L;
    /** Sentinel for "no CPU affinity". */
    public static final int NO_CPU_AFFINITY = -1;
    /** Default dispatch-latency sampling interval: {@code 0} = disabled (B5 design §2.4). */
    public static final int DEFAULT_LATENCY_SAMPLE_EVERY = 0;

    private static final IoUringOptions[] DEFAULT_RING_OPTIONS = {
            IORING_SETUP_SINGLE_ISSUER,
            IORING_SETUP_COOP_TASKRUN,
            IORING_SETUP_DEFER_TASKRUN
    };

    private static final ShardOptions DEFAULTS = new ShardOptions(
            "shard",
            DEFAULT_RING_ENTRIES,
            DEFAULT_RING_OPTIONS,
            NO_CPU_AFFINITY,
            DEFAULT_TASK_BATCH,
            DEFAULT_CQE_BATCH,
            DEFAULT_SPIN_ITERATIONS,
            DEFAULT_INBOUND_CAPACITY,
            DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
            DEFAULT_LATENCY_SAMPLE_EVERY);

    private final String name;
    private final int ringEntries;
    private final IoUringOptions[] ringOptions;
    private final int cpuAffinity;
    private final int taskBatch;
    private final int cqeBatch;
    private final int spinIterations;
    private final int inboundCapacity;
    private final long shutdownTimeoutMillis;
    private final int latencySampleEvery;

    private ShardOptions(String name,
                         int ringEntries,
                         IoUringOptions[] ringOptions,
                         int cpuAffinity,
                         int taskBatch,
                         int cqeBatch,
                         int spinIterations,
                         int inboundCapacity,
                         long shutdownTimeoutMillis,
                         int latencySampleEvery) {
        this.name = name;
        this.ringEntries = ringEntries;
        this.ringOptions = ringOptions;
        this.cpuAffinity = cpuAffinity;
        this.taskBatch = taskBatch;
        this.cqeBatch = cqeBatch;
        this.spinIterations = spinIterations;
        this.inboundCapacity = inboundCapacity;
        this.shutdownTimeoutMillis = shutdownTimeoutMillis;
        this.latencySampleEvery = latencySampleEvery;
    }

    /** Default shard configuration: 256-entry ring, the proven three ring flags, no affinity. */
    public static ShardOptions defaults() {
        return DEFAULTS;
    }

    /** Human-readable shard name; appears in the platform thread name. */
    public String name() {
        return name;
    }

    /** Requested ring depth ({@code >= 8}); effective depth may be rounded up by the kernel. */
    public int ringEntries() {
        return ringEntries;
    }

    /** Defensive copy of the ring setup flags used to create the shard's ring. */
    public IoUringOptions[] ringOptions() {
        return ringOptions.clone();
    }

    /** {@link #NO_CPU_AFFINITY} or a logical CPU id. */
    public int cpuAffinity() {
        return cpuAffinity;
    }

    /** Inbound tasks/handoffs executed per loop pass ({@code >= 1}). */
    public int taskBatch() {
        return taskBatch;
    }

    /** Completions reaped/dispatched per loop pass ({@code >= 1}). */
    public int cqeBatch() {
        return cqeBatch;
    }

    /** Idle spin iterations before blocking in the kernel ({@code >= 0}). */
    public int spinIterations() {
        return spinIterations;
    }

    /** Inbound MPSC capacity (power of two, {@code >= 2}). */
    public int inboundCapacity() {
        return inboundCapacity;
    }

    /** Graceful-shutdown budget in milliseconds ({@code >= 0}). */
    public long shutdownTimeoutMillis() {
        return shutdownTimeoutMillis;
    }

    /**
     * Dispatch-latency sampling interval: {@code 0} = disabled; {@code 1} = measure every
     * registered completion; {@code N >= 2} = measure every Nth successful
     * {@link ShardContext#onCompletion(long, CompletionHandler)} registration (deterministic,
     * 1-based, per shard). See {@code docs/b5-design.md} §2.4/§4.
     */
    public int latencySampleEvery() {
        return latencySampleEvery;
    }

    /** @return a copy with a new shard name (thread name component) */
    public ShardOptions withName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /** @return a copy with a new requested ring depth ({@code >= 8}) */
    public ShardOptions withRingEntries(int ringEntries) {
        if (ringEntries < 8) {
            throw new IllegalArgumentException("ringEntries must be >= 8: " + ringEntries);
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /**
     * @return a copy whose ring setup flags are <b>replaced</b> (not merged) by
     * {@code ringOptions}; no arguments means "no setup flags"
     */
    public ShardOptions withRingOptions(IoUringOptions... ringOptions) {
        Objects.requireNonNull(ringOptions, "ringOptions");
        IoUringOptions[] copy = ringOptions.clone();
        for (int i = 0; i < copy.length; i++) {
            Objects.requireNonNull(copy[i], "ringOptions[" + i + "]");
        }
        return new ShardOptions(name, ringEntries, copy, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /**
     * @return a copy pinned to {@code cpuAffinity} ({@link #NO_CPU_AFFINITY} for none)
     * @throws IllegalArgumentException if {@code cpuAffinity < NO_CPU_AFFINITY}
     */
    public ShardOptions withCpuAffinity(int cpuAffinity) {
        if (cpuAffinity < NO_CPU_AFFINITY) {
            throw new IllegalArgumentException("cpuAffinity must be " + NO_CPU_AFFINITY
                    + " (none) or a logical CPU id: " + cpuAffinity);
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /** @return a copy with a new inbound task batch size ({@code >= 1}) */
    public ShardOptions withTaskBatch(int taskBatch) {
        if (taskBatch < 1) {
            throw new IllegalArgumentException("taskBatch must be >= 1: " + taskBatch);
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /** @return a copy with a new completion batch size ({@code >= 1}) */
    public ShardOptions withCqeBatch(int cqeBatch) {
        if (cqeBatch < 1) {
            throw new IllegalArgumentException("cqeBatch must be >= 1: " + cqeBatch);
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /** @return a copy with a new idle spin count ({@code >= 0}) */
    public ShardOptions withSpinIterations(int spinIterations) {
        if (spinIterations < 0) {
            throw new IllegalArgumentException("spinIterations must be >= 0: " + spinIterations);
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /** @return a copy with a new inbound MPSC capacity (power of two, {@code >= 2}) */
    public ShardOptions withInboundCapacity(int inboundCapacity) {
        if (inboundCapacity < 2 || Integer.bitCount(inboundCapacity) != 1) {
            throw new IllegalArgumentException("inboundCapacity must be a power of two >= 2: " + inboundCapacity);
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /** @return a copy with a new graceful-shutdown budget ({@code >= 0} ms) */
    public ShardOptions withShutdownTimeoutMillis(long shutdownTimeoutMillis) {
        if (shutdownTimeoutMillis < 0) {
            throw new IllegalArgumentException("shutdownTimeoutMillis must be >= 0: " + shutdownTimeoutMillis);
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    /**
     * @return a copy with a new dispatch-latency sampling interval ({@code 0} = disabled,
     * {@code >= 1} = every Nth successful registration)
     * @throws IllegalArgumentException when {@code latencySampleEvery < 0}
     */
    public ShardOptions withLatencySampleEvery(int latencySampleEvery) {
        if (latencySampleEvery < 0) {
            throw new IllegalArgumentException("latencySampleEvery must be >= 0: " + latencySampleEvery);
        }
        return new ShardOptions(name, ringEntries, ringOptions, cpuAffinity, taskBatch, cqeBatch,
                spinIterations, inboundCapacity, shutdownTimeoutMillis, latencySampleEvery);
    }

    @Override
    public String toString() {
        return "ShardOptions{name=" + name
                + ", ringEntries=" + ringEntries
                + ", ringOptions=" + Arrays.toString(ringOptions)
                + ", cpuAffinity=" + cpuAffinity
                + ", taskBatch=" + taskBatch
                + ", cqeBatch=" + cqeBatch
                + ", spinIterations=" + spinIterations
                + ", inboundCapacity=" + inboundCapacity
                + ", shutdownTimeoutMillis=" + shutdownTimeoutMillis
                + ", latencySampleEvery=" + latencySampleEvery
                + '}';
    }
}
