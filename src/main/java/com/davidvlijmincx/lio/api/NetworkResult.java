package com.davidvlijmincx.lio.api;

public class NetworkResult {
    private final int fd;
    private final int type;
    private final long result;

    public NetworkResult(int fd, int type, long result) {
        this.fd = fd;
        this.type = type;
        this.result = result;
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
}