package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

class LibUringWrapper implements AutoCloseable {

    private static final MethodHandle io_uring_queue_init;
    private static final MethodHandle io_uring_get_sqe;
    private static final MethodHandle io_uring_prep_read;
    private static final MethodHandle io_uring_prep_write;
    private static final MethodHandle io_uring_submit;
    private static final MethodHandle io_uring_wait_cqe;
    private static final MethodHandle io_uring_peek_cqe;
    private static final MethodHandle io_uring_cqe_seen;
    private static final MethodHandle io_uring_queue_exit;
    private static final MethodHandle io_uring_sqe_set_data;

    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;
    private static final GroupLayout io_uring_cqe_layout;

    private final MemorySegment ring;
    private final Arena arena;
    static {

        Linker linker = Linker.nativeLinker();

        SymbolLookup liburing = SymbolLookup.libraryLookup("liburing-ffi.so", Arena.ofAuto());
        AddressLayout C_POINTER = ValueLayout.ADDRESS
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
    }

    LibUringWrapper(int queueDepth) {
        arena = Arena.ofShared();
        ring = arena.allocate(ring_layout);

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

    Cqe peekForResult(MemorySegment cqePtr) {
        try {
            int ret = (int) io_uring_peek_cqe.invokeExact(ring, cqePtr);

            if (ret == 0) {
                var cqeRaw = MemorySegment.ofAddress(cqePtr.get(ValueLayout.ADDRESS, 0).address())
                        .reinterpret(io_uring_cqe_layout.byteSize());

                long userData = cqeRaw.get(ValueLayout.JAVA_LONG, 0);
                int res = cqeRaw.get(ValueLayout.JAVA_INT, 8);

                return new Cqe(userData, res, cqeRaw);
            } else if (ret == -11) {
                return null;
            }
            else if (ret < 0) {
                throw new RuntimeException("Failed to peek result");
            }

        } catch (Throwable e) {
            throw new RuntimeException("Failed while peeking or creating result from cqe ",e);
        }

        return null;
    }

    Cqe waitForResult(MemorySegment cqePtr) {
        try {
            int ret = (int) io_uring_wait_cqe.invokeExact(ring, cqePtr);
            if (ret < 0) {
                throw new RuntimeException("Error while waiting for cqe: " + ret);
            }

            var cqeRaw = MemorySegment.ofAddress(cqePtr.get(ValueLayout.ADDRESS, 0).address())
                    .reinterpret(io_uring_cqe_layout.byteSize());

            long userData = cqeRaw.get(ValueLayout.JAVA_LONG, 0);
            int res = cqeRaw.get(ValueLayout.JAVA_INT, 8);

            return new Cqe(userData, res, cqeRaw);
        } catch (Throwable e) {
            throw new RuntimeException("Exception while waiting/creating result", e);
        }
    }

    void seen(MemorySegment cqePointer) {
        try {
            io_uring_cqe_seen.invokeExact(ring, cqePointer);
        } catch (Throwable e) {
            throw new RuntimeException("Could not mark cqe as seen",e);
        }
    }

    void freeMemory(MemorySegment memory) {
        LibCWrapper.freeBuffer(memory);
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
        closeArena();
    }

}
