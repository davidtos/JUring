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

record IoUringDispatcher(Arena arena,
                         UserDataPool userDataPool,
                         IovecBlockPool iovecBlockPool,
                         MemorySegment ring,
                         MemorySegment cqePtr,
                         MemorySegment cqePtrPtr,
                         MemorySegment callState,
                         IoUringEnter ioUringEnter,
                         IoUringRegister ioUringRegister) implements AutoCloseable {

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
    private static final GroupLayout io_uring_cqe_layout;

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

        io_uring_cqe_layout = MemoryLayout.structLayout(
                JAVA_LONG.withName("user_data"),
                JAVA_INT.withName("res"),
                JAVA_INT.withName("flags"),
                MemoryLayout.sequenceLayout(0, JAVA_LONG).withName("big_cqe")
        ).withName("io_uring_cqe");

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
                new UserDataPool(queueDepth),
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

    long allocateUserData(long id, int fd, OperationType type, MemorySegment buffer) {
        long address = userDataPool.checkOut();
        ZeroGcUserData.write(address, id, fd, type, buffer);
        return address;
    }

    long allocateUserDataFixed(long id, int fd, OperationType type, MemorySegment buffer, int bufferIndex) {
        long address = userDataPool.checkOut();
        ZeroGcUserData.write(address, id, fd, type, buffer, bufferIndex);
        return address;
    }

    long allocateUserData(long id, int fd, OperationType type, long buffer) {
        long address = userDataPool.checkOut();
        ZeroGcUserData.write(address, id, fd, type, buffer);
        return address;
    }

    long allocateIovecBlock() {
        return iovecBlockPool.checkOut();
    }

    void releaseIovecBlock(long address) {
        iovecBlockPool.checkIn(address);
    }

    MemorySegment getSqe() {
        long khead_addr = ((MemorySegment) sq_khead_handle.get(ring, 0L)).address();
        int head = (int) VH_INT.get(GLOBAL_MEMORY, khead_addr);
        int tail = (int) sq_sqe_tail_handle.get(ring, 0L);
        int ring_entries = (int) sq_ring_entries_handle.get(ring, 0L);

        if (tail - head >= ring_entries) {
            return null;
        }

        int ring_mask = (int) sq_ring_mask_handle.get(ring, 0L);
        long sqes_addr = ((MemorySegment) sq_sqes_handle.get(ring, 0L)).address();
        int index = tail & ring_mask;
        long sqe_addr = sqes_addr + (long) index * 64L;

        sq_sqe_tail_handle.set(ring, 0L, tail + 1);

        MemorySegment sqe = MemorySegment.ofAddress(sqe_addr).reinterpret(64L);
        sqe.fill((byte) 0);
        return sqe;
    }

    void setSqeFlag(MemorySegment sqe, SqeOptions... flags) {
        setSqeFlag(sqe, SqeOptions.combineOptions(flags));
    }

    void setSqeFlag(MemorySegment sqe, byte flags) {
        byte current = sqe.get(JAVA_BYTE, 1L);
        sqe.set(JAVA_BYTE, 1L, (byte) (current | flags));
    }

    void setUserData(MemorySegment sqe, long userData) {
        sqe.set(JAVA_LONG, 32L, userData);
    }

    void prepareOpenAt(MemorySegment sqe, MemorySegment filePath, int flags, int mode) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_OPENAT);
        sqe.set(JAVA_INT, 4L, AT_FDCWD.value);
        sqe.set(JAVA_LONG, 8L, 0L);
        sqe.set(JAVA_LONG, 16L, filePath.address());
        sqe.set(JAVA_INT, 24L, mode);
        sqe.set(JAVA_INT, 28L, flags);
    }

    void prepareOpenDirectAt(MemorySegment sqe, MemorySegment filePath, int flags, int mode, int fileIndex) {
        prepareOpenAt(sqe, filePath, flags, mode);
        sqe.set(JAVA_INT, 44L, fileIndex + 1);
    }

    void prepareClose(MemorySegment sqe, int fd) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_CLOSE);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, 0L);
        sqe.set(JAVA_LONG, 16L, 0L);
        sqe.set(JAVA_INT, 24L, 0);
    }

    void prepareCloseDirect(MemorySegment sqe, int fileIndex) {
        prepareClose(sqe, 0);
        sqe.set(JAVA_INT, 44L, fileIndex + 1);
    }

    void prepareRead(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_READ);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, buffer.address());
        sqe.set(JAVA_INT, 24L, (int) buffer.byteSize());
    }

    void prepareRead(MemorySegment sqe, int fd, long buffer, long size, long offset) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_READ);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, buffer);
        sqe.set(JAVA_INT, 24L, (int) size);
    }

    void prepareReadFixed(MemorySegment sqe, int fd, MemorySegment buffer, long nbytes, long offset, int bufferIndex) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_READ_FIXED);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, buffer.address());
        sqe.set(JAVA_INT, 24L, (int) nbytes);
        sqe.set(JAVA_SHORT, 40L, (short) bufferIndex);
    }

    void prepareWrite(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_WRITE);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, buffer.address());
        sqe.set(JAVA_INT, 24L, (int) buffer.byteSize());
    }

    void prepareWriteFixed(MemorySegment sqe, int fd, MemorySegment buffer, long nbytes, long offset, int bufferIndex) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_WRITE_FIXED);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, buffer.address());
        sqe.set(JAVA_INT, 24L, (int) nbytes);
        sqe.set(JAVA_SHORT, 40L, (short) bufferIndex);
    }

    void prepareReadv(MemorySegment sqe, int fd, MemorySegment iovecs, int nrVecs, long offset) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_READV);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, iovecs.address());
        sqe.set(JAVA_INT, 24L, nrVecs);
    }

    void prepareWritev(MemorySegment sqe, int fd, MemorySegment iovecs, int nrVecs, long offset) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_WRITEV);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, iovecs.address());
        sqe.set(JAVA_INT, 24L, nrVecs);
    }

    void prepareReadvAddress(MemorySegment sqe, int fd, long iovecAddr, int nrVecs, long offset) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_READV);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, iovecAddr);
        sqe.set(JAVA_INT, 24L, nrVecs);
    }

    void prepareWritevAddress(MemorySegment sqe, int fd, long iovecAddr, int nrVecs, long offset) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_WRITEV);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, offset);
        sqe.set(JAVA_LONG, 16L, iovecAddr);
        sqe.set(JAVA_INT, 24L, nrVecs);
    }

    void prepareAccept(MemorySegment sqe, int fd, MemorySegment addr, MemorySegment addrLen, int flags) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_ACCEPT);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, addrLen != null && !addrLen.equals(MemorySegment.NULL) ? addrLen.address() : 0L);
        sqe.set(JAVA_LONG, 16L, addr != null && !addr.equals(MemorySegment.NULL) ? addr.address() : 0L);
        sqe.set(JAVA_INT, 24L, 0);
        sqe.set(JAVA_INT, 28L, flags);
    }

    void prepareMultishotAccept(MemorySegment sqe, int fd, MemorySegment addr, MemorySegment addrLen, int flags) {
        prepareAccept(sqe, fd, addr, addrLen, flags);
        short prio = sqe.get(JAVA_SHORT, 2L);
        sqe.set(JAVA_SHORT, 2L, (short) (prio | IORING_ACCEPT_MULTISHOT));
    }

    void prepareConnect(MemorySegment sqe, int fd, MemorySegment addr, int addrLen) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_CONNECT);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, (long) addrLen);
        sqe.set(JAVA_LONG, 16L, addr.address());
        sqe.set(JAVA_INT, 24L, 0);
    }

    void prepareRecv(MemorySegment sqe, int fd, MemorySegment buf, long len, int flags) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_RECV);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, 0L);
        sqe.set(JAVA_LONG, 16L, buf.address());
        sqe.set(JAVA_INT, 24L, (int) len);
        sqe.set(JAVA_INT, 28L, flags);
    }

    void prepareSend(MemorySegment sqe, int fd, MemorySegment buf, long len, int flags) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_SEND);
        sqe.set(JAVA_INT, 4L, fd);
        sqe.set(JAVA_LONG, 8L, 0L);
        sqe.set(JAVA_LONG, 16L, buf.address());
        sqe.set(JAVA_INT, 24L, (int) len);
        sqe.set(JAVA_INT, 28L, flags);
    }

    void prepareCancel(MemorySegment sqe, long userDataToCancel, int flags) {
        sqe.set(JAVA_BYTE, 0L, IORING_OP_ASYNC_CANCEL);
        sqe.set(JAVA_INT, 4L, -1);
        sqe.set(JAVA_LONG, 8L, 0L);
        sqe.set(JAVA_LONG, 16L, userDataToCancel);
        sqe.set(JAVA_INT, 24L, 0);
        sqe.set(JAVA_INT, 28L, flags);
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
        return iovecStructure.buffers();
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

    List<Result> peekForBatchResult(int batchSize) {
        int count = peekBatchCqe(ring, cqePtrPtr, batchSize);

        if (count > 0) {
            List<Result> ret = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                long address = cqePtrPtr.getAtIndex(JAVA_LONG, i);

                long userData = ZeroGcCqe.getUserData(address);
                int res = ZeroGcCqe.getRes(address);
                int cqeFlags = ZeroGcCqe.getFlags(address);

                ret.add(getResultFromCqe(userData, res, cqeFlags));
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
        int count = peekBatchCqe(ring, cqePtrPtr, batchSize);

        List<Result> ret = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            long address = cqePtrPtr.getAtIndex(JAVA_LONG, i);
            long userData = ZeroGcCqe.getUserData(address);
            int res = ZeroGcCqe.getRes(address);
            int cqeFlags = ZeroGcCqe.getFlags(address);

            ret.add(getResultFromCqe(userData, res, cqeFlags));
        }

        cqAdvance(ring, count);
        return ret;
    }

    Result waitForResult() {
        int ret = waitCqe(ring, cqePtr);
        if (ret < 0) {
            throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(ret));
        }

        long cqeAddress = cqePtr.getAtIndex(JAVA_LONG, 0);

        long userData = ZeroGcCqe.getUserData(cqeAddress);
        int res = ZeroGcCqe.getRes(cqeAddress);
        int cqeFlags = ZeroGcCqe.getFlags(cqeAddress);

        Result result = getResultFromCqe(userData, res, cqeFlags);
        cqAdvance(ring, 1);
        return result;
    }

    private Result getResultFromCqe(long userDataAddress, long result, int cqeFlags) {
        OperationType type = ZeroGcUserData.getType(userDataAddress);
        long id = ZeroGcUserData.getId(userDataAddress);

        return switch (type) {
            case READ_FIXED -> {
                MemorySegment buffer = ZeroGcUserData.getBufferSegment(userDataAddress);
                userDataPool.checkIn(userDataAddress);
                yield new ReadResultFixed(id, buffer, result, ZeroGcUserData.getBufferIndex(userDataAddress));
            }
            case READ_POOLED -> {
                MemorySegment buffer = ZeroGcUserData.getBufferSegment(userDataAddress);
                userDataPool.checkIn(userDataAddress);
                yield new ReadResult(id, buffer, result);
            }
            case READ -> {
                MemorySegment buffer = ZeroGcUserData.getBufferSegment(userDataAddress);
                userDataPool.checkIn(userDataAddress);
                yield new ReadResult(id, buffer, result);
            }
            case WRITE -> {
                libCDispatcher.free(ZeroGcUserData.getBufferAddress(userDataAddress));
                userDataPool.checkIn(userDataAddress);
                yield new WriteResult(id, result);
            }
            case WRITE_FIXED -> {
                userDataPool.checkIn(userDataAddress);
                yield new WriteResult(id, result);
            }
            case OPEN -> {
                libCDispatcher.free(ZeroGcUserData.getBufferAddress(userDataAddress));
                userDataPool.checkIn(userDataAddress);
                yield new OpenResult(id, (int) result);
            }
            case CLOSE -> {
                userDataPool.checkIn(userDataAddress);
                yield new CloseResult(id, (int) result);
            }
            case READV -> {
                long blockAddr = ZeroGcUserData.getBufferAddress(userDataAddress);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);
                MemorySegment[] buffers = new MemorySegment[count];
                for (int i = 0; i < count; i++) {
                    buffers[i] = ZeroGcIovecBlock.getIovBaseSegment(blockAddr, i);
                }
                iovecBlockPool.checkIn(blockAddr);
                userDataPool.checkIn(userDataAddress);
                yield new ReadvResult(id, buffers, result);
            }
            case WRITEV -> {
                long blockAddr = ZeroGcUserData.getBufferAddress(userDataAddress);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);
                for (int i = 0; i < count; i++) {
                    libCDispatcher.free(ZeroGcIovecBlock.getIovBase(blockAddr, i));
                }
                iovecBlockPool.checkIn(blockAddr);
                userDataPool.checkIn(userDataAddress);
                yield new WriteResult(id, result);
            }
            case READV_FIXED -> {
                long blockAddr = ZeroGcUserData.getBufferAddress(userDataAddress);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);
                MemorySegment[] buffers = new MemorySegment[count];
                for (int i = 0; i < count; i++) {
                    buffers[i] = ZeroGcIovecBlock.getIovBaseSegment(blockAddr, i);
                }
                iovecBlockPool.checkIn(blockAddr);
                userDataPool.checkIn(userDataAddress);
                yield new ReadvResult(id, buffers, result);
            }
            case WRITEV_FIXED -> {
                long blockAddr = ZeroGcUserData.getBufferAddress(userDataAddress);
                iovecBlockPool.checkIn(blockAddr);
                userDataPool.checkIn(userDataAddress);
                yield new WriteResult(id, result);
            }
            case ACCEPT -> {
                long addrAddress = ZeroGcUserData.getBufferAddress(userDataAddress);
                if (addrAddress != 0L) {
                    libCDispatcher.free(addrAddress);
                }
                userDataPool.checkIn(userDataAddress);
                yield new AcceptResult(id, (int) result);
            }
            case MULTISHOT_ACCEPT -> {
                boolean morecoming = (cqeFlags & IORING_CQE_F_MORE) != 0;
                if (!morecoming) {
                    long addrAddress = ZeroGcUserData.getBufferAddress(userDataAddress);
                    if (addrAddress != 0L) {
                        libCDispatcher.free(addrAddress);
                    }
                    userDataPool.checkIn(userDataAddress);
                }
                yield new AcceptResult(id, (int) result);
            }
            case CONNECT -> {
                long connectAddrAddress = ZeroGcUserData.getBufferAddress(userDataAddress);
                if (connectAddrAddress != 0L) {
                    libCDispatcher.free(connectAddrAddress);
                }
                userDataPool.checkIn(userDataAddress);
                yield new ConnectResult(id, (int) result);
            }
            case RECV -> {
                MemorySegment buffer = ZeroGcUserData.getBufferSegment(userDataAddress);
                userDataPool.checkIn(userDataAddress);
                yield new RecvResult(id, buffer, result);
            }
            case RECV_EXT -> {
                MemorySegment buffer = ZeroGcUserData.getBufferSegment(userDataAddress);
                userDataPool.checkIn(userDataAddress);
                yield new RecvResult(id, buffer, result);
            }
            case SEND -> {
                libCDispatcher.free(ZeroGcUserData.getBufferAddress(userDataAddress));
                userDataPool.checkIn(userDataAddress);
                yield new SendResult(id, result);
            }
            case SEND_EXT -> {
                userDataPool.checkIn(userDataAddress);
                yield new SendResult(id, result);
            }
            case CANCEL -> {
                userDataPool.checkIn(userDataAddress);
                yield new CloseResult(id, (int) result);
            }
        };
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
        userDataPool.close();
        iovecBlockPool.close();
        libCDispatcher.free(cqePtr);
        libCDispatcher.free(cqePtrPtr);
        closeArena();
        arena.close();
    }
}
