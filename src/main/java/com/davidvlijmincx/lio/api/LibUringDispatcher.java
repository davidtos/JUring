package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.util.ArrayList;
import java.util.List;

import static com.davidvlijmincx.lio.api.DirectoryFileDescriptorFlags.AT_FDCWD;
import static java.lang.foreign.ValueLayout.*;

record LibUringDispatcher(Arena arena,
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
                          PrepareReadFixed prepReadFixed,
                          PrepareWrite prepWrite,
                          PrepareWriteFixed prepWriteFixed,
                          Submit submitOp,
                          WaitCqe waitCqe,
                          PeekCqe peekCqe,
                          PeekBatchCqe peekBatchCqe,
                          CqeSeen cqeSeen,
                          QueueInit queueInit,
                          QueueExit queueExit,
                          SqeSetData sqeSetData,
                          RegisterBuffers registerBuffers,
                          RegisterFiles registerFiles,
                          RegisterFilesUpdate registerFilesUpdate,
                          CqAdvance cqAdvance,
                          WaitCqeNr waitCqeNr,
                          RegisterIowqMaxWorkers registerIowqMaxWorkers) implements AutoCloseable {

    private static final AddressLayout C_POINTER = ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));
    private static final Linker linker = Linker.nativeLinker();
    private static final SymbolLookup liburing = SymbolLookup.libraryLookup("liburing-ffi.so", Arena.ofAuto());
    private static final LibCDispatcher libCDispatcher = NativeDispatcher.C;

    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;
    private static final GroupLayout io_uring_cqe_layout;


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
    }

    static LibUringDispatcher create(int queueDepth, IoUringOptions... ioUringOptions) {
        Arena arena = Arena.ofShared();

        LibUringDispatcher dispatcher = new LibUringDispatcher(arena, arena.allocate(ring_layout), libCDispatcher.alloc(AddressLayout.ADDRESS.byteSize()), libCDispatcher.alloc(AddressLayout.ADDRESS.byteSize() * 500),
                libLink(GetSqe.class, "io_uring_get_sqe", FunctionDescriptor.of(ADDRESS, ADDRESS), true),
                libLink(SetSqeFlag.class, "io_uring_sqe_set_flags", FunctionDescriptor.ofVoid(C_POINTER, JAVA_BYTE), true),
                libLink(PrepOpenAt.class, "io_uring_prep_openat", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT, JAVA_INT), false),
                libLink(PrepareOpenDirect.class, "io_uring_prep_openat_direct", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT, JAVA_INT, JAVA_INT), false),
                libLink(PrepareClose.class, "io_uring_prep_close", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT), false),
                libLink(PrepareCloseDirect.class, "io_uring_prep_close_direct", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT), false),
                libLink(PrepareRead.class, "io_uring_prep_read", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG), false),
                libLink(PrepareReadFixed.class, "io_uring_prep_read_fixed", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG, JAVA_INT), false),
                libLink(PrepareWrite.class, "io_uring_prep_write", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG), false),
                libLink(PrepareWriteFixed.class, "io_uring_prep_write_fixed", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG, JAVA_INT), false),
                libLink(Submit.class, "io_uring_submit", FunctionDescriptor.of(JAVA_INT, ADDRESS), true),
                libLink(WaitCqe.class, "io_uring_wait_cqe", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER), false),
                libLink(PeekCqe.class, "io_uring_peek_cqe", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER), false),
                libLink(PeekBatchCqe.class, "io_uring_peek_batch_cqe", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT), false),
                libLink(CqeSeen.class, "io_uring_cqe_seen", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), true),
                libLink(QueueInit.class, "io_uring_queue_init", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), false),
                libLink(QueueExit.class, "io_uring_queue_exit", FunctionDescriptor.ofVoid(ADDRESS), false),
                libLink(SqeSetData.class, "io_uring_sqe_set_data", FunctionDescriptor.ofVoid(C_POINTER, JAVA_LONG), false),
                libLink(RegisterBuffers.class, "io_uring_register_buffers", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT), false),
                libLink(RegisterFiles.class, "io_uring_register_files", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT), false),
                libLink(RegisterFilesUpdate.class, "io_uring_register_files_update", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, C_POINTER, JAVA_INT), false),
                libLink(CqAdvance.class, "io_uring_cq_advance", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT), true),
                libLink(WaitCqeNr.class, "io_uring_wait_cqe_nr", FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT), false),
                libLink(RegisterIowqMaxWorkers.class, "io_uring_register_iowq_max_workers", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), false)
        );

        int ret = dispatcher.queueInit(queueDepth, IoUringOptions.combineOptions(ioUringOptions));
        dispatcher.registerIowqMaxWorkers(4,3);
        if (ret < 0) {
            throw new RuntimeException("Failed to initialize queue " + libCDispatcher.strerror(ret));
        }

        return dispatcher;
    }

    private static <T> T libLink(Class<T> type, String name, FunctionDescriptor descriptor, boolean critical) {
        MemorySegment symbol = liburing.findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.critical(critical));
        return MethodHandleProxies.asInterfaceInstance(type, handle);
    }

    MemorySegment getSqe() {
        return sqe.getSqe(ring);
    }

    void setSqeFlag(MemorySegment sqe, SqeOptions... flags) {
        setSqeFlag.setSqeFlag(sqe, SqeOptions.combineOptions(flags));
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

    void prepareReadFixed(MemorySegment sqe, int fd, MemorySegment buffer, long offset, int bufferIndex) {
        prepReadFixed.prepareReadFixed(sqe, fd, buffer, buffer.byteSize(), offset, bufferIndex);
    }

    void prepareWrite(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        prepWrite.prepareWrite(sqe, fd, buffer, buffer.byteSize(), offset);
    }

    void prepareWriteFixed(MemorySegment sqe, int fd, MemorySegment buffer, long nbytes, long offset, int bufferIndex) {
        prepWriteFixed.prepareWriteFixed(sqe, fd, buffer, nbytes, offset, bufferIndex);
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
                var nativeCqe = cqePtrPtr.getAtIndex(ADDRESS, i).reinterpret(io_uring_cqe_layout.byteSize());

                long userData = nativeCqe.get(JAVA_LONG, 0);
                int res = nativeCqe.get(JAVA_INT, 8);

                ret.add(getResultFromCqe(userData, res));
            }

            cqAdvance.peekBatchCqe(ring, count);
            return ret;
        }
        return List.of();
    }

    List<Result> waitForBatchResult(int batchSize) {
        int status = waitCqeNr.waitForCqeNr(ring, cqePtrPtr, batchSize);
        if (status < 0) {
            throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(status));
        }
        int count = peekBatchCqe(ring, cqePtrPtr, batchSize);

        List<Result> ret = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            var nativeCqe = cqePtrPtr.getAtIndex(ADDRESS, i).reinterpret(io_uring_cqe_layout.byteSize());

            long userData = nativeCqe.get(JAVA_LONG, 0);
            int res = nativeCqe.get(JAVA_INT, 8);

            ret.add(getResultFromCqe(userData, res));
        }

        cqAdvance.peekBatchCqe(ring, count);
        return ret;
    }

    Result waitForResult() {
        int ret = waitCqe(ring, cqePtr);
        if (ret < 0) {
            throw new RuntimeException("Error while waiting for cqe: " + libCDispatcher.strerror(ret));
        }

        var nativeCqe = cqePtr.getAtIndex(ADDRESS, 0).reinterpret(io_uring_cqe_layout.byteSize());

        long userData = nativeCqe.get(JAVA_LONG, 0);
        int res = nativeCqe.get(JAVA_INT, 8);

        Result result = getResultFromCqe(userData, res);
        cqeSeen.cqeSeen(ring, nativeCqe);
        return result;
    }

    private Result getResultFromCqe(long address, long result) {
        MemorySegment nativeUserData = MemorySegment.ofAddress(address).reinterpret(UserData.getByteSize());

        OperationType type = UserData.getType(nativeUserData);
        long id = UserData.getId(nativeUserData);
        MemorySegment bufferResult = UserData.getBuffer(nativeUserData);

        libCDispatcher.free(nativeUserData);

        if (OperationType.READ.equals(type)) {
            return new ReadResult(id, bufferResult, result);
        } else if (OperationType.WRITE.equals(type)) {
            libCDispatcher.free(bufferResult);
            return new WriteResult(id, result);
        } else if (OperationType.WRITE_FIXED.equals(type)) {
            return new WriteResult(id, result);
        } else if (OperationType.OPEN.equals(type)) {
            libCDispatcher.free(bufferResult);
            return new OpenResult(id, (int) result);
        } else if (OperationType.CLOSE.equals(type)) {
            return new CloseResult(id, (int) result);
        }

        throw new IllegalStateException("Unexpected result type: " + type);
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
        arena.close();
    }

    @Override
    public void close() {
        closeRing();
        libCDispatcher.free(cqePtr);
        libCDispatcher.free(cqePtrPtr);
        closeArena();
    }

}
