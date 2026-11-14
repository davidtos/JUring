package com.davidvlijmincx.lio.api;

/**
 * JMX MXBean view of one {@link IoShard} (B5 design §2.7).
 *
 * <p>Attributes are <b>pull-based</b>: every getter takes a fresh {@link IoShard#metrics()}
 * snapshot at read time, so there is no exporter thread and no background memory cost. Reads
 * never throw, including for a closed or failed shard. Counters are {@code long}s, latency
 * values are <b>microseconds</b> as {@code double} (exactly {@code 0.0} when sampling is
 * disabled or no sample was recorded), booleans are booleans. Accessor names follow the
 * JavaBean convention, so the JMX attribute names are e.g. {@code Submitted}, {@code CqReady}
 * and {@code LatencyP99Micros}.
 *
 * <p>Register/obtain handles through {@link MetricsJmx}; the deterministic {@code ObjectName}
 * scheme is {@code com.davidvlijmincx.lio.api:type=IoShard,instance=<N>,id=<shardId>} for a
 * standalone shard and {@code ...:type=IoShard,group=<N>,id=<shardId>} for a shard registered
 * as part of a group.
 */
public interface IoShardMetricsMXBean {

    /** @return the shard id (0-based within its {@link RingGroup}) */
    int getShardId();

    /** @return the shard platform thread name, or {@code null} before start */
    String getThreadName();

    /** @return the CPU observed at start, or {@code -1} before start */
    int getCurrentCpu();

    /** @return whether the configured CPU affinity was applied */
    boolean isAffinityApplied();

    /** @return whether the shard loop is currently running */
    boolean isStarted();

    /** @return whether a stop was requested */
    boolean isStopping();

    /** @return whether the shard is closed */
    boolean isClosed();

    // --- ring counters / occupancy (JUring.ringMetrics, nanoseconds-free; see RingMetrics) ---

    long getSqesPrepared();

    long getSubmitted();

    long getReaped();

    long getCompleted();

    long getFailed();

    long getCancelled();

    long getTimedOut();

    long getInFlight();

    long getEnters();

    long getSubmitBatches();

    long getSubmitErrors();

    long getSubmitRetries();

    long getSqFullEvents();

    long getSqQueued();

    int getSqEntries();

    long getCqReady();

    int getCqEntries();

    long getCqOverflowEvents();

    // --- shard counters (B4 ShardStats) ---

    long getTasksSubmitted();

    long getTasksExecuted();

    long getTaskFailures();

    long getTasksRejected();

    long getCompletionsRegistered();

    long getCompletionsDispatched();

    long getCompletionsUnattributed();

    long getCompletionFailures();

    long getHandoffsSent();

    long getHandoffsInlineFallback();

    long getEventfdWrites();

    long getRingSubmits();

    long getCancelledAtClose();

    long getAbandonedAtClose();

    boolean isCloseTimedOut();

    // --- completion-dispatch latency (microseconds; 0 when disabled/empty) ---

    /** @return number of recorded dispatch-latency samples */
    long getLatencySamples();

    double getLatencyMeanMicros();

    double getLatencyP50Micros();

    double getLatencyP90Micros();

    double getLatencyP99Micros();

    double getLatencyP999Micros();

    double getLatencyMaxMicros();

    double getLatencyMinMicros();
}
