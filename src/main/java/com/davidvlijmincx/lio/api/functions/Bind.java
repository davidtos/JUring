package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code bind(2)}.
 */
public interface Bind {
    int bind(int sockfd, MemorySegment addr, int addrLen);
}
