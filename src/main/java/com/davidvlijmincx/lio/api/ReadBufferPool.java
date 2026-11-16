package com.davidvlijmincx.lio.api;

/**
 * Fixed-capacity pool of off-heap read buffers used by {@code JUring.prepareReadPooled}.
 *
 * <p>Ownership is tracked explicitly: a slot is either free (on the free stack) or checked out
 * (owned by the caller of {@link #checkOut()}). {@link #checkIn(long)} rejects addresses that
 * do not belong to this pool and double check-ins, so a misused completion can never cause a
 * double free or hand the same native buffer to two readers. The pool never grows silently;
 * an exhausted pool throws instead of allocating outside its configured capacity.
 */
final class ReadBufferPool {

    private final long[] slots;
    private final int[] freeStack;
    private final boolean[] inUse;
    private final int bufferSize;
    private int freeTop;
    private boolean closed;

    ReadBufferPool(int capacity, int bufferSize) {
        this.bufferSize = bufferSize;
        slots = new long[capacity];
        freeStack = new int[capacity];
        inUse = new boolean[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = NativeDispatcher.C.mallocAddress(bufferSize);
            freeStack[i] = i;
        }
        freeTop = capacity;
    }

    int bufferSize() {
        return bufferSize;
    }

    /**
     * Checks out a free buffer.
     *
     * @return the native address of the checked-out buffer
     * @throws IllegalStateException when the pool is closed or every buffer is in use
     */
    long checkOut() {
        if (closed) {
            throw new IllegalStateException("Read buffer pool is closed");
        }
        if (freeTop == 0) {
            throw new IllegalStateException("Read buffer pool exhausted (capacity " + slots.length + ")");
        }
        int index = freeStack[--freeTop];
        if (inUse[index]) {
            throw new IllegalStateException("Read buffer pool invariant violated: slot " + index + " already in use");
        }
        inUse[index] = true;
        return slots[index];
    }

    /**
     * Returns a buffer to the pool.
     *
     * @param address native address previously returned by {@link #checkOut()}
     * @throws IllegalArgumentException when the address does not belong to this pool
     * @throws IllegalStateException    when the address was already checked in
     */
    void checkIn(long address) {
        int index = indexOf(address);
        if (index < 0) {
            throw new IllegalArgumentException("Address does not belong to this read buffer pool");
        }
        if (!inUse[index]) {
            throw new IllegalStateException("Read buffer at slot " + index + " is already checked in (double check-in)");
        }
        inUse[index] = false;
        freeStack[freeTop++] = index;
    }

    private int indexOf(long address) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == address) {
                return i;
            }
        }
        return -1;
    }

    /** Number of buffers currently checked out by callers (for diagnostics/tests). */
    int outstanding() {
        int outstanding = 0;
        for (boolean used : inUse) {
            if (used) {
                outstanding++;
            }
        }
        return outstanding;
    }

    /**
     * Frees every buffer that is currently idle. Checked-out buffers are owned by their callers
     * and are not freed here; they must be checked in (or are leaked when the ring is closed
     * with operations still outstanding).
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int i = 0; i < slots.length; i++) {
            if (!inUse[i]) {
                NativeDispatcher.C.free(slots[i]);
            }
        }
    }
}
