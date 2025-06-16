package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public record ReadResult(long id, MemorySegment buffer,long result ) implements Result, AutoCloseable {

    public void freeBuffer() {
        NativeDispatcher.C.free(buffer);
    }

    @Override
    public void close() throws Exception {
        freeBuffer();
    }
}
