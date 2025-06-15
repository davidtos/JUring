package com.davidvlijmincx.lio.api;
import com.davidvlijmincx.lio.api.functions.Calloc;
import com.davidvlijmincx.lio.api.functions.Malloc;
import com.davidvlijmincx.lio.api.functions.Open;
import com.davidvlijmincx.lio.api.functions.Strerror;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

record LibCDispatcher (Consumer<MemorySegment> free,
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

     void close(int fd){ close.accept(fd); }

     MemorySegment malloc(long size) {
       return malloc.malloc(size).reinterpret(size);
    }

     String strerror(int errno) {
        return strerror.strerror(errno).reinterpret(Long.MAX_VALUE).getString(0);
    }

     MemorySegment calloc(long size){
       return calloc.calloc(1L, size).reinterpret(size);
    }

     IovecStructure allocateIovec(Arena arena, long bufferSize, long nrIovecs) {
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

    private void setIovecData(MemorySegment memorySegment, MemorySegment buffer) {
        try {
            Iovec.iov_base(memorySegment, buffer);
            Iovec.iov_len(memorySegment, buffer.byteSize());
        } catch (Throwable e) {
            throw new RuntimeException("Could not set iovec data", e);
        }
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

