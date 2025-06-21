package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareRecv {
    void prepareRecv(MemorySegment sqe, int fd, MemorySegment buffer, long bufferSize, int flags);
}