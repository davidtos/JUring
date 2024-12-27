package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class ReadResult extends Result {

    private final MemorySegment buffer;
    private final long result;

    public ReadResult(MemorySegment buffer, long result) {
        this.buffer = buffer;
        this.result = result;
    }

    public MemorySegment getBuffer() {
        return buffer;
    }

    public long getResult() {
        return result;
    }
}
