package com.davidvlijmincx.lio.api;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

class LibCWrapper {

    private static final MethodHandle free;
    private static final MethodHandle open;
    private static final MethodHandle malloc;
    private static final MethodHandle close;

    static {
        Linker linker = Linker.nativeLinker();

        free = linker.downcallHandle(
                linker.defaultLookup().find("free").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS)
        );

        open = linker.downcallHandle(
                linker.defaultLookup().find("open").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
        );


        malloc = linker.downcallHandle(
                linker.defaultLookup().find("malloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG)
        );

        close = linker.downcallHandle(
                linker.defaultLookup().find("close").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT)
        );
    }

    private LibCWrapper() {
    }

    static int openFile(MemorySegment filePath, int flags, int mode) {

        try {
            int fd = (int) open.invokeExact(filePath, flags, mode);
            if (fd < 0) {
                throw new RuntimeException("Failed to open file");
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
        try {
            return ((MemorySegment) malloc.invokeExact(size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
