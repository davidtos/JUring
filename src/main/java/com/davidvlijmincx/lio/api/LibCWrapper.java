package com.davidvlijmincx.lio.api;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.*;

class LibCWrapper {

    private static final MethodHandle free;
    private static final MethodHandle open;
    private static final MethodHandle close;
    private static final MethodHandle malloc;
    private static final MethodHandle calloc;

    static {
        Linker linker = Linker.nativeLinker();

        free = linker.downcallHandle(
                linker.defaultLookup().find("free").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS)
                , Linker.Option.critical(true)
        );

        open = linker.downcallHandle(
                linker.defaultLookup().find("open").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                Linker.Option.critical(true)
        );


        malloc = linker.downcallHandle(
                linker.defaultLookup().find("malloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG)
                , Linker.Option.critical(true)
        );

        calloc = linker.downcallHandle(
                linker.defaultLookup().find("calloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG)
                , Linker.Option.critical(true)
        );

        close = linker.downcallHandle(
                linker.defaultLookup().find("close").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT)
                , Linker.Option.critical(true)
        );
    }

    private LibCWrapper() {
    }

    static int OpenFile(String filePath, int flags, int mode, byte[] stableBuffer) {
        try {
            byte[] pathBytes = filePath.getBytes();
            Arrays.fill(stableBuffer,pathBytes.length,pathBytes.length + 5,(byte) 0);
            System.arraycopy(pathBytes, 0, stableBuffer, 0, pathBytes.length);


            int fd = (int) open.invokeExact(MemorySegment.ofArray(stableBuffer), flags, mode);
            if (fd < 0) {
                throw new RuntimeException("Failed to open file fd=" + fd);
            }
            return fd;
        } catch (Throwable e) {
            throw new RuntimeException(e);
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
