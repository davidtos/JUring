package com.davidvlijmincx.lio.blackpanda;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single partition log segment backed by a sector-aligned {@code .log} file and
 * companion {@link UringSparseIndex} files.
 * <p>
 * Supports Linux {@code O_DIRECT} for zero-page-cache pollution.
 */
public final class UringLogSegment implements AutoCloseable {

    /** Default maximum segment size in bytes ({@code 1 GiB}). */
    public static final long DEFAULT_MAX_SEGMENT_SIZE_BYTES = 1024L * 1024L * 1024L;

    private final Path logPath;
    private final long baseOffset;
    private final long maxSizeBytes;
    private final UringSparseIndex index;

    private final AtomicLong physicalSize = new AtomicLong(0L);
    private volatile long flushedPosition = 0L;
    private volatile boolean sealed = false;
    private volatile boolean closed = false;

    private FileDescriptor writeFd;
    private FileDescriptor readFd;

    /**
     * Opens or creates the segment for {@code baseOffset} in {@code partitionDir}.
     *
     * <p>The segment file is named after the zero-padded base offset ({@code <offset>.log}),
     * and the companion sparse index files are opened alongside it. Reads and writes use the
     * {@code O_DIRECT} flags.
     *
     * @param partitionDir       directory that holds the segment files
     * @param baseOffset         logical offset of the first record in the segment
     * @param maxSizeBytes       maximum segment size; a non-positive value selects
     *                           {@link #DEFAULT_MAX_SEGMENT_SIZE_BYTES}
     * @param indexIntervalBytes sparse-index interval in bytes; a non-positive value selects
     *                           {@link UringSparseIndex#DEFAULT_INDEX_INTERVAL_BYTES}
     * @throws RuntimeException when the segment files cannot be created or opened
     */
    public UringLogSegment(Path partitionDir, long baseOffset, long maxSizeBytes, int indexIntervalBytes) {
        this.baseOffset = baseOffset;
        this.maxSizeBytes = maxSizeBytes > 0 ? maxSizeBytes : DEFAULT_MAX_SEGMENT_SIZE_BYTES;
        String prefix = String.format("%020d", baseOffset);
        this.logPath = partitionDir.resolve(prefix + ".log");

        try {
            Files.createDirectories(partitionDir);
            if (Files.exists(logPath)) {
                this.physicalSize.set(Files.size(logPath));
                this.flushedPosition = this.physicalSize.get();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize log segment file", e);
        }

        this.index = new UringSparseIndex(partitionDir, baseOffset, indexIntervalBytes);

        // Open direct write and read file descriptors
        this.writeFd = new FileDescriptor(
                logPath,
                0644,
                LinuxOpenOptions.WRITE_DIRECT,
                LinuxOpenOptions.CREATE
        );
        this.readFd = new FileDescriptor(
                logPath.toString(),
                LinuxOpenOptions.READ_DIRECT,
                0
        );
    }

    /**
     * Tests whether appending {@code additionalBytes} more bytes would exceed the maximum
     * segment size.
     *
     * @param additionalBytes number of bytes about to be appended
     * @return {@code true} when the segment should be rolled before the append
     */
    public boolean shouldRoll(long additionalBytes) {
        return physicalSize.get() + additionalBytes > maxSizeBytes;
    }

    /**
     * Appends a sparse-index entry when the index interval has advanced enough.
     *
     * @param offset       logical record offset to index
     * @param timestamp    record timestamp in milliseconds
     * @param posInSegment physical position of the record within the segment file
     */
    public void appendIndexEntry(long offset, long timestamp, long posInSegment) {
        index.maybeAppend(offset, timestamp, posInSegment);
    }

    /**
     * Appends a sparse-index entry unconditionally.
     *
     * @param offset       logical record offset to index
     * @param timestamp    record timestamp in milliseconds
     * @param posInSegment physical position of the record within the segment file
     */
    public void forceIndexEntry(long offset, long timestamp, long posInSegment) {
        index.forceAppend(offset, timestamp, posInSegment);
    }

    /**
     * Records that the segment has been durably flushed up to {@code newFlushedPos}.
     *
     * @param newFlushedPos physical position flushed so far, in bytes
     */
    public void updateFlushedPosition(long newFlushedPos) {
        this.flushedPosition = newFlushedPos;
        this.physicalSize.set(Math.max(this.physicalSize.get(), newFlushedPos));
    }

    /**
     * Seals the segment: flushes its sparse index so the segment can be reopened read-only.
     * Sealing an already-sealed segment has no effect.
     */
    public synchronized void seal() {
        if (sealed) return;
        sealed = true;
        index.flush();
    }

    /**
     * Tests whether the segment was sealed.
     *
     * @return {@code true} when the segment is no longer appended to
     */
    public boolean isSealed() {
        return sealed;
    }

    /**
     * Returns the logical offset of the first record in this segment.
     *
     * @return the segment base offset
     */
    public long getBaseOffset() {
        return baseOffset;
    }

    /**
     * Returns the current physical size of the segment file.
     *
     * @return the file size in bytes
     */
    public long getPhysicalSize() {
        return physicalSize.get();
    }

    /**
     * Returns the last position that was reported as durably flushed.
     *
     * @return the flushed position in bytes
     */
    public long getFlushedPosition() {
        return flushedPosition;
    }

    /**
     * Returns the maximum size of this segment.
     *
     * @return the size limit in bytes
     */
    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    /**
     * Returns the path of the segment's {@code .log} file.
     *
     * @return the segment file path
     */
    public Path getLogPath() {
        return logPath;
    }

    /**
     * Returns the direct-I/O file descriptor used for appends.
     *
     * @return the write file descriptor
     */
    public FileDescriptor getWriteFd() {
        return writeFd;
    }

    /**
     * Returns the direct-I/O file descriptor used for reads.
     *
     * @return the read file descriptor
     */
    public FileDescriptor getReadFd() {
        return readFd;
    }

    /**
     * Returns the sparse index of this segment.
     *
     * @return the segment's sparse index
     */
    public UringSparseIndex getIndex() {
        return index;
    }

    /**
     * Seals the segment, closes both file descriptors and closes the sparse index; closing
     * twice has no effect.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        seal();
        try {
            if (writeFd != null) {
                writeFd.close();
                writeFd = null;
            }
            if (readFd != null) {
                readFd.close();
                readFd = null;
            }
        } catch (Exception ignored) {
        }
        index.close();
    }
}
