package com.davidvlijmincx.lio.api;

/**
 * Zero-allocation {@code user_data} bit-packer and unpacker.
 * <p>
 * <b>Why this change was made (Eliminating Correlation State & GC Pressure):</b>
 * In Linux {@code io_uring}, {@code sqe->user_data} is an opaque 64-bit integer that the kernel preserves and returns
 * unmodified in {@code cqe->user_data}. Conventional Java wrappers allocate a context object (or an off-heap state
 * struct) and store its memory address or hash map key in {@code user_data}. When completions arrive at millions of ops/sec,
 * looking up or deallocating those context objects generates severe hash table contention and GC pressure.
 * <p>
 * <b>How it works:</b>
 * Instead of allocating correlation state, {@code ZeroGcUserData} bit-packs all required metadata directly into the
 * 64-bit primitive integer:
 * <ul>
 *   <li><b>Bits 56..63 (8 bits):</b> The primitive {@link OperationType#getIndex()} (0..255).
 *       Reaping code can switch directly on this byte using a single-instruction {@code tableswitch} without
 *       allocating or looking up enum instances.</li>
 *   <li><b>Bits 40..55 (16 bits, Fixed Operations):</b> The registered buffer index (0..65535).
 *       Allows instant mapping to pre-registered buffer memory addresses without secondary lookups.</li>
 *   <li><b>Bits 0..39 (40 bits, Fixed Operations):</b> Monotonically increasing operation ID sequence (up to 1 trillion unique ops).</li>
 *   <li><b>Bits 0..55 (56 bits, General Operations):</b> Raw native payload pointer (valid for canonical 48-bit / 56-bit
 *       x86_64 virtual address spaces).</li>
 * </ul>
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