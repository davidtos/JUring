package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

import static com.davidvlijmincx.lio.api.DirectoryFileDescriptorFlags.AT_FDCWD;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_ATTACH_WQ;
import static java.lang.foreign.ValueLayout.*;

record LibUringDispatcher(Arena arena,
                          UserDataPool userDataPool,
                          IovecBlockPool iovecBlockPool,
                          MemorySegment ring,
                          MemorySegment cqePtr,
                          MemorySegment cqePtrPtr,
                          GetSqe sqe,
                          SetSqeFlag setSqeFlag,
                          PrepOpenAt prepOpenAt,
                          PrepareOpenDirect prepOpenDirectAt,
                          PrepareClose prepClose,
                          PrepareCloseDirect prepCloseDirect,
                          PrepareRead prepRead,
                          PrepareReadAddress prepareReadAddress,
                          PrepareReadFixed prepReadFixed,
                          PrepareWrite prepWrite,
                          PrepareWriteFixed prepWriteFixed,
                          Submit submitOp,
                          WaitCqe waitCqe,
                          PeekCqe peekCqe,
                          PeekBatchCqe peekBatchCqe,
                          CqeSeen cqeSeen,
                          QueueInit queueInit,
                          QueueInitParams queueInitParams,
                          QueueExit queueExit,
                          SqeSetData sqeSetData,
                          RegisterBuffers registerBuffers,
                          RegisterFiles registerFiles,
                          RegisterFilesUpdate registerFilesUpdate,
                          CqAdvance cqAdvance,
                          WaitCqeNr waitCqeNr,
                          RegisterIowqMaxWorkers registerIowqMaxWorkers,
                          PrepareReadv prepReadv,
                          PrepareReadvAddress prepReadvAddress,
                          PrepareWritev prepWritev,
                          PrepareWritevAddress prepWritevAddress,
                          PrepareAccept prepAccept,
                          PrepareMultishotAccept prepMultishotAccept,
                          PrepareConnect prepConnect,
                          PrepareRecv prepRecv,
                          PrepareSend prepSend,
                          PrepareCancel prepCancel) implements AutoCloseable {

    /** IORING_CQE_F_MORE: set in cqe->flags when a multishot SQE will generate more CQEs. */
    static final int IORING_CQE_F_MORE = 1 << 1;

    private static final AddressLayout C_POINTER = ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));
    private static final Linker linker = Linker.nativeLinker();
    private static final SymbolLookup liburing = SymbolLookup.libraryLookup("liburing-ffi.so", Arena.ofAuto());
    private static final LibCDispatcher libCDispatcher = NativeDispatcher.C;

    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;
    private static final GroupLayout io_uring_cqe_layout;

    private static final VarHandle ringFdHandle;
    private static final VarHandle ringFlagHandle;
    private static final VarHandle ringFeaturesandle;


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
        ringFlagHandle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("int_flags"));
        ringFeaturesandle = ring_layout.varHandle(MemoryLayout.PathElement.groupElement("features"));
    }

    static LibUringDispatcher create(int queueDepth, IoUringOptions... ioUringOptions) {
        MemorySegment ring = NativeDispatcher.C.malloc(ring_layout.byteSize());

        LibUringDispatcher dispatcher = getDispatcher(ring, queueDepth);


        int ret = dispatcher.queueInit(queueDepth, IoUringOptions.combineOptions(ioUringOptions));
     //  dispatcher.registerIowqMaxWorkers(1,1);
        if (ret < 0) {
            throw new RuntimeException("Failed to initialize queue " + libCDispatcher.strerror(ret));
        }

        int ring_fd = (int) ringFdHandle.get(ring, 0L);

        return dispatcher;
    }

    private static LibUringDispatcher getDispatcher(MemorySegment ring, int queueDepth) {
        return new LibUringDispatcher(Arena.ofShared(), new UserDataPool(queueDepth), new IovecBlockPool(queueDepth), ring, libCDispatcher.alloc(AddressLayout.ADDRESS.byteSize()), libCDispatcher.alloc(AddressLayout.ADDRESS.byteSize() * 500),
                libLink(GetSqe.class, "io_uring_get_sqe", FunctionDescriptor.of(ADDRESS, ADDRESS), true),
                libLink(SetSqeFlag.class, "io_uring_sqe_set_flags", FunctionDescriptor.ofVoid(C_POINTER, JAVA_BYTE), true),
                libLink(PrepOpenAt.class, "io_uring_prep_openat", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT, JAVA_INT), false),
                libLink(PrepareOpenDirect.class, "io_uring_prep_openat_direct", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT, JAVA_INT, JAVA_INT), false),
                libLink(PrepareClose.class, "io_uring_prep_close", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT), false),
                libLink(PrepareCloseDirect.class, "io_uring_prep_close_direct", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT), false),
                libLink(PrepareRead.class, "io_uring_prep_read", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG), false),
                libLink(PrepareReadAddress.class, "io_uring_prep_read", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG), false),
                libLink(PrepareReadFixed.class, "io_uring_prep_read_fixed", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG, JAVA_INT), false),
                libLink(PrepareWrite.class, "io_uring_prep_write", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG), false),
                libLink(PrepareWriteFixed.class, "io_uring_prep_write_fixed", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG, JAVA_INT), false),
                libLink(Submit.class, "io_uring_submit", FunctionDescriptor.of(JAVA_INT, ADDRESS), true),
                libLink(WaitCqe.class, "io_uring_wait_cqe", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER), false),
                libLink(PeekCqe.class, "io_uring_peek_cqe", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER), false),
                libLink(PeekBatchCqe.class, "io_uring_peek_batch_cqe", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT), false),
                libLink(CqeSeen.class, "io_uring_cqe_seen", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), true),
                libLink(QueueInit.class, "io_uring_queue_init", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), false),
                libLink(QueueInitParams.class, "io_uring_queue_init_params", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS), false),
                libLink(QueueExit.class, "io_uring_queue_exit", FunctionDescriptor.ofVoid(ADDRESS), false),
                libLink(SqeSetData.class, "io_uring_sqe_set_data", FunctionDescriptor.ofVoid(C_POINTER, JAVA_LONG), false),
                libLink(RegisterBuffers.class, "io_uring_register_buffers", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT), false),
                libLink(RegisterFiles.class, "io_uring_register_files", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT), false),
                libLink(RegisterFilesUpdate.class, "io_uring_register_files_update", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, C_POINTER, JAVA_INT), false),
                libLink(CqAdvance.class, "io_uring_cq_advance", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT), true),
                libLink(WaitCqeNr.class, "io_uring_wait_cqe_nr", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT), false),
                libLink(RegisterIowqMaxWorkers.class, "io_uring_register_iowq_max_workers", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), false),
                libLink(PrepareReadv.class, "io_uring_prep_readv", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT, JAVA_LONG), false),
                libLink(PrepareReadvAddress.class, "io_uring_prep_readv", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG), false),
                libLink(PrepareWritev.class, "io_uring_prep_writev", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT, JAVA_LONG), false),
                libLink(PrepareWritevAddress.class, "io_uring_prep_writev", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG), false),
                // io_uring_prep_accept(sqe, fd, addr*, addrlen*, flags)
                libLink(PrepareAccept.class, "io_uring_prep_accept", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, C_POINTER, JAVA_INT), false),
                // io_uring_prep_multishot_accept(sqe, fd, addr*, addrlen*, flags)
                libLink(PrepareMultishotAccept.class, "io_uring_prep_multishot_accept", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, C_POINTER, JAVA_INT), false),
                // io_uring_prep_connect(sqe, fd, addr*, addrlen)
                libLink(PrepareConnect.class, "io_uring_prep_connect", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT), false),
                // io_uring_prep_recv(sqe, fd, buf*, len, flags)
                libLink(PrepareRecv.class, "io_uring_prep_recv", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_INT), false),
                // io_uring_prep_send(sqe, fd, buf*, len, flags)
                libLink(PrepareSend.class, "io_uring_prep_send", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_INT), false),
                // io_uring_prep_cancel64(sqe, user_data, flags) — cancel by 64-bit user_data value
                libLink(PrepareCancel.class, "io_uring_prep_cancel64", FunctionDescriptor.ofVoid(C_POINTER, JAVA_LONG, JAVA_INT), false)
        );
    }

    private static <T> T libLink(Class<T> type, String name, FunctionDescriptor descriptor, boolean critical) {
        MemorySegment symbol = liburing.findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.critical(critical));
        return MethodHandleProxies.asInterfaceInstance(type, handle);
    }

    /*
     IORING_SETUP_ATTACH_WQ
              This flag should be set in conjunction with struct
              io_uring_params.wq_fd being set to an existing io_uring
              ring file descriptor. When set, the io_uring instance being
              created will share the asynchronous worker thread backend
              of the specified io_uring ring, rather than create a new
              separate thread pool. Additionally the sq polling thread
              will be shared, if IORING_SETUP_SQPOLL is set.
     */
    public LibUringDispatcher getSharedWorkerRing(int queueDepth, IoUringOptions... ioUringOptions){
        MemorySegment ring = NativeDispatcher.C.malloc(ring_layout.byteSize());
        LibUringDispatcher dispatcher = getDispatcher(ring, queueDepth);
        MemorySegment params = NativeDispatcher.C.calloc(io_uring_params.layout().byteSize());

        int ring_fd = (int) ringFdHandle.get(this.ring, 0L); // this. is the parent (ring)

        var result = IoUringOptions.combineOptions(ioUringOptions);

        io_uring_params.flags(params, result | IORING_SETUP_ATTACH_WQ.value);
        io_uring_params.wq_fd(params, ring_fd);

        var ret = dispatcher.queueInitParams(queueDepth, ring, params);

        if (ret < 0){
            throw new RuntimeException("ret = " + ret + " " + NativeDispatcher.C.strerror(ret));
        }

        return dispatcher;
    }

    long allocateUserData(long id, int fd, OperationType type, MemorySegment buffer) {
        long address = userDataPool.checkOut();
        ZeroGcUserData.write(address, id, fd, type, buffer);
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
        return sqe.getSqe(ring);
    }

    void setSqeFlag(MemorySegment sqe, SqeOptions... flags) {
        setSqeFlag.setSqeFlag(sqe, SqeOptions.combineOptions(flags));
    }

    void setSqeFlag(MemorySegment sqe, byte flags) {
        setSqeFlag.setSqeFlag(sqe, flags);
    }

    void prepareOpenAt(MemorySegment sqe, MemorySegment filePath, int flags, int mode) {
        prepOpenAt.prepareOpenAt(sqe, AT_FDCWD.value, filePath, flags, mode);
    }

    void prepareOpenDirectAt(MemorySegment sqe, MemorySegment filePath, int flags, int mode, int fileIndex) {
        prepOpenDirectAt.prepareOpenDirectAt(sqe, AT_FDCWD.value, filePath, flags, mode, fileIndex);
    }

    void prepareClose(MemorySegment sqe, int fd) {
        prepClose.prepareClose(sqe, fd);
    }

    void prepareCloseDirect(MemorySegment sqe, int fileIndex) {
        prepCloseDirect.prepareCloseDirect(sqe, fileIndex);
    }

    void prepareRead(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        prepRead.prepareRead(sqe, fd, buffer, buffer.byteSize(), offset);
    }

    void prepareRead(MemorySegment sqe, int fd, long buffer, long size, long offset) {
        prepareReadAddress.prepareRead(sqe, fd, buffer, size, offset);
    }

    void prepareReadFixed(MemorySegment sqe, int fd, MemorySegment buffer, long nbytes, long offset, int bufferIndex) {
        prepReadFixed.prepareReadFixed(sqe, fd, buffer, nbytes, offset, bufferIndex);
    }

    void prepareWrite(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        prepWrite.prepareWrite(sqe, fd, buffer, buffer.byteSize(), offset);
    }

    void prepareWriteFixed(MemorySegment sqe, int fd, MemorySegment buffer, long nbytes, long offset, int bufferIndex) {
        prepWriteFixed.prepareWriteFixed(sqe, fd, buffer, nbytes, offset, bufferIndex);
    }

    void prepareReadv(MemorySegment sqe, int fd, MemorySegment iovecs, int nrVecs, long offset) {
        prepReadv.prepareReadv(sqe, fd, iovecs, nrVecs, offset);
    }

    void prepareWritev(MemorySegment sqe, int fd, MemorySegment iovecs, int nrVecs, long offset) {
        prepWritev.prepareWritev(sqe, fd, iovecs, nrVecs, offset);
    }

    void prepareReadvAddress(MemorySegment sqe, int fd, long iovecAddr, int nrVecs, long offset) {
        prepReadvAddress.prepareReadv(sqe, fd, iovecAddr, nrVecs, offset);
    }

    void prepareWritevAddress(MemorySegment sqe, int fd, long iovecAddr, int nrVecs, long offset) {
        prepWritevAddress.prepareWritev(sqe, fd, iovecAddr, nrVecs, offset);
    }

    void prepareAccept(MemorySegment sqe, int fd, MemorySegment addr, MemorySegment addrLen, int flags) {
        prepAccept.prepareAccept(sqe, fd, addr, addrLen, flags);
    }

    void prepareMultishotAccept(MemorySegment sqe, int fd, MemorySegment addr, MemorySegment addrLen, int flags) {
        prepMultishotAccept.prepareMultishotAccept(sqe, fd, addr, addrLen, flags);
    }

    void prepareConnect(MemorySegment sqe, int fd, MemorySegment addr, int addrLen) {
        prepConnect.prepareConnect(sqe, fd, addr, addrLen);
    }

    void prepareRecv(MemorySegment sqe, int fd, MemorySegment buf, long len, int flags) {
        prepRecv.prepareRecv(sqe, fd, buf, len, flags);
    }

    void prepareSend(MemorySegment sqe, int fd, MemorySegment buf, long len, int flags) {
        prepSend.prepareSend(sqe, fd, buf, len, flags);
    }

    void prepareCancel(MemorySegment sqe, long userDataToCancel, int flags) {
        prepCancel.prepareCancel(sqe, userDataToCancel, flags);
    }

    void submit() {
        int ret = submitOp.submit(ring);
        if (ret < 0) {
            throw new RuntimeException("Failed to submit queue: " + libCDispatcher.strerror(ret));
        }
    }

    int waitCqe(MemorySegment ring, MemorySegment cqePtr) {
        return waitCqe.waitCqe(ring, cqePtr);
    }

    int peekCqe(MemorySegment ring, MemorySegment cqePtr) {
        return peekCqe.peekCqe(ring, cqePtr);
    }

    int peekBatchCqe(MemorySegment ring, MemorySegment cqePtrPtr, int batchSize) {
        return peekBatchCqe.peekBatchCqe(ring, cqePtrPtr, batchSize);
    }

    int queueInit(int queueDepth, int flags) {
        return queueInit.queueInit(queueDepth, this.ring , flags);
    }

    int queueInitParams(int queueDepth, MemorySegment ring, MemorySegment params) {
        return queueInitParams.queueInitParams(queueDepth, ring, params);
    }

    void registerIowqMaxWorkers(int bounded, int unbounded) {

        MemorySegment values = this.arena.allocate(JAVA_INT, 2);
        values.setAtIndex(JAVA_INT, 0, bounded);
        values.setAtIndex(JAVA_INT, 1, unbounded);

        int ret = registerIowqMaxWorkers.registerMaxIoWqWorkers(this.ring, values);
        if (ret < 0) {
            throw new RuntimeException("Failed to register max workers: " + libCDispatcher.strerror(ret));
        }
    }

    void queueExit(MemorySegment ring) {
        queueExit.queueExit(ring);
    }

    void setUserData(MemorySegment sqe, long userData) {
        sqeSetData.sqeSetData(sqe, userData);
    }

    int registerBuffers(MemorySegment ring, MemorySegment iovecs, int nrIovecs) {
        return registerBuffers.registerBuffers(ring, iovecs, nrIovecs);
    }

    int registerFiles(MemorySegment ring, MemorySegment fdArray, int count) {
        return registerFiles.registerFiles(ring, fdArray, count);
    }

    int registerFilesUpdate(MemorySegment ring, int offset, MemorySegment fdArray, int count) {
        return registerFilesUpdate.registerFilesUpdate(ring, offset, fdArray, count);
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

            cqAdvance.peekBatchCqe(ring, count);
            return ret;
        }
        return List.of();
    }

    List<Result> waitForBatchResult(int batchSize) {
        int status = waitCqeNr.waitForCqeNr(ring, cqePtrPtr, batchSize);
        if (status < 0) {
            status = waitCqeNr.waitForCqeNr(ring, cqePtrPtr, batchSize);
            if (status < 0) {
                status = waitCqeNr.waitForCqeNr(ring, cqePtrPtr, batchSize);
                if (status < 0) {
                    throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(status));
                }
            }
        }
        int count = peekBatchCqe(ring, cqePtrPtr, batchSize);

        List<Result> ret = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            long address =  cqePtrPtr.getAtIndex(JAVA_LONG, i);
            long userData = ZeroGcCqe.getUserData(address);
            int res = ZeroGcCqe.getRes(address);
            int cqeFlags = ZeroGcCqe.getFlags(address);

            ret.add(getResultFromCqe(userData, res, cqeFlags));
        }

        cqAdvance.peekBatchCqe(ring, count);
        return ret;
    }

    Result waitForResult() {
        int ret = waitCqe(ring, cqePtr);
        if (ret < 0) {
            throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(ret));
        }

        var nativeCqe = cqePtr.getAtIndex(ADDRESS, 0);

        long userDataAddress = cqePtr.getAtIndex(JAVA_LONG, 0);

        long userData = ZeroGcCqe.getUserData(userDataAddress);
        int res = ZeroGcCqe.getRes(userDataAddress);
        int cqeFlags = ZeroGcCqe.getFlags(userDataAddress);

        Result result = getResultFromCqe(userData, res, cqeFlags);
        cqeSeen.cqeSeen(ring, nativeCqe);
        return result;
    }

    private Result getResultFromCqe(long userDataAddress, long result, int cqeFlags) {
        OperationType type = ZeroGcUserData.getType(userDataAddress);
        long id = ZeroGcUserData.getId(userDataAddress);

        return switch (type) {
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
                // block layout: [ long count | iovec[0] | iovec[1] | ... ]
                long blockAddr = ZeroGcUserData.getBufferAddress(userDataAddress);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);
                MemorySegment[] buffers = new MemorySegment[count];
                for (int i = 0; i < count; i++) {
                    buffers[i] = ZeroGcIovecBlock.getIovBaseSegment(blockAddr, i);
                }
                // return the outer block to pool (count + iovec array); caller owns the data buffers
                iovecBlockPool.checkIn(blockAddr);
                userDataPool.checkIn(userDataAddress);
                yield new ReadvResult(id, buffers, result);
            }
            case WRITEV -> {
                // block layout: [ long count | iovec[0] | iovec[1] | ... ]
                long blockAddr = ZeroGcUserData.getBufferAddress(userDataAddress);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);
                for (int i = 0; i < count; i++) {
                    libCDispatcher.free(ZeroGcIovecBlock.getIovBase(blockAddr, i));
                }
                // return the outer block to pool
                iovecBlockPool.checkIn(blockAddr);
                userDataPool.checkIn(userDataAddress);
                yield new WriteResult(id, result);
            }
            case READV_FIXED -> {
                // block layout: [ long count | iovec[0] | iovec[1] | ... ]
                // iov_base entries point into registered (kernel-pinned) buffers — do NOT free them
                long blockAddr = ZeroGcUserData.getBufferAddress(userDataAddress);
                int count = (int) ZeroGcIovecBlock.getCount(blockAddr);
                MemorySegment[] buffers = new MemorySegment[count];
                for (int i = 0; i < count; i++) {
                    buffers[i] = ZeroGcIovecBlock.getIovBaseSegment(blockAddr, i);
                }
                // return the outer iovec wrapper block to pool; registered buffers are owned by the caller
                iovecBlockPool.checkIn(blockAddr);
                userDataPool.checkIn(userDataAddress);
                yield new ReadvResult(id, buffers, result);
            }
            case WRITEV_FIXED -> {
                // iov_base entries point into registered buffers — do NOT free them
                long blockAddr = ZeroGcUserData.getBufferAddress(userDataAddress);
                // return the outer iovec wrapper block to pool
                iovecBlockPool.checkIn(blockAddr);
                userDataPool.checkIn(userDataAddress);
                yield new WriteResult(id, result);
            }
            case ACCEPT -> {
                // For accept, buffer holds the malloc'd sockaddr_in when address capture
                // was requested, or 0 (NULL) when called with addr=NULL.
                long addrAddress = ZeroGcUserData.getBufferAddress(userDataAddress);
                if (addrAddress != 0L) {
                    libCDispatcher.free(addrAddress);
                }
                userDataPool.checkIn(userDataAddress);
                // result is the accepted fd (>= 0) or negative errno
                yield new AcceptResult(id, (int) result);
            }
            case MULTISHOT_ACCEPT -> {
                // IORING_CQE_F_MORE (bit 1) set means the multishot SQE is still active —
                // do NOT free user_data yet; more CQEs will arrive for this SQE.
                boolean morecoming = (cqeFlags & IORING_CQE_F_MORE) != 0;
                if (!morecoming) {
                    // Multishot is done (cancelled, error, or final completion) — free resources.
                    long addrAddress = ZeroGcUserData.getBufferAddress(userDataAddress);
                    if (addrAddress != 0L) {
                        libCDispatcher.free(addrAddress);
                    }
                    userDataPool.checkIn(userDataAddress);
                }
                // result is the accepted fd (>= 0) or negative errno
                yield new AcceptResult(id, (int) result);
            }
            case CONNECT -> {
                // buffer holds the malloc'd sockaddr_in; free it now
                long connectAddrAddress = ZeroGcUserData.getBufferAddress(userDataAddress);
                if (connectAddrAddress != 0L) {
                    libCDispatcher.free(connectAddrAddress);
                }
                userDataPool.checkIn(userDataAddress);
                yield new ConnectResult(id, (int) result);
            }
            case RECV -> {
                // malloc'd buffer — returned to caller in RecvResult; caller frees via freeBuffer()
                MemorySegment buffer = ZeroGcUserData.getBufferSegment(userDataAddress);
                userDataPool.checkIn(userDataAddress);
                yield new RecvResult(id, buffer, result);
            }
            case RECV_EXT -> {
                // caller-supplied buffer — not freed here
                MemorySegment buffer = ZeroGcUserData.getBufferSegment(userDataAddress);
                userDataPool.checkIn(userDataAddress);
                yield new RecvResult(id, buffer, result);
            }
            case SEND -> {
                // malloc'd buffer — free it now; caller doesn't need it
                libCDispatcher.free(ZeroGcUserData.getBufferAddress(userDataAddress));
                userDataPool.checkIn(userDataAddress);
                yield new SendResult(id, result);
            }
            case SEND_EXT -> {
                // caller-supplied buffer — not freed here
                userDataPool.checkIn(userDataAddress);
                yield new SendResult(id, result);
            }
            case CANCEL -> {
                // CQE for the cancel-request SQE itself.
                // result == 0: cancellation was submitted successfully.
                // result == -ENOENT: target not found (already completed).
                // result == -EALREADY: target is already completing.
                userDataPool.checkIn(userDataAddress);
                yield new CloseResult(id, (int) result);
            }
        };
    }

    MemorySegment[] registerBuffers(int bufferSize, int nrIovecs) {
        var iovecStructure = libCDispatcher.allocateIovec(arena, bufferSize, nrIovecs);
        registerBuffers(ring, iovecStructure.iovecArray(), nrIovecs);
        return iovecStructure.buffers();
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
    }

}
