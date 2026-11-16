package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Buffered, prefetching input stream over a {@link JuringFile}.
 *
 * <p>This is the B2 L1 stream layer (see {@code SEASTAR-PLAN.md} §4 B2 and
 * {@code docs/b2-design.md}): a byte stream on top of the asynchronous file handle with a
 * configurable buffer size, configurable readahead depth, optional seastar-style adaptive
 * readahead (slow start + 4 MiB unused-ratio windows), zero-copy chunk reads,
 * complete-until-done {@link #read(MemorySegment)} and bounded memory.
 *
 * <p>Typical usage:
 * <pre>{@code
 * try (JuringInputStream in = JuringInputStream.open(file,
 *         InputStreamOptions.defaults().withBufferSize(128 * 1024).withReadAhead(7))) {
 *     MemorySegment chunk;
 *     while ((chunk = in.readChunk()).byteSize() > 0) {
 *         consume(chunk);            // valid until the next stream call
 *     }
 * }
 * }</pre>
 *
 * <h2>Chunk lifetime (zero-copy contract)</h2>
 * <p>{@link #readChunk()} returns a view into the stream's internal buffer. The view is
 * valid only until the next call to {@link #readChunk()}, {@link #read(MemorySegment)},
 * {@link #skip(long)} or {@link #close()}; copy it if it must outlive the call.
 *
 * <h2>Bounded memory</h2>
 * <p>The stream allocates exactly {@code readAhead + 1} native buffers of
 * {@code alignUp(bufferSize, alignment) + alignment} bytes each, once, at
 * {@code open()}. {@link #bufferMemoryBytes()} reports the total.
 *
 * <h2>Threading and ring ownership</h2>
 * <p>The stream is <strong>not thread-safe</strong> (one owner thread, like
 * {@link JuringFile}). It uses {@link JuringFile}'s blocking helpers, so it may share one
 * {@link JUring} with other {@link JuringFile}-based handles (files, output streams, group
 * commits) on the same thread; completions are routed to their issuing handle. Only raw
 * un-reaped ring work on the shared ring is rejected (B10a).
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #close()} drains the stream's own in-flight reads within a hard deadline,
 * releases the buffers and is idempotent. It does <strong>not</strong> close the underlying
 * {@link JuringFile} (callers own the file). A read that cannot be drained by the deadline is
 * abandoned with its buffer kept alive (a bounded leak, never a use-after-free) and counted
 * by {@link #abandonedAtClose()}. Using the stream after {@code close()} throws
 * {@link IllegalStateException}.
 */
public final class JuringInputStream implements AutoCloseable {

    /** Default deadline used when draining in-flight reads at {@link #close()}. */
    public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;

    /** Fixed grace on top of the close deadline before a read is abandoned. */
    private static final long CLOSE_GRACE_NANOS = 250_000_000L;

    /** Readahead adaptation window size (matches seastar's {@code fstream.cc}). */
    private static final long WINDOW_SIZE = 4L * 1024 * 1024;
    /** Target maximum fraction of unused readahead bytes (seastar unused_ratio_target). */
    private static final long UNUSED_RATIO_NUM = 25;
    private static final long UNUSED_RATIO_DEN = 100;
    private static final int MIN_ADAPTIVE_BUFFER = 8192;

    private static final byte PENDING = 0;
    private static final byte DONE = 1;

    private final JuringFile file;
    private final InputStreamOptions options;
    private final Arena arena;

    private final int alignment;
    private final int maxBufferSize;      // alignUp(options.bufferSize(), alignment)
    private final int bufferCapacity;     // maxBufferSize + alignment
    private final int maxReadAhead;       // options.readAhead()
    private final int maxInFlight;        // maxReadAhead + 1
    private final boolean dynamic;
    private final long closeTimeoutMillis;

    // Buffer pool (all allocated at construction; bounded memory).
    private final MemorySegment[] buffers;
    private final int[] freeBuffers;
    private int freeBufferTop;

    // Prefetch descriptors, used as a FIFO plus a free stack.
    private final long[] handles;
    private final int[] bufferIndex;
    private final long[] alignedStart;
    private final int[] frontTrim;
    private final int[] logicalLength;
    private final byte[] state;
    private final int[] results;
    private final int[] fifo;
    private final int[] freeDescriptors;
    private int fifoHead;
    private int fifoSize;
    private int freeDescriptorTop;
    private int heldDescriptor = -1;   // descriptor whose buffer backs the last returned chunk

    // Adaptive readahead state (seastar fstream.cc:106-235, simplified per-stream).
    private int currentBufferSize;
    private int currentReadAhead;
    private boolean slowStart;
    private long windowTotal;
    private long windowUnused;
    private long previousWindowTotal;
    private long previousWindowUnused;

    // Stream state.
    private final long startOffset;
    private final long windowLength;      // -1 = until EOF
    private long position;                // next absolute file offset to return
    private long windowRemaining;         // -1 = unknown
    private long bufferedBytes;           // sum of logicalLength of queued chunks
    private long bytesRead;
    /**
     * Tail of the last chunk returned by {@link #readChunk()} that a partial
     * complete-until-done {@code read(...)} did not consume yet. The bytes after
     * {@code carryOffset} are the next bytes a {@code read()} must copy.
     */
    private MemorySegment carryChunk = MemorySegment.NULL;
    private int carryOffset;
    /** True EOF observed on the file (short/zero read), as opposed to a consumed window. */
    private boolean fileEof;
    private boolean closed;
    /** Reads abandoned by {@link #close()} because the bounded deadline expired. */
    private long abandonedAtClose;

    private JuringInputStream(JuringFile file, InputStreamOptions options, long offset, long length) {
        this.file = file;
        this.options = options;
        this.alignment = options.alignment();
        this.maxBufferSize = alignUp(options.bufferSize(), this.alignment);
        this.bufferCapacity = Math.addExact(maxBufferSize, this.alignment);
        this.maxReadAhead = options.readAhead();
        this.maxInFlight = maxReadAhead + 1;
        this.dynamic = options.dynamicAdjustments();
        this.closeTimeoutMillis = DEFAULT_CLOSE_TIMEOUT_MILLIS;
        this.startOffset = offset;
        this.windowLength = length;
        this.position = offset;
        this.windowRemaining = length < 0 ? -1 : length;
        this.bytesRead = 0L;
        this.fileEof = false;

        // Adaptive initial state: seastar starts in slow start with the minimal buffer
        // when dynamic adjustments are enabled (file_data_source_impl constructor calls
        // set_new_buffer_size(after_skip::no) on an empty history).
        if (dynamic) {
            int minimal = minimalBufferSize(options.bufferSize(), this.alignment);
            this.currentBufferSize = minimal;
            this.currentReadAhead = Math.min(1, maxReadAhead);
            this.slowStart = minimal < options.bufferSize();
        } else {
            this.currentBufferSize = options.bufferSize();
            this.currentReadAhead = maxReadAhead;
            this.slowStart = false;
        }

        this.arena = Arena.ofConfined();
        try {
            this.buffers = new MemorySegment[maxInFlight];
            for (int i = 0; i < maxInFlight; i++) {
                buffers[i] = arena.allocate(bufferCapacity, this.alignment);
            }
            this.freeBuffers = new int[maxInFlight];
            for (int i = 0; i < maxInFlight; i++) {
                freeBuffers[i] = i;
            }
            this.freeBufferTop = maxInFlight;

            this.handles = new long[maxInFlight];
            this.bufferIndex = new int[maxInFlight];
            this.alignedStart = new long[maxInFlight];
            this.frontTrim = new int[maxInFlight];
            this.logicalLength = new int[maxInFlight];
            this.state = new byte[maxInFlight];
            this.results = new int[maxInFlight];
            this.fifo = new int[maxInFlight];
            this.freeDescriptors = new int[maxInFlight];
            for (int i = 0; i < maxInFlight; i++) {
                freeDescriptors[i] = i;
            }
            this.freeDescriptorTop = maxInFlight;
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // factories
    // ------------------------------------------------------------------

    /**
     * Opens an input stream that reads the whole file from offset 0 with default options.
     *
     * @param file the backing file (must be opened for reading)
     * @return a new input stream
     * @throws JuringException if reading the file fails
     */
    public static JuringInputStream open(JuringFile file) throws JuringException {
        return open(file, InputStreamOptions.defaults());
    }

    /**
     * Opens an input stream that reads the whole file from offset 0.
     *
     * @param file    the backing file (must be opened for reading)
     * @param options stream options
     * @return a new input stream
     * @throws JuringException if reading the file fails
     */
    public static JuringInputStream open(JuringFile file, InputStreamOptions options) throws JuringException {
        return open(file, 0L, -1L, options);
    }

    /**
     * Opens an input stream over an explicit window of the file.
     *
     * <p>The B6 {@link InputStreamOptions#fadvise()} hint (when not
     * {@link FadviseAdvice#NORMAL}) is applied once, after the arguments are validated and
     * before the first read: the declared window is hinted ({@code (offset, length)}, with
     * {@code 0} meaning "to EOF"). A hint failure propagates out of this method.
     *
     * @param file    the backing file (must be opened for reading)
     * @param offset  absolute start offset, {@code >= 0}
     * @param length  number of bytes to expose, or {@code -1} to read until EOF
     * @param options stream options
     * @return a new input stream
     * @throws JuringException if reading the file fails, or the optional {@code fadvise}
     *                         hint fails
     */
    public static JuringInputStream open(JuringFile file, long offset, long length,
                                         InputStreamOptions options) throws JuringException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        if (!file.isOpen()) {
            throw new IllegalStateException("JuringInputStream.open: file is closed (" + file.path() + ")");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (length < -1) {
            throw new IllegalArgumentException("length must be >= -1 (-1 = until EOF): " + length);
        }
        if (options.fadvise() != FadviseAdvice.NORMAL) {
            file.fadvise(options.fadvise(), offset, length <= 0 ? 0L : length);
        }
        return new JuringInputStream(file, options, offset, length);
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
     * Returns the configured maximum chunk size in bytes.
     *
     * @return the configured maximum chunk size
     */
    public int bufferSize() {
        return options.bufferSize();
    }

    /**
     * Returns the adaptive current chunk size in bytes.
     *
     * @return the current chunk size ({@code bufferSize()} when adaptation is off)
     */
    public int currentBufferSize() {
        return currentBufferSize;
    }

    /**
     * Returns the configured maximum number of extra prefetch buffers.
     *
     * @return the configured maximum readahead depth
     */
    public int readAhead() {
        return maxReadAhead;
    }

    /**
     * Returns the adaptive current number of extra prefetch buffers.
     *
     * @return the current readahead depth
     */
    public int currentReadAhead() {
        return currentReadAhead;
    }

    /**
     * Returns the number of bytes of native buffer memory held by this stream.
     *
     * @return the bounded buffer memory in bytes ({@code 0} after a normal {@link #close()};
     *         the retained size after an abandoned close, where the buffers stay alive)
     */
    public long bufferMemoryBytes() {
        return (!closed || abandonedAtClose > 0) ? (long) buffers.length * bufferCapacity : 0L;
    }

    /**
     * Returns the absolute file offset of the next byte the stream will return.
     *
     * @return the next absolute file offset
     */
    public long position() {
        return position - unreadCarry();
    }

    /**
     * Returns the number of bytes left in the declared window.
     *
     * @return the bytes left in the declared window, or {@code -1} when the stream reads
     *         until EOF
     */
    public long remaining() {
        return windowRemaining < 0 ? -1 : windowRemaining + unreadCarry();
    }

    /**
     * Returns the total number of bytes returned or skipped by this stream.
     *
     * @return the number of bytes consumed so far
     */
    public long bytesRead() {
        return bytesRead - unreadCarry();
    }

    /**
     * Returns whether EOF has been observed (or a zero-length window was requested).
     *
     * <p>This is not a lookahead guarantee before EOF has been reached.
     *
     * @return {@code true} once EOF has been observed
     */
    public boolean eof() {
        return (fileEof || windowRemaining == 0) && unreadCarry() == 0;
    }

    private long unreadCarry() {
        return carryOffset > 0 ? carryChunk.byteSize() - carryOffset : 0L;
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    /**
     * Returns the next chunk of the stream.
     *
     * <p>The returned segment is a <strong>view into an internal buffer</strong> and is
     * valid only until the next stream call ({@code readChunk}/{@code read}/{@code skip}/
     * {@code close}). An empty segment ({@link MemorySegment#byteSize()} {@code == 0})
     * signals EOF.
     *
     * @return the next chunk, or an empty segment at EOF
     * @throws JuringException       if the read fails (typed errno)
     * @throws IllegalStateException if the stream is closed
     */
    public MemorySegment readChunk() throws JuringException {
        ensureOpen();
        if (carryOffset > 0) {
            MemorySegment remainder = carryChunk.asSlice(carryOffset);
            carryOffset = 0;
            carryChunk = MemorySegment.NULL;
            return remainder;
        }
        carryChunk = MemorySegment.NULL;
        releaseHeld();
        if (fifoSize == 0 && (fileEof || windowRemaining == 0)) {
            return MemorySegment.NULL;
        }
        ensurePrefetch();
        if (fifoSize == 0) {
            fileEof = true;
            return MemorySegment.NULL;
        }
        int slot = fifo[fifoHead];
        if (state[slot] == PENDING) {
            // The consumer has to wait for data: grow the readahead window (seastar
            // file_data_source_impl::try_increase_read_ahead), then issue more reads.
            tryIncreaseReadAhead();
            ensurePrefetch();
            slot = fifo[fifoHead];
        }
        try {
            awaitIfPending(slot);
        } catch (JuringException e) {
            // The failed completion has already been consumed from the file ledger; release
            // its descriptor (the operation is finished) and drop the rest of the window.
            bufferedBytes -= logicalLength[slot];
            popDescriptor(slot);
            discardPrefetched();
            throw e;
        }
        int available = results[slot] - frontTrim[slot];
        int chunkLength;
        if (available <= 0) {
            chunkLength = 0;
            fileEof = true;
        } else {
            chunkLength = Math.min(available, logicalLength[slot]);
            if (available < logicalLength[slot]) {
                fileEof = true; // short read before the requested logical chunk => EOF
            }
            if (windowRemaining >= 0 && chunkLength > windowRemaining) {
                chunkLength = (int) windowRemaining;
            }
        }
        int trim = frontTrim[slot];
        int buffer = bufferIndex[slot];
        consumeDescriptor(slot, chunkLength);
        if (fileEof) {
            // Prefetched reads past the observed end of file are unusable.
            discardPrefetched();
        }
        if (chunkLength == 0) {
            return MemorySegment.NULL;
        }
        updateHistoryConsumed(chunkLength);
        heldDescriptor = slot;
        return buffers[buffer].asSlice(trim, chunkLength);
    }

    /**
     * Reads until {@code destination} is full or EOF (complete-until-done), copying bytes
     * from the internal prefetch buffers.
     *
     * @param destination writable native segment to fill
     * @return the number of bytes read ({@code destination.byteSize()} unless EOF was hit)
     * @throws JuringException if the read fails
     */
    public int read(MemorySegment destination) throws JuringException {
        Objects.requireNonNull(destination, "destination");
        JUring.validateNativeSegment(destination, true);
        long size = destination.byteSize();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("destination too large: " + size);
        }
        int length = (int) size;
        if (length == 0) {
            ensureOpen();
            return 0;
        }
        int total = 0;
        while (total < length) {
            if (carryOffset > 0) {
                int available = (int) carryChunk.byteSize() - carryOffset;
                int copy = Math.min(available, length - total);
                MemorySegment.copy(carryChunk, carryOffset, destination, total, copy);
                carryOffset += copy;
                total += copy;
                if (carryOffset == carryChunk.byteSize()) {
                    carryOffset = 0;
                    carryChunk = MemorySegment.NULL;
                }
                continue;
            }
            MemorySegment chunk = readChunk();
            int chunkLength = (int) chunk.byteSize();
            if (chunkLength == 0) {
                break;
            }
            int copy = Math.min(chunkLength, length - total);
            MemorySegment.copy(chunk, 0, destination, total, copy);
            total += copy;
            if (copy < chunkLength) {
                carryChunk = chunk;
                carryOffset = copy;
            }
        }
        return total;
    }

    /**
     * Reads until {@code destination[offset..offset+length)} is full or EOF.
     *
     * @param destination destination array
     * @param offset      first index to fill
     * @param length      number of bytes to read
     * @return the number of bytes read (short only at EOF)
     * @throws JuringException if the read fails
     */
    public int read(byte[] destination, int offset, int length) throws JuringException {
        Objects.requireNonNull(destination, "destination");
        Objects.checkFromIndexSize(offset, length, destination.length);
        if (length == 0) {
            ensureOpen();
            return 0;
        }
        int total = 0;
        while (total < length) {
            if (carryOffset > 0) {
                int available = (int) carryChunk.byteSize() - carryOffset;
                int copy = Math.min(available, length - total);
                MemorySegment.copy(carryChunk, JAVA_BYTE, carryOffset, destination, offset + total, copy);
                carryOffset += copy;
                total += copy;
                if (carryOffset == carryChunk.byteSize()) {
                    carryOffset = 0;
                    carryChunk = MemorySegment.NULL;
                }
                continue;
            }
            MemorySegment chunk = readChunk();
            int chunkLength = (int) chunk.byteSize();
            if (chunkLength == 0) {
                break;
            }
            int copy = Math.min(chunkLength, length - total);
            MemorySegment.copy(chunk, JAVA_BYTE, 0, destination, offset + total, copy);
            total += copy;
            if (copy < chunkLength) {
                carryChunk = chunk;
                carryOffset = copy;
            }
        }
        return total;
    }

    /**
     * Skips {@code bytes} bytes without copying them.
     *
     * <p>Fully prefetched chunks are dropped and their buffers recycled; a partially
     * consumed chunk is trimmed. When nothing is buffered and the stream is not at EOF,
     * the position advances directly (the prefetch will resume at the new offset). The
     * returned count is capped by the declared window when one was given, and by the
     * first observed EOF otherwise.
     *
     * @param bytes number of bytes to skip, {@code >= 0}
     * @return the number of bytes actually skipped
     * @throws JuringException if a prefetched read fails
     */
    public long skip(long bytes) throws JuringException {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be >= 0: " + bytes);
        }
        ensureOpen();
        if (bytes == 0) {
            return 0;
        }
        long toSkip = bytes;
        if (windowRemaining >= 0) {
            toSkip = Math.min(toSkip, windowRemaining + unreadCarry());
        } else if (fileEof) {
            toSkip = Math.min(toSkip, unreadCarry());
        }
        if (toSkip == 0) {
            return 0;
        }
        long skipped = 0;

        // Bytes of the last returned chunk that a partial read() did not consume are
        // already past position(); skipping over them must count them as skipped.
        if (carryOffset > 0) {
            long carryBytes = unreadCarry();
            if (toSkip < carryBytes) {
                carryOffset += (int) toSkip;
                return toSkip;
            }
            skipped += carryBytes;
            toSkip -= carryBytes;
            carryOffset = 0;
            carryChunk = MemorySegment.NULL;
            if (toSkip == 0) {
                updateHistoryUnused(skipped);
                return skipped;
            }
        }
        releaseHeld();
        if (fifoSize == 0 && !fileEof) {
            advance(toSkip);
            skipped += toSkip;
            updateHistoryUnused(skipped);
            return skipped;
        }
        while (toSkip > 0 && !(fileEof && fifoSize == 0)) {
            ensurePrefetch();
            if (fifoSize == 0) {
                break;
            }
            int slot = fifo[fifoHead];
            try {
                awaitIfPending(slot);
            } catch (JuringException e) {
                bufferedBytes -= logicalLength[slot];
                popDescriptor(slot);
                discardPrefetched();
                throw e;
            }
            int available = results[slot] - frontTrim[slot];
            if (available <= 0) {
                fileEof = true;
                discardPrefetched();
                break;
            }
            int chunkLength = Math.min(available, logicalLength[slot]);
            boolean shortChunk = available < logicalLength[slot];
            if (toSkip >= chunkLength) {
                toSkip -= chunkLength;
                skipped += chunkLength;
                advance(chunkLength);
                bufferedBytes -= logicalLength[slot];
                popDescriptor(slot);
                if (shortChunk) {
                    fileEof = true;
                    discardPrefetched();
                    break;
                }
            } else {
                int skipWithinChunk = (int) toSkip;
                frontTrim[slot] += skipWithinChunk;
                logicalLength[slot] -= skipWithinChunk;
                bufferedBytes -= skipWithinChunk;
                skipped += skipWithinChunk;
                advance(skipWithinChunk);
                toSkip = 0;
            }
        }
        if (skipped > 0) {
            updateHistoryUnused(skipped);
        }
        return skipped;
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /**
     * Drains the stream's in-flight reads, releases its buffers and marks it closed.
     * Idempotent; does not close the backing {@link JuringFile}.
     *
     * <p>The close is hard-bounded: it spends at most {@link #DEFAULT_CLOSE_TIMEOUT_MILLIS} ms
     * (+250 ms grace) draining in-flight reads and never falls back to an unbounded wait. A
     * read that has not completed by the hard deadline is abandoned: its buffer is kept alive
     * (a bounded leak, never a use-after-free), the request is left for the backing
     * {@link JuringFile}/{@link JUring} shutdown to cancel or drop, and this method throws a
     * {@link JuringException} with {@code ETIMEDOUT}. Abandoned reads are counted by
     * {@link #abandonedAtClose()}.
     *
     * @throws JuringException if a drained read failed or the close deadline expired
     */
    @Override
    public void close() throws JuringException {
        if (closed) {
            return;
        }
        closed = true;
        carryOffset = 0;
        carryChunk = MemorySegment.NULL;
        JuringException firstError = null;
        boolean safeToRelease = true;
        releaseHeld();
        long hardDeadline = System.nanoTime()
                + closeTimeoutMillis * 1_000_000L + CLOSE_GRACE_NANOS;
        while (fifoSize > 0) {
            int slot = fifo[fifoHead];
            if (state[slot] == PENDING) {
                long remainingNanos = hardDeadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    safeToRelease = false;
                    abandonedAtClose += pendingSlotsInFifo();
                    JuringException timeout = new JuringException(JuringException.ETIMEDOUT,
                            "close", "close deadline expired while draining input stream reads ("
                                    + pendingSlotsInFifo() + " read(s) abandoned)");
                    if (firstError == null) {
                        firstError = timeout;
                    } else {
                        firstError.addSuppressed(timeout);
                    }
                    break;
                }
                try {
                    results[slot] = file.await(handles[slot],
                            Math.max(1L, remainingNanos / 1_000_000L));
                } catch (JuringException e) {
                    if (e.isTimedOut()) {
                        // Bounded close: abandon instead of falling back to an unbounded wait.
                        // The kernel may still read into the buffer, so the arena is not freed.
                        safeToRelease = false;
                        abandonedAtClose += pendingSlotsInFifo();
                        if (firstError == null) {
                            firstError = e;
                        } else {
                            firstError.addSuppressed(e);
                        }
                        break;
                    }
                    if (firstError == null) {
                        firstError = e;
                    }
                } catch (RuntimeException e) {
                    // Ring ownership misuse: our read may still be in flight, so the
                    // buffers must stay alive (leaked, bounded) rather than be freed.
                    safeToRelease = false;
                    abandonedAtClose += pendingSlotsInFifo();
                    if (firstError == null) {
                        firstError = new JuringException(0, "close",
                                "could not drain input stream read: " + e.getMessage(), e);
                    }
                    break;
                }
            }
            state[slot] = DONE;
            releaseBufferAndDescriptor(slot);
            fifoHead = (fifoHead + 1) % maxInFlight;
            fifoSize--;
        }
        bufferedBytes = 0;
        if (safeToRelease) {
            arena.close();
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    /** Number of reads currently queued in the prefetch FIFO that are still pending. */
    private int pendingSlotsInFifo() {
        int pending = 0;
        for (int i = 0; i < fifoSize; i++) {
            if (state[fifo[(fifoHead + i) % maxInFlight]] == PENDING) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * Number of reads abandoned by {@link #close()} because the bounded close deadline
     * expired (or because ring-ownership misuse left the read un-reapable). Non-zero only
     * after an abnormal close that retains buffers; they are intentionally not freed (a
     * bounded leak is safer than a use-after-free).
     *
     * @return retained/abandoned read-buffer count ({@code 0} for a normal close)
     */
    public long abandonedAtClose() {
        return abandonedAtClose;
    }

    // ------------------------------------------------------------------
    // prefetch pipeline
    // ------------------------------------------------------------------

    private void ensurePrefetch() throws JuringException {
        if (closed || fileEof) {
            return;
        }
        long prefetchPosition = position + bufferedBytes;
        int issued = 0;
        while (fifoSize < currentReadAhead + 1 && freeDescriptorTop > 0) {
            long remain = windowRemaining < 0 ? Long.MAX_VALUE : windowRemaining - bufferedBytes;
            if (remain <= 0) {
                break;
            }
            int chunkLogical = (int) Math.min(remain, currentBufferSize);
            long start = alignDown(prefetchPosition, alignment);
            int trim = (int) (prefetchPosition - start);
            long readLength = alignUp((long) trim + chunkLogical, alignment);
            if (readLength > bufferCapacity) {
                throw new IllegalStateException("internal stream error: aligned read "
                        + readLength + " exceeds buffer capacity " + bufferCapacity);
            }
            if (readLength > Integer.MAX_VALUE) {
                throw new IllegalStateException("internal stream error: read length overflow");
            }
            int descriptor = freeDescriptors[--freeDescriptorTop];
            int buffer = freeBuffers[--freeBufferTop];
            MemorySegment segment = buffers[buffer];
            long handle;
            try {
                handle = file.prepareRead(start, (int) readLength, segment);
            } catch (IllegalStateException sqFull) {
                // The ring SQ can hold fewer entries than our prefetch window (or the
                // caller's ring is small): submit what we have and retry once.
                file.ring().submit();
                try {
                    handle = file.prepareRead(start, (int) readLength, segment);
                } catch (IllegalStateException again) {
                    freeDescriptors[freeDescriptorTop++] = descriptor;
                    freeBuffers[freeBufferTop++] = buffer;
                    throw new IllegalStateException("JuringInputStream: cannot issue prefetch: the ring "
                            + "submission queue is full or the JuringFile in-flight capacity is exhausted "
                            + "(window=" + (currentReadAhead + 1) + " reads); configure ring entries >= "
                            + "readAhead+2 and call JuringFile.withInFlightCapacity(readAhead+2) before the "
                            + "first operation (" + again.getMessage() + ")", again);
                }
            } catch (JuringException | RuntimeException | Error e) {
                freeDescriptors[freeDescriptorTop++] = descriptor;
                freeBuffers[freeBufferTop++] = buffer;
                throw e;
            }
            handles[descriptor] = handle;
            bufferIndex[descriptor] = buffer;
            alignedStart[descriptor] = start;
            frontTrim[descriptor] = trim;
            logicalLength[descriptor] = chunkLogical;
            state[descriptor] = PENDING;
            fifo[(fifoHead + fifoSize) % maxInFlight] = descriptor;
            fifoSize++;
            bufferedBytes += chunkLogical;
            prefetchPosition += chunkLogical;
            issued++;
        }
        if (issued > 0) {
            file.ring().submit();
        }
    }

    private void awaitIfPending(int slot) throws JuringException {
        if (state[slot] == DONE) {
            return;
        }
        results[slot] = file.await(handles[slot]);
        state[slot] = DONE;
    }

    /** Consumes the FIFO head, advancing position by {@code chunkLength} logical bytes. */
    private void consumeDescriptor(int slot, int chunkLength) {
        bufferedBytes -= logicalLength[slot];
        advance(chunkLength);
        if (chunkLength > 0) {
            // The buffer backs the chunk we return; it is recycled on the next stream
            // call (releaseHeld), so only pop the descriptor bookkeeping here.
            fifoHead = (fifoHead + 1) % maxInFlight;
            fifoSize--;
            return;
        }
        popDescriptor(slot);
    }

    private void popDescriptor(int slot) {
        fifoHead = (fifoHead + 1) % maxInFlight;
        fifoSize--;
        releaseBufferAndDescriptor(slot);
    }

    private void releaseBufferAndDescriptor(int slot) {
        int buffer = bufferIndex[slot];
        if (buffer >= 0) {
            freeBuffers[freeBufferTop++] = buffer;
            bufferIndex[slot] = -1;
        }
        state[slot] = DONE;
        freeDescriptors[freeDescriptorTop++] = slot;
    }

    private void releaseHeld() {
        if (heldDescriptor < 0) {
            return;
        }
        int slot = heldDescriptor;
        heldDescriptor = -1;
        releaseBufferAndDescriptor(slot);
    }

    private void discardPrefetched() {
        while (fifoSize > 0) {
            int slot = fifo[fifoHead];
            if (state[slot] == PENDING) {
                try {
                    file.await(handles[slot]);
                } catch (JuringException ignored) {
                    // A completed read that failed past the EOF we observed is not
                    // actionable; the completion is done, so the buffer is safe to reuse.
                }
                // RuntimeException (ring ownership misuse) propagates: the operation may
                // still be in flight, so its buffer must not be recycled or freed.
            }
            state[slot] = DONE;
            bufferedBytes -= logicalLength[slot];
            releaseBufferAndDescriptor(slot);
            fifoHead = (fifoHead + 1) % maxInFlight;
            fifoSize--;
        }
        bufferedBytes = 0;
    }

    // ------------------------------------------------------------------
    // adaptive readahead (simplified seastar fstream.cc:106-235)
    // ------------------------------------------------------------------

    private void updateHistoryConsumed(int bytes) {
        if (!dynamic) {
            return;
        }
        updateHistory(0, bytes);
        if (!slowStart) {
            return;
        }
        long newSize = Math.min((long) currentBufferSize * 2, options.bufferSize());
        long total = windowTotal + previousWindowTotal + newSize;
        long unused = windowUnused + previousWindowUnused + newSize;
        if (belowTarget(unused, total)) {
            currentBufferSize = (int) Math.max(newSize, 1);
            slowStart = currentBufferSize < options.bufferSize();
        }
    }

    private void updateHistoryUnused(long bytes) {
        if (!dynamic) {
            return;
        }
        updateHistory(bytes, bytes);
        setNewBufferSize(true);
    }

    private void updateHistory(long unused, long total) {
        windowTotal += total;
        windowUnused += unused;
        if (windowTotal >= WINDOW_SIZE) {
            previousWindowTotal = windowTotal;
            previousWindowUnused = windowUnused;
            windowTotal = 0;
            windowUnused = 0;
        }
    }

    private static boolean belowTarget(long unused, long total) {
        return unused * UNUSED_RATIO_DEN < total * UNUSED_RATIO_NUM;
    }

    private void setNewBufferSize(boolean afterSkip) {
        long total = windowTotal + previousWindowTotal;
        long unused = windowUnused + previousWindowUnused;
        if (afterSkip && belowTarget(unused, total)) {
            return;
        }
        long newTarget = (UNUSED_RATIO_NUM * total - UNUSED_RATIO_DEN * unused)
                / (UNUSED_RATIO_DEN - UNUSED_RATIO_NUM);
        long newSize = Math.max(newTarget, minimalBufferSize(options.bufferSize(), alignment));
        newSize = Math.max(floorPowerOfTwo(newSize), minimalBufferSize(options.bufferSize(), alignment));
        if (newSize >= currentBufferSize) {
            return;
        }
        slowStart = true;
        currentReadAhead = Math.min(currentReadAhead, 1);
        currentBufferSize = (int) Math.min(newSize, options.bufferSize());
    }

    private void tryIncreaseReadAhead() {
        if (dynamic && currentReadAhead < maxReadAhead && !slowStart) {
            currentReadAhead++;
        }
    }

    private void advance(long bytes) {
        position += bytes;
        bytesRead += bytes;
        if (windowRemaining >= 0) {
            windowRemaining -= bytes;
            if (windowRemaining < 0) {
                windowRemaining = 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JuringInputStream: stream is closed (" + file.path() + ")");
        }
        if (!file.isOpen()) {
            throw new IllegalStateException("JuringInputStream: backing file is closed (" + file.path() + ")");
        }
    }

    private static int minimalBufferSize(int configuredBufferSize, int alignment) {
        long minimal = Math.min(Math.max(configuredBufferSize / 4L, MIN_ADAPTIVE_BUFFER), configuredBufferSize);
        int rounded = alignUp(minimal, alignment);
        // The adaptive chunk size must never exceed the configured maximum buffer size
        // (docs/b2-design.md §1.3/§4.2); only the internal aligned read length may exceed it.
        return (int) Math.max(1L, Math.min(rounded, configuredBufferSize));
    }

    private static long floorPowerOfTwo(long value) {
        if (value <= 1) {
            return 1;
        }
        return Long.highestOneBit(value);
    }

    private static int alignUp(long value, int alignment) {
        long mask = alignment - 1L;
        long aligned = (value + mask) & ~mask;
        if (aligned > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("aligned value too large: " + value);
        }
        return (int) aligned;
    }

    private static long alignDown(long value, int alignment) {
        return value & ~(alignment - 1L);
    }

    @Override
    public String toString() {
        return "JuringInputStream{file=" + file.path()
                + ", position=" + position
                + ", bytesRead=" + bytesRead
                + ", bufferSize=" + options.bufferSize()
                + ", currentBufferSize=" + currentBufferSize
                + ", readAhead=" + currentReadAhead + '/' + maxReadAhead
                + (eof() ? ", eof" : "")
                + (closed ? ", closed" : "") + '}';
    }
}
