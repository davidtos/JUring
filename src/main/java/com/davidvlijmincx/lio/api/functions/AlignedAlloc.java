package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface AlignedAlloc {

    MemorySegment alignedAlloc(long alignment, long size);
}
