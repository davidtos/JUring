package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareReadAddress {
    void prepareRead(MemorySegment sqe, int fd, long buffer,long readSize, long offset);
}
