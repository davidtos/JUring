package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface WaitCqeNr {
    int waitForCqeNr(MemorySegment ring, MemorySegment cqePtrPtr, int waitNumber);
}
