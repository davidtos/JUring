package com.davidvlijmincx.lio.api;

/**
 * Immutable snapshot of one {@link IoShard}: the B4 lifecycle/counters ({@link ShardStats}),
 * the B5 ring counters/occupancy ({@link RingMetrics}) and the B5 completion-dispatch
 * latency ({@link LatencySnapshot}).
 *
 * <p>The three components are three separate snapshots taken by {@link IoShard#metrics()},
 * not an atomic view: they may be mutually inconsistent by a few events, and ring counters
 * may lag the kernel by one loop pass (see {@code docs/b5-design.md} §2.5/§5). Metrics are
 * for trend/straggler detection and M3 reporting, not for correctness assertions about
 * application state.
 *
 * <p>{@code dispatchLatency} measures the window from a successful
 * {@link ShardContext#onCompletion(long, CompletionHandler)} registration to the dispatch of
 * the matching completion to that handler, recorded immediately before the handler runs and
 * only for deterministic every-Nth sampled registrations
 * ({@link ShardOptions#withLatencySampleEvery(int)}); it is {@link LatencySnapshot#empty()}
 * when sampling is disabled or for body-hosted shards.
 *
 * @param stats           the B4 shard snapshot (equals {@link IoShard#stats()} at snapshot time)
 * @param ring            the ring snapshot ({@code JUring.ringMetrics()} for the shard's ring)
 * @param dispatchLatency completion-dispatch latency histogram snapshot (nanoseconds)
 */
public record ShardMetrics(ShardStats stats, RingMetrics ring, LatencySnapshot dispatchLatency) {
}
