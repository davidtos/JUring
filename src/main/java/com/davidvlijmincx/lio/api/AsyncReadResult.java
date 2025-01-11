package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class AsyncReadResult extends Result implements ReadResult {

    private final MemorySegment buffer;
    private final long result;

    public AsyncReadResult(long id, MemorySegment buffer, long result) {
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
        LibCWrapper.freeBuffer(buffer);
    }
}
