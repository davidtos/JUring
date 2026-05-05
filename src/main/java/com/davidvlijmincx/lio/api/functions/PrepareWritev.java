package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareWritev {
    void prepareWritev(MemorySegment sqe, int fd, MemorySegment iovecs, int nrVecs, long offset);
}
