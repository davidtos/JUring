package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class ReadResult extends Result {

    MemorySegment buffer;
    long result;


    ReadResult(long id) {
        super(id);
    }

    ReadResult(long id, MemorySegment buffer, long result) {
        super(id);
        this.buffer = buffer;
        this.result = result;
    }

    public MemorySegment getBuffer() {
        return buffer;
    }

    public long getResult() {
        return result;
    }

    public void freeBuffer() {
        NativeDispatcher.C.free(buffer);
    }
}
