package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
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

    private static final MethodHandle io_uring_queue_init;
    private static final MethodHandle io_uring_get_sqe;
    private static final MethodHandle io_uring_prep_read;
    private static final MethodHandle io_uring_prep_write;
    private static final MethodHandle io_uring_submit;
    private static final MethodHandle io_uring_wait_cqe;
    private static final MethodHandle io_uring_peek_cqe;
    private static final MethodHandle io_uring_peek_batch_cqe;
    private static final MethodHandle io_uring_cqe_seen;
    private static final MethodHandle io_uring_queue_exit;
    private static final MethodHandle io_uring_sqe_set_data;

    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;
    private static final GroupLayout io_uring_cqe_layout;

    private final MemorySegment ring;
    private final Arena arena;
    private final MemorySegment cqePtr;
    private final MemorySegment cqePtrPtr;
    private static final AddressLayout C_POINTER;

    private static final StructLayout requestLayout;
    private static final VarHandle idHandle;
    private static final VarHandle fdHandle;
    private static final VarHandle readHandle;
    private static final VarHandle bufferHandle;

    static {

        Linker linker = Linker.nativeLinker();

        SymbolLookup liburing = SymbolLookup.libraryLookup("liburing-ffi.so", Arena.ofAuto());
        C_POINTER = ValueLayout.ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));


        io_uring_queue_init = linker.downcallHandle(
                liburing.find("io_uring_queue_init").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );

        io_uring_get_sqe = linker.downcallHandle(
                liburing.find("io_uring_get_sqe").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS),
                Linker.Option.critical(true)
        );

        io_uring_prep_read = linker.downcallHandle(
                liburing.find("io_uring_prep_read").orElseThrow(),
                FunctionDescriptor.ofVoid(
                        C_POINTER,
                        JAVA_INT,
                        C_POINTER,
                        JAVA_LONG,
                        ValueLayout.JAVA_LONG
                )
        );

        io_uring_prep_write = linker.downcallHandle(
                liburing.find("io_uring_prep_write").orElseThrow(),
                FunctionDescriptor.ofVoid(
                        C_POINTER,
                        JAVA_INT,
                        C_POINTER,
                        JAVA_LONG,
                        JAVA_LONG
                )
        );

        io_uring_submit = linker.downcallHandle(
                liburing.find("io_uring_submit").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS),
                Linker.Option.critical(true)
        );

        io_uring_wait_cqe = linker.downcallHandle(
                liburing.find("io_uring_wait_cqe").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER)
        );

        io_uring_peek_cqe = linker.downcallHandle(
                liburing.find("io_uring_peek_cqe").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER)
        );

        io_uring_peek_batch_cqe = linker.downcallHandle(
                liburing.find("io_uring_peek_batch_cqe").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER, JAVA_INT)
        );

        io_uring_cqe_seen = linker.downcallHandle(
                liburing.find("io_uring_cqe_seen").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
                Linker.Option.critical(true)
        );

        io_uring_queue_exit = linker.downcallHandle(
                liburing.find("io_uring_queue_exit").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS)
        );

        io_uring_sqe_set_data = linker.downcallHandle(
                liburing.find("io_uring_sqe_set_data").orElseThrow(),
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_LONG)
        );

        io_uring_sq_layout = MemoryLayout.structLayout(
                C_POINTER.withName("khead"),
                C_POINTER.withName("ktail"),
                C_POINTER.withName("kring_mask"),
                C_POINTER.withName("kring_entries"),
                C_POINTER.withName("kflags"),
                C_POINTER.withName("kdropped"),
                C_POINTER.withName("array"),
                C_POINTER.withName("sqes"),
                ValueLayout.JAVA_INT.withName("sqe_head"),
                ValueLayout.JAVA_INT.withName("sqe_tail"),
                ValueLayout.JAVA_LONG.withName("ring_sz"),
                C_POINTER.withName("ring_ptr"),
                ValueLayout.JAVA_INT.withName("ring_mask"),
                ValueLayout.JAVA_INT.withName("ring_entries"),
                MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT).withName("pad")
        ).withName("io_uring_sq");

        io_uring_cq_layout = MemoryLayout.structLayout(
                C_POINTER.withName("khead"),
                C_POINTER.withName("ktail"),
                C_POINTER.withName("kring_mask"),
                C_POINTER.withName("kring_entries"),
                C_POINTER.withName("kflags"),
                C_POINTER.withName("koverflow"),
                C_POINTER.withName("cqes"),
                ValueLayout.JAVA_LONG.withName("ring_sz"),
                C_POINTER.withName("ring_ptr"),
                ValueLayout.JAVA_INT.withName("ring_mask"),
                ValueLayout.JAVA_INT.withName("ring_entries"),
                MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT).withName("pad")
        ).withName("io_uring_cq");

        ring_layout = MemoryLayout.structLayout(
                io_uring_sq_layout.withName("sq"),
                io_uring_cq_layout.withName("cq"),
                ValueLayout.JAVA_INT.withName("flags"),
                ValueLayout.JAVA_INT.withName("ring_fd"),
                ValueLayout.JAVA_INT.withName("features"),
                ValueLayout.JAVA_INT.withName("enter_ring_fd"),
                ValueLayout.JAVA_BYTE.withName("int_flags"),
                MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_BYTE).withName("pad"),
                ValueLayout.JAVA_INT.withName("pad2")
        ).withName("io_uring");

        io_uring_cqe_layout = MemoryLayout.structLayout(
                ValueLayout.JAVA_LONG.withName("user_data"),
                ValueLayout.JAVA_INT.withName("res"),
                ValueLayout.JAVA_INT.withName("flags"),
                MemoryLayout.sequenceLayout(0, ValueLayout.JAVA_LONG).withName("big_cqe")
        ).withName("io_uring_cqe");

        requestLayout = MemoryLayout.structLayout(
                        ValueLayout.JAVA_LONG.withName("id"),
                        C_POINTER.withName("buffer"),
                        ValueLayout.JAVA_INT.withName("fd"),
                        ValueLayout.JAVA_BOOLEAN.withName("read"))
                .withName("request");

        idHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("id"));
        fdHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("fd"));
        readHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("read"));
        bufferHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("buffer"));
    }

    LibUringWrapper(int queueDepth) {
        arena = Arena.ofShared();
        ring = arena.allocate(ring_layout);
        cqePtr = LibCWrapper.malloc(AddressLayout.ADDRESS.byteSize());
        cqePtrPtr = LibCWrapper.malloc(AddressLayout.ADDRESS.byteSize() * 100);

        try {

            int ret = (int) io_uring_queue_init.invokeExact(queueDepth, ring, IORING_SETUP_SINGLE_ISSUER);
            if (ret < 0) {
                throw new RuntimeException("Failed to initialize queue " + ret);
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }

    }

    MemorySegment getSqe() {
        try {
            MemorySegment sqe = (MemorySegment) io_uring_get_sqe.invokeExact(ring);
            if (sqe == null) {
                throw new RuntimeException("Failed to get sqe");
            }
            return sqe;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void prepareRead(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        try {
            io_uring_prep_read.invokeExact(sqe, fd, buffer, buffer.byteSize(), offset);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void prepareWrite(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        try {
            io_uring_prep_write.invokeExact(sqe, fd, buffer, buffer.byteSize(), offset);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void setUserData(MemorySegment sqe, long userData) {
        try {
            io_uring_sqe_set_data.invokeExact(sqe, userData);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void submit() {
        try {
            int ret = (int) io_uring_submit.invokeExact(ring);
            if (ret < 0) {
                throw new RuntimeException("Failed to submit queue");
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    List<Result> peekForBatchResult(int batchSize) {
        try {
            int count = (int) io_uring_peek_batch_cqe.invokeExact(ring, cqePtrPtr, batchSize);

            if (count > 0) {
                List<Result> ret = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    var nativeCqe = cqePtrPtr.getAtIndex(ADDRESS, i).reinterpret(io_uring_cqe_layout.byteSize());

                    long userData = nativeCqe.get(ValueLayout.JAVA_LONG, 0);
                    int res = nativeCqe.get(ValueLayout.JAVA_INT, 8);

                    ret.add(getResultFromCqe(userData, res, nativeCqe));
                }

                return ret;
            }
            return List.of();

        } catch (Throwable e) {
            throw new RuntimeException("Failed while peeking or creating result from cqe ", e);
        }
    }

    Result waitForResult() {
        try {
            int ret = (int) io_uring_wait_cqe.invokeExact(ring, cqePtr);
            if (ret < 0) {
                throw new RuntimeException("Error while waiting for cqe: " + ret);
            }

            var nativeCqe = cqePtr.getAtIndex(ADDRESS, 0).reinterpret(io_uring_cqe_layout.byteSize());

            long userData = nativeCqe.get(ValueLayout.JAVA_LONG, 0);
            int res = nativeCqe.get(ValueLayout.JAVA_INT, 8);

            return getResultFromCqe(userData, res, nativeCqe);
        } catch (Throwable e) {
            throw new RuntimeException("Exception while waiting/creating result", e);
        }
    }

    private Result getResultFromCqe(long address, long result, MemorySegment cqePointer) {
        MemorySegment nativeUserData = MemorySegment.ofAddress(address).reinterpret(requestLayout.byteSize());

        seen(cqePointer);

        boolean readResult = (boolean) readHandle.get(nativeUserData, 0L);
        long idResult = (long) idHandle.get(nativeUserData, 0L);
        MemorySegment bufferResult = (MemorySegment) bufferHandle.get(nativeUserData, 0L);

        LibCWrapper.freeBuffer(nativeUserData);

        if (!readResult) {
            LibCWrapper.freeBuffer(bufferResult);
            return new AsyncWriteResult(idResult, result);
        }

        return new AsyncReadResult(idResult, bufferResult, result);
    }

    private void seen(MemorySegment cqePointer) {
        try {
            io_uring_cqe_seen.invokeExact(ring, cqePointer);
        } catch (Throwable e) {
            throw new RuntimeException("Could not mark cqe as seen", e);
        }
    }

    void closeRing() {
        try {
            io_uring_queue_exit.invokeExact(ring);
        } catch (Throwable e) {
            throw new RuntimeException("Could not close ring", e);
        }
    }

    void closeArena() {
        arena.close();
    }

    @Override
    public void close() {
        closeRing();
        LibCWrapper.freeBuffer(cqePtr);
        LibCWrapper.freeBuffer(cqePtrPtr);
        closeArena();
    }

}
