package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code open(2)}.
 */
public interface Open {

    int open(MemorySegment path, int flags, int mode);
}
