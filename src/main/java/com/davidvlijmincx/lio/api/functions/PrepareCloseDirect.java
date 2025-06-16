package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareCloseDirect {
    void prepareCloseDirect(MemorySegment sqe, int fileIndex);
}
