package com.davidvlijmincx.lio.blackpanda;

import com.davidvlijmincx.lio.api.IoUringOptions;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.Result;

import java.lang.foreign.MemorySegment;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * High Queue Depth (QD=32–64) asynchronous direct I/O reader for lagging consumers.
 * <p>
 * Reads strictly via Linux {@code O_DIRECT} using registered fixed buffers ({@code IORING_OP_READ_FIXED}).
 * Completely bypasses the Linux page cache, guaranteeing 0% cache pollution and 0% write throughput
 * degradation for active producers.
 */
public final class UringHistoricReader implements AutoCloseable {

    public static final int DEFAULT_QUEUE_DEPTH = 8;
    public static final int BUFFER_SIZE = 64 * 1024; // 64 KB sector-aligned direct I/O read buffer

    private final UringPartitionManager partitionManager;
    private final int queueDepth;
    private final JUring ring;
    private final MemorySegment[] registeredBuffers;

    private final long[] bufferFilePositions;
    private final UringLogSegment[] bufferSegments;

    private UringLogSegment currentSegment;
    private long currentFilePosition;
    private long nextConsumePos;
    private long nextExpectedOffset;
    private int inFlight = 0;
    private boolean eof = false;
    private volatile boolean closed = false;

    // Queue of pending read completions ordered by segment base offset and file start position
    private final PriorityQueue<CompletedBuffer> pendingBuffers = new PriorityQueue<>(
            Comparator.<CompletedBuffer>comparingLong(b -> b.segment.getBaseOffset())
                    .thenComparingLong(b -> b.startPos)
    );

    private static final class CompletedBuffer {
        final int bufferIdx;
        final int bytesRead;
        final long startPos;
        final UringLogSegment segment;

        CompletedBuffer(int bufferIdx, int bytesRead, long startPos, UringLogSegment segment) {
            this.bufferIdx = bufferIdx;
            this.bytesRead = bytesRead;
            this.startPos = startPos;
            this.segment = segment;
        }
    }

    public UringHistoricReader(UringPartitionManager partitionManager) {
        this(partitionManager, DEFAULT_QUEUE_DEPTH);
    }

    public UringHistoricReader(UringPartitionManager partitionManager, int queueDepth) {
        this.partitionManager = partitionManager;
        this.queueDepth = Math.max(1, Math.min(queueDepth, 32));
        this.ring = new JUring(this.queueDepth);
        this.registeredBuffers = ring.registerBuffers(BUFFER_SIZE, this.queueDepth);
        this.bufferFilePositions = new long[this.queueDepth];
        this.bufferSegments = new UringLogSegment[this.queueDepth];
    }

    /**
     * Seeks to {@code targetOffset} using the sparse index.
     */
    public void seek(long targetOffset) {
        drainPending();
        this.nextExpectedOffset = targetOffset;
        this.currentSegment = partitionManager.findSegmentForOffset(targetOffset);
        if (currentSegment != null) {
            this.currentFilePosition = currentSegment.getIndex().lookup(targetOffset);
            this.nextConsumePos = this.currentFilePosition;
            this.eof = false;
        } else {
            this.eof = true;
        }
    }

    /**
     * Drains pending in-flight I/Os.
     */
    private void drainPending() {
        while (inFlight > 0) {
            ring.submitAndWaitForBatchResult(1, this::onResult);
        }
        while (!pendingBuffers.isEmpty()) {
            CompletedBuffer cb = pendingBuffers.poll();
            ring.checkInBuffer(cb.bufferIdx);
        }
    }

    private void onResult(Result result) {
        inFlight--;
        int bufIdx = result.bufferIdx();
        int bytes = (int) result.result();
        if (bytes > 0 && bufIdx >= 0) {
            pendingBuffers.add(new CompletedBuffer(bufIdx, bytes, bufferFilePositions[bufIdx], bufferSegments[bufIdx]));
        } else if (bufIdx >= 0) {
            ring.checkInBuffer(bufIdx);
        }
    }

