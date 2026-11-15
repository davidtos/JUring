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

    /**
     * B6: the {@code O_DSYNC} bit of the combined open flags. It is set by both
     * {@link LinuxOpenOptions#DSYNC} ({@code 0x1000}) and {@link LinuxOpenOptions#SYNC}
     * ({@code 0x101000}).
     */
    private static final int O_DSYNC = 0x1000;

    /** B6: ledger kind of an {@code fsync}/{@code fdatasync} request. */
    private static final byte FSYNC_KIND = (byte) OperationType.FSYNC.getIndex();

    private static final byte FREE = 0;
    private static final byte PENDING = 1;
    private static final byte DONE = 2;

    // Ledger roles. A timed operation is two ring requests (target + linked timeout):
    // ROLE_NORMAL target with a companion handle, and ROLE_COMPANION for the link timeout.
    private static final byte ROLE_NORMAL = 0;
    private static final byte ROLE_CONTROL = 1;
    private static final byte ROLE_COMPANION = 2;
    private static final byte ROLE_TIMEOUT = 3;

    /** {@code EALREADY}: kernel reported that the cancel target was already completing. */
    private static final int EALREADY = JuringException.EALREADY;

    /** Outcome of {@link #cancel(long)}. */
    public enum CancelStatus {
        /** The request was still in flight and the kernel cancelled it; its completion is {@code -ECANCELED}. */
        CANCELLED,
        /** The request had already completed (or was completing) when the cancel was issued; its real result is delivered. */
        ALREADY_COMPLETED,
        /** The handle is unknown to this file (never issued, consumed already, or not owned by this handle). */
        NOT_FOUND
    }

    private final JUring ring;
    private final Path path;
    private final FileDescriptor fileDescriptor;
    private final int openFlags;
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

    // B3 lifecycle bookkeeping (allocated with the ledger; no per-op allocation on the normal path).
    private long[] companionHandles;   // target slot -> linked-timeout handle (0 = none)
    private byte[] roles;              // ROLE_* for the slot
    private long[] timespecAddresses;  // malloc'd __kernel_timespec owned by the slot (0 = none)

    private int inFlight;
    private int registeredSlot = -1;
    private boolean open = true;
    private boolean fdClosed;

    private long issuedOperations;
    private long completedOperations;
    private long failedOperations;
    private long abandonedOperations;
    private long foreignCompletions;
    private long cancelledOperations;
    private long controlOperations;
    private long controlCompletions;
    // B6: fsync/fdatasync requests prepared/completed through this handle (owner-thread counters).
    private long syncOperations;
    private long syncCompletions;

    private JuringFile(JUring ring, Path path, FileDescriptor fileDescriptor, int openFlags) {
        this.ring = ring;
        this.path = path;
        this.fileDescriptor = fileDescriptor;
        this.openFlags = openFlags;
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
        return new JuringFile(ring, path, new FileDescriptor(fd), flags);
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
     * @return the configured maximum number of unawaited ring requests (the in-flight
     *         ledger capacity): {@link #DEFAULT_IN_FLIGHT_CAPACITY} unless changed with
     *         {@link #withInFlightCapacity(int)}
     */
    public int inFlightCapacity() {
        return ledgerCapacity;
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
    // durability and advisory page-cache hints (B6)
    // ------------------------------------------------------------------

    /**
     * The durability mode implied by the flags this file was opened with.
     *
     * <p>Derived once at {@code open} from the combined flags: the {@code O_DSYNC} bit is
     * set by {@link LinuxOpenOptions#DSYNC} and by {@link LinuxOpenOptions#SYNC}, so both
     * report {@link DurabilityMode#DSYNC}; every other open reports
     * {@link DurabilityMode#WRITEBACK}. This is a report only: {@code JuringFile} never
     * issues an implicit sync (B1 contract; the B6 durability points live on the buffered
     * streams and group commit).
     *
     * @return {@link DurabilityMode#DSYNC} or {@link DurabilityMode#WRITEBACK}; readable
     *         after {@link #close()} (it reports the frozen open-time value)
     */
    public DurabilityMode durability() {
        return (openFlags & O_DSYNC) != 0 ? DurabilityMode.DSYNC : DurabilityMode.WRITEBACK;
    }

    /**
     * Issues {@code posix_fadvise(fd, offset, length, advice.value())} as a blocking,
     * advisory page-cache hint (B6, §2.3). It never changes file content, stream position
     * or ring state, is <b>not</b> a ring request (it does not touch the in-flight ledger or
     * the operation counters) and therefore does not require the ring-ownership invariant
     * of the blocking helpers. The owner-thread rule still applies.
     *
     * <p>{@code length == 0} means "to the end of the file" (POSIX). {@code WILLNEED} and
     * {@code DONTNEED} can do real work and must not be called from a B4 shard task; the
     * other advices are cheap.
     *
     * @param advice the hint, must not be {@code null}
     * @param offset file offset to start the hint at, {@code >= 0}
     * @param length number of bytes the hint covers, {@code >= 0}; {@code 0} means to EOF
     * @throws NullPointerException     when {@code advice} is {@code null}
     * @throws IllegalArgumentException when {@code offset < 0} or {@code length < 0}
     * @throws IllegalStateException    when the handle or the ring is closed
     * @throws JuringException          on a non-zero {@code posix_fadvise} return (typed errno)
     */
    public void fadvise(FadviseAdvice advice, long offset, long length) throws JuringException {
        ensureOpen("fadvise");
        Objects.requireNonNull(advice, "advice");
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("negative length: " + length);
        }
        int result = NativeDispatcher.C.posixFadvise(fileDescriptor.getFd(), offset, length, advice.value());
        if (result != 0) {
            // posix_fadvise returns the errno directly (no -1/errno convention).
            throw new JuringException(result, "fadvise", path + " advice=" + advice
                    + ", offset=" + offset + ", length=" + length);
        }
    }

    /**
     * Whole-file convenience for {@link #fadvise(FadviseAdvice, long, long)}: exactly
     * equivalent to {@code fadvise(advice, 0, 0)}.
     *
     * @param advice the hint, must not be {@code null}
     * @throws NullPointerException     when {@code advice} is {@code null}
     * @throws IllegalStateException    when the handle or the ring is closed
     * @throws JuringException          on a non-zero {@code posix_fadvise} return (typed errno)
     */
    public void fadvise(FadviseAdvice advice) throws JuringException {
        fadvise(advice, 0, 0);
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
        // B6: count only OperationType.FSYNC requests prepared through this handle.
        syncOperations++;
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

    /**
     * Prepares a standalone relative {@code IORING_OP_TIMEOUT} tracked by this handle's
     * ledger, so {@link #await(long)}, {@link #cancel(long)}, {@link #cancelAll()} and
     * {@link #close(long)} apply to it uniformly. The timer uses {@code count=0} (a pure
     * timer: it does not complete early because other requests complete).
     *
     * <p>A fired timer completes with kernel {@code -ETIME}; {@link #await(long)} maps that
     * to a checked {@link JuringException} with {@link JuringException#ETIMEDOUT}. If the
     * timer is cancelled instead, {@code await} reports {@code ECANCELED}. This operation is
     * useful as a controllable, cancellable long-running request (e.g. to bound shutdown
     * tests); it owns a 16-byte native {@code __kernel_timespec}, freed when the operation is
     * consumed or abandoned.
     *
     * @param timeoutMillis relative timeout in milliseconds, {@code >= 0}
     * @return the operation handle
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_TIMEOUT}
     */
    public long prepareTimeout(long timeoutMillis) throws JuringException {
        ensureOpen("timeout");
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        if (!ring.supportsTimeout()) {
            throw new UnsupportedOperationException("kernel does not support IORING_OP_TIMEOUT; cannot prepare a timer");
        }
        ensureCapacity();
        long timespecAddress = allocateTimespec(timeoutMillis);
        long handle;
        try {
            handle = ring.prepareTimeout(MemorySegment.ofAddress(timespecAddress)
                    .reinterpret(JUring.TIMESPEC_BYTES), 0, 0);
        } catch (RuntimeException e) {
            NativeDispatcher.C.free(timespecAddress);
            throw e;
        }
        track(handle, 0L, timespecAddress, ROLE_TIMEOUT);
        return handle;
    }

    /**
     * Prepares a single-shot read with a <b>kernel</b> timeout: one {@code IORING_OP_READ}
     * carrying {@code IOSQE_IO_LINK} immediately followed by an {@code IORING_OP_LINK_TIMEOUT}.
     * Unlike {@link #await(long, long)} (caller-side deadline, operation stays in flight),
     * this cancels the read in the kernel when the deadline expires.
     *
     * <p>Deterministic outcome via {@link #await(long)}:
     * <ul>
     *   <li>read wins the race: its real byte count is returned (0 at EOF, short reads are
     *       NOT retried);</li>
     *   <li>timeout fires first: exactly two completions arrive (read {@code -ECANCELED},
     *       linked timeout {@code -ETIME}) and {@code await} throws
     *       {@link JuringException} with {@link JuringException#ETIMEDOUT};</li>
     *   <li>{@link #cancel(long)} wins: the read completes {@code -ECANCELED}, the linked
     *       timeout is disarmed with {@code -ECANCELED}, and {@code await} throws
     *       {@code ECANCELED} (distinguishable from a timeout);</li>
     *   <li>the read fails first (e.g. {@code -EBADF}): its real errno is thrown and the
     *       linked timeout completes {@code -ECANCELED}.</li>
     * </ul>
     *
     * <p>This is a two-request operation for ring accounting: {@link #inFlightOperations()}
     * and the {@code issued/completed} counters count the read and the linked timeout, and
     * the ledger needs two free slots ({@link #withInFlightCapacity}).
     *
     * @param offset      file offset
     * @param length      bytes to read, {@code 0 <= length <= buffer.byteSize()}
     * @param buffer      native writable segment
     * @param timeoutMillis kernel timeout in milliseconds, {@code >= 0}
     * @return the read operation handle
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_TIMEOUT}
     *                                       or {@code IORING_OP_LINK_TIMEOUT}
     */
    public long prepareRead(long offset, int length, MemorySegment buffer, long timeoutMillis) throws JuringException {
        ensureOpen("read");
        validateReadArguments(offset, length, buffer);
        requireLinkedTimeout("read", timeoutMillis);
        ensureCapacity(2); // target + linked timeout
        long timespecAddress = allocateTimespec(timeoutMillis);
        long target = 0L;
        boolean targetPrepared = false;
        try {
            target = registeredSlot >= 0
                    ? ring.prepareRead(registeredSlot, buffer, length, offset, SqeOptions.IOSQE_IO_LINK)
                    : ring.prepareRead(fileDescriptor, buffer, length, offset, SqeOptions.IOSQE_IO_LINK);
            targetPrepared = true;
            long companion = ring.prepareLinkTimeout(timespecSegment(timespecAddress), 0);
            track(target, companion, timespecAddress, ROLE_NORMAL);
            trackCompanion(companion);
            return target;
        } catch (RuntimeException e) {
            NativeDispatcher.C.free(timespecAddress);
            if (targetPrepared) {
                // The target SQE is already queued with IOSQE_IO_LINK; track it as a plain
                // request so its completion is still reconciled by the ledger.
                track(target, 0L, 0L, ROLE_NORMAL);
            }
            throw e;
        }
    }

    /**
     * Single-shot write with a kernel linked timeout; see
     * {@link #prepareRead(long, int, MemorySegment, long)} for the frozen CQE semantics.
     *
     * @param offset      file offset
     * @param buffer      native segment with the bytes to write
     * @param timeoutMillis kernel timeout in milliseconds, {@code >= 0}
     * @return the write operation handle
     */
    public long prepareWrite(long offset, MemorySegment buffer, long timeoutMillis) throws JuringException {
        ensureOpen("write");
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset: " + offset);
        }
        JUring.validateNativeSegment(buffer, false);
        requireLinkedTimeout("write", timeoutMillis);
        ensureCapacity(2); // target + linked timeout
        long timespecAddress = allocateTimespec(timeoutMillis);
        long target = 0L;
        boolean targetPrepared = false;
        try {
            target = registeredSlot >= 0
                    ? ring.prepareWrite(registeredSlot, buffer, offset, SqeOptions.IOSQE_IO_LINK)
                    : ring.prepareWrite(fileDescriptor, buffer, offset, SqeOptions.IOSQE_IO_LINK);
            targetPrepared = true;
            long companion = ring.prepareLinkTimeout(timespecSegment(timespecAddress), 0);
            track(target, companion, timespecAddress, ROLE_NORMAL);
            trackCompanion(companion);
            return target;
        } catch (RuntimeException e) {
            NativeDispatcher.C.free(timespecAddress);
            if (targetPrepared) {
                track(target, 0L, 0L, ROLE_NORMAL);
            }
            throw e;
        }
    }

    /**
     * Single-shot read bounded by a kernel linked timeout: prepares
     * {@link #prepareRead(long, int, MemorySegment, long)} and awaits it. The kernel cancels
     * the read when the deadline expires, so this method returns or throws within the
     * timeout (plus scheduling overhead) even on a hung device.
     *
     * @return bytes read (short count at EOF); the read is not retried
     * @throws JuringException {@code ETIMEDOUT} when the kernel timeout won, {@code ECANCELED}
     *                         when the request was explicitly cancelled, or the real errno
     */
    public int read(long offset, MemorySegment buffer, long timeoutMillis) throws JuringException {
        long handle = prepareRead(offset, Math.toIntExact(Math.min(buffer.byteSize(), Integer.MAX_VALUE)),
                buffer, timeoutMillis);
        return await(handle);
    }

    /**
     * Single-shot write bounded by a kernel linked timeout; see
     * {@link #read(long, MemorySegment, long)}.
     *
     * @return bytes written; the write is not retried
     */
    public int write(long offset, MemorySegment buffer, long timeoutMillis) throws JuringException {
        long handle = prepareWrite(offset, buffer, timeoutMillis);
        return await(handle);
    }

    private void requireLinkedTimeout(String operation, long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        if (!ring.supportsTimeout() || !ring.supportsLinkTimeout()) {
            throw new UnsupportedOperationException("kernel does not support IORING_OP_TIMEOUT/IORING_OP_LINK_TIMEOUT"
                    + " required by JuringFile." + operation + " with a kernel timeout");
        }
    }

    private MemorySegment timespecSegment(long address) {
        return MemorySegment.ofAddress(address).reinterpret(JUring.TIMESPEC_BYTES);
    }

    /** Allocates a caller-managed native {@code struct __kernel_timespec} (16 bytes, relative). */
    private static long allocateTimespec(long timeoutMillis) {
        long address = NativeDispatcher.C.calloc(JUring.TIMESPEC_BYTES).address();
        MemorySegment ts = MemorySegment.ofAddress(address).reinterpret(JUring.TIMESPEC_BYTES);
        ts.set(JAVA_LONG, 0L, timeoutMillis / 1_000L);
        ts.set(JAVA_LONG, 8L, (timeoutMillis % 1_000L) * 1_000_000L);
        return address;
    }

    // ------------------------------------------------------------------
    // cancellation contract
    // ------------------------------------------------------------------

    /**
     * Cancels one in-flight operation and returns a deterministic status:
     * {@link CancelStatus#CANCELLED} (the kernel cancelled it; a later {@link #await(long)}
     * throws {@code ECANCELED}), {@link CancelStatus#ALREADY_COMPLETED} (the request's real
     * result is still delivered by {@code await}), or {@link CancelStatus#NOT_FOUND}.
     *
     * <p>The cancel request itself is an internal control request: it is tracked and reaped
     * before this method returns (its own completion is accounted in
     * {@link #controlOperations()}, not in {@link #issuedOperations()}). Every cancelled
     * request receives exactly one terminal completion; this method does not steal it.
     *
     * <p>For a timed operation ({@link #prepareRead(long, int, MemorySegment, long)}) the
     * target is cancelled and the kernel disarms the linked timeout with {@code -ECANCELED};
     * both completions are reconciled by the following {@code await}.
     *
     * @param handle operation handle returned by a {@code prepare*} method
     * @return the cancellation status
     * @throws JuringException          when the kernel rejects the cancel request (e.g. {@code EINVAL})
     * @throws IllegalStateException    when the handle is closed or the ring has foreign work
     */
    public CancelStatus cancel(long handle) throws JuringException {
        ensureOpen("cancel");
        if (handles == null) {
            return CancelStatus.NOT_FOUND;
        }
        checkRingOwnership("cancel");
        int slot = findSlot(handle);
        if (slot < 0) {
            return CancelStatus.NOT_FOUND;
        }
        if (states[slot] == DONE) {
            return CancelStatus.ALREADY_COMPLETED;
        }
        long cancelHandle = issueCancelControl(handle);
        int controlSlot = findSlot(cancelHandle);
        while (states[slot] != DONE || controlSlot < 0 || states[controlSlot] != DONE) {
            ring.submitAndWaitForBatchResult(1, completionConsumer);
            if (controlSlot < 0) {
                controlSlot = findSlot(cancelHandle);
            }
        }
        int cancelResult = results[controlSlot];
        releaseSlot(controlSlot);
        if (cancelResult == 0) {
            return CancelStatus.CANCELLED;
        }
        if (cancelResult == -JuringException.ENOENT || cancelResult == -EALREADY) {
            // The target won the race and completed before the cancel request ran; its real
            // result is still in the ledger and is delivered by await(handle).
            return CancelStatus.ALREADY_COMPLETED;
        }
        throw JuringException.fromResult(cancelResult, "cancel", path + " handle=" + handle);
    }

    /**
     * Cancels every in-flight operation owned by this handle and reaps all resulting
     * completions before returning: each request gets exactly one terminal completion
     * ({@code -ECANCELED} or its real result), and {@link #await(long)} on any previously
     * issued handle is deterministic afterwards.
     *
     * <p>Implementation: one bulk cancel by file
     * ({@code IORING_OP_ASYNC_CANCEL} with {@code ALL|FD[_FIXED]}) for file operations plus
     * per-handle cancels for the standalone timers, then a bounded drain
     * ({@link #closeTimeoutMillis}).
     *
     * @return number of {@code -ECANCELED} completions observed (includes disarmed linked
     *         timeouts of timed operations; ring-request granularity)
     * @throws JuringException       when the bounded drain did not observe all completions
     *                              (the remaining requests are still tracked and can be
     *                              awaited or closed)
     * @throws IllegalStateException when the handle is closed or the ring carries foreign work
     */
    public int cancelAll() throws JuringException {
        ensureOpen("cancelAll");
        if (handles == null || inFlight == 0) {
            return 0;
        }
        checkRingOwnership("cancelAll");
        long cancelledBefore = cancelledOperations;
        long deadline = System.nanoTime() + Math.max(0L, closeTimeoutMillis) * 1_000_000L;
        if (!issueCancelsBatched(deadline) || !drainUntilIdle(deadline)) {
            throw new JuringException(JuringException.ETIMEDOUT, "cancelAll",
                    "cancelAll did not observe terminal completions for all requests within "
                            + closeTimeoutMillis + " ms (" + inFlight + " request(s) still in flight)");
        }
        releaseControlSlots();
        return (int) (cancelledOperations - cancelledBefore);
    }

    /** Issues one cancel-by-user_data control request, retrying on SQ-full by submitting first. */
    private long issueCancelControl(long userData) {
        while (true) {
            ensureControlCapacity();
            try {
                long cancelHandle = ring.prepareCancelByUserData(userData);
                trackControl(cancelHandle);
                return cancelHandle;
            } catch (IllegalStateException sqFull) {
                // The argument validation throws IllegalStateException too, but for a
                // numeric user_data the only realistic cause is a full SQ ring.
                ring.submit();
            }
        }
    }

    /**
     * Issues the cancel requests for every pending user-visible request. The caller must hold
     * the ownership guard ({@code ring.inFlightCount() == inFlight}), which means every
     * in-flight request on the ring belongs to this handle; therefore a single ring-wide
     * {@code IORING_OP_ASYNC_CANCEL} with {@code ALL|ANY} is exact, safe and O(1) even with
     * tens of thousands of pending requests (verified: cancelling 10k timers takes a single
     * request). Requests that the kernel already finished race normally and still produce
     * exactly one terminal completion each.
     *
     * @return {@code false} when the deadline expires before the cancel request could be
     *         submitted
     */
    private boolean issueCancelsBatched(long deadlineNanos) {
        while (true) {
            boolean anyPending = false;
            for (int i = 0; i < ledgerCapacity; i++) {
                if (states[i] == PENDING && (roles[i] == ROLE_NORMAL || roles[i] == ROLE_TIMEOUT)) {
                    anyPending = true;
                    break;
                }
            }
            if (!anyPending) {
                ring.submit();
                return true;
            }
            ensureControlCapacity();
            try {
                long cancelHandle = ring.prepareCancelAny();
                trackControl(cancelHandle);
                ring.submit();
                return true;
            } catch (IllegalStateException sqFull) {
                // SQ full: submit what is queued (this also arms the pending requests so the
                // ring-wide cancel covers them), then retry.
                ring.submit();
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
        }
    }

    /** Waits (bounded, non-blocking polls) until no internal control request is still pending. */
    private boolean drainControlSlots(long deadlineNanos) {
        while (true) {
            boolean pendingControl = false;
            for (int i = 0; i < ledgerCapacity; i++) {
                if (states[i] == PENDING && roles[i] == ROLE_CONTROL) {
                    pendingControl = true;
                    break;
                }
            }
            if (!pendingControl) {
                return true;
            }
            if (ring.pollForBatchResult(Integer.MAX_VALUE, completionConsumer) > 0) {
                continue;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            LockSupport.parkNanos(Math.min(remaining, POLL_SLICE_NANOS));
        }
    }

    /** Frees the ledger slots of completed internal control requests. */
    private void releaseControlSlots() {
        for (int i = 0; i < ledgerCapacity; i++) {
            if (roles[i] == ROLE_CONTROL && states[i] == DONE) {
                releaseSlot(i);
            }
        }
    }

    /** Non-blocking drain: reaps until no request is in flight or the deadline expires. */
    private boolean drainUntilIdle(long deadlineNanos) {
        while (inFlight > 0) {
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            if (ring.pollForBatchResult(Integer.MAX_VALUE, completionConsumer) > 0) {
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
            if (isTerminal(slot)) {
                return consume(slot);
            }
            if (deadlineNanos > 0) {
                // Bounded caller-side await: the operation STAYS IN FLIGHT on timeout. The
                // non-blocking poll also flushes IORING_SETUP_DEFER_TASKRUN task work.
                int reaped = ring.pollForBatchResult(Integer.MAX_VALUE, completionConsumer);
                if (reaped == 0) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        throw new JuringException(JuringException.ETIMEDOUT, "await",
                                "timed out waiting for operation handle " + handle
                                        + " (the operation is still in flight; await/drain it or close the handle)");
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

    /**
     * @return number of operations abandoned at close because they could not be reaped
     *         (drain deadline + grace expired, or foreign work on the ring made reaping
     *         unsafe). Abandoned requests are counted, never silent; their completions may
     *         still be delivered through the ring's CQ.
     */
    public int abandonedOperations() {
        return (int) abandonedOperations;
    }

    /**
     * @return number of terminal {@code -ECANCELED} completions observed by this handle
     *         (explicitly cancelled requests, disarmed linked timeouts of timed operations,
     *         and requests cancelled by {@link #close(long)})
     */
    public long cancelledOperations() {
        return cancelledOperations;
    }

    /**
     * @return number of internal cancel control requests issued by this handle
     *         ({@link #cancel(long)}, {@link #cancelAll()}, {@link #close(long)}); these are
     *         tracked and reaped but are not user-visible operations
     */
    public long controlOperations() {
        return controlOperations;
    }

    /**
     * @return the number of {@code fsync}/{@code fdatasync} ({@link OperationType#FSYNC})
     *         requests prepared through this handle, including syncs issued by the buffered
     *         streams and group commit. Owner-thread counter; excludes {@code fadvise},
     *         which is not a ring request.
     */
    public long syncOperations() {
        return syncOperations;
    }

    /**
     * @return the number of {@code fsync}/{@code fdatasync} ({@link OperationType#FSYNC})
     *         completions reaped through this handle (successful or failed). Owner-thread
     *         counter; the delta against {@link #syncOperations()} shows issued syncs still
     *         in flight.
     */
    public long syncCompletions() {
        return syncCompletions;
    }

    // ------------------------------------------------------------------
    // close
    // ------------------------------------------------------------------

    /**
     * Closes the handle, draining in-flight operations with the configured deadline
     * ({@link #DEFAULT_CLOSE_TIMEOUT_MILLIS} by default), cancelling what cannot be drained,
     * reconciling the cancelled completions, unregistering the fixed-file slot and closing the
     * descriptor. Never throws because an operation failed, and is idempotent.
     *
     * <p><b>Bounded shutdown contract:</b> the method returns after at most
     * {@code timeoutMillis + GRACE_NANOS} (250 ms) when cancellation is required; it returns
     * earlier when the drain succeeds. If the ring carries foreign in-flight work (or the
     * ring is already closed) reaping would steal/miss completions, so this handle's pending
     * requests are cancelled only when their completions can be reaped; otherwise they are
     * abandoned and counted in {@link #abandonedOperations()} (their completions may still be
     * delivered to the ring's owner).
     */
    @Override
    public void close() {
        close(closeTimeoutMillis);
    }

    /**
     * Closes the handle with an explicit drain deadline; see {@link #close()}.
     *
     * @param timeoutMillis graceful-drain deadline in milliseconds, {@code >= 0}; the total
     *                      bound is {@code timeoutMillis + 250 ms} when a cancel/grace phase
     *                      is needed
     */
    public void close(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("close timeout must be >= 0: " + timeoutMillis);
        }
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
            } else {
                // The ring is already gone: no completion can be reaped or cancelled here.
                abandonRemaining();
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
        if (states == null || inFlight == 0) {
            return;
        }
        // Hard bound for the whole close (drain + cancel + grace): documented as
        // timeoutMillis + GRACE_NANOS. The shutdown path never waits past it.
        long hardDeadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L + GRACE_NANOS;
        int ringInFlight = ring.inFlightCount();
        if (ringInFlight > inFlight) {
            // Foreign in-flight work on the ring: reaping would steal another owner's
            // completions, and cancelling would create cancel CQEs we cannot reap (they would
            // grow the ring in-flight counter forever). Count our own requests as abandoned
            // and leave their completions to the ring's owner (B1 foreign-work rule).
            abandonRemaining();
            return;
        }
        if (ringInFlight < inFlight) {
            // Some completions were reaped outside this handle; the ledger can no longer be
            // reconciled, so count the remainder as abandoned rather than hang.
            abandonRemaining();
            return;
        }
        ring.submit();
        long drainDeadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        if (drainUntilIdle(drainDeadline)) {
            releaseControlSlots();
            resetLedger();
            return;
        }
        // Deadline expired: cancel everything we own, then reap the cancellations with the
        // remaining part of the hard bound. Cancelled requests are reaped/reconciled here;
        // only a request that still has not completed when the bound is reached is abandoned
        // (and counted).
        long cancellationBudget = hardDeadline - System.nanoTime();
        if (cancellationBudget > 0
                && issueCancelsBatched(hardDeadline)
                && drainUntilIdle(hardDeadline)) {
            releaseControlSlots();
            resetLedger();
            return;
        }
        ring.pollForBatchResult(Integer.MAX_VALUE, completionConsumer);
        releaseControlSlots();
        abandonRemaining();
    }

    private void abandonRemaining() {
        if (states == null) {
            inFlight = 0;
            return;
        }
        int abandonedNow = 0;
        for (int i = 0; i < ledgerCapacity; i++) {
            if (states[i] == PENDING && roles[i] != ROLE_CONTROL) {
                abandonedNow++;
            }
        }
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
            freeTimespec(i);
            states[i] = FREE;
            handles[i] = 0L;
            results[i] = 0;
            kinds[i] = 0;
            companionHandles[i] = 0L;
            roles[i] = ROLE_NORMAL;
        }
        freeTop = ledgerCapacity;
        for (int i = 0; i < ledgerCapacity; i++) {
            freeSlots[i] = i;
        }
    }

    private void freeTimespec(int slot) {
        long address = timespecAddresses[slot];
        if (address != 0L) {
            timespecAddresses[slot] = 0L;
            NativeDispatcher.C.free(address);
        }
    }

    /** Releases one ledger slot (frees any owned timespec) without touching counters. */
    private void releaseSlot(int slot) {
        freeTimespec(slot);
        states[slot] = FREE;
        handles[slot] = 0L;
        results[slot] = 0;
        kinds[slot] = 0;
        companionHandles[slot] = 0L;
        roles[slot] = ROLE_NORMAL;
        freeSlots[freeTop++] = slot;
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
        companionHandles = new long[capacity];
        roles = new byte[capacity];
        timespecAddresses = new long[capacity];
        freeTop = capacity;
        for (int i = 0; i < capacity; i++) {
            freeSlots[i] = i;
        }
    }

    private void ensureCapacity() {
        ensureCapacity(1);
    }

    private void ensureCapacity(int slots) {
        ensureLedger();
        if (freeTop < slots) {
            throw new IllegalStateException("JuringFile in-flight capacity exhausted (" + ledgerCapacity
                    + " slots, " + slots + " needed by this operation); await or drain completions,"
                    + " or raise withInFlightCapacity before the first operation");
        }
    }

    private void track(long handle) {
        track(handle, 0L, 0L, ROLE_NORMAL);
    }

    /** Tracks one user-visible ring request, optionally paired with a linked-timeout companion. */
    private void track(long handle, long companionHandle, long timespecAddress, byte role) {
        int slot = freeSlots[--freeTop];
        handles[slot] = handle;
        states[slot] = PENDING;
        companionHandles[slot] = companionHandle;
        timespecAddresses[slot] = timespecAddress;
        roles[slot] = role;
        inFlight++;
        issuedOperations++;
    }

    /**
     * Tracks the {@code IORING_OP_LINK_TIMEOUT} companion of a timed operation. It is a real
     * ring request (counted in {@code issuedOperations}/{@code completedOperations}) but has
     * no user-facing handle; its terminal result is folded into the target's await.
     */
    private void trackCompanion(long handle) {
        int slot = freeSlots[--freeTop];
        handles[slot] = handle;
        states[slot] = PENDING;
        roles[slot] = ROLE_COMPANION;
        inFlight++;
        issuedOperations++;
    }

    /** Tracks an internal cancel control request (not user-visible; separate counters). */
    private void trackControl(long handle) {
        int slot = freeSlots[--freeTop];
        handles[slot] = handle;
        states[slot] = PENDING;
        roles[slot] = ROLE_CONTROL;
        inFlight++;
        controlOperations++;
    }

    private void ensureControlCapacity() {
        ensureLedger();
        if (freeTop == 0) {
            throw new IllegalStateException("JuringFile in-flight capacity exhausted (" + ledgerCapacity
                    + ") while issuing an internal cancel request");
        }
    }

    private void onCompletion(Result result) {
        long handle = result.id();
        int value = (int) result.result();
        byte kind = (byte) result.type().getIndex();
        for (int i = 0; i < ledgerCapacity; i++) {
            if (handles[i] != handle || states[i] == FREE) {
                continue;
            }
            if (states[i] == DONE) {
                return; // duplicate completion for an already-recorded request: ignore
            }
            states[i] = DONE;
            results[i] = value;
            kinds[i] = kind;
            inFlight--;
            if (roles[i] == ROLE_CONTROL) {
                controlCompletions++;
                return;
            }
            completedOperations++;
            if (kind == FSYNC_KIND) {
                // B6: a terminal fsync/fdatasync completion reaped through this handle.
                syncCompletions++;
            }
            if (value < 0) {
                failedOperations++;
                // A linked timeout disarmed by a winning target always completes -ECANCELED;
                // that is the normal timed-operation fast path, not a cancellation, so only
                // target/timeout requests (not companions) count as cancelled operations.
                if (value == -JuringException.ECANCELED && roles[i] != ROLE_COMPANION) {
                    cancelledOperations++;
                }
            }
            return;
        }
        // Stray timer completions (e.g. from a shared/foreign ring) carry no file operation and
        // are ignored instead of being counted as foreign work (B1 behaviour preserved).
        if (result.type() == OperationType.TIMEOUT || result.type() == OperationType.LINK_TIMEOUT) {
            return;
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

    /** @return true when the ledger slot's request and (for a timed pair) its companion are terminal */
    private boolean isTerminal(int slot) {
        if (states[slot] != DONE) {
            return false;
        }
        long companion = companionHandles[slot];
        if (companion == 0L) {
            return true;
        }
        int companionSlot = findSlot(companion);
        return companionSlot < 0 || states[companionSlot] == DONE;
    }

    private int consume(int slot) throws JuringException {
        int value = results[slot];
        long handle = handles[slot];
        byte kind = kinds[slot];
        long companion = companionHandles[slot];
        int companionResult = 0;
        if (companion != 0L) {
            int companionSlot = findSlot(companion);
            if (companionSlot >= 0) {
                companionResult = results[companionSlot];
                releaseSlot(companionSlot);
            }
        }
        releaseSlot(slot);
        if (value == -JuringException.ETIME) {
            // Kernel IORING_OP_TIMEOUT completions use ETIME(62); the file API maps them to
            // the documented JuringException.ETIMEDOUT(110) taxonomy.
            throw new JuringException(JuringException.ETIMEDOUT, operationName(kind),
                    "timed out (" + path + " handle=" + handle + ")");
        }
        if (value == -JuringException.ECANCELED && companion != 0L && companionResult == -JuringException.ETIME) {
            throw new JuringException(JuringException.ETIMEDOUT, operationName(kind),
                    "linked timeout expired (" + path + " handle=" + handle + ")");
        }
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
