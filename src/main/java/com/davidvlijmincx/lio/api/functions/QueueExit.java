package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface QueueExit {
    void queueExit(MemorySegment ring);
}