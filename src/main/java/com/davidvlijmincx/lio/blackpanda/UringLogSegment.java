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

    public static final long DEFAULT_MAX_SEGMENT_SIZE_BYTES = 1024L * 1024L * 1024L; // 1 GB

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

    public boolean shouldRoll(long additionalBytes) {
        return physicalSize.get() + additionalBytes > maxSizeBytes;
    }

    public void appendIndexEntry(long offset, long timestamp, long posInSegment) {
        index.maybeAppend(offset, timestamp, posInSegment);
    }

    public void forceIndexEntry(long offset, long timestamp, long posInSegment) {
        index.forceAppend(offset, timestamp, posInSegment);
    }

    public void updateFlushedPosition(long newFlushedPos) {
        this.flushedPosition = newFlushedPos;
        this.physicalSize.set(Math.max(this.physicalSize.get(), newFlushedPos));
    }

    public synchronized void seal() {
        if (sealed) return;
        sealed = true;
        index.flush();
    }

    public boolean isSealed() {
        return sealed;
    }

    public long getBaseOffset() {
        return baseOffset;
    }

    public long getPhysicalSize() {
        return physicalSize.get();
    }

    public long getFlushedPosition() {
        return flushedPosition;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public Path getLogPath() {
        return logPath;
    }

    public FileDescriptor getWriteFd() {
        return writeFd;
    }

    public FileDescriptor getReadFd() {
        return readFd;
    }

    public UringSparseIndex getIndex() {
        return index;
    }

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
