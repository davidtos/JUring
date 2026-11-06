package com.davidvlijmincx.lio.api;

/**
 * Zero-allocation {@code user_data} bit-packer and unpacker.
 */
final class ZeroGcUserData {

    public static final int TYPE_SHIFT = 56;
    public static final long TYPE_MASK = 0xFFL;
    public static final long PAYLOAD_MASK = 0x00FFFFFFFFFFFFFFL;

    public static final int BUFFER_INDEX_SHIFT = 40;
    public static final long BUFFER_INDEX_MASK = 0xFFFFL;
    public static final long FIXED_OP_ID_MASK = (1L << BUFFER_INDEX_SHIFT) - 1;

    private ZeroGcUserData() {}

    public static long encode(OperationType type, long payload) {
        return (((long) type.getIndex() & TYPE_MASK) << TYPE_SHIFT) | (payload & PAYLOAD_MASK);
    }

    public static long encodeFixed(OperationType type, int bufferIndex, long opId) {
        return (((long) type.getIndex() & TYPE_MASK) << TYPE_SHIFT)
                | (((long) bufferIndex & BUFFER_INDEX_MASK) << BUFFER_INDEX_SHIFT)
                | (opId & FIXED_OP_ID_MASK);
    }

    public static OperationType getType(long userData) {
        int typeIndex = (int) ((userData >>> TYPE_SHIFT) & TYPE_MASK);
        return OperationType.valueOf(typeIndex);
    }

    public static long getPayload(long userData) {
        return userData & PAYLOAD_MASK;
    }

    public static int getBufferIndex(long userData) {
        return (int) ((userData >>> BUFFER_INDEX_SHIFT) & BUFFER_INDEX_MASK);
    }

    public static long getFixedOpId(long userData) {
        return userData & FIXED_OP_ID_MASK;
    }
}