package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface CqeSeen {
    void cqeSeen(MemorySegment ring, MemorySegment cqePointer);
}