package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareOpenDirect {

    void prepareOpenDirect(MemorySegment sqe,int dfd, MemorySegment filePath, int flags, int mode, int fileIndex);
}
