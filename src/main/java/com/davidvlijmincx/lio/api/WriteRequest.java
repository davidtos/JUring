package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class WriteRequest extends Request {

    public WriteRequest(long id, int fd, MemorySegment buffer) {
        super(id, fd, buffer);
    }
}
