package com.davidvlijmincx.lio.api;

public final class CloseResult extends Result {

    int result;

    CloseResult(long id, int result) {
        super(id);
        this.result = result;
    }

    public int getResult() {
        return result;
    }
}
