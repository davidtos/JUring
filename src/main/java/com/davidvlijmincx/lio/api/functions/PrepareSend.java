package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareSend {
    void prepareSend(MemorySegment sqe, int fd, MemorySegment buf, long len, int flags);
}
