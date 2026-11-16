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
 * {@link #bufferMemoryBytes()} reports the total. Write-behind never exceeds that buffer
 * set: when the configured window is full, {@link #write(MemorySegment)} waits for the
 * oldest in-flight write before reusing its buffer, and short writes are retried from the
 * last completed byte.
 *
 * <h2>Durability (B6)</h2>
 * <p>{@link OutputStreamOptions#durability()} makes {@link #flush()} and {@link #close()}
 * durability points (see {@code docs/b6-design.md} §2.5/§3.1): for
 * {@link DurabilityMode#FDATASYNC}/{@link DurabilityMode#FSYNC} each point issues exactly
 * one {@code fdatasync}/{@code fsync}; {@link DurabilityMode#WRITEBACK} keeps the frozen B2
 * behaviour (no sync in {@code flush()}, one {@code fdatasync} in {@code close()} iff
 * {@link OutputStreamOptions#syncOnClose()}); {@link DurabilityMode#DSYNC} never syncs
 * explicitly because it asserts the file was opened with {@code O_DSYNC}.
 * {@link #syncs()} reports the successful sync operations issued so far.
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #close()} performs one flush (hence at most one mode sync), optionally calls
 * the B2 {@code fdatasync}-on-close, releases the buffers and is idempotent. Drain, final
 * truncate and durability sync all share one hard deadline; a write that cannot be drained
 * in time is abandoned with its buffer kept alive (a bounded leak, never a use-after-free)
 * and counted by {@link #abandonedAtClose()}. It does <strong>not</strong> close the
 * underlying {@link JuringFile}. Writing after {@code close()} throws
 * {@link IllegalStateException}.
 * The stream is <strong>not thread-safe</strong> and inherits the B10a ring-ownership rule:
 * it may share its {@link JUring} with sibling handles on the same thread; only raw
 * un-reaped ring work is rejected.
 */
public final class JuringOutputStream implements AutoCloseable {

    /** Default deadline used when draining in-flight writes at {@link #close()}. */
    public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;

    private static final int MAX_SHORT_WRITE_RETRIES = 8;

    /** Fixed grace on top of the close deadline before an in-flight write is abandoned. */
    private static final long CLOSE_GRACE_NANOS = 250_000_000L;

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

    private final DurabilityMode durability;

    private int fillBuffer = -1;
    private int fillPos;
    private long position;
    private long flushOffset;
    private long syncs;
    private boolean closed;
    private boolean unalignedRestart;
    private JuringException failure;
    /** Hard deadline for the current bounded {@link #close()}, or {@code -1} outside close. */
    private long boundedCloseDeadlineNanos = -1L;
    /** Writes abandoned by {@link #close()} because the bounded deadline expired. */
    private long abandonedAtClose;

    private JuringOutputStream(JuringFile file, OutputStreamOptions options) {
        this.file = file;
        this.options = options;
        this.alignment = options.alignment();
        this.chunkSize = alignUp(options.bufferSize(), this.alignment);
        this.writeBehind = options.writeBehind();
        this.bufferCount = writeBehind + 1;
        this.durability = options.durability();
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
     * <p>B6 validation and hints (see {@code docs/b6-design.md} §2.6): a
     * {@link DurabilityMode#DSYNC} request asserts that the backing file was opened with
     * {@code O_DSYNC} ({@link LinuxOpenOptions#DSYNC}/{@link LinuxOpenOptions#SYNC}) and
     * fails with {@link IllegalArgumentException} <em>before</em> any write is accepted;
     * the optional preallocation hint is issued next, then the optional
     * {@link OutputStreamOptions#fadvise()} hint for the whole file. Hint failures
     * propagate out of this method.
     *
     * @param file    the backing file (must be opened for writing)
     * @param options stream options
     * @return a new output stream
     * @throws IllegalArgumentException if {@code durability() == DSYNC} but the file was not
     *                                  opened with {@code O_DSYNC}
     * @throws JuringException if the optional preallocation or {@code fadvise} hint fails
     */
    public static JuringOutputStream open(JuringFile file, OutputStreamOptions options) throws JuringException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        if (!file.isOpen()) {
            throw new IllegalStateException("JuringOutputStream.open: file is closed (" + file.path() + ")");
        }
        if (options.durability() == DurabilityMode.DSYNC && file.durability() != DurabilityMode.DSYNC) {
            throw new IllegalArgumentException("JuringOutputStream.open: durability mode DSYNC requires "
                    + "the file to be opened with O_DSYNC (LinuxOpenOptions.DSYNC or SYNC): options.durability()="
                    + options.durability() + ", file.durability()=" + file.durability() + " (" + file.path() + ")");
        }
        JuringOutputStream stream = new JuringOutputStream(file, options);
        try {
            if (options.preallocationSize() > 0) {
                file.allocate(0L, options.preallocationSize());
            }
            if (options.fadvise() != FadviseAdvice.NORMAL) {
                file.fadvise(options.fadvise(), 0L, 0L);
            }
        } catch (JuringException | RuntimeException e) {
            stream.closeQuietly();
            throw e;
        }
        return stream;
    }

    // ------------------------------------------------------------------
    // accessors
    // ------------------------------------------------------------------

    /**
     * Returns the backing file.
     *
     * @return the backing file (never closed by this stream)
     */
    public JuringFile file() {
        return file;
    }

    /**
     * Returns the configured logical buffer size in bytes.
     *
     * @return the configured logical buffer size
     */
    public int bufferSize() {
        return options.bufferSize();
    }

    /**
     * Returns the configured write-behind depth.
     *
     * @return the configured write-behind depth
     */
    public int writeBehind() {
        return writeBehind;
    }

    /**
     * Returns the number of bytes of native buffer memory held by this stream.
     *
     * @return the bounded buffer memory in bytes ({@code 0} after a normal {@link #close()};
     *         the retained size after an abandoned close, where the buffers stay alive)
     */
    public long bufferMemoryBytes() {
        return (!closed || abandonedAtClose > 0) ? (long) bufferCount * chunkSize : 0L;
    }

    /**
     * Returns the configured durability mode (the echo of
     * {@link OutputStreamOptions#durability()}).
     *
     * @return the configured durability mode, readable after {@link #close()}
     */
    public DurabilityMode durability() {
        return durability;
    }

    /**
     * Number of durability sync operations ({@code fdatasync}/{@code fsync}) this stream has
     * successfully issued so far (B6, see {@code docs/b6-design.md} §3.1).
     *
     * <p>It is {@code 0} for {@link DurabilityMode#WRITEBACK} (without
     * {@link OutputStreamOptions#syncOnClose()}) and for {@link DurabilityMode#DSYNC}; it
     * increments by one per {@code flush()} for {@link DurabilityMode#FDATASYNC}/
     * {@link DurabilityMode#FSYNC} and by (at most) one on {@code close()}. The value is
     * valid after {@link #close()} and is never reset.
     *
     * @return successful sync operations issued by this stream
     */
    public long syncs() {
        return syncs;
    }

    /**
     * Returns the logical number of bytes accepted and written by this stream.
     *
     * @return the logical write position
     */
    public long position() {
        return position;
    }

    /**
     * Returns the logical number of bytes accepted and written by this stream (the same
     * value as {@link #position()}).
     *
     * @return the number of bytes written
     */
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
     * Flushes the pending partial buffer (alignment-padded), waits for all in-flight
     * write-behind operations and, when the durability mode is
     * {@link DurabilityMode#FDATASYNC}/{@link DurabilityMode#FSYNC}, issues exactly one
     * {@code fdatasync}/{@code fsync} before returning (a B6 durability point). For
     * {@link DurabilityMode#WRITEBACK} (and {@link DurabilityMode#DSYNC}) no sync is issued
     * here; {@code WRITEBACK} optionally syncs once on {@link #close()} when
     * {@link OutputStreamOptions#syncOnClose()} is set.
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
        syncAtDurabilityPoint();
    }

    /**
     * Issued after the drain/truncate step of a flush so the mode sync is ordered after every
     * write accepted before the durability point. No-op for {@link DurabilityMode#WRITEBACK}
     * and {@link DurabilityMode#DSYNC}.
     */
    private void syncAtDurabilityPoint() throws JuringException {
        if (durability == DurabilityMode.FDATASYNC) {
            issueSync(false);
        } else if (durability == DurabilityMode.FSYNC) {
            issueSync(true);
        }
    }

    /** Issues one {@code fdatasync}/{@code fsync}, latching the first sync failure (B2 rule). */
    private void issueSync(boolean metadata) throws JuringException {
        try {
            if (metadata) {
                file.fsync();
            } else {
                file.fdatasync();
            }
        } catch (JuringException e) {
            if (failure == null) {
                failure = e;
            }
            throw e;
        }
        syncs++;
    }

    /**
     * Flushes, optionally {@code fdatasync}s, releases the buffers and marks the stream
     * closed. Idempotent; does not close the backing {@link JuringFile}.
     *
     * <p>The close is hard-bounded: draining in-flight writes, the final truncate and the
     * durability sync all share one deadline of {@link #DEFAULT_CLOSE_TIMEOUT_MILLIS} ms
     * (+250 ms grace) and never fall back to an unbounded wait. A write that has not completed
     * by the hard deadline is abandoned: its buffer is kept alive (a bounded leak, never a
     * use-after-free), the request is left for the backing {@link JuringFile}/{@link JUring}
     * shutdown to cancel or drop, this method throws a {@link JuringException} with
     * {@code ETIMEDOUT}, and the truncate/sync and buffer release are skipped. Abandoned
     * writes are counted by {@link #abandonedAtClose()}. If the writes drained but the final
     * ftruncate/sync itself misses the deadline, close also throws {@code ETIMEDOUT}, but the
     * stream buffers are released (no leak).
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
        boolean abandoned = false;
        boolean deadlineExpired = false;

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
        boolean paddedEnqueued = false;
        long hardDeadline = System.nanoTime()
                + DEFAULT_CLOSE_TIMEOUT_MILLIS * 1_000_000L + CLOSE_GRACE_NANOS;
        boundedCloseDeadlineNanos = hardDeadline;
        try {
            // Drain what is already in flight. drainOldest() pops an entry even when its
            // completion failed, so keep draining the rest while recording the first failure.
            try {
                drainAll();
            } catch (JuringException e) {
                if (e.isTimedOut()) {
                    abandoned = true;
                    safeToRelease = false;
                    if (first == null) {
                        first = e;
                    }
                } else if (first == null) {
                    first = e;
                }
            } catch (RuntimeException e) {
                abandoned = true;
                safeToRelease = false;
                if (first == null) {
                    first = new JuringException(0, "close",
                            "could not drain output stream write: " + e.getMessage(), e);
                }
            }
            while (!abandoned && safeToRelease && outstandingSize > 0) {
                try {
                    drainOldest();
                } catch (JuringException e) {
                    if (e.isTimedOut()) {
                        abandoned = true;
                        safeToRelease = false;
                    }
                    if (first == null) {
                        first = e;
                    }
                } catch (RuntimeException e) {
                    abandoned = true;
                    safeToRelease = false;
                    if (first == null) {
                        first = new JuringException(0, "close",
                                "could not drain output stream write: " + e.getMessage(), e);
                    }
                }
            }
            if (padded && safeToRelease && !abandoned) {
                try {
                    enqueueWrite(partialBuffer, partialOffset);
                    paddedEnqueued = true;
                    drainAll();
                } catch (JuringException e) {
                    if (e.isTimedOut()) {
                        abandoned = true;
                        safeToRelease = false;
                    }
                    if (first == null) {
                        first = e;
                    }
                } catch (RuntimeException e) {
                    abandoned = true;
                    safeToRelease = false;
                    if (first == null) {
                        first = new JuringException(0, "close",
                                "could not write the final partial buffer: " + e.getMessage(), e);
                    }
                }
            }
            if (padded && safeToRelease && !abandoned && !deadlineExpired) {
                try {
                    file.truncateBounded(position, hardDeadline);
                } catch (JuringException e) {
                    if (e.isTimedOut()) {
                        deadlineExpired = true;
                    }
                    if (first == null) {
                        first = e;
                    }
                    if (failure == null) {
                        failure = e;
                    }
                }
            }
            // Exactly one close durability point (B6 §3.2): FDATASYNC/FSYNC issue one mode sync
            // (syncOnClose is then redundant and never adds a second one), DSYNC none, and
            // WRITEBACK keeps the frozen B2 fdatasync-on-close shortcut.
            boolean closeSync = false;
            if (durability == DurabilityMode.FDATASYNC) {
                closeSync = true;
            } else if (durability == DurabilityMode.FSYNC) {
                closeSync = true;
            } else if (durability == DurabilityMode.WRITEBACK && options.syncOnClose()) {
                closeSync = true;
            }
            if (closeSync && safeToRelease && !abandoned && !deadlineExpired) {
                try {
                    file.syncBounded(durability == DurabilityMode.FSYNC, hardDeadline);
                    syncs++;
                } catch (JuringException e) {
                    if (e.isTimedOut()) {
                        deadlineExpired = true;
                    }
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
        } finally {
            boundedCloseDeadlineNanos = -1L;
            if (abandoned) {
                // Un-drained writes keep their buffers; a partial buffer that never reached
                // the write-behind FIFO counts as one more abandoned buffer.
                abandonedAtClose = outstandingSize + ((padded && !paddedEnqueued) ? 1 : 0);
            }
        }
    }

    /**
     * Number of writes abandoned by {@link #close()} because the bounded close deadline
     * expired (or because ring-ownership misuse left the write un-reapable). Non-zero only
     * after an abnormal close that retains buffers; they are intentionally not freed (a
     * bounded leak is safer than a use-after-free).
     *
     * @return retained/abandoned write-buffer count ({@code 0} for a normal close)
     */
    public long abandonedAtClose() {
        return abandonedAtClose;
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
     * failure is latched and thrown. During a bounded {@link #close()}, a deadline expiry is
     * rethrown <em>without</em> releasing the buffer, because the kernel may still write into it.
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
                if (boundedCloseDeadlineNanos >= 0) {
                    long remainingNanos = boundedCloseDeadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw closeDeadlineExpired(offset);
                    }
                    result = file.await(handle, Math.max(1L, remainingNanos / 1_000_000L));
                } else {
                    result = file.await(handle);
                }
            } catch (JuringException e) {
                if (boundedCloseDeadlineNanos >= 0 && e.isTimedOut()) {
                    // Bounded close: the write may still be in flight, so its buffer and FIFO
                    // entry must stay alive. Do not pop or free anything (bounded leak).
                    throw e;
                }
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

    /** Builds the typed timeout thrown when a bounded close cannot drain a write in time. */
    private static JuringException closeDeadlineExpired(long offset) {
        return new JuringException(JuringException.ETIMEDOUT, "close",
                "close deadline expired while draining a write at offset " + offset
                        + " (write abandoned; buffer kept alive)");
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
