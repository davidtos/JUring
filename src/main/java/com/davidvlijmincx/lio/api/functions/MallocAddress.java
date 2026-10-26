package com.davidvlijmincx.lio.api.functions;

/**
 * Downcall interface for {@code malloc(3)} returning a raw memory address.
 */
public interface MallocAddress {

    long malloc(long size);
}
