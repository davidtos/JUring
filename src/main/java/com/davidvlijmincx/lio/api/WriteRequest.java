package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class WriteRequest extends Request {
    public WriteRequest(int fd, MemorySegment buffer) {
        super(fd, buffer);
    }
}
