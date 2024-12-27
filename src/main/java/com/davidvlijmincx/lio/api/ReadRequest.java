package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class ReadRequest extends Request {

    public ReadRequest(long id, int fd, MemorySegment buffer) {
        super(id, fd, buffer);
    }
}
