package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareSend {
    void prepareSend(MemorySegment sqe, int fd, MemorySegment buffer, long bufferSize, int flags);
}