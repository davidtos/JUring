package com.davidvlijmincx.lio.api;

public abstract sealed class Result permits AsyncReadResult, AsyncWriteResult, BlockingReadResult, BlockingWriteResult {

    private final long id;

    protected Result(long id) {
        this.id = id;
    }

    public long getId() {
        return id;
    }
}
