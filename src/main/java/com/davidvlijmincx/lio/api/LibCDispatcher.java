package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.Calloc;
import com.davidvlijmincx.lio.api.functions.Malloc;
import com.davidvlijmincx.lio.api.functions.Open;
import com.davidvlijmincx.lio.api.functions.Strerror;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static java.lang.foreign.ValueLayout.ADDRESS;

record LibCDispatcher(Consumer<MemorySegment> free,
                      Open open,
                      IntConsumer close,
                      Malloc malloc,
                      Strerror strerror,
                      Calloc calloc) {

    private static final Linker linker = Linker.nativeLinker();

    static LibCDispatcher create() {
        return new LibCDispatcher(
                link(Consumer.class, "free", FunctionDescriptor.ofVoid(ADDRESS), true),
                link(Open.class, "open", FunctionDescriptor.of(ValueLayout.JAVA_INT, ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), true),
                link(IntConsumer.class, "close", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT), true),
                link(Malloc.class, "malloc", FunctionDescriptor.of(ADDRESS, ValueLayout.JAVA_LONG), true),
                link(Strerror.class, "strerror", FunctionDescriptor.of(ADDRESS, ValueLayout.JAVA_INT), false),
                link(Calloc.class, "calloc", FunctionDescriptor.of(ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), true)
        );
    }

    private static <T> T link(Class<T> type, String name, FunctionDescriptor descriptor, boolean critical) {
        MemorySegment symbol = linker.defaultLookup().findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.critical(critical));
        return MethodHandleProxies.asInterfaceInstance(type, handle);
    }

    void free(MemorySegment address) {
        free.accept(address);
    }

    int open(String path, int flags, int mode) {
        return open.open(MemorySegment.ofArray((path + "\0").getBytes()), flags, mode);
    }

    void close(int fd) {
        close.accept(fd);
    }

    MemorySegment malloc(long size) {
        return malloc.malloc(size).reinterpret(size);
    }

    String strerror(int errno) {
        return strerror.strerror(errno).reinterpret(Long.MAX_VALUE).getString(0);
    }

    MemorySegment calloc(long size) {
        return calloc.calloc(1L, size).reinterpret(size);
    }

    IovecStructure allocateIovec(Arena arena, long bufferSize, long nrIovecs) {
        MemorySegment iovecArray = Iovec.allocateArray(nrIovecs, arena);
        MemorySegment[] buffers = new MemorySegment[(int) nrIovecs];

        for (int i = 0; i < nrIovecs; i++) {
            MemorySegment nthIovec = Iovec.asSlice(iovecArray, i);
            MemorySegment buffer = malloc(bufferSize);

            Iovec.iov_base(nthIovec, buffer);
            Iovec.iov_len(nthIovec, buffer.byteSize());
            buffers[i] = buffer;
        }

        return new IovecStructure(iovecArray, buffers);
    }

    record IovecStructure(MemorySegment iovecArray, MemorySegment[] buffers) {
    }

    MemorySegment alloc(long size) {

        if (size >= 4000) {
            return calloc(size);
        }

        try {
            return malloc(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}

