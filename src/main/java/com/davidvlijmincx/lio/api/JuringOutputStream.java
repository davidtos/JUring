package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Buffered, write-behind output stream over a {@link JuringFile}.
 *
 * <p>This is the B2 L1 stream layer (see {@code SEASTAR-PLAN.md} §4 B2 and
 * {@code docs/b2-design.md}): a byte stream on top of the asynchronous file handle with a
 * configurable buffer size, configurable write-behind depth, complete-until-done writes,
 * alignment padding for O_DIRECT files, an optional preallocation hint and
 * {@link #flush()}, backed by a fixed-size buffer set (bounded memory).
 *
 * <p>Typical usage:
 * <pre>{@code
 * try (JuringOutputStream out = JuringOutputStream.open(file,
 *         OutputStreamOptions.defaults().withBufferSize(128 * 1024).withWriteBehind(8))) {
 *     out.write(chunk);
 *     out.flush();
 * }
 * }</pre>
 *
 * <h2>Write position</h2>
 * <p>The stream writes from file offset 0 and never seeks: every accepted byte advances
 * the logical position. It is intended for a file the stream owns (like seastar's
 * {@code make_file_output_stream}). Internal writes are alignment-aligned; the final
 * partial buffer is zero-padded to alignment and {@link #flush()}/{@link #close()} then
 * truncate the file back to the logical position, so no padding bytes are visible.
 *
 * <h2>Bounded memory</h2>
 * <p>The stream allocates exactly {@code writeBehind + 1} native buffers of
 * {@code alignUp(bufferSize, alignment)} bytes each, once, at {@code open()}.
 * {@link #bufferMemoryBytes()} reports the total.
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #close()} flushes, optionally calls {@code fdatasync}, releases the buffers and
 * is idempotent. It does <strong>not</strong> close the underlying {@link JuringFile}.
 * Writing after {@code close()} throws {@link IllegalStateException}. The stream is
 * <strong>not thread-safe</strong> and inherits the B1 ring-ownership rule.
 */
public final class JuringOutputStream implements AutoCloseable {

    /** Default deadline used when draining in-flight writes at {@link #close()}. */
    public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;

    private static final int MAX_SHORT_WRITE_RETRIES = 8;

    private final JuringFile file;
    private final OutputStreamOptions options;
    private final Arena arena;

    private final int alignment;
    private final int chunkSize;        // alignUp(options.bufferSize(), alignment)
    private final int writeBehind;
    private final int bufferCount;      // writeBehind + 1

    private final MemorySegment[] buffers;
    private final int[] freeBuffers;
    private int freeBufferTop;

    // Write-behind FIFO of buffers. Each entry is driven to completion by drainOldest().
    private final int[] outstandingBuffer;
    private final long[] outstandingOffset;
    private final long[] outstandingHandle;
    private int outstandingHead;
    private int outstandingSize;

    private int fillBuffer = -1;
    private int fillPos;
    private long position;
    private long flushOffset;
    private boolean closed;
    private boolean unalignedRestart;
    private JuringException failure;

    private JuringOutputStream(JuringFile file, OutputStreamOptions options) {
        this.file = file;
        this.options = options;
        this.alignment = options.alignment();
        this.chunkSize = alignUp(options.bufferSize(), this.alignment);
        this.writeBehind = options.writeBehind();
        this.bufferCount = writeBehind + 1;
        this.arena = Arena.ofConfined();
        try {
            this.buffers = new MemorySegment[bufferCount];
            for (int i = 0; i < bufferCount; i++) {
                buffers[i] = arena.allocate(chunkSize, this.alignment);
            }
            this.freeBuffers = new int[bufferCount];
            for (int i = 0; i < bufferCount; i++) {
                freeBuffers[i] = i;
            }
            this.freeBufferTop = bufferCount;
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }
        this.outstandingBuffer = new int[bufferCount];
        this.outstandingOffset = new long[bufferCount];
        this.outstandingHandle = new long[bufferCount];
    }

    // ------------------------------------------------------------------
    // factories
    // ------------------------------------------------------------------

    /**
     * Opens an output stream that writes the file from offset 0 with default options.
     *
     * @param file the backing file (must be opened for writing)
     * @return a new output stream
     * @throws JuringException if the optional preallocation hint fails
     */
    public static JuringOutputStream open(JuringFile file) throws JuringException {
        return open(file, OutputStreamOptions.defaults());
    }

    /**
     * Opens an output stream that writes the file from offset 0.
     *
     * @param file    the backing file (must be opened for writing)
     * @param options stream options
     * @return a new output stream
     * @throws JuringException if the optional preallocation hint fails
     */
    public static JuringOutputStream open(JuringFile file, OutputStreamOptions options) throws JuringException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        if (!file.isOpen()) {
            throw new IllegalStateException("JuringOutputStream.open: file is closed (" + file.path() + ")");
        }
        JuringOutputStream stream = new JuringOutputStream(file, options);
        if (options.preallocationSize() > 0) {
            try {
                file.allocate(0L, options.preallocationSize());
            } catch (JuringException | RuntimeException e) {
                stream.closeQuietly();
                throw e;
            }
        }
        return stream;
    }

    // ------------------------------------------------------------------
    // accessors
    // ------------------------------------------------------------------

    /** @return the backing file */
    public JuringFile file() {
        return file;
    }

    /** @return the configured logical buffer size in bytes */
    public int bufferSize() {
        return options.bufferSize();
    }

    /** @return the configured write-behind depth */
    public int writeBehind() {
        return writeBehind;
    }

    /** @return number of bytes of native buffer memory held by this stream (bounded) */
    public long bufferMemoryBytes() {
        return closed ? 0L : (long) bufferCount * chunkSize;
    }

    /** @return the logical number of bytes accepted and written by this stream */
    public long position() {
        return position;
    }

    /** @return the logical number of bytes accepted and written by this stream */
    public long bytesWritten() {
        return position;
    }

    // ------------------------------------------------------------------
    // writing
    // ------------------------------------------------------------------

    /**
     * Writes all bytes of {@code source} (complete-until-done). The data is copied into the
     * internal buffers; write-behind operations may still be in flight when this returns
     * ({@link #flush()} waits for them).
     *
     * @param source native segment containing the bytes to write
     * @throws JuringException if an I/O error occurred (the first failure is latched)
     */
    public void write(MemorySegment source) throws JuringException {
        Objects.requireNonNull(source, "source");
        ensureWritable();
        JUring.validateNativeSegment(source, false);
        long size = source.byteSize();
        long written = 0;
        while (written < size) {
            if (fillBuffer < 0) {
                acquireFillBuffer();
                fillPos = 0;
            }
            int space = chunkSize - fillPos;
            int copy = (int) Math.min(space, size - written);
            MemorySegment.copy(source, written, buffers[fillBuffer], fillPos, copy);
            fillPos += copy;
            written += copy;
            position += copy;
            if (fillPos == chunkSize) {
                submitFillBuffer();
            }
        }
    }

    /**
     * Writes all bytes of {@code source[offset..offset+length)} (complete-until-done).
     *
     * @param source source array
     * @param offset first index to write
     * @param length number of bytes to write
     * @throws JuringException if an I/O error occurred
     */
    public void write(byte[] source, int offset, int length) throws JuringException {
        Objects.requireNonNull(source, "source");
        Objects.checkFromIndexSize(offset, length, source.length);
        ensureWritable();
        if (length == 0) {
            return;
        }
        int written = 0;
        while (written < length) {
            if (fillBuffer < 0) {
                acquireFillBuffer();
                fillPos = 0;
            }
            int space = chunkSize - fillPos;
            int copy = Math.min(space, length - written);
            MemorySegment.copy(source, offset + written, buffers[fillBuffer], JAVA_BYTE, fillPos, copy);
            fillPos += copy;
            written += copy;
            position += copy;
            if (fillPos == chunkSize) {
                submitFillBuffer();
            }
        }
    }

    /**
     * Flushes the pending partial buffer (alignment-padded) and waits for all in-flight
     * write-behind operations. Does not {@code fsync} (that only happens on
     * {@link #close()} when {@code syncOnClose} is set).
     *
     * <p>Continuing to write after a flush is supported when the logical position is
     * alignment-aligned (the usual case after full buffers); after a padded flush at an
     * unaligned position further writes throw {@link IllegalStateException} because an
     * O_DIRECT stream cannot restart at an unaligned offset.
     *
     * @throws JuringException if an I/O error occurred (latched)
     */
    public void flush() throws JuringException {
        ensureOpen();
        if (failure != null) {
            throw failure;
        }
        flushInternal();
    }

    private void flushInternal() throws JuringException {
        if (fillBuffer >= 0 && fillPos > 0) {
            int paddedFrom = fillPos;
            for (int i = paddedFrom; i < chunkSize; i++) {
                buffers[fillBuffer].set(JAVA_BYTE, i, (byte) 0);
            }
            int buffer = fillBuffer;
            long offset = flushOffset;
            fillBuffer = -1;
            fillPos = 0;
            enqueueWrite(buffer, offset);
            drainAll();
            // Undo the alignment padding so the file ends exactly at position().
            try {
                file.truncate(position);
            } catch (JuringException e) {
                failure = e;
                unalignedRestart = true;
                throw e;
            }
            flushOffset = position;
            if ((position & (alignment - 1L)) != 0) {
                unalignedRestart = true;
            }
        } else {
            drainAll();
        }
    }

    /**
     * Flushes, optionally {@code fdatasync}s, releases the buffers and marks the stream
     * closed. Idempotent; does not close the backing {@link JuringFile}.
     *
     * <p>If a write failed, the first failure is rethrown after the stream has been closed
     * and its buffers released. If an in-flight operation could not be drained (ring
     * ownership misuse), the buffers are intentionally <em>not</em> freed: they stay alive
     * (bounded, one stream's set) because the kernel may still reference them.
     *
     * @throws JuringException if a write or the final flush failed (latched)
     */
    @Override
    public void close() throws JuringException {
        if (closed) {
            return;
        }
        closed = true;
        JuringException first = failure;
        boolean safeToRelease = true;

        // Hand the pending partial buffer to the write-behind pipeline first.
        boolean padded = false;
        int partialBuffer = -1;
        long partialOffset = -1L;
        if (fillBuffer >= 0 && fillPos > 0) {
            int paddedFrom = fillPos;
            for (int i = paddedFrom; i < chunkSize; i++) {
                buffers[fillBuffer].set(JAVA_BYTE, i, (byte) 0);
            }
            partialBuffer = fillBuffer;
            partialOffset = flushOffset;
            fillBuffer = -1;
            fillPos = 0;
            padded = true;
        }
        // Drain what is already in flight. drainOldest() pops an entry even when its
        // completion failed, so keep draining the rest while recording the first failure.
        try {
            drainAll();
        } catch (JuringException e) {
            if (first == null) {
                first = e;
            }
        } catch (RuntimeException e) {
            safeToRelease = false;
            if (first == null) {
                first = new JuringException(0, "close",
                        "could not drain output stream write: " + e.getMessage(), e);
            }
        }
        while (safeToRelease && outstandingSize > 0) {
            try {
                drainOldest();
            } catch (JuringException e) {
                if (first == null) {
                    first = e;
                }
            } catch (RuntimeException e) {
                safeToRelease = false;
                if (first == null) {
                    first = new JuringException(0, "close",
                            "could not drain output stream write: " + e.getMessage(), e);
                }
            }
        }
        if (padded && safeToRelease) {
            try {
                enqueueWrite(partialBuffer, partialOffset);
                drainAll();
            } catch (JuringException e) {
                if (first == null) {
                    first = e;
                }
            } catch (RuntimeException e) {
                safeToRelease = false;
                if (first == null) {
                    first = new JuringException(0, "close",
                            "could not write the final partial buffer: " + e.getMessage(), e);
                }
            }
        }
        if (padded && safeToRelease) {
            try {
                file.truncate(position);
            } catch (JuringException e) {
                if (first == null) {
                    first = e;
                }
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (options.syncOnClose()) {
            try {
                file.fdatasync();
            } catch (JuringException e) {
                if (first == null) {
                    first = e;
                }
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (safeToRelease) {
            releaseBuffers();
            arena.close();
        }
        if (first != null) {
            throw first;
        }
    }

    // ------------------------------------------------------------------
    // write-behind pipeline
    // ------------------------------------------------------------------

    private void acquireFillBuffer() throws JuringException {
        if (freeBufferTop == 0) {
            drainOldest();
        }
        if (freeBufferTop == 0) {
            throw new JuringException(0, "write", "no free write buffer (write-behind window exhausted)");
        }
        fillBuffer = freeBuffers[--freeBufferTop];
    }

    private void submitFillBuffer() throws JuringException {
        int buffer = fillBuffer;
        long offset = flushOffset;
        fillBuffer = -1;
        fillPos = 0;
        enqueueWrite(buffer, offset);
    }

    private void enqueueWrite(int buffer, long offset) throws JuringException {
        if (outstandingSize == writeBehind) {
            drainOldest();
        }
        int slot = (outstandingHead + outstandingSize) % bufferCount;
        long handle;
        try {
            handle = file.prepareWrite(offset, buffers[buffer]);
        } catch (IllegalStateException sqFull) {
            file.ring().submit();
            handle = file.prepareWrite(offset, buffers[buffer]);
        }
        file.ring().submit();
        outstandingBuffer[slot] = buffer;
        outstandingOffset[slot] = offset;
        outstandingHandle[slot] = handle;
        outstandingSize++;
        flushOffset = offset + chunkSize;
    }

    /**
     * Drives the oldest write-behind entry to completion. On a short write the remainder is
     * re-issued from the last alignment boundary (rewriting aligned bytes is idempotent).
     * On a completion error the buffer is released (the operation is finished) and the
     * failure is latched and thrown.
     */
    private void drainOldest() throws JuringException {
        if (outstandingSize == 0) {
            return;
        }
        int slot = outstandingHead;
        int buffer = outstandingBuffer[slot];
        long offset = outstandingOffset[slot];
        int written = 0;
        long handle = outstandingHandle[slot];
        int retries = 0;
        JuringException error = null;
        while (true) {
            int result;
            try {
                result = file.await(handle);
            } catch (JuringException e) {
                error = e;
                break;
            } catch (RuntimeException e) {
                // Ring ownership misuse: the operation may still be in flight, so neither
                // the buffer nor the stream arena may be released. Rethrow as-is.
                throw e;
            }
            if (result < 0) {
                error = new JuringException(-result, "write",
                        "short write result " + result + " at offset " + (offset + written));
                break;
            }
            written += result;
            if (written >= chunkSize) {
                break;
            }
            int aligned = alignDown(written, alignment);
            if (aligned <= 0 || retries++ >= MAX_SHORT_WRITE_RETRIES) {
                error = new JuringException(0, "write",
                        "write made no aligned progress at offset " + (offset + written)
                                + " (" + written + "/" + chunkSize + " bytes of the buffer written)");
                break;
            }
            written = aligned;
            try {
                handle = file.prepareWrite(offset + written,
                        buffers[buffer].asSlice(written, chunkSize - written));
            } catch (IllegalStateException sqFull) {
                file.ring().submit();
                handle = file.prepareWrite(offset + written,
                        buffers[buffer].asSlice(written, chunkSize - written));
            }
            file.ring().submit();
        }
        freeBuffers[freeBufferTop++] = buffer;
        outstandingHead = (outstandingHead + 1) % bufferCount;
        outstandingSize--;
        if (error != null) {
            failure = error;
            throw error;
        }
    }

    private void drainAll() throws JuringException {
        while (outstandingSize > 0) {
            drainOldest();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void ensureWritable() throws JuringException {
        if (closed) {
            throw new IllegalStateException("JuringOutputStream: stream is closed (" + file.path() + ")");
        }
        if (unalignedRestart) {
            throw new IllegalStateException("JuringOutputStream: cannot continue writing after a "
                    + "padded flush at unaligned position " + position + " (" + file.path()
                    + "); keep the logical position alignment-aligned or use a new stream");
        }
        if (!file.isOpen()) {
            throw new IllegalStateException("JuringOutputStream: backing file is closed (" + file.path() + ")");
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JuringOutputStream: stream is closed (" + file.path() + ")");
        }
        if (!file.isOpen()) {
            throw new IllegalStateException("JuringOutputStream: backing file is closed (" + file.path() + ")");
        }
    }

    private void releaseBuffers() {
        fillBuffer = -1;
        fillPos = 0;
        for (int i = 0; i < outstandingSize; i++) {
            int slot = (outstandingHead + i) % bufferCount;
            freeBuffers[freeBufferTop++] = outstandingBuffer[slot];
        }
        outstandingHead = 0;
        outstandingSize = 0;
    }

    private void closeQuietly() {
        try {
            close();
        } catch (RuntimeException | JuringException ignored) {
            // Construction failure: best-effort teardown only.
        }
    }

    private static int alignUp(long value, int alignment) {
        long mask = alignment - 1L;
        long aligned = (value + mask) & ~mask;
        if (aligned > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("aligned value too large: " + value);
        }
        return (int) aligned;
    }

    private static int alignDown(long value, int alignment) {
        return (int) (value & ~(alignment - 1L));
    }

    @Override
    public String toString() {
        return "JuringOutputStream{file=" + file.path()
                + ", position=" + position
                + ", bufferSize=" + options.bufferSize()
                + ", writeBehind=" + writeBehind
                + ", inFlight=" + outstandingSize
                + (closed ? ", closed" : "") + '}';
    }
}
