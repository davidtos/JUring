package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareReadFixed {
    void prepareReadFixed(MemorySegment sqe, int fd, MemorySegment buffer,long readSize, long offset, int bufferIndex);
}
