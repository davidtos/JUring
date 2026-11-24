package com.davidvlijmincx.lio.api;

/**
 * Immutable statistics of one {@link GroupCommit} instance.
 *
 * <p>All components are non-negative and monotone non-decreasing except
 * {@code maxObservedBatch}, which is monotone non-decreasing and bounded by
 * {@link GroupCommit#maxBatchSize()}. A {@link GroupCommit} snapshots its counters into a
 * fresh record on every {@link GroupCommit#stats()} call; the returned value never changes.
 * The statistics are readable after {@code close()} and are frozen once a failure has been
 * latched.
 *
 * @param batches          successful non-empty {@link GroupCommit#commit()} calls
 * @param committedWrites  writes made durable by those successful commits
 * @param committedBytes   sum of the buffer lengths of those writes
 * @param syncs            successful {@code fdatasync}/{@code fsync} operations issued
 *                         ({@code <= batches}; equality for {@link DurabilityMode#FDATASYNC}/
 *                         {@link DurabilityMode#FSYNC} when every batch succeeded)
 * @param failedCommits    commit calls that threw (write or sync failure)
 * @param maxObservedBatch largest committed batch seen ({@code 0} before any commit)
 */
public record GroupCommitStats(
        long batches,
        long committedWrites,
        long committedBytes,
        long syncs,
        long failedCommits,
        int maxObservedBatch) {
}
