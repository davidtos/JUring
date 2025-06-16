package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.*;

class LibUringWrapper implements AutoCloseable {

    // io_uring setup flags
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


    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;
    private static final GroupLayout io_uring_cqe_layout;

    private final MemorySegment ring;
    private final Arena arena;
    private final MemorySegment cqePtr;
    private final MemorySegment cqePtrPtr;
    private static final AddressLayout C_POINTER;

    static {
        C_POINTER = ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));

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

        io_uring_cqe_layout = MemoryLayout.structLayout(
                JAVA_LONG.withName("user_data"),
                JAVA_INT.withName("res"),
                JAVA_INT.withName("flags"),
                MemoryLayout.sequenceLayout(0, JAVA_LONG).withName("big_cqe")
        ).withName("io_uring_cqe");

    }

    LibUringWrapper(int queueDepth) {
        arena = Arena.ofShared();
        ring = arena.allocate(ring_layout);
        cqePtr = NativeDispatcher.C.alloc(AddressLayout.ADDRESS.byteSize());
        cqePtrPtr = NativeDispatcher.C.alloc(AddressLayout.ADDRESS.byteSize() * 100);

        int ret = NativeDispatcher.URING.queueInit(queueDepth, ring, IORING_SETUP_SINGLE_ISSUER);
        if (ret < 0) {
            throw new RuntimeException("Failed to initialize queue " + NativeDispatcher.C.strerror(ret));
        }

    }

    MemorySegment getSqe() {
        return NativeDispatcher.URING.getSqe(ring);
    }

    void link(MemorySegment sqe) {
        setSqeFlag(sqe, IOSQE_IO_HARDLINK);
    }

    void fixedFile(MemorySegment sqe) {
        setSqeFlag(sqe, IOSQE_FIXED_FILE);
    }

    void setSqeFlag(MemorySegment sqe, byte flag) {
        NativeDispatcher.URING.setSqeFlag(sqe, flag);
    }

    void prepareOpen(MemorySegment sqe, MemorySegment filePath, int flags, int mode) {
        NativeDispatcher.URING.prepareOpen(sqe, filePath, flags, mode);
    }

    void prepareOpenDirect(MemorySegment sqe, MemorySegment filePath, int flags, int mode, int fileIndex) {
       NativeDispatcher.URING.prepareOpenDirect(sqe, filePath, flags, mode, fileIndex);
    }

    void prepareClose(MemorySegment sqe, int fd) {
        NativeDispatcher.URING.prepareClose(sqe,fd);
    }

    void prepareCloseDirect(MemorySegment sqe, int fileIndex) {
       NativeDispatcher.URING.prepareCloseDirect(sqe, fileIndex);
    }

    void prepareRead(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        NativeDispatcher.URING.prepareRead(sqe,fd,buffer,offset);
    }

    void prepareReadFixed(MemorySegment sqe, int fd, MemorySegment buffer, long offset, int bufferIndex) {
        NativeDispatcher.URING.prepareReadFixed( sqe,fd,buffer,offset,bufferIndex);
    }

    void prepareWrite(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        NativeDispatcher.URING.prepareWrite(sqe, fd, buffer, offset);
    }

    void prepareWriteFixed(MemorySegment sqe, int fd, MemorySegment buffer,long nbytes, long offset, int bufferIndex) {
        NativeDispatcher.URING.prepareWriteFixed(sqe, fd, buffer, nbytes, offset, bufferIndex);
    }

    void setUserData(MemorySegment sqe, long userData) {
        NativeDispatcher.URING.sqeSetData(sqe, userData);
    }

    void submit() {
        int ret = NativeDispatcher.URING.submit(ring);
        if (ret < 0) {
            throw new RuntimeException("Failed to submit queue: " + NativeDispatcher.C.strerror(ret));
        }
    }

    List<Result> peekForBatchResult(int batchSize) {
        int count = NativeDispatcher.URING.peekBatchCqe(ring, cqePtrPtr, batchSize);

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
        int ret = NativeDispatcher.URING.waitCqe(ring, cqePtr);
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
        NativeDispatcher.URING.cqeSeen(ring, cqePointer);
    }

    MemorySegment[] registerBuffers(int bufferSize, int nrIovecs) {
        var iovecStructure = NativeDispatcher.C.allocateIovec(arena, bufferSize, nrIovecs);
        registerBuffers(ring, iovecStructure.iovecArray(), nrIovecs);
        return iovecStructure.buffers();
    }

    private void registerBuffers(MemorySegment ring, MemorySegment iovecs, int nrIovecs) {
        int ret = NativeDispatcher.URING.registerBuffers(ring, iovecs, nrIovecs);
        if (ret < 0) {
            throw new RuntimeException("Failed to register buffers");
        }
    }

    int registerFiles(int[] fileDescriptors) {
        int count = fileDescriptors.length;
        MemorySegment fdArray = arena.allocate(JAVA_INT.byteSize() * count);
        
        for (int i = 0; i < count; i++) {
            fdArray.setAtIndex(JAVA_INT, i, fileDescriptors[i]);
        }
        
        int ret = NativeDispatcher.URING.registerFiles(ring, fdArray, count);
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
        
        int ret = NativeDispatcher.URING.registerFilesUpdate(ring, offset, fdArray, count);
        if (ret < 0) {
            throw new RuntimeException("Failed to update registered files: " + NativeDispatcher.C.strerror(ret));
        }
        return ret;
    }

    void closeRing() {
        NativeDispatcher.URING.queueExit(ring);
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
