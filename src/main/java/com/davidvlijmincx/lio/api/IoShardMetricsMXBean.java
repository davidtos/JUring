package com.davidvlijmincx.lio.api;

/**
 * JMX MXBean view of one {@link IoShard}.
 *
 * <p>Attributes are <b>pull-based</b> and never throw, including for a closed or failed shard.
 * A bean serves every attribute from one coherent cached {@link IoShard#metrics()} snapshot per
 * short refresh window (50 ms, see {@link MetricsJmx}), so the attributes of a single scrape
 * describe one instant instead of each getter re-snapshotting the shard. Values are therefore at
 * most one window old, and a scrape that spans a window boundary can mix two adjacent
 * snapshots; there is no exporter thread and no background work — refreshes happen on the
 * reading thread. Counters are {@code long}s, latency
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

    /**
     * Returns the shard id (0-based within its {@link RingGroup}).
     *
     * @return the shard id
     */
    int getShardId();

    /**
     * Returns the shard platform thread name.
     *
     * @return the thread name, or {@code null} before start
     */
    String getThreadName();

    /**
     * Returns the CPU reported by {@code sched_getcpu()} when the shard started.
     *
     * @return the current CPU id, or {@code -1} before start
     */
    int getCurrentCpu();

    /**
     * Returns whether the configured CPU affinity was applied at start.
     *
     * @return whether {@code sched_setaffinity} succeeded
     */
    boolean isAffinityApplied();

    /**
     * Returns whether the shard loop is currently running.
     *
     * @return whether the shard loop is currently running
     */
    boolean isStarted();

    /**
     * Returns whether a stop was requested.
     *
     * @return whether {@link IoShard#requestStop()} has been observed
     */
    boolean isStopping();

    /**
     * Returns whether the shard is closed.
     *
     * @return whether {@link IoShard#close(long)} completed (or the shard failed to start)
     */
    boolean isClosed();

    // --- ring counters / occupancy (JUring.ringMetrics, nanoseconds-free; see RingMetrics) ---

    /**
     * Returns the number of successful SQE allocations taken from the submission queue.
     *
     * @return successful {@code getSqe()} allocations
     */
    long getSqesPrepared();

    /**
     * Returns the number of SQEs the kernel has consumed (owner-observed SQ {@code khead}
     * progress).
     *
     * @return the submitted-SQE counter
     */
    long getSubmitted();

    /**
     * Returns the number of CQEs consumed by the library (CQ {@code khead} progress),
     * including failed completions.
     *
     * @return the reaped-CQE counter
     */
    long getReaped();

    /**
     * Returns the number of reaped CQEs with a non-negative result.
     *
     * @return the completed-operation counter
     */
    long getCompleted();

    /**
     * Returns the number of reaped CQEs with a negative result.
     *
     * @return the failed-operation counter
     */
    long getFailed();

    /**
     * Returns the number of reaped CQEs with result {@code -ECANCELED}.
     *
     * @return the cancelled-operation counter
     */
    long getCancelled();

    /**
     * Returns the number of reaped CQEs with result {@code -ETIME} or {@code -ETIMEDOUT}.
     *
     * @return the timed-out-operation counter
     */
    long getTimedOut();

    /**
     * Returns the number of in-flight ring requests ({@code max(0, submitted - reaped)}).
     *
     * @return the in-flight request count
     */
    long getInFlight();

    /**
     * Returns the number of {@code io_uring_enter()} calls issued by the ring.
     *
     * @return the enter-call counter
     */
    long getEnters();

    /**
     * Returns the number of flush calls that set a non-empty SQ tail.
     *
     * @return the submit-batch counter
     */
    long getSubmitBatches();

    /**
     * Returns the number of {@code io_uring_enter()} failures that are not classified as
     * retries (for example {@code EBUSY}).
     *
     * @return the submit-error counter
     */
    long getSubmitErrors();

    /**
     * Returns the number of {@code io_uring_enter()} {@code EAGAIN}/{@code EINTR} retries.
     *
     * @return the submit-retry counter
     */
    long getSubmitRetries();

    /**
     * Returns the number of SQE allocations that failed because the submission queue was
     * full.
     *
     * @return the SQ-full event counter
     */
    long getSqFullEvents();

    /**
     * Returns the current SQ occupancy (prepared minus kernel-consumed).
     *
     * @return the SQ occupancy; {@code 0} after the ring is closed
     */
    long getSqQueued();

    /**
     * Returns the effective submission-queue ring depth.
     *
     * @return the SQ ring depth
     */
    int getSqEntries();

    /**
     * Returns the number of CQEs currently available to reap.
     *
     * @return the CQ occupancy; {@code 0} after the ring is closed
     */
    long getCqReady();

    /**
     * Returns the effective completion-queue ring depth.
     *
     * @return the CQ ring depth
     */
    int getCqEntries();

    /**
     * Returns the kernel's completion-queue overflow counter ({@code cq.koverflow}).
     *
     * @return the CQ overflow event count (best effort)
     */
    long getCqOverflowEvents();

    // --- shard counters (ShardStats) ---

    /**
     * Returns the number of accepted {@link ShardTask}s (generic loop only).
     *
     * @return the submitted-task counter
     */
    long getTasksSubmitted();

    /**
     * Returns the number of executed {@link ShardTask}s (generic loop only).
     *
     * @return the executed-task counter
     */
    long getTasksExecuted();

    /**
     * Returns the number of caught task or continuation exceptions (generic loop only).
     *
     * @return the task-failure counter
     */
    long getTaskFailures();

    /**
     * Returns the number of rejected {@code execute} calls (stopped, closed, or a full
     * inbound queue from the shard thread).
     *
     * @return the rejected-task counter
     */
    long getTasksRejected();

    /**
     * Returns the number of successful completion-handler registrations.
     *
     * @return the registered-completion counter
     */
    long getCompletionsRegistered();

    /**
     * Returns the number of completion handlers invoked.
     *
     * @return the dispatched-completion counter
     */
    long getCompletionsDispatched();

    /**
     * Returns the number of reaped completions without a registered handler (the internal
     * wakeup read is excluded).
     *
     * @return the unattributed-completion counter
     */
    long getCompletionsUnattributed();

    /**
     * Returns the number of completion-handler exceptions caught by the loop.
     *
     * @return the completion-failure counter
     */
    long getCompletionFailures();

    /**
     * Returns the number of continuations enqueued to another shard's MPSC queue.
     *
     * @return the sent-handoff counter
     */
    long getHandoffsSent();

    /**
     * Returns the number of continuations that ran inline because the target queue stayed
     * full.
     *
     * @return the inline-handoff-fallback counter
     */
    long getHandoffsInlineFallback();

    /**
     * Returns the number of eventfd wake writes issued by the shard runtime.
     *
     * @return the eventfd-write counter
     */
    long getEventfdWrites();

    /**
     * Returns the number of ring submit calls that actually flushed SQEs.
     *
     * @return the ring-submit counter
     */
    long getRingSubmits();

    /**
     * Returns the number of requests whose {@code -ECANCELED} completion was observed during
     * shutdown.
     *
     * @return the cancelled-at-close counter
     */
    long getCancelledAtClose();

    /**
     * Returns the number of requests still in flight when the bounded ring close gave up.
     *
     * @return the abandoned-at-close counter
     */
    long getAbandonedAtClose();

    /**
     * Returns whether {@link IoShard#close(long)} gave up waiting for the shard thread.
     *
     * @return whether the close timed out
     */
    boolean isCloseTimedOut();

    // --- completion-dispatch latency (microseconds; 0 when disabled/empty) ---

    /**
     * Returns the number of recorded dispatch-latency samples.
     *
     * @return the sample count
     */
    long getLatencySamples();

    /**
     * Returns the mean completion-dispatch latency in microseconds.
     *
     * @return the mean latency, or {@code 0.0} when no sample was recorded
     */
    double getLatencyMeanMicros();

    /**
     * Returns the 50th-percentile completion-dispatch latency in microseconds.
     *
     * @return the 50th-percentile latency (a bucket maximum; {@code 0.0} when no sample was
     *         recorded)
     */
    double getLatencyP50Micros();

    /**
     * Returns the 90th-percentile completion-dispatch latency in microseconds.
     *
     * @return the 90th-percentile latency (a bucket maximum; {@code 0.0} when no sample was
     *         recorded)
     */
    double getLatencyP90Micros();

    /**
     * Returns the 99th-percentile completion-dispatch latency in microseconds.
     *
     * @return the 99th-percentile latency (a bucket maximum; {@code 0.0} when no sample was
     *         recorded)
     */
    double getLatencyP99Micros();

    /**
     * Returns the 99.9th-percentile completion-dispatch latency in microseconds.
     *
     * @return the 99.9th-percentile latency (a bucket maximum; {@code 0.0} when no sample
     *         was recorded)
     */
    double getLatencyP999Micros();

    /**
     * Returns the maximum recorded completion-dispatch latency in microseconds.
     *
     * @return the exact maximum latency, or {@code 0.0} when no sample was recorded
     */
    double getLatencyMaxMicros();

    /**
     * Returns the minimum recorded completion-dispatch latency in microseconds.
     *
     * @return the exact minimum latency, or {@code 0.0} when no sample was recorded
     */
    double getLatencyMinMicros();
}
