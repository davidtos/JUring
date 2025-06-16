package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareRead {
    void prepareRead(MemorySegment sqe, int fd, MemorySegment buffer,long readSize, long offset);
}
