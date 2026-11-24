package com.davidvlijmincx.lio.api;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LatencyHistogram} exact statistics, brute-force percentile
 * oracle, boundaries/clamping/merge/snapshot immutability, and allocation-free recording.
 * The bucket layout is the frozen one documented on {@link LatencyHistogram}.
 */
class LatencyHistogramTest {

    private static final long MAX_NANOS = 0xFFFF_FFFFL;
    private static final long[] QUANTILES_NUM = {1L, 9L, 99L, 999L};
    private static final long[] QUANTILES_DEN = {2L, 10L, 100L, 1000L};

    // --- test 8: exact statistics / empty / reset -----------------------------

    @Test
    void emptyHistogramSnapshotsToAllZeroes() {
        LatencyHistogram histogram = new LatencyHistogram();

        LatencySnapshot snapshot = histogram.snapshot();

        assertEquals(0L, histogram.count());
        assertEquals(LatencySnapshot.empty(), snapshot);
        assertEquals(0L, snapshot.samples());
        assertEquals(0L, snapshot.totalNanos());
        assertEquals(0L, snapshot.minNanos());
        assertEquals(0L, snapshot.maxNanos());
        assertEquals(0L, snapshot.p50Nanos());
        assertEquals(0L, snapshot.p90Nanos());
        assertEquals(0L, snapshot.p99Nanos());
        assertEquals(0L, snapshot.p999Nanos());
        assertEquals(0.0d, snapshot.meanNanos());
    }

    @Test
    void countSumMinMaxMeanAreExact() {
        LatencyHistogram histogram = new LatencyHistogram();
        long[] values = {0L, 1L, 15L, 16L, 17L, 100L, 1_000L, 1_000_000L, 1_000_000_000L, 17L};
        long sum = 0L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long value : values) {
            histogram.record(value);
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        LatencySnapshot snapshot = histogram.snapshot();

        assertEquals(values.length, snapshot.samples());
        assertEquals(values.length, histogram.count());
        assertEquals(sum, snapshot.totalNanos());
        assertEquals(min, snapshot.minNanos());
        assertEquals(max, snapshot.maxNanos());
        assertEquals((double) sum / values.length, snapshot.meanNanos());
        assertEquals(sum, snapshot.totalNanos());
    }

    @Test
    void resetZeroesEverything() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(1L);
        histogram.record(1_000_000L);
        histogram.reset();

        assertEquals(0L, histogram.count());
        assertEquals(LatencySnapshot.empty(), histogram.snapshot());

