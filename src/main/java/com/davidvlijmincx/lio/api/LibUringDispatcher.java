package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.*;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

record LibUringDispatcher (Arena arena,
                           MemorySegment ring,
                           MemorySegment cqePtr,
                           MemorySegment cqePtrPtr,
                           GetSqe sqe,
                           SetSqeFlag setSqeFlag,
                           PrepOpenAt prepOpenAt,
                           PrepareOpenDirect prepOpenDirect,
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
                           RegisterFilesUpdate registerFilesUpdate) implements AutoCloseable {

    private static final AddressLayout C_POINTER = ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));

    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;
    private static final GroupLayout io_uring_cqe_layout;

    private static final int IORING_SETUP_IOPOLL = 1 << 0;           // 1
    private static final int IORING_SETUP_SQPOLL = 1 << 1;           // 2
    private static final int IORING_SETUP_SQ_AFF = 1 << 2;           // 4
    private static final int IORING_SETUP_CQSIZE = 1 << 3;           // 8
    private static final int IORING_SETUP_CLAMP = 1 << 4;            // 16
    private static final int IORING_SETUP_ATTACH_WQ = 1 << 5;        // 32
    private static final int IORING_SETUP_R_DISABLED = 1 << 6;       // 64
    private static final int IORING_SETUP_SUBMIT_ALL = 1 << 7;       // 128
    private static final int IORING_SETUP_COOP_TASKRUN = 1 << 8;     // 256
    private static final int IORING_SETUP_TASKRUN_FLAG = 1 << 9;     // 512
    private static final int IORING_SETUP_SQE128 = 1 << 10;          // 1024
    private static final int IORING_SETUP_CQE32 = 1 << 11;           // 2048
    private static final int IORING_SETUP_SINGLE_ISSUER = 1 << 12;   // 4096
    private static final int IORING_SETUP_DEFER_TASKRUN = 1 << 13;   // 8192
    private static final int IORING_SETUP_NO_MMAP = 1 << 14;         // 16384
    private static final int IORING_SETUP_REGISTERED_FD_ONLY = 1 << 15; // 32768
    private static final int IORING_SETUP_NO_SQARRAY = 1 << 16;      // 65536
    private static final int IORING_SETUP_HYBRID_IOPOLL = 1 << 17;   // 131072

    // SQE flags
    private static final int AT_FDCWD = (int) -100L;
    private static final byte IOSQE_FIXED_FILE = (byte) (1 << 0);    // 0x01
    private static final byte IOSQE_IO_DRAIN = (byte) (1 << 1);     // 0x02
    private static final byte IOSQE_IO_LINK = (byte) (1 << 2);      // 0x04
    private static final byte IOSQE_IO_HARDLINK = (byte) (1 << 3);  // 0x08
    private static final byte IOSQE_ASYNC = (byte) (1 << 4);        // 0x10
    private static final byte IOSQE_BUFFER_SELECT = (byte) (1 << 5); // 0x20
    private static final byte IOSQE_CQE_SKIP_SUCCESS = (byte) (1 << 6); // 0x40


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

    public LibUringDispatcher(int queueDepth, Arena arena, GetSqe sqe, SetSqeFlag setSqeFlag, PrepOpenAt prepOpenAt, PrepareOpenDirect prepOpenDirect, PrepareClose prepClose, PrepareCloseDirect prepCloseDirect, PrepareRead prepRead, PrepareReadFixed prepReadFixed, PrepareWrite prepWrite, PrepareWriteFixed prepWriteFixed, Submit submit, WaitCqe waitCqe, PeekCqe peekCqe, PeekBatchCqe peekBatchCqe, CqeSeen cqeSeen, QueueInit queueInit, QueueExit queueExit, SqeSetData sqeSetData, RegisterBuffers registerBuffers, RegisterFiles registerFiles, RegisterFilesUpdate registerFilesUpdate) {
        this(arena, arena.allocate(ring_layout), NativeDispatcher.C.alloc(AddressLayout.ADDRESS.byteSize()), NativeDispatcher.C.alloc(AddressLayout.ADDRESS.byteSize() * 100), sqe, setSqeFlag, prepOpenAt, prepOpenDirect, prepClose, prepCloseDirect, prepRead, prepReadFixed, prepWrite, prepWriteFixed, submit, waitCqe, peekCqe, peekBatchCqe, cqeSeen, queueInit, queueExit, sqeSetData, registerBuffers, registerFiles, registerFilesUpdate);

        int ret = queueInit(queueDepth, ring, IORING_SETUP_SINGLE_ISSUER);
        if (ret < 0) {
            throw new RuntimeException("Failed to initialize queue " + NativeDispatcher.C.strerror(ret));
        }
    }

    void link(MemorySegment sqe) {
        setSqeFlag(sqe, IOSQE_IO_HARDLINK);
    }

    void fixedFile(MemorySegment sqe) {
        setSqeFlag(sqe, IOSQE_FIXED_FILE);
    }

    MemorySegment getSqe() {
        return sqe.getSqe(ring);
    }

    void setSqeFlag(MemorySegment sqe, byte flag) {
        setSqeFlag.setSqeFlag(sqe, flag);
    }

    void prepareOpen(MemorySegment sqe, MemorySegment filePath, int flags, int mode) {
        prepOpenAt.prepareOpen(sqe,AT_FDCWD,filePath, flags, mode);
    }

    void prepareOpenDirect(MemorySegment sqe, MemorySegment filePath, int flags, int mode, int fileIndex){
        prepOpenDirect.prepareOpenDirect(sqe,AT_FDCWD, filePath, flags, mode, fileIndex);
    }

    void prepareClose(MemorySegment sqe, int fd) {
        prepClose.prepareClose(sqe,fd);
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
            throw new RuntimeException("Failed to submit queue: " + NativeDispatcher.C.strerror(ret));
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

    void cqeSeen(MemorySegment ring, MemorySegment cqePointer) {
        cqeSeen.cqeSeen(ring, cqePointer);
    }

    int queueInit(int queueDepth, MemorySegment ring, int flags) {
        return queueInit.queueInit(queueDepth, ring, flags);
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
                seen(nativeCqe);
            }

            return ret;
        }
        return List.of();
    }

    Result waitForResult() {
        int ret = waitCqe(ring, cqePtr);
        if (ret < 0) {
            throw new RuntimeException("Error while waiting for cqe: " + NativeDispatcher.C.strerror(ret));
        }

        var nativeCqe = cqePtr.getAtIndex(ADDRESS, 0).reinterpret(io_uring_cqe_layout.byteSize());

        long userData = nativeCqe.get(JAVA_LONG, 0);
        int res = nativeCqe.get(JAVA_INT, 8);

        Result result = getResultFromCqe(userData, res);
        seen(nativeCqe);
        return result;
    }

    private Result getResultFromCqe(long address, long result) {
        MemorySegment nativeUserData = MemorySegment.ofAddress(address).reinterpret(UserData.getByteSize());

        OperationType type = UserData.getType(nativeUserData);
        long id = UserData.getId(nativeUserData);
        MemorySegment bufferResult = UserData.getBuffer(nativeUserData);

        NativeDispatcher.C.free(nativeUserData);

        if (OperationType.WRITE.equals(type)) {
            NativeDispatcher.C.free(bufferResult);
            return new WriteResult(id, result);
        }
        else if (OperationType.WRITE_FIXED.equals(type)) {
            return new WriteResult(id, result);
        }
        else if(OperationType.OPEN.equals(type)) {
            NativeDispatcher.C.free(bufferResult);
            return new OpenResult(id, (int) result);
        }
        else if(OperationType.CLOSE.equals(type)) {
            return new CloseResult(id, (int) result);
        }

        return new ReadResult(id, bufferResult, result);
    }

    private void seen(MemorySegment cqePointer) {
        cqeSeen(ring, cqePointer);
    }

    MemorySegment[] registerBuffers(int bufferSize, int nrIovecs) {
        var iovecStructure = NativeDispatcher.C.allocateIovec(arena, bufferSize, nrIovecs);
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
            throw new RuntimeException("Failed to register files: " + NativeDispatcher.C.strerror(ret));
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
            throw new RuntimeException("Failed to update registered files: " + NativeDispatcher.C.strerror(ret));
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
        NativeDispatcher.C.free(cqePtr);
        NativeDispatcher.C.free(cqePtrPtr);
        closeArena();
    }

}
