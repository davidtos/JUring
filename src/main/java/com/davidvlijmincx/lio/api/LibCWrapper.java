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

    private static final Linker linker = Linker.nativeLinker();
    private final static SymbolLookup libtcmalloc = Linker.nativeLinker().defaultLookup(); // SymbolLookup.libraryLookup("libtcmalloc_minimal.so.4", Arena.ofAuto());


    static {
        free = linker.downcallHandle(
                libtcmalloc.find("free").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS),
                Linker.Option.critical(true)
        );

        open = linker.downcallHandle(
                linker.defaultLookup().find("open").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                Linker.Option.critical(true)
        );

        malloc = linker.downcallHandle(
                libtcmalloc.find("malloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG),
                Linker.Option.critical(true)
        );

        calloc = linker.downcallHandle(
                libtcmalloc.find("calloc").orElseThrow(),
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
        var iovecSequence = MemoryLayout.sequenceLayout(nrIovecs, iovec.layout());
        var iovecArray = arena.allocate(iovecSequence);
        MemorySegment[] buffers = new MemorySegment[(int) nrIovecs];

        for (int i = 0; i < nrIovecs; i++) {
            MemorySegment nthIovec = iovecArray.asSlice(i * iovec.layout().byteSize(),  iovec.layout().byteSize());
            MemorySegment buffer = allocate(bufferSize);
            setIovecData(nthIovec, buffer);
            buffers[i] = buffer;
        }

        return new IovecStructure(iovecArray, buffers);
    }

    record IovecStructure(MemorySegment iovecArray, MemorySegment[] buffers) {
    }

    static private void setIovecData(MemorySegment memorySegment, MemorySegment buffer) {
        try {
            iovec.iov_base(memorySegment, buffer);
            iovec.iov_len(memorySegment, buffer.byteSize());
        } catch (Throwable e) {
            throw new RuntimeException("Could not set com.davidvlijmincx.lio.api.iovec data", e);
        }
    }

    static int OpenFile(String filePath, int flags, int mode) {
        try {
            int fd = (int) open.invokeExact(MemorySegment.ofArray((filePath + "\0").getBytes()), flags, mode);
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

    static MemorySegment allocate(int size) {
        return allocate((long) size);
    }

    static MemorySegment allocate(long size) {
        try {
            if (size >= 4000) {
                return ((MemorySegment) calloc.invokeExact(1L, size)).reinterpret(size);
            } else {
                return ((MemorySegment) malloc.invokeExact(size)).reinterpret(size);
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
