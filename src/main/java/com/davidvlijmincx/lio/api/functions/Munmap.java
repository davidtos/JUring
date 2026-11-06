package com.davidvlijmincx.lio.api.functions;

/**
 * Downcall interface for {@code munmap(2)}.
 */
public interface Munmap {
    int munmap(long address, long length);
}
