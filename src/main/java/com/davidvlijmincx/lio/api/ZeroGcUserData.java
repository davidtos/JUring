package com.davidvlijmincx.lio.api;

/**
 * Zero-allocation user_data bit packer and unpacker.
 *
 * io_uring sqe->user_data is a 64-bit integer returned untouched in cqe->user_data.
 * We pack the OperationType (8 bits) and operation metadata/payload (56 bits) into
 * the 64-bit user_data so no off-heap struct allocation or pooling is needed.
 *
 * Bit layout:
 * - General operations:
 *   [Bits 56..63 (8 bits)]: OperationType index (0..255)
 *   [Bits  0..55 (56 bits)]: Payload (pointer address or sequence ID)
 *
 * - Fixed buffer operations (READ_FIXED, WRITE_FIXED):
 *   [Bits 56..63 (8 bits)]: OperationType index
 *   [Bits 40..55 (16 bits)]: Buffer index (0..65535)
 *   [Bits  0..39 (40 bits)]: Operation ID
 */
public final class ZeroGcUserData {

    public static final int TYPE_SHIFT = 56;
    public static final long TYPE_MASK = 0xFFL;
    public static final long PAYLOAD_MASK = 0x00FFFFFFFFFFFFFFL;

    public static final int BUFFER_INDEX_SHIFT = 40;
    public static final long BUFFER_INDEX_MASK = 0xFFFFL;
    public static final long FIXED_OP_ID_MASK = (1L << BUFFER_INDEX_SHIFT) - 1; // 40 bits

    private ZeroGcUserData() {}

    /**
     * Packs an OperationType and a 56-bit payload (such as a native memory address or op ID) into a 64-bit user_data.
     */
    public static long encode(OperationType type, long payload) {
        return (((long) type.getIndex() & TYPE_MASK) << TYPE_SHIFT) | (payload & PAYLOAD_MASK);
    }

    /**
     * Packs an OperationType, a 16-bit buffer index, and a 40-bit operation ID into a 64-bit user_data.
     */
    public static long encodeFixed(OperationType type, int bufferIndex, long opId) {
        return (((long) type.getIndex() & TYPE_MASK) << TYPE_SHIFT)
                | (((long) bufferIndex & BUFFER_INDEX_MASK) << BUFFER_INDEX_SHIFT)
                | (opId & FIXED_OP_ID_MASK);
    }

    /**
     * Extracts the OperationType from the 64-bit user_data.
     */
    public static OperationType getType(long userData) {
        int typeIndex = (int) ((userData >>> TYPE_SHIFT) & TYPE_MASK);
        return OperationType.valueOf(typeIndex);
    }

    /**
     * Extracts the 56-bit payload (e.g. pointer address or ID) from the 64-bit user_data.
     */
    public static long getPayload(long userData) {
        return userData & PAYLOAD_MASK;
    }

    /**
     * Extracts the 16-bit buffer index from fixed-buffer user_data.
     */
    public static int getBufferIndex(long userData) {
        return (int) ((userData >>> BUFFER_INDEX_SHIFT) & BUFFER_INDEX_MASK);
    }

    /**
     * Extracts the 40-bit operation ID from fixed-buffer user_data.
     */
    public static long getFixedOpId(long userData) {
        return userData & FIXED_OP_ID_MASK;
    }
}