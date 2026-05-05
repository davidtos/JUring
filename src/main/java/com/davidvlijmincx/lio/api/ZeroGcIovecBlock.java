package com.davidvlijmincx.lio.api;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Zero-allocation accessor for the iovec block pool slots.
 *
 * Block memory layout (matches what IovecBlockPool allocates):
 *   [long count (8 bytes)] [iov_base_0 (8B)] [iov_len_0 (8B)] [iov_base_1 (8B)] [iov_len_1 (8B)] ...
 *
 * struct iovec on Linux x86-64: { void *iov_base; size_t iov_len; } = 16 bytes each.
 *
 * All methods accept a raw native address (long) and perform typed reads/writes
 * through a single GLOBAL_MEMORY segment — no MemorySegment objects are created
 * on the hot path.
 */
public final class ZeroGcIovecBlock {

    private static final MemorySegment GLOBAL_MEMORY = MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE);

    private static final VarHandle VH_LONG = JAVA_LONG.varHandle();

    private static final AddressLayout C_POINTER = ADDRESS
            .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));
    private static final VarHandle VH_POINTER = C_POINTER.varHandle();

    static final long IOVEC_SIZE = 16L;

    static final long IOVEC_ARRAY_OFFSET = Long.BYTES; // 8

    private ZeroGcIovecBlock() {}

    public static void setCount(long blockAddr, long count) {
        VH_LONG.set(GLOBAL_MEMORY, blockAddr, count);
    }

    public static long getCount(long blockAddr) {
        return (long) VH_LONG.get(GLOBAL_MEMORY, blockAddr);
    }

    public static void setIovBase(long blockAddr, int index, long base) {
        VH_LONG.set(GLOBAL_MEMORY, blockAddr + IOVEC_ARRAY_OFFSET + (long) index * IOVEC_SIZE, base);
    }

    public static long getIovBase(long blockAddr, int index) {
        return (long) VH_LONG.get(GLOBAL_MEMORY, blockAddr + IOVEC_ARRAY_OFFSET + (long) index * IOVEC_SIZE);
    }

    public static MemorySegment getIovBaseSegment(long blockAddr, int index) {
        return (MemorySegment) VH_POINTER.get(GLOBAL_MEMORY, blockAddr + IOVEC_ARRAY_OFFSET + (long) index * IOVEC_SIZE);
    }

public static void setIovLen(long blockAddr, int index, long len) {
        VH_LONG.set(GLOBAL_MEMORY, blockAddr + IOVEC_ARRAY_OFFSET + (long) index * IOVEC_SIZE + 8L, len);
    }

    public static long getIovLen(long blockAddr, int index) {
        return (long) VH_LONG.get(GLOBAL_MEMORY, blockAddr + IOVEC_ARRAY_OFFSET + (long) index * IOVEC_SIZE + 8L);
    }

    /** Returns the address of the first iovec entry, i.e. blockAddr + Long.BYTES. */
    public static long iovecArrayAddress(long blockAddr) {
        return blockAddr + IOVEC_ARRAY_OFFSET;
    }
}
