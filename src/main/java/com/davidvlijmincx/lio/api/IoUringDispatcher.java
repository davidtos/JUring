package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_ATTACH_WQ;
import static java.lang.foreign.ValueLayout.*;

/**
 * Low-level dispatcher providing direct memory-mapped access to Linux {@code io_uring}.
 */
final class IoUringDispatcher implements AutoCloseable {

    static final byte IORING_OP_NOP = 0;
    static final byte IORING_OP_READV = 1;
    static final byte IORING_OP_WRITEV = 2;
    static final byte IORING_OP_FSYNC = 3;
    static final byte IORING_OP_READ_FIXED = 4;
    static final byte IORING_OP_WRITE_FIXED = 5;
    static final byte IORING_OP_TIMEOUT = 11;
    static final byte IORING_OP_ASYNC_CANCEL = 14;
    static final byte IORING_OP_LINK_TIMEOUT = 15;
    static final byte IORING_OP_FALLOCATE = 17;
    static final byte IORING_OP_OPENAT = 18;
    static final byte IORING_OP_CLOSE = 19;
    static final byte IORING_OP_STATX = 21;
    static final byte IORING_OP_READ = 22;
    static final byte IORING_OP_WRITE = 23;
    static final byte IORING_OP_FTRUNCATE = 55;

    // Enter flags
    private static final int IORING_ENTER_GETEVENTS = 1;
    private static final int IORING_ENTER_SQ_WAKEUP = 2;
    private static final int IORING_ENTER_REGISTERED_RING = 1 << 4;

    // Register opcodes
    private static final int IORING_REGISTER_RING_FDS = 20;
    private static final int IORING_UNREGISTER_RING_FDS = 21;

    // Ring flags
    private static final int IORING_SQ_NEED_WAKEUP = 1;

    // IORING_OP_ASYNC_CANCEL sqe->cancel_flags values (uapi/linux/io_uring.h).
    static final int IORING_ASYNC_CANCEL_ALL = 1;
    static final int IORING_ASYNC_CANCEL_FD = 1 << 1;
    static final int IORING_ASYNC_CANCEL_ANY = 1 << 2;
    static final int IORING_ASYNC_CANCEL_FD_FIXED = 1 << 3;

    private static final int EBADF = 9;
    private static final int EINTR = 4;
    private static final int ECANCELED = 125;

    private static final int IORING_FEAT_SINGLE_MMAP = 1;
    private static final long IORING_OFF_SQ_RING = 0L;
    private static final long IORING_OFF_CQ_RING = 0x8000000L;
    private static final long IORING_OFF_SQES = 0x10000000L;

    private static final int IORING_REGISTER_BUFFERS = 0;
    private static final int IORING_UNREGISTER_BUFFERS = 1;
    private static final int IORING_REGISTER_FILES = 2;
    private static final int IORING_UNREGISTER_FILES = 3;
    private static final int IORING_REGISTER_FILES_UPDATE = 6;
    private static final int IORING_REGISTER_BUFFERS_UPDATE = 16;
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

