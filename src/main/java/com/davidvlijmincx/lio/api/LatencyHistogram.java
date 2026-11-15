package com.davidvlijmincx.lio.api;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Dependency-free fixed-bucket latency histogram in nanoseconds (B5).
 *
 * <p><b>Layout (frozen, B5 design §2.2 as amended by A1.2).</b> 512 bucket slots:
 * bucket <em>i</em> for {@code i in [0, 15]} is the exact value <em>i</em> ns;
 * slots {@code [16, 63]} are unused padding; for values {@code v >= 16} let
 * {@code mag = floor(log2(v))}, {@code w = 2^(mag-4)}, {@code base = 2^mag} and
 * {@code sub = (v - base) / w}; the slot is {@code 64 + (mag-4)*16 + sub} ({@code sub in
 * [0,15]}) and it covers the inclusive range
 * {@code [base + sub*w, base + sub*w + w - 1]}. The largest representable value is
 * {@code 2^32-1} ns (slot 511); negatives clamp to 0 and larger values clamp to
 * {@code 2^32-1}.
 *
 * <p><b>Accuracy.</b> {@code min}/{@code max}/{@code total}/{@code count} are exact over the
 * recorded observations. {@link #snapshot()} reports {@code p50/p90/p99/p999} as the
 * <em>inclusive bucket maximum</em> containing the requested quantile (first bucket whose
 * cumulative count reaches {@code ceil(q * samples)}). Values {@code 0..15} and every
 * bucket maximum are exact; for {@code v >= 16} the reported value is {@code >= the true
 * quantile} and its relative over-estimate is strictly below {@code 1/16} (6.25 %)
 * whenever it is non-zero (exact at every bucket maximum). Percentiles are clamped to
 * {@link LatencySnapshot#maxNanos()}, so {@code p50 <= p90 <= p99 <= p999 <= max} always
 * holds.
 *
 * <p><b>Threading.</b> Not a general multi-writer structure: {@link #record(long)} is
 * expected to be called from one owner thread. {@link #snapshot()} and {@link #merge} may
 * be called from any thread as a best-effort telemetry view (bucket/stat reads use acquire
 * semantics, so a snapshot never tears; a concurrent record may be missed or double-counted
 * for one snapshot and that is documented, not a bug). {@code record()} performs no
 * allocation; {@code snapshot()} allocation is expected (it is called off the hot path).
 */
public final class LatencyHistogram {

    /** Total bucket slots (frozen: values 0..15 exact, 64..511 log-linear, 16..63 padding). */
    private static final int BUCKETS = 512;
    /** First log-linear slot; slots 0..15 are the exact 0..15 ns values. */
    private static final int FIRST_LOG_BUCKET = 64;
    /** Number of exact 1-ns buckets at the bottom of the range. */
    private static final int EXACT_BUCKETS = 16;
    /** Largest accepted latency in nanoseconds ({@code 2^32 - 1}). */
    private static final long MAX_NANOS = 0xFFFF_FFFFL;

    private static final int S_COUNT = 0;
    private static final int S_TOTAL = 1;
    private static final int S_MIN = 2;
    private static final int S_MAX = 3;
    private static final int STATS = 4;

    private static final VarHandle VH_LONG = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] buckets = new long[BUCKETS];
    private final long[] stats = new long[STATS];

    /** Creates an empty histogram. */
    public LatencyHistogram() {
        reset();
    }

    /**
     * Records one observation. Negative values clamp to {@code 0}; values above
     * {@code 2^32-1} ns clamp to {@code 2^32-1}. Allocation-free.
     *
     * @param nanos observed latency in nanoseconds
     */
    public void record(long nanos) {
        if (nanos < 0L) {
            nanos = 0L;
        } else if (nanos > MAX_NANOS) {
            nanos = MAX_NANOS;
        }
        int index = bucketIndex(nanos);
        // Opaque (atomic) writes: record() is owner-thread-only, but snapshot()/merge() may
        // read concurrently from any thread and must never tear a 64-bit element (design §5).
        addOpaque(buckets, index, 1L);
        long count = (long) VH_LONG.getOpaque(stats, S_COUNT);
        if (count == 0L) {
            VH_LONG.setOpaque(stats, S_MIN, nanos);
            VH_LONG.setOpaque(stats, S_MAX, nanos);
        } else {
            if (nanos < (long) VH_LONG.getOpaque(stats, S_MIN)) {
                VH_LONG.setOpaque(stats, S_MIN, nanos);
            }
            if (nanos > (long) VH_LONG.getOpaque(stats, S_MAX)) {
                VH_LONG.setOpaque(stats, S_MAX, nanos);
            }
        }
        addOpaque(stats, S_TOTAL, nanos);
        VH_LONG.setOpaque(stats, S_COUNT, count + 1L);
    }

    /**
     * Returns an immutable snapshot of the current histogram.
     *
     * <p>Percentiles are inclusive bucket maxima as described in the class documentation;
     * the snapshot is all-zero when the histogram is empty. The snapshot may allocate and is
     * safe to call from any thread as a best-effort view that never tears.
     *
     * @return the current histogram snapshot
     */
    public LatencySnapshot snapshot() {
        long samples = (long) VH_LONG.getAcquire(stats, S_COUNT);
        if (samples == 0L) {
            return LatencySnapshot.empty();
        }
        long totalNanos = (long) VH_LONG.getAcquire(stats, S_TOTAL);
        long minNanos = (long) VH_LONG.getAcquire(stats, S_MIN);
        long maxNanos = (long) VH_LONG.getAcquire(stats, S_MAX);

        long rank50 = quantileRank(samples, 1L, 2L);
        long rank90 = quantileRank(samples, 9L, 10L);
        long rank99 = quantileRank(samples, 99L, 100L);
        long rank999 = quantileRank(samples, 999L, 1000L);

        long cumulative = 0L;
        long p50 = maxNanos;
        long p90 = maxNanos;
        long p99 = maxNanos;
        long p999 = maxNanos;
        boolean found50 = false;
        boolean found90 = false;
        boolean found99 = false;
        boolean found999 = false;
        for (int i = 0; i < BUCKETS && !found999; i++) {
            long count = (long) VH_LONG.getAcquire(buckets, i);
            if (count == 0L) {
                continue;
            }
            cumulative += count;
            long value = Math.min(bucketMaximum(i), maxNanos);
            if (!found50 && cumulative >= rank50) {
                p50 = value;
                found50 = true;
            }
            if (!found90 && cumulative >= rank90) {
                p90 = value;
                found90 = true;
            }
            if (!found99 && cumulative >= rank99) {
                p99 = value;
                found99 = true;
            }
            if (!found999 && cumulative >= rank999) {
                p999 = value;
                found999 = true;
            }
        }
        return new LatencySnapshot(samples, totalNanos, minNanos, maxNanos, p50, p90, p99, p999);
    }

    /** Zeroes the buckets and the count/sum/min/max statistics. */
    public void reset() {
        for (int i = 0; i < BUCKETS; i++) {
            VH_LONG.setOpaque(buckets, i, 0L);
        }
        VH_LONG.setOpaque(stats, S_COUNT, 0L);
        VH_LONG.setOpaque(stats, S_TOTAL, 0L);
        VH_LONG.setOpaque(stats, S_MIN, Long.MAX_VALUE);
        VH_LONG.setOpaque(stats, S_MAX, 0L);
    }

    /**
     * Returns the number of recorded observations.
     *
     * @return the observation count
     */
    public long count() {
        return (long) VH_LONG.getAcquire(stats, S_COUNT);
    }

    /**
     * Adds another histogram's bucket counts and statistics to this one. Off the hot path
     * (used by the shard/group aggregation, typically into a scratch histogram). Merge in
     * either direction between two live owner threads is best effort.
     *
     * @param other histogram to merge into this one (may be empty; must not be {@code null})
     */
    public void merge(LatencyHistogram other) {
        long otherCount = other.count();
        if (otherCount == 0L) {
            return;
        }
        for (int i = 0; i < BUCKETS; i++) {
            long count = (long) VH_LONG.getAcquire(other.buckets, i);
            if (count != 0L) {
                addOpaque(buckets, i, count);
            }
        }
        long otherTotal = (long) VH_LONG.getAcquire(other.stats, S_TOTAL);
        long otherMin = (long) VH_LONG.getAcquire(other.stats, S_MIN);
        long otherMax = (long) VH_LONG.getAcquire(other.stats, S_MAX);

        long count = (long) VH_LONG.getOpaque(stats, S_COUNT);
        if (count == 0L) {
            VH_LONG.setOpaque(stats, S_MIN, otherMin);
            VH_LONG.setOpaque(stats, S_MAX, otherMax);
        } else {
            if (otherMin < (long) VH_LONG.getOpaque(stats, S_MIN)) {
                VH_LONG.setOpaque(stats, S_MIN, otherMin);
            }
            if (otherMax > (long) VH_LONG.getOpaque(stats, S_MAX)) {
                VH_LONG.setOpaque(stats, S_MAX, otherMax);
            }
        }
        addOpaque(stats, S_TOTAL, otherTotal);
        VH_LONG.setOpaque(stats, S_COUNT, count + otherCount);
    }

    // ------------------------------------------------------------------
    // Bucket arithmetic (frozen layout, see class documentation / design A1.2)
    // ------------------------------------------------------------------

    /** Maps a clamped nanosecond value to its bucket slot. */
    private static int bucketIndex(long nanos) {
        if (nanos < EXACT_BUCKETS) {
            return (int) nanos;
        }
        int magnitude = 63 - Long.numberOfLeadingZeros(nanos); // floor(log2(nanos)), >= 4
        int shift = magnitude - 4;
        int sub = (int) ((nanos >>> shift) & 0xF);
        return FIRST_LOG_BUCKET + ((magnitude - 4) << 4) + sub;
    }

    /** @return the largest value (inclusive upper bound) stored in the bucket slot */
    private static long bucketMaximum(int index) {
        if (index < EXACT_BUCKETS) {
            return index;
        }
        int log = index - FIRST_LOG_BUCKET;
        int magnitude = (log >>> 4) + 4;
        int sub = log & 0xF;
        long base = 1L << magnitude;
        long width = 1L << (magnitude - 4);
        return Math.min(base + ((long) (sub + 1) * width) - 1L, MAX_NANOS);
    }

    /** Adds {@code delta} to an array element with an opaque (atomic, non-tearing) write. */
    private static void addOpaque(long[] array, int index, long delta) {
        VH_LONG.setOpaque(array, index, (long) VH_LONG.getOpaque(array, index) + delta);
    }

    /** @return {@code ceil(samples * numerator / denominator)}, overflow-safe for long counts */
    private static long quantileRank(long samples, long numerator, long denominator) {
        long quotient = samples / denominator;
        long remainder = samples % denominator;
        return quotient * numerator + (remainder * numerator + denominator - 1L) / denominator;
    }
}
