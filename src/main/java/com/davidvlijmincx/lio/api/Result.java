package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Unified, mutable result class for {@code io_uring} completion events.
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
     * Returns true if more completion queue entries will be produced by this operation.
     *
     * @return true if more completions are expected
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
     * Returns a {@link MemorySegment} sliced to the exact number of bytes transferred.
     *
     * @return memory segment of transferred bytes
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
     * Copies the transferred bytes into a new byte array.
     *
     * @return byte array containing transferred bytes
     */
    public byte[] toByteArray() {
        MemorySegment valid = validBuffer();
        if (valid.equals(MemorySegment.NULL)) {
            return new byte[0];
        }
        return valid.toArray(JAVA_BYTE);
    }

    /**
     * Decodes the transferred bytes as a UTF-8 string.
     *
     * @return decoded string
     */
    public String getString() {
        return getString(StandardCharsets.UTF_8);
    }

    /**
     * Decodes the transferred bytes using the specified charset.
     *
     * @param charset character set for decoding
     * @return decoded string
     */
    public String getString(Charset charset) {
        return new String(toByteArray(), charset);
    }

    /**
     * Wraps the transferred bytes as a direct ByteBuffer.
     *
     * @return byte buffer containing transferred bytes
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
