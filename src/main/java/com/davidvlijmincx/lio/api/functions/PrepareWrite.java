package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareWrite {
    void prepareWrite(MemorySegment sqe, int fd, MemorySegment buffer, long nbytes, long offset);
}