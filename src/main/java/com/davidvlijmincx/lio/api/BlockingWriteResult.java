package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class BlockingWriteResult extends Result implements WriteResult, BlockingResult{

    private final CompletableFuture<Void> lock = new CompletableFuture<>();
    private WriteResult writeResult;
    private long result;

    BlockingWriteResult(long id) {
        super(id);
    }

    public long getResult() {
        try {
            lock.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public void setResult(Result result) {
        if (result instanceof WriteResult r) {
            this.result = r.getResult();
            this.writeResult = r;
            lock.complete(null);
        } else {
            throw new IllegalArgumentException("Result is not a WriteResult");
        }
    }

    @Override
    public void freeBuffer() {
        this.writeResult.freeBuffer();
    }
}
