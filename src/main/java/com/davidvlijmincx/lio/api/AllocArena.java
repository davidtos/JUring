package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public class AllocArena implements Arena {

    private static final MethodHandle malloc;
    private static final MethodHandle calloc;
    private static final MethodHandle free;

    static {
        Linker linker = Linker.nativeLinker();

        malloc = linker.downcallHandle(linker.defaultLookup().find("malloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG),
                Linker.Option.critical(true));
        calloc = linker.downcallHandle(linker.defaultLookup().find("calloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG),
                Linker.Option.critical(true));
        free = linker.downcallHandle(linker.defaultLookup().find("free").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS),
                Linker.Option.critical(true));

    }

    final Arena arena = Arena.ofConfined();

    @Override
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        if (byteSize >= 4000) {
            return calloc(byteSize).reinterpret(byteSize, arena, this::freeBuffer);
        } else {
            return malloc(byteSize).reinterpret(byteSize, arena, this::freeBuffer);
        }
    }

    MemorySegment malloc(long size) {

        if (size >= 4000) {
            return calloc(size);
        }

        try {
            return ((MemorySegment) malloc.invokeExact(size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    MemorySegment calloc(long size) {
        try {
            return ((MemorySegment) calloc.invokeExact(1L, size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void freeBuffer(MemorySegment buffer) {
        try {
            free.invokeExact(buffer);
        } catch (Throwable e) {
            throw new RuntimeException("Could not free memory", e);
        }
    }

    @Override
    public MemorySegment.Scope scope() {
        return arena.scope();
    }

    @Override
    public void close() {
        arena.close();
    }
}
