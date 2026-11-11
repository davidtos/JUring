package com.davidvlijmincx.lio.blackpanda;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Framing, header layout, and serialization for BlackPanda record batches.
 * <p>
 * Batches are sector-aligned (multiples of 4096 bytes) for zero-copy Linux {@code O_DIRECT} I/O.
 * Header size is 32 bytes:
 * - offset 0:  magic (int, 4 bytes) - 0x42504441 ("BPDA")
 * - offset 4:  crc32 (int, 4 bytes)
 * - offset 8:  baseOffset (long, 8 bytes)
 * - offset 16: recordCount (int, 4 bytes)
 * - offset 20: payloadLength (int, 4 bytes)
 * - offset 24: timestamp (long, 8 bytes)
 * - offset 32: payload data
 */
public final class RecordBatch {

    public static final int MAGIC = 0x42504441; // "BPDA"
    public static final int HEADER_SIZE = 32;
    public static final int SECTOR_SIZE = 4096;

    public static final long OFFSET_MAGIC = 0L;
    public static final long OFFSET_CRC = 4L;
    public static final long OFFSET_BASE_OFFSET = 8L;
    public static final long OFFSET_RECORD_COUNT = 16L;
    public static final long OFFSET_PAYLOAD_LEN = 20L;
    public static final long OFFSET_TIMESTAMP = 24L;
    public static final long OFFSET_PAYLOAD = 32L;

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                crc = ((crc & 1) != 0) ? (crc >>> 1) ^ 0xEDB88320 : (crc >>> 1);
            }
            CRC_TABLE[i] = crc;
        }
    }

    private RecordBatch() {}

    /**
     * Calculates the sector-aligned (4KB multiple) batch size for a given payload length.
     */
    public static int alignedBatchSize(int payloadLength) {
        int total = HEADER_SIZE + payloadLength;
        return (total + SECTOR_SIZE - 1) & ~(SECTOR_SIZE - 1);
    }

    /**
     * Writes the 32-byte batch header directly into off-heap memory at {@code offset}.
     */
    public static void writeHeader(MemorySegment segment, long offset, long baseOffset, int recordCount, int payloadLength, long timestamp) {
        segment.set(ValueLayout.JAVA_INT, offset + OFFSET_MAGIC, MAGIC);
        segment.set(ValueLayout.JAVA_LONG, offset + OFFSET_BASE_OFFSET, baseOffset);
        segment.set(ValueLayout.JAVA_INT, offset + OFFSET_RECORD_COUNT, recordCount);
        segment.set(ValueLayout.JAVA_INT, offset + OFFSET_PAYLOAD_LEN, payloadLength);
        segment.set(ValueLayout.JAVA_LONG, offset + OFFSET_TIMESTAMP, timestamp);
        segment.set(ValueLayout.JAVA_INT, offset + OFFSET_CRC, 0);
    }

    public static boolean validateMagic(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset + OFFSET_MAGIC) == MAGIC;
    }

    public static int getCrc(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset + OFFSET_CRC);
    }

    public static long getBaseOffset(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_LONG, offset + OFFSET_BASE_OFFSET);
    }

    public static int getRecordCount(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset + OFFSET_RECORD_COUNT);
    }

    public static int getPayloadLength(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset + OFFSET_PAYLOAD_LEN);
    }

    public static long getTimestamp(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_LONG, offset + OFFSET_TIMESTAMP);
    }

    public static int computeCrc(MemorySegment segment, long offset, int length) {
        int crc = 0xFFFFFFFF;
        int i = 0;
        while (i + 8 <= length) {
            long val = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + i);
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (int) (val & 0xFF)) & 0xFF];
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (int) ((val >>> 8) & 0xFF)) & 0xFF];
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (int) ((val >>> 16) & 0xFF)) & 0xFF];
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (int) ((val >>> 24) & 0xFF)) & 0xFF];
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (int) ((val >>> 32) & 0xFF)) & 0xFF];
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (int) ((val >>> 40) & 0xFF)) & 0xFF];
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (int) ((val >>> 48) & 0xFF)) & 0xFF];
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (int) ((val >>> 56) & 0xFF)) & 0xFF];
            i += 8;
        }
        while (i < length) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, offset + i);
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ b) & 0xFF];
            i++;
        }
        return ~crc;
    }
}
