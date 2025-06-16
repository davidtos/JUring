package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface RegisterFiles {
    int registerFiles(MemorySegment ring, MemorySegment fdArray, int count);
}