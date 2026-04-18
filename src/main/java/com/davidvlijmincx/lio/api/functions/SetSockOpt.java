package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface SetSockOpt {
    int setsockopt(int sockfd, int level, int optname, MemorySegment optval, int optlen);
}
