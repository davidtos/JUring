package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface CqAdvance {
    void peekBatchCqe(MemorySegment ring, int nr);
}