package com.davidvlijmincx.lio.api;

final class UserDataPool {

    private final long[] slots;
    private int top;

    UserDataPool(int capacity) {
        slots = new long[capacity];
        top = 0;
        for (int i = 0; i < capacity; i++) {
            slots[top++] = NativeDispatcher.C.mallocAddress(ZeroGcUserData.getByteSize());
        }
    }

    long checkOut() {
        if (top == 0) {
            return NativeDispatcher.C.mallocAddress(ZeroGcUserData.getByteSize());
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
