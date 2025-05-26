package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;

import static com.davidvlijmincx.lio.api.JUring.*;
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
    private static final MethodHandle io_uring_queue_init_params;
    private static final MethodHandle io_uring_get_sqe;
    private static final MethodHandle io_uring_sqe_set_flags;
    private static final MethodHandle io_uring_prep_openat;
    private static final MethodHandle io_uring_prep_close;
    private static final MethodHandle io_uring_prep_read;
    private static final MethodHandle io_uring_prep_read_fixed;
    private static final MethodHandle io_uring_prep_write;
    private static final MethodHandle io_uring_submit;
    private static final MethodHandle io_uring_wait_cqe;
    private static final MethodHandle io_uring_peek_cqe;
    private static final MethodHandle io_uring_peek_batch_cqe;
    private static final MethodHandle io_uring_cqe_seen;
    private static final MethodHandle io_uring_queue_exit;
    private static final MethodHandle io_uring_sqe_set_data;
    private static final MethodHandle io_uring_register_buffers;

    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;
    private static final GroupLayout io_uring_cqe_layout;
    private static final GroupLayout iovec_layout;


    private final MemorySegment ring;
    private final Arena arena;
    private final MemorySegment cqePtr;
    private final MemorySegment cqePtrPtr;
    private static final AddressLayout C_POINTER;

    private final static Linker linker = Linker.nativeLinker();
    private final static SymbolLookup liburing = SymbolLookup.libraryLookup("liburing-ffi.so", Arena.ofAuto());

    static {
        iovec_layout = MemoryLayout.structLayout(
                ADDRESS.withOrder(ByteOrder.nativeOrder()).withName("iov_base"),
                JAVA_LONG.withOrder(ByteOrder.nativeOrder()).withName("iov_len")
        );

        C_POINTER = ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));


        io_uring_queue_init = linker.downcallHandle(
                liburing.find("io_uring_queue_init").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );

        io_uring_queue_init_params = linker.downcallHandle(
                liburing.find("io_uring_queue_init_params").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, C_POINTER)
        );

        io_uring_get_sqe = linker.downcallHandle(
                liburing.find("io_uring_get_sqe").orElseThrow(),
                FunctionDescriptor.of(C_POINTER, C_POINTER)
        );

        io_uring_sqe_set_flags = linker.downcallHandle(
                liburing.find("io_uring_sqe_set_flags").orElseThrow(),
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_BYTE)
        );

        io_uring_register_buffers = linker.downcallHandle(
                liburing.find("io_uring_register_buffers").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, C_POINTER, C_POINTER, JAVA_INT));

        io_uring_prep_read_fixed = linker.downcallHandle(
                liburing.find("io_uring_prep_read_fixed").orElseThrow(),
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG, JAVA_INT));

        io_uring_prep_openat = linker.downcallHandle(
                liburing.find("io_uring_prep_openat").orElseThrow(),
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT, JAVA_INT)
        );

        io_uring_prep_close = linker.downcallHandle(
                liburing.find("io_uring_prep_close").orElseThrow(),
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT)
        );

        io_uring_prep_read = linker.downcallHandle(
                liburing.find("io_uring_prep_read").orElseThrow(),
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG)
        );

        io_uring_prep_write = linker.downcallHandle(
                liburing.find("io_uring_prep_write").orElseThrow(),
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG)
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
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_LONG),
                Linker.Option.critical(true)
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
        cqePtr = LibCWrapper.allocate(ADDRESS.byteSize());
        cqePtrPtr = LibCWrapper.allocate(ADDRESS.byteSize() * 200);

        try {
            int ret = (int) io_uring_queue_init.invokeExact(queueDepth, ring, IORING_SETUP_SINGLE_ISSUER |
                    IORING_SETUP_COOP_TASKRUN |
                    IORING_SETUP_DEFER_TASKRUN |
                    IORING_SETUP_CLAMP);
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

    void prepareOpen(MemorySegment sqe, MemorySegment filePath, int flags, int mode) {
        try {
            io_uring_prep_openat.invokeExact(sqe, AT_FDCWD, filePath, flags, mode);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void prepareClose(MemorySegment sqe, int fd) {
        try {
            io_uring_prep_close.invokeExact(sqe, fd);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void link(MemorySegment sqe) {
        try {
            io_uring_sqe_set_flags.invokeExact(sqe, IOSQE_IO_HARDLINK);
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

    void prepareReadFixed(MemorySegment sqe, int fd, MemorySegment buffer, long offset, int index) {
        try {
            io_uring_prep_read_fixed.invokeExact(sqe, fd, buffer, buffer.byteSize(), offset, index);
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

    MemorySegment[] registerBuffers(int bufferSize, int nrIovecs) {
        LibCWrapper.IovecStructure iovecStructure = LibCWrapper.allocateIovec(arena, bufferSize, nrIovecs);
        registerBuffers(ring, iovecStructure.iovecArray(), nrIovecs);
        return iovecStructure.buffers();
    }

    private void registerBuffers(MemorySegment ring, MemorySegment iovecs, int nrIovecs) {
        try {
            int ret = (int) io_uring_register_buffers.invokeExact(ring, iovecs, nrIovecs);
            if (ret < 0) {
                throw new RuntimeException("Failed to register buffers");
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    IoResult[] peekForBatchResult(int batchSize) {
        try {
            int count = (int) io_uring_peek_batch_cqe.invokeExact(ring, cqePtrPtr, batchSize);

            if (count > 0) {
                IoResult[] ret = new IoResult[count];

                for (int i = 0; i < count; i++) {
                    ret[i] =(getResultFromCqe(cqePtrPtr.getAtIndex(ADDRESS, i)));
                }

                return ret;
            }
            return new IoResult[0];

        } catch (Throwable e) {
            throw new RuntimeException("Failed while peeking or creating result from cqe ", e);
        }
    }

    IoResult waitForResult() {
        try {
            int ret = (int) io_uring_wait_cqe.invokeExact(ring, cqePtr);
            if (ret < 0) {
                throw new RuntimeException("Error while waiting for cqe: " + ret);
            }

            return getResultFromCqe(cqePtr.getAtIndex(ADDRESS, 0));
        } catch (Throwable e) {
            throw new RuntimeException("Exception while waiting/creating result", e);
        }
    }

    private IoResult getResultFromCqe(MemorySegment ptr) {
        var cqePointer = ptr.reinterpret(io_uring_cqe_layout.byteSize());

        long userData = cqePointer.get(JAVA_LONG, 0);
        int result = cqePointer.get(JAVA_INT, 8);

        MemorySegment nativeUserData = MemorySegment.ofAddress(userData).reinterpret(requestLayout.byteSize());

        seen(cqePointer);

        int type = (int) typeHandle.get(nativeUserData, 0L);
        OperationType operationType = OperationType.valueOf(type);

        var value = switch (operationType) {
            case READ, WRITE ->
                    new IoResult((long) idHandle.get(nativeUserData, 0L), operationType, result, (MemorySegment) bufferHandle.get(nativeUserData, 0L));
            case OPEN, CLOSE -> new IoResult((long) idHandle.get(nativeUserData, 0L), operationType, result, null);
        };

        LibCWrapper.freeBuffer(nativeUserData);

        return value;
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
