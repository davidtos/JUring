package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

public class Main {

    private static final MethodHandle io_uring_queue_init;
    private static final MethodHandle io_uring_get_sqe;
    private static final MethodHandle io_uring_prep_read;
    private static final MethodHandle io_uring_submit;
    private static final MethodHandle io_uring_wait_cqe;
    private static final MethodHandle io_uring_cqe_seen;
    private static final MethodHandle io_uring_queue_exit;

    private static final MethodHandle open;
    private static final MethodHandle close;

    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;

    static {
        Linker linker = Linker.nativeLinker();

        SymbolLookup liburing = SymbolLookup.libraryLookup("liburing-ffi.so", Arena.ofAuto());
        AddressLayout C_POINTER = ValueLayout.ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));

        // int io_uring_submit(struct io_uring *ring);
        io_uring_submit = linker.downcallHandle(
                liburing.find("io_uring_submit").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS)
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

    }

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
                FunctionDescriptor.of(ADDRESS, ADDRESS)
        );

        io_uring_wait_cqe = linker.downcallHandle(
                liburing.find("io_uring_wait_cqe").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, C_POINTER)
        );

        io_uring_cqe_seen = linker.downcallHandle(
                liburing.find("io_uring_cqe_seen").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
        );

        io_uring_queue_exit = linker.downcallHandle(
                liburing.find("io_uring_queue_exit").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS)
        );

        open = linker.downcallHandle(
                linker.defaultLookup().find("open").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                Linker.Option.critical(true)
        );

        close = linker.downcallHandle(
                linker.defaultLookup().find("close").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT),
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
    }

    public static void main(String[] args) throws Throwable{

        try (Arena arena = Arena.ofConfined()) {
            // Create and initialize the ring
            MemorySegment ring = arena.allocate(ring_layout);
            int ret = (int) io_uring_queue_init.invokeExact(10, ring, 0);

            // Prepare file descriptor and buffer
            int fd = (int) open.invokeExact(MemorySegment.ofArray("/home/david/read_file".getBytes()), 0, 0);
            MemorySegment buffer = arena.allocate(1024);

            // Prepare read operation
            MemorySegment sqe = (MemorySegment) io_uring_get_sqe.invokeExact(ring);
            io_uring_prep_read.invokeExact(sqe, fd, buffer, buffer.byteSize(), 0L);

            // Submit to uring
            ret = (int) io_uring_submit.invokeExact(ring);

            // Wait for and process completion
            MemorySegment cqePtr = arena.allocate(ValueLayout.ADDRESS);
            ret = (int) io_uring_wait_cqe.invokeExact(ring, cqePtr);

            // print the result
            System.out.println(buffer.getString(0));

            // Process data and cleanup
            io_uring_cqe_seen.invokeExact(ring, cqePtr);
            close.invokeExact(fd);
            io_uring_queue_exit.invokeExact(ring);
        }
    }
}
