package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class BlockingReadResult extends Result implements ReadResult{

    private final CompletableFuture<Void> lock = new CompletableFuture<>();
    private MemorySegment buffer;
    private long result;


    public BlockingReadResult(long id) {
        super(id);
    }


    public void setResult(AsyncReadResult result) {
        this.result = result.getResult();
        this.buffer = result.getBuffer();
        lock.complete(null);
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
        return result;
    }

}
