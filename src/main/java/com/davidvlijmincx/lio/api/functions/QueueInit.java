package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface QueueInit {
    int queueInit(int queueDepth, MemorySegment ring, int flags);
}