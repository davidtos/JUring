package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface Strerror {

    MemorySegment strerror(int errno);
}
