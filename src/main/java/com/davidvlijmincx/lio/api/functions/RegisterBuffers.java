package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface RegisterBuffers {
    int registerBuffers(MemorySegment ring, MemorySegment iovecs, int nrIovecs);
}