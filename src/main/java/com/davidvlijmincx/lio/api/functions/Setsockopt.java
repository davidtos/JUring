package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface Setsockopt {
    int setsockopt(int sockfd, int level, int optname, MemorySegment optval, int optlen);
}