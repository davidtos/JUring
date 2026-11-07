package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_ATTACH_WQ;
import static java.lang.foreign.ValueLayout.*;

/**
 * Low-level dispatcher providing direct memory-mapped access to Linux {@code io_uring}.
 */
final class IoUringDispatcher implements AutoCloseable {

    /**
     * Set in CQE flags when a multishot SQE will generate more CQEs.
     */
    static final int IORING_CQE_F_MORE = 1 << 1;

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

    private static final int EBADF = 9;
    private static final int EINTR = 4;

    private static final int IORING_FEAT_SINGLE_MMAP = 1;
    private static final long IORING_OFF_SQ_RING = 0L;
    private static final long IORING_OFF_CQ_RING = 0x8000000L;
    private static final long IORING_OFF_SQES = 0x10000000L;

    private static final int IORING_REGISTER_BUFFERS = 0;
    private static final int IORING_REGISTER_FILES = 2;
    private static final int IORING_REGISTER_FILES_UPDATE = 6;
    private static final int IORING_REGISTER_IOWQ_MAX_WORKERS = 19;

    private static final LibCDispatcher libCDispatcher = NativeDispatcher.C;
    private static final MethodHandle MH_IO_URING_ENTER = LibCDispatcher.MH_IO_URING_ENTER;
    private static final MethodHandle MH_IO_URING_REGISTER = LibCDispatcher.MH_IO_URING_REGISTER;

    private static long enter(MemorySegment callState, long fd, long toSubmit, long minComplete, long flags, MemorySegment arg, long argSize) {
        try {
            return (long) MH_IO_URING_ENTER.invokeExact(callState, fd, toSubmit, minComplete, flags, arg, argSize);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static long register(MemorySegment callState, long fd, long opcode, MemorySegment arg, long nrArgs) {
        try {
            return (long) MH_IO_URING_REGISTER.invokeExact(callState, fd, opcode, arg, nrArgs);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

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
    private final Result reusableResult = new Result();
    private MemorySegment[] registeredBuffers;
    private long[] registeredBufferAddresses;

    // Cached Ring Fields
    private int ringFd = -1;
    private int enterRingFd = -1;
    private int enterFlagsBase;
    private int ringFlags;
    private boolean isSqPoll;
    private boolean isDeferTaskrun;
    private boolean isSingleIssuer;
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
    private int inFlightCount;

    private long cqKheadAddr;
    private long cqKtailAddr;
    private long cqCqesAddr;
    private int cqRingMask;
    private int cqRingEntries;

    public static final int AT_FDCWD = -100;

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

    private static final int IORING_REGISTER_PROBE = 8;
    private static final int IO_URING_OP_SUPPORTED = 1;

    static final byte[] REQUIRED_OPCODES = {
            IORING_OP_NOP,
            IORING_OP_READV,
            IORING_OP_WRITEV,
            IORING_OP_READ_FIXED,
            IORING_OP_WRITE_FIXED,
            IORING_OP_ASYNC_CANCEL,
            IORING_OP_OPENAT,
            IORING_OP_CLOSE,
            IORING_OP_READ,
            IORING_OP_WRITE
    };

    static String getOpcodeName(byte op) {
        return switch (op) {
            case IORING_OP_NOP -> "IORING_OP_NOP";
            case IORING_OP_READV -> "IORING_OP_READV";
            case IORING_OP_WRITEV -> "IORING_OP_WRITEV";
            case IORING_OP_READ_FIXED -> "IORING_OP_READ_FIXED";
            case IORING_OP_WRITE_FIXED -> "IORING_OP_WRITE_FIXED";
            case IORING_OP_ASYNC_CANCEL -> "IORING_OP_ASYNC_CANCEL";
            case IORING_OP_OPENAT -> "IORING_OP_OPENAT";
            case IORING_OP_CLOSE -> "IORING_OP_CLOSE";
            case IORING_OP_READ -> "IORING_OP_READ";
            case IORING_OP_WRITE -> "IORING_OP_WRITE";
            default -> "OP_" + Byte.toUnsignedInt(op);
        };
    }

    void probeSupportedOpcodes() {
        int nrOps = 256;
        long probeSize = 16L + (long) nrOps * 8L;
        MemorySegment probeBuf = libCDispatcher.calloc(probeSize);
        try {
            long res = register(this.callState, this.ringFd, IORING_REGISTER_PROBE, probeBuf, nrOps);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                throw new UnsupportedOperationException("Kernel does not support IORING_REGISTER_PROBE (Linux 5.6+ required): errno=" + err + " - " + libCDispatcher.strerror(err));
            }
            int opsLen = Byte.toUnsignedInt(probeBuf.get(JAVA_BYTE, 1L));
            for (byte op : REQUIRED_OPCODES) {
                int opIndex = Byte.toUnsignedInt(op);
                if (opIndex >= opsLen) {
                    throw new UnsupportedOperationException("Kernel does not support required io_uring opcode: " + getOpcodeName(op) + " (op=" + opIndex + ", kernel 5.6+ required)");
                }
                long opOffset = 16L + (long) opIndex * 8L;
                short flags = probeBuf.get(JAVA_SHORT, opOffset + 2L);
                if ((flags & IO_URING_OP_SUPPORTED) == 0) {
                    throw new UnsupportedOperationException("Kernel does not support required io_uring opcode: " + getOpcodeName(op) + " (op=" + opIndex + ", kernel 5.6+ required)");
                }
            }
        } finally {
            libCDispatcher.free(probeBuf);
        }
    }

    static IoUringDispatcher create(int queueDepth, IoUringOptions... ioUringOptions) {
        return create(queueDepth, 0, ioUringOptions);
    }

    static IoUringDispatcher create(int queueDepth, int sqThreadIdleMs, IoUringOptions... ioUringOptions) {
        MemorySegment ring = NativeDispatcher.C.calloc(ring_layout.byteSize());
        IoUringDispatcher dispatcher = getDispatcher(ring, queueDepth);

        int combinedFlags = IoUringOptions.combineOptions(ioUringOptions);
        int ret = dispatcher.queueInit(queueDepth, sqThreadIdleMs, combinedFlags);
        if (ret < 0) {
            dispatcher.close();
            int err = -ret;
            String detail = libCDispatcher.strerror(err);
            if (err == 22 /* EINVAL */ || err == 95 /* EOPNOTSUPP */ || err == 38 /* ENOSYS */) {
                throw new IllegalArgumentException("Failed to initialize io_uring queue (" + detail + ", errno=" + err +
                        "): requested flags 0x" + Integer.toHexString(combinedFlags) +
                        " or queueDepth=" + queueDepth + " not supported by this kernel (Linux 6.1+ recommended for DEFER_TASKRUN / SINGLE_ISSUER)");
            }
            throw new RuntimeException("Failed to initialize queue: " + detail + " (errno=" + err + ")");
        }

        try {
            dispatcher.probeSupportedOpcodes();
        } catch (Throwable t) {
            dispatcher.close();
            throw t;
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

    long getSqe() {
        int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
        int tail = sqeTail;

        if (tail - head >= sqRingEntries) {
            return 0L;
        }

        int index = tail & sqRingMask;
        long sqe_addr = sqSqesAddr + ((long) index << sqeShift);

        sqeTail = tail + 1;
        inFlightCount++;

        Sqe.zero(sqe_addr);
        if (sqeShift > 6) {
            Sqe.zero(sqe_addr + 64L);
        }
        return sqe_addr;
    }

    void setSqeFlag(long sqe, SqeOptions... flags) {
        setSqeFlag(sqe, SqeOptions.combineOptions(flags));
    }

    void setSqeFlag(long sqe, byte flags) {
        Sqe.setFlags(sqe, flags);
    }

    void setUserData(long sqe, long userData) {
        Sqe.setUserData(sqe, userData);
    }

    void prepareOpenAt(long sqe, long filePathAddress, int flags, int mode) {
        Sqe.setOpcode(sqe, IORING_OP_OPENAT);
        Sqe.setFd(sqe, AT_FDCWD);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, filePathAddress);
        Sqe.setLen(sqe, mode);
        Sqe.setRwFlags(sqe, flags);
    }

    void prepareOpenDirectAt(long sqe, long filePathAddress, int flags, int mode, int fileIndex) {
        prepareOpenAt(sqe, filePathAddress, flags, mode);
        Sqe.setFileIndex(sqe, fileIndex + 1);
    }

    void prepareClose(long sqe, int fd) {
        Sqe.setOpcode(sqe, IORING_OP_CLOSE);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, 0L);
        Sqe.setLen(sqe, 0);
    }

    void prepareCloseDirect(long sqe, int fileIndex) {
        prepareClose(sqe, 0);
        Sqe.setFileIndex(sqe, fileIndex + 1);
    }

    void prepareRead(long sqe, int fd, long bufferAddress, long size, long offset) {
        Sqe.setOpcode(sqe, IORING_OP_READ);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, offset);
        Sqe.setAddr(sqe, bufferAddress);
        Sqe.setLen(sqe, (int) size);
    }

    void prepareReadFixed(long sqe, int fd, long bufferAddress, long nbytes, long offset, int bufferIndex) {
        Sqe.setOpcode(sqe, IORING_OP_READ_FIXED);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, offset);
        Sqe.setAddr(sqe, bufferAddress);
        Sqe.setLen(sqe, (int) nbytes);
        Sqe.setBufIndex(sqe, (short) bufferIndex);
    }

    void prepareWrite(long sqe, int fd, long bufferAddress, long size, long offset) {
        Sqe.setOpcode(sqe, IORING_OP_WRITE);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, offset);
        Sqe.setAddr(sqe, bufferAddress);
        Sqe.setLen(sqe, (int) size);
    }

    void prepareWriteFixed(long sqe, int fd, long bufferAddress, long nbytes, long offset, int bufferIndex) {
        Sqe.setOpcode(sqe, IORING_OP_WRITE_FIXED);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, offset);
        Sqe.setAddr(sqe, bufferAddress);
        Sqe.setLen(sqe, (int) nbytes);
        Sqe.setBufIndex(sqe, (short) bufferIndex);
    }

    void prepareReadvAddress(long sqe, int fd, long iovecAddr, int nrVecs, long offset) {
        Sqe.setOpcode(sqe, IORING_OP_READV);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, offset);
        Sqe.setAddr(sqe, iovecAddr);
        Sqe.setLen(sqe, nrVecs);
    }

    void prepareWritevAddress(long sqe, int fd, long iovecAddr, int nrVecs, long offset) {
        Sqe.setOpcode(sqe, IORING_OP_WRITEV);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, offset);
        Sqe.setAddr(sqe, iovecAddr);
        Sqe.setLen(sqe, nrVecs);
    }

    void prepareAccept(long sqe, int fd, long addr, long addrLen, int flags) {
        Sqe.setOpcode(sqe, IORING_OP_ACCEPT);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, addrLen);
        Sqe.setAddr(sqe, addr);
        Sqe.setLen(sqe, 0);
        Sqe.setRwFlags(sqe, flags);
    }

    void prepareMultishotAccept(long sqe, int fd, long addr, long addrLen, int flags) {
        prepareAccept(sqe, fd, addr, addrLen, flags);
        short prio = Sqe.getIoprio(sqe);
        Sqe.setIoprio(sqe, (short) (prio | IORING_ACCEPT_MULTISHOT));
    }

    void prepareConnect(long sqe, int fd, long addr, int addrLen) {
        Sqe.setOpcode(sqe, IORING_OP_CONNECT);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, (long) addrLen);
        Sqe.setAddr(sqe, addr);
        Sqe.setLen(sqe, 0);
    }

    void prepareRecv(long sqe, int fd, long buf, long len, int flags) {
        Sqe.setOpcode(sqe, IORING_OP_RECV);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, buf);
        Sqe.setLen(sqe, (int) len);
        Sqe.setRwFlags(sqe, flags);
    }

    void prepareSend(long sqe, int fd, long buf, long len, int flags) {
        Sqe.setOpcode(sqe, IORING_OP_SEND);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, buf);
        Sqe.setLen(sqe, (int) len);
        Sqe.setRwFlags(sqe, flags);
    }

    void prepareCancel(long sqe, long userDataToCancel, int flags) {
        Sqe.setOpcode(sqe, IORING_OP_ASYNC_CANCEL);
        Sqe.setFd(sqe, -1);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, userDataToCancel);
        Sqe.setLen(sqe, 0);
        Sqe.setRwFlags(sqe, flags);
    }

    void prepareNop(long sqe) {
        Sqe.setOpcode(sqe, IORING_OP_NOP);
        Sqe.setFd(sqe, -1);
    }

    void submit() {
        int tail = sqeTail;
        int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);

        VH_INT.setRelease(GLOBAL_MEMORY, sqKtailAddr, tail);
        sqeHead = tail;

        int toSubmit = tail - head;
        if (toSubmit <= 0) {
            return;
        }

        boolean sqpoll = this.isSqPoll;
        int enterFlags = this.enterFlagsBase;
        if (this.isDeferTaskrun) {
            enterFlags |= IORING_ENTER_GETEVENTS;
        }
        boolean needEnter = true;

        if (sqpoll) {
            VarHandle.fullFence();
            int sqFlags = (int) VH_INT.getVolatile(GLOBAL_MEMORY, sqKflagsAddr);
            if ((sqFlags & IORING_SQ_NEED_WAKEUP) != 0) {
                enterFlags |= IORING_ENTER_SQ_WAKEUP;
            } else {
                needEnter = false;
            }
        }

        if (needEnter) {
            while (true) {
                long res = enter(this.callState, enterRingFd, toSubmit, 0, enterFlags, MemorySegment.NULL, 0L);
                if (res >= 0) {
                    head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    if (toSubmit == 0) {
                        return;
                    }
                    continue;
                }
                int err = LibCDispatcher.errno(this.callState);
                if (err == 16 /* EBUSY */) {
                    throw new IllegalStateException("Completion queue ring is full (EBUSY); reap completions first");
                }
                if (err == 11 /* EAGAIN */) {
                    head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    if (toSubmit == 0 && !sqpoll) {
                        return;
                    }
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    tail = sqeTail;
                    head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
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
        enter(this.callState, enterRingFd, 0, 0, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
    }

    int submitAndWait(int waitNr) {
        int tail = sqeTail;
        int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
        int toSubmit = tail - head;
        if (toSubmit > 0) {
            VH_INT.setRelease(GLOBAL_MEMORY, sqKtailAddr, tail);
            sqeHead = tail;
        } else {
            toSubmit = 0;
        }

        boolean sqpoll = this.isSqPoll;
        while (true) {
            int cqHead = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
            int cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            int available = cqTail - cqHead;
            if (available >= waitNr && toSubmit == 0 && (!this.isDeferTaskrun || available > 0)) {
                return available;
            }

            int enterFlags = (waitNr > 0 && available < waitNr) ? IORING_ENTER_GETEVENTS : 0;
            if (this.isDeferTaskrun) {
                enterFlags |= IORING_ENTER_GETEVENTS;
            }
            boolean needEnter = (enterFlags != 0);
            if (toSubmit > 0) {
                if (sqpoll) {
                    VarHandle.fullFence();
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

            int minComplete = (enterFlags & IORING_ENTER_GETEVENTS) != 0 ? waitNr : 0;
            long res = enter(this.callState, enterRingFd, toSubmit, minComplete, enterFlags | enterFlagsBase, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 16 /* EBUSY */) {
                    cqHead = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
                    cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
                    int avail = cqTail - cqHead;
                    if (avail > 0) {
                        return avail;
                    }
                    throw new IllegalStateException("Completion queue ring is full (EBUSY); reap completions first");
                }
                if (err == 11 /* EAGAIN */) {
                    head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    tail = sqeTail;
                    head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    continue;
                }
                throw new RuntimeException("Failed in submitAndWait: " + libCDispatcher.strerror(err));
            }
            head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
            toSubmit = Math.max(0, tail - head);
            if (waitNr <= 0 && toSubmit == 0) {
                cqHead = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
                cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
                return cqTail - cqHead;
            }
        }
    }

    int waitCqe(MemorySegment cqePtr) {
        while (true) {
            int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
            int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            if (head != tail) {
                int index = head & cqRingMask;
                long cqeAddr = cqCqesAddr + ((long) index << cqeShift);
                cqePtr.setAtIndex(JAVA_LONG, 0, cqeAddr);
                return 0;
            }

            long res = enter(this.callState, enterRingFd, 0, 1, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
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

    void cqAdvance(int count) {
        if (count <= 0) return;
        inFlightCount -= count;
        int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
        VH_INT.setRelease(GLOBAL_MEMORY, cqKheadAddr, head + count);
    }

    int waitCqeNr(int waitNr) {
        while (true) {
            int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
            int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            int available = tail - head;
            if (available >= waitNr) {
                return 0;
            }

            long res = enter(this.callState, enterRingFd, 0, waitNr, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
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
        return queueInit(queueDepth, 0, flags);
    }

    int queueInit(int queueDepth, int sqThreadIdleMs, int flags) {
        MemorySegment params = libCDispatcher.calloc(io_uring_params.layout().byteSize());
        io_uring_params.flags(params, flags);
        if (sqThreadIdleMs > 0) {
            io_uring_params.sq_thread_idle(params, sqThreadIdleMs);
        }
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

        boolean singleMmap = (features & IORING_FEAT_SINGLE_MMAP) != 0;
        if (singleMmap) {
            if (cq_ring_sz > sq_ring_sz) {
                sq_ring_sz = cq_ring_sz;
            }
            cq_ring_sz = sq_ring_sz;
        }

        long sq_ring_ptr = libCDispatcher.mmap(sq_ring_sz, LibCDispatcher.PROT_READ | LibCDispatcher.PROT_WRITE,
                LibCDispatcher.MAP_SHARED | LibCDispatcher.MAP_POPULATE, fd, IORING_OFF_SQ_RING);
        if (sq_ring_ptr < 0) {
            libCDispatcher.close(fd);
            return (int) sq_ring_ptr;
        }

        long cq_ring_ptr;
        if (singleMmap) {
            cq_ring_ptr = sq_ring_ptr;
        } else {
            cq_ring_ptr = libCDispatcher.mmap(cq_ring_sz, LibCDispatcher.PROT_READ | LibCDispatcher.PROT_WRITE,
                    LibCDispatcher.MAP_SHARED | LibCDispatcher.MAP_POPULATE, fd, IORING_OFF_CQ_RING);
            if (cq_ring_ptr < 0) {
                libCDispatcher.munmap(sq_ring_ptr, sq_ring_sz);
                libCDispatcher.close(fd);
                return (int) cq_ring_ptr;
            }
        }

        int sqe_shift = ((flags & IoUringOptions.IORING_SETUP_SQE128.value) != 0) ? 1 : 0;
        long sqes_sz = ((long) sq_entries << sqe_shift) * 64L;
        long sqes_ptr = libCDispatcher.mmap(sqes_sz, LibCDispatcher.PROT_READ | LibCDispatcher.PROT_WRITE,
                LibCDispatcher.MAP_SHARED | LibCDispatcher.MAP_POPULATE, fd, IORING_OFF_SQES);
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
        this.isSingleIssuer = (flags & IoUringOptions.IORING_SETUP_SINGLE_ISSUER.value) != 0;
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

        if (this.isSingleIssuer) {
            registerRingFd();
        }

        return 0;
    }

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
            long res = register(this.callState, this.ringFd, IORING_REGISTER_RING_FDS, up, 1);
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
            long res = register(this.callState, this.ringFd, IORING_UNREGISTER_RING_FDS, up, 1);
            if (res < 0) {
                return -LibCDispatcher.errno(this.callState);
            }
            return (int) res;
        }
    }

    boolean isRingFdRegistered() {
        return this.enterFlagsBase != 0;
    }

    boolean isSqNeedWakeup() {
        return sqKflagsAddr != 0L && ((int) VH_INT.getVolatile(GLOBAL_MEMORY, sqKflagsAddr) & IORING_SQ_NEED_WAKEUP) != 0;
    }

    int ringFd() {
        return this.ringFd;
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
        long res = register(this.callState, fd, IORING_REGISTER_BUFFERS, iovecs, nrIovecs);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        return (int) res;
    }

    LibCDispatcher.IovecStructure allocateAndRegisterBuffers(int bufferSize, int nrIovecs) {
        var iovecStructure = libCDispatcher.allocateIovec(arena, bufferSize, nrIovecs);
        int ret = registerBuffers(ring, iovecStructure.iovecArray(), nrIovecs);
        if (ret < 0) {
            throw new RuntimeException("Failed to register buffers: " + libCDispatcher.strerror(-ret));
        }
        setRegisteredBuffers(iovecStructure.buffers());
        return iovecStructure;
    }

    void registerExistingIovecStructure(LibCDispatcher.IovecStructure iovecStructure, int nrIovecs) {
        int ret = registerBuffers(ring, iovecStructure.iovecArray(), nrIovecs);
        if (ret < 0) {
            throw new RuntimeException("Failed to register buffers: " + libCDispatcher.strerror(-ret));
        }
        setRegisteredBuffers(iovecStructure.buffers());
    }

    MemorySegment[] registerBuffers(int bufferSize, int nrIovecs) {
        return allocateAndRegisterBuffers(bufferSize, nrIovecs).buffers();
    }

    int registerFiles(MemorySegment ring, MemorySegment fdArray, int count) {
        int fd = this.ringFd;
        long res = register(this.callState, fd, IORING_REGISTER_FILES, fdArray, count);
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
            long res = register(this.callState, fd, IORING_REGISTER_FILES_UPDATE, up, count);
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
            long res = register(this.callState, fd, IORING_REGISTER_IOWQ_MAX_WORKERS, values, 2);
            if (res < 0) {
                throw new RuntimeException("Failed to register max workers: " + libCDispatcher.strerror(LibCDispatcher.errno(this.callState)));
            }
        }
    }

    /**
     * Drains available completions directly from the shared memory-mapped CQ ring in-place.
     * <p>
     * Advances {@code cq.khead} in a {@code finally} block using the count of processed completions.
     * A CQE counts as processed before invoking {@code consumer.accept}, ensuring that if the consumer
     * throws, only the failing CQE is lost and already-freed buffers are never processed or freed again.
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
        int processed = 0;
        try {
            for (int i = 0; i < count; i++) {
                int index = (head + i) & mask;
                long address = cqes + ((long) index << shift);
                long userData = Cqe.getUserData(address);
                int res = Cqe.getRes(address);
                int flags = Cqe.getFlags(address);

                populateResultFromCqe(reusableResult, userData, res, flags);
                processed = i + 1;
                consumer.accept(reusableResult);
            }
        } finally {
            inFlightCount -= processed;
            VH_INT.setRelease(GLOBAL_MEMORY, cqKheadAddr, head + processed);
        }
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
        int processed = 0;
        try {
            for (int i = 0; i < count; i++) {
                int index = (head + i) & mask;
                long address = cqes + ((long) index << shift);
                long userData = Cqe.getUserData(address);
                int res = Cqe.getRes(address);
                int flags = Cqe.getFlags(address);
                processed = i + 1;
                consumer.accept(userData, res, flags);
            }
        } finally {
            inFlightCount -= processed;
            VH_INT.setRelease(GLOBAL_MEMORY, cqKheadAddr, head + processed);
        }
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
        int processed = 0;
        try {
            for (int i = 0; i < count; i++) {
                int index = (head + i) & mask;
                long address = cqes + ((long) index << shift);
                long userData = Cqe.getUserData(address);
                int res = Cqe.getRes(address);
                int flags = Cqe.getFlags(address);

                Result r = new Result();
                r.setRegisteredBuffers(this.registeredBuffers);
                populateResultFromCqe(r, userData, res, flags);
                processed = i + 1;
                ret.add(r);
            }
        } finally {
            inFlightCount -= processed;
            VH_INT.setRelease(GLOBAL_MEMORY, cqKheadAddr, head + processed);
        }
        return ret;
    }

    List<Result> waitForBatchResult(int batchSize) {
        int status = waitCqeNr( batchSize);
        if (status < 0) {
            status = waitCqeNr(batchSize);
            if (status < 0) {
                status = waitCqeNr(batchSize);
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
            int cqHead = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
            int cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            int available = cqTail - cqHead;
            if (available >= minComplete) {
                return reapAvailable(cqHead, available, maxReap, consumer);
            }

            long res = enter(this.callState, enterRingFd, 0, minComplete, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 4 /* EINTR */) {
                    continue;
                }
                throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(err));
            }
        }
    }

    int submitAndWaitForBatchResult(int minComplete, int maxReap, ResultConsumer consumer) {
        int tail = sqeTail;
        int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
        int toSubmit = tail - head;
        if (toSubmit > 0) {
            VH_INT.setRelease(GLOBAL_MEMORY, sqKtailAddr, tail);
            sqeHead = tail;
        } else {
            toSubmit = 0;
        }

        boolean sqpoll = this.isSqPoll;
        while (true) {
            int cqHead = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
            int cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
            int available = cqTail - cqHead;
            if (available >= minComplete && toSubmit == 0 && (!this.isDeferTaskrun || available > 0)) {
                return reapAvailable(cqHead, available, maxReap, consumer);
            }

            int enterFlags = (minComplete > 0 && available < minComplete) ? IORING_ENTER_GETEVENTS : 0;
            if (this.isDeferTaskrun) {
                enterFlags |= IORING_ENTER_GETEVENTS;
            }
            boolean needEnter = (enterFlags != 0);
            if (toSubmit > 0) {
                if (sqpoll) {
                    VarHandle.fullFence();
                    int sqFlags = (int) VH_INT.getVolatile(GLOBAL_MEMORY, sqKflagsAddr);
                    if ((sqFlags & IORING_SQ_NEED_WAKEUP) != 0) {
                        enterFlags |= IORING_ENTER_SQ_WAKEUP;
                        needEnter = true;
                    }
                } else {
                    needEnter = true;
                }
            }

            if (available > 0 && available >= cqRingEntries) {
                int reaped = reapAvailable(cqHead, available, maxReap, consumer);
                if (reaped > 0) {
                    cqHead = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
                    cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
                    available = cqTail - cqHead;
                }
            }

            if (!needEnter) {
                return reapAvailable(cqHead, available, maxReap, consumer);
            }

            int waitEvents = (enterFlags & IORING_ENTER_GETEVENTS) != 0 ? minComplete : 0;
            long res = enter(this.callState, enterRingFd, toSubmit, waitEvents, enterFlags | enterFlagsBase, MemorySegment.NULL, 0L);
            if (res < 0) {
                int err = LibCDispatcher.errno(this.callState);
                if (err == 16 /* EBUSY */) {
                    cqHead = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
                    cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
                    int avail = cqTail - cqHead;
                    if (avail > 0) {
                        int reaped = reapAvailable(cqHead, avail, maxReap, consumer);
                        if (reaped > 0) {
                            head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
                            toSubmit = Math.max(0, tail - head);
                            continue;
                        }
                    }
                    throw new IllegalStateException("Completion queue ring is full (EBUSY); reap completions first");
                }
                if (err == 11 /* EAGAIN */) {
                    head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    continue;
                }
                if (err == 4 /* EINTR */) {
                    tail = sqeTail;
                    head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
                    toSubmit = Math.max(0, tail - head);
                    continue;
                }
                throw new RuntimeException("Failed in submitAndWaitForBatchResult: " + libCDispatcher.strerror(err));
            }
            head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
            toSubmit = Math.max(0, tail - head);
            if (minComplete <= 0 && toSubmit == 0) {
                cqHead = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
                cqTail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
                return reapAvailable(cqHead, cqTail - cqHead, maxReap, consumer);
            }
        }
    }

    Result waitForResult() {
        return waitForResult(new Result());
    }

    Result waitForResult(Result target) {
        int ret = waitCqe(cqePtr);
        if (ret < 0) {
            throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(-ret));
        }

        long cqeAddress = cqePtr.getAtIndex(JAVA_LONG, 0);

        long userData = Cqe.getUserData(cqeAddress);
        int res = Cqe.getRes(cqeAddress);
        int flags = Cqe.getFlags(cqeAddress);

        target.setRegisteredBuffers(this.registeredBuffers);
        populateResultFromCqe(target, userData, res, flags);
        cqAdvance(1);
        return target;
    }

    void populateResultFromCqe(Result target, long userData, long result, int cqeFlags) {
        int typeIndex = (int) ((userData >>> UserData.TYPE_SHIFT) & UserData.TYPE_MASK);

        switch (typeIndex) {
            case 17 /* READ_FIXED */ -> {
                int bufferIdx = UserData.getBufferIndex(userData);
                long regBufAddr = (registeredBufferAddresses != null && bufferIdx >= 0 && bufferIdx < registeredBufferAddresses.length)
                        ? registeredBufferAddresses[bufferIdx] : 0L;
                target.set(userData, result, OperationType.READ_FIXED, regBufAddr, null, bufferIdx, null, cqeFlags);
            }
            case 2 /* WRITE_FIXED */ -> {
                int bufferIdx = UserData.getBufferIndex(userData);
                target.set(userData, result, OperationType.WRITE_FIXED, 0L, null, bufferIdx, null, cqeFlags);
            }
            case 0 /* READ */, 18 /* READ_POOLED */ -> {
                long bufferAddr = UserData.getPayload(userData);
                target.set(userData, result, OperationType.valueOf(typeIndex), bufferAddr, null, -1, null, cqeFlags);
            }
            case 1 /* WRITE */ -> {
                long buffAddr = UserData.getPayload(userData);
                if (buffAddr != 0L) {
                    libCDispatcher.free(buffAddr);
                }
                target.set(userData, result, OperationType.WRITE, 0L, null, -1, null, cqeFlags);
            }
            case 3 /* OPEN */ -> {
                long pathAddr = UserData.getPayload(userData);
                if (pathAddr != 0L) {
                    libCDispatcher.free(pathAddr);
                }
                target.set(userData, result, OperationType.OPEN, 0L, null, -1, null, cqeFlags);
            }
            case 4 /* CLOSE */ -> {
                target.set(userData, result, OperationType.CLOSE, 0L, null, -1, null, cqeFlags);
            }
            case 5 /* READV */, 7 /* READV_FIXED */ -> {
                long blockAddr = UserData.getPayload(userData);
                int count = (int) IovecBlock.getCount(blockAddr);

                MemorySegment[] buffers = new MemorySegment[count];
                long remaining = result > 0 ? result : 0L;
                for (int i = 0; i < count; i++) {
                    long base = IovecBlock.getIovBase(blockAddr, i);
                    long len = IovecBlock.getIovLen(blockAddr, i);
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
                long blockAddr = UserData.getPayload(userData);
                int count = (int) IovecBlock.getCount(blockAddr);
                for (int i = 0; i < count; i++) {
                    libCDispatcher.free(IovecBlock.getIovBase(blockAddr, i));
                }
                iovecBlockPool.checkIn(blockAddr);
                target.set(userData, result, OperationType.WRITEV, 0L, null, -1, null, cqeFlags);
            }
            case 8 /* WRITEV_FIXED */ -> {
                long blockAddr = UserData.getPayload(userData);
                iovecBlockPool.checkIn(blockAddr);
                target.set(userData, result, OperationType.WRITEV_FIXED, 0L, null, -1, null, cqeFlags);
            }
            case 9 /* ACCEPT */, 15 /* MULTISHOT_ACCEPT */ -> {
                target.set(userData, result, OperationType.valueOf(typeIndex), 0L, null, -1, null, cqeFlags);
            }
            case 10 /* CONNECT */ -> {
                long connectAddr = UserData.getPayload(userData);
                if (connectAddr != 0L) {
                    libCDispatcher.free(connectAddr);
                }
                target.set(userData, result, OperationType.CONNECT, 0L, null, -1, null, cqeFlags);
            }
            case 11 /* RECV */, 14 /* RECV_EXT */ -> {
                long bufferAddr = UserData.getPayload(userData);
                target.set(userData, result, OperationType.valueOf(typeIndex), bufferAddr, null, -1, null, cqeFlags);
            }
            case 12 /* SEND */ -> {
                long sendBuf = UserData.getPayload(userData);
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
            case 20 /* WRITE_EXT */ -> {
                target.set(userData, result, OperationType.WRITE_EXT, 0L, null, -1, null, cqeFlags);
            }
        }
    }

    void closeRing() {
        queueExit(ring);
    }

    void closeArena() {
        NativeDispatcher.C.free(ring);
    }

    private void drainAndCancel() {
        try {
            int unsubmitted = sqeTail - sqeHead;
            if (unsubmitted > 0) {
                submit();
            }
            if (inFlightCount > 0) {
                long cancelSqe = getSqe();
                if (cancelSqe != 0L) {
                    Sqe.zero(cancelSqe);
                    Sqe.setOpcode(cancelSqe, IORING_OP_ASYNC_CANCEL);
                    Sqe.setFd(cancelSqe, -1);
                    Sqe.setRwFlags(cancelSqe, 5); // IORING_ASYNC_CANCEL_ALL | IORING_ASYNC_CANCEL_ANY
                    Sqe.setUserData(cancelSqe, Long.MIN_VALUE + 1);
                    submit();
                }
                while (inFlightCount > 0) {
                    long res = enter(this.callState, enterRingFd, 0, 1, IORING_ENTER_GETEVENTS | enterFlagsBase, MemorySegment.NULL, 0L);
                    if (res < 0) {
                        int err = LibCDispatcher.errno(this.callState);
                        if (err != 11 /* EAGAIN */ && err != 16 /* EBUSY */ && err != 4 /* EINTR */) {
                            break;
                        }
                    }
                    int head = (int) VH_INT.get(GLOBAL_MEMORY, cqKheadAddr);
                    int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
                    int available = tail - head;
                    if (available > 0) {
                        reapAvailable(head, available, available, Result::close);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Disabled rings (IORING_SETUP_R_DISABLED) or invalid fds can fail submit; proceed to close
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        drainAndCancel();
        closed = true;
        closeRing();
        iovecBlockPool.close();
        libCDispatcher.free(cqePtr);
        libCDispatcher.free(cqePtrPtr);
        closeArena();
        arena.close();
    }
}
