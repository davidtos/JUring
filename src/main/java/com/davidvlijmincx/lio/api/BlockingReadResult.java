package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class BlockingReadResult extends Result implements ReadResult, BlockingResult{

    private final CompletableFuture<Void> lock = new CompletableFuture<>();
    private MemorySegment buffer;
    private long result;


    public BlockingReadResult(long id) {
        super(id);
    }


    public MemorySegment getBuffer() {
        try {
            lock.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        return buffer;
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
        if (result instanceof ReadResult r) {
            this.result = r.getResult();
            this.buffer = r.getBuffer();
            lock.complete(null);
        } else {
            throw new IllegalArgumentException("Result is not a ReadResult");
        }
    }

    public void freeBuffer() {
        LibCWrapper.freeBuffer(buffer);
    }
}
