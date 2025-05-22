package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class BlockingReadResult extends BlockingResult{

    private final CompletableFuture<Void> lock = new CompletableFuture<>();
    private MemorySegment buffer;
    private long result;

    BlockingReadResult(long id) {
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

    public void setResult(IoResult result) {
        if (result.type() == OperationType.READ) {
            this.result = result.bytesTransferred();
            this.buffer = result.readBuffer();
            lock.complete(null);
        } else {
            throw new IllegalArgumentException("Result is not a ReadResult");
        }
    }

    public void freeBuffer() {
        LibCWrapper.freeBuffer(buffer);
    }
}
