package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Result of an {@code io_uring} completion event.
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

    /**
     * Creates an empty result.
     *
     * <p>Reaping loops reuse one instance per ring and application code normally receives
     * results from {@link JUring} instead of constructing them.
     */
    public Result() {}

    Result(long id, long result, OperationType type, long bufferAddress, MemorySegment buffer, int bufferIdx, MemorySegment[] buffers) {
        set(id, result, type, bufferAddress, buffer, bufferIdx, buffers, 0);
    }

    /**
     * Creates a copy of another result, including its buffer references; the native memory is
     * not copied.
     *
     * @param other result to copy
     */
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

    void set(long id, long result, OperationType type, long bufferAddress, MemorySegment buffer, int bufferIdx, MemorySegment[] buffers) {
        set(id, result, type, bufferAddress, buffer, bufferIdx, buffers, 0);
    }

    void set(long id, long result, OperationType type, long bufferAddress, MemorySegment buffer, int bufferIdx, MemorySegment[] buffers, int cqeFlags) {
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

    /**
     * Returns the operation handle ({@code user_data}) that this completion belongs to; the
     * handle equals the value returned when the operation was prepared.
     *
     * @return the operation handle
     */
    public long id() {
        return id;
    }

    /**
     * Returns the raw io_uring completion result.
     *
     * <p>For reads and writes this is the transferred byte count, for an {@code OPEN} it is
     * the new file descriptor, for operations without a payload it is {@code 0}, and a
     * failure is reported as the negative errno ({@code -errno}).
     *
     * @return the raw completion result
     */
    public long result() {
        return result;
    }

    /**
     * Returns the operation type decoded from the completion's {@code user_data}.
     *
     * @return the operation type
     */
    public OperationType type() {
        return type;
    }

    /**
     * Tests whether the operation completed successfully.
     *
     * @return {@code true} when {@link #result()} is not negative
     */
    public boolean isSuccess() {
        return result >= 0;
    }

    /**
     * Returns the raw io_uring completion flags, for example {@code IORING_CQE_F_BUFFER} for
     * a read that selected a provided buffer.
     *
     * @return the raw completion flags
     */
    public int cqeFlags() {
        return cqeFlags;
    }

    /**
     * Returns the native address of the buffer associated with the completion.
     *
     * <p>For registered fixed-buffer operations this is the address of the registered
     * buffer, for buffer-allocating reads it is the address of the ring-allocated buffer,
     * and for operations without a buffer it is {@code 0}.
     *
     * @return the buffer address, or {@code 0} when the operation has no buffer
     */
    public long bufferAddress() {
        return bufferAddress;
    }

    /**
     * Returns the buffer associated with the completion as a memory segment.
     *
     * <p>For a {@code READ_FIXED} completion this is the registered buffer selected by
     * {@link #bufferIdx()}; for a buffer-allocating read it is a segment that reinterprets
     * {@link #bufferAddress()} with the byte count reported by {@link #result()}. A buffer
     * supplied by the caller at preparation time is not retained in the result.
     *
     * @return the buffer, or {@link MemorySegment#NULL} when the operation has no buffer
     */
    public MemorySegment buffer() {
        if (buffer != null) {
            return buffer;
        }
        if (type == OperationType.READ_FIXED && registeredBuffers != null && bufferIdx >= 0 && bufferIdx < registeredBuffers.length) {
            buffer = registeredBuffers[bufferIdx];
            return buffer;
        }
        if (bufferAddress != 0L) {
            long len = result > 0 ? result : 0L;
            buffer = MemorySegment.ofAddress(bufferAddress).reinterpret(len);
            return buffer;
        }
        return MemorySegment.NULL;
    }

    /**
     * Returns the part of {@link #buffer()} that holds the bytes produced by the operation.
     *
     * @return the valid byte range, or {@link MemorySegment#NULL} when there is no buffer or
     *         the completion result is not positive
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
     * Copies the valid bytes of the completion into a new heap array.
     *
     * @return a new byte array containing the operation's bytes, empty when there are none
     */
    public byte[] toByteArray() {
        MemorySegment valid = validBuffer();
        if (valid.equals(MemorySegment.NULL)) {
            return new byte[0];
        }
        return valid.toArray(JAVA_BYTE);
    }

    /**
     * Decodes the valid bytes of the completion as UTF-8 text.
     *
     * @return the decoded text, empty when there are no bytes
     */
    public String getString() {
        return getString(StandardCharsets.UTF_8);
    }

    /**
     * Decodes the valid bytes of the completion with the given charset.
     *
     * @param charset charset used to decode the bytes
     * @return the decoded text, empty when there are no bytes
     */
    public String getString(Charset charset) {
        return new String(toByteArray(), charset);
    }

    /**
     * Wraps the valid bytes of the completion in a byte buffer.
     *
     * @return a byte buffer over the valid bytes, empty when there are none
     */
    public ByteBuffer asByteBuffer() {
        MemorySegment valid = validBuffer();
        return valid.equals(MemorySegment.NULL) ? ByteBuffer.allocate(0) : valid.asByteBuffer();
    }

    /**
     * Returns the index of the fixed or pooled buffer selected for the operation.
     *
     * @return the buffer index, or {@code -1} when the operation used no indexed buffer
     */
    public int bufferIdx() {
        return bufferIdx;
    }

    /**
     * Returns the completion result cast to a file descriptor, for a successful
     * {@code OPEN} operation.
     *
     * @return the newly opened file descriptor
     */
    public int fd() {
        return (int) result;
    }

    /**
     * Wraps the file descriptor of a successful {@code OPEN} completion in a
     * {@link FileDescriptor}.
     *
     * <p>The returned object owns the descriptor; closing it closes the underlying file.
     *
     * @return a file descriptor for the newly opened file
     */
    public FileDescriptor fileDescriptor() {
        return new FileDescriptor((int) result);
    }

    /**
     * Returns the per-iovec segments produced by a vectored read.
     *
     * @return the iovec segments, or {@code null} for non-vectored operations
     */
    public MemorySegment[] buffers() {
        return buffers;
    }

    /**
     * Returns the number of bytes transferred by a read or write.
     *
     * @return the byte count reported by the kernel (the raw completion result)
     */
    public long bytesRead() {
        return result;
    }

    private boolean ownsMallocBuffer() {
        return type == null || type == OperationType.READ;
    }

    /**
     * Frees the native buffer owned by this result, if any.
     *
     * <p>Only plain {@code READ} results own a heap-allocated native buffer; for fixed,
     * pooled and other operation types the memory belongs to the ring or to the registered
     * buffers and is not freed, the result only drops its reference.
     */
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

    /**
     * Frees the native buffers owned by a {@code READV} result, if any.
     *
     * <p>The segment array is released for every operation type; the native memory is freed
     * only for plain {@code READV} results, whose buffers are heap-allocated by the ring.
     */
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

    /**
     * Releases the native memory owned by this result; equivalent to {@link #freeBuffer()}
     * followed by {@link #freeBuffers()}.
     */
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
