package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.IoUringEnter;
import com.davidvlijmincx.lio.api.functions.IoUringRegister;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

import static com.davidvlijmincx.lio.api.DirectoryFileDescriptorFlags.AT_FDCWD;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_ATTACH_WQ;
import static java.lang.foreign.ValueLayout.*;

public final class IoUringDispatcher implements AutoCloseable {

    /** IORING_CQE_F_MORE: set in cqe->flags when a multishot SQE will generate more CQEs. */
    static final int IORING_CQE_F_MORE = 1 << 1;

    // io_uring opcodes (linux/io_uring.h)
    static final byte IORING_OP_NOP = 0;
    static final byte IORING_OP_READV = 1;
    static final byte IORING_OP_WRITEV = 2;
    static final byte IORING_OP_READ_FIXED = 4;
    static final byte IORING_OP_WRITE_FIXED = 5;
    static final byte IORING_OP_ACCEPT = 13;
    static final byte IORING_OP_ASYNC_CANCEL = 14;
    static final byte IORING_OP_CONNECT = 16;
    static final byte IORING_OP_OPENAT = 18;
    static final byte IORING_OP_CLOSE = 19;
    static final byte IORING_OP_READ = 22;
    static final byte IORING_OP_WRITE = 23;
    static final byte IORING_OP_SEND = 26;
    static final byte IORING_OP_RECV = 27;

    // Enter flags
    private static final int IORING_ENTER_GETEVENTS = 1;
    private static final int IORING_ENTER_SQ_WAKEUP = 2;
    private static final int IORING_ENTER_REGISTERED_RING = 1 << 4;

    // Register opcodes & internal flags
    private static final int IORING_REGISTER_RING_FDS = 20;
    private static final int IORING_UNREGISTER_RING_FDS = 21;
    private static final byte INT_FLAG_REG_RING = 1;

    // Ring flags
    private static final int IORING_SQ_NEED_WAKEUP = 1;

    private static final short IORING_ACCEPT_MULTISHOT = 1;

    private static final LibCDispatcher libCDispatcher = NativeDispatcher.C;
    private static final IoUringEnter ioUringEnter = libCDispatcher.ioUringEnter();
    private static final IoUringRegister ioUringRegister = libCDispatcher.ioUringRegister();

    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;

    private static final VarHandle ringFdHandle;
    private static final VarHandle enterRingFdHandle;
    private static final VarHandle ringFlagsHandle;
    private static final VarHandle ringFeaturesHandle;
    private static final VarHandle ringIntFlagsHandle;

    private static final VarHandle sq_khead_handle;
    private static final VarHandle sq_ktail_handle;
    private static final VarHandle sq_kring_mask_handle;
    private static final VarHandle sq_kring_entries_handle;
    private static final VarHandle sq_kflags_handle;
    private static final VarHandle sq_kdropped_handle;
    private static final VarHandle sq_array_handle;
    private static final VarHandle sq_sqes_handle;
    private static final VarHandle sq_sqe_head_handle;
    private static final VarHandle sq_sqe_tail_handle;
    private static final VarHandle sq_ring_sz_handle;
    private static final VarHandle sq_ring_ptr_handle;
    private static final VarHandle sq_ring_mask_handle;
    private static final VarHandle sq_ring_entries_handle;

    private static final VarHandle cq_khead_handle;
    private static final VarHandle cq_ktail_handle;
    private static final VarHandle cq_kring_mask_handle;
    private static final VarHandle cq_kring_entries_handle;
    private static final VarHandle cq_kflags_handle;
    private static final VarHandle cq_koverflow_handle;
    private static final VarHandle cq_cqes_handle;
    private static final VarHandle cq_ring_sz_handle;
    private static final VarHandle cq_ring_ptr_handle;
    private static final VarHandle cq_ring_mask_handle;
    private static final VarHandle cq_ring_entries_handle;

