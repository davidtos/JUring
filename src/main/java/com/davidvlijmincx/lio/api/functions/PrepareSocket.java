package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

@FunctionalInterface
public interface PrepareSocket {
    void prepSocket(MemorySegment sqe, int domain, int type, int protocol, int flags);
}
