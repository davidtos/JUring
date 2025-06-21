package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareAccept {
    void prepareAccept(MemorySegment sqe, int serverFd, MemorySegment addr, MemorySegment addrlen, int flags);
}