package com.davidvlijmincx.lio.api.functions;

/**
 * {@code int munmap(void *addr, size_t length)}.
 */
public interface Munmap {
    int munmap(long address, long length);
}
