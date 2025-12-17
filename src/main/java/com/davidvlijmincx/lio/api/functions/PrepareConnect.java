package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

@FunctionalInterface
public interface PrepareConnect {
    void prepConnect(MemorySegment sqe, int sockFd, MemorySegment addr, int addrLen);
}
