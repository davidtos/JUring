package com.davidvlijmincx.lio.blackpanda;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Kafka-compatible sparse index backing {@code .index} and {@code .timeindex} files.
 * <p>
 * Backed by off-heap memory ({@link Arena#ofShared()}) for lock-free binary search navigation
 * with zero heap allocations on lookups and appends.
 * <p>
 * Entry formats:
 * - {@code .index}: 8 bytes per entry (4-byte relative offset, 4-byte physical position)
 * - {@code .timeindex}: 12 bytes per entry (8-byte timestamp, 4-byte relative offset)
 */
public final class UringSparseIndex implements AutoCloseable {

    /** Default sparse-index interval in bytes (one entry per 4 KB of segment data). */
    public static final int DEFAULT_INDEX_INTERVAL_BYTES = 4096;
    /** Maximum number of entries per index file (4 MB of entries, about 2 GB of segment data at the default interval). */
    public static final int MAX_ENTRIES = 524_288;

    /** Size in bytes of one {@code .index} entry: 4-byte relative offset followed by 4-byte physical position. */
    public static final int OFFSET_ENTRY_SIZE = 8;
    /** Size in bytes of one {@code .timeindex} entry: 8-byte timestamp followed by 4-byte relative offset. */
    public static final int TIME_ENTRY_SIZE = 12;

    private static final ValueLayout.OfLong JAVA_LONG_U = ValueLayout.JAVA_LONG.withByteAlignment(1);
    private static final ValueLayout.OfInt JAVA_INT_U = ValueLayout.JAVA_INT.withByteAlignment(1);

    private final Path indexPath;
    private final Path timeIndexPath;
    private final long baseOffset;
    private final int indexIntervalBytes;

    private final Arena arena;
    private final MemorySegment offsetSegment;
    private final MemorySegment timeSegment;

    private int offsetEntriesCount;
    private int timeEntriesCount;
    private long lastIndexedPosition = -1L;
    private volatile boolean closed;

    /**
     * Opens (or creates) the sparse index files for a segment.
     *
     * <p>The index is held in off-heap shared memory; when the files already exist their
     * entries are loaded. File names derive from the zero-padded base offset
     * ({@code <offset>.index}, {@code <offset>.timeindex}).
     *
     * @param partitionDir       directory that holds the index files
     * @param baseOffset         logical offset of the first record in the segment
     * @param indexIntervalBytes index interval in bytes; a non-positive value selects
     *                           {@link #DEFAULT_INDEX_INTERVAL_BYTES}
     * @throws RuntimeException when an existing index file cannot be read
     */
    public UringSparseIndex(Path partitionDir, long baseOffset, int indexIntervalBytes) {
        this.baseOffset = baseOffset;
        this.indexIntervalBytes = indexIntervalBytes > 0 ? indexIntervalBytes : DEFAULT_INDEX_INTERVAL_BYTES;
        String prefix = String.format("%020d", baseOffset);
        this.indexPath = partitionDir.resolve(prefix + ".index");
        this.timeIndexPath = partitionDir.resolve(prefix + ".timeindex");

        this.arena = Arena.ofShared();
        this.offsetSegment = arena.allocate((long) MAX_ENTRIES * OFFSET_ENTRY_SIZE, 8);
        this.timeSegment = arena.allocate((long) MAX_ENTRIES * TIME_ENTRY_SIZE, 8);

        this.offsetEntriesCount = 0;
        this.timeEntriesCount = 0;

        loadFromDiskIfExists();
    }

    private void loadFromDiskIfExists() {
        try {
            if (Files.exists(indexPath)) {
                long size = Files.size(indexPath);
                int entries = (int) (size / OFFSET_ENTRY_SIZE);
                if (entries > 0) {
                    try (FileChannel fc = FileChannel.open(indexPath, StandardOpenOption.READ)) {
                        fc.read(offsetSegment.asSlice(0, (long) entries * OFFSET_ENTRY_SIZE).asByteBuffer());
                    }
                    this.offsetEntriesCount = Math.min(entries, MAX_ENTRIES);
                }
            }
            if (Files.exists(timeIndexPath)) {
                long size = Files.size(timeIndexPath);
                int entries = (int) (size / TIME_ENTRY_SIZE);
                if (entries > 0) {
                    try (FileChannel fc = FileChannel.open(timeIndexPath, StandardOpenOption.READ)) {
                        fc.read(timeSegment.asSlice(0, (long) entries * TIME_ENTRY_SIZE).asByteBuffer());
                    }
                    this.timeEntriesCount = Math.min(entries, MAX_ENTRIES);
                }
            }
            if (offsetEntriesCount > 0) {
                long lastPos = Integer.toUnsignedLong(offsetSegment.get(ValueLayout.JAVA_INT, (long) (offsetEntriesCount - 1) * OFFSET_ENTRY_SIZE + 4L));
                this.lastIndexedPosition = lastPos;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load sparse index from disk", e);
        }
    }

    /**
     * Appends an index entry if the physical position has advanced past
     * {@code indexIntervalBytes} since the last indexed position.
     *
     * @param offset           logical record offset
     * @param timestamp        record timestamp in milliseconds
     * @param physicalPosition physical position of the record within the segment file
     */
    public synchronized void maybeAppend(long offset, long timestamp, long physicalPosition) {
        if (closed) {
            return;
        }
        if (lastIndexedPosition == -1L || physicalPosition - lastIndexedPosition >= indexIntervalBytes) {
            appendEntry(offset, timestamp, physicalPosition);
        }
    }

    /**
     * Appends an index entry unconditionally, bypassing the interval check.
     *
     * @param offset           logical record offset
     * @param timestamp        record timestamp in milliseconds
     * @param physicalPosition physical position of the record within the segment file
     */
    public synchronized void forceAppend(long offset, long timestamp, long physicalPosition) {
        if (closed) {
            return;
        }
        appendEntry(offset, timestamp, physicalPosition);
    }

    private void appendEntry(long offset, long timestamp, long physicalPosition) {
        if (offsetEntriesCount < MAX_ENTRIES) {
            int relOffset = (int) Math.min(Integer.MAX_VALUE, Math.max(0, offset - baseOffset));
            long byteOffset = (long) offsetEntriesCount * OFFSET_ENTRY_SIZE;
            offsetSegment.set(ValueLayout.JAVA_INT, byteOffset, relOffset);
            offsetSegment.set(ValueLayout.JAVA_INT, byteOffset + 4L, (int) physicalPosition);
            offsetEntriesCount++;
        }

        if (timeEntriesCount < MAX_ENTRIES) {
            int relOffset = (int) Math.min(Integer.MAX_VALUE, Math.max(0, offset - baseOffset));
            long byteOffset = (long) timeEntriesCount * TIME_ENTRY_SIZE;
            timeSegment.set(JAVA_LONG_U, byteOffset, timestamp);
            timeSegment.set(JAVA_INT_U, byteOffset + 8L, relOffset);
            timeEntriesCount++;
        }

        lastIndexedPosition = physicalPosition;
    }

    /**
     * Finds the physical position to start scanning from for {@code targetOffset}.
     *
     * <p>Performs a zero-allocation off-heap binary search for the largest indexed entry whose
     * relative offset is not greater than {@code targetOffset - baseOffset}. Callers still
     * scan forward from the returned position to the exact record.
     *
     * @param targetOffset logical record offset to look up
     * @return the indexed physical position closest below the target, or {@code 0}
     */
    public long lookup(long targetOffset) {
        int count = offsetEntriesCount;
        if (count == 0 || targetOffset <= baseOffset) {
            return 0L;
        }
        int targetRel = (int) Math.min(Integer.MAX_VALUE, targetOffset - baseOffset);
        int low = 0;
        int high = count - 1;
        int best = 0;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            long byteOffset = (long) mid * OFFSET_ENTRY_SIZE;
            int rel = offsetSegment.get(JAVA_INT_U, byteOffset);

            if (rel <= targetRel) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        long bestByteOffset = (long) best * OFFSET_ENTRY_SIZE;
        return Integer.toUnsignedLong(offsetSegment.get(JAVA_INT_U, bestByteOffset + 4L));
    }

    /**
     * Finds the first logical offset whose indexed timestamp is not greater than
     * {@code targetTimestamp}.
     *
     * <p>Performs a zero-allocation off-heap binary search over the time index and returns the
     * absolute logical offset stored in the matching entry (or the segment base offset when
     * the index holds no entries).
     *
     * @param targetTimestamp timestamp in milliseconds to look up
     * @return the logical offset of the closest indexed record at or before the timestamp
     */
    public long lookupByTimestamp(long targetTimestamp) {
        int count = timeEntriesCount;
        if (count == 0) {
            return baseOffset;
        }
        int low = 0;
        int high = count - 1;
        int best = 0;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            long byteOffset = (long) mid * TIME_ENTRY_SIZE;
            long ts = timeSegment.get(JAVA_LONG_U, byteOffset);

            if (ts <= targetTimestamp) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        long bestByteOffset = (long) best * TIME_ENTRY_SIZE;
        int rel = timeSegment.get(JAVA_INT_U, bestByteOffset + 8L);
        return baseOffset + Integer.toUnsignedLong(rel);
    }

    /**
     * Writes the complete index to disk, replacing the existing files.
     *
     * <p>Both index files are recreated and forced to stable storage; a closed index is not
     * flushed again.
     *
     * @throws RuntimeException when the index files cannot be written
     */
    public synchronized void flush() {
        if (closed) return;
        try {
            if (offsetEntriesCount > 0) {
                try (FileChannel fc = FileChannel.open(indexPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    fc.write(offsetSegment.asSlice(0, (long) offsetEntriesCount * OFFSET_ENTRY_SIZE).asByteBuffer());
                    fc.force(true);
                }
            }
            if (timeEntriesCount > 0) {
                try (FileChannel fc = FileChannel.open(timeIndexPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    fc.write(timeSegment.asSlice(0, (long) timeEntriesCount * TIME_ENTRY_SIZE).asByteBuffer());
                    fc.force(true);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to flush index to disk", e);
        }
    }

    /**
     * Returns the logical offset of the first record of the indexed segment.
     *
     * @return the segment base offset
     */
    public long getBaseOffset() {
        return baseOffset;
    }

    /**
     * Returns the number of entries currently in the offset index.
     *
     * @return the {@code .index} entry count
     */
    public int getOffsetEntriesCount() {
        return offsetEntriesCount;
    }

    /**
     * Returns the number of entries currently in the time index.
     *
     * @return the {@code .timeindex} entry count
     */
    public int getTimeEntriesCount() {
        return timeEntriesCount;
    }

    /**
     * Returns the path of the {@code .index} file.
     *
     * @return the offset-index file path
     */
    public Path getIndexPath() {
        return indexPath;
    }

    /**
     * Returns the path of the {@code .timeindex} file.
     *
     * @return the time-index file path
     */
    public Path getTimeIndexPath() {
        return timeIndexPath;
    }

    /**
     * Flushes the index to disk and releases its off-heap memory; closing twice has no effect.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        flush();
        closed = true;
        arena.close();
    }
}
