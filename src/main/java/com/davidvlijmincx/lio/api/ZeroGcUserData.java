package com.davidvlijmincx.lio.api;

/**
 * Zero-allocation {@code user_data} bit-packer and unpacker.
 */
public final class ZeroGcUserData {

    public static final int TYPE_SHIFT = 56;
    public static final long TYPE_MASK = 0xFFL;
    public static final long PAYLOAD_MASK = 0x00FFFFFFFFFFFFFFL;

    public static final int BUFFER_INDEX_SHIFT = 40;
    public static final long BUFFER_INDEX_MASK = 0xFFFFL;
    public static final long FIXED_OP_ID_MASK = (1L << BUFFER_INDEX_SHIFT) - 1;

    static final int TYPE_WRITE_EXT = 20;

    private ZeroGcUserData() {}

    /**
     * Packs an OperationType and a 56-bit payload (such as a native memory address or op ID) into a 64-bit user_data.
     */
    public static long encode(OperationType type, long payload) {
        return (((long) type.getIndex() & TYPE_MASK) << TYPE_SHIFT) | (payload & PAYLOAD_MASK);
    }

    static long encodeWriteExt(long opId) {
        return (((long) TYPE_WRITE_EXT & TYPE_MASK) << TYPE_SHIFT) | (opId & PAYLOAD_MASK);
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
        if (typeIndex == TYPE_WRITE_EXT) {
            return OperationType.WRITE;
        }
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