package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface Submit {
    int submit(MemorySegment ring);
}