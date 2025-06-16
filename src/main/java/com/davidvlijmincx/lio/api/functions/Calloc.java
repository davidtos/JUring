package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface Calloc {

    MemorySegment calloc(long num, long size);
}