        histogram.record(7L);
        LatencySnapshot afterReset = histogram.snapshot();
        assertEquals(1L, afterReset.samples());
        assertEquals(7L, afterReset.totalNanos());
        assertEquals(7L, afterReset.minNanos());
        assertEquals(7L, afterReset.maxNanos());
    }

    // --- test 9: brute-force percentile oracle --------------------------------

    @Test
    void percentilesAreBucketMaximaAtOrAboveTheTrueQuantile() {
        long[] values = {
                0L, 0L, 1L, 15L, 16L, 16L, 17L, 31L, 32L, 33L, 48L, 49L,
                100L, 1_000L, 1_000_000L, 1_000_000_000L, 2_000_000_000L
        };
        Arrays.sort(values);
        LatencyHistogram histogram = new LatencyHistogram();
        for (long value : values) {
            histogram.record(value);
        }

        LatencySnapshot snapshot = histogram.snapshot();
        long[] reported = {snapshot.p50Nanos(), snapshot.p90Nanos(), snapshot.p99Nanos(), snapshot.p999Nanos()};

        for (int q = 0; q < QUANTILES_NUM.length; q++) {
            long rank = quantileRank(values.length, QUANTILES_NUM[q], QUANTILES_DEN[q]);
            long trueValue = values[(int) (rank - 1)];
            long expected = Math.min(bucketMaximum(trueValue), snapshot.maxNanos());
            assertEquals(expected, reported[q],
                    "p" + QUANTILES_NUM[q] + " must be the inclusive bucket maximum of the true quantile " + trueValue);
            assertTrue(reported[q] >= trueValue, "percentile must never be below the true quantile value");
            if (trueValue >= 16L) {
                double relativeOverEstimate = (double) (reported[q] - trueValue) / (double) trueValue;
                assertTrue(relativeOverEstimate < 1.0d / 16.0d,
                        "relative over-estimate must be below 1/16 for v>=16, was " + relativeOverEstimate);
            }
        }
        assertTrue(snapshot.p50Nanos() <= snapshot.p90Nanos());
        assertTrue(snapshot.p90Nanos() <= snapshot.p99Nanos());
        assertTrue(snapshot.p99Nanos() <= snapshot.p999Nanos());
        assertTrue(snapshot.p999Nanos() <= snapshot.maxNanos());
    }

    @Test
    void percentilesAreMonotoneAndBoundedForManyValues() {
        LatencyHistogram histogram = new LatencyHistogram();
        long max = 0L;
        for (long value = 0L; value < 4_096L; value++) {
            histogram.record(value);
            max = value;
        }

        LatencySnapshot snapshot = histogram.snapshot();

        assertEquals(4_096L, snapshot.samples());
        assertEquals(max, snapshot.maxNanos());
        assertTrue(snapshot.p50Nanos() >= trueQuantile(snapshot.samples(), 1L, 2L));
        assertTrue(snapshot.p50Nanos() <= snapshot.p90Nanos());
        assertTrue(snapshot.p90Nanos() <= snapshot.p99Nanos());
        assertTrue(snapshot.p99Nanos() <= snapshot.p999Nanos());
        assertTrue(snapshot.p999Nanos() <= snapshot.maxNanos());
    }

    @Test
    void valuesOnBucketMaximumAreExact() {
        long[] exact = {0L, 1L, 15L, 16L, 17L, 31L, 33L, 49L, 63L, 127L, 255L, 511L, 1_023L, MAX_NANOS};
        for (long value : exact) {
            LatencyHistogram histogram = new LatencyHistogram();
            histogram.record(value);
            LatencySnapshot snapshot = histogram.snapshot();
            assertEquals(value, snapshot.p50Nanos(), "single bucket-maximum value must be exact: " + value);
            assertEquals(value, snapshot.p999Nanos());
            assertEquals(value, snapshot.minNanos());
            assertEquals(value, snapshot.maxNanos());
        }
    }

    // --- test 10: boundaries, clamping, merge, snapshot immutability ----------

    @Test
    void negativeValuesClampToZero() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(-1L);
        histogram.record(-1_000_000L);

        LatencySnapshot snapshot = histogram.snapshot();

        assertEquals(2L, snapshot.samples());
        assertEquals(0L, snapshot.minNanos());
        assertEquals(0L, snapshot.maxNanos());
        assertEquals(0L, snapshot.totalNanos());
        assertEquals(0L, snapshot.p50Nanos());
    }

    @Test
    void maximumValueIsAcceptedAndLargerValuesClampToMaximum() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(MAX_NANOS);

        LatencySnapshot atMax = histogram.snapshot();
        assertEquals(1L, atMax.samples());
        assertEquals(MAX_NANOS, atMax.minNanos());
        assertEquals(MAX_NANOS, atMax.maxNanos());
        assertEquals(MAX_NANOS, atMax.p999Nanos());

        LatencyHistogram clamped = new LatencyHistogram();
        clamped.record(MAX_NANOS + 1L);
        clamped.record(Long.MAX_VALUE);

        LatencySnapshot clampedSnapshot = clamped.snapshot();
        assertEquals(2L, clampedSnapshot.samples());
        assertEquals(MAX_NANOS, clampedSnapshot.minNanos());
        assertEquals(MAX_NANOS, clampedSnapshot.maxNanos());
        assertEquals(2L * MAX_NANOS, clampedSnapshot.totalNanos());
        assertEquals(MAX_NANOS, clampedSnapshot.p50Nanos());
        assertTrue(clampedSnapshot.p999Nanos() <= clampedSnapshot.maxNanos());
    }

    @Test
    void mergeEqualsRecordingAllValues() {
        long[] first = {0L, 1L, 15L, 16L, 31L, 32L, 33L, 100L, 1_000L};
        long[] second = {17L, 48L, 49L, 1_000_000L, 1_000_000_000L, -5L};

        LatencyHistogram left = new LatencyHistogram();
        LatencyHistogram right = new LatencyHistogram();
        LatencyHistogram reference = new LatencyHistogram();
        for (long value : first) {
            left.record(value);
            reference.record(value);
        }
        for (long value : second) {
            right.record(value);
            reference.record(value);
        }

        left.merge(right);

        assertEquals(reference.count(), left.count());
        assertEquals(reference.snapshot(), left.snapshot(), "merge must equal recording all values");
        assertEquals(reference.count(), left.count());

        LatencyHistogram empty = new LatencyHistogram();
        LatencySnapshot beforeEmptyMerge = left.snapshot();
        left.merge(empty);
        assertEquals(beforeEmptyMerge, left.snapshot(), "merging an empty histogram is a no-op");
    }

    @Test
    void snapshotsAreImmutableViews() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(1_000L);
        LatencySnapshot snapshot = histogram.snapshot();

        histogram.record(2_000_000L);

        assertEquals(1L, snapshot.samples());
        assertEquals(1_000L, snapshot.totalNanos());
        assertEquals(1_000L, snapshot.minNanos());
        assertEquals(1_000L, snapshot.maxNanos());
        assertEquals(1_000L, snapshot.p999Nanos());
        assertNotEquals(snapshot, histogram.snapshot());
    }

    // --- test 11: no allocation per record ------------------------------------

    @Test
    void recordDoesNotAllocate() {
        ThreadMXBean bean = threadBean();
        long threadId = Thread.currentThread().threadId();
        LatencyHistogram histogram = new LatencyHistogram();
        long[] values = new long[1_024];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 1_000_003L;
        }

        // Warm up in large chunks: the first chunk(s) may pay one-off JIT/class-init costs
        // in the application thread; once the recording loop is compiled the delta is 0.
        int zeroRuns = 0;
        for (int warmup = 0; warmup < 40 && zeroRuns < 3; warmup++) {
            long before = bean.getThreadAllocatedBytes(threadId);
            recordLoop(histogram, values, 100_000);
            long after = bean.getThreadAllocatedBytes(threadId);
            if (after == before) {
                zeroRuns++;
            } else {
                zeroRuns = 0;
            }
        }
        assertTrue(zeroRuns >= 3, "warmup must reach a steady state with 0 allocations per record()");

        long countBefore = histogram.count();
        long before = bean.getThreadAllocatedBytes(threadId);
        recordLoop(histogram, values, 500_000);
        long after = bean.getThreadAllocatedBytes(threadId);

        assertEquals(countBefore + 500_000L, histogram.count());
        assertEquals(0L, after - before, "record() must be allocation-free");
    }

    private static void recordLoop(LatencyHistogram histogram, long[] values, int iterations) {
        for (int i = 0; i < iterations; i++) {
            histogram.record(values[i & (values.length - 1)]);
        }
    }

    private static ThreadMXBean threadBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean sunBean)) {
            throw new UnsupportedOperationException("com.sun.management.ThreadMXBean not available");
        }
        if (!sunBean.isThreadAllocatedMemorySupported()) {
            throw new UnsupportedOperationException("Thread allocated memory measurement is not supported");
        }
        if (!sunBean.isThreadAllocatedMemoryEnabled()) {
            sunBean.setThreadAllocatedMemoryEnabled(true);
        }
        return sunBean;
    }

    // --- oracle helpers (independent re-derivation of the frozen layout) ------

    private static long quantileRank(long samples, long numerator, long denominator) {
        return (samples * numerator + denominator - 1L) / denominator;
    }

    private static long trueQuantile(long samples, long numerator, long denominator) {
        long rank = quantileRank(samples, numerator, denominator);
        return rank - 1L;
    }

    /** Inclusive maximum of the frozen bucket that contains {@code value}. */
    private static long bucketMaximum(long value) {
        if (value < 0L) {
            return 0L;
        }
        long clamped = Math.min(value, MAX_NANOS);
        if (clamped < 16L) {
            return clamped;
        }
        int magnitude = 63 - Long.numberOfLeadingZeros(clamped);
        long width = 1L << (magnitude - 4);
        long base = 1L << magnitude;
        long sub = (clamped - base) / width;
        long lower = base + sub * width;
        return Math.min(lower + width - 1L, MAX_NANOS);
    }
}
