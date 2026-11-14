package com.davidvlijmincx.lio.api;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Bounded lock-free multi-producer / single-consumer queue (Vyukov-style sequence ring)
 * used for one shard's inbound tasks and handoff continuations.
 *
 * <p>{@link #offer(Object)} is lock-free and safe from any number of producers; it returns
 * {@code false} when the ring is full and never blocks. {@link #poll()} and
 * {@link #isEmpty()} are consumer-only (the owning shard thread) and never block. The
 * implementation allocates nothing on the hot path: slots are preallocated object
 * references and the sequence ring is a plain {@code long[]}.
 */
final class MpscQueue {

    private static final VarHandle VH_TAIL;
    private static final VarHandle VH_SEQ;
    private static final VarHandle VH_BUFFER;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            VH_TAIL = l.findVarHandle(MpscQueue.class, "tail", long.class);
            VH_SEQ = MethodHandles.arrayElementVarHandle(long[].class);
            VH_BUFFER = MethodHandles.arrayElementVarHandle(Object[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Object[] buffer;
    private final long[] sequence;
    private final int capacity;
    private final int mask;

    /** Producer position; advanced with a CAS so concurrent producers never overlap. */
    @SuppressWarnings("unused")
    private volatile long tail;
    /** Consumer position; only the shard thread reads/writes it. */
    private long head;

    MpscQueue(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("MPSC capacity must be a power of two >= 2: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.buffer = new Object[capacity];
        this.sequence = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            this.sequence[i] = i;
        }
    }

    int capacity() {
        return capacity;
    }

    /**
     * Enqueues {@code element} if a slot is available.
     *
     * @return {@code true} when enqueued, {@code false} when the queue is full (never blocks)
     */
    boolean offer(Object element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        long pos = (long) VH_TAIL.getVolatile(this);
        while (true) {
            int index = (int) (pos & mask);
            long seq = (long) VH_SEQ.getAcquire(sequence, index);
            if (seq == pos) {
                if (VH_TAIL.compareAndSet(this, pos, pos + 1L)) {
                    // Publish the element before making the slot visible to the consumer.
                    VH_BUFFER.setRelease(buffer, index, element);
                    VH_SEQ.setRelease(sequence, index, pos + 1L);
                    return true;
                }
                pos = (long) VH_TAIL.getVolatile(this);
            } else if (seq < pos) {
                // The slot still holds the element for position pos - capacity: the ring is full.
                return false;
            } else {
                // Another producer reserved this position; retry at the new tail.
                pos = (long) VH_TAIL.getVolatile(this);
            }
        }
    }

    /**
     * Dequeues the oldest element (consumer thread only).
     *
     * @return the element, or {@code null} when the queue is empty
     */
    Object poll() {
        long pos = head;
        int index = (int) (pos & mask);
        long seq = (long) VH_SEQ.getAcquire(sequence, index);
        if (seq != pos + 1L) {
            return null;
        }
        Object element = (Object) VH_BUFFER.getAcquire(buffer, index);
        VH_BUFFER.setRelease(buffer, index, null);
        VH_SEQ.setRelease(sequence, index, pos + capacity);
        head = pos + 1L;
        return element;
    }

    /** Consumer-side empty check; does not block. */
    boolean isEmpty() {
        long pos = head;
        int index = (int) (pos & mask);
        long seq = (long) VH_SEQ.getAcquire(sequence, index);
        return seq != pos + 1L;
    }
}
