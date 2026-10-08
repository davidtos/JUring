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
    private static final byte IORING_OP_NOP = 0;
    private static final byte IORING_OP_READV = 1;
    private static final byte IORING_OP_WRITEV = 2;
    private static final byte IORING_OP_READ_FIXED = 4;
    private static final byte IORING_OP_WRITE_FIXED = 5;
    private static final byte IORING_OP_ACCEPT = 13;
    private static final byte IORING_OP_ASYNC_CANCEL = 14;
    private static final byte IORING_OP_CONNECT = 16;
    private static final byte IORING_OP_OPENAT = 18;
    private static final byte IORING_OP_CLOSE = 19;
    private static final byte IORING_OP_READ = 22;
    private static final byte IORING_OP_WRITE = 23;
    private static final byte IORING_OP_SEND = 26;
    private static final byte IORING_OP_RECV = 27;

    // Enter flags
    private static final int IORING_ENTER_GETEVENTS = 1;
    private static final int IORING_ENTER_SQ_WAKEUP = 2;

    // Ring flags
    private static final int IORING_SQ_NEED_WAKEUP = 1;

    private static final short IORING_ACCEPT_MULTISHOT = 1;

    private static final AddressLayout C_POINTER = ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));
    private static final LibCDispatcher libCDispatcher = NativeDispatcher.C;

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

    private final Arena arena;
    private final IovecBlockPool iovecBlockPool;
    private final MemorySegment ring;
    private final MemorySegment cqePtr;
    private final MemorySegment cqePtrPtr;
    private final MemorySegment callState;
    private final IoUringEnter ioUringEnter;
    private final IoUringRegister ioUringRegister;
    private final Result reusableResult = new Result();
    private MemorySegment[] registeredBuffers;

    static {
        io_uring_sq_layout = MemoryLayout.structLayout(
                C_POINTER.withName("khead"),
                C_POINTER.withName("ktail"),
                C_POINTER.withName("kring_mask"),
                C_POINTER.withName("kring_entries"),
                C_POINTER.withName("kflags"),
                C_POINTER.withName("kdropped"),
                C_POINTER.withName("array"),
                C_POINTER.withName("sqes"),
                JAVA_INT.withName("sqe_head"),
                JAVA_INT.withName("sqe_tail"),
                JAVA_LONG.withName("ring_sz"),
                C_POINTER.withName("ring_ptr"),
                JAVA_INT.withName("ring_mask"),
                JAVA_INT.withName("ring_entries"),
                MemoryLayout.sequenceLayout(2, JAVA_INT).withName("pad")
        ).withName("io_uring_sq");

        io_uring_cq_layout = MemoryLayout.structLayout(
                C_POINTER.withName("khead"),
                C_POINTER.withName("ktail"),
                C_POINTER.withName("kring_mask"),
                C_POINTER.withName("kring_entries"),
                C_POINTER.withName("kflags"),
                C_POINTER.withName("koverflow"),
                C_POINTER.withName("cqes"),
                JAVA_LONG.withName("ring_sz"),
                C_POINTER.withName("ring_ptr"),
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
                            MemorySegment callState,
                            IoUringEnter ioUringEnter,
                            IoUringRegister ioUringRegister) {
        this.arena = arena;
        this.iovecBlockPool = iovecBlockPool;
        this.ring = ring;
        this.cqePtr = cqePtr;
        this.cqePtrPtr = cqePtrPtr;
        this.callState = callState;
        this.ioUringEnter = ioUringEnter;
        this.ioUringRegister = ioUringRegister;
    }

    public Arena arena() {
        return arena;
    }

    public MemorySegment ring() {
        return ring;
    }

    public void setRegisteredBuffers(MemorySegment[] registeredBuffers) {
        this.registeredBuffers = registeredBuffers;
    }

    static IoUringDispatcher create(int queueDepth, IoUringOptions... ioUringOptions) {
        MemorySegment ring = NativeDispatcher.C.calloc(ring_layout.byteSize());
        IoUringDispatcher dispatcher = getDispatcher(ring, queueDepth);

        int ret = dispatcher.queueInit(queueDepth, IoUringOptions.combineOptions(ioUringOptions));
        if (ret < 0) {
            throw new RuntimeException("Failed to initialize queue " + libCDispatcher.strerror(ret));
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
                callState,
                libCDispatcher.ioUringEnter(),
                libCDispatcher.ioUringRegister()
        );
    }

    public IoUringDispatcher getSharedWorkerRing(int queueDepth, IoUringOptions... ioUringOptions) {
        MemorySegment ring = NativeDispatcher.C.calloc(ring_layout.byteSize());
        IoUringDispatcher dispatcher = getDispatcher(ring, queueDepth);
        MemorySegment params = NativeDispatcher.C.calloc(io_uring_params.layout().byteSize());

        int ring_fd = (int) ringFdHandle.get(this.ring, 0L);

        var result = IoUringOptions.combineOptions(ioUringOptions);

        io_uring_params.flags(params, result | IORING_SETUP_ATTACH_WQ.value);
        io_uring_params.wq_fd(params, ring_fd);

        var ret = dispatcher.queueInitParams(queueDepth, ring, params);
        if (ret < 0) {
            throw new RuntimeException("ret = " + ret + " " + NativeDispatcher.C.strerror(ret));
        }

        return dispatcher;
    }

    long allocateIovecBlock() {
        return iovecBlockPool.checkOut();
    }

    void releaseIovecBlock(long address) {
        iovecBlockPool.checkIn(address);
    }

    /**
     * Gets the next available SQE address without allocating any MemorySegment objects.
     * Returns 0L if the SQ queue is currently full.
     */
    long getSqe() {
        long khead_addr = ((MemorySegment) sq_khead_handle.get(ring, 0L)).address();
        int head = (int) VH_INT.get(GLOBAL_MEMORY, khead_addr);
        int tail = (int) sq_sqe_tail_handle.get(ring, 0L);
        int ring_entries = (int) sq_ring_entries_handle.get(ring, 0L);

        if (tail - head >= ring_entries) {
            return 0L;
        }

        int ring_mask = (int) sq_ring_mask_handle.get(ring, 0L);
        long sqes_addr = ((MemorySegment) sq_sqes_handle.get(ring, 0L)).address();
        int index = tail & ring_mask;
        long sqe_addr = sqes_addr + (long) index * 64L;

        sq_sqe_tail_handle.set(ring, 0L, tail + 1);

        ZeroGcSqe.zero(sqe_addr);
        return sqe_addr;
    }

    void setSqeFlag(long sqe, SqeOptions... flags) {
        setSqeFlag(sqe, SqeOptions.combineOptions(flags));
    }

    void setSqeFlag(long sqe, byte flags) {
        ZeroGcSqe.addFlags(sqe, flags);
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

    void submit() {
        long khead_addr = ((MemorySegment) sq_khead_handle.get(ring, 0L)).address();
        long ktail_addr = ((MemorySegment) sq_ktail_handle.get(ring, 0L)).address();

        int tail = (int) sq_sqe_tail_handle.get(ring, 0L);
        int head = (int) VH_INT.get(GLOBAL_MEMORY, khead_addr);

        VH_INT.setRelease(GLOBAL_MEMORY, ktail_addr, tail);
        sq_sqe_head_handle.set(ring, 0L, tail);

        int toSubmit = tail - head;
        if (toSubmit <= 0) {
            return;
        }

        int flags = (int) ringFlagsHandle.get(ring, 0L);
        boolean sqpoll = (flags & IoUringOptions.IORING_SETUP_SQPOLL.value) != 0;
        int enterFlags = 0;
        boolean needEnter = true;

        if (sqpoll) {
            long kflags_addr = ((MemorySegment) sq_kflags_handle.get(ring, 0L)).address();
            int sqFlags = (int) VH_INT.getVolatile(GLOBAL_MEMORY, kflags_addr);
            if ((sqFlags & IORING_SQ_NEED_WAKEUP) != 0) {
                enterFlags |= IORING_ENTER_SQ_WAKEUP;
            } else {
                needEnter = false;
            }
        }

        if (needEnter) {
            int fd = (int) ringFdHandle.get(ring, 0L);
            long res = this.ioUringEnter.ioUringEnter(this.callState, fd, toSubmit, 0, enterFlags, MemorySegment.NULL, 0L);
            if (res < 0) {
                throw new RuntimeException("Failed to submit queue: " + libCDispatcher.strerror(-LibCDispatcher.errno(this.callState)));
            }
        }
    }

    int waitCqe(MemorySegment ring, MemorySegment cqePtr) {
        long cq_khead_addr = ((MemorySegment) cq_khead_handle.get(ring, 0L)).address();
        long cq_ktail_addr = ((MemorySegment) cq_ktail_handle.get(ring, 0L)).address();
        long cq_cqes_addr = ((MemorySegment) cq_cqes_handle.get(ring, 0L)).address();
        int cq_ring_mask = (int) cq_ring_mask_handle.get(ring, 0L);
        int fd = (int) ringFdHandle.get(ring, 0L);

        while (true) {
            int head = (int) VH_INT.get(GLOBAL_MEMORY, cq_khead_addr);
            int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cq_ktail_addr);
            if (head != tail) {
                int index = head & cq_ring_mask;
                long cqeAddr = cq_cqes_addr + (long) index * 16L;
                cqePtr.setAtIndex(JAVA_LONG, 0, cqeAddr);
                return 0;
            }

            long res = this.ioUringEnter.ioUringEnter(this.callState, fd, 0, 1, IORING_ENTER_GETEVENTS, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 4 /* EINTR */) {
                    continue;
                }
                return -err;
            }
        }
    }

    int peekCqe(MemorySegment ring, MemorySegment cqePtr) {
        long cq_khead_addr = ((MemorySegment) cq_khead_handle.get(ring, 0L)).address();
        long cq_ktail_addr = ((MemorySegment) cq_ktail_handle.get(ring, 0L)).address();
        long cq_cqes_addr = ((MemorySegment) cq_cqes_handle.get(ring, 0L)).address();
        int cq_ring_mask = (int) cq_ring_mask_handle.get(ring, 0L);

        int head = (int) VH_INT.get(GLOBAL_MEMORY, cq_khead_addr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cq_ktail_addr);
        if (head != tail) {
            int index = head & cq_ring_mask;
            long cqeAddr = cq_cqes_addr + (long) index * 16L;
            cqePtr.setAtIndex(JAVA_LONG, 0, cqeAddr);
            return 0;
        }
        return -11; // -EAGAIN
    }

    int peekBatchCqe(MemorySegment ring, MemorySegment cqePtrPtr, int batchSize) {
        long cq_khead_addr = ((MemorySegment) cq_khead_handle.get(ring, 0L)).address();
        long cq_ktail_addr = ((MemorySegment) cq_ktail_handle.get(ring, 0L)).address();
        long cq_cqes_addr = ((MemorySegment) cq_cqes_handle.get(ring, 0L)).address();
        int cq_ring_mask = (int) cq_ring_mask_handle.get(ring, 0L);

        int head = (int) VH_INT.get(GLOBAL_MEMORY, cq_khead_addr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cq_ktail_addr);
        int available = tail - head;
        int count = Math.min(available, batchSize);
        for (int i = 0; i < count; i++) {
            int index = (head + i) & cq_ring_mask;
            long cqeAddr = cq_cqes_addr + (long) index * 16L;
            cqePtrPtr.setAtIndex(JAVA_LONG, i, cqeAddr);
        }
        return count;
    }

    void cqeSeen(MemorySegment ring, MemorySegment cqe) {
        cqAdvance(ring, 1);
    }

    void cqAdvance(MemorySegment ring, int count) {
        if (count <= 0) return;
        long cq_khead_addr = ((MemorySegment) cq_khead_handle.get(ring, 0L)).address();
        int head = (int) VH_INT.get(GLOBAL_MEMORY, cq_khead_addr);
        VH_INT.setRelease(GLOBAL_MEMORY, cq_khead_addr, head + count);
    }

    int waitCqeNr(MemorySegment ring, MemorySegment cqePtrPtr, int waitNr) {
        long cq_khead_addr = ((MemorySegment) cq_khead_handle.get(ring, 0L)).address();
        long cq_ktail_addr = ((MemorySegment) cq_ktail_handle.get(ring, 0L)).address();
        int fd = (int) ringFdHandle.get(ring, 0L);

        while (true) {
            int head = (int) VH_INT.get(GLOBAL_MEMORY, cq_khead_addr);
            int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cq_ktail_addr);
            int available = tail - head;
            if (available >= waitNr) {
                return 0;
            }

            long res = this.ioUringEnter.ioUringEnter(this.callState, fd, 0, waitNr, IORING_ENTER_GETEVENTS, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 4 /* EINTR */) {
                    continue;
                }
                return -err;
            }
        }
    }

    int queueInit(int queueDepth, int flags) {
        MemorySegment params = libCDispatcher.calloc(io_uring_params.layout().byteSize());
        io_uring_params.flags(params, flags);
        int ret = queueInitParams(queueDepth, this.ring, params);
        libCDispatcher.free(params);
        return ret;
    }

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

        sq_khead_handle.set(ring, 0L, MemorySegment.ofAddress(sq_khead));
        sq_ktail_handle.set(ring, 0L, MemorySegment.ofAddress(sq_ktail));
        sq_kring_mask_handle.set(ring, 0L, MemorySegment.ofAddress(sq_kring_mask));
        sq_kring_entries_handle.set(ring, 0L, MemorySegment.ofAddress(sq_kring_entries));
        sq_kflags_handle.set(ring, 0L, MemorySegment.ofAddress(sq_kflags));
        sq_kdropped_handle.set(ring, 0L, MemorySegment.ofAddress(sq_kdropped));
        sq_sqes_handle.set(ring, 0L, MemorySegment.ofAddress(sqes_ptr));
        sq_sqe_head_handle.set(ring, 0L, 0);
        sq_sqe_tail_handle.set(ring, 0L, 0);
        sq_ring_sz_handle.set(ring, 0L, sq_ring_sz);
        sq_ring_ptr_handle.set(ring, 0L, MemorySegment.ofAddress(sq_ring_ptr));

        int sq_mask = (int) VH_INT.get(GLOBAL_MEMORY, sq_kring_mask);
        int sq_ents = (int) VH_INT.get(GLOBAL_MEMORY, sq_kring_entries);
        sq_ring_mask_handle.set(ring, 0L, sq_mask);
        sq_ring_entries_handle.set(ring, 0L, sq_ents);

        if ((flags & IoUringOptions.IORING_SETUP_NO_SQARRAY.value) == 0) {
            long sq_array = sq_ring_ptr + io_sqring_offsets.array(sq_off);
            sq_array_handle.set(ring, 0L, MemorySegment.ofAddress(sq_array));
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

        cq_khead_handle.set(ring, 0L, MemorySegment.ofAddress(cq_khead));
        cq_ktail_handle.set(ring, 0L, MemorySegment.ofAddress(cq_ktail));
        cq_kring_mask_handle.set(ring, 0L, MemorySegment.ofAddress(cq_kring_mask));
        cq_kring_entries_handle.set(ring, 0L, MemorySegment.ofAddress(cq_kring_entries));
        cq_koverflow_handle.set(ring, 0L, MemorySegment.ofAddress(cq_koverflow));
        cq_cqes_handle.set(ring, 0L, MemorySegment.ofAddress(cq_cqes_addr));
        cq_ring_sz_handle.set(ring, 0L, cq_ring_sz);
        cq_ring_ptr_handle.set(ring, 0L, MemorySegment.ofAddress(cq_ring_ptr));

        int cq_off_flags = io_cqring_offsets.flags(cq_off);
        if (cq_off_flags != 0) {
            cq_kflags_handle.set(ring, 0L, MemorySegment.ofAddress(cq_ring_ptr + cq_off_flags));
        }

        int cq_mask = (int) VH_INT.get(GLOBAL_MEMORY, cq_kring_mask);
        int cq_ents = (int) VH_INT.get(GLOBAL_MEMORY, cq_kring_entries);
        cq_ring_mask_handle.set(ring, 0L, cq_mask);
        cq_ring_entries_handle.set(ring, 0L, cq_ents);

        return 0;
    }

    void queueExit(MemorySegment ring) {
        int fd = (int) ringFdHandle.get(ring, 0L);
        if (fd < 0) return;

        MemorySegment sqesSeg = (MemorySegment) sq_sqes_handle.get(ring, 0L);
        int sq_entries = (int) sq_ring_entries_handle.get(ring, 0L);
        int flags = (int) ringFlagsHandle.get(ring, 0L);
        int sqe_shift = ((flags & IoUringOptions.IORING_SETUP_SQE128.value) != 0) ? 1 : 0;
        long sqes_sz = ((long) sq_entries << sqe_shift) * 64L;
        if (sqesSeg.address() != 0L) {
            libCDispatcher.munmap(sqesSeg.address(), sqes_sz);
        }

        long sq_ring_ptr = ((MemorySegment) sq_ring_ptr_handle.get(ring, 0L)).address();
        long sq_ring_sz = (long) sq_ring_sz_handle.get(ring, 0L);
        long cq_ring_ptr = ((MemorySegment) cq_ring_ptr_handle.get(ring, 0L)).address();
        long cq_ring_sz = (long) cq_ring_sz_handle.get(ring, 0L);

        if (cq_ring_ptr != 0L && cq_ring_ptr != sq_ring_ptr && cq_ring_sz != 0L) {
            libCDispatcher.munmap(cq_ring_ptr, cq_ring_sz);
        }
        if (sq_ring_ptr != 0L && sq_ring_sz != 0L) {
            libCDispatcher.munmap(sq_ring_ptr, sq_ring_sz);
        }

        libCDispatcher.close(fd);
        ringFdHandle.set(ring, 0L, -1);
    }

    int registerBuffers(MemorySegment ring, MemorySegment iovecs, int nrIovecs) {
        int fd = (int) ringFdHandle.get(ring, 0L);
        long res = this.ioUringRegister.ioUringRegister(this.callState, fd, 0 /* IORING_REGISTER_BUFFERS */, iovecs, nrIovecs);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        return (int) res;
    }

    MemorySegment[] registerBuffers(int bufferSize, int nrIovecs) {
        var iovecStructure = libCDispatcher.allocateIovec(arena, bufferSize, nrIovecs);
        int ret = registerBuffers(ring, iovecStructure.iovecArray(), nrIovecs);
        if (ret < 0) {
            throw new RuntimeException("Failed to register buffers: " + libCDispatcher.strerror(ret));
        }
        this.registeredBuffers = iovecStructure.buffers();
        return this.registeredBuffers;
    }

    int registerFiles(MemorySegment ring, MemorySegment fdArray, int count) {
        int fd = (int) ringFdHandle.get(ring, 0L);
        long res = this.ioUringRegister.ioUringRegister(this.callState, fd, 2 /* IORING_REGISTER_FILES */, fdArray, count);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        return (int) res;
    }

    int registerFiles(int[] fileDescriptors) {
        int count = fileDescriptors.length;
        MemorySegment fdArray = arena.allocate(JAVA_INT.byteSize() * count);

        for (int i = 0; i < count; i++) {
            fdArray.setAtIndex(JAVA_INT, i, fileDescriptors[i]);
        }

        int ret = registerFiles(ring, fdArray, count);
        if (ret < 0) {
            throw new RuntimeException("Failed to register files: " + libCDispatcher.strerror(ret));
        }
        return ret;
    }

    int registerFilesUpdate(MemorySegment ring, int offset, MemorySegment fdArray, int count) {
        int fd = (int) ringFdHandle.get(ring, 0L);
        MemorySegment up = this.arena.allocate(16L);
        up.set(JAVA_INT, 0L, offset);
        up.set(JAVA_INT, 4L, 0);
        up.set(JAVA_LONG, 8L, fdArray.address());
        long res = this.ioUringRegister.ioUringRegister(this.callState, fd, 6 /* IORING_REGISTER_FILES_UPDATE */, up, count);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        return (int) res;
    }

    int registerFilesUpdate(int offset, int[] fileDescriptors) {
        int count = fileDescriptors.length;
        MemorySegment fdArray = arena.allocate(JAVA_INT.byteSize() * count);

        for (int i = 0; i < count; i++) {
            fdArray.setAtIndex(JAVA_INT, i, fileDescriptors[i]);
        }

        int ret = registerFilesUpdate(ring, offset, fdArray, count);
        if (ret < 0) {
            throw new RuntimeException("Failed to update registered files: " + libCDispatcher.strerror(ret));
        }
        return ret;
    }

    void registerIowqMaxWorkers(int bounded, int unbounded) {
        int fd = (int) ringFdHandle.get(ring, 0L);
        MemorySegment values = this.arena.allocate(JAVA_INT, 2);
        values.setAtIndex(JAVA_INT, 0, bounded);
        values.setAtIndex(JAVA_INT, 1, unbounded);
        long res = this.ioUringRegister.ioUringRegister(this.callState, fd, 19 /* IORING_REGISTER_IOWQ_MAX_WORKERS */, values, 2);
        if (res < 0) {
            throw new RuntimeException("Failed to register max workers: " + libCDispatcher.strerror(-LibCDispatcher.errno(this.callState)));
        }
    }

    int peekForBatchResult(int batchSize, ResultConsumer consumer) {
        int count = peekBatchCqe(ring, cqePtrPtr, batchSize);
        if (count > 0) {
            for (int i = 0; i < count; i++) {
                long address = cqePtrPtr.getAtIndex(JAVA_LONG, i);
                long userData = ZeroGcCqe.getUserData(address);
                int res = ZeroGcCqe.getRes(address);
                int cqeFlags = ZeroGcCqe.getFlags(address);

                populateResultFromCqe(reusableResult, userData, res, cqeFlags);
                consumer.accept(reusableResult);
            }
            cqAdvance(ring, count);
            return count;
        }
        return 0;
    }

    List<Result> peekForBatchResult(int batchSize) {
        int count = peekBatchCqe(ring, cqePtrPtr, batchSize);

        if (count > 0) {
            List<Result> ret = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                long address = cqePtrPtr.getAtIndex(JAVA_LONG, i);

                long userData = ZeroGcCqe.getUserData(address);
                int res = ZeroGcCqe.getRes(address);
                int cqeFlags = ZeroGcCqe.getFlags(address);

                Result r = new Result();
                populateResultFromCqe(r, userData, res, cqeFlags);
                ret.add(r);
            }

            cqAdvance(ring, count);
            return ret;
        }
        return List.of();
    }

    List<Result> waitForBatchResult(int batchSize) {
        int status = waitCqeNr(ring, cqePtrPtr, batchSize);
        if (status < 0) {
            status = waitCqeNr(ring, cqePtrPtr, batchSize);
            if (status < 0) {
                status = waitCqeNr(ring, cqePtrPtr, batchSize);
                if (status < 0) {
                    throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(status));
                }
            }
        }
        return peekForBatchResult(batchSize);
    }

    int waitForBatchResult(int batchSize, ResultConsumer consumer) {
        int status = waitCqeNr(ring, cqePtrPtr, batchSize);
        if (status < 0) {
            status = waitCqeNr(ring, cqePtrPtr, batchSize);
            if (status < 0) {
                status = waitCqeNr(ring, cqePtrPtr, batchSize);
                if (status < 0) {
                    throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(status));
                }
            }
        }
        return peekForBatchResult(batchSize, consumer);
    }

    Result waitForResult() {
        return waitForResult(new Result());
    }

    Result waitForResult(Result target) {
        int ret = waitCqe(ring, cqePtr);
        if (ret < 0) {
            throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(ret));
        }

        long cqeAddress = cqePtr.getAtIndex(JAVA_LONG, 0);

        long userData = ZeroGcCqe.getUserData(cqeAddress);
        int res = ZeroGcCqe.getRes(cqeAddress);
        int cqeFlags = ZeroGcCqe.getFlags(cqeAddress);

        populateResultFromCqe(target, userData, res, cqeFlags);
        cqAdvance(ring, 1);
        return target;
    }

    void populateResultFromCqe(Result target, long userData, long result, int cqeFlags) {
        OperationType type = ZeroGcUserData.getType(userData);

        switch (type) {
            case READ_FIXED -> {
                int bufferIdx = ZeroGcUserData.getBufferIndex(userData);
                MemorySegment regBuf = (registeredBuffers != null && bufferIdx >= 0 && bufferIdx < registeredBuffers.length)
                        ? registeredBuffers[bufferIdx] : null;
                target.set(userData, result, type, regBuf != null ? regBuf.address() : 0L, regBuf, bufferIdx, null);
            }
            case READ, READ_POOLED -> {
                long bufferAddr = ZeroGcUserData.getPayload(userData);
                target.set(userData, result, type, bufferAddr, null, -1, null);
            }
            case WRITE -> {
                long buffAddr = ZeroGcUserData.getPayload(userData);
                if (buffAddr != 0L) {
                    libCDispatcher.free(buffAddr);
                }
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case WRITE_FIXED -> {
                int bufferIdx = ZeroGcUserData.getBufferIndex(userData);
                target.set(userData, result, type, 0L, null, bufferIdx, null);
            }
            case OPEN -> {
                long pathAddr = ZeroGcUserData.getPayload(userData);
                if (pathAddr != 0L) {
                    libCDispatcher.free(pathAddr);
                }
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case CLOSE -> {
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case READV, READV_FIXED -> {
                long blockAddr = ZeroGcUserData.getPayload(userData);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);

                MemorySegment[] buffers = new MemorySegment[count];
                for (int i = 0; i < count; i++) {
                    long base = ZeroGcIovecBlock.getIovBase(blockAddr, i);
                    buffers[i] = MemorySegment.ofAddress(base).reinterpret(Long.MAX_VALUE);
                }
                iovecBlockPool.checkIn(blockAddr);
                target.set(userData, result, type, 0L, null, -1, buffers);
            }
            case WRITEV -> {
                long blockAddr = ZeroGcUserData.getPayload(userData);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);
                for (int i = 0; i < count; i++) {
                    libCDispatcher.free(ZeroGcIovecBlock.getIovBase(blockAddr, i));
                }
                iovecBlockPool.checkIn(blockAddr);
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case WRITEV_FIXED -> {
                long blockAddr = ZeroGcUserData.getPayload(userData);
                iovecBlockPool.checkIn(blockAddr);
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case ACCEPT, MULTISHOT_ACCEPT -> {
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case CONNECT -> {
                long connectAddr = ZeroGcUserData.getPayload(userData);
                if (connectAddr != 0L) {
                    libCDispatcher.free(connectAddr);
                }
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case RECV, RECV_EXT -> {
                long bufferAddr = ZeroGcUserData.getPayload(userData);
                target.set(userData, result, type, bufferAddr, null, -1, null);
            }
            case SEND -> {
                long sendBuf = ZeroGcUserData.getPayload(userData);
                if (sendBuf != 0L) {
                    libCDispatcher.free(sendBuf);
                }
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case SEND_EXT -> {
                target.set(userData, result, type, 0L, null, -1, null);
            }
            case CANCEL -> {
                target.set(userData, result, type, 0L, null, -1, null);
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
        closeRing();
        iovecBlockPool.close();
        libCDispatcher.free(cqePtr);
        libCDispatcher.free(cqePtrPtr);
        closeArena();
        arena.close();
    }
}
