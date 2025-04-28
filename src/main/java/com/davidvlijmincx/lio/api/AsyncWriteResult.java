package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class AsyncWriteResult extends Result implements WriteResult {

    private final MemorySegment buffer;
    private final long result;

    AsyncWriteResult(long id, MemorySegment buffer, long result) {
        super(id);
        this.buffer = buffer;
        this.result = result;
    }

    public long getResult() {
        return result;
    }

    public void freeBuffer() {
        LibCWrapper.freeBuffer(buffer);
    }
}
