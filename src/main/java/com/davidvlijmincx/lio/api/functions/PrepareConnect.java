package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareConnect {
    void prepareConnect(MemorySegment sqe, int fd, MemorySegment addr, int addrLen);
}
