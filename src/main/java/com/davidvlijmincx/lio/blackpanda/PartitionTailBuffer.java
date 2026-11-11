package com.davidvlijmincx.lio.blackpanda;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free off-heap circular buffer serving real-time tailing consumers with zero disk I/O
 * and zero heap allocations.
 * <p>
 * If a consumer requests an offset that has already been evicted from the tail buffer,
 * the buffer signals lagging (returns {@code -1}), directing the consumer to {@link UringHistoricReader}.
 */
public final class PartitionTailBuffer implements AutoCloseable {

    public static final int DEFAULT_CAPACITY_SLOTS = 16_384; // 16,384 slots
    public static final int SLOT_SIZE = 4096; // 4KB per slot = 64MB off-heap ring buffer

    public static final int RESULT_LAGGING = -1;
    public static final int RESULT_NO_DATA = 0;

    private final int capacity;
    private final int mask;
    private final Arena arena;
    private final MemorySegment payloadSlab;

    private final long[] slotBaseOffset;
    private final long[] slotEndOffset;
    private final long[] slotTimestamp;
    private final int[] slotPayloadLen;
    private final long[] slotSequence;

    private final AtomicLong headSequence = new AtomicLong(0L);
    private volatile long minAvailableOffset = Long.MAX_VALUE;
    private volatile long maxAvailableOffset = -1L;
    private volatile boolean closed = false;

    public PartitionTailBuffer() {
        this(DEFAULT_CAPACITY_SLOTS);
    }

    public PartitionTailBuffer(int capacitySlots) {
        // Ensure power of 2
        int cap = 1;
        while (cap < capacitySlots) {
            cap <<= 1;
        }
        this.capacity = cap;
        this.mask = cap - 1;

        this.arena = Arena.ofShared();
        this.payloadSlab = arena.allocate((long) capacity * SLOT_SIZE, 64);

        this.slotBaseOffset = new long[capacity];
        this.slotEndOffset = new long[capacity];
        this.slotTimestamp = new long[capacity];
        this.slotPayloadLen = new int[capacity];
        this.slotSequence = new long[capacity];

        for (int i = 0; i < capacity; i++) {
            slotSequence[i] = -1L;
            slotBaseOffset[i] = -1L;
            slotEndOffset[i] = -1L;
        }
    }

    /**
     * Publishes a committed batch to the tail buffer.
     * Zero-allocation off-heap copy.
     */
    public void publish(long baseOffset, int recordCount, long timestamp, MemorySegment source, long srcOffset, int payloadLen) {
        if (closed) return;

        int idx = (int) (baseOffset & mask);

        int copyLen = Math.min(payloadLen, SLOT_SIZE);
        slotSequence[idx] = -1L;
        VarHandle.releaseFence();

        if (copyLen > 0) {
            MemorySegment.copy(source, srcOffset, payloadSlab, (long) idx * SLOT_SIZE, copyLen);
        }

        slotBaseOffset[idx] = baseOffset;
        slotEndOffset[idx] = baseOffset + recordCount - 1;
        slotTimestamp[idx] = timestamp;
        slotPayloadLen[idx] = copyLen;

        VarHandle.releaseFence();
        slotSequence[idx] = baseOffset;

        long newMax = baseOffset + recordCount - 1;
        maxAvailableOffset = newMax;

        if (minAvailableOffset == Long.MAX_VALUE) {
            minAvailableOffset = baseOffset;
        }
        if (newMax - minAvailableOffset >= capacity) {
            minAvailableOffset = newMax - capacity + 1;
        }
    }

    /**
     * Reads a record payload for {@code targetOffset} directly into {@code dstBuffer}.
     *
     * @param targetOffset logical offset to read
     * @param dstBuffer destination off-heap or native memory segment
     * @return payload length read, 0 if offset is beyond high watermark, or -1 if consumer is lagging
     */
    public int read(long targetOffset, MemorySegment dstBuffer) {
        long min = minAvailableOffset;
        if (min != Long.MAX_VALUE && targetOffset < min) {
            return RESULT_LAGGING;
        }

        long max = maxAvailableOffset;
        if (targetOffset > max || max == -1L) {
            return RESULT_NO_DATA;
        }

        int directIdx = (int) (targetOffset & mask);
        long seq = slotSequence[directIdx];
        VarHandle.acquireFence();

        if (seq >= 0 && slotBaseOffset[directIdx] <= targetOffset && targetOffset <= slotEndOffset[directIdx]) {
            int len = slotPayloadLen[directIdx];
            if (len > 0) {
                MemorySegment.copy(payloadSlab, (long) directIdx * SLOT_SIZE, dstBuffer, 0L, len);
            }
            VarHandle.acquireFence();
            if (slotSequence[directIdx] == seq) {
                return len;
            }
            return RESULT_LAGGING;
        }

        // Check if part of a multi-record batch
        for (int step = 1; step <= 32; step++) {
            int i = (int) ((targetOffset - step) & mask);
            long sSeq = slotSequence[i];
            VarHandle.acquireFence();
            if (sSeq >= 0 && slotBaseOffset[i] <= targetOffset && targetOffset <= slotEndOffset[i]) {
                int len = slotPayloadLen[i];
                if (len > 0) {
                    MemorySegment.copy(payloadSlab, (long) i * SLOT_SIZE, dstBuffer, 0L, len);
                }
                VarHandle.acquireFence();
                if (slotSequence[i] == sSeq) {
                    return len;
                }
                return RESULT_LAGGING;
            }
        }

        if (targetOffset < minAvailableOffset) {
            return RESULT_LAGGING;
        }
        return RESULT_NO_DATA;
    }

    public long getMinAvailableOffset() {
        return minAvailableOffset;
    }

    public long getMaxAvailableOffset() {
        return maxAvailableOffset;
    }

    public int getCapacity() {
        return capacity;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        arena.close();
    }
}
