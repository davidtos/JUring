package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

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
 * <b>Lazy Buffer Materialization & Memory Safety:</b>
 * For fixed operations, the dispatcher records only the raw primitive {@code bufferAddress} and {@code bufferIdx}.
 * The higher-level {@link MemorySegment} wrapper is only materialized if the caller explicitly invokes {@link #buffer()},
 * returning the bounded registered buffer slice and completely eliminating Java heap object creation in tight I/O loops.
 */
public class Result implements AutoCloseable {

    private long id;
    private long result;
    private OperationType type;
    private long bufferAddress;
    private MemorySegment buffer;
    private int bufferIdx = -1;
    private MemorySegment[] buffers;
    private int cqeFlags;
    private MemorySegment[] registeredBuffers;

    public Result() {}

    public Result(long id, long result, OperationType type, long bufferAddress, MemorySegment buffer, int bufferIdx, MemorySegment[] buffers) {
        set(id, result, type, bufferAddress, buffer, bufferIdx, buffers, 0);
    }

    public Result(Result other) {
        this.id = other.id;
        this.result = other.result;
        this.type = other.type;
        this.bufferAddress = other.bufferAddress;
        this.buffer = other.buffer;
        this.bufferIdx = other.bufferIdx;
        this.buffers = other.buffers;
        this.cqeFlags = other.cqeFlags;
        this.registeredBuffers = other.registeredBuffers;
    }

    public void set(long id, long result, OperationType type, long bufferAddress, MemorySegment buffer, int bufferIdx, MemorySegment[] buffers) {
        set(id, result, type, bufferAddress, buffer, bufferIdx, buffers, 0);
    }

    public void set(long id, long result, OperationType type, long bufferAddress, MemorySegment buffer, int bufferIdx, MemorySegment[] buffers, int cqeFlags) {
        this.id = id;
        this.result = result;
        this.type = type;
        this.bufferAddress = bufferAddress;
        this.buffer = buffer;
        this.bufferIdx = bufferIdx;
        this.buffers = buffers;
        this.cqeFlags = cqeFlags;
    }

    void setRegisteredBuffers(MemorySegment[] registeredBuffers) {
        this.registeredBuffers = registeredBuffers;
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

    public int cqeFlags() {
        return cqeFlags;
    }

    /**
     * Returns {@code true} if {@code IORING_CQE_F_MORE} is set on the completion queue entry,
     * indicating that a multishot operation (e.g. {@code prepareMultishotAccept}) remains active
     * and will produce further completions.
     */
    public boolean hasMore() {
        return (cqeFlags & IoUringDispatcher.IORING_CQE_F_MORE) != 0;
    }

    public long bufferAddress() {
        return bufferAddress;
    }

    public MemorySegment buffer() {
        if (buffer != null) {
            return buffer;
        }
        if (type == OperationType.READ_FIXED && registeredBuffers != null && bufferIdx >= 0 && bufferIdx < registeredBuffers.length) {
            buffer = registeredBuffers[bufferIdx];
            return buffer;
        }
        if (bufferAddress != 0L) {
            if (type == OperationType.READ && result >= 0) {
                buffer = MemorySegment.ofAddress(bufferAddress).reinterpret(result + 1);
                buffer.set(JAVA_BYTE, result, (byte) 0);
                return buffer;
            }
            long len = result > 0 ? result : 0L;
            buffer = MemorySegment.ofAddress(bufferAddress).reinterpret(len);
            return buffer;
        }
        return MemorySegment.NULL;
    }

    /**
     * Returns a {@link MemorySegment} sliced to the exact number of bytes transferred ({@code 0..result}).
     */
    public MemorySegment validBuffer() {
        MemorySegment buf = buffer();
        if (buf.equals(MemorySegment.NULL) || result <= 0) {
            return MemorySegment.NULL;
        }
        long validLen = Math.min(result, buf.byteSize());
        return buf.byteSize() == validLen ? buf : buf.asSlice(0, validLen);
    }

    /**
     * Copies the valid transferred bytes ({@code 0..result}) into a new {@code byte[]}.
     */
    public byte[] toByteArray() {
        MemorySegment valid = validBuffer();
        if (valid.equals(MemorySegment.NULL)) {
            return new byte[0];
        }
        return valid.toArray(JAVA_BYTE);
    }

    /**
     * Decodes the valid transferred bytes ({@code 0..result}) as a UTF-8 {@link String}
     * without requiring a null-terminator byte.
     */
    public String getString() {
        return getString(StandardCharsets.UTF_8);
    }

    /**
     * Decodes the valid transferred bytes ({@code 0..result}) using the specified {@link Charset}
     * without requiring a null-terminator byte.
     */
    public String getString(Charset charset) {
        return new String(toByteArray(), charset);
    }

    /**
     * Wraps the valid transferred bytes ({@code 0..result}) as a direct {@link ByteBuffer}.
     */
    public ByteBuffer asByteBuffer() {
        MemorySegment valid = validBuffer();
        return valid.equals(MemorySegment.NULL) ? ByteBuffer.allocate(0) : valid.asByteBuffer();
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

    private boolean ownsMallocBuffer() {
        return type == null || type == OperationType.READ || type == OperationType.RECV;
    }

    public void freeBuffer() {
        if (!ownsMallocBuffer()) {
            bufferAddress = 0L;
            buffer = null;
            return;
        }
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
            if (type == null || type == OperationType.READV) {
                for (MemorySegment b : buffers) {
                    if (b != null && !b.equals(MemorySegment.NULL)) {
                        NativeDispatcher.C.free(b);
                    }
                }
            }
            buffers = null;
        }
    }

    @Override
    public void close() {
        freeBuffer();
        freeBuffers();
    }

    @Override
    public String toString() {
        return "Result{" +
                "id=" + id +
                ", result=" + result +
                ", type=" + type +
                ", bufferIdx=" + bufferIdx +
                ", cqeFlags=" + cqeFlags +
                '}';
    }
}
