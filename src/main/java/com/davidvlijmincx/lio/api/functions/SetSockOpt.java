package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code setsockopt(2)}.
 */
public interface SetSockOpt {
    int setsockopt(int sockfd, int level, int optname, MemorySegment optval, int optlen);
}
