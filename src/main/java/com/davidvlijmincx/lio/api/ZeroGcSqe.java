package com.davidvlijmincx.lio.api;

import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Zero-allocation accessor and mutator for native {@code io_uring_sqe} structures.
 * <p>
 * <b>Why this change was made (Zero-Allocation Hot Path):</b>
 * In early designs, preparing an operation allocated heap wrapper objects (or transient {@link MemorySegment}
 * slices) to represent submission queue entries. At millions of I/O operations per second, this placed immense
 * pressure on the JVM garbage collector, creating GC pauses and cache churn.
 * <p>
 * <b>How it works:</b>
 * {@code ZeroGcSqe} operates directly on raw 64-bit native memory addresses ({@code long sqeAddress}) obtained from the
 * memory-mapped SQ ring. All reads and writes are performed via static {@link VarHandle} handles bound to
 * {@link #GLOBAL_MEMORY} (a boundless root segment spanning the entire native address space). No Java objects
 * or temporary memory segments are allocated during SQE acquisition or configuration.
 */
public final class ZeroGcSqe {

    static final GroupLayout SQE_LAYOUT = MemoryLayout.structLayout(
            JAVA_BYTE.withName("opcode"),
            JAVA_BYTE.withName("flags"),
            JAVA_SHORT.withName("ioprio"),
            JAVA_INT.withName("fd"),
            JAVA_LONG.withName("off"),
            JAVA_LONG.withName("addr"),
            JAVA_INT.withName("len"),
            JAVA_INT.withName("rw_flags"),
            JAVA_LONG.withName("user_data"),
            JAVA_SHORT.withName("buf_index"),
            JAVA_SHORT.withName("personality"),
            JAVA_INT.withName("file_index"),
            JAVA_LONG.withName("addr3"),
            JAVA_LONG.withName("pad")
    ).withName("io_uring_sqe");

    public static final long SQE_SIZE = SQE_LAYOUT.byteSize(); // 64 bytes

    private static final VarHandle VH_OPCODE = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("opcode"));
    private static final VarHandle VH_FLAGS = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flags"));
    private static final VarHandle VH_IOPRIO = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ioprio"));
    private static final VarHandle VH_FD = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("fd"));
    private static final VarHandle VH_OFF = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("off"));
    private static final VarHandle VH_ADDR = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("addr"));
    private static final VarHandle VH_LEN = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("len"));
    private static final VarHandle VH_RW_FLAGS = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("rw_flags"));
    private static final VarHandle VH_USER_DATA = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("user_data"));
    private static final VarHandle VH_BUF_INDEX = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("buf_index"));
    private static final VarHandle VH_PERSONALITY = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("personality"));
    private static final VarHandle VH_FILE_INDEX = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("file_index"));

    private static final VarHandle VH_LONG = JAVA_LONG.varHandle();

    private static final MemorySegment GLOBAL_MEMORY = MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE);

    private ZeroGcSqe() {}

    /**
     * Zeroes all 64 bytes of an SQE slot in-place using exactly 8 aligned 64-bit store operations.
     * <p>
     * <b>Why 8x 64-bit stores instead of memset:</b>
     * Calling foreign {@code memset} via downcall handles incurs JNI/FFM transition overhead (~10-15 ns),
     * which exceeds the cost of zeroing 64 bytes. In Java, eight sequential {@code VH_LONG.set} stores
     * are recognized by the C2 JIT compiler and lowered into 4 AVX/SSE vector stores (or 8 aligned {@code MOV}
     * instructions on x86_64). This provides zeroing with no JNI transition overhead and zero memory allocation.
     *
     * @param sqeAddress the 64-bit raw native memory address of the SQE slot
     */
    public static void zero(long sqeAddress) {
        VH_LONG.set(GLOBAL_MEMORY, sqeAddress, 0L);
        VH_LONG.set(GLOBAL_MEMORY, sqeAddress + 8L, 0L);
        VH_LONG.set(GLOBAL_MEMORY, sqeAddress + 16L, 0L);
        VH_LONG.set(GLOBAL_MEMORY, sqeAddress + 24L, 0L);
        VH_LONG.set(GLOBAL_MEMORY, sqeAddress + 32L, 0L);
        VH_LONG.set(GLOBAL_MEMORY, sqeAddress + 40L, 0L);
        VH_LONG.set(GLOBAL_MEMORY, sqeAddress + 48L, 0L);
        VH_LONG.set(GLOBAL_MEMORY, sqeAddress + 56L, 0L);
    }

    public static byte getOpcode(long sqeAddress) {
        return (byte) VH_OPCODE.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setOpcode(long sqeAddress, byte opcode) {
        VH_OPCODE.set(GLOBAL_MEMORY, sqeAddress, opcode);
    }

    public static byte getFlags(long sqeAddress) {
        return (byte) VH_FLAGS.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setFlags(long sqeAddress, byte flags) {
        VH_FLAGS.set(GLOBAL_MEMORY, sqeAddress, flags);
    }

    public static void addFlags(long sqeAddress, byte flags) {
        byte cur = (byte) VH_FLAGS.get(GLOBAL_MEMORY, sqeAddress);
        VH_FLAGS.set(GLOBAL_MEMORY, sqeAddress, (byte) (cur | flags));
    }

    public static short getIoprio(long sqeAddress) {
        return (short) VH_IOPRIO.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setIoprio(long sqeAddress, short ioprio) {
        VH_IOPRIO.set(GLOBAL_MEMORY, sqeAddress, ioprio);
    }

    public static int getFd(long sqeAddress) {
        return (int) VH_FD.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setFd(long sqeAddress, int fd) {
        VH_FD.set(GLOBAL_MEMORY, sqeAddress, fd);
    }

    public static long getOff(long sqeAddress) {
        return (long) VH_OFF.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setOff(long sqeAddress, long off) {
        VH_OFF.set(GLOBAL_MEMORY, sqeAddress, off);
    }

    public static long getAddr(long sqeAddress) {
        return (long) VH_ADDR.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setAddr(long sqeAddress, long addr) {
        VH_ADDR.set(GLOBAL_MEMORY, sqeAddress, addr);
    }

    public static int getLen(long sqeAddress) {
        return (int) VH_LEN.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setLen(long sqeAddress, int len) {
        VH_LEN.set(GLOBAL_MEMORY, sqeAddress, len);
    }

    public static int getRwFlags(long sqeAddress) {
        return (int) VH_RW_FLAGS.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setRwFlags(long sqeAddress, int rwFlags) {
        VH_RW_FLAGS.set(GLOBAL_MEMORY, sqeAddress, rwFlags);
    }

    public static long getUserData(long sqeAddress) {
        return (long) VH_USER_DATA.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setUserData(long sqeAddress, long userData) {
        VH_USER_DATA.set(GLOBAL_MEMORY, sqeAddress, userData);
    }

    public static short getBufIndex(long sqeAddress) {
        return (short) VH_BUF_INDEX.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setBufIndex(long sqeAddress, short bufIndex) {
        VH_BUF_INDEX.set(GLOBAL_MEMORY, sqeAddress, bufIndex);
    }

    public static int getFileIndex(long sqeAddress) {
        return (int) VH_FILE_INDEX.get(GLOBAL_MEMORY, sqeAddress);
    }

    public static void setFileIndex(long sqeAddress, int fileIndex) {
        VH_FILE_INDEX.set(GLOBAL_MEMORY, sqeAddress, fileIndex);
    }
}
