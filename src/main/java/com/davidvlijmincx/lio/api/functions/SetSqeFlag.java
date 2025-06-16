package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface SetSqeFlag {

    void setSqeFlag(MemorySegment sqe, byte flag);
}