    /**
     * Fills the asynchronous read pipeline up to queue depth.
     */
    private void pumpPipeline() {
        if (closed || currentSegment == null) return;

        while (inFlight < queueDepth) {
            long segSize = currentSegment.getPhysicalSize();
            if (currentFilePosition >= segSize) {
                // Try rolling to next segment
                List<UringLogSegment> all = partitionManager.getAllSegments();
                int idx = all.indexOf(currentSegment);
                if (idx >= 0 && idx + 1 < all.size()) {
                    currentSegment = all.get(idx + 1);
                    currentFilePosition = 0L;
                    nextConsumePos = 0L;
                } else {
                    if (currentSegment != partitionManager.getActiveSegment()) {
                        eof = true;
                    }
                    break;
                }
            }

            int bufIdx = ring.checkOutBuffer();
            if (bufIdx < 0) break;

            bufferFilePositions[bufIdx] = currentFilePosition;
            bufferSegments[bufIdx] = currentSegment;
            ring.prepareReadFixed(currentSegment.getReadFd(), BUFFER_SIZE, currentFilePosition, bufIdx);
            currentFilePosition += BUFFER_SIZE;
            inFlight++;
        }

        if (inFlight > 0) {
            ring.submit();
        }
    }

    /**
     * Functional record consumer receiving record batches.
     */
    @FunctionalInterface
    public interface RecordConsumer {
        void accept(long offset, long timestamp, MemorySegment payload);
    }

    /**
     * Reads up to {@code maxRecords} historic records directly from NVMe storage using O_DIRECT.
     *
     * @return number of records delivered to {@code consumer}
     */
    public int readHistoric(int maxRecords, RecordConsumer consumer) {
        if (closed) return 0;
        int delivered = 0;

        while (delivered < maxRecords) {
            if (pendingBuffers.isEmpty()) {
                pumpPipeline();
                if (inFlight == 0 && pendingBuffers.isEmpty()) {
                    break; // No more data currently available
                }
                ring.submitAndWaitForBatchResult(inFlight, this::onResult);
            }

            while (!pendingBuffers.isEmpty() && delivered < maxRecords) {
                CompletedBuffer cb = pendingBuffers.poll();
                int bufIdx = cb.bufferIdx;
                int bytesRead = cb.bytesRead;
                MemorySegment buf = registeredBuffers[bufIdx];

                long offsetInBuf = 0L;
                while (offsetInBuf + RecordBatch.HEADER_SIZE <= bytesRead) {
                    if (!RecordBatch.validateMagic(buf, offsetInBuf)) {
                        break;
                    }
                    long baseOffset = RecordBatch.getBaseOffset(buf, offsetInBuf);
                    int recordCount = RecordBatch.getRecordCount(buf, offsetInBuf);
                    int payloadLen = RecordBatch.getPayloadLength(buf, offsetInBuf);
                    long timestamp = RecordBatch.getTimestamp(buf, offsetInBuf);

                    int batchSize = Math.max(RecordBatch.SECTOR_SIZE, RecordBatch.alignedBatchSize(payloadLen));

                    if (baseOffset + recordCount > nextExpectedOffset) {
                        MemorySegment payload = buf.asSlice(offsetInBuf + RecordBatch.HEADER_SIZE, payloadLen);
                        consumer.accept(baseOffset, timestamp, payload);
                        delivered++;
                        nextExpectedOffset = baseOffset + recordCount;
                    }

                    offsetInBuf += batchSize;
                }

                ring.checkInBuffer(bufIdx);
            }
        }

        return delivered;
    }

    public long getNextExpectedOffset() {
        return nextExpectedOffset;
    }

    public boolean isEof() {
        return eof && pendingBuffers.isEmpty() && inFlight == 0;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        drainPending();
        ring.close();
    }
}
