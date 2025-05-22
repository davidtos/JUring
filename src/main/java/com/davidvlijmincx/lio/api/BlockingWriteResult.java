package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class BlockingWriteResult extends BlockingResult {

    private final CompletableFuture<Void> lock = new CompletableFuture<>();
    private MemorySegment buffer;
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


    public void setResult(IoResult result) {
        if (result.type() == OperationType.WRITE) {
            this.result = result.bytesTransferred();
            this.buffer = result.buffer();
            lock.complete(null);
        } else {
            throw new IllegalArgumentException("Result is not a WriteResult");
        }
    }

    @Override
    public void freeBuffer() {
        LibCWrapper.freeBuffer(buffer);
    }
}
