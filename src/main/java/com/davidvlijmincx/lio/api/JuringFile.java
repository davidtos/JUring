package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Asynchronous file handle bound to a {@link JUring} ring: explicit-offset reads and
 * writes (plain and fixed-buffer), metadata operations, bounded close-with-drain, typed
 * {@code -errno} errors and short-I/O / {@code EAGAIN} policy.
 *
 * <p>This is the first layer of the JUring L1 API (see {@code SEASTAR-PLAN.md} §4 B1).
 * It keeps the raw-SQE fast path available through the {@code prepare*} methods, which
 * perform no heap allocation per operation, and adds blocking convenience methods on top
 * ({@link #read}, {@link #write}, {@link #length}, {@link #truncate}, ...) that drive the
 * operation to completion according to the configured {@link IoPolicy}.
 *
 * <h2>Threading</h2>
 * <p>A {@code JuringFile} is not thread-safe; like {@link JUring}, it is designed for
 * one ring per thread and one owner thread.
 *
 * <h2>Ring ownership</h2>
 * <p>The blocking helpers ({@link #await}, {@link #drain}, {@link #close} and the
 * convenience methods) reap completions from the ring. They therefore require that all
 * operations currently in flight on the ring belong to this handle; otherwise they fail
 * with {@link IllegalStateException} instead of silently losing another owner's
 * completions. Raw ring operations can be issued in parallel with a {@code JuringFile}
 * as long as the caller reaps those completions itself before calling a blocking file
 * helper. A ring-level completion dispatcher arrives in a later milestone (B3).
 *
 * <h2>Fast path</h2>
 * <p>Register the file once ({@link #register()}) and use
 * {@link #prepareReadFixed(long, int, int)} / {@link #prepareWriteFixed(long, int, int)}
 * with ring-registered fixed buffers, then {@link #await(long)} or the ring's own batch
 * reap. That path performs zero heap allocations per operation, including the await
 * bookkeeping.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * try (JUring ring = new JUring(64, SINGLE_ISSUER, COOP_TASKRUN, DEFER_TASKRUN);
 *      JuringFile file = JuringFile.open(ring, path, READ, DIRECT)) {
 *     file.register();
 *     ring.registerBuffers(4096, 16);
 *     MemorySegment buffer = ring.getRegisteredBuffer(0);
 *     long handle = file.prepareReadFixed(0, 4096, 0);
 *     int bytes = file.await(handle);   // 4096 (or short at EOF)
 * }
 * }</pre>
 */
public final class JuringFile implements AutoCloseable {

    /** Default capacity of the per-file in-flight ledger (maximum unawaited operations). */
    public static final int DEFAULT_IN_FLIGHT_CAPACITY = 1024;

    /** Default deadline used by {@link #close()} before cancelling in-flight operations. */
    public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;

    /** {@code fallocate} mode: do not change the file size. */
    public static final int FALLOC_FL_KEEP_SIZE = 0x01;
    /** {@code fallocate} mode: punch a hole (deallocate and zero the range). */
    public static final int FALLOC_FL_PUNCH_HOLE = 0x02;
    /** {@code fallocate} mode: zero the range. */
    public static final int FALLOC_FL_ZERO_RANGE = 0x10;

    private static final int STATX_BUFFER_SIZE = 256;
    private static final int STATX_SIZE_OFFSET = 40;
    private static final long GRACE_NANOS = 250_000_000L;
    private static final long POLL_SLICE_NANOS = 100_000L;

    private static final byte FREE = 0;
    private static final byte PENDING = 1;
    private static final byte DONE = 2;

    private final JUring ring;
    private final Path path;
    private final FileDescriptor fileDescriptor;
    private final ResultConsumer completionConsumer = this::onCompletion;

    private IoPolicy policy = IoPolicy.defaults();
    private long closeTimeoutMillis = DEFAULT_CLOSE_TIMEOUT_MILLIS;
    private int ledgerCapacity = DEFAULT_IN_FLIGHT_CAPACITY;

    private long[] handles;
    private int[] results;
    private byte[] states;
    private byte[] kinds;
    private int[] freeSlots;
    private int freeTop;

    private int inFlight;
    private int registeredSlot = -1;
    private boolean open = true;
    private boolean fdClosed;

    private long issuedOperations;
    private long completedOperations;
    private long failedOperations;
    private long abandonedOperations;
    private long foreignCompletions;

    private JuringFile(JUring ring, Path path, FileDescriptor fileDescriptor) {
        this.ring = ring;
        this.path = path;
        this.fileDescriptor = fileDescriptor;
    }

    // ------------------------------------------------------------------
    // open / close
    // ------------------------------------------------------------------

    /**
     * Opens a file on the given ring with default permissions ({@code 0644}).
     *
     * <p>The open itself is synchronous ({@code open(2)}); subsequent I/O is asynchronous.
     * {@code O_CLOEXEC} is always added. The file handle takes ownership of the
     * descriptor.
     *
     * @param ring    the ring the handle is bound to
     * @param path    the file path
     * @param options Linux open flags, e.g. {@code READ, DIRECT}
     * @return an open, unregistered {@code JuringFile}
     * @throws JuringException when the kernel refuses the open (typed errno)
     */
    public static JuringFile open(JUring ring, Path path, LinuxOpenOptions... options) throws JuringException {
        return open(ring, path, 0644, options);
    }

    /**
     * Opens a file on the given ring with an explicit creation mode.
     *
     * @param ring    the ring the handle is bound to
     * @param path    the file path
     * @param mode    creation mode used when {@code CREATE} is present
     * @param options Linux open flags, e.g. {@code READ, WRITE, CREATE, TRUNCATE}
     * @return an open, unregistered {@code JuringFile}
     * @throws JuringException when the kernel refuses the open (typed errno)
     */
    public static JuringFile open(JUring ring, Path path, int mode, LinuxOpenOptions... options) throws JuringException {
        Objects.requireNonNull(ring, "ring");
        Objects.requireNonNull(path, "path");
        if (ring.isClosed()) {
            throw new IllegalStateException("Cannot open a JuringFile on a closed JUring");
        }
        int flags = LinuxOpenOptions.combineOptions(options);
        int fd = NativeDispatcher.C.open(path.toString(), flags, mode);
        if (fd < 0) {
            throw JuringException.fromResult(fd, "open", path.toString());
        }
        return new JuringFile(ring, path, new FileDescriptor(fd));
    }

    /** @return the ring this handle is bound to */
    public JUring ring() {
        return ring;
    }

    /** @return the path this handle was opened with */
    public Path path() {
        return path;
    }

    /** @return the raw file descriptor (valid until {@link #close()}) */
    public int fd() {
        return fileDescriptor.getFd();
    }

    /** @return {@code true} while the handle is open */
    public boolean isOpen() {
        return open;
    }

    /** @return the fixed-file slot index, or {@code -1} when the file is not registered */
    public int registeredSlot() {
        return registeredSlot;
    }

    /** @return {@code true} when the file is registered with the ring as a fixed file */
    public boolean isRegistered() {
        return registeredSlot >= 0;
    }

    /** @return the current short-I/O / {@code EAGAIN} policy */
    public IoPolicy policy() {
        return policy;
    }

    /**
     * Replaces the short-I/O / {@code EAGAIN} policy used by the blocking convenience
     * methods.
     *
     * @param policy the new policy, must not be {@code null}
     * @return this handle
     */
    public JuringFile withPolicy(IoPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * Sets the capacity of the in-flight ledger. Must be called before the first operation;
     * every issued-but-unawaited operation occupies one ledger slot.
     *
     * @param capacity maximum number of in-flight operations, {@code > 0}
     * @return this handle
     */
    public JuringFile withInFlightCapacity(int capacity) {
        if (handles != null) {
            throw new IllegalStateException("in-flight capacity cannot change after the first operation");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0: " + capacity);
        }
        this.ledgerCapacity = capacity;
        return this;
    }

    /**
     * Sets the deadline used by {@link #close()} before it cancels in-flight operations.
     *
     * @param millis timeout in milliseconds, {@code >= 0}
     * @return this handle
     */
    public JuringFile withCloseTimeout(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("close timeout must be >= 0: " + millis);
        }
        this.closeTimeoutMillis = millis;
        return this;
    }

    // ------------------------------------------------------------------
    // registration lifecycle
    // ------------------------------------------------------------------

    /**
     * Registers this file with the ring's fixed-file table (runtime add). Idempotent;
     * after registration the fixed-file fast path is available.
     *
     * @return the fixed-file slot index
     * @throws IllegalStateException when the handle is closed or the ring is not usable
     */
    public int register() {
        ensureOpen("register");
        if (registeredSlot >= 0) {
            return registeredSlot;
        }
        registeredSlot = ring.registerFileAdd(fileDescriptor);
        return registeredSlot;
    }

    /**
     * Removes this file from the ring's fixed-file table (runtime remove). Idempotent.
     *
     * @throws IllegalStateException when the ring is already closed
     */
    public void unregister() {
        if (registeredSlot < 0) {
            return;
        }
        int slot = registeredSlot;
        registeredSlot = -1;
        if (!ring.isClosed()) {
            ring.unregisterFileSlot(slot);
        }
    }

    // ------------------------------------------------------------------
    // async issue — zero-allocation fast path
    // ------------------------------------------------------------------

    /**
     * Prepares an asynchronous offset read into {@code buffer} ({@code READ} or
     * {@code READ_EXT}; fixed-file when registered). Does not submit and does not retry.
     *
     * @param offset file offset
     * @param length number of bytes to read, {@code 0 <= length <= buffer.byteSize()}
     * @param buffer native memory segment (must be writable)
     * @return the operation handle to pass to {@link #await(long)}
     * @throws JuringException if the operation cannot be prepared
     */
    public long prepareRead(long offset, int length, MemorySegment buffer) throws JuringException {
        ensureOpen("read");
        validateReadArguments(offset, length, buffer);
        ensureCapacity();
        long handle = registeredSlot >= 0
                ? ring.prepareRead(registeredSlot, buffer, length, offset)
                : ring.prepareRead(fileDescriptor, buffer, length, offset);
        track(handle);
        return handle;
    }

    /**
     * Prepares an asynchronous offset read into a ring-registered fixed buffer
     * ({@code READ_FIXED}); combine with {@link #register()} for the zero-allocation
     * registered-file + fixed-buffer fast path.
     *
     * @param offset      file offset
     * @param length      number of bytes to read
     * @param bufferIndex registered buffer index
     * @return the operation handle
     */
    public long prepareReadFixed(long offset, int length, int bufferIndex) throws JuringException {
        ensureOpen("readFixed");
        if (offset < 0 || length < 0 || bufferIndex < 0) {
            throw new IllegalArgumentException("invalid readFixed arguments: offset=" + offset
                    + ", length=" + length + ", bufferIndex=" + bufferIndex);
        }
        ensureCapacity();
        long handle = registeredSlot >= 0
                ? ring.prepareReadFixed(registeredSlot, length, offset, bufferIndex)
                : ring.prepareReadFixed(fileDescriptor, length, offset, bufferIndex);
        track(handle);
        return handle;
    }

    /**
     * Prepares an asynchronous offset write from {@code buffer} ({@code WRITE} or
     * {@code WRITE_EXT}). Does not submit and does not retry.
     *
     * @param offset file offset
     * @param buffer native memory segment containing the bytes to write
     * @return the operation handle
     */
    public long prepareWrite(long offset, MemorySegment buffer) throws JuringException {
        ensureOpen("write");
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset: " + offset);
        }
        JUring.validateNativeSegment(buffer, false);
        ensureCapacity();
        long handle = registeredSlot >= 0
                ? ring.prepareWrite(registeredSlot, buffer, offset)
                : ring.prepareWrite(fileDescriptor, buffer, offset);
        track(handle);
        return handle;
    }

    /**
     * Prepares an asynchronous offset write from a ring-registered fixed buffer
     * ({@code WRITE_FIXED}); the buffer must already contain the data (zero-copy write).
     *
     * @param offset      file offset
     * @param length      number of bytes to write, {@code 0 <= length <= buffer size}
     * @param bufferIndex registered buffer index
     * @return the operation handle
     */
    public long prepareWriteFixed(long offset, int length, int bufferIndex) throws JuringException {
        ensureOpen("writeFixed");
        if (offset < 0 || length < 0 || bufferIndex < 0) {
            throw new IllegalArgumentException("invalid writeFixed arguments: offset=" + offset
                    + ", length=" + length + ", bufferIndex=" + bufferIndex);
        }
        ensureCapacity();
        long handle = registeredSlot >= 0
                ? ring.prepareWriteFixed(registeredSlot, length, offset, bufferIndex)
                : ring.prepareWriteFixed(fileDescriptor, length, offset, bufferIndex);
        track(handle);
        return handle;
    }

    /**
     * Prepares an asynchronous {@code fsync} / {@code fdatasync}.
     *
     * @param dataOnly {@code true} for {@code fdatasync} semantics
     * @return the operation handle
     */
    public long prepareFsync(boolean dataOnly) throws JuringException {
        ensureOpen("fsync");
        int flags = dataOnly ? JUring.IORING_FSYNC_DATASYNC : 0;
        ensureCapacity();
        long handle = registeredSlot >= 0
                ? ring.prepareFsync(registeredSlot, flags)
                : ring.prepareFsync(fileDescriptor, flags);
        track(handle);
        return handle;
    }

    /**
     * Prepares an asynchronous {@code ftruncate}.
     *
     * @param newLength the new file length in bytes (negative values are rejected by the
     *                  kernel with {@code EINVAL}, surfaced as a typed error)
     * @return the operation handle
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_FTRUNCATE}
     */
    public long prepareTruncate(long newLength) throws JuringException {
        ensureOpen("truncate");
        if (!ring.supportsFtruncate()) {
            throw new UnsupportedOperationException("kernel does not support IORING_OP_FTRUNCATE; cannot truncate asynchronously");
        }
        if (newLength < 0) {
            // Mirror the kernel's EINVAL as a typed error instead of a client-side IAE.
            throw new JuringException(JuringException.EINVAL, "truncate", "negative length " + newLength);
        }
        ensureCapacity();
        long handle = registeredSlot >= 0
                ? ring.prepareFtruncate(registeredSlot, newLength)
                : ring.prepareFtruncate(fileDescriptor, newLength);
        track(handle);
        return handle;
    }

    /**
     * Prepares an asynchronous {@code fallocate}.
     *
     * @param offset file offset
     * @param length number of bytes
     * @param mode   {@code fallocate} mode ({@code 0}, {@link #FALLOC_FL_KEEP_SIZE},
     *               {@link #FALLOC_FL_PUNCH_HOLE}, {@link #FALLOC_FL_ZERO_RANGE}, or a
     *               combination supported by the filesystem)
     * @return the operation handle
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_FALLOCATE}
     */
    public long prepareFallocate(long offset, long length, int mode) throws JuringException {
        ensureOpen("fallocate");
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("invalid fallocate arguments: offset=" + offset + ", length=" + length);
        }
        if (!ring.supportsFallocate()) {
            throw new UnsupportedOperationException("kernel does not support IORING_OP_FALLOCATE; cannot allocate/discard asynchronously");
        }
        ensureCapacity();
        long handle = registeredSlot >= 0
                ? ring.prepareFallocate(registeredSlot, mode, offset, length)
                : ring.prepareFallocate(fileDescriptor, mode, offset, length);
        track(handle);
        return handle;
    }

    /**
     * Prepares an asynchronous hole punch ({@code fallocate} with
     * {@code PUNCH_HOLE | KEEP_SIZE}); the file size is unchanged and the range reads as
     * zeros. The range must be filesystem-aligned (typically 4 KiB).
     *
     * @param offset file offset
     * @param length number of bytes
     * @return the operation handle
     */
    public long prepareDiscard(long offset, long length) throws JuringException {
        return prepareFallocate(offset, length, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE);
    }

    // ------------------------------------------------------------------
    // blocking completion
    // ------------------------------------------------------------------

    /**
     * Waits until the given operation completes and returns its non-negative result
     * (bytes transferred for read/write, {@code 0} for metadata operations).
     *
     * @param handle operation handle returned by a {@code prepare*} method
     * @return the io_uring completion result for the operation
     * @throws JuringException  when the operation failed (typed errno)
     * @throws IllegalStateException when the handle is unknown, the file is closed, or the
     *                               ring has in-flight work owned by someone else
     */
    public int await(long handle) throws JuringException {
        return awaitInternal(handle, -1L);
    }

    /**
     * Bounded variant of {@link #await(long)}.
     *
     * @param handle        operation handle returned by a {@code prepare*} method
     * @param timeoutMillis deadline in milliseconds, {@code >= 0}; on expiry a
     *                      {@link JuringException} with {@code ETIMEDOUT} is thrown
     * @return the io_uring completion result for the operation
     * @throws JuringException on failure or deadline expiry (typed errno)
     */
    public int await(long handle, long timeoutMillis) throws JuringException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        return awaitInternal(handle, deadline);
    }

    private int awaitInternal(long handle, long deadlineNanos) throws JuringException {
        ensureOpen("await");
        checkRingOwnership("await");
        while (true) {
            int slot = findSlot(handle);
            if (slot < 0) {
                if (foreignCompletions > 0) {
                    throw new IllegalStateException("JuringFile.await: the ring delivered "
                            + foreignCompletions + " completion(s) not owned by this handle; "
                            + "do not share a ring with foreign in-flight work while using blocking file helpers");
                }
                throw new IllegalStateException("JuringFile.await: unknown operation handle " + handle
                        + " (never issued, or already consumed)");
            }
            if (states[slot] == DONE) {
                return consume(slot);
            }
            if (deadlineNanos > 0) {
                int reaped = ring.peekForBatchResult(Integer.MAX_VALUE, completionConsumer);
                if (reaped == 0) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        throw new JuringException(JuringException.ETIMEDOUT, "await",
                                "timed out waiting for operation handle " + handle);
                    }
                    LockSupport.parkNanos(Math.min(remaining, POLL_SLICE_NANOS));
                }
            } else {
                ring.submitAndWaitForBatchResult(1, completionConsumer);
            }
        }
    }

    /**
     * Waits until all operations currently in flight on this handle complete, recording
     * their results. Intended for shutdown paths; failures are recorded in
     * {@link #failedOperations()} and do not throw.
     *
     * @return number of operations that were in flight when the call started
     * @throws IllegalStateException when the ring carries foreign in-flight operations
     */
    public int drain() {
        ensureOpen("drain");
        checkRingOwnership("drain");
        int expected = inFlight;
        ring.submit();
        while (inFlight > 0) {
            ring.submitAndWaitForBatchResult(1, completionConsumer);
        }
        return expected;
    }

    // ------------------------------------------------------------------
    // blocking convenience (policy-driven)
    // ------------------------------------------------------------------

    /**
     * Reads until {@code buffer} is full or EOF, retrying short reads and {@code EAGAIN}
     * according to the {@link IoPolicy}. Offset reads never change the file position.
     *
     * @param offset file offset
     * @param buffer native memory segment to fill (must be writable)
     * @return the number of bytes read; less than {@code buffer.byteSize()} only at EOF
     * @throws JuringException on I/O failure, or at EOF when
     *                         {@link IoPolicy#shortReadAtEofAllowed()} is {@code false}
     */
    public int read(long offset, MemorySegment buffer) throws JuringException {
        ensureOpen("read");
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset: " + offset);
        }
        JUring.validateNativeSegment(buffer, true);
        long bufferSize = buffer.byteSize();
        if (bufferSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("buffer too large for read: " + bufferSize);
        }
        int length = (int) bufferSize;
        int total = 0;
        int eagainAttempts = 0;
        while (total < length) {
            MemorySegment target = total == 0 ? buffer : buffer.asSlice(total);
            long handle = prepareRead(offset + total, length - total, target);
            int read;
            try {
                read = await(handle);
            } catch (JuringException e) {
                if (e.isAgain() && eagainAttempts < policy.eagainRetries()) {
                    eagainAttempts++;
                    backoff();
                    continue;
                }
                throw e;
            }
            eagainAttempts = 0;
            if (read <= 0) {
                if (read == 0) {
                    if (!policy.shortReadAtEofAllowed()) {
                        throw new JuringException(0, "read",
                                "unexpected EOF at offset " + (offset + total) + " (" + total + " of " + length + " bytes read)");
                    }
                    return total;
                }
                throw new JuringException(0, "read", "negative sized read result " + read);
            }
            total += read;
        }
        return total;
    }

    /**
     * Writes {@code buffer} completely at {@code offset}, retrying short writes and
     * {@code EAGAIN} according to the {@link IoPolicy}.
     *
     * @param offset file offset
     * @param buffer native memory segment containing the data
     * @return the number of bytes written (always {@code buffer.byteSize()} on success)
     * @throws JuringException on I/O failure or when the write makes no progress
     */
    public int write(long offset, MemorySegment buffer) throws JuringException {
        ensureOpen("write");
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset: " + offset);
        }
        JUring.validateNativeSegment(buffer, false);
        long bufferSize = buffer.byteSize();
        if (bufferSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("buffer too large for write: " + bufferSize);
        }
        int length = (int) bufferSize;
        int total = 0;
        int eagainAttempts = 0;
        while (total < length) {
            MemorySegment source = total == 0 ? buffer : buffer.asSlice(total);
            long handle = prepareWrite(offset + total, source);
            int written;
            try {
                written = await(handle);
            } catch (JuringException e) {
                if (e.isAgain() && eagainAttempts < policy.eagainRetries()) {
                    eagainAttempts++;
                    backoff();
                    continue;
                }
                throw e;
            }
            eagainAttempts = 0;
            if (written <= 0) {
                throw new JuringException(0, "write", "write made no progress at offset "
                        + (offset + total) + " result=" + written);
            }
            total += written;
        }
        return total;
    }

    /**
     * Returns the file size through an asynchronous {@code statx} on the descriptor
     * ({@code AT_EMPTY_PATH}); works for registered files too.
     *
     * @return the file size in bytes
     * @throws JuringException on failure (typed errno)
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_STATX}
     */
    public long length() throws JuringException {
        ensureOpen("length");
        if (!ring.supportsStatx()) {
            throw new UnsupportedOperationException("kernel does not support IORING_OP_STATX; cannot query length asynchronously");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment statxBuffer = arena.allocate(STATX_BUFFER_SIZE, 8);
            ensureCapacity();
            long handle = registeredSlot >= 0
                    ? ring.prepareStatx(registeredSlot, statxBuffer)
                    : ring.prepareStatx(fileDescriptor, statxBuffer);
            track(handle);
            await(handle);
            return statxBuffer.get(JAVA_LONG, STATX_SIZE_OFFSET);
        }
    }

    /**
     * Truncates the file to {@code newLength} bytes (asynchronous {@code ftruncate}).
     *
     * @param newLength the new length
     * @throws JuringException on failure (typed errno, e.g. {@code EINVAL})
     */
    public void truncate(long newLength) throws JuringException {
        long handle = prepareTruncate(newLength);
        await(handle);
    }

    /**
     * Allocates (preallocates) the range, extending the file size when needed
     * ({@code fallocate} mode 0).
     *
     * @param offset file offset
     * @param length number of bytes
     * @throws JuringException on failure (typed errno)
     */
    public void allocate(long offset, long length) throws JuringException {
        long handle = prepareFallocate(offset, length, 0);
        await(handle);
    }

    /**
     * Deallocates the range ({@code PUNCH_HOLE | KEEP_SIZE}); the file size is unchanged
     * and the range reads back as zeros.
     *
     * @param offset file offset (filesystem-aligned)
     * @param length number of bytes (filesystem-aligned)
     * @throws JuringException on failure (typed errno, e.g. {@code EINVAL} for unaligned ranges)
     */
    public void discard(long offset, long length) throws JuringException {
        long handle = prepareDiscard(offset, length);
        await(handle);
    }

    /** Flushes file data and metadata ({@code fsync}). */
    public void fsync() throws JuringException {
        long handle = prepareFsync(false);
        await(handle);
    }

    /** Flushes file data ({@code fdatasync}). */
    public void fdatasync() throws JuringException {
        long handle = prepareFsync(true);
        await(handle);
    }

    // ------------------------------------------------------------------
    // counters
    // ------------------------------------------------------------------

    /** @return total number of operations prepared through this handle */
    public long issuedOperations() {
        return issuedOperations;
    }

    /** @return number of operations that completed (successfully or not) and were reaped */
    public long completedOperations() {
        return completedOperations;
    }

    /** @return number of operations that completed with a negative result */
    public long failedOperations() {
        return failedOperations;
    }

    /** @return number of operations currently prepared but not yet reaped */
    public int inFlightOperations() {
        return inFlight;
    }

    /** @return number of operations abandoned at close (drain deadline exceeded) */
    public int abandonedOperations() {
        return (int) abandonedOperations;
    }

    // ------------------------------------------------------------------
    // close
    // ------------------------------------------------------------------

    /**
     * Closes the handle, draining in-flight operations with the configured deadline
     * ({@link #DEFAULT_CLOSE_TIMEOUT_MILLIS} by default), cancelling what cannot be
     * drained, unregistering the fixed-file slot and closing the descriptor. Never blocks
     * longer than the deadline, never throws because an operation failed, and is
     * idempotent.
     */
    @Override
    public void close() {
        close(closeTimeoutMillis);
    }

    /**
     * Closes the handle with an explicit drain deadline.
     *
     * @param timeoutMillis deadline in milliseconds, {@code >= 0}
     */
    public void close(long timeoutMillis) {
        if (!open) {
            return;
        }
        open = false;
        try {
            if (!ring.isClosed()) {
                try {
                    drainForClose(timeoutMillis);
                } catch (RuntimeException ignored) {
                    // close() must not throw because draining/cancelling failed; the ring
                    // and descriptor teardown below still run. Deadline logic is enforced
                    // inside drainForClose; this is a last-resort guard.
                    abandonRemaining();
                }
            }
            unregisterSlotQuietly();
        } finally {
            if (!fdClosed) {
                fdClosed = true;
                fileDescriptor.close();
            }
        }
    }

    private void drainForClose(long timeoutMillis) {
        int ringInFlight = ring.inFlightCount();
        if (ringInFlight > inFlight) {
            // Foreign in-flight work on the ring: reaping it here would steal another
            // owner's completions. Cancel only our own operations and leave the CQ alone.
            cancelOwnOperations();
            abandonRemaining();
            return;
        }
        if (ringInFlight < inFlight) {
            // Some completions were reaped outside this handle; the ledger can no longer
            // be reconciled, so count the remainder as abandoned rather than hang.
            abandonRemaining();
            return;
        }
        ring.submit();
        long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        if (drainUntilIdle(deadline)) {
            return;
        }
        cancelOwnOperations();
        long graceDeadline = System.nanoTime() + GRACE_NANOS;
        if (drainUntilIdle(graceDeadline)) {
            return;
        }
        abandonRemaining();
    }

    private boolean drainUntilIdle(long deadlineNanos) {
        while (inFlight > 0) {
            if (ring.peekForBatchResult(Integer.MAX_VALUE, completionConsumer) > 0) {
                continue;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            LockSupport.parkNanos(Math.min(remaining, POLL_SLICE_NANOS));
        }
        return true;
    }

    private void cancelOwnOperations() {
        if (states == null) {
            return; // no operation was ever issued through this handle
        }
        boolean prepared = false;
        for (int i = 0; i < ledgerCapacity; i++) {
            if (states[i] != PENDING) {
                continue;
            }
            long target = handles[i];
            while (true) {
                try {
                    ring.prepareCancel(target);
                    prepared = true;
                    break;
                } catch (IllegalStateException sqFull) {
                    if (!prepared) {
                        throw sqFull;
                    }
                    ring.submit();
                    prepared = false;
                }
            }
        }
        if (prepared) {
            ring.submit();
        }
    }

    private void abandonRemaining() {
        int abandonedNow = inFlight;
        if (abandonedNow > 0) {
            abandonedOperations += abandonedNow;
        }
        inFlight = 0;
        foreignCompletions = 0;
        resetLedger();
    }

    private void resetLedger() {
        if (states == null) {
            return;
        }
        for (int i = 0; i < ledgerCapacity; i++) {
            states[i] = FREE;
            handles[i] = 0L;
            results[i] = 0;
            kinds[i] = 0;
        }
        freeTop = ledgerCapacity;
        for (int i = 0; i < ledgerCapacity; i++) {
            freeSlots[i] = i;
        }
    }

    private void unregisterSlotQuietly() {
        int slot = registeredSlot;
        registeredSlot = -1;
        if (slot < 0 || ring.isClosed()) {
            return;
        }
        try {
            ring.unregisterFileSlot(slot);
        } catch (RuntimeException ignored) {
            // Ring teardown races are not actionable from close(); the ring owns cleanup.
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void ensureOpen(String operation) {
        if (!open) {
            throw new IllegalStateException("JuringFile." + operation + ": handle is closed (" + path + ")");
        }
        if (ring.isClosed()) {
            throw new IllegalStateException("JuringFile." + operation + ": ring is closed (" + path + ")");
        }
    }

    private void checkRingOwnership(String operation) {
        int ringInFlight = ring.inFlightCount();
        if (ringInFlight != inFlight) {
            throw new IllegalStateException("JuringFile." + operation + ": ring has " + ringInFlight
                    + " in-flight operation(s) but this handle owns " + inFlight
                    + "; blocking file helpers require that every in-flight operation on the ring belongs to this handle"
                    + " (await the foreign work first, or use raw prepare* + ring reaping)");
        }
    }

    private void validateReadArguments(long offset, int length, MemorySegment buffer) throws JuringException {
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("negative length: " + length);
        }
        JUring.validateNativeSegment(buffer, true);
        if (length > buffer.byteSize()) {
            throw new IllegalArgumentException("length " + length + " exceeds buffer size " + buffer.byteSize());
        }
    }

    private void backoff() {
        long nanos = policy.eagainBackoffNanos();
        if (nanos > 0) {
            LockSupport.parkNanos(nanos);
        }
    }

    private void ensureLedger() {
        if (handles != null) {
            return;
        }
        int capacity = ledgerCapacity;
        handles = new long[capacity];
        results = new int[capacity];
        states = new byte[capacity];
        kinds = new byte[capacity];
        freeSlots = new int[capacity];
        freeTop = capacity;
        for (int i = 0; i < capacity; i++) {
            freeSlots[i] = i;
        }
    }

    private void ensureCapacity() {
        ensureLedger();
        if (freeTop == 0) {
            throw new IllegalStateException("JuringFile in-flight capacity exhausted (" + ledgerCapacity
                    + "); await or drain completions, or raise withInFlightCapacity before the first operation");
        }
    }

    private void track(long handle) {
        int slot = freeSlots[--freeTop];
        handles[slot] = handle;
        states[slot] = PENDING;
        inFlight++;
        issuedOperations++;
    }

    private void onCompletion(Result result) {
        if (result.type() == OperationType.TIMEOUT) {
            // Stray timeout completions (e.g. from a shared ring) carry no file operation.
            return;
        }
        long handle = result.id();
        int value = (int) result.result();
        byte kind = (byte) result.type().getIndex();
        for (int i = 0; i < ledgerCapacity; i++) {
            if (states[i] == PENDING && handles[i] == handle) {
                states[i] = DONE;
                results[i] = value;
                kinds[i] = kind;
                inFlight--;
                completedOperations++;
                if (value < 0) {
                    failedOperations++;
                }
                return;
            }
        }
        foreignCompletions++;
    }

    private int findSlot(long handle) {
        for (int i = 0; i < ledgerCapacity; i++) {
            if (handles[i] == handle && (states[i] == PENDING || states[i] == DONE)) {
                return i;
            }
        }
        return -1;
    }

    private int consume(int slot) throws JuringException {
        int value = results[slot];
        long handle = handles[slot];
        byte kind = kinds[slot];
        states[slot] = FREE;
        handles[slot] = 0L;
        results[slot] = 0;
        kinds[slot] = 0;
        freeSlots[freeTop++] = slot;
        if (value < 0) {
            // Operation-name formatting only happens on the failure path: the success path
            // must not allocate (see JuringFileZeroAllocationTest).
            throw JuringException.fromResult(value, operationName(kind), path + " handle=" + handle);
        }
        return value;
    }

    private static String operationName(byte kind) {
        try {
            return OperationType.valueOf(kind).name().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException ignored) {
            return "file operation";
        }
    }

    @Override
    public String toString() {
        return "JuringFile{" + path + ", fd=" + fileDescriptor.getFd()
                + ", registeredSlot=" + registeredSlot
                + ", inFlight=" + inFlight
                + ", issued=" + issuedOperations
                + ", completed=" + completedOperations
                + ", abandoned=" + abandonedOperations
                + (open ? "" : ", closed") + '}';
    }
}
