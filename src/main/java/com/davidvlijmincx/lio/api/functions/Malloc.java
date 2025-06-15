package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface Malloc {

    MemorySegment malloc(long size);
}
