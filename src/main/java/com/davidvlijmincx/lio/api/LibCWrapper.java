package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

class LibCWrapper {

    private static final MethodHandle free;
    private static final MethodHandle open;
    private static final MethodHandle close;
    private static final MethodHandle malloc;
    private static final MethodHandle calloc;
    private static final MethodHandle strerror;

    static {
        Linker linker = Linker.nativeLinker();

        free = linker.downcallHandle(
                linker.defaultLookup().find("free").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS),
                Linker.Option.critical(true)
        );

        strerror = linker.downcallHandle(
                linker.defaultLookup().find("strerror").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_INT)
        );


        open = linker.downcallHandle(
                linker.defaultLookup().find("open").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                Linker.Option.critical(true)
        );


        malloc = linker.downcallHandle(
                linker.defaultLookup().find("malloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG),
                Linker.Option.critical(true)
        );

        calloc = linker.downcallHandle(
                linker.defaultLookup().find("calloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG),
                Linker.Option.critical(true)
        );

        close = linker.downcallHandle(
                linker.defaultLookup().find("close").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT),
                Linker.Option.critical(true)
        );
    }

    private LibCWrapper() {
    }

    static IovecStructure allocateIovec(Arena arena, long bufferSize, long nrIovecs) {
        var iovecSequence = MemoryLayout.sequenceLayout(nrIovecs, Iovec.layout());
        var iovecArray = arena.allocate(iovecSequence);
        MemorySegment[] buffers = new MemorySegment[(int) nrIovecs];

        for (int i = 0; i < nrIovecs; i++) {
            MemorySegment nthIovec = iovecArray.asSlice(i * Iovec.layout().byteSize(),  Iovec.layout().byteSize());
            MemorySegment buffer = malloc(bufferSize);
            setIovecData(nthIovec, buffer);
            buffers[i] = buffer;
        }

        return new IovecStructure(iovecArray, buffers);
    }

    record IovecStructure(MemorySegment iovecArray, MemorySegment[] buffers) {
    }

    static private void setIovecData(MemorySegment memorySegment, MemorySegment buffer) {
        try {
            Iovec.iov_base(memorySegment, buffer);
            Iovec.iov_len(memorySegment, buffer.byteSize());
        } catch (Throwable e) {
            throw new RuntimeException("Could not set com.davidvlijmincx.lio.api.iovec data", e);
        }
    }

    static int OpenFile(String filePath, int flags, int mode) {
        try {
            int fd = (int) open.invokeExact(MemorySegment.ofArray((filePath + "\0").getBytes()), flags, mode);
            if (fd < 0) {
                throw new RuntimeException("Failed to open file: " + getErrorMessage(fd));
            }
            return fd;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static String getErrorMessage(int errno) {
        try {
            MemorySegment result = (MemorySegment) strerror.invokeExact(errno);
            return result.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable e) {
            throw new RuntimeException("Could not get error message for errno: " + errno, e);
        }
    }

    static void freeBuffer(MemorySegment buffer) {
        try {
            free.invokeExact(buffer);
        } catch (Throwable e) {
            throw new RuntimeException("Could not free memory", e);
        }
    }

    static void closeFile(int fd) {
        try {
            close.invokeExact(fd);
        } catch (Throwable e) {
            throw new RuntimeException("Could not close file with FD:" + fd, e);
        }
    }

    static MemorySegment malloc(int size) {
        return malloc((long) size);
    }

    static MemorySegment malloc(long size) {

        if (size >= 4000) {
            return calloc(size);
        }

        try {
            return ((MemorySegment) malloc.invokeExact(size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }


    static MemorySegment calloc(long size) {
        try {
            return ((MemorySegment) calloc.invokeExact(1L, size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
