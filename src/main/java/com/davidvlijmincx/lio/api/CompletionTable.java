package com.davidvlijmincx.lio.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Shard-thread-local open-addressing {@code long -> CompletionHandler} map.
 *
 * <p>Backed by two flat arrays (no boxing, no per-operation allocation). Insertion uses
 * linear probing; removal uses <b>backward-shift deletion</b> (Knuth TAOCP vol. 3, Algorithm
 * R): after clearing a slot, later entries of the probe cluster are shifted back into the
 * hole while their probe path from their home slot still passes through it. There are no
 * tombstones and no re-insertion, so the table never degrades and deletion is always
 * bounded; every probe loop is hard-bounded by the table capacity and terminates.
 *
 * <p>Capacity is a power of two and fixed at construction (the shard sizes it as
 * {@code nextPow2(max(1024, ringEntries * 2))}). {@link #put} throws
 * {@link IllegalStateException} at capacity; {@link #remove} returns {@code null} in bounded
 * time for a missing key, including when the table is full.
 *
 * <p>Not thread-safe by design: used only from the owning shard thread.
 */
final class CompletionTable {

    private static final long EMPTY = 0L;

    private final long[] keys;
    private final CompletionHandler[] values;
    private final int mask;
    private int size;

    CompletionTable(int requestedCapacity) {
        int capacity = nextPowerOfTwo(Math.max(8L, requestedCapacity));
        this.keys = new long[capacity];
        this.values = new CompletionHandler[capacity];
        this.mask = capacity - 1;
    }

    private static int nextPowerOfTwo(long value) {
        if (value >= (1L << 30)) {
            return 1 << 30;
        }
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    int capacity() {
        return keys.length;
    }

    int size() {
        return size;
    }

    boolean isFull() {
        return size >= keys.length;
    }

    /**
     * Registers {@code handler} for {@code key}.
     *
     * @throws IllegalStateException when the key is already registered or the table is full
     */
    void put(long key, CompletionHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (key == EMPTY) {
            throw new IllegalArgumentException("user_data 0 cannot be registered (reserved as the empty-slot marker)");
        }
        int capacity = keys.length;
        if (size >= capacity) {
            throw new IllegalStateException("Completion handler table is full (capacity " + capacity + ")");
        }
        int index = hash(key) & mask;
        for (int probe = 0; probe < capacity; probe++) {
            long existing = keys[index];
            if (existing == EMPTY) {
                keys[index] = key;
                values[index] = handler;
                size++;
                return;
            }
            if (existing == key) {
                throw new IllegalStateException("A completion handler is already registered for user_data 0x"
                        + Long.toHexString(key));
            }
            index = (index + 1) & mask;
        }
        // Unreachable when size < capacity (there is always at least one EMPTY slot);
        // kept as a defensive hard stop so a corrupted table can never spin.
        throw new IllegalStateException("Completion handler table is full (capacity " + capacity + ")");
    }

    /**
     * Removes and returns the handler registered for {@code key}, or {@code null} when none
     * is registered (bounded probe; terminates even on a full table).
     */
    CompletionHandler remove(long key) {
        int capacity = keys.length;
        int index = hash(key) & mask;
        boolean found = false;
        for (int probe = 0; probe < capacity; probe++) {
            long existing = keys[index];
            if (existing == EMPTY) {
                return null;
            }
            if (existing == key) {
                found = true;
                break;
            }
            index = (index + 1) & mask;
        }
        if (!found) {
            // A full table has no EMPTY stopping slot; the bounded probe above visited every
            // slot, so the key is genuinely absent.
            return null;
        }
        CompletionHandler handler = values[index];
        keys[index] = EMPTY;
        values[index] = null;
        size--;

        // Backward-shift deletion: walk forward from the hole and move the next cluster
        // entry back when the hole lies on that entry's probe path from its home slot,
        // i.e. distance(home -> hole) < distance(home -> next), computed cyclically.
        int hole = index;
        int next = index;
        for (int probe = 0; probe < capacity; probe++) {
            next = (next + 1) & mask;
            long nextKey = keys[next];
            if (nextKey == EMPTY) {
                break;
            }
            int home = hash(nextKey) & mask;
            int distanceToHole = (hole - home) & mask;
            int distanceToNext = (next - home) & mask;
            if (distanceToHole < distanceToNext) {
                keys[hole] = nextKey;
                values[hole] = values[next];
                keys[next] = EMPTY;
                values[next] = null;
                hole = next;
            }
        }
        return handler;
    }

    /** Clears the table (used only on teardown to drop un-dispatched handlers). */
    void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(keys, EMPTY);
        Arrays.fill(values, null);
        size = 0;
    }

    private static int hash(long key) {
        long z = key + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= (z >>> 31);
        return (int) (z ^ (z >>> 32));
    }
}
