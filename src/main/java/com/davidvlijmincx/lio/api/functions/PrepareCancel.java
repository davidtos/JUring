package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareCancel {
    void prepareCancel(MemorySegment sqe, long userData, int flags);
}
