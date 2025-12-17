package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

@FunctionalInterface
public interface PrepareRecv {
    void prepRecv(MemorySegment sqe, int sockFd, MemorySegment buffer, long len, int flags);
}
