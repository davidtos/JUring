package com.davidvlijmincx.lio.api;

/**
 * JMX MXBean view of one {@link RingGroup} aggregate.
 *
 * <p>Attributes are <b>pull-based</b> and never throw, including after the group was closed.
 * A bean serves every attribute from one coherent cached {@link RingGroup#aggregateMetrics()}
 * snapshot per short refresh window (50 ms, see {@link MetricsJmx}), so the aggregated counters
 * and the merged dispatch-latency histogram of a single scrape describe one instant instead of
 * being recomputed per getter. Values are therefore at most one window old, and a scrape that
 * spans a window boundary can mix two adjacent snapshots; there is no exporter thread and no
 * background work — refreshes happen on the reading thread. Counters are
 * {@code long}s, latency values are <b>microseconds</b> as {@code double} (exactly
 * {@code 0.0} when no dispatch-latency sample was recorded).
 *
 * <p>The deterministic {@code ObjectName} scheme is
 * {@code com.davidvlijmincx.lio.api:type=RingGroup,instance=<N>}; the group's shard beans are
 * registered alongside it with {@code type=IoShard,group=<N>,id=<shardId>}. Obtain handles
 * through {@link MetricsJmx#register(RingGroup)}.
 */
public interface RingGroupMetricsMXBean {

    /**
     * Returns the number of shards in the group.
     *
     * @return the group size
     */
    int getShards();

    /**
     * Returns the summed ring {@link RingMetrics#submitted()} counter over all shards.
     *
     * @return the summed submitted-SQE counter
     */
    long getSubmitted();

    /**
     * Returns the summed ring {@link RingMetrics#reaped()} counter over all shards.
     *
     * @return the summed reaped-CQE counter
     */
    long getReaped();

    /**
     * Returns the summed ring {@link RingMetrics#completed()} counter over all shards.
     *
     * @return the summed completed-operation counter
     */
    long getCompleted();

    /**
     * Returns the summed ring {@link RingMetrics#failed()} counter over all shards.
     *
     * @return the summed failed-operation counter
     */
    long getFailed();

    /**
     * Returns the summed ring {@link RingMetrics#cancelled()} counter over all shards.
     *
     * @return the summed cancelled-operation counter
     */
    long getCancelled();

    /**
     * Returns the summed ring {@link RingMetrics#timedOut()} counter over all shards.
     *
     * @return the summed timed-out-operation counter
     */
    long getTimedOut();

    /**
     * Returns the summed ring {@link RingMetrics#inFlight()} count over all shards.
     *
     * @return the summed in-flight request count
     */
    long getInFlight();

    /**
     * Returns the summed SQ occupancy over all shards.
     *
     * @return the summed SQ occupancy
     */
    long getSqQueued();

    /**
     * Returns the summed CQ occupancy over all shards.
     *
     * @return the summed CQ occupancy
     */
    long getCqReady();

    /**
     * Returns the number of recorded dispatch-latency samples (merged over shards).
     *
     * @return the merged sample count
     */
    long getLatencySamples();

    /**
     * Returns the mean completion-dispatch latency in microseconds, merged over shards.
     *
     * @return the mean latency, or {@code 0.0} when no sample was recorded
     */
    double getLatencyMeanMicros();

    /**
     * Returns the 50th-percentile completion-dispatch latency in microseconds, merged over
     * shards.
     *
     * @return the 50th-percentile latency (a bucket maximum; {@code 0.0} when no sample was
     *         recorded)
     */
    double getLatencyP50Micros();

    /**
     * Returns the 90th-percentile completion-dispatch latency in microseconds, merged over
     * shards.
     *
     * @return the 90th-percentile latency (a bucket maximum; {@code 0.0} when no sample was
     *         recorded)
     */
    double getLatencyP90Micros();

    /**
     * Returns the 99th-percentile completion-dispatch latency in microseconds, merged over
     * shards.
     *
     * @return the 99th-percentile latency (a bucket maximum; {@code 0.0} when no sample was
     *         recorded)
     */
    double getLatencyP99Micros();

    /**
     * Returns the 99.9th-percentile completion-dispatch latency in microseconds, merged
     * over shards.
     *
     * @return the 99.9th-percentile latency (a bucket maximum; {@code 0.0} when no sample
     *         was recorded)
     */
    double getLatencyP999Micros();

    /**
     * Returns the maximum recorded completion-dispatch latency in microseconds, merged over
     * shards.
     *
     * @return the exact maximum latency, or {@code 0.0} when no sample was recorded
     */
    double getLatencyMaxMicros();

    /**
     * Returns the minimum recorded completion-dispatch latency in microseconds, merged over
     * shards.
     *
     * @return the exact minimum latency, or {@code 0.0} when no sample was recorded
     */
    double getLatencyMinMicros();
}
