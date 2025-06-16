package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareClose {

    void prepareClose(MemorySegment sqe, int fd);
}
