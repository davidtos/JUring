package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface RegisterFilesUpdate {
    int registerFilesUpdate(MemorySegment ring, int offset, MemorySegment fdArray, int count);
}