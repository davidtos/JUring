package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class ReadRequest extends Request {
    public ReadRequest(int fd, MemorySegment buffer) {
        super(fd, buffer);
    }
}
