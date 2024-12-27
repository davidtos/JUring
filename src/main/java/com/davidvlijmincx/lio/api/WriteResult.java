package com.davidvlijmincx.lio.api;

public final class WriteResult extends Result {

    private final long result;

    public WriteResult(long id,long result) {
        super(id);
        this.result = result;
    }

    public long getResult() {
        return result;
    }
}
