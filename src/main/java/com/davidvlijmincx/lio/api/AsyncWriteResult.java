package com.davidvlijmincx.lio.api;

public final class AsyncWriteResult extends Result implements WriteResult {

    private final long result;

    AsyncWriteResult(long id, long result) {
        super(id);
        this.result = result;
    }

    public long getResult() {
        return result;
    }
}
