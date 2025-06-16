package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepOpenAt {
    void prepareOpen(MemorySegment sqe,int dfd, MemorySegment filePath, int flags, int mode);
}
