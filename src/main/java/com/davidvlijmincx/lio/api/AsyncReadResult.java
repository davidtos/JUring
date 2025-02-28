package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class AsyncReadResult extends Result implements ReadResult {

    private final MemorySegment buffer;
    private final long result;
    private final boolean hasArena;

    AsyncReadResult(long id, MemorySegment buffer, long result, boolean hasArena) {
        super(id);
        this.buffer = buffer;
        this.result = result;
        this.hasArena = hasArena;
    }

    public MemorySegment getBuffer() {
        return buffer;
    }

    public long getResult() {
        return result;
    }

    public void freeBuffer() {
        if (hasArena){
            throw new UnsupportedOperationException("This read result is part of an arena, and cannot be freed manually.");
        }
        LibCWrapper.freeBuffer(buffer);
    }
}
