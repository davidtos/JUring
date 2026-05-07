package com.davidvlijmincx.lio.api;

final class ReadBufferPool {

    private final long[] slots;
    private final int bufferSize;
    private int top;

    ReadBufferPool(int capacity, int bufferSize) {
        this.bufferSize = bufferSize;
        slots = new long[capacity];
        top = 0;
        for (int i = 0; i < capacity; i++) {
            slots[top++] = NativeDispatcher.C.mallocAddress(bufferSize);
        }
    }

    long checkOut() {
        if (top == 0) {
            return NativeDispatcher.C.mallocAddress(bufferSize);
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
