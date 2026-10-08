package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface GetSqe {

    long getSqe(MemorySegment ring);
}
