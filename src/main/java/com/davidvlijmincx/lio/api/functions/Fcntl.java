package com.davidvlijmincx.lio.api.functions;

/**
 * Downcall interface for {@code fcntl(2)}.
 */
public interface Fcntl {

    int fcntl(int fd, int cmd);
}
