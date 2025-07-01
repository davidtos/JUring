package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.Calloc;
import com.davidvlijmincx.lio.api.functions.Malloc;
import com.davidvlijmincx.lio.api.functions.Open;
import com.davidvlijmincx.lio.api.functions.Strerror;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

record LibCDispatcher(Consumer<MemorySegment> free,
                      Open open,
                      IntConsumer close,
                      Malloc malloc,
                      Strerror strerror,
                      Calloc calloc) {

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

