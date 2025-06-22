package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepOpenAt {
    void prepareOpenAt(MemorySegment sqe, int dfd, MemorySegment filePath, int flags, int mode);
}
