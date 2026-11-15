package com.davidvlijmincx.lio.api;

/**
 * Immutable snapshot of a {@link LatencyHistogram} in nanoseconds (B5).
 *
 * <p>{@code minNanos}/{@code maxNanos}/{@code totalNanos}/{@code samples} are exact over the
 * recorded observations. The percentiles are the inclusive maximum of the fixed log-linear
 * bucket that contains the requested quantile (see {@link LatencyHistogram} and the B5
 * design §2.2/A1.2): values {@code 0..15 ns} and every bucket maximum are exact, for larger
 * values the reported percentile is {@code >=} the true quantile value and its relative
 * over-estimate is {@code <= 1/16} (6.25 %). Use the percentiles for telemetry/trend
 * reporting, not for per-request SLO enforcement.
 *
 * @param samples     number of recorded observations
 * @param totalNanos  exact sum of the recorded values
 * @param minNanos    exact minimum ({@code 0} when {@code samples == 0})
 * @param maxNanos    exact maximum ({@code 0} when {@code samples == 0})
 * @param p50Nanos    bucket maximum containing the 50th percentile
 * @param p90Nanos    bucket maximum containing the 90th percentile
 * @param p99Nanos    bucket maximum containing the 99th percentile
 * @param p999Nanos   bucket maximum containing the 99.9th percentile
 */
public record LatencySnapshot(
        long samples,
        long totalNanos,
        long minNanos,
        long maxNanos,
        long p50Nanos,
        long p90Nanos,
        long p99Nanos,
        long p999Nanos) {

    /**
     * Returns the all-zero snapshot used when no observation was recorded (sampling
     * disabled or an empty histogram).
     *
     * @return the all-zero snapshot
     */
    public static LatencySnapshot empty() {
        return new LatencySnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /**
     * Returns the arithmetic mean of the recorded latencies.
     *
     * @return {@code totalNanos / samples}, or {@code 0} when no sample was recorded
     */
    public double meanNanos() {
        return samples == 0L ? 0.0d : (double) totalNanos / (double) samples;
    }
}
