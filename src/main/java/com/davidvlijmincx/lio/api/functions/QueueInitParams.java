package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface QueueInitParams {
    int queueInitParams(int queueDepth, MemorySegment ring, MemorySegment flags);
}