package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

abstract sealed class Request permits ReadRequest, WriteRequest{

    private final int fd;
    private final MemorySegment buffer;

    public Request(int fd, MemorySegment buffer) {
        this.fd = fd;
        this.buffer = buffer;
    }

    int getFd() {
        return fd;
    }

    public MemorySegment getBuffer() {
        return buffer;
    }
}
