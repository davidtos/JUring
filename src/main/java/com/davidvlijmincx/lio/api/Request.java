package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

abstract sealed class Request permits ReadRequest, WriteRequest{

    private final long id;
    private final int fd;
    private final MemorySegment buffer;

    public Request(long id, int fd, MemorySegment buffer) {
        this.id = id;
        this.fd = fd;
        this.buffer = buffer;
    }

    public long getId() {
        return id;
    }

    int getFd() {
        return fd;
    }

    public MemorySegment getBuffer() {
        return buffer;
    }
}
