package com.davidvlijmincx.lio.api;

/**
 * Immutable aggregate of a {@link RingGroup}'s shard metrics.
 *
 * <p>Counters are sums over the group's shards: {@code submitted/reaped/completed/failed/
 * cancelled/timedOut} are summed ring counters, and {@code inFlight}, {@code sqQueued} and
 * {@code cqReady} are summed snapshots. {@code dispatchLatency} is the bucket-wise merge of
 * the per-shard {@link LatencySnapshot}s (exact histogram-count merge, percentiles per the
 * {@link LatencyHistogram} contract). A group of unstarted shards aggregates to all zeroes
 * and {@link LatencySnapshot#empty()}.
 *
 * <p>Like all snapshots the aggregate is best effort and not linearizable with respect to
 * concurrent traffic.
 *
 * @param shards          number of shards in the group
 * @param submitted       summed ring {@link RingMetrics#submitted()}
 * @param reaped          summed ring {@link RingMetrics#reaped()}
 * @param completed       summed ring {@link RingMetrics#completed()}
 * @param failed          summed ring {@link RingMetrics#failed()}
 * @param cancelled       summed ring {@link RingMetrics#cancelled()}
 * @param timedOut        summed ring {@link RingMetrics#timedOut()}
 * @param inFlight        summed ring {@link RingMetrics#inFlight()}
 * @param sqQueued        summed SQ occupancy
 * @param cqReady         summed CQ occupancy
 * @param dispatchLatency merged completion-dispatch latency snapshot (nanoseconds)
 */
public record GroupMetrics(
        int shards,
        long submitted, long reaped, long completed, long failed,
        long cancelled, long timedOut, long inFlight,
        long sqQueued, long cqReady,
        LatencySnapshot dispatchLatency) {
}
