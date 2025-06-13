package com.davidvlijmincx.lio.api;

public final class WriteResult extends Result {

    private long result;

    WriteResult(long id, long result) {
        super(id);
        this.result = result;
    }

    public long getResult() {
        return result;
    }
}