    private static final VarHandle VH_INT = JAVA_INT.varHandle();
    private static final MemorySegment GLOBAL_MEMORY = MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE);
    private final Arena arena;
    private final IovecBlockPool iovecBlockPool;
    private final MemorySegment callState;
    private final MemorySegment cqePtr;
    private final Result reusableResult = new Result();
    private MemorySegment[] registeredBuffers;
    private long[] registeredBufferAddresses;

    // Optional-opcode support cache, a 256-bit set filled by probeSupportedOpcodes().
    private final long[] opcodeSupport = new long[4];
    // Lazily allocated single NUL byte in the ring arena, used as the statx AT_EMPTY_PATH pathname.
    private long emptyPathAddress;

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
    private long sqRingPtr;
    private long sqRingSz;
    private long sqKheadAddr;
    private long sqKtailAddr;
    private long sqKflagsAddr;
    private long sqSqesAddr;
    private int sqRingMask;
    private int sqRingEntries;
    private int sqeHead;
    private int sqeTail;
    private int inFlightCount;

    private long cqRingPtr;
    private long cqRingSz;
    private long cqKheadAddr;
    private long cqKtailAddr;
    private long cqCqesAddr;
    private int cqRingMask;
    private int cqRingEntries;

    public static final int AT_FDCWD = -100;

    public IoUringDispatcher(Arena arena,
                            IovecBlockPool iovecBlockPool,
                            MemorySegment callState) {
        this.arena = arena;
        this.iovecBlockPool = iovecBlockPool;
        this.callState = callState;
        this.cqePtr = arena.allocate(JAVA_LONG);
    }

    public Arena arena() {
        return arena;
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
            case IORING_OP_TIMEOUT -> "IORING_OP_TIMEOUT";
            case IORING_OP_ASYNC_CANCEL -> "IORING_OP_ASYNC_CANCEL";
            case IORING_OP_LINK_TIMEOUT -> "IORING_OP_LINK_TIMEOUT";
            case IORING_OP_FALLOCATE -> "IORING_OP_FALLOCATE";
            case IORING_OP_OPENAT -> "IORING_OP_OPENAT";
            case IORING_OP_CLOSE -> "IORING_OP_CLOSE";
            case IORING_OP_STATX -> "IORING_OP_STATX";
            case IORING_OP_READ -> "IORING_OP_READ";
            case IORING_OP_WRITE -> "IORING_OP_WRITE";
            case IORING_OP_FTRUNCATE -> "IORING_OP_FTRUNCATE";
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
            opcodeSupport[0] = 0L;
            opcodeSupport[1] = 0L;
            opcodeSupport[2] = 0L;
            opcodeSupport[3] = 0L;
            for (int opIndex = 0; opIndex < opsLen; opIndex++) {
                long opOffset = 16L + (long) opIndex * 8L;
                short flags = probeBuf.get(JAVA_SHORT, opOffset + 2L);
                if ((flags & IO_URING_OP_SUPPORTED) != 0) {
                    opcodeSupport[opIndex >>> 6] |= 1L << (opIndex & 63);
                }
            }
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

    /**
     * Reports whether the kernel advertised support for an opcode in the probe performed at ring
     * creation. Only the optional opcodes outside {@link #REQUIRED_OPCODES} should be queried;
     * required opcodes already caused ring creation to fail when unsupported.
     */
    boolean isOpcodeSupported(byte op) {
        int index = Byte.toUnsignedInt(op);
        return (opcodeSupport[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    static IoUringDispatcher create(int queueDepth, IoUringOptions... ioUringOptions) {
        return create(queueDepth, 0, ioUringOptions);
    }

    static IoUringDispatcher create(int queueDepth, int sqThreadIdleMs, IoUringOptions... ioUringOptions) {
        IoUringDispatcher dispatcher = getDispatcher(queueDepth);

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

    private static IoUringDispatcher getDispatcher(int queueDepth) {
        Arena arena = Arena.ofShared();
        MemorySegment callState = arena.allocate(LibCDispatcher.CAPTURE_STATE_LAYOUT);
        return new IoUringDispatcher(
                arena,
                new IovecBlockPool(queueDepth),
                callState
        );
    }

    public IoUringDispatcher getSharedWorkerRing(int queueDepth, IoUringOptions... ioUringOptions) {
        IoUringDispatcher dispatcher = getDispatcher(queueDepth);
        MemorySegment params = NativeDispatcher.C.calloc(io_uring_params.layout().byteSize());
        try {
            int ring_fd = this.ringFd;

            var result = IoUringOptions.combineOptions(ioUringOptions);

            io_uring_params.flags(params, result | IORING_SETUP_ATTACH_WQ.value);
            io_uring_params.wq_fd(params, ring_fd);

            var ret = dispatcher.queueInitParams(queueDepth, params);
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

    void prepareFsync(long sqe, int fd, int fsyncFlags) {
        Sqe.setOpcode(sqe, IORING_OP_FSYNC);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, 0L);
        Sqe.setLen(sqe, 0);
        Sqe.setRwFlags(sqe, fsyncFlags);
    }

    /**
     * Prepares an {@code IORING_OP_ASYNC_CANCEL} that matches the target request by its
     * {@code user_data} value. SQE field mapping follows liburing
     * ({@code io_uring_prep_cancel}): {@code fd=-1}, {@code addr=target user_data},
     * {@code off=len=0}, {@code rw_flags=cancel_flags}.
     */
    void prepareCancelByUserData(long sqe, long userDataToCancel) {
        Sqe.setOpcode(sqe, IORING_OP_ASYNC_CANCEL);
        Sqe.setFd(sqe, -1);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, userDataToCancel);
        Sqe.setLen(sqe, 0);
        Sqe.setRwFlags(sqe, 0);
    }

    /**
     * Prepares an {@code IORING_OP_ASYNC_CANCEL} that matches requests by the file they
     * operate on ({@code io_uring_prep_cancel_fd}): {@code fd=fdOrSlot}, {@code addr=0} and
     * {@code IORING_ASYNC_CANCEL_FD} (plus {@code IORING_ASYNC_CANCEL_FD_FIXED} for a
     * registered-file slot) in {@code rw_flags}. The {@code extraFlags} must only contain
     * {@code IORING_ASYNC_CANCEL_ALL}.
     */
    void prepareCancelFile(long sqe, int fdOrSlot, boolean fixedFile, int extraFlags) {
        int flags = extraFlags | IORING_ASYNC_CANCEL_FD | (fixedFile ? IORING_ASYNC_CANCEL_FD_FIXED : 0);
        Sqe.setOpcode(sqe, IORING_OP_ASYNC_CANCEL);
        Sqe.setFd(sqe, fdOrSlot);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, 0L);
        Sqe.setLen(sqe, 0);
        Sqe.setRwFlags(sqe, flags);
    }

    /**
     * Prepares an {@code IORING_OP_ASYNC_CANCEL} with {@code IORING_ASYNC_CANCEL_ALL |
     * IORING_ASYNC_CANCEL_ANY}: cancel every request currently in flight on this ring.
     * {@code fd=-1}, {@code addr=0}.
     */
    void prepareCancelAny(long sqe) {
        Sqe.setOpcode(sqe, IORING_OP_ASYNC_CANCEL);
        Sqe.setFd(sqe, -1);
        Sqe.setOff(sqe, 0L);
        Sqe.setAddr(sqe, 0L);
        Sqe.setLen(sqe, 0);
        Sqe.setRwFlags(sqe, IORING_ASYNC_CANCEL_ALL | IORING_ASYNC_CANCEL_ANY);
    }

    void prepareNop(long sqe) {
        Sqe.setOpcode(sqe, IORING_OP_NOP);
        Sqe.setFd(sqe, -1);
    }

    /**
     * Prepares an {@code IORING_OP_FALLOCATE} SQE. Field mapping follows liburing:
     * {@code off=offset}, {@code len=mode}, {@code addr=length}.
     */
    void prepareFallocate(long sqe, int fd, int mode, long offset, long length) {
        Sqe.setOpcode(sqe, IORING_OP_FALLOCATE);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, offset);
        Sqe.setLen(sqe, mode);
        Sqe.setAddr(sqe, length);
    }

    /**
     * Prepares an {@code IORING_OP_FTRUNCATE} SQE. The new length travels in {@code off}.
     */
    void prepareFtruncate(long sqe, int fd, long length) {
        Sqe.setOpcode(sqe, IORING_OP_FTRUNCATE);
        Sqe.setFd(sqe, fd);
        Sqe.setOff(sqe, length);
        Sqe.setLen(sqe, 0);
        Sqe.setAddr(sqe, 0L);
    }

    /**
     * Prepares an {@code IORING_OP_STATX} SQE. Field mapping follows liburing:
     * {@code addr=pathname}, {@code len=mask}, {@code off=statxBuffer}, {@code rw_flags=flags}.
     */
    void prepareStatx(long sqe, int fd, long pathnameAddr, int flags, int mask, long statxBufAddr) {
        Sqe.setOpcode(sqe, IORING_OP_STATX);
        Sqe.setFd(sqe, fd);
        Sqe.setAddr(sqe, pathnameAddr);
        Sqe.setLen(sqe, mask);
        Sqe.setOff(sqe, statxBufAddr);
        Sqe.setRwFlags(sqe, flags);
    }

    /**
     * Prepares an {@code IORING_OP_TIMEOUT} SQE. Field mapping follows liburing:
     * {@code fd=-1}, {@code addr=timespec}, {@code len=1}, {@code off=count}, {@code rw_flags=flags}.
     */
    void prepareTimeout(long sqe, long timespecAddr, long count, int flags) {
        Sqe.setOpcode(sqe, IORING_OP_TIMEOUT);
        Sqe.setFd(sqe, -1);
        Sqe.setAddr(sqe, timespecAddr);
        Sqe.setLen(sqe, 1);
        Sqe.setOff(sqe, count);
        Sqe.setRwFlags(sqe, flags);
    }

    /**
     * Prepares an {@code IORING_OP_LINK_TIMEOUT} SQE. Field mapping follows liburing
     * ({@code io_uring_prep_link_timeout}): {@code fd=-1}, {@code addr=timespec},
     * {@code len=1}, {@code off=0}, {@code rw_flags=flags}. The SQE only has an effect when
     * it immediately follows a submitted SQE that carries {@code IOSQE_IO_LINK}; the kernel
     * validates that at submission time.
     */
    void prepareLinkTimeout(long sqe, long timespecAddr, int flags) {
        Sqe.setOpcode(sqe, IORING_OP_LINK_TIMEOUT);
        Sqe.setFd(sqe, -1);
        Sqe.setAddr(sqe, timespecAddr);
        Sqe.setLen(sqe, 1);
        Sqe.setOff(sqe, 0L);
        Sqe.setRwFlags(sqe, flags);
    }

    /**
     * Returns the address of the most recently prepared but not yet submitted SQE, or
     * {@code 0} when the current submission batch is empty. Used to validate that an
     * {@code IORING_OP_LINK_TIMEOUT} immediately follows its target in the same batch.
     */
    long lastQueuedSqe() {
        if (closed || sqKheadAddr == 0L) {
            return 0L;
        }
        int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
        if (sqeTail <= head) {
            return 0L;
        }
        int index = (sqeTail - 1) & sqRingMask;
        return sqSqesAddr + ((long) index << sqeShift);
    }

    /**
     * Allocates a single NUL byte in the ring arena and returns its address. Used as the
     * {@code AT_EMPTY_PATH} pathname for statx. Allocated once per ring; valid until the ring closes.
     */
    long emptyPathAddress() {
        if (emptyPathAddress == 0L) {
            MemorySegment path = arena.allocate(1L, 8L);
            path.set(JAVA_BYTE, 0L, (byte) 0);
            emptyPathAddress = path.address();
        }
        return emptyPathAddress;
    }

    /**
     * Reads {@code stx_size} (a {@code __u64} at offset 40) out of a completed 256+ byte statx buffer.
     * The {@code struct statx} layout is architecture-stable.
     */
    long staticxSize(MemorySegment statxBuffer) {
        return statxBuffer.get(JAVA_LONG, 40L);
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
        int ret = queueInitParams(queueDepth, params);
        libCDispatcher.free(params);
        return ret;
    }

    int queueInitParams(int queueDepth, MemorySegment params) {
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

        long sq_khead = sq_ring_ptr + io_sqring_offsets.head(sq_off);
        long sq_ktail = sq_ring_ptr + io_sqring_offsets.tail(sq_off);
        long sq_kring_mask = sq_ring_ptr + io_sqring_offsets.ring_mask(sq_off);
        long sq_kring_entries = sq_ring_ptr + io_sqring_offsets.ring_entries(sq_off);
        long sq_kflags = sq_ring_ptr + io_sqring_offsets.flags(sq_off);

        int sq_mask = (int) VH_INT.get(GLOBAL_MEMORY, sq_kring_mask);
        int sq_ents = (int) VH_INT.get(GLOBAL_MEMORY, sq_kring_entries);

        if ((flags & IoUringOptions.IORING_SETUP_NO_SQARRAY.value) == 0) {
            long sq_array = sq_ring_ptr + io_sqring_offsets.array(sq_off);
            for (int i = 0; i < sq_ents; i++) {
                VH_INT.set(GLOBAL_MEMORY, sq_array + (long) i * 4L, i);
            }
        }

        long cq_khead = cq_ring_ptr + io_cqring_offsets.head(cq_off);
        long cq_ktail = cq_ring_ptr + io_cqring_offsets.tail(cq_off);
        long cq_kring_mask = cq_ring_ptr + io_cqring_offsets.ring_mask(cq_off);
        long cq_kring_entries = cq_ring_ptr + io_cqring_offsets.ring_entries(cq_off);
        long cq_cqes_addr = cq_ring_ptr + io_cqring_offsets.cqes(cq_off);

        int cq_mask = (int) VH_INT.get(GLOBAL_MEMORY, cq_kring_mask);
        int cq_ents = (int) VH_INT.get(GLOBAL_MEMORY, cq_kring_entries);

        this.ringFd = fd;
        this.enterRingFd = fd;
        this.enterFlagsBase = 0;
        this.ringFlags = flags;
        this.isSqPoll = (flags & IoUringOptions.IORING_SETUP_SQPOLL.value) != 0;
        this.isDeferTaskrun = (flags & IoUringOptions.IORING_SETUP_DEFER_TASKRUN.value) != 0;
        this.isSingleIssuer = (flags & IoUringOptions.IORING_SETUP_SINGLE_ISSUER.value) != 0;
        this.sqeShift = 6 + sqe_shift;
        this.cqeShift = 4 + cqe_shift;
        this.sqRingPtr = sq_ring_ptr;
        this.sqRingSz = sq_ring_sz;
        this.sqKheadAddr = sq_khead;
        this.sqKtailAddr = sq_ktail;
        this.sqKflagsAddr = this.isSqPoll ? sq_kflags : 0L;
        this.sqSqesAddr = sqes_ptr;
        this.sqRingMask = sq_mask;
        this.sqRingEntries = sq_ents;
        this.sqeHead = 0;
        this.sqeTail = 0;

        this.cqRingPtr = cq_ring_ptr;
        this.cqRingSz = cq_ring_sz;
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

    void queueExit() {
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

        if (cqRingPtr != 0L && cqRingPtr != sqRingPtr && cqRingSz != 0L) {
            libCDispatcher.munmap(cqRingPtr, cqRingSz);
        }
        if (sqRingPtr != 0L && sqRingSz != 0L) {
            libCDispatcher.munmap(sqRingPtr, sqRingSz);
        }

        libCDispatcher.close(fd);
        this.ringFd = -1;
    }

    int registerBuffers(MemorySegment iovecs, int nrIovecs) {
        int fd = this.ringFd;
        long res = register(this.callState, fd, IORING_REGISTER_BUFFERS, iovecs, nrIovecs);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        return (int) res;
    }

    LibCDispatcher.IovecStructure allocateAndRegisterBuffers(int bufferSize, int nrIovecs) {
        var iovecStructure = libCDispatcher.allocateIovec(arena, bufferSize, nrIovecs);
        int ret = registerBuffers(iovecStructure.iovecArray(), nrIovecs);
        if (ret < 0) {
            throw new RuntimeException("Failed to register buffers: " + libCDispatcher.strerror(-ret));
        }
        setRegisteredBuffers(iovecStructure.buffers());
        return iovecStructure;
    }

    void registerExistingIovecStructure(LibCDispatcher.IovecStructure iovecStructure, int nrIovecs) {
        int ret = registerBuffers(iovecStructure.iovecArray(), nrIovecs);
        if (ret < 0) {
            throw new RuntimeException("Failed to register buffers: " + libCDispatcher.strerror(-ret));
        }
        setRegisteredBuffers(iovecStructure.buffers());
    }

    MemorySegment[] registerBuffers(int bufferSize, int nrIovecs) {
        return allocateAndRegisterBuffers(bufferSize, nrIovecs).buffers();
    }

    /**
     * Unregisters all registered buffers. Returns 0 on success or {@code -errno}.
     * Clears the dispatcher's mirror of the registered buffer table on success.
     */
    int unregisterBuffers() {
        long res = register(this.callState, this.ringFd, IORING_UNREGISTER_BUFFERS, MemorySegment.NULL, 0L);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        setRegisteredBuffers(null);
        return 0;
    }

    /**
     * Best-effort update of registered buffers through {@code IORING_REGISTER_BUFFERS_UPDATE}.
     * The kernel takes a {@code struct io_uring_rsrc_update2} (32 bytes) whose {@code nr_args}
     * size is {@code sizeof(struct io_uring_rsrc_update2)}, not the number of buffers.
     * Returns 0 on success or {@code -errno} (never throws for kernel rejection).
     */
    int registerBuffersUpdate(int offset, MemorySegment[] buffers) {
        int count = buffers.length;
        if (count == 0) {
            return 0;
        }
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment iovecArray = Iovec.allocateArray(count, tempArena);
            for (int i = 0; i < count; i++) {
                MemorySegment nthIovec = Iovec.asSlice(iovecArray, i);
                Iovec.iov_base(nthIovec, buffers[i]);
                Iovec.iov_len(nthIovec, buffers[i].byteSize());
            }
            MemorySegment up = tempArena.allocate(32L, 8L);
            up.set(JAVA_INT, 0L, offset);
            up.set(JAVA_INT, 4L, 0);
            up.set(JAVA_LONG, 8L, iovecArray.address());
            up.set(JAVA_LONG, 16L, 0L);
            up.set(JAVA_INT, 24L, count);
            up.set(JAVA_INT, 28L, 0);
            long res = register(this.callState, this.ringFd, IORING_REGISTER_BUFFERS_UPDATE, up, 32L);
            if (res < 0) {
                return -LibCDispatcher.errno(this.callState);
            }
            return (int) res;
        }
    }

    /**
     * Unregisters the whole registered file table. Returns 0 on success or {@code -errno}.
     */
    int unregisterFiles() {
        long res = register(this.callState, this.ringFd, IORING_UNREGISTER_FILES, MemorySegment.NULL, 0L);
        if (res < 0) {
            return -LibCDispatcher.errno(this.callState);
        }
        return 0;
    }

    /**
     * Number of SQEs prepared but not yet completed (the counter also decremented when completions
     * are reaped). Used by {@code JuringFile}'s ownership guard.
     */
    int inFlightCount() {
        return inFlightCount;
    }

    int registerFiles(MemorySegment fdArray, int count) {
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

            int ret = registerFiles(fdArray, count);
            if (ret < 0) {
                throw new RuntimeException("Failed to register files: " + libCDispatcher.strerror(-ret));
            }
            return ret;
        }
    }

    int registerFilesUpdate(int offset, MemorySegment fdArray, int count) {
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

            int ret = registerFilesUpdate(offset, fdArray, count);
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

    /** Number of SQEs prepared but not yet submitted to the kernel. */
    int pendingSqes() {
        if (sqKheadAddr == 0L) {
            return 0;
        }
        int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
        return sqeTail - head;
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
            case 10 /* READ_FIXED */ -> {
                int bufferIdx = UserData.getBufferIndex(userData);
                long regBufAddr = (registeredBufferAddresses != null && bufferIdx >= 0 && bufferIdx < registeredBufferAddresses.length)
                        ? registeredBufferAddresses[bufferIdx] : 0L;
                target.set(userData, result, OperationType.READ_FIXED, regBufAddr, null, bufferIdx, null, cqeFlags);
            }
            case 2 /* WRITE_FIXED */ -> {
                int bufferIdx = UserData.getBufferIndex(userData);
                target.set(userData, result, OperationType.WRITE_FIXED, 0L, null, bufferIdx, null, cqeFlags);
            }
            case 0 /* READ */, 11 /* READ_POOLED */, 14 /* READ_EXT */ -> {
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
                    buffers[i] = MemorySegment.ofAddress(base).reinterpret(valid);
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
            case 9 /* CANCEL */ -> {
                target.set(userData, result, OperationType.CANCEL, 0L, null, -1, null, cqeFlags);
            }
            case 12 /* NOP */ -> {
                target.set(userData, result, OperationType.NOP, 0L, null, -1, null, cqeFlags);
            }
            case 13 /* WRITE_EXT */ -> {
                target.set(userData, result, OperationType.WRITE_EXT, 0L, null, -1, null, cqeFlags);
            }
            case 15 /* FSYNC */ -> {
                target.set(userData, result, OperationType.FSYNC, 0L, null, -1, null, cqeFlags);
            }
            case 16 /* TIMEOUT */ -> {
                target.set(userData, result, OperationType.TIMEOUT, 0L, null, -1, null, cqeFlags);
            }
            case 17 /* FALLOCATE */ -> {
                target.set(userData, result, OperationType.FALLOCATE, 0L, null, -1, null, cqeFlags);
            }
            case 18 /* FTRUNCATE */ -> {
                target.set(userData, result, OperationType.FTRUNCATE, 0L, null, -1, null, cqeFlags);
            }
            case 19 /* STATX */ -> {
                target.set(userData, result, OperationType.STATX, 0L, null, -1, null, cqeFlags);
            }
            case 20 /* LINK_TIMEOUT */ -> {
                // A linked timeout completion owns no user buffer: it only reports the timer
                // result (-ETIME when it fired, -ECANCELED when the target won the race).
                target.set(userData, result, OperationType.LINK_TIMEOUT, 0L, null, -1, null, cqeFlags);
            }
            default -> {
                // Unknown/unmatched user_data type (e.g. an internal sentinel): report it as a
                // NOP-shaped completion with no buffer ownership instead of leaving the reusable
                // Result populated with stale ownership fields.
                target.set(userData, result, OperationType.NOP, 0L, null, -1, null, cqeFlags);
            }
        }
    }

    void closeRing() {
        queueExit();
    }

    // ------------------------------------------------------------------
    // Bounded shutdown (B3)
    // ------------------------------------------------------------------

    /** Default deadline for {@link #close()} before the ring is force-closed. */
    static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;

    /** Park slice used by the bounded drain loops (100 µs). */
    private static final long CLOSE_POLL_SLICE_NANOS = 100_000L;

    /** Completions with {@code -ECANCELED} reaped by the most recent bounded close. */
    private long cancelledAtClose;
    /** Requests still in flight when the most recent bounded close force-closed the ring. */
    private int abandonedAtClose;

    long cancelledAtClose() {
        return cancelledAtClose;
    }

    int abandonedAtClose() {
        return abandonedAtClose;
    }

    /**
     * Non-blocking CQ poll. Asks the kernel to run deferred task work (needed for
     * {@code IORING_SETUP_DEFER_TASKRUN} rings, whose completions are only posted while
     * inside {@code io_uring_enter}) and returns the number of CQEs currently available.
     * Never blocks: the enter uses {@code min_complete=0}.
     *
     * @return number of completions available to reap
     */
    int pollCq() {
        if (closed || ringFd < 0 || cqKheadAddr == 0L) {
            return 0;
        }
        try {
            enter(this.callState, enterRingFd, 0, 0, IORING_ENTER_GETEVENTS | enterFlagsBase,
                    MemorySegment.NULL, 0L);
        } catch (Throwable ignored) {
            // A disabled/broken ring is force-closed by the caller; polling is best effort.
        }
        int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
        return tail - head;
    }

    private final ResultConsumer closeResultConsumer = result -> {
        if ((int) result.result() == -ECANCELED) {
            cancelledAtClose++;
        }
        result.close();
    };

    /** Reaps every completion currently in the CQ ring (non-blocking). */
    private int reapAllAvailable() {
        if (cqKheadAddr == 0L) {
            return 0;
        }
        int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKheadAddr);
        int tail = (int) VH_INT.getAcquire(GLOBAL_MEMORY, cqKtailAddr);
        return reapAvailable(head, tail - head, Integer.MAX_VALUE, closeResultConsumer);
    }

    /**
     * Submits a best-effort {@code IORING_OP_ASYNC_CANCEL} with {@code ALL | ANY}: every
     * request currently queued/in flight on the ring is cancelled. Idempotent and quiet;
     * returns {@code true} when a cancel request was submitted.
     */
    private boolean submitCancelAllForClose() {
        try {
            int head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
            if (sqeTail - head >= sqRingEntries) {
                submit();
                head = (int) VH_INT.getAcquire(GLOBAL_MEMORY, sqKheadAddr);
            }
            long sqe = getSqe();
            if (sqe == 0L) {
                submit();
                sqe = getSqe();
            }
            if (sqe == 0L) {
                return false;
            }
            Sqe.setOpcode(sqe, IORING_OP_ASYNC_CANCEL);
            Sqe.setFd(sqe, -1);
            Sqe.setOff(sqe, 0L);
            Sqe.setAddr(sqe, 0L);
            Sqe.setLen(sqe, 0);
            Sqe.setRwFlags(sqe, IORING_ASYNC_CANCEL_ALL | IORING_ASYNC_CANCEL_ANY);
            // A well-formed user_data type keeps populateResultFromCqe's reusable Result clean.
            Sqe.setUserData(sqe, UserData.encode(OperationType.CANCEL, 0L));
            submit();
            return true;
        } catch (Throwable ignored) {
            // Disabled rings or a full SQ at teardown: fall through to the bounded drain.
            return false;
        }
    }

    /**
     * Drains the CQ ring until no request is in flight or the deadline expires. Never blocks
     * longer than the deadline; uses non-blocking polls + {@code enter(min_complete=0)} (to
     * flush deferred task work) + short parks.
     *
     * @return number of requests still in flight at return (0 on a clean drain)
     */
    private int drainToIdleBounded(long deadlineNanos) {
        while (inFlightCount > 0) {
            if (reapAllAvailable() > 0) {
                continue;
            }
            if (pollCq() > 0) {
                continue;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            LockSupport.parkNanos(Math.min(remaining, CLOSE_POLL_SLICE_NANOS));
        }
        return inFlightCount;
    }

    /**
     * Bounded ring shutdown: submits queued SQEs, cancels every in-flight request
     * ({@code ASYNC_CANCEL ALL|ANY}), reaps completions with a deadline enforced by
     * non-blocking polls and short parks, then force-closes the ring. Never throws and never
     * waits longer than {@code timeoutMillis} for completions (plus the final force-close
     * which is syscall-bound and cannot block on I/O).
     * <p>
     * When the deadline expires, requests that never completed are counted in
     * {@link #abandonedAtClose()} and their CQEs are dropped by the munmap; the kernel
     * releases the requests with the ring fd. Completions with {@code -ECANCELED} reaped
     * during the drain are counted in {@link #cancelledAtClose()}. Idempotent.
     *
     * @param timeoutMillis deadline in milliseconds, {@code >= 0} ({@code 0} = cancel and
     *                      force-close immediately after one reap pass)
     * @return number of abandoned requests
     */
    int closeBounded(long timeoutMillis) {
        if (closed) {
            return abandonedAtClose;
        }
        try {
            if (ringFd >= 0 && sqKheadAddr != 0L) {
                try {
                    submit();
                } catch (Throwable ignored) {
                    // Best effort: force-close below.
                }
                if (inFlightCount > 0) {
                    submitCancelAllForClose();
                }
                long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
                drainToIdleBounded(deadline);
                if (inFlightCount > 0) {
                    // Last chance: collect completions that are already posted before the
                    // mmap is torn down, so they are not lost silently.
                    reapAllAvailable();
                }
                abandonedAtClose = Math.max(0, inFlightCount);
            }
        } finally {
            closed = true;
            closeRing();
            iovecBlockPool.close();
            arena.close();
        }
        return abandonedAtClose;
    }

    @Override
    public void close() {
        closeBounded(DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }
}
