package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code malloc(3)}.
 */
public interface Malloc {

    MemorySegment malloc(long size);
}
