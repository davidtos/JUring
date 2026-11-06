package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code strerror(3)}.
 */
public interface Strerror {

    MemorySegment strerror(int errno);
}
