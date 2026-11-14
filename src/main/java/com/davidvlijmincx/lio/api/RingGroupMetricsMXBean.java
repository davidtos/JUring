package com.davidvlijmincx.lio.api;

/**
 * JMX MXBean view of one {@link RingGroup} aggregate (B5 design §2.7).
 *
 * <p>Attributes are <b>pull-based</b>: every getter takes a fresh
 * {@link RingGroup#aggregateMetrics()} snapshot at read time (no exporter thread, no
 * background cost) and never throws, including after the group was closed. Counters are
 * {@code long}s, latency values are <b>microseconds</b> as {@code double} (exactly
 * {@code 0.0} when no dispatch-latency sample was recorded).
 *
 * <p>The deterministic {@code ObjectName} scheme is
 * {@code com.davidvlijmincx.lio.api:type=RingGroup,instance=<N>}; the group's shard beans are
 * registered alongside it with {@code type=IoShard,group=<N>,id=<shardId>}. Obtain handles
 * through {@link MetricsJmx#register(RingGroup)}.
 */
public interface RingGroupMetricsMXBean {

    /** @return number of shards in the group */
    int getShards();

    long getSubmitted();

    long getReaped();

    long getCompleted();

    long getFailed();

    long getCancelled();

    long getTimedOut();

    long getInFlight();

    long getSqQueued();

    long getCqReady();

    /** @return number of recorded dispatch-latency samples (merged over shards) */
    long getLatencySamples();

    double getLatencyMeanMicros();

    double getLatencyP50Micros();

    double getLatencyP90Micros();

    double getLatencyP99Micros();

    double getLatencyP999Micros();

    double getLatencyMaxMicros();

    double getLatencyMinMicros();
}
