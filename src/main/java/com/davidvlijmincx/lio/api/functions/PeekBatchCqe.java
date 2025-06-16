package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PeekBatchCqe {
    int peekBatchCqe(MemorySegment ring, MemorySegment cqePtrPtr, int batchSize);
}