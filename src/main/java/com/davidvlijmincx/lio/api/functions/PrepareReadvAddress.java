package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareReadvAddress {
    void prepareReadv(MemorySegment sqe, int fd, long iovecs, int nrVecs, long offset);
}
