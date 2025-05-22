package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.davidvlijmincx.lio.api.JUring.*;
import static java.lang.foreign.ValueLayout.*;

class LibUringWrapper implements AutoCloseable {

    private static final int AT_FDCWD = (int)-100L;
    private static final int IOSQE_IO_LINK_BIT = 2;
    private static final byte IOSQE_IO_LINK = (byte)(1 << IOSQE_IO_LINK_BIT);

    private static final MethodHandle io_uring_queue_init;
    private static final MethodHandle io_uring_queue_init_params;
    private static final MethodHandle io_uring_get_sqe;
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
                FunctionDescriptor.of(ADDRESS, ADDRESS),
                Linker.Option.critical(true)
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

            int ret = (int) io_uring_queue_init.invokeExact(queueDepth, ring, 0);
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
            io_uring_prep_openat.invokeExact(sqe,AT_FDCWD,filePath, flags, mode );
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
        sqe = sqe.reinterpret(4);
        byte flagz = 1;
        sqe.set(JAVA_BYTE, JAVA_CHAR.byteSize(), flagz |= IOSQE_IO_LINK);
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
            io_uring_prep_read_fixed.invokeExact(sqe,fd,buffer, buffer.byteSize(), offset, index);
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
            if (ret <0) {
                throw new RuntimeException("Failed to register buffers");
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    List<IoResult> peekForBatchResult(int batchSize) {
        try {
            int count = (int) io_uring_peek_batch_cqe.invokeExact(ring, cqePtrPtr, batchSize);

            if (count > 0) {
                List<IoResult> ret = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    ret.add(getResultFromCqe(cqePtrPtr.getAtIndex(ADDRESS, i)));
                }

                return ret;
            }
            return List.of();

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
             case READ, WRITE -> new IoResult((long) idHandle.get(nativeUserData, 0L), operationType, result, (MemorySegment) bufferHandle.get(nativeUserData, 0L));
            case OPEN, CLOSE -> new IoResult((long) idHandle.get(nativeUserData, 0L),operationType,result,null);
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
