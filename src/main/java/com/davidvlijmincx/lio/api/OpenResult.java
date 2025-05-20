package com.davidvlijmincx.lio.api;

public final class OpenResult extends Result {

    private final int result;

    OpenResult(long id, int result) {
        super(id);
        this.result = result;
    }

    public int getResult() {
        return result;
    }

    @Override
    public void freeBuffer() {
        throw new UnsupportedOperationException("OpenResult has no buffer");
    }
}
