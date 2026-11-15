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

    /** Batch magic ({@code 0x42504441}, ASCII {@code "BPDA"}). */
    public static final int MAGIC = 0x42504441; // "BPDA"
    /** Size in bytes of a batch header. */
    public static final int HEADER_SIZE = 32;
    /** Direct-I/O sector size in bytes; every batch occupies a multiple of it. */
    public static final int SECTOR_SIZE = 4096;

    /** Byte offset of the magic field within a batch header. */
    public static final long OFFSET_MAGIC = 0L;
    /** Byte offset of the CRC field within a batch header. */
    public static final long OFFSET_CRC = 4L;
    /** Byte offset of the base-offset field within a batch header. */
    public static final long OFFSET_BASE_OFFSET = 8L;
    /** Byte offset of the record-count field within a batch header. */
    public static final long OFFSET_RECORD_COUNT = 16L;
    /** Byte offset of the payload-length field within a batch header. */
    public static final long OFFSET_PAYLOAD_LEN = 20L;
    /** Byte offset of the timestamp field within a batch header. */
    public static final long OFFSET_TIMESTAMP = 24L;
    /** Byte offset of the payload bytes within a batch. */
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
     * Calculates the sector-aligned (4 KB multiple) batch size for a payload length.
     *
     * @param payloadLength payload length in bytes
     * @return the total batch size in bytes, rounded up to a multiple of {@link #SECTOR_SIZE}
     */
    public static int alignedBatchSize(int payloadLength) {
        int total = HEADER_SIZE + payloadLength;
        return (total + SECTOR_SIZE - 1) & ~(SECTOR_SIZE - 1);
    }

    /**
     * Writes the 32-byte batch header directly into off-heap memory at {@code offset}.
     *
     * <p>The CRC field is written as {@code 0}; callers that record a checksum overwrite it
     * afterwards. No bounds checking is performed: the segment must hold
     * {@link #HEADER_SIZE} bytes from {@code offset}.
     *
     * @param segment      segment that receives the header
     * @param offset       byte offset of the header within {@code segment}
     * @param baseOffset   logical offset of the first record in the batch
     * @param recordCount  number of records in the batch
     * @param payloadLength payload length in bytes
     * @param timestamp    batch timestamp in milliseconds
     */
    public static void writeHeader(MemorySegment segment, long offset, long baseOffset, int recordCount, int payloadLength, long timestamp) {
        segment.set(ValueLayout.JAVA_INT, offset + OFFSET_MAGIC, MAGIC);
        segment.set(ValueLayout.JAVA_LONG, offset + OFFSET_BASE_OFFSET, baseOffset);
        segment.set(ValueLayout.JAVA_INT, offset + OFFSET_RECORD_COUNT, recordCount);
        segment.set(ValueLayout.JAVA_INT, offset + OFFSET_PAYLOAD_LEN, payloadLength);
        segment.set(ValueLayout.JAVA_LONG, offset + OFFSET_TIMESTAMP, timestamp);
        segment.set(ValueLayout.JAVA_INT, offset + OFFSET_CRC, 0);
    }

    /**
     * Tests whether a batch header at {@code offset} starts with {@link #MAGIC}.
     *
     * @param segment segment that holds the header
     * @param offset  byte offset of the header within {@code segment}
     * @return {@code true} when the header magic matches
     */
    public static boolean validateMagic(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset + OFFSET_MAGIC) == MAGIC;
    }

    /**
     * Reads the CRC field of a batch header.
     *
     * @param segment segment that holds the header
     * @param offset  byte offset of the header within {@code segment}
     * @return the stored CRC value
     */
    public static int getCrc(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset + OFFSET_CRC);
    }

    /**
     * Reads the base-offset field of a batch header.
     *
     * @param segment segment that holds the header
     * @param offset  byte offset of the header within {@code segment}
     * @return the logical offset of the first record in the batch
     */
    public static long getBaseOffset(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_LONG, offset + OFFSET_BASE_OFFSET);
    }

    /**
     * Reads the record-count field of a batch header.
     *
     * @param segment segment that holds the header
     * @param offset  byte offset of the header within {@code segment}
     * @return the number of records in the batch
     */
    public static int getRecordCount(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset + OFFSET_RECORD_COUNT);
    }

    /**
     * Reads the payload-length field of a batch header.
     *
     * @param segment segment that holds the header
     * @param offset  byte offset of the header within {@code segment}
     * @return the payload length in bytes
     */
    public static int getPayloadLength(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset + OFFSET_PAYLOAD_LEN);
    }

    /**
     * Reads the timestamp field of a batch header.
     *
     * @param segment segment that holds the header
     * @param offset  byte offset of the header within {@code segment}
     * @return the batch timestamp in milliseconds
     */
    public static long getTimestamp(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_LONG, offset + OFFSET_TIMESTAMP);
    }

    /**
     * Computes a reflected CRC-32 (polynomial {@code 0xEDB88320}) over {@code length} bytes.
     *
     * @param segment segment that holds the bytes
     * @param offset  byte offset of the first byte within {@code segment}
     * @param length  number of bytes to include in the checksum
     * @return the computed CRC value
     */
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
