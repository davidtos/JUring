package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public sealed interface ReadResult permits AsyncReadResult, BlockingReadResult {
    long getId();
    MemorySegment getBuffer();
    long getResult();
    void freeBuffer();
}
