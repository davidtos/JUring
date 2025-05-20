package com.davidvlijmincx.lio.api;

public final class CloseResult extends Result {

    private final int result;

    CloseResult(long id, int result) {
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
