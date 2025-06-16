package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface WaitCqe {
    int waitCqe(MemorySegment ring, MemorySegment cqePtr);
}