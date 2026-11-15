package com.davidvlijmincx.lio.blackpanda;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Manages partition segment files, segment rolling, and segment offset lookups.
 * Maintains the currently active (writable) segment alongside all read-only closed segments.
 */
public final class UringPartitionManager implements AutoCloseable {

    private final Path partitionDir;
    private final long maxSegmentSizeBytes;
    private final int indexIntervalBytes;

    private final ConcurrentNavigableMap<Long, UringLogSegment> closedSegments = new ConcurrentSkipListMap<>();
    private volatile UringLogSegment activeSegment;
    private volatile boolean closed = false;

    /**
     * Opens (or creates) the partition directory and discovers its segment files.
     *
     * <p>Existing segments other than the newest are opened and sealed as read-only closed
     * segments; the newest becomes the active segment. An empty directory gets a new active
     * segment starting at offset {@code 0}.
     *
     * @param partitionDir       directory that holds the partition's segment files
     * @param maxSegmentSizeBytes maximum size of a segment in bytes
     * @param indexIntervalBytes sparse-index interval in bytes
     * @throws RuntimeException when the directory cannot be created or read
     */
    public UringPartitionManager(Path partitionDir, long maxSegmentSizeBytes, int indexIntervalBytes) {
        this.partitionDir = partitionDir;
        this.maxSegmentSizeBytes = maxSegmentSizeBytes;
        this.indexIntervalBytes = indexIntervalBytes;
        init();
    }

    private void init() {
        try {
            Files.createDirectories(partitionDir);
            List<Long> baseOffsets = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(partitionDir, "*.log")) {
                for (Path p : stream) {
                    String fileName = p.getFileName().toString();
                    String prefix = fileName.substring(0, fileName.lastIndexOf('.'));
                    try {
                        long baseOffset = Long.parseLong(prefix);
                        baseOffsets.add(baseOffset);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            Collections.sort(baseOffsets);

            if (baseOffsets.isEmpty()) {
                this.activeSegment = new UringLogSegment(partitionDir, 0L, maxSegmentSizeBytes, indexIntervalBytes);
            } else {
                for (int i = 0; i < baseOffsets.size() - 1; i++) {
                    long b = baseOffsets.get(i);
                    UringLogSegment seg = new UringLogSegment(partitionDir, b, maxSegmentSizeBytes, indexIntervalBytes);
                    seg.seal();
                    closedSegments.put(b, seg);
                }
                long lastBase = baseOffsets.get(baseOffsets.size() - 1);
                this.activeSegment = new UringLogSegment(partitionDir, lastBase, maxSegmentSizeBytes, indexIntervalBytes);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize partition directory: " + partitionDir, e);
        }
    }

    /**
     * Rolls the active segment and creates a new active segment starting at
     * {@code nextBaseOffset}.
     *
     * <p>The old active segment is sealed and moved to the closed segments. The caller must
     * pass the logical offset that the next appended record will have.
     *
     * @param nextBaseOffset logical offset of the first record in the new segment
     * @return the new active segment
     * @throws IllegalStateException when the manager is closed
     */
    public synchronized UringLogSegment roll(long nextBaseOffset) {
        if (closed) {
            throw new IllegalStateException("Partition manager is closed");
        }
        UringLogSegment oldActive = this.activeSegment;
        oldActive.seal();
        closedSegments.put(oldActive.getBaseOffset(), oldActive);

        UringLogSegment newActive = new UringLogSegment(partitionDir, nextBaseOffset, maxSegmentSizeBytes, indexIntervalBytes);
        this.activeSegment = newActive;
        return newActive;
    }

    /**
     * Finds the log segment containing {@code offset}.
     *
     * @param offset logical record offset
     * @return the active segment for the newest offsets, otherwise the newest closed segment
     *         whose base offset is not greater than the requested offset
     */
    public UringLogSegment findSegmentForOffset(long offset) {
        UringLogSegment curActive = this.activeSegment;
        if (curActive != null && offset >= curActive.getBaseOffset()) {
            return curActive;
        }
        var entry = closedSegments.floorEntry(offset);
        if (entry != null) {
            return entry.getValue();
        }
        return curActive != null ? curActive : closedSegments.firstEntry().getValue();
    }

    /**
     * Finds the log segment for a timestamp by consulting the sparse time indexes of the
     * closed segments.
     *
     * @param timestamp timestamp in milliseconds
     * @return the first closed segment whose time index reaches the timestamp, otherwise the
     *         active segment
     */
    public UringLogSegment findSegmentForTimestamp(long timestamp) {
        for (UringLogSegment seg : closedSegments.values()) {
            long off = seg.getIndex().lookupByTimestamp(timestamp);
            if (off >= seg.getBaseOffset()) {
                return seg;
            }
        }
        return activeSegment;
    }

    /**
     * Returns the segment that currently receives appends.
     *
     * @return the active segment, or {@code null} after close
     */
    public UringLogSegment getActiveSegment() {
        return activeSegment;
    }

    /**
     * Returns all segments in base-offset order, with the active segment last.
     *
     * @return an immutable list of the segments
     */
    public List<UringLogSegment> getAllSegments() {
        List<UringLogSegment> all = new ArrayList<>(closedSegments.values());
        UringLogSegment act = activeSegment;
        if (act != null && !all.contains(act)) {
            all.add(act);
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Returns the directory that holds the partition's segment files.
     *
     * @return the partition directory
     */
    public Path getPartitionDir() {
        return partitionDir;
    }

    /**
     * Closes every closed segment and the active segment; closing twice has no effect.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (UringLogSegment seg : closedSegments.values()) {
            seg.close();
        }
        closedSegments.clear();
        if (activeSegment != null) {
            activeSegment.close();
            activeSegment = null;
        }
    }
}
