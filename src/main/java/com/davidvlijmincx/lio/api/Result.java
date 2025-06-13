package com.davidvlijmincx.lio.api;

public abstract sealed class Result permits CloseResult, OpenResult, ReadResult, SocketResult, WriteResult {

    private final long id;

    protected Result(long id) {
        this.id = id;
    }

    public long getId() {
        return id;
    }
}
