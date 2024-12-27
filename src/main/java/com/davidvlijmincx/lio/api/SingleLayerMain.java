package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class SingleLayerMain {

    record IoData(MemorySegment buffer, int fd) {
    }


    public static void main(String[] args) throws Throwable {
        IoData[] io_data = new IoData[1];

        read(io_data);
        write(io_data);

    }

    private static void read(IoData[] io_data) throws Throwable {
        try (LibUringLayer uring = new LibUringLayer(io_data.length, false)) {

            for (int i = 0; i < io_data.length; i++) {

                int fd = uring.openFile("/home/david/Desktop/tmp_file_read", 0, 0);
                MemorySegment sqe = uring.getSqe();
                uring.setUserData(sqe,i);

                MemorySegment buff = uring.malloc(15);
                uring.prepareRead(sqe, fd, buff, 0L);
                io_data[i] = new IoData(buff, fd);
            }

            uring.submit();

            for (int i = 0; i < io_data.length; i++) {
                Cqe cqe = uring.waitForResult();

                io_data[(int) cqe.UserData()].buffer.set(JAVA_BYTE, cqe.result() - 1, (byte) 0);

                String buffString = io_data[(int) cqe.UserData()].buffer.getString(0);
                if (!buffString.equals("hello world\n")) {
                    System.out.println("userdata = " + cqe.UserData());
                    System.out.println(buffString);
                }

                uring.seen(cqe.cqePointer());
                uring.freeMemory(io_data[(int) cqe.UserData()].buffer);
                uring.closeFile(io_data[(int) cqe.UserData()].fd);
            }

        }
    }


    private static void write(IoData[] io_data) throws Throwable {
        try (LibUringLayer uring = new LibUringLayer(io_data.length, false)) {

            for (int i = 0; i < io_data.length; i++) {

                int fd = uring.openFile("/home/david/Desktop/tmp_file_write", 2, 0);
                MemorySegment sqe = uring.getSqe();
                uring.setUserData(sqe,i);

                String a = "Hello, form Java " + i;
                var StringBytes = a.getBytes();
                MemorySegment buff = uring.malloc(StringBytes.length);
                MemorySegment.copy(StringBytes, 0, buff, JAVA_BYTE, 0, StringBytes.length);

                uring.prepareWrite(sqe, fd, buff, 0L);
                io_data[i] = new IoData(buff, fd);
            }

            uring.submit();

            for (int i = 0; i < io_data.length; i++) {
                Cqe cqe = uring.waitForResult();

                System.out.println("userdata = " + cqe.UserData());
                System.out.println(cqe.result());

                uring.seen(cqe.cqePointer());
                uring.freeMemory(io_data[(int) cqe.UserData()].buffer);
                uring.closeFile(io_data[(int) cqe.UserData()].fd);
            }

        }
    }
}
