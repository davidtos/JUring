package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public record IoResult(
        long id,
        OperationType type,
        int returnValue,
        MemorySegment buffer
) {

    public MemorySegment readBuffer() {
        return buffer;
    }

    public int fileDescriptor() {
        return returnValue;
    }

    public int bytesTransferred() {
        return returnValue;
    }

    public void freeBuffer() {
        LibCWrapper.freeBuffer(buffer);
    }

    public boolean isSuccess() {
        return returnValue >= 0;
    }

    public int errorCode() {
        return isSuccess() ? 0 : -returnValue;
    }
}