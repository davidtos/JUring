package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareWriteFixed {
    void prepareWriteFixed(MemorySegment sqe, int fd, MemorySegment buffer, long nbytes, long offset, int bufferIndex);
}