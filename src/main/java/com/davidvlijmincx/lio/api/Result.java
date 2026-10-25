package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

/**
 * Unified, mutable result class for all {@code io_uring} completion events.
 * <p>
 * <b>Why this change was made (Eliminating Polymorphic Allocations):</b>
 * Previously, JUring had over 10 distinct immutable record types (e.g. {@code ReadResult}, {@code WriteResult},
 * {@code OpenResult}, {@code AcceptResult}, etc.). Under high load (>300,000 ops/s), creating a new record instance
 * for every single completion resulted in severe GC allocation pressure (~50-100 MB/s).
 * <p>
 * <b>How it works:</b>
 * This single class consolidates all possible completion fields into a reusable, mutable container.
 * In-place reaping loops (such as {@link IoUringDispatcher#peekForBatchResult(int, ResultConsumer)}) populate a single
 * reused instance and invoke {@link ResultConsumer#accept(Result)}.
 * <p>
 * <b>Lazy Buffer Materialization:</b>
 * For fixed operations, the dispatcher records only the raw primitive {@code bufferAddress}. The higher-level
 * {@link MemorySegment} wrapper is only materialized if the caller explicitly invokes {@link #buffer()},
 * completely eliminating Java heap object creation in tight I/O loops.
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