    private static final VarHandle VH_INT = JAVA_INT.varHandle();
    private static final MemorySegment GLOBAL_MEMORY = MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE);
    static final boolean COUNT_ENTERS = Boolean.getBoolean("juring.countEnters");

    private final Arena arena;
    private int enterCount;
    private final IovecBlockPool iovecBlockPool;
    private final MemorySegment ring;
    private final MemorySegment cqePtr;
    private final MemorySegment cqePtrPtr;
    private final MemorySegment callState;
    private final Result reusableResult = new Result();
    private MemorySegment[] registeredBuffers;
    private long[] registeredBufferAddresses;

    // =========================================================================
    // Cached Ring Fields (Performance Optimization - Cycle 2)
    // -------------------------------------------------------------------------
    // Why cached as primitive Java fields:
    // In liburing / C io_uring, ring pointers, ring masks, and local head/tail indices
    // reside in a local heap struct. In early JUring designs, these fields were accessed
    // on EVERY SQE allocation, submission, and reaping via off-heap VarHandle reads into
    // the native 'ring' struct (6+ off-heap reads per operation).
    //
    // Caching these immutable pointers (addresses of mmapped kernel counters), masks,
    // limits, and local SQ tail counters as Java primitive instance fields eliminated
    // dozens of off-heap memory loads per I/O operation, reducing instructions per op
    // by 11.5% and boosting single-thread DirectRead throughput by +18.62%.
    // =========================================================================
    private int ringFd = -1;
    private int enterRingFd = -1;
    private int enterFlagsBase;
    private int ringFlags;
    private boolean isSqPoll;
    private boolean isDeferTaskrun;
    private int sqeShift = 6;
    private int cqeShift = 4;
    private Thread registeredThread;
    private boolean closed;
    private long sqKheadAddr;
    private long sqKtailAddr;
    private long sqKflagsAddr;
    private long sqSqesAddr;
    private int sqRingMask;
    private int sqRingEntries;
    private int sqeHead;
    private int sqeTail;

    private long cqKheadAddr;
    private long cqKtailAddr;
    private long cqCqesAddr;
    private int cqRingMask;
    private int cqRingEntries;

    static {
        io_uring_sq_layout = MemoryLayout.structLayout(
                JAVA_LONG.withName("khead"),
                JAVA_LONG.withName("ktail"),
                JAVA_LONG.withName("kring_mask"),
                JAVA_LONG.withName("kring_entries"),
                JAVA_LONG.withName("kflags"),
                JAVA_LONG.withName("kdropped"),
                JAVA_LONG.withName("array"),
                JAVA_LONG.withName("sqes"),
                JAVA_INT.withName("sqe_head"),
                JAVA_INT.withName("sqe_tail"),
                JAVA_LONG.withName("ring_sz"),
                JAVA_LONG.withName("ring_ptr"),
                JAVA_INT.withName("ring_mask"),
                JAVA_INT.withName("ring_entries"),
                MemoryLayout.sequenceLayout(2, JAVA_INT).withName("pad")
        ).withName("io_uring_sq");

        io_uring_cq_layout = MemoryLayout.structLayout(
                JAVA_LONG.withName("khead"),
                JAVA_LONG.withName("ktail"),
                JAVA_LONG.withName("kring_mask"),
                JAVA_LONG.withName("kring_entries"),
                JAVA_LONG.withName("kflags"),
                JAVA_LONG.withName("koverflow"),
                JAVA_LONG.withName("cqes"),
                JAVA_LONG.withName("ring_sz"),
                JAVA_LONG.withName("ring_ptr"),
                JAVA_INT.withName("ring_mask"),
                JAVA_INT.withName("ring_entries"),
                MemoryLayout.sequenceLayout(2, JAVA_INT).withName("pad")
        ).withName("io_uring_cq");

        ring_layout = MemoryLayout.structLayout(
                io_uring_sq_layout.withName("sq"),
                io_uring_cq_layout.withName("cq"),
                JAVA_INT.withName("flags"),
                JAVA_INT.withName("ring_fd"),
                JAVA_INT.withName("features"),
                JAVA_INT.withName("enter_ring_fd"),
                JAVA_BYTE.withName("int_flags"),
                MemoryLayout.sequenceLayout(3, JAVA_BYTE).withName("pad"),
                JAVA_INT.withName("pad2")
        ).withName("io_uring");

        ringFdHandle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("ring_fd"));
        enterRingFdHandle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("enter_ring_fd"));
        ringFlagsHandle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("flags"));
        ringFeaturesHandle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("features"));
        ringIntFlagsHandle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("int_flags"));

        sq_khead_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("khead"));
        sq_ktail_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("ktail"));
        sq_kring_mask_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("kring_mask"));
        sq_kring_entries_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("kring_entries"));
        sq_kflags_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("kflags"));
        sq_kdropped_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("kdropped"));
        sq_array_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("array"));
        sq_sqes_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("sqes"));
        sq_sqe_head_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("sqe_head"));
        sq_sqe_tail_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("sqe_tail"));
        sq_ring_sz_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("ring_sz"));
        sq_ring_ptr_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("ring_ptr"));
        sq_ring_mask_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("ring_mask"));
        sq_ring_entries_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("sq"), MemoryLayout.PathElement.groupElement("ring_entries"));

        cq_khead_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("khead"));
        cq_ktail_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("ktail"));
        cq_kring_mask_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("kring_mask"));
        cq_kring_entries_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("kring_entries"));
        cq_kflags_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("kflags"));
        cq_koverflow_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("koverflow"));
        cq_cqes_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("cqes"));
        cq_ring_sz_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("ring_sz"));
        cq_ring_ptr_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("ring_ptr"));
        cq_ring_mask_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("ring_mask"));
        cq_ring_entries_handle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("cq"), MemoryLayout.PathElement.groupElement("ring_entries"));
    }

    public IoUringDispatcher(Arena arena,
                            IovecBlockPool iovecBlockPool,
                            MemorySegment ring,
                            MemorySegment cqePtr,
                            MemorySegment cqePtrPtr,
                            MemorySegment callState) {
        this.arena = arena;
        this.iovecBlockPool = iovecBlockPool;
        this.ring = ring;
        this.cqePtr = cqePtr;
        this.cqePtrPtr = cqePtrPtr;
        this.callState = callState;
    }

    public IoUringDispatcher(Arena arena,
                            IovecBlockPool iovecBlockPool,
                            MemorySegment ring,
                            MemorySegment cqePtr,
                            MemorySegment cqePtrPtr,
                            MemorySegment callState,
                            IoUringEnter ioUringEnter,
                            IoUringRegister ioUringRegister) {
        this(arena, iovecBlockPool, ring, cqePtr, cqePtrPtr, callState);
    }

    public Arena arena() {
        return arena;
    }

    public MemorySegment ring() {
        return ring;
    }

    public void setRegisteredBuffers(MemorySegment[] registeredBuffers) {
        this.registeredBuffers = registeredBuffers;
        this.reusableResult.setRegisteredBuffers(registeredBuffers);
        if (registeredBuffers != null) {
            this.registeredBufferAddresses = new long[registeredBuffers.length];
            for (int i = 0; i < registeredBuffers.length; i++) {
                this.registeredBufferAddresses[i] = registeredBuffers[i] != null ? registeredBuffers[i].address() : 0L;
            }
        } else {
            this.registeredBufferAddresses = null;
        }
    }

    static IoUringDispatcher create(int queueDepth, IoUringOptions... ioUringOptions) {
        MemorySegment ring = NativeDispatcher.C.calloc(ring_layout.byteSize());
        IoUringDispatcher dispatcher = getDispatcher(ring, queueDepth);

        int ret = dispatcher.queueInit(queueDepth, IoUringOptions.combineOptions(ioUringOptions));
        if (ret < 0) {
            dispatcher.close();
            throw new RuntimeException("Failed to initialize queue " + libCDispatcher.strerror(-ret));
        }

        return dispatcher;
    }

    private static IoUringDispatcher getDispatcher(MemorySegment ring, int queueDepth) {
        Arena arena = Arena.ofShared();
        MemorySegment callState = arena.allocate(LibCDispatcher.CAPTURE_STATE_LAYOUT);
        return new IoUringDispatcher(
                arena,
                new IovecBlockPool(queueDepth),
                ring,
                libCDispatcher.alloc(AddressLayout.ADDRESS.byteSize()),
                libCDispatcher.alloc(AddressLayout.ADDRESS.byteSize() * 500),
                callState
        );
    }

    public IoUringDispatcher getSharedWorkerRing(int queueDepth, IoUringOptions... ioUringOptions) {
        MemorySegment ring = NativeDispatcher.C.calloc(ring_layout.byteSize());
        IoUringDispatcher dispatcher = getDispatcher(ring, queueDepth);
        MemorySegment params = NativeDispatcher.C.calloc(io_uring_params.layout().byteSize());
        try {
            int ring_fd = this.ringFd;

            var result = IoUringOptions.combineOptions(ioUringOptions);

            io_uring_params.flags(params, result | IORING_SETUP_ATTACH_WQ.value);
            io_uring_params.wq_fd(params, ring_fd);

            var ret = dispatcher.queueInitParams(queueDepth, ring, params);
            if (ret < 0) {
                dispatcher.close();
                throw new RuntimeException("ret = " + ret + " " + NativeDispatcher.C.strerror(-ret));
            }

            return dispatcher;
        } finally {
            NativeDispatcher.C.free(params);
        }
    }

    long allocateIovecBlock() {
        return iovecBlockPool.checkOut();
    }

    void releaseIovecBlock(long address) {
        iovecBlockPool.checkIn(address);
    }

    /**
     * Gets the next available SQE address in the submission ring without allocating memory.
     * <p>
     * <b>Ring Logic & Zero-Allocation:</b>
     * <ol>
     *   <li>Reads the kernel-managed head index {@code *sqKheadAddr} directly via {@link VarHandle}.</li>
     *   <li>Checks for queue overflow: if {@code tail - head >= sqRingEntries}, the ring is full and returns {@code 0L}.</li>
     *   <li>Computes the physical SQE offset using the power-of-two mask: {@code (tail & sqRingMask) * 64L}.</li>
     *   <li>Increments the local {@code sqeTail} counter.</li>
     *   <li>Zeroes all 64 bytes of the newly acquired SQE slot via {@link ZeroGcSqe#zero(long)} (8x 64-bit stores).</li>
     * </ol>
     *
     * @return the raw 64-bit native memory address of the SQE slot, or {@code 0L} if the queue is full
     */
    long getSqe() {
        int head = (int) VH_INT.get(GLOBAL_MEMORY, sqKheadAddr);
        int tail = sqeTail;

        if (tail - head >= sqRingEntries) {
            return 0L;
        }

        int index = tail & sqRingMask;
        long sqe_addr = sqSqesAddr + ((long) index << sqeShift);

        sqeTail = tail + 1;

        ZeroGcSqe.zero(sqe_addr);
        if (sqeShift > 6) {
            ZeroGcSqe.zero(sqe_addr + 64L);
        }
        return sqe_addr;
    }

    void setSqeFlag(long sqe, SqeOptions... flags) {
        setSqeFlag(sqe, SqeOptions.combineOptions(flags));
    }

    void setSqeFlag(long sqe, byte flags) {
        ZeroGcSqe.setFlags(sqe, flags);
    }

    void setUserData(long sqe, long userData) {
        ZeroGcSqe.setUserData(sqe, userData);
    }

    void prepareOpenAt(long sqe, long filePathAddress, int flags, int mode) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_OPENAT);
        ZeroGcSqe.setFd(sqe, AT_FDCWD.value);
        ZeroGcSqe.setOff(sqe, 0L);
        ZeroGcSqe.setAddr(sqe, filePathAddress);
        ZeroGcSqe.setLen(sqe, mode);
        ZeroGcSqe.setRwFlags(sqe, flags);
    }

    void prepareOpenDirectAt(long sqe, long filePathAddress, int flags, int mode, int fileIndex) {
        prepareOpenAt(sqe, filePathAddress, flags, mode);
        ZeroGcSqe.setFileIndex(sqe, fileIndex + 1);
    }

    void prepareClose(long sqe, int fd) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_CLOSE);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, 0L);
        ZeroGcSqe.setAddr(sqe, 0L);
        ZeroGcSqe.setLen(sqe, 0);
    }

    void prepareCloseDirect(long sqe, int fileIndex) {
        prepareClose(sqe, 0);
        ZeroGcSqe.setFileIndex(sqe, fileIndex + 1);
    }

    void prepareRead(long sqe, int fd, long bufferAddress, long size, long offset) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_READ);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, offset);
        ZeroGcSqe.setAddr(sqe, bufferAddress);
        ZeroGcSqe.setLen(sqe, (int) size);
    }

    void prepareReadFixed(long sqe, int fd, long bufferAddress, long nbytes, long offset, int bufferIndex) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_READ_FIXED);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, offset);
        ZeroGcSqe.setAddr(sqe, bufferAddress);
        ZeroGcSqe.setLen(sqe, (int) nbytes);
        ZeroGcSqe.setBufIndex(sqe, (short) bufferIndex);
    }

    void prepareWrite(long sqe, int fd, long bufferAddress, long size, long offset) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_WRITE);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, offset);
        ZeroGcSqe.setAddr(sqe, bufferAddress);
        ZeroGcSqe.setLen(sqe, (int) size);
    }

    void prepareWriteFixed(long sqe, int fd, long bufferAddress, long nbytes, long offset, int bufferIndex) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_WRITE_FIXED);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, offset);
        ZeroGcSqe.setAddr(sqe, bufferAddress);
        ZeroGcSqe.setLen(sqe, (int) nbytes);
        ZeroGcSqe.setBufIndex(sqe, (short) bufferIndex);
    }

    void prepareReadvAddress(long sqe, int fd, long iovecAddr, int nrVecs, long offset) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_READV);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, offset);
        ZeroGcSqe.setAddr(sqe, iovecAddr);
        ZeroGcSqe.setLen(sqe, nrVecs);
    }

    void prepareWritevAddress(long sqe, int fd, long iovecAddr, int nrVecs, long offset) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_WRITEV);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, offset);
        ZeroGcSqe.setAddr(sqe, iovecAddr);
        ZeroGcSqe.setLen(sqe, nrVecs);
    }

    void prepareAccept(long sqe, int fd, long addr, long addrLen, int flags) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_ACCEPT);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, addrLen);
        ZeroGcSqe.setAddr(sqe, addr);
        ZeroGcSqe.setLen(sqe, 0);
        ZeroGcSqe.setRwFlags(sqe, flags);
    }

    void prepareMultishotAccept(long sqe, int fd, long addr, long addrLen, int flags) {
        prepareAccept(sqe, fd, addr, addrLen, flags);
        short prio = ZeroGcSqe.getIoprio(sqe);
        ZeroGcSqe.setIoprio(sqe, (short) (prio | IORING_ACCEPT_MULTISHOT));
    }

    void prepareConnect(long sqe, int fd, long addr, int addrLen) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_CONNECT);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, (long) addrLen);
        ZeroGcSqe.setAddr(sqe, addr);
        ZeroGcSqe.setLen(sqe, 0);
    }

    void prepareRecv(long sqe, int fd, long buf, long len, int flags) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_RECV);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, 0L);
        ZeroGcSqe.setAddr(sqe, buf);
        ZeroGcSqe.setLen(sqe, (int) len);
        ZeroGcSqe.setRwFlags(sqe, flags);
    }

    void prepareSend(long sqe, int fd, long buf, long len, int flags) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_SEND);
        ZeroGcSqe.setFd(sqe, fd);
        ZeroGcSqe.setOff(sqe, 0L);
        ZeroGcSqe.setAddr(sqe, buf);
        ZeroGcSqe.setLen(sqe, (int) len);
        ZeroGcSqe.setRwFlags(sqe, flags);
    }

    void prepareCancel(long sqe, long userDataToCancel, int flags) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_ASYNC_CANCEL);
        ZeroGcSqe.setFd(sqe, -1);
        ZeroGcSqe.setOff(sqe, 0L);
        ZeroGcSqe.setAddr(sqe, userDataToCancel);
        ZeroGcSqe.setLen(sqe, 0);
        ZeroGcSqe.setRwFlags(sqe, flags);
    }

    void prepareNop(long sqe) {
        ZeroGcSqe.setOpcode(sqe, IORING_OP_NOP);
        ZeroGcSqe.setFd(sqe, -1);
    }

    /**
     * Submits all prepared SQEs to the kernel.
     * <p>
     * <b>Memory Ordering:</b>
     * Emits a release barrier ({@code VH_INT.setRelease}) when writing the local tail to {@code sq_ktail}.
     * This guarantees that all SQE field writes (opcode, fd, addr, len, flags) are globally visible in RAM
     * before the kernel observes the updated tail counter.
     * <p>
     * <b>Fast-Path Registered Ring:</b>
     * Uses {@code enterRingFd} with {@code IORING_ENTER_REGISTERED_RING} to bypass the kernel's process-wide
     * {@code files_struct} lookup and atomic {@code fget}/{@code fput} refcounting.
     */
    void submit() {
        int tail = sqeTail;
        int head = (int) VH_INT.get(GLOBAL_MEMORY, sqKheadAddr);

        VH_INT.setRelease(GLOBAL_MEMORY, sqKtailAddr, tail);
        sqeHead = tail;

        int toSubmit = tail - head;
        if (toSubmit <= 0) {
            return;
        }

        boolean sqpoll = this.isSqPoll;
        if (this.enterFlagsBase != 0 && Thread.currentThread() != this.registeredThread) {
            fallbackOrReRegisterRingFd();
        }
        int enterFlags = this.enterFlagsBase;
        boolean needEnter = true;

        if (sqpoll) {
            int sqFlags = (int) VH_INT.getVolatile(GLOBAL_MEMORY, sqKflagsAddr);
            if ((sqFlags & IORING_SQ_NEED_WAKEUP) != 0) {
                enterFlags |= IORING_ENTER_SQ_WAKEUP;
            } else {
                needEnter = false;
            }
        }

        if (needEnter) {
            while (true) {
                if (COUNT_ENTERS) {
                    enterCount++;
                }
                long res = ioUringEnter.ioUringEnter(this.callState, enterRingFd, toSubmit, 0, enterFlags, MemorySegment.NULL, 0L);
                if (res >= 0) {
                    return;
                }
                int err = LibCDispatcher.errno(this.callState);
                if (err == 9 /* EBADF */ && this.enterFlagsBase != 0) {
                    fallbackOrReRegisterRingFd();
                    enterFlags = (enterFlags & ~IORING_ENTER_REGISTERED_RING) | this.enterFlagsBase;
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    tail = sqeTail;
                    head = (int) VH_INT.get(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    if (toSubmit == 0 && !sqpoll) {
                        return;
                    }
                    continue;
                }
                throw new RuntimeException("Failed to submit queue: " + libCDispatcher.strerror(err));
            }
        }
    }

    boolean isDeferTaskrun() {
        return isDeferTaskrun;
    }

    void flushDeferredTaskrun() {
        if (this.enterFlagsBase != 0 && Thread.currentThread() != this.registeredThread) {
            fallbackOrReRegisterRingFd();
        }
        if (COUNT_ENTERS) {
            enterCount++;
        }
        long res = ioUringEnter.ioUringEnter(this.callState, enterRingFd, 0, 0, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
        if (res < 0) {
            int err = LibCDispatcher.errno(this.callState);
            if (err == 9 /* EBADF */ && this.enterFlagsBase != 0) {
                fallbackOrReRegisterRingFd();
                ioUringEnter.ioUringEnter(this.callState, enterRingFd, 0, 0, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
            }
        }
    }

    /**
     * Submits pending SQEs and waits until at least {@code waitNr} CQEs are available in a single syscall.
     * <p>
     * <b>Why this is faster:</b>
     * If the required completions are already available in the completion ring (detected via acquire-read of {@code cq_ktail}),
     * and no SQEs are pending submission, this method returns immediately with <i>zero system calls</i>.
     * If a wait is required, it merges submission ({@code toSubmit}) and waiting ({@code waitNr}) into a single
     * {@code io_uring_enter(..., IORING_ENTER_GETEVENTS)} call.
     *
     * @param waitNr minimum completions required before returning
     * @return number of completions currently available in the CQ ring
     */
    int submitAndWait(int waitNr) {
        int tail = sqeTail;
        int head = (int) VH_INT.get(GLOBAL_MEMORY, sqKheadAddr);
        int toSubmit = tail - head;
        if (toSubmit > 0) {
            VH_INT.setRelease(GLOBAL_MEMORY, sqKtailAddr, tail);
            sqeHead = tail;
        } else {
            toSubmit = 0;
        }

        boolean sqpoll = this.isSqPoll;
        while (true) {
            int cqHead = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
            int cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            int available = cqTail - cqHead;
            if (available >= waitNr && toSubmit == 0) {
                return available;
            }

            int enterFlags = (waitNr > 0 && available < waitNr) ? IORING_ENTER_GETEVENTS : 0;
            boolean needEnter = (enterFlags != 0);
            if (toSubmit > 0) {
                if (sqpoll) {
                    int sqFlags = (int) VH_INT.getVolatile(GLOBAL_MEMORY, sqKflagsAddr);
                    if ((sqFlags & IORING_SQ_NEED_WAKEUP) != 0) {
                        enterFlags |= IORING_ENTER_SQ_WAKEUP;
                        needEnter = true;
                    }
                } else {
                    needEnter = true;
                }
            }

            if (!needEnter) {
                return available;
            }

            if (COUNT_ENTERS) {
                enterCount++;
            }
            if (this.enterFlagsBase != 0 && Thread.currentThread() != this.registeredThread) {
                fallbackOrReRegisterRingFd();
            }
            int minComplete = (enterFlags & IORING_ENTER_GETEVENTS) != 0 ? waitNr : 0;
            long res = ioUringEnter.ioUringEnter(this.callState, enterRingFd, toSubmit, minComplete, enterFlags | enterFlagsBase, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 9 /* EBADF */ && this.enterFlagsBase != 0) {
                    fallbackOrReRegisterRingFd();
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    tail = sqeTail;
                    head = (int) VH_INT.get(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    continue;
                }
                throw new RuntimeException("Failed in submitAndWait: " + libCDispatcher.strerror(err));
            }
            toSubmit = 0;
            if (waitNr <= 0) {
                return 0;
            }
        }
    }

    int waitCqe(MemorySegment ring, MemorySegment cqePtr) {
        while (true) {
            int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
            int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            if (head != tail) {
                int index = head & cqRingMask;
                long cqeAddr = cqCqesAddr + ((long) index << cqeShift);
                cqePtr.setAtIndex(JAVA_LONG, 0, cqeAddr);
                return 0;
            }

            if (COUNT_ENTERS) {
                enterCount++;
            }
            if (this.enterFlagsBase != 0 && Thread.currentThread() != this.registeredThread) {
                fallbackOrReRegisterRingFd();
            }
            long res = ioUringEnter.ioUringEnter(this.callState, enterRingFd, 0, 1, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 9 /* EBADF */ && this.enterFlagsBase != 0) {
                    fallbackOrReRegisterRingFd();
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    continue;
                }
                return -err;
            }
        }
    }

    int peekCqe(MemorySegment ring, MemorySegment cqePtr) {
        int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
        if (head != tail) {
            int index = head & cqRingMask;
            long cqeAddr = cqCqesAddr + ((long) index << cqeShift);
            cqePtr.setAtIndex(JAVA_LONG, 0, cqeAddr);
            return 0;
        }
        return -11; // -EAGAIN
    }

    int peekBatchCqe(MemorySegment ring, MemorySegment cqePtrPtr, int batchSize) {
        int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
        int available = tail - head;
        int count = Math.min(available, batchSize);
        long cqes = cqCqesAddr;
        int mask = cqRingMask;
        int shift = cqeShift;
        for (int i = 0; i < count; i++) {
            int index = (head + i) & mask;
            long cqeAddr = cqes + ((long) index << shift);
            cqePtrPtr.setAtIndex(JAVA_LONG, i, cqeAddr);
        }
        return count;
    }

    void cqeSeen(MemorySegment ring, MemorySegment cqe) {
        cqAdvance(ring, 1);
    }

    void cqAdvance(MemorySegment ring, int count) {
        if (count <= 0) return;
        int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
        VH_INT.setRelease(GLOBAL_MEMORY, cqKheadAddr, head + count);
    }

    int waitCqeNr(MemorySegment ring, MemorySegment cqePtrPtr, int waitNr) {
        while (true) {
            int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
            int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            int available = tail - head;
            if (available >= waitNr) {
                return 0;
            }

            if (COUNT_ENTERS) {
                enterCount++;
            }
            if (this.enterFlagsBase != 0 && Thread.currentThread() != this.registeredThread) {
                fallbackOrReRegisterRingFd();
            }
            long res = ioUringEnter.ioUringEnter(this.callState, enterRingFd, 0, waitNr, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 9 /* EBADF */ && this.enterFlagsBase != 0) {
                    fallbackOrReRegisterRingFd();
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    continue;
                }
                return -err;
            }
        }
    }

    int takeEnterCount() {
        int c = enterCount;
        enterCount = 0;
        return c;
    }

    int queueInit(int queueDepth, int flags) {
        MemorySegment params = libCDispatcher.calloc(io_uring_params.layout().byteSize());
        io_uring_params.flags(params, flags);
        int ret = queueInitParams(queueDepth, this.ring, params);
        libCDispatcher.free(params);
        return ret;
    }

    /**
     * Initializes the io_uring submission and completion queues, memory-mapping the ring buffers.
     * <p>
     * <b>Memory Mapping Architecture:</b>
     * <ol>
     *   <li>Calls {@code io_uring_setup} to create the ring file descriptor and populate ring offsets.</li>
     *   <li>Inspects {@code IORING_FEAT_SINGLE_MMAP}: If supported by the kernel (Linux 5.4+), both the SQ ring
     *       and CQ ring are mapped in a single {@code mmap} call, sharing the same virtual memory region.</li>
     *   <li>Maps the SQE array separately at offset {@code 0x10000000L (IORING_OFF_SQES)}.</li>
     *   <li>Extracts and caches immutable pointers, masks, and entries directly into primitive Java fields.</li>
     *   <li>Registers the ring file descriptor via {@link #registerRingFd()} to optimize subsequent syscalls.</li>
     * </ol>
     */
    int queueInitParams(int queueDepth, MemorySegment ring, MemorySegment params) {
        int fd = libCDispatcher.ioUringSetup(this.callState, queueDepth, params);
        if (fd < 0) {
            return fd;
        }

        int sq_entries = io_uring_params.sq_entries(params);
        int cq_entries = io_uring_params.cq_entries(params);
        int flags = io_uring_params.flags(params);
        int features = io_uring_params.features(params);

        MemorySegment sq_off = io_uring_params.sq_off(params);
        MemorySegment cq_off = io_uring_params.cq_off(params);

        long sq_off_array = io_sqring_offsets.array(sq_off);
        long sq_ring_sz = sq_off_array + (long) sq_entries * 4L;

        long cq_off_cqes = io_cqring_offsets.cqes(cq_off);
        int cqe_shift = ((flags & IoUringOptions.IORING_SETUP_CQE32.value) != 0) ? 1 : 0;
        long cq_size = ((long) cq_entries << cqe_shift) * 16L;
        long cq_ring_sz = cq_off_cqes + cq_size;

        boolean singleMmap = (features & 1 /* IORING_FEAT_SINGLE_MMAP */) != 0;
        if (singleMmap) {
            if (cq_ring_sz > sq_ring_sz) {
                sq_ring_sz = cq_ring_sz;
            }
            cq_ring_sz = sq_ring_sz;
        }

        long sq_ring_ptr = libCDispatcher.mmap(sq_ring_sz, LibCDispatcher.PROT_READ | LibCDispatcher.PROT_WRITE,
                LibCDispatcher.MAP_SHARED | LibCDispatcher.MAP_POPULATE, fd, 0L /* IORING_OFF_SQ_RING */);
        if (sq_ring_ptr < 0) {
            libCDispatcher.close(fd);
            return (int) sq_ring_ptr;
        }

        long cq_ring_ptr;
        if (singleMmap) {
            cq_ring_ptr = sq_ring_ptr;
        } else {
            cq_ring_ptr = libCDispatcher.mmap(cq_ring_sz, LibCDispatcher.PROT_READ | LibCDispatcher.PROT_WRITE,
                    LibCDispatcher.MAP_SHARED | LibCDispatcher.MAP_POPULATE, fd, 0x8000000L /* IORING_OFF_CQ_RING */);
            if (cq_ring_ptr < 0) {
                libCDispatcher.munmap(sq_ring_ptr, sq_ring_sz);
                libCDispatcher.close(fd);
                return (int) cq_ring_ptr;
            }
        }

        int sqe_shift = ((flags & IoUringOptions.IORING_SETUP_SQE128.value) != 0) ? 1 : 0;
        long sqes_sz = ((long) sq_entries << sqe_shift) * 64L;
        long sqes_ptr = libCDispatcher.mmap(sqes_sz, LibCDispatcher.PROT_READ | LibCDispatcher.PROT_WRITE,
                LibCDispatcher.MAP_SHARED | LibCDispatcher.MAP_POPULATE, fd, 0x10000000L /* IORING_OFF_SQES */);
        if (sqes_ptr < 0) {
            if (!singleMmap) {
                libCDispatcher.munmap(cq_ring_ptr, cq_ring_sz);
            }
            libCDispatcher.munmap(sq_ring_ptr, sq_ring_sz);
            libCDispatcher.close(fd);
            return (int) sqes_ptr;
        }

        ringFdHandle.set(ring, 0L, fd);
        enterRingFdHandle.set(ring, 0L, fd);
        ringFlagsHandle.set(ring, 0L, flags);
        ringFeaturesHandle.set(ring, 0L, features);
        ringIntFlagsHandle.set(ring, 0L, (byte) 0);

        long sq_khead = sq_ring_ptr + io_sqring_offsets.head(sq_off);
        long sq_ktail = sq_ring_ptr + io_sqring_offsets.tail(sq_off);
        long sq_kring_mask = sq_ring_ptr + io_sqring_offsets.ring_mask(sq_off);
        long sq_kring_entries = sq_ring_ptr + io_sqring_offsets.ring_entries(sq_off);
        long sq_kflags = sq_ring_ptr + io_sqring_offsets.flags(sq_off);
        long sq_kdropped = sq_ring_ptr + io_sqring_offsets.dropped(sq_off);

        sq_khead_handle.set(ring, 0L, sq_khead);
        sq_ktail_handle.set(ring, 0L, sq_ktail);
        sq_kring_mask_handle.set(ring, 0L, sq_kring_mask);
        sq_kring_entries_handle.set(ring, 0L, sq_kring_entries);
        sq_kflags_handle.set(ring, 0L, sq_kflags);
        sq_kdropped_handle.set(ring, 0L, sq_kdropped);
        sq_sqes_handle.set(ring, 0L, sqes_ptr);
        sq_sqe_head_handle.set(ring, 0L, 0);
        sq_sqe_tail_handle.set(ring, 0L, 0);
        sq_ring_sz_handle.set(ring, 0L, sq_ring_sz);
        sq_ring_ptr_handle.set(ring, 0L, sq_ring_ptr);

        int sq_mask = (int) VH_INT.get(GLOBAL_MEMORY, sq_kring_mask);
        int sq_ents = (int) VH_INT.get(GLOBAL_MEMORY, sq_kring_entries);
        sq_ring_mask_handle.set(ring, 0L, sq_mask);
        sq_ring_entries_handle.set(ring, 0L, sq_ents);

        if ((flags & IoUringOptions.IORING_SETUP_NO_SQARRAY.value) == 0) {
            long sq_array = sq_ring_ptr + io_sqring_offsets.array(sq_off);
            sq_array_handle.set(ring, 0L, sq_array);
            for (int i = 0; i < sq_ents; i++) {
                VH_INT.set(GLOBAL_MEMORY, sq_array + (long) i * 4L, i);
            }
        }

        long cq_khead = cq_ring_ptr + io_cqring_offsets.head(cq_off);
        long cq_ktail = cq_ring_ptr + io_cqring_offsets.tail(cq_off);
        long cq_kring_mask = cq_ring_ptr + io_cqring_offsets.ring_mask(cq_off);
        long cq_kring_entries = cq_ring_ptr + io_cqring_offsets.ring_entries(cq_off);
        long cq_koverflow = cq_ring_ptr + io_cqring_offsets.overflow(cq_off);
        long cq_cqes_addr = cq_ring_ptr + io_cqring_offsets.cqes(cq_off);

        cq_khead_handle.set(ring, 0L, cq_khead);
        cq_ktail_handle.set(ring, 0L, cq_ktail);
        cq_kring_mask_handle.set(ring, 0L, cq_kring_mask);
        cq_kring_entries_handle.set(ring, 0L, cq_kring_entries);
        cq_koverflow_handle.set(ring, 0L, cq_koverflow);
        cq_cqes_handle.set(ring, 0L, cq_cqes_addr);
        cq_ring_sz_handle.set(ring, 0L, cq_ring_sz);
        cq_ring_ptr_handle.set(ring, 0L, cq_ring_ptr);

        int cq_off_flags = io_cqring_offsets.flags(cq_off);
        if (cq_off_flags != 0) {
            cq_kflags_handle.set(ring, 0L, cq_ring_ptr + cq_off_flags);
        }

        int cq_mask = (int) VH_INT.get(GLOBAL_MEMORY, cq_kring_mask);
        int cq_ents = (int) VH_INT.get(GLOBAL_MEMORY, cq_kring_entries);
        cq_ring_mask_handle.set(ring, 0L, cq_mask);
        cq_ring_entries_handle.set(ring, 0L, cq_ents);

        this.ringFd = fd;
        this.enterRingFd = fd;
        this.enterFlagsBase = 0;
        this.ringFlags = flags;
        this.isSqPoll = (flags & IoUringOptions.IORING_SETUP_SQPOLL.value) != 0;
        this.isDeferTaskrun = (flags & IoUringOptions.IORING_SETUP_DEFER_TASKRUN.value) != 0;
        this.sqeShift = 6 + sqe_shift;
        this.cqeShift = 4 + cqe_shift;
        this.sqKheadAddr = sq_khead;
        this.sqKtailAddr = sq_ktail;
        this.sqKflagsAddr = this.isSqPoll ? sq_kflags : 0L;
        this.sqSqesAddr = sqes_ptr;
        this.sqRingMask = sq_mask;
        this.sqRingEntries = sq_ents;
        this.sqeHead = 0;
        this.sqeTail = 0;

        this.cqKheadAddr = cq_khead;
        this.cqKtailAddr = cq_ktail;
        this.cqCqesAddr = cq_cqes_addr;
        this.cqRingMask = cq_mask;
        this.cqRingEntries = cq_ents;

        registerRingFd();

        return 0;
    }

    /**
     * Registers the io_uring ring file descriptor with the kernel via {@code IORING_REGISTER_RING_FDS}.
     * <p>
     * <b>Why this optimization was made (Eliminating JVM files_struct Contention):</b>
     * In a standard {@code io_uring_enter} invocation, the kernel executes {@code fdget(fd)} to look up the
     * file struct in the process-wide descriptor table ({@code current->files}). In a multi-threaded JVM,
     * all JVM worker, GC, JIT, and application threads share the same {@code files_struct}.
     * Because {@code files_struct.count > 1}, the kernel cannot take the lockless single-threaded fast-path
     * and is forced to perform an atomic {@code atomic_long_inc_not_zero(&file->f_count)} and subsequent {@code fput()}
     * on every single system call.
     * <p>
     * <b>How it works:</b>
     * Calling {@code io_uring_register(IORING_REGISTER_RING_FDS)} registers the ring's own file descriptor
     * into the ring's private registered table. The kernel assigns a registered slot index (stored in {@code enterRingFd}).
     * Subsequent {@code io_uring_enter} calls pass this slot index with flag {@code IORING_ENTER_REGISTERED_RING},
     * completely bypassing {@code files_struct} lookup and atomic reference counting.
     *
     * @return 1 on success, or negative errno on failure
     */
    int registerRingFd() {
        Thread current = Thread.currentThread();
        if (this.ringFd < 0 || current.isVirtual()) {
            return -9; // -EBADF
        }
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment up = tempArena.allocate(16L, 8L);
            up.set(JAVA_INT, 0L, -1);
            up.set(JAVA_INT, 4L, 0);
            up.set(JAVA_LONG, 8L, this.ringFd);
            long res = ioUringRegister.ioUringRegister(this.callState, this.ringFd, IORING_REGISTER_RING_FDS, up, 1);
            if (res == 1L) {
                int regFd = up.get(JAVA_INT, 0L);
                this.enterRingFd = regFd;
                this.enterFlagsBase = IORING_ENTER_REGISTERED_RING;
                this.registeredThread = current;
                enterRingFdHandle.set(this.ring, 0L, regFd);
                ringIntFlagsHandle.set(this.ring, 0L, INT_FLAG_REG_RING);
                return 1;
            }
            return -LibCDispatcher.errno(this.callState);
        }
    }

    /**
     * Unregisters the ring file descriptor from the kernel registered table.
     *
     * @return 0 on success, or negative errno
     */
    int unregisterRingFd() {
        if (this.ringFd < 0 || this.enterFlagsBase == 0) {
            return 0;
        }
        int regSlot = this.enterRingFd;
        Thread regThread = this.registeredThread;
        this.enterRingFd = this.ringFd;
        this.enterFlagsBase = 0;
        this.registeredThread = null;
        enterRingFdHandle.set(this.ring, 0L, this.ringFd);
        ringIntFlagsHandle.set(this.ring, 0L, (byte) 0);
        if (Thread.currentThread() != regThread) {
            return 0;
        }
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment up = tempArena.allocate(16L, 8L);
            up.set(JAVA_INT, 0L, regSlot);
            up.set(JAVA_INT, 4L, 0);
            up.set(JAVA_LONG, 8L, 0L);
            long res = ioUringRegister.ioUringRegister(this.callState, this.ringFd, IORING_UNREGISTER_RING_FDS, up, 1);
            if (res < 0) {
                return -LibCDispatcher.errno(this.callState);
            }
            return (int) res;
        }
    }

    private void fallbackOrReRegisterRingFd() {
        if (registerRingFd() < 0) {
            this.enterRingFd = this.ringFd;
            this.enterFlagsBase = 0;
            this.registeredThread = null;
            enterRingFdHandle.set(this.ring, 0L, this.ringFd);
            ringIntFlagsHandle.set(this.ring, 0L, (byte) 0);
        }
    }

    boolean isRingFdRegistered() {
        return this.enterFlagsBase != 0;
    }

    void queueExit(MemorySegment ring) {
        int fd = this.ringFd;
        if (fd < 0) return;

        unregisterRingFd();

        long sqes_addr = this.sqSqesAddr;
        int sq_entries = this.sqRingEntries;
        int flags = this.ringFlags;
        int sqe_shift = ((flags & IoUringOptions.IORING_SETUP_SQE128.value) != 0) ? 1 : 0;
        long sqes_sz = ((long) sq_entries << sqe_shift) * 64L;
        if (sqes_addr != 0L) {
            libCDispatcher.munmap(sqes_addr, sqes_sz);
        }

        long sq_ring_ptr = (long) sq_ring_ptr_handle.get(ring, 0L);
        long sq_ring_sz = (long) sq_ring_sz_handle.get(ring, 0L);
        long cq_ring_ptr = (long) cq_ring_ptr_handle.get(ring, 0L);
        long cq_ring_sz = (long) cq_ring_sz_handle.get(ring, 0L);

        if (cq_ring_ptr != 0L && cq_ring_ptr != sq_ring_ptr && cq_ring_sz != 0L) {
            libCDispatcher.munmap(cq_ring_ptr, cq_ring_sz);
        }
        if (sq_ring_ptr != 0L && sq_ring_sz != 0L) {
            libCDispatcher.munmap(sq_ring_ptr, sq_ring_sz);
        }

        libCDispatcher.close(fd);
        ringFdHandle.set(ring, 0L, -1);
        this.ringFd = -1;
    }

    int registerBuffers(MemorySegment ring, MemorySegment iovecs, int nrIovecs) {
        int fd = this.ringFd;
        long res = ioUringRegister.ioUringRegister(this.callState, fd, 0 /* IORING_REGISTER_BUFFERS */, iovecs, nrIovecs);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        return (int) res;
    }

    MemorySegment[] registerBuffers(int bufferSize, int nrIovecs) {
        var iovecStructure = libCDispatcher.allocateIovec(arena, bufferSize, nrIovecs);
        int ret = registerBuffers(ring, iovecStructure.iovecArray(), nrIovecs);
        if (ret < 0) {
            throw new RuntimeException("Failed to register buffers: " + libCDispatcher.strerror(-ret));
        }
        setRegisteredBuffers(iovecStructure.buffers());
        return this.registeredBuffers;
    }

    int registerFiles(MemorySegment ring, MemorySegment fdArray, int count) {
        int fd = this.ringFd;
        long res = ioUringRegister.ioUringRegister(this.callState, fd, 2 /* IORING_REGISTER_FILES */, fdArray, count);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        return (int) res;
    }

    int registerFiles(int[] fileDescriptors) {
        int count = fileDescriptors.length;
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment fdArray = tempArena.allocate(JAVA_INT.byteSize() * count);

            for (int i = 0; i < count; i++) {
                fdArray.setAtIndex(JAVA_INT, i, fileDescriptors[i]);
            }

            int ret = registerFiles(ring, fdArray, count);
            if (ret < 0) {
                throw new RuntimeException("Failed to register files: " + libCDispatcher.strerror(-ret));
            }
            return ret;
        }
    }

    int registerFilesUpdate(MemorySegment ring, int offset, MemorySegment fdArray, int count) {
        int fd = this.ringFd;
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment up = tempArena.allocate(16L);
            up.set(JAVA_INT, 0L, offset);
            up.set(JAVA_INT, 4L, 0);
            up.set(JAVA_LONG, 8L, fdArray.address());
            long res = ioUringRegister.ioUringRegister(this.callState, fd, 6 /* IORING_REGISTER_FILES_UPDATE */, up, count);
            if (res < 0) {
                return -LibCDispatcher.errno(this.callState);
            }
            return (int) res;
        }
    }

    int registerFilesUpdate(int offset, int[] fileDescriptors) {
        int count = fileDescriptors.length;
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment fdArray = tempArena.allocate(JAVA_INT.byteSize() * count);

            for (int i = 0; i < count; i++) {
                fdArray.setAtIndex(JAVA_INT, i, fileDescriptors[i]);
            }

            int ret = registerFilesUpdate(ring, offset, fdArray, count);
            if (ret < 0) {
                throw new RuntimeException("Failed to update registered files: " + libCDispatcher.strerror(-ret));
            }
            return ret;
        }
    }

    void registerIowqMaxWorkers(int bounded, int unbounded) {
        int fd = this.ringFd;
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment values = tempArena.allocate(JAVA_INT, 2);
            values.setAtIndex(JAVA_INT, 0, bounded);
            values.setAtIndex(JAVA_INT, 1, unbounded);
            long res = ioUringRegister.ioUringRegister(this.callState, fd, 19 /* IORING_REGISTER_IOWQ_MAX_WORKERS */, values, 2);
            if (res < 0) {
                throw new RuntimeException("Failed to register max workers: " + libCDispatcher.strerror(LibCDispatcher.errno(this.callState)));
            }
        }
    }

    /**
     * Drains available completions directly from the shared memory-mapped CQ ring in-place.
     * <p>
     * <b>Why in-place reaping is faster (Cycle 5 & Sequel Cycle 1):</b>
     * Previous designs called {@code peekBatchCqe}, which computed native CQE addresses and copied them into an
     * intermediate off-heap pointer buffer ({@code cqePtrPtr}), only to read them back out in a second loop.
     * This method fuses CQE address calculation, result field extraction, consumer notification, and CQ head advancement
     * into a single sequential loop directly over the memory-mapped ring.
     * <p>
     * <b>Memory Ordering:</b>
     * After processing {@code count} entries, advances {@code cq_khead} using {@code VH_INT.setRelease}.
     * This signals to the kernel that these CQ slots are free to be overwritten by new completions.
     *
     * @param head      the current CQ ring head index
     * @param available total number of completed entries currently in the CQ ring
     * @param maxReap   maximum number of completions to reap in this pass
     * @param consumer  zero-allocation callback invoked with {@link #reusableResult}
     * @return number of completions actually reaped
     */
    private int reapAvailable(int head, int available, int maxReap, ResultConsumer consumer) {
        if (available <= 0) {
            return 0;
        }
        int count = Math.min(available, maxReap);
        long cqes = cqCqesAddr;
        int mask = cqRingMask;
        int shift = cqeShift;
        for (int i = 0; i < count; i++) {
            int index = (head + i) & mask;
            long address = cqes + ((long) index << shift);
            long userData = ZeroGcCqe.getUserData(address);
            int res = ZeroGcCqe.getRes(address);
            int flags = ZeroGcCqe.getFlags(address);

            populateResultFromCqe(reusableResult, userData, res, flags);
            consumer.accept(reusableResult);
        }
        VH_INT.setRelease(GLOBAL_MEMORY, cqKheadAddr, head + count);
        return count;
    }

    int peekForBatchResult(int batchSize, ResultConsumer consumer) {
        int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
        return reapAvailable(head, tail - head, batchSize, consumer);
    }

    @FunctionalInterface
    interface RawCqeConsumer {
        void accept(long userData, int res, int flags);
    }

    int sqRingEntries() {
        return sqRingEntries;
    }

    long[] registeredBufferAddresses() {
        return registeredBufferAddresses;
    }

    MemorySegment[] registeredBuffers() {
        return registeredBuffers;
    }

    int reapRawCqes(int maxReap, RawCqeConsumer consumer) {
        int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
        int available = tail - head;
        if (available <= 0) {
            return 0;
        }
        int count = Math.min(available, maxReap);
        long cqes = cqCqesAddr;
        int mask = cqRingMask;
        int shift = cqeShift;
        for (int i = 0; i < count; i++) {
            int index = (head + i) & mask;
            long address = cqes + ((long) index << shift);
            long userData = ZeroGcCqe.getUserData(address);
            int res = ZeroGcCqe.getRes(address);
            int flags = ZeroGcCqe.getFlags(address);
            consumer.accept(userData, res, flags);
        }
        VH_INT.setRelease(GLOBAL_MEMORY, cqKheadAddr, head + count);
        return count;
    }

    List<Result> peekForBatchResult(int batchSize) {
        int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
        int available = tail - head;
        if (available <= 0) {
            return List.of();
        }
        int count = Math.min(available, batchSize);
        long cqes = cqCqesAddr;
        int mask = cqRingMask;
        int shift = cqeShift;
        List<Result> ret = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = (head + i) & mask;
            long address = cqes + ((long) index << shift);
            long userData = ZeroGcCqe.getUserData(address);
            int res = ZeroGcCqe.getRes(address);
            int flags = ZeroGcCqe.getFlags(address);

            Result r = new Result();
            r.setRegisteredBuffers(this.registeredBuffers);
            populateResultFromCqe(r, userData, res, flags);
            ret.add(r);
        }
        VH_INT.setRelease(GLOBAL_MEMORY, cqKheadAddr, head + count);
        return ret;
    }

    List<Result> waitForBatchResult(int batchSize) {
        int status = waitCqeNr(ring, cqePtrPtr, batchSize);
        if (status < 0) {
            status = waitCqeNr(ring, cqePtrPtr, batchSize);
            if (status < 0) {
                status = waitCqeNr(ring, cqePtrPtr, batchSize);
                if (status < 0) {
                    throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(-status));
                }
            }
        }
        return peekForBatchResult(batchSize);
    }

    int waitForBatchResult(int minComplete, ResultConsumer consumer) {
        return waitForBatchResult(minComplete, Integer.MAX_VALUE, consumer);
    }

    int waitForBatchResult(int minComplete, int maxReap, ResultConsumer consumer) {
        while (true) {
            int cqHead = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
            int cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            int available = cqTail - cqHead;
            if (available >= minComplete) {
                return reapAvailable(cqHead, available, maxReap, consumer);
            }

            if (COUNT_ENTERS) {
                enterCount++;
            }
            if (this.enterFlagsBase != 0 && Thread.currentThread() != this.registeredThread) {
                fallbackOrReRegisterRingFd();
            }
            long res = ioUringEnter.ioUringEnter(this.callState, enterRingFd, 0, minComplete, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 9 /* EBADF */ && this.enterFlagsBase != 0) {
                    fallbackOrReRegisterRingFd();
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    continue;
                }
                throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(err));
            }
        }
    }

    int submitAndWaitForBatchResult(int minComplete, int maxReap, ResultConsumer consumer) {
        int tail = sqeTail;
        int head = (int) VH_INT.get(GLOBAL_MEMORY, sqKheadAddr);
        int toSubmit = tail - head;
        if (toSubmit > 0) {
            VH_INT.setRelease(GLOBAL_MEMORY, sqKtailAddr, tail);
            sqeHead = tail;
        } else {
            toSubmit = 0;
        }

        boolean sqpoll = this.isSqPoll;
        while (true) {
            int cqHead = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
            int cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            int available = cqTail - cqHead;
            if (available >= minComplete && toSubmit == 0) {
                return reapAvailable(cqHead, available, maxReap, consumer);
            }

            int enterFlags = (minComplete > 0 && available < minComplete) ? IORING_ENTER_GETEVENTS : 0;
            boolean needEnter = (enterFlags != 0);
            if (toSubmit > 0) {
                if (sqpoll) {
                    int sqFlags = (int) VH_INT.getVolatile(GLOBAL_MEMORY, sqKflagsAddr);
                    if ((sqFlags & IORING_SQ_NEED_WAKEUP) != 0) {
                        enterFlags |= IORING_ENTER_SQ_WAKEUP;
                        needEnter = true;
                    }
                } else {
                    needEnter = true;
                }
            }

            if (!needEnter) {
                return reapAvailable(cqHead, available, maxReap, consumer);
            }

            if (COUNT_ENTERS) {
                enterCount++;
            }
            if (this.enterFlagsBase != 0 && Thread.currentThread() != this.registeredThread) {
                fallbackOrReRegisterRingFd();
            }
            int waitEvents = (enterFlags & IORING_ENTER_GETEVENTS) != 0 ? minComplete : 0;
            long res = ioUringEnter.ioUringEnter(this.callState, enterRingFd, toSubmit, waitEvents, enterFlags | enterFlagsBase, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 9 /* EBADF */ && this.enterFlagsBase != 0) {
                    fallbackOrReRegisterRingFd();
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    tail = sqeTail;
                    head = (int) VH_INT.get(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    continue;
                }
                throw new RuntimeException("Failed in submitAndWaitForBatchResult: " + libCDispatcher.strerror(err));
            }
            toSubmit = 0;
            if (minComplete <= 0) {
                cqHead = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
                cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
                return reapAvailable(cqHead, cqTail - cqHead, maxReap, consumer);
            }
        }
    }

    Result waitForResult() {
        return waitForResult(new Result());
    }

    Result waitForResult(Result target) {
        int ret = waitCqe(ring, cqePtr);
        if (ret < 0) {
            throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(-ret));
        }

        long cqeAddress = cqePtr.getAtIndex(JAVA_LONG, 0);

        long userData = ZeroGcCqe.getUserData(cqeAddress);
        int res = ZeroGcCqe.getRes(cqeAddress);
        int flags = ZeroGcCqe.getFlags(cqeAddress);

        target.setRegisteredBuffers(this.registeredBuffers);
        populateResultFromCqe(target, userData, res, flags);
        cqAdvance(ring, 1);
        return target;
    }

    /**
     * Populates a mutable {@link Result} instance directly from reaped CQE fields.
     * <p>
     * <b>Why Primitive Type-Index Switching (Sequel Cycle 1):</b>
     * Previously, this method resolved {@code ZeroGcUserData.getType(userData)} followed by {@code switch(enumType)}.
     * This required two heap array dereferences per CQE: {@code OperationType.valueOf(index)} array lookup and javac's
     * synthetic {@code $SwitchMap$OperationType} array lookup.
     * Switching directly on the extracted primitive integer {@code typeIndex} compiles into a single-instruction
     * register {@code tableswitch} in C2 JIT with zero memory lookups.
     * <p>
     * <b>Lazy Buffer Materialization:</b>
     * For {@code READ_FIXED}, the dispatcher passes the raw 64-bit {@code regBufAddr} and leaves the
     * {@link MemorySegment} as {@code null}. This avoids touching the {@code registeredBuffersArray} heap object array
     * on the critical path. The {@link MemorySegment} is only materialized on demand if the caller explicitly calls
     * {@link Result#buffer()}.
     */
    void populateResultFromCqe(Result target, long userData, long result, int cqeFlags) {
        int typeIndex = (int) ((userData >>> ZeroGcUserData.TYPE_SHIFT) & ZeroGcUserData.TYPE_MASK);

        switch (typeIndex) {
            case 17 /* READ_FIXED */ -> {
                int bufferIdx = ZeroGcUserData.getBufferIndex(userData);
                long regBufAddr = (registeredBufferAddresses != null && bufferIdx >= 0 && bufferIdx < registeredBufferAddresses.length)
                        ? registeredBufferAddresses[bufferIdx] : 0L;
                target.set(userData, result, OperationType.READ_FIXED, regBufAddr, null, bufferIdx, null, cqeFlags);
            }
            case 2 /* WRITE_FIXED */ -> {
                int bufferIdx = ZeroGcUserData.getBufferIndex(userData);
                target.set(userData, result, OperationType.WRITE_FIXED, 0L, null, bufferIdx, null, cqeFlags);
            }
            case 0 /* READ */, 18 /* READ_POOLED */ -> {
                long bufferAddr = ZeroGcUserData.getPayload(userData);
                target.set(userData, result, OperationType.valueOf(typeIndex), bufferAddr, null, -1, null, cqeFlags);
            }
            case 1 /* WRITE */ -> {
                long buffAddr = ZeroGcUserData.getPayload(userData);
                if (buffAddr != 0L) {
                    libCDispatcher.free(buffAddr);
                }
                target.set(userData, result, OperationType.WRITE, 0L, null, -1, null, cqeFlags);
            }
            case 3 /* OPEN */ -> {
                long pathAddr = ZeroGcUserData.getPayload(userData);
                if (pathAddr != 0L) {
                    libCDispatcher.free(pathAddr);
                }
                target.set(userData, result, OperationType.OPEN, 0L, null, -1, null, cqeFlags);
            }
            case 4 /* CLOSE */ -> {
                target.set(userData, result, OperationType.CLOSE, 0L, null, -1, null, cqeFlags);
            }
            case 5 /* READV */, 7 /* READV_FIXED */ -> {
                long blockAddr = ZeroGcUserData.getPayload(userData);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);

                MemorySegment[] buffers = new MemorySegment[count];
                long remaining = result > 0 ? result : 0L;
                for (int i = 0; i < count; i++) {
                    long base = ZeroGcIovecBlock.getIovBase(blockAddr, i);
                    long len = ZeroGcIovecBlock.getIovLen(blockAddr, i);
                    long valid = Math.min(len, remaining);
                    remaining -= valid;
                    MemorySegment seg = MemorySegment.ofAddress(base).reinterpret(len + 1);
                    if (typeIndex == 5 /* READV */ || valid < len || (len & 511L) != 0) {
                        seg.set(JAVA_BYTE, valid, (byte) 0);
                    }
                    buffers[i] = seg;
                }
                iovecBlockPool.checkIn(blockAddr);
                target.set(userData, result, OperationType.valueOf(typeIndex), 0L, null, -1, buffers, cqeFlags);
            }
            case 6 /* WRITEV */ -> {
                long blockAddr = ZeroGcUserData.getPayload(userData);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);
                for (int i = 0; i < count; i++) {
                    libCDispatcher.free(ZeroGcIovecBlock.getIovBase(blockAddr, i));
                }
                iovecBlockPool.checkIn(blockAddr);
                target.set(userData, result, OperationType.WRITEV, 0L, null, -1, null, cqeFlags);
            }
            case 8 /* WRITEV_FIXED */ -> {
                long blockAddr = ZeroGcUserData.getPayload(userData);
                iovecBlockPool.checkIn(blockAddr);
                target.set(userData, result, OperationType.WRITEV_FIXED, 0L, null, -1, null, cqeFlags);
            }
            case 9 /* ACCEPT */, 15 /* MULTISHOT_ACCEPT */ -> {
                target.set(userData, result, OperationType.valueOf(typeIndex), 0L, null, -1, null, cqeFlags);
            }
            case 10 /* CONNECT */ -> {
                long connectAddr = ZeroGcUserData.getPayload(userData);
                if (connectAddr != 0L) {
                    libCDispatcher.free(connectAddr);
                }
                target.set(userData, result, OperationType.CONNECT, 0L, null, -1, null, cqeFlags);
            }
            case 11 /* RECV */, 14 /* RECV_EXT */ -> {
                long bufferAddr = ZeroGcUserData.getPayload(userData);
                target.set(userData, result, OperationType.valueOf(typeIndex), bufferAddr, null, -1, null, cqeFlags);
            }
            case 12 /* SEND */ -> {
                long sendBuf = ZeroGcUserData.getPayload(userData);
                if (sendBuf != 0L) {
                    libCDispatcher.free(sendBuf);
                }
                target.set(userData, result, OperationType.SEND, 0L, null, -1, null, cqeFlags);
            }
            case 13 /* SEND_EXT */ -> {
                target.set(userData, result, OperationType.SEND_EXT, 0L, null, -1, null, cqeFlags);
            }
            case 16 /* CANCEL */ -> {
                target.set(userData, result, OperationType.CANCEL, 0L, null, -1, null, cqeFlags);
            }
            case 19 /* NOP */ -> {
                target.set(userData, result, OperationType.NOP, 0L, null, -1, null, cqeFlags);
            }
            case ZeroGcUserData.TYPE_WRITE_EXT /* 20: WRITE (external MemorySegment) */ -> {
                target.set(userData, result, OperationType.WRITE, 0L, null, -1, null, cqeFlags);
            }
        }
    }

    void closeRing() {
        queueExit(ring);
    }

    void closeArena() {
        NativeDispatcher.C.free(ring);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeRing();
        iovecBlockPool.close();
        libCDispatcher.free(cqePtr);
        libCDispatcher.free(cqePtrPtr);
        closeArena();
        arena.close();
    }
}
