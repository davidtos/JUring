package com.davidvlijmincx.lio.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class BlockingWriteResult extends Result implements WriteResult, BlockingResult{

    private final CompletableFuture<Void> lock = new CompletableFuture<>();
    private long result;

    public BlockingWriteResult(long id) {
        super(id);
    }

    public void setResult(AsyncReadResult result) {
        this.result = result.getResult();
        lock.complete(null);
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
            lock.complete(null);
        } else {
            throw new IllegalArgumentException("Result is not a WriteResult");
        }
    }
}
