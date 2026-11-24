package com.davidvlijmincx.lio.api;

/**
 * Immutable snapshot of one {@link IoShard}'s lifecycle, counters and affinity state.
 *
 * <p>{@link IoShard#stats()} may be called from any thread, before start, while running and
 * after close. Counters are monotone for the lifetime of the shard.
 *
 * <h2>Body-hosted shards</h2>
 * <p>A shard whose loop is provided by an internal {@code ShardBody} (the {@code
 * JUringBlocking} integration path) runs no generic task/handoff loop: for those
 * shards {@code tasksSubmitted/Executed/Rejected/Failures},
 * {@code completionsRegistered/Dispatched/Unattributed/Failures},
 * {@code handoffsSent/handoffsInlineFallback}, {@code eventfdWrites} and {@code
 * wakeupArmed} stay {@code 0}/{@code false} (the body may report {@code ringSubmits} through
 * its loop context). {@code inFlightRequests} reports the underlying ring's in-flight count
 * (including the body's own internal wakeup read). Lifecycle, affinity and the ring's
 * {@code cancelledAtClose}/{@code abandonedAtClose} are still maintained by {@code IoShard}.
 *
 * @param shardId                 shard id (0-based within its {@link RingGroup})
 * @param threadName              shard platform thread name ({@code null} before start)
 * @param requestedCpu            requested logical CPU, or {@link ShardOptions#NO_CPU_AFFINITY}
 * @param currentCpu              CPU reported by {@code sched_getcpu()} at start ({@code -1} before start)
 * @param affinityApplied         whether {@code sched_setaffinity} succeeded at start
 * @param started                 whether the shard loop is currently running
 * @param stopping                whether a stop was requested
 * @param closed                  whether {@link IoShard#close(long)} completed (or the shard failed to start)
 * @param tasksSubmitted          accepted {@link ShardTask}s (generic loop only)
 * @param tasksExecuted           executed {@link ShardTask}s (generic loop only)
 * @param taskFailures            caught task/continuation exceptions (generic loop only)
 * @param tasksRejected           rejected {@code execute} calls (stopped/closed/full-from-shard-thread)
 * @param completionsRegistered   successful {@code onCompletion} registrations
 * @param completionsDispatched   handlers invoked
 * @param completionsUnattributed completions reaped without a registered handler (wakeup excluded)
 * @param completionFailures      caught handler exceptions
 * @param handoffsSent            continuations enqueued to another shard's MPSC queue
 * @param handoffsInlineFallback  continuations run inline because the target queue stayed full
 * @param eventfdWrites           eventfd wake writes issued by the shard runtime
 * @param ringSubmits             ring submit calls that actually flushed SQEs
 * @param inFlightRequests        ring requests in flight (snapshot; may lag the event loop by one
 *                                pass), excluding the generic loop's wakeup read
 * @param wakeupArmed             whether the generic loop's eventfd wakeup read is currently armed
 * @param cancelledAtClose        requests whose {@code -ECANCELED} completion was observed during shutdown
 * @param abandonedAtClose        requests still in flight when the bounded ring close gave up
 * @param closeTimedOut           whether {@code close} gave up waiting for the shard thread
 */
public record ShardStats(
        int shardId, String threadName,
        int requestedCpu, int currentCpu, boolean affinityApplied,
        boolean started, boolean stopping, boolean closed,
        long tasksSubmitted, long tasksExecuted, long taskFailures, long tasksRejected,
        long completionsRegistered, long completionsDispatched,
        long completionsUnattributed, long completionFailures,
        long handoffsSent, long handoffsInlineFallback,
        long eventfdWrites, long ringSubmits,
        long inFlightRequests,
        boolean wakeupArmed,
        long cancelledAtClose, long abandonedAtClose, boolean closeTimedOut) {
}
