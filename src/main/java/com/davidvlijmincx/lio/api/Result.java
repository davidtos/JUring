package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

/**
 * Unified result class for all io_uring completion events.
 *
 * Replaces the previous 10 separate result records/classes to eliminate per-CQE class
 * instantiations and enables zero-allocation polling via reusable instances.
 */
public class Result implements AutoCloseable {

    private long id;
    private long result;
    private OperationType type;
    private long bufferAddress;
    private MemorySegment buffer;
    private int bufferIdx = -1;
    private MemorySegment[] buffers;

    public Result() {}

    public Result(long id, long result, OperationType type, long bufferAddress, MemorySegment buffer, int bufferIdx, MemorySegment[] buffers) {
        set(id, result, type, bufferAddress, buffer, bufferIdx, buffers);
    }

    public Result(Result other) {
        this.id = other.id;
        this.result = other.result;
        this.type = other.type;
        this.bufferAddress = other.bufferAddress;
        this.buffer = other.buffer;
        this.bufferIdx = other.bufferIdx;
        this.buffers = other.buffers;
    }

    public void set(long id, long result, OperationType type, long bufferAddress, MemorySegment buffer, int bufferIdx, MemorySegment[] buffers) {
        this.id = id;
        this.result = result;
        this.type = type;
        this.bufferAddress = bufferAddress;
        this.buffer = buffer;
        this.bufferIdx = bufferIdx;
        this.buffers = buffers;
    }

    public long id() {
        return id;
    }

    public long result() {
        return result;
    }

    public OperationType type() {
        return type;
    }

    public boolean isSuccess() {
        return result >= 0;
    }

    public long bufferAddress() {
        return bufferAddress;
    }

    public MemorySegment buffer() {
        if (buffer != null) {
            return buffer;
        }
        if (bufferAddress != 0L) {
            buffer = MemorySegment.ofAddress(bufferAddress).reinterpret(Long.MAX_VALUE);
            return buffer;
        }
        return MemorySegment.NULL;
    }

    public int bufferIdx() {
        return bufferIdx;
    }

    public int fd() {
        return (int) result;
    }

    public FileDescriptor fileDescriptor() {
        return new FileDescriptor((int) result);
    }

    public MemorySegment[] buffers() {
        return buffers;
    }

    public long bytesRead() {
        return result;
    }

    public long bytesSent() {
        return result;
    }

    public void freeBuffer() {
        if (bufferAddress != 0L) {
            NativeDispatcher.C.free(bufferAddress);
            bufferAddress = 0L;
            buffer = null;
        } else if (buffer != null && !buffer.equals(MemorySegment.NULL)) {
            NativeDispatcher.C.free(buffer);
            buffer = null;
        }
    }

    public void freeBuffers() {
        if (buffers != null) {
            for (MemorySegment b : buffers) {
                if (b != null && !b.equals(MemorySegment.NULL)) {
                    NativeDispatcher.C.free(b);
                }
            }
            buffers = null;
        }
    }

    @Override
    public void close() {
        freeBuffer();
    }

    @Override
    public String toString() {
        return "Result{" +
                "id=" + id +
                ", result=" + result +
                ", type=" + type +
                ", bufferIdx=" + bufferIdx +
                '}';
    }
}
