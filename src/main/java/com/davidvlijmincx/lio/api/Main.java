package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.*;

public class Main {

    public static final int buffSize = 15;

    private static final MethodHandle open;
    private static final MethodHandle malloc;
    private static final MethodHandle free;
    private static final MethodHandle close;
    private static final MethodHandle io_uring_queue_init;
    private static final MethodHandle io_uring_get_sqe;
    private static final MethodHandle io_uring_prep_read;
    private static final MethodHandle io_uring_submit;
    private static final MethodHandle io_uring_wait_cqe;
    private static final MethodHandle io_uring_cqe_seen;
    private static final MethodHandle io_uring_queue_exit;
    private static final MethodHandle io_uring_sqe_set_data;
    private static final MethodHandle io_uring_cqe_get_data;


    private static final GroupLayout ring_layout;
    private static final GroupLayout io_uring_cq_layout;
    private static final GroupLayout io_uring_sq_layout;
    private static final GroupLayout io_uring_cqe_layout;

    static {

        Linker linker = Linker.nativeLinker();

        SymbolLookup liburing = SymbolLookup.libraryLookup("liburing-ffi.so", Arena.ofAuto());
        AddressLayout C_POINTER = ValueLayout.ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));

        open = linker.downcallHandle(
                linker.defaultLookup().find("open").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
        );

        free = linker.downcallHandle(
                linker.defaultLookup().find("free").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS)
        );

        malloc = linker.downcallHandle(
                linker.defaultLookup().find("malloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_INT)
        );

        close = linker.downcallHandle(
                linker.defaultLookup().find("close").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT)
        );

        io_uring_queue_init = linker.downcallHandle(
                liburing.find("io_uring_queue_init").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );

        io_uring_get_sqe = linker.downcallHandle(
                liburing.find("io_uring_get_sqe").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS)
        );

        io_uring_prep_read = linker.downcallHandle(
                liburing.find("io_uring_prep_read").orElseThrow(),
                FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE)),
                        JAVA_INT,
                        ValueLayout.ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE)),
                        JAVA_INT,
                        ValueLayout.JAVA_LONG
                )
        );

        io_uring_submit = linker.downcallHandle(
                liburing.find("io_uring_submit").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS)
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

        io_uring_sqe_set_data = linker.downcallHandle(
                liburing.find("io_uring_sqe_set_data").orElseThrow(),
                FunctionDescriptor.ofVoid(C_POINTER, JAVA_LONG)
        );

        io_uring_cqe_get_data = linker.downcallHandle(
                liburing.find("io_uring_cqe_get_data").orElseThrow(),
                FunctionDescriptor.of(C_POINTER, C_POINTER)
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

    record IoData(MemorySegment buffer, int fd) {
    }

    public static void main(String[] args) throws Throwable {
        Main main = new Main();
        main.start();
    }

    public void start() throws Throwable {
        IoData[] io_data = new IoData[5];

        Arena arena = Arena.ofConfined();
        MemorySegment ring = arena.allocate(ring_layout);

        int ret = (int) io_uring_queue_init.invokeExact(io_data.length + 10, ring, 0);
        if (ret < 0) {
            System.out.println("Error in io_uring_queue_init");
        }

        for (int i = 0; i < io_data.length; i++) {
            //   MemorySegment filePath = arena.allocateFrom("/home/david/Documents/tmp_file_read");
            MemorySegment filePath = arena.allocateFrom("/home/david/Desktop/tmp_file_read");
//            MemorySegment filePath = arena.allocateFrom("/media/david/Data2/tmp_file_read");
            int fd = (int) open.invokeExact(filePath, 0, 0);
            if (fd < 0) {
                System.out.println("Error in open");
            }

            MemorySegment sqe = (MemorySegment) io_uring_get_sqe.invokeExact(ring);
            if (sqe == null) {
                System.out.println("Error in get_sqe");
            }

            MemorySegment buff = ((MemorySegment) malloc.invokeExact(buffSize)).reinterpret(buffSize);
            io_uring_prep_read.invokeExact(sqe, fd, buff, buffSize, 0L);

            io_data[i] = new IoData(buff, fd);

            io_uring_sqe_set_data.invokeExact(sqe, (long) i);
        }


        ret = (int) io_uring_submit.invokeExact(ring);
        if (ret < 0) {
            System.out.println("Error in submit");
        }

        MemorySegment cqePtr = arena.allocate(ADDRESS);


        for (int i = 0; i < io_data.length; i++) {
            ret = (int) io_uring_wait_cqe.invokeExact(ring, cqePtr);
            if (ret < 0) {
                System.out.println("Error in wait_cqe");
            }

            var cqe = MemorySegment.ofAddress(cqePtr.get(ValueLayout.ADDRESS, 0).address())
                    .reinterpret(io_uring_cqe_layout.byteSize());

            long userData = cqe.get(ValueLayout.JAVA_LONG, 0);
            int res = cqe.get(ValueLayout.JAVA_INT, 8);

            io_data[(int) userData].buffer.set(JAVA_BYTE, res, (byte)0);

            String buffString = io_data[(int) userData].buffer.getString(0);
          //  if (!buffString.equals("hello world\n")) {
            System.out.println("userdata = " + userData);
            System.out.println(buffString);
           // }

            io_uring_cqe_seen.invokeExact(ring, cqe);
            free.invokeExact(io_data[(int) userData].buffer);

            close.invokeExact(io_data[(int) userData].fd);
        }


        io_uring_queue_exit.invokeExact(ring);
        arena.close();

    }

}