package com.davidvlijmincx.lio.api.functions;

/**
 * Downcall interface for {@code socket(2)}.
 */
public interface Socket {
    int socket(int domain, int type, int protocol);
}
