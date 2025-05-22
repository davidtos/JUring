package com.davidvlijmincx.lio.api;

public abstract sealed class BlockingResult permits BlockingReadResult, BlockingWriteResult {

    private final long id;

    protected BlockingResult(long id) {
        this.id = id;
    }

    public abstract void setResult(IoResult result);

    public long getId() {
        return id;
    }

    public abstract void freeBuffer();
}
