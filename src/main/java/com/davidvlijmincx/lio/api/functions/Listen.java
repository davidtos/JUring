package com.davidvlijmincx.lio.api.functions;

/**
 * Downcall interface for {@code listen(2)}.
 */
public interface Listen {
    int listen(int sockfd, int backlog);
}
