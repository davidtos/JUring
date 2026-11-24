package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Caller-controlled group commit over a {@link JuringFile}.
 *
 * <p>A group commit turns many asynchronous writes into <strong>one</strong> durability
 * sync: {@link #write(long, MemorySegment)} queues a write asynchronously (it may still be
 * in flight when the method returns), and {@link #commit()} waits for every queued write of
 * the current batch before issuing exactly one {@code fdatasync} (or {@code fsync}) for the
 * whole batch. The batch boundary is caller-controlled: there is <strong>no implicit
 * auto-commit</strong>; a {@code write} on a full batch throws {@link IllegalStateException}
 * and the caller has to {@code commit()} first.
 *
 * <p>Typical usage:
 * <pre>{@code
 * try (GroupCommit group = GroupCommit.open(file,
 *         GroupCommitOptions.defaults().withMaxBatchSize(32))) {
 *     for (Record r : batch) {
 *         group.write(r.offset(), r.segment());   // segment valid until commit() returns
 *     }
 *     group.commit();                             // one fdatasync for the whole batch
 * }
 * }</pre>
 *
 * <h2>Buffer lifetime (zero-copy)</h2>
 * <p>The group does <strong>not</strong> copy the bytes. The memory of every
 * {@code source} passed to {@link #write(long, MemorySegment)} must remain valid and
 * unmodified until the enclosing {@link #commit()} (or {@link #close()}) has returned.
 * This mirrors the {@code JuringFile.prepareWrite} contract.
 *
 * <h2>Durability</h2>
 * <p>{@link #durabilityMode()} defaults to {@link DurabilityMode#FDATASYNC}: a successful
 * commit means every write of the batch completed and the {@code fdatasync} completed, so
 * the bytes are visible to a subsequent open (same or new process) subject to the
 * documented {@code fdatasync} metadata scope. {@link DurabilityMode#WRITEBACK} completes
 * the writes without a sync; {@link DurabilityMode#DSYNC} requires a file opened with
 * {@code O_DSYNC} and never issues an explicit sync. {@link DurabilityMode#FSYNC} issues one
 * {@code fsync} per batch. All modes are validated at {@link #open}.
 *
 * <h2>Bounded memory / allocation</h2>
 * <p>{@link #open} allocates O({@code maxBatchSize}) handle/length arrays once; steady-state
 * {@link #write}/{@link #commit()} allocate no heap memory. No ring, thread, executor or
 * arena is created.
 *
 * <h2>Errors</h2>
 * <p>The first {@link JuringException} from a write completion or a sync is latched: every
 * later {@code write}/{@code commit}/{@code close} rethrows the same exception object.
 * {@code commit()} drains the remaining writes of a failed batch before latching so the
 * file's in-flight ledger is not left with unowned operations. A
 * {@link IllegalStateException} for use after {@code close()} or a full batch is not
 * latched.
 *
 * <h2>Threading / ownership</h2>
 * <p>Not thread-safe: exactly like {@link JuringFile}, one owner thread per handle and ring.
 * A batch can leave up to {@code maxBatchSize} operations in flight on the file's ring; that
 * ring may be shared with sibling {@code JuringFile}-based handles, whose
 * completions are routed to their own ledgers. Only raw un-reaped ring work is rejected.
 * This is a caller-driven batch, not a daemon.
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #close()} commits a pending batch (exactly like {@link #commit()}), is
 * idempotent and does <strong>not</strong> close the backing {@link JuringFile}. After
 * {@code close()}, {@code write}/{@code commit} throw {@link IllegalStateException};
 * {@link #stats()}, {@link #pendingWrites()} and {@link #pendingBytes()} remain readable.
 */
public final class GroupCommit implements AutoCloseable {

    /** Default maximum batch size ({@code 32}) when {@link GroupCommitOptions} are not used. */
    public static final int DEFAULT_MAX_BATCH_SIZE = 32;

    private final JuringFile file;
    private final DurabilityMode durability;
    private final int maxBatchSize;

    // Batch bookkeeping: allocated once at open (O(maxBatchSize)); no steady-state allocation.
    private long[] handles;
    private int[] lengths;
    private int pendingWrites;
    private long pendingBytes;

    private long batches;
    private long committedWrites;
    private long committedBytes;
    private long syncs;
    private long failedCommits;
    private int maxObservedBatch;

    private JuringException failure;
    private boolean closed;

    private GroupCommit(JuringFile file, GroupCommitOptions options) {
        this.file = file;
        this.durability = options.durability();
        this.maxBatchSize = options.maxBatchSize();
        this.handles = new long[this.maxBatchSize];
        this.lengths = new int[this.maxBatchSize];
    }

    // ------------------------------------------------------------------
    // factories
    // ------------------------------------------------------------------

    /**
     * Opens a group commit with the default options ({@link DurabilityMode#FDATASYNC},
     * {@code maxBatchSize = 32}).
     *
     * @param file the open backing file
     * @return a new group commit
     * @throws IllegalArgumentException if the batch-size/capacity or {@code DSYNC} check fails
     * @throws IllegalStateException    if the file is closed
     */
    public static GroupCommit open(JuringFile file) {
        return open(file, GroupCommitOptions.defaults());
    }

    /**
     * Opens a group commit.
     *
     * <p>Validation (frozen order): non-null arguments, an open file, then
     * {@code maxBatchSize + 1 <= file.inFlightCapacity()} (one ledger slot per queued write
     * plus one reserved slot for the commit's sync), then the
     * {@link DurabilityMode#DSYNC} assertion against {@link JuringFile#durability()}.
     *
     * @param file    the open backing file
     * @param options group-commit options
     * @return a new group commit
     * @throws NullPointerException     if {@code file} or {@code options} is {@code null}
     * @throws IllegalArgumentException if the batch-size/capacity or {@code DSYNC} check fails
     * @throws IllegalStateException    if the file is closed
     */
    public static GroupCommit open(JuringFile file, GroupCommitOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        if (!file.isOpen()) {
            throw new IllegalStateException("GroupCommit.open: file is closed (" + file.path() + ")");
        }
        int batchSize = options.maxBatchSize();
        int capacity = file.inFlightCapacity();
        if (batchSize + 1 > capacity) {
            throw new IllegalArgumentException("GroupCommit.open: maxBatchSize " + batchSize
                    + " needs " + (batchSize + 1) + " in-flight ledger slots (one per queued write plus one"
                    + " reserved for the commit sync) but the file's in-flight capacity is " + capacity
                    + "; raise it with JuringFile.withInFlightCapacity or lower maxBatchSize ("
                    + file.path() + ")");
        }
        if (options.durability() == DurabilityMode.DSYNC && file.durability() != DurabilityMode.DSYNC) {
            throw new IllegalArgumentException("GroupCommit.open: durability mode DSYNC requires the file "
                    + "to be opened with O_DSYNC (LinuxOpenOptions.DSYNC or SYNC): options.durability()="
                    + options.durability() + ", file.durability()=" + file.durability() + " (" + file.path() + ")");
        }
        return new GroupCommit(file, options);
    }

    // ------------------------------------------------------------------
    // accessors
    // ------------------------------------------------------------------

    /**
     * Returns the backing file (never closed by this group).
     *
     * @return the backing file
     */
    public JuringFile file() {
        return file;
    }

    /**
     * Returns the durability mode applied by {@link #commit()} (the echo of the option).
     *
     * @return the configured durability mode
     */
    public DurabilityMode durabilityMode() {
        return durability;
    }

    /**
     * Returns the maximum number of writes in one uncommitted batch.
     *
     * @return the configured maximum batch size
     */
    public int maxBatchSize() {
        return maxBatchSize;
    }

    /**
     * Returns the number of writes in the current (uncommitted) batch.
     *
     * @return the pending write count; {@code 0} after a commit
     */
    public int pendingWrites() {
        return pendingWrites;
    }

    /**
     * Returns the sum of the buffer lengths of the writes in the current batch.
     *
     * @return the pending byte count; {@code 0} after a commit
     */
    public long pendingBytes() {
        return pendingBytes;
    }

    // ------------------------------------------------------------------
    // writing
    // ------------------------------------------------------------------

    /**
     * Queues one asynchronous write in the current batch.
     *
     * <p>The write is prepared through {@link JuringFile#prepareWrite} and may still be in
     * flight when this method returns; {@link #commit()} waits for it. The method does not
     * submit, drain or sync. The bytes of {@code source} must stay valid and unmodified
     * until the enclosing {@code commit()}/{@code close()} returns (no copy is made).
     *
     * @param offset file offset, {@code >= 0}
     * @param source native segment containing the bytes to write (not copied)
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code offset < 0} or the segment is invalid
     *                                  (delegated to {@link JuringFile#prepareWrite})
     * @throws IllegalStateException    if the group/file is closed, a failure has been
     *                                  latched, or the batch is full ("commit() first")
     * @throws JuringException          if the write cannot be prepared
     */
    public void write(long offset, MemorySegment source) throws JuringException {
        ensureWritable();
        Objects.requireNonNull(source, "source");
        if (pendingWrites == maxBatchSize) {
            throw new IllegalStateException("GroupCommit.write: batch full (" + pendingWrites + " of "
                    + maxBatchSize + " writes); commit() first (" + file.path() + ")");
        }
        long size = source.byteSize();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("source too large for a group-commit write: " + size);
        }
        // Offset/segment validation and in-flight bookkeeping happen in JuringFile.prepareWrite.
        long handle = file.prepareWrite(offset, source);
        handles[pendingWrites] = handle;
        lengths[pendingWrites] = (int) size;
        pendingWrites++;
        pendingBytes += size;
    }

    // ------------------------------------------------------------------
    // commit
    // ------------------------------------------------------------------

    /**
     * Makes the current batch durable and returns the number of writes it contained.
     *
     * <p>An empty batch returns {@code 0} and issues neither a write nor a sync. Otherwise
     * every queued write is awaited in submission order (so the sync is ordered after all
     * of them) and then exactly one {@code fdatasync}/{@code fsync} is issued for
     * {@link DurabilityMode#FDATASYNC}/{@link DurabilityMode#FSYNC}; no sync is issued for
     * {@link DurabilityMode#WRITEBACK}/{@link DurabilityMode#DSYNC}.
     *
     * <p>A successful commit increments {@link GroupCommitStats#batches()},
     * {@link GroupCommitStats#committedWrites()} and
     * {@link GroupCommitStats#committedBytes()}, records the batch size in
     * {@link GroupCommitStats#maxObservedBatch()}, and adds the mode's one sync (if any) to
     * {@link GroupCommitStats#syncs()}.
     *
     * <p>If a write or the sync fails, the remaining writes are drained, the first
     * {@link JuringException} is latched (thrown now and on every later call) and
     * {@code failedCommits} is incremented. No sync is issued for a failed batch.
     *
     * @return the number of writes made durable by this call ({@code 0} for an empty batch)
     * @throws IllegalStateException if the group/file is closed, or a full batch is invalid
     *                               only on {@code write}
     * @throws JuringException       if a write or the sync failed (latched)
     */
    public int commit() throws JuringException {
        ensureOpen();
        if (failure != null) {
            throw failure;
        }
        int n = pendingWrites;
        if (n == 0) {
            return 0;
        }
        JuringException first = null;
        // 1) Await every queued write (submission order), draining the rest even after a
        //    failure so the file's in-flight ledger is not left with unowned operations.
        for (int i = 0; i < n; i++) {
            try {
                int written = file.await(handles[i]);
                if (first == null && written < lengths[i]) {
                    first = new JuringException(0, "write", "short group-commit write at batch index "
                            + i + ": " + written + " of " + lengths[i] + " bytes (" + file.path() + ")");
                }
            } catch (JuringException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        long batchBytes = pendingBytes;
        clearBatch();
        if (first != null) {
            failedCommits++;
            failure = first;
            throw first;
        }
        // 2) Exactly one sync for the whole batch (none for WRITEBACK/DSYNC).
        int issuedSyncs = 0;
        if (durability == DurabilityMode.FDATASYNC || durability == DurabilityMode.FSYNC) {
            try {
                if (durability == DurabilityMode.FSYNC) {
                    file.fsync();
                } else {
                    file.fdatasync();
                }
                issuedSyncs = 1;
            } catch (JuringException e) {
                failedCommits++;
                failure = e;
                throw e;
            }
        }
        batches++;
        committedWrites += n;
        committedBytes += batchBytes;
        syncs += issuedSyncs;
        if (n > maxObservedBatch) {
            maxObservedBatch = n;
        }
        return n;
    }

    /**
     * Snapshot of the group's counters. Allocates a fresh immutable record; never on the
     * {@code write}/{@code commit} hot path.
     *
     * @return the current statistics (readable after {@link #close()})
     */
    public GroupCommitStats stats() {
        return new GroupCommitStats(batches, committedWrites, committedBytes, syncs,
                failedCommits, maxObservedBatch);
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /**
     * Commits a pending batch (exactly like {@link #commit()}), marks the group closed and
     * releases its batch bookkeeping. Idempotent and does <strong>not</strong> close the
     * backing {@link JuringFile}.
     *
     * <p>A {@code close()} after a latched failure rethrows the latched
     * {@link JuringException} and still marks the group closed; a second {@code close()} is a
     * no-op. After close, {@code write}/{@code commit} throw {@link IllegalStateException}.
     *
     * @throws JuringException if the pending batch's commit (write or sync) failed (latched)
     */
    @Override
    public void close() throws JuringException {
        if (closed) {
            return;
        }
        if (failure != null) {
            closed = true;
            releaseBookkeeping();
            throw failure;
        }
        JuringException first = null;
        try {
            if (pendingWrites > 0) {
                commit();
            }
        } catch (JuringException e) {
            first = e;
        } finally {
            closed = true;
            releaseBookkeeping();
        }
        if (first != null) {
            throw first;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("GroupCommit: group is closed (" + file.path() + ")");
        }
        if (!file.isOpen()) {
            throw new IllegalStateException("GroupCommit: backing file is closed (" + file.path() + ")");
        }
    }

    private void ensureWritable() throws JuringException {
        ensureOpen();
        if (failure != null) {
            throw failure;
        }
    }

    private void clearBatch() {
        pendingWrites = 0;
        pendingBytes = 0L;
    }

    /** Drops the O(maxBatchSize) batch arrays; the counters remain readable. */
    private void releaseBookkeeping() {
        handles = null;
        lengths = null;
    }

    @Override
    public String toString() {
        return "GroupCommit{file=" + file.path()
                + ", durability=" + durability
                + ", maxBatchSize=" + maxBatchSize
                + ", pendingWrites=" + pendingWrites
                + ", pendingBytes=" + pendingBytes
                + ", batches=" + batches
                + ", syncs=" + syncs
                + (failure != null ? ", failed" : "")
                + (closed ? ", closed" : "") + '}';
    }
}
