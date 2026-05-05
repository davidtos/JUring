package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareAccept {
    void prepareAccept(MemorySegment sqe, int fd, MemorySegment addr, MemorySegment addrLen, int flags);
}
