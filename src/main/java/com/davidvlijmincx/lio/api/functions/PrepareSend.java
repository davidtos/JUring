package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

@FunctionalInterface
public interface PrepareSend {
    void prepSend(MemorySegment sqe, int sockFd, MemorySegment buffer, long len, int flags);
}
