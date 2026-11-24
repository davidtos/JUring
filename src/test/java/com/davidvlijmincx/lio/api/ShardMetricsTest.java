package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShardMetrics}/{@link GroupMetrics} shape and
 * consistency across the shard lifecycle, deterministic dispatch-latency sampling,
 * group aggregation/merging, any-thread/never-throw reads (including body-hosted shards) and
 * zero-allocation registration+dispatch recording.
 *
 * <p>Percentile assertions use only the safe frozen contract: {@code p50 <= p90 <= p99
 * <= p999 <= max}, count/sum exact per the sampling rule; they never assume a particular
 * bucket layout or errno mapping.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ShardMetricsTest {

    private static final CompletionHandler NO_OP_HANDLER = result -> {
    };

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // snapshot shape/consistency across lifecycle + per-shard isolation
    // ------------------------------------------------------------------

    @Test
    void shardMetricsLifecycleShapeAndPerShardIsolation() throws Exception {
        int[] nops = {7, 11, 13, 17};
        RingGroup group = RingGroup.create(nops.length,
                ShardOptions.defaults().withLatencySampleEvery(1));
        try {
            // Never-started shards: ring counters 0, occupancy 0, entries 0, empty latency.
            List<ShardMetrics> beforeStart = group.metrics();
            assertThat(beforeStart).hasSize(nops.length);
            for (int i = 0; i < nops.length; i++) {
                ShardMetrics metrics = beforeStart.get(i);
                assertThat(metrics.stats().shardId()).isEqualTo(i);
                assertThat(metrics.stats().started()).isFalse();
                assertThat(metrics.stats().closed()).isFalse();
                assertZeroRingMetrics(metrics.ring(), "never-started shard " + i);
                assertThat(metrics.dispatchLatency()).isEqualTo(LatencySnapshot.empty());
            }
            GroupMetrics unstarted = group.aggregateMetrics();
            assertThat(unstarted.shards()).isEqualTo(nops.length);
            assertThat(unstarted.submitted()).isZero();
            assertThat(unstarted.reaped()).isZero();
            assertThat(unstarted.inFlight()).isZero();
            assertThat(unstarted.sqQueued()).isZero();
            assertThat(unstarted.cqReady()).isZero();
            assertThat(unstarted.dispatchLatency()).isEqualTo(LatencySnapshot.empty());

            group.start();

            // A known, distinct NOP workload per shard; the shard-level counters must be exact
            // per shard (no cross-shard leakage).
            CountDownLatch done = new CountDownLatch(sum(nops));
            for (int i = 0; i < nops.length; i++) {
                int count = nops[i];
                group.shard(i).execute(ctx -> {
                    for (int k = 0; k < count; k++) {
                        long handle = ctx.ring().prepareNop();
                        ctx.onCompletion(handle, result -> done.countDown());
                    }
                    ctx.submit();
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "NOP workload did not drain");
            waitUntil(() -> {
                for (int i = 0; i < nops.length; i++) {
                    if (group.shard(i).stats().completionsDispatched() != nops[i]) {
                        return false;
                    }
                }
                return true;
            }, 5_000L);

            List<ShardMetrics> during = group.metrics();
            for (int i = 0; i < nops.length; i++) {
                ShardMetrics metrics = during.get(i);
                assertThat(metrics.stats().shardId()).isEqualTo(i);
                assertThat(metrics.stats().started()).isTrue();
                assertThat(metrics.stats().completionsRegistered()).isEqualTo(nops[i]);
                assertThat(metrics.stats().completionsDispatched()).isEqualTo(nops[i]);
                assertThat(metrics.dispatchLatency().samples())
                        .as("every=1 sampling: one sample per dispatched registration of shard %d", i)
                        .isEqualTo(nops[i]);
                assertRingSnapshotConsistent(metrics, nops[i]);
            }

        } finally {
            group.close(500L);
        }

        // After close the counters are frozen, occupancy is 0 and the effective entries are
        // retained (after-close rule); metrics() stays readable and total.
        List<ShardMetrics> afterClose = group.metrics();
        List<ShardMetrics> afterCloseAgain = group.metrics();
        assertThat(afterClose).hasSize(nops.length);
        for (int i = 0; i < nops.length; i++) {
            ShardMetrics metrics = afterClose.get(i);
            assertThat(metrics.stats().closed()).isTrue();
            assertThat(metrics.stats()).isEqualTo(afterCloseAgain.get(i).stats());
            assertThat(metrics.ring()).isEqualTo(afterCloseAgain.get(i).ring());
            assertThat(metrics.ring().sqEntries()).isGreaterThan(0);
            assertThat(metrics.ring().cqEntries()).isGreaterThan(0);
            assertThat(metrics.ring().sqQueued()).isZero();
            assertThat(metrics.ring().cqReady()).isZero();
            assertThat(metrics.ring().reaped())
                    .isGreaterThanOrEqualTo(metrics.stats().completionsDispatched());
            assertThat(metrics.ring().reaped())
                    .isEqualTo(metrics.ring().completed() + metrics.ring().failed());
            assertThat(metrics.dispatchLatency().samples()).isEqualTo(nops[i]);
        }
    }

    // ------------------------------------------------------------------
    // dispatch latency sampling
    // ------------------------------------------------------------------

    @Test
    void dispatchLatencyDisabledIsEmpty() throws Exception {
        IoShard shard = IoShard.create(0, ShardOptions.defaults().withRingEntries(64));
        try {
            shard.start();
            runNops(shard, 64);
            ShardMetrics metrics = shard.metrics();
            assertThat(metrics.dispatchLatency()).isEqualTo(LatencySnapshot.empty());
            assertThat(metrics.dispatchLatency().samples()).isZero();
            assertThat(metrics.dispatchLatency().meanNanos()).isZero();
        } finally {
            shard.close(500L);
        }
        // Frozen empty latency after close (all registrations drained, none sampled).
        assertThat(shard.metrics().dispatchLatency()).isEqualTo(LatencySnapshot.empty());
        assertThat(shard.metrics().ring().reaped()).isGreaterThanOrEqualTo(64L);
    }

    @Test
    void dispatchLatencyEveryRegistrationSamplesEveryDispatch() throws Exception {
        IoShard shard = IoShard.create(0,
                ShardOptions.defaults().withRingEntries(128).withLatencySampleEvery(1));
        try {
            shard.start();
            runNops(shard, 64);
            ShardMetrics metrics = shard.metrics();
            long dispatched = metrics.stats().completionsDispatched();
            assertThat(dispatched).isEqualTo(64L);
            assertThat(metrics.dispatchLatency().samples()).isEqualTo(dispatched);
            assertThat(metrics.dispatchLatency().samples())
                    .isLessThanOrEqualTo(metrics.stats().completionsDispatched());
            assertPlausibleDispatchLatency(metrics.dispatchLatency());
        } finally {
            shard.close(500L);
        }
        ShardMetrics afterClose = shard.metrics();
        assertThat(afterClose.dispatchLatency().samples()).isEqualTo(afterClose.stats().completionsDispatched());
        assertPlausibleDispatchLatency(afterClose.dispatchLatency());
    }

    @Test
    void dispatchLatencyEveryFourthSamplesExactlyDispatchedOverFour() throws Exception {
        // Timed registrations are 1-based indices N, 2N, ... of successful registrations,
        // so a drained run with R registrations has exactly floor(R/N) samples (64/4 = 16 here).
        int dispatchedNops = 64;
        IoShard shard = IoShard.create(0,
                ShardOptions.defaults().withRingEntries(128).withLatencySampleEvery(4));
        try {
            shard.start();
            runNops(shard, dispatchedNops);
            ShardMetrics metrics = shard.metrics();
            assertThat(metrics.stats().completionsDispatched()).isEqualTo(dispatchedNops);
            assertThat(metrics.dispatchLatency().samples()).isEqualTo(dispatchedNops / 4);
            assertPlausibleDispatchLatency(metrics.dispatchLatency());
        } finally {
            shard.close(500L);
        }
    }

    @Test
    void failedRegistrationDoesNotConsumeSampleOrLeaveTimestamp() throws Exception {
        IoShard shard = IoShard.create(0,
                ShardOptions.defaults().withRingEntries(64).withLatencySampleEvery(2));
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        try {
            shard.start();
            CountDownLatch done = new CountDownLatch(2);
            CompletionHandler handler = result -> done.countDown();
            shard.execute(ctx -> {
                long first = ctx.ring().prepareNop();
                ctx.onCompletion(first, handler); // registration #1: not sampled
                try {
                    ctx.onCompletion(first, handler); // duplicate: ISE, must not consume a sample
                    unexpected.set(new AssertionError("duplicate registration did not throw"));
                } catch (IllegalStateException expected) {
                    // expected: no sample consumed, no timestamp left behind
                }
                long second = ctx.ring().prepareNop();
                ctx.onCompletion(second, handler); // registration #2: sampled (every=2)
                ctx.submit();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS), "registrations did not drain");
            assertThat(unexpected.get()).isNull();
            waitUntil(() -> shard.stats().completionsDispatched() == 2L, 5_000L);
            ShardMetrics metrics = shard.metrics();
            assertThat(metrics.stats().completionsRegistered()).isEqualTo(2L);
            assertThat(metrics.stats().completionsDispatched()).isEqualTo(2L);
            assertThat(metrics.dispatchLatency().samples())
                    .as("the failed duplicate registration must not shift the every-2 sampling")
                    .isEqualTo(1L);
        } finally {
            shard.close(500L);
        }
    }

    // ------------------------------------------------------------------
    // group metrics + aggregation
    // ------------------------------------------------------------------

    @Test
    void groupMetricsAndAggregation() throws Exception {
        int shardCount = 3;
        int perShard = 48;
        RingGroup group = RingGroup.create(shardCount,
                ShardOptions.defaults().withRingEntries(128).withLatencySampleEvery(1));
        try {
            GroupMetrics unstarted = group.aggregateMetrics();
            assertThat(unstarted.shards()).isEqualTo(shardCount);
            assertThat(unstarted.submitted()).isZero();
            assertThat(unstarted.dispatchLatency()).isEqualTo(LatencySnapshot.empty());

            group.start();
            CountDownLatch done = new CountDownLatch(shardCount * perShard);
            for (int i = 0; i < shardCount; i++) {
                group.shard(i).execute(ctx -> {
                    for (int k = 0; k < perShard; k++) {
                        long handle = ctx.ring().prepareNop();
                        ctx.onCompletion(handle, result -> done.countDown());
                    }
                    ctx.submit();
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "NOP workload did not drain");
            waitUntil(() -> {
                for (int i = 0; i < shardCount; i++) {
                    if (group.shard(i).stats().completionsDispatched() != perShard) {
                        return false;
                    }
                }
                return true;
            }, 5_000L);
        } finally {
            group.close(500L);
        }

        // Closed group: snapshots are frozen, so the aggregate is exactly the component sum.
        List<ShardMetrics> metrics = group.metrics();
        GroupMetrics aggregate = group.aggregateMetrics();
        assertThat(metrics).hasSize(shardCount);
        for (int i = 0; i < shardCount; i++) {
            assertThat(metrics.get(i).stats().shardId()).isEqualTo(i);
        }
        long submitted = 0L;
        long reaped = 0L;
        long completed = 0L;
        long failed = 0L;
        long cancelled = 0L;
        long timedOut = 0L;
        long inFlight = 0L;
        long sqQueued = 0L;
        long cqReady = 0L;
        long latencySamples = 0L;
        long latencyTotalNanos = 0L;
        for (ShardMetrics shardMetrics : metrics) {
            RingMetrics ring = shardMetrics.ring();
            submitted += ring.submitted();
            reaped += ring.reaped();
            completed += ring.completed();
            failed += ring.failed();
            cancelled += ring.cancelled();
            timedOut += ring.timedOut();
            inFlight += ring.inFlight();
            sqQueued += ring.sqQueued();
            cqReady += ring.cqReady();
            latencySamples += shardMetrics.dispatchLatency().samples();
            latencyTotalNanos += shardMetrics.dispatchLatency().totalNanos();
        }
        assertThat(aggregate.shards()).isEqualTo(shardCount);
        assertThat(aggregate.submitted()).isEqualTo(submitted);
        assertThat(aggregate.reaped()).isEqualTo(reaped);
        assertThat(aggregate.completed()).isEqualTo(completed);
        assertThat(aggregate.failed()).isEqualTo(failed);
        assertThat(aggregate.cancelled()).isEqualTo(cancelled);
        assertThat(aggregate.timedOut()).isEqualTo(timedOut);
        assertThat(aggregate.inFlight()).isEqualTo(inFlight);
        assertThat(aggregate.sqQueued()).isEqualTo(sqQueued);
        assertThat(aggregate.cqReady()).isEqualTo(cqReady);
        assertThat(aggregate.dispatchLatency().samples())
                .as("merged histogram sample count is the sum of the shard histograms")
                .isEqualTo(latencySamples)
                .isEqualTo((long) shardCount * perShard);
        assertThat(aggregate.dispatchLatency().totalNanos()).isEqualTo(latencyTotalNanos);
        assertPlausibleDispatchLatency(aggregate.dispatchLatency());
    }

    // ------------------------------------------------------------------
    // no-throw / any-thread reads, closed shard, body-hosted shard
    // ------------------------------------------------------------------

    @Test
    void anyThreadSamplingDuringBurstNeverThrows() throws Exception {
        RingGroup group = RingGroup.create(4,
                ShardOptions.defaults().withRingEntries(512).withLatencySampleEvery(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong reads = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        Thread sampler = Thread.ofPlatform().name("sampler").daemon(true).start(() -> {
            try {
                while (!stop.get()) {
                    for (IoShard shard : new IoShard[]{group.shard(0), group.shard(1),
                            group.shard(2), group.shard(3)}) {
                        ShardMetrics metrics = shard.metrics();
                        metrics.stats();
                        metrics.ring();
                        metrics.dispatchLatency();
                        shard.stats();
                    }
                    group.metrics();
                    group.aggregateMetrics();
                    reads.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            group.start();
            int perShard = 250;
            CountDownLatch done = new CountDownLatch(4 * perShard);
            for (int i = 0; i < 4; i++) {
                group.shard(i).execute(ctx -> {
                    for (int k = 0; k < perShard; k++) {
                        long handle = ctx.ring().prepareNop();
                        ctx.onCompletion(handle, result -> done.countDown());
                    }
                    ctx.submit();
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "burst did not drain");
            waitUntil(() -> group.stats().stream()
                    .allMatch(stats -> stats.completionsDispatched() == perShard), 5_000L);
        } finally {
            stop.set(true);
            sampler.join(5_000L);
            group.close(500L);
        }
        assertThat(failure.get()).as("sampler threw: %s", failure.get()).isNull();
        assertThat(reads.get()).isGreaterThan(0L);

        // Closed shard metrics stay readable and total.
        ShardMetrics closed = group.shard(0).metrics();
        assertThat(closed.stats().closed()).isTrue();
        assertThat(closed.ring().sqQueued()).isZero();
        assertThat(closed.ring().cqReady()).isZero();
        assertThat(group.metrics()).hasSize(4);
        assertThat(group.aggregateMetrics().shards()).isEqualTo(4);
    }

    @Test
    void bodyHostedShardHasEmptyDispatchLatencyAndRealRingMetrics() throws Exception {
        Path file = tempDir.resolve("body-data");
        byte[] content = {1, 2, 3, 4, 5, 6, 7, 8};
        Files.write(file, content);
        RingGroup group = RingGroup.create(1, ShardOptions.defaults()
                .withName("body-hosted")
                .withRingEntries(64)
                .withRingOptions(IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN,
                        IORING_SETUP_DEFER_TASKRUN)
                .withLatencySampleEvery(1));
        JUringBlocking blocking = new JUringBlocking(group);
        try (FileDescriptor fd = new FileDescriptor(file, READ, 0);
             Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(content.length);
            assertThat(blocking.read(fd, buffer, content.length, 0)).isEqualTo(content.length);
            ShardMetrics live = group.shard(0).metrics();
            assertThat(live.dispatchLatency())
                    .as("body-hosted shards have no generic completion table")
                    .isEqualTo(LatencySnapshot.empty());
            assertThat(live.ring().sqEntries()).isGreaterThan(0);
            assertThat(live.ring().reaped()).isGreaterThan(0L);
            assertThat(live.ring().completed()).isGreaterThan(0L);
        } finally {
            blocking.close();
        }
        // After close: dispatch latency still empty (never recorded), ring metrics real and
        // frozen (occupancy 0, counters/entries retained); never throws.
        ShardMetrics after = group.shard(0).metrics();
        assertThat(after.dispatchLatency()).isEqualTo(LatencySnapshot.empty());
        assertThat(after.stats().closed()).isTrue();
        assertThat(after.ring().reaped()).isGreaterThan(0L);
        assertThat(after.ring().sqEntries()).isGreaterThan(0);
        assertThat(after.ring().sqQueued()).isZero();
        assertThat(after.ring().cqReady()).isZero();
    }

    // ------------------------------------------------------------------
    // zero-allocation registration + dispatch path with sampling enabled
    // ------------------------------------------------------------------

    @Test
    void metricsOnHandlerPathAllocatesZeroBytesPerCall() throws Exception {
        com.sun.management.ThreadMXBean threadBean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemoryEnabled())
                .as("thread allocated-memory measurement must be enabled")
                .isTrue();
        int n = 2_000;
        // SQ ring and handler table must cover the whole measured batch (one task, one submit).
        IoShard shard = IoShard.create(0,
                ShardOptions.defaults().withRingEntries(4_096).withLatencySampleEvery(1));
        try {
            shard.start();
            // Warm up the exact measured shapes, the fixed-bucket histogram and the JIT until
            // the measured deltas are stably zero (class-loading/JIT one-offs happen early).
            registrationProbe(shard, threadBean, n);
            dispatchProbe(shard, threadBean, n);
            int stableZeroRounds = 0;
            for (int round = 0; round < 10 && stableZeroRounds < 2; round++) {
                long warmRegistration = registrationProbe(shard, threadBean, n);
                long warmDispatch = dispatchProbe(shard, threadBean, n);
                stableZeroRounds = warmRegistration == 0L && warmDispatch == 0L
                        ? stableZeroRounds + 1 : 0;
            }
            // A real per-call allocation would show up in every round; sporadic JVM one-offs
            // (e.g. a TLAB accounting boundary or a JIT event under full-suite JVM state) can
            // appear in a single round, so the evidence is the minimum over repeated rounds.
            long[] registrationRounds = new long[8];
            long[] dispatchRounds = new long[8];
            for (int round = 0; round < registrationRounds.length; round++) {
                registrationRounds[round] = registrationProbe(shard, threadBean, n);
                dispatchRounds[round] = dispatchProbe(shard, threadBean, n);
            }
            long registrationBytes = registrationRounds[0];
            long dispatchBytes = dispatchRounds[0];
            for (int round = 1; round < registrationRounds.length; round++) {
                registrationBytes = Math.min(registrationBytes, registrationRounds[round]);
                dispatchBytes = Math.min(dispatchBytes, dispatchRounds[round]);
            }
            assertThat(registrationBytes)
                    .as("registration path (prepareNop + onCompletion, sampling enabled) "
                            + "allocated %d bytes over %d operations (rounds %s)",
                            registrationBytes, n, java.util.Arrays.toString(registrationRounds))
                    .isZero();
            assertThat(dispatchBytes)
                    .as("dispatch path (table remove + latency record + handler invoke, sampling "
                            + "enabled) allocated %d bytes over %d dispatches (rounds %s)",
                            dispatchBytes, n - 1, java.util.Arrays.toString(dispatchRounds))
                    .isZero();
        } finally {
            shard.close(500L);
        }
    }

    /**
     * Measures the registration path only (inside one shard-thread task, between two
     * {@code getCurrentThreadAllocatedBytes()} calls around the prepareNop+onCompletion loop).
     */
    private static long registrationProbe(IoShard shard, com.sun.management.ThreadMXBean threadBean,
                                          int n) throws Exception {
        AtomicLong before = new AtomicLong();
        AtomicLong after = new AtomicLong();
        CountDownLatch done = new CountDownLatch(n);
        CompletionHandler counting = result -> done.countDown();
        shard.execute(ctx -> {
            before.set(threadBean.getCurrentThreadAllocatedBytes());
            for (int i = 0; i < n; i++) {
                long handle = ctx.ring().prepareNop();
                ctx.onCompletion(handle, counting);
            }
            after.set(threadBean.getCurrentThreadAllocatedBytes());
            ctx.submit();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "registration probe did not drain");
        return after.get() - before.get();
    }

    /**
     * Measures the dispatch path only: the first sampled handler invocation starts the
     * measurement, the last one ends it, so the delta covers the n-1 dispatches in between
     * (CompletionTable remove + latency record + handler invoke).
     */
    private static long dispatchProbe(IoShard shard, com.sun.management.ThreadMXBean threadBean, int n)
            throws Exception {
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        AtomicLong before = new AtomicLong(-1L);
        AtomicLong after = new AtomicLong(-1L);
        CountDownLatch latch = new CountDownLatch(1);
        CompletionHandler measured = result -> {
            int count = seen.incrementAndGet();
            if (count == 1) {
                before.set(threadBean.getCurrentThreadAllocatedBytes());
            } else if (count == n) {
                after.set(threadBean.getCurrentThreadAllocatedBytes());
                latch.countDown();
            }
        };
        shard.execute(ctx -> {
            for (int i = 0; i < n; i++) {
                long handle = ctx.ring().prepareNop();
                ctx.onCompletion(handle, measured);
            }
            ctx.submit();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "dispatch probe did not finish");
        return after.get() - before.get();
    }

    // ------------------------------------------------------------------
    // CompletionTable timestamp bookkeeping (timestamps survive backward-shift deletion
    // and full-table put/remove semantics; a failed put leaves no timestamp behind).
    // ------------------------------------------------------------------

    @Test
    void completionTableCarriesTimestampsAcrossDeletionAndFullTable() {
        CompletionTable table = new CompletionTable(8); // capacity 8, power of two
        int capacity = table.capacity();
        for (int key = 1; key <= capacity; key++) {
            table.put(key, NO_OP_HANDLER, 1_000L + key);
        }
        assertThat(table.isFull()).isTrue();
        // Full-table put must throw without mutating or leaking a timestamp.
        assertThatThrownBy(() -> table.put(999L, NO_OP_HANDLER, 42L))
                .isInstanceOf(IllegalStateException.class);
        for (int key = 1; key <= capacity; key++) {
            assertThat(table.timestampOf(key)).isEqualTo(1_000L + key);
        }
        // Duplicate put must throw without changing the stored timestamp.
        assertThatThrownBy(() -> table.put(1L, NO_OP_HANDLER, 42L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(table.timestampOf(1L)).isEqualTo(1_001L);

        // Remove one entry: backward-shift deletion must move the timestamps with the handlers.
        CompletionHandler removed = table.remove(1L);
        assertThat(removed).isNotNull();
        assertThat(table.lastRemovedTimestamp()).isEqualTo(1_001L);
        assertThat(table.timestampOf(1L)).isZero();
        for (int key = 2; key <= capacity; key++) {
            assertThat(table.timestampOf(key)).isEqualTo(1_000L + key);
        }
        // Removing an absent key resets the last-removed timestamp to 0.
        assertThat(table.remove(1L)).isNull();
        assertThat(table.lastRemovedTimestamp()).isZero();

        // Re-insertion with a fresh timestamp, then clear drops everything.
        table.put(1L, NO_OP_HANDLER, 7_777L);
        assertThat(table.timestampOf(1L)).isEqualTo(7_777L);
        assertThat(table.remove(1L)).isNotNull();
        assertThat(table.lastRemovedTimestamp()).isEqualTo(7_777L);
        table.clear();
        assertThat(table.size()).isZero();
        for (int key = 1; key <= capacity; key++) {
            assertThat(table.timestampOf(key)).isZero();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private static void assertZeroRingMetrics(RingMetrics ring, String description) {
        assertThat(ring.sqesPrepared()).as(description).isZero();
        assertThat(ring.submitted()).as(description).isZero();
        assertThat(ring.reaped()).as(description).isZero();
        assertThat(ring.completed()).as(description).isZero();
        assertThat(ring.failed()).as(description).isZero();
        assertThat(ring.cancelled()).as(description).isZero();
        assertThat(ring.timedOut()).as(description).isZero();
        assertThat(ring.inFlight()).as(description).isZero();
        assertThat(ring.enters()).as(description).isZero();
        assertThat(ring.submitBatches()).as(description).isZero();
        assertThat(ring.submitErrors()).as(description).isZero();
        assertThat(ring.submitRetries()).as(description).isZero();
        assertThat(ring.sqFullEvents()).as(description).isZero();
        assertThat(ring.sqQueued()).as(description).isZero();
        assertThat(ring.sqEntries()).as(description).isZero();
        assertThat(ring.cqReady()).as(description).isZero();
        assertThat(ring.cqEntries()).as(description).isZero();
        assertThat(ring.cqOverflowEvents()).as(description).isZero();
    }

    private static void assertRingSnapshotConsistent(ShardMetrics metrics, long dispatched) {
        RingMetrics ring = metrics.ring();
        assertThat(ring.reaped()).isEqualTo(ring.completed() + ring.failed());
        assertThat(ring.inFlight()).isEqualTo(Math.max(0L, ring.submitted() - ring.reaped()));
        assertThat(ring.sqQueued()).isNotNegative();
        assertThat(ring.cqReady()).isNotNegative();
        assertThat(ring.sqEntries()).isGreaterThan(0);
        assertThat(ring.cqEntries()).isGreaterThan(0);
        assertThat(ring.submitted()).isNotNegative();
        assertThat(ring.reaped()).isNotNegative();
        assertThat(ring.completed()).isNotNegative();
    }

    private static void assertPlausibleDispatchLatency(LatencySnapshot latency) {
        assertThat(latency.samples()).isGreaterThan(0L);
        assertThat(latency.maxNanos()).isGreaterThan(0L);
        assertThat(latency.minNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(latency.p50Nanos()).isLessThanOrEqualTo(latency.p90Nanos());
        assertThat(latency.p90Nanos()).isLessThanOrEqualTo(latency.p99Nanos());
        assertThat(latency.p99Nanos()).isLessThanOrEqualTo(latency.p999Nanos());
        assertThat(latency.p999Nanos()).isLessThanOrEqualTo(latency.maxNanos());
        assertThat(latency.meanNanos()).isGreaterThan(0.0);
        assertThat(latency.totalNanos()).isGreaterThanOrEqualTo(latency.maxNanos());
    }

    private static void runNops(IoShard shard, int operations) throws Exception {
        CountDownLatch done = new CountDownLatch(operations);
        shard.execute(ctx -> {
            for (int i = 0; i < operations; i++) {
                long handle = ctx.ring().prepareNop();
                ctx.onCompletion(handle, result -> done.countDown());
            }
            ctx.submit();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "NOP burst did not complete");
        waitUntil(() -> shard.stats().completionsDispatched() == operations, 10_000L);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(1L);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition not met within " + timeoutMillis + " ms");
        }
    }

}
