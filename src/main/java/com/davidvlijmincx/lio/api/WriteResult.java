package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public sealed interface WriteResult permits AsyncWriteResult, BlockingWriteResult{

    long getResult();
    void freeBuffer();
}
