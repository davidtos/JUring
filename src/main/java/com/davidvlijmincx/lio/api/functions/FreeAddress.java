package com.davidvlijmincx.lio.api.functions;

/**
 * Downcall interface for {@code free(3)}.
 */
@FunctionalInterface
public interface FreeAddress {
    void free(long address);
}
