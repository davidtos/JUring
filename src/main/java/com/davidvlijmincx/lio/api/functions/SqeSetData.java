package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface SqeSetData {
    void sqeSetData(MemorySegment sqe, long userData);
}