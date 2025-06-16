package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PeekCqe {
    int peekCqe(MemorySegment ring, MemorySegment cqePtr);
}