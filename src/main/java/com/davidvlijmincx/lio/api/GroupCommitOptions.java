package com.davidvlijmincx.lio.api;

import java.util.Objects;

/**
 * Immutable options for {@link GroupCommit}.
 *
 * <p>Defaults are {@link DurabilityMode#FDATASYNC} (a commit is durable: one
 * {@code fdatasync} per non-empty batch) and a maximum batch size of
 * {@link GroupCommit#DEFAULT_MAX_BATCH_SIZE} writes. Set
 * {@link DurabilityMode#WRITEBACK} to complete a commit without a sync, or
 * {@link DurabilityMode#DSYNC} for a file already opened with {@code O_DSYNC}.
 *
 * <p>The batch-size bound {@code 1..4096} is validated here; the file-dependent checks
 * ({@code maxBatchSize + 1 <= file.inFlightCapacity()} and the {@code DSYNC} assertion) are
 * performed by {@link GroupCommit#open} because they need the backing file.
 *
 * <p>Example:
 * <pre>{@code
 * GroupCommitOptions opts = GroupCommitOptions.defaults()
 *         .withDurabilityMode(DurabilityMode.FSYNC)
 *         .withMaxBatchSize(64);
 * }</pre>
 */
public final class GroupCommitOptions {

    /** Smallest allowed maximum batch size. */
    private static final int MIN_BATCH_SIZE = 1;

    /** Largest allowed maximum batch size. */
    private static final int MAX_BATCH_SIZE = 4096;

    private static final GroupCommitOptions DEFAULTS =
            new GroupCommitOptions(DurabilityMode.FDATASYNC, GroupCommit.DEFAULT_MAX_BATCH_SIZE);

    private final DurabilityMode durability;
    private final int maxBatchSize;

    private GroupCommitOptions(DurabilityMode durability, int maxBatchSize) {
        this.durability = Objects.requireNonNull(durability, "durability");
        if (maxBatchSize < MIN_BATCH_SIZE || maxBatchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("maxBatchSize must be in [" + MIN_BATCH_SIZE
                    + ", " + MAX_BATCH_SIZE + "]: " + maxBatchSize);
        }
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * Returns the shared default options: {@link DurabilityMode#FDATASYNC} and
     * {@code maxBatchSize == 32}.
     *
     * @return the shared default options
     */
    public static GroupCommitOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the durability mode applied by {@link GroupCommit#commit()}.
     *
     * @return the configured durability mode
     */
    public DurabilityMode durability() {
        return durability;
    }

    /**
     * Returns the maximum number of writes in one uncommitted batch.
     *
     * @return the configured batch-size bound, {@code 1..4096}
     */
    public int maxBatchSize() {
        return maxBatchSize;
    }

    /**
     * Returns a copy with a different durability mode.
     *
     * @param durability the mode, must not be {@code null}
     * @return a new options object
     */
    public GroupCommitOptions withDurabilityMode(DurabilityMode durability) {
        return new GroupCommitOptions(durability, maxBatchSize);
    }

    /**
     * Returns a copy with a different maximum batch size.
     *
     * @param maxBatchSize writes per batch, {@code 1..4096}
     * @return a new options object
     */
    public GroupCommitOptions withMaxBatchSize(int maxBatchSize) {
        return new GroupCommitOptions(durability, maxBatchSize);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupCommitOptions other)) {
            return false;
        }
        return durability == other.durability && maxBatchSize == other.maxBatchSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(durability, maxBatchSize);
    }

    @Override
    public String toString() {
        return "GroupCommitOptions{durability=" + durability
                + ", maxBatchSize=" + maxBatchSize + '}';
    }
}
