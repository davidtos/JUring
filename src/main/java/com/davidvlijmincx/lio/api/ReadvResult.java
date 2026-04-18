package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public record ReadvResult(long id, MemorySegment[] buffers, long result) implements Result {

    public void freeBuffers() {
        for (MemorySegment buffer : buffers) {
            NativeDispatcher.C.free(buffer);
        }
    }
}
