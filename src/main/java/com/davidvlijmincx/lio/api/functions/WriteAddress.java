package com.davidvlijmincx.lio.api.functions;

/**
 * Downcall interface for {@code write(2)} using a raw buffer address.
 */
public interface WriteAddress {

    long write(int fd, long buf, long count);
}
