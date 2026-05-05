package com.davidvlijmincx.lio.api;

final class IovecBlockPool {

    /** Maximum number of iovec entries per pooled slot. */
    public static final int MAX_VECS = 64;

    /**
     * Byte size of one pooled slot:
     *   8 bytes for the leading count (long) + MAX_VECS * 16 bytes per iovec struct.
     * iovec on Linux x86-64: { void *iov_base (8 bytes), size_t iov_len (8 bytes) } = 16 bytes.
     */
    public static final long SLOT_BYTES = 8L + (long) MAX_VECS * 16L; // 1032

    private final long[] slots;
    private int top;

    IovecBlockPool(int capacity) {
        slots = new long[capacity];
        top = 0;
        for (int i = 0; i < capacity; i++) {
            slots[top++] = NativeDispatcher.C.mallocAddress(SLOT_BYTES);
        }
    }

    long checkOut() {
        if (top == 0) {
            return NativeDispatcher.C.mallocAddress(SLOT_BYTES);
        }
        return slots[--top];
    }

    void checkIn(long address) {
        if (top < slots.length) {
            slots[top++] = address;
        } else {
            NativeDispatcher.C.free(address);
        }
    }

    void close() {
        while (top > 0) {
            NativeDispatcher.C.free(slots[--top]);
        }
    }
}
