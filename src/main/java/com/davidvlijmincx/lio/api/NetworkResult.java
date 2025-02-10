package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public class NetworkResult {
    private final int fd;
    private final int type;
    private final long result;
    private final MemorySegment buffer;

    public NetworkResult(int fd, int type, long result, MemorySegment buffer) {
        this.fd = fd;
        this.type = type;
        this.result = result;
        this.buffer = buffer;
    }

    public int getFd() {
        return fd;
    }

    public int getType() {
        return type;
    }

    public long getResult() {
        return result;
    }

    public MemorySegment getBuffer() {
        return buffer;
    }
}