package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code calloc(3)}.
 */
public interface Calloc {

    MemorySegment calloc(long num, long size);
}
