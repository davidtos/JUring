package com.davidvlijmincx.lio.api;

public final class SocketResult extends Result {

    long result;

    public SocketResult(long id, long result) {
        super(id);
        this.result = result;
    }

    public long getResult() {
        return result;
    }
}