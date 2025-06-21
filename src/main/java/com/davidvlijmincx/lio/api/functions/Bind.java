package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface Bind {
    int bind(int sockfd, MemorySegment addr, int addrlen);
}