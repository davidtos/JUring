package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface PrepareMultishotAccept {
    void prepareMultishotAccept(MemorySegment sqe, int fd, MemorySegment addr, MemorySegment addrLen, int flags);
}
