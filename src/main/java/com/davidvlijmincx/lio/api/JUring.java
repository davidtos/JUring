package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Single-threaded Java API for Linux {@code io_uring} direct file I/O.
 *
 * <p>A {@code JUring} wraps one io_uring instance: a submission queue and a completion
 * queue mapped from the kernel. Operations are <em>prepared</em> as submission queue
 * entries on the owner thread (each {@code prepare*} method returns an operation handle),
 * <em>submitted</em> with {@link #submit()} or one of the {@code *AndWait*} methods, and
 * their completions are <em>reaped</em> as {@link Result} objects. A ring is confined to a
 * single owner thread: it is not thread-safe, and the owner thread must prepare, submit
 * and reap. Use one ring per thread, or {@link IoShard}/{@link RingGroup} for a managed
 * multi-ring layout. The ownership contract is enforced: data-plane and registration methods
 * called from a foreign thread throw {@link WrongThreadException}, and {@link #close(long)}
 * may only be called by the owner while the ring is open. {@link #ringMetrics()} (and the
 * post-close counters) remain readable from any thread.
 *
 * <p>Registrations are explicit and lifecycle-bound to the ring: registered buffers
 * ({@link #registerBuffers(int, int)}), the sparse registered-file table
 * ({@link #registerFileAdd(FileDescriptor)} and friends) and the optional read-buffer pool
 * ({@link #setupReadBufferPool(int, int)}) must be released before the ring is closed, and
 * the registered memory must stay valid for as long as the ring uses it. Closing the ring
 * ({@link #close()}) is bounded and idempotent.
 *
 * <p>Operations that take a {@link FileDescriptor} submitted with the registered-file slot
 * overloads (an {@code int indexFD} argument) require the slot to have been registered with
 * {@link #registerFileAdd(FileDescriptor)} or {@link #registerFiles(FileDescriptor...)}.
 *
 * <p>Completion results are reported exactly once per submitted SQE. A completion's
 * {@link Result#result()} is the transfer size, {@code 0} or the new file descriptor for
 * operations that produce one, or the negative errno on failure.
 */
public class JUring implements AutoCloseable {

    /**
     * Tests whether this JVM can create an {@code io_uring}: Linux on x86-64 (amd64) or aarch64,
     * with io_uring enabled for the process. The first call performs a minimal
     * {@code io_uring_setup} probe and caches the result; later calls are free.
     *
     * <p>Returns {@code false} instead of throwing for unsupported platforms and for
     * environments whose security policy denies io_uring (Docker's default seccomp profile,
     * {@code kernel.io_uring_disabled}, AppArmor). Libraries should use this to fall back to
     * {@code java.nio} instead of failing at startup; {@code new JUring(...)} still throws an
     * {@link UnsupportedOperationException} on such platforms with the errno-free reason.
     *
     * @return whether a ring can be created in this process
     */
    public static boolean isSupported() {
        if (!PlatformSupport.isSupportedPlatform()) {
            return false;
        }
        return NativeDispatcher.isSupported();
    }

    /** {@code IORING_FSYNC_DATASYNC}: {@code fsync} flag requesting {@code fdatasync} (data only). */
    public static final int IORING_FSYNC_DATASYNC = 1;

    /** {@code IORING_TIMEOUT_ABS}: the {@code __kernel_timespec} is an absolute CLOCK_MONOTONIC time. */
    public static final int IORING_TIMEOUT_ABS = 1;
    /** {@code IORING_TIMEOUT_UPDATE}: update an existing timeout request instead of arming a new one. */
    public static final int IORING_TIMEOUT_UPDATE = 1 << 1;
    /** {@code IORING_TIMEOUT_BOOTTIME}: interpret the timeout against CLOCK_BOOTTIME. */
    public static final int IORING_TIMEOUT_BOOTTIME = 1 << 2;
    /** {@code IORING_TIMEOUT_REALTIME}: interpret the timeout against CLOCK_REALTIME. */
    public static final int IORING_TIMEOUT_REALTIME = 1 << 3;
    /** {@code IORING_LINK_TIMEOUT_UPDATE}: update the linked timeout from within the target. */
    public static final int IORING_LINK_TIMEOUT_UPDATE = 1 << 4;
    /** {@code IORING_TIMEOUT_ETIME_SUCCESS}: an expired timeout completes with {@code 0} instead of {@code -ETIME}. */
    public static final int IORING_TIMEOUT_ETIME_SUCCESS = 1 << 5;
    /** {@code IORING_TIMEOUT_MULTISHOT}: the timeout re-arms and can complete more than once. */
    public static final int IORING_TIMEOUT_MULTISHOT = 1 << 6;

    /** {@code IORING_ASYNC_CANCEL_ALL}: cancel every matching request, not just one. */
    public static final int IORING_ASYNC_CANCEL_ALL = IoUringDispatcher.IORING_ASYNC_CANCEL_ALL;
    /** {@code IORING_ASYNC_CANCEL_FD}: match the target by file instead of {@code user_data}. */
    public static final int IORING_ASYNC_CANCEL_FD = IoUringDispatcher.IORING_ASYNC_CANCEL_FD;
    /** {@code IORING_ASYNC_CANCEL_ANY}: match any in-flight request. */
    public static final int IORING_ASYNC_CANCEL_ANY = IoUringDispatcher.IORING_ASYNC_CANCEL_ANY;
    /** {@code IORING_ASYNC_CANCEL_FD_FIXED}: the cancel fd is a registered-file slot. */
    public static final int IORING_ASYNC_CANCEL_FD_FIXED = IoUringDispatcher.IORING_ASYNC_CANCEL_FD_FIXED;

    /** Default deadline used by {@link #close()} before the ring is force-closed. */
    public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;

    /** Default capacity of the lazily initialised sparse registered-file table. */
    static final int DEFAULT_FILE_TABLE_CAPACITY = 64;

    /** {@code AT_EMPTY_PATH}: operate on the file referred to by the dirfd argument itself. */
    static final int AT_EMPTY_PATH = 0x1000;

    /** {@code STATX_BASIC_STATS}: the basic statx field set, including {@code stx_size}. */
    static final int STATX_BASIC_STATS = 0x7ff;

    private final IoUringDispatcher ioUring;
    private final List<MemorySegment> registeredBuffers;

    private MemorySegment[] registeredBuffersArray = new MemorySegment[0];
    private long[] registeredBufferAddresses = new long[0];
    private long[] registeredBufferSizes = new long[0];
    private boolean[] bufferInUse;

    private int[] freeBufferStack;
    private int freeBufferTop;
    private ReadBufferPool readBufferPool;

    // Sparse registered-file table tracking: fd per slot (-1 = empty slot).
    private int[] registeredFileTable = new int[0];

    private long opSeq = 1;
    private short defaultIoprio;
    private long lastSqeAddress;
    private long previousSqeAddress;
    /** B10: {@code JuringFile} handles attached to this ring (grows at open, shrinks at close). */
    private JuringFile[] attachedFiles = new JuringFile[0];
    private int attachedFileCount;
    private volatile Thread ownerThread;
    private volatile boolean closed;
    private long cancelledAtClose;
    private int abandonedAtClose;
    /** B5: final frozen ring-metrics snapshot captured on close (design §2.1 after-close rule). */
    private volatile RingMetrics frozenRingMetrics;

    /**
     * Creates a ring with the given submission queue depth and setup flags.
     *
     * <p>The ring is owned by the calling thread; see the class documentation for the
     * ownership contract. Closing the ring releases its kernel and off-heap resources.
     *
     * @param queueDepth   number of submission queue entries (the completion queue defaults
     *                     to twice this value)
     * @param ioUringFlags {@link IoUringOptions} setup flags, combined with bitwise OR
     * @throws RuntimeException when {@code io_uring_setup} fails
     */
    public JUring(int queueDepth, IoUringOptions... ioUringFlags) {
        this(queueDepth, 0, ioUringFlags);
    }

    /**
     * Creates a ring with the given queue depth, SQPOLL idle time and setup flags.
     *
     * <p>The ring is owned by the calling thread; see the class documentation for the
     * ownership contract.
     *
     * @param queueDepth     number of submission queue entries
     * @param sqThreadIdleMs idle time in milliseconds before the SQPOLL kernel thread
     *                       sleeps; only meaningful with
     *                       {@link IoUringOptions#IORING_SETUP_SQPOLL}
     * @param ioUringFlags   {@link IoUringOptions} setup flags, combined with bitwise OR
     * @throws RuntimeException when {@code io_uring_setup} fails
     */
    public JUring(int queueDepth, int sqThreadIdleMs, IoUringOptions... ioUringFlags) {
        PlatformSupport.requireSupportedPlatform();
        ioUring = NativeDispatcher.getUringInstance(queueDepth, sqThreadIdleMs, ioUringFlags);
        this.ownerThread = Thread.currentThread();
        registeredBuffers = new ArrayList<>();
        freeBufferStack = new int[0];
        freeBufferTop = 0;
    }

    private JUring(IoUringDispatcher ioUring) {
        this.ioUring = ioUring;
        this.ownerThread = Thread.currentThread();
        registeredBuffers = new ArrayList<>();
        freeBufferStack = new int[0];
        freeBufferTop = 0;
    }

    /**
     * Creates a second ring that shares this ring's asynchronous-worker pool
     * ({@code IORING_SETUP_ATTACH_WQ}).
     *
     * <p>The returned ring is independent for submission and reaping and has its own
     * metrics; it must be closed by its caller and is confined to the calling thread.
     *
     * @param queueDepth     number of submission queue entries of the new ring
     * @param ioUringOptions additional {@link IoUringOptions} setup flags
     * @return a new ring attached to this ring's worker pool
     * @throws RuntimeException when the attached ring cannot be created
     */
    public JUring getSharedWorkerRing(int queueDepth, IoUringOptions... ioUringOptions) {
        ensureOpenAndOwner();
        var ring = this.ioUring.getSharedWorkerRing(queueDepth, ioUringOptions);
        return new JUring(ring);
    }

    /**
     * Prepares a read of {@code readSize} bytes at file offset {@code offset} into a buffer
     * allocated by the ring.
     *
     * <p>The buffer is allocated at preparation time, exposed by the completion's
     * {@link Result#buffer()} and released by {@link Result#close()}.
     *
     * @param fd       file to read
     * @param readSize maximum number of bytes to read
     * @param offset   file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when {@code readSize} is negative
     * @throws IllegalStateException    when the ring is closed or the submission queue is full
     */
    public long prepareRead(FileDescriptor fd, int readSize, long offset) {
        return prepareReadInternal(fd.getFd(), readSize, offset, (byte) 0, false);
    }

    /**
     * Prepares a read of {@code readSize} bytes at {@code offset} into a ring-allocated
     * buffer, with explicit SQE flags.
     *
     * @param fd         file to read
     * @param readSize   maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareRead(FileDescriptor fd, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), readSize, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a read into the whole of {@code buffer} at file offset {@code offset}.
     *
     * <p>The buffer must be native, alive and accessible by the calling thread, and must
     * stay valid until the completion is reaped.
     *
     * @param fd     file to read
     * @param buffer destination buffer; its size determines the read size
     * @param offset file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the segment is not native writeable memory
     * @throws IllegalStateException    when the ring is closed or the segment scope is closed
     * @throws WrongThreadException     when the segment is confined to another thread
     */
    public long prepareRead(FileDescriptor fd, MemorySegment buffer, long offset) {
        return prepareReadInternal(fd.getFd(), buffer, Math.toIntExact(buffer.byteSize()), offset, (byte) 0, false);
    }

    /**
     * Prepares a read into the whole of {@code buffer} at {@code offset}, with explicit SQE
     * flags.
     *
     * @param fd         file to read
     * @param buffer     destination buffer; its size determines the read size
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareRead(FileDescriptor fd, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), buffer, Math.toIntExact(buffer.byteSize()), offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a read of at most {@code readSize} bytes into the start of {@code buffer} at
     * file offset {@code offset}.
     *
     * @param fd       file to read
     * @param buffer   destination buffer
     * @param readSize maximum number of bytes to read; must not exceed the buffer size
     * @param offset   file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when {@code readSize} is negative or larger than the buffer
     */
    public long prepareRead(FileDescriptor fd, MemorySegment buffer, int readSize, long offset) {
        return prepareReadInternal(fd.getFd(), buffer, readSize, offset, (byte) 0, false);
    }

    /**
     * Prepares a read of at most {@code readSize} bytes into {@code buffer} at {@code offset},
     * with explicit SQE flags.
     *
     * @param fd         file to read
     * @param buffer     destination buffer
     * @param readSize   maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareRead(FileDescriptor fd, MemorySegment buffer, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), buffer, readSize, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a read of {@code readSize} bytes from a registered-file slot into a
     * ring-allocated buffer.
     *
     * @param indexFD  registered-file slot number (submitted with {@code IOSQE_FIXED_FILE})
     * @param readSize maximum number of bytes to read
     * @param offset   file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when {@code readSize} is negative
     * @throws IllegalStateException    when the ring is closed or the submission queue is full
     */
    public long prepareRead(int indexFD, int readSize, long offset) {
        return prepareReadInternal(indexFD, readSize, offset, (byte) 0, true);
    }

    /**
     * Prepares a read of {@code readSize} bytes from a registered-file slot into a
     * ring-allocated buffer, with explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param readSize   maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareRead(int indexFD, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, readSize, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Prepares a read from a registered-file slot into the whole of {@code buffer}.
     *
     * @param indexFD registered-file slot number
     * @param buffer  destination buffer; its size determines the read size
     * @param offset  file offset in bytes
     * @return the operation handle ({@code user_data})
     */
    public long prepareRead(int indexFD, MemorySegment buffer, long offset) {
        return prepareReadInternal(indexFD, buffer, Math.toIntExact(buffer.byteSize()), offset, (byte) 0, true);
    }

    /**
     * Prepares a read from a registered-file slot into the whole of {@code buffer}, with
     * explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param buffer     destination buffer; its size determines the read size
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareRead(int indexFD, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, buffer, Math.toIntExact(buffer.byteSize()), offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Prepares a read of at most {@code readSize} bytes from a registered-file slot into
     * {@code buffer}.
     *
     * @param indexFD  registered-file slot number
     * @param buffer   destination buffer
     * @param readSize maximum number of bytes to read
     * @param offset   file offset in bytes
     * @return the operation handle ({@code user_data})
     */
    public long prepareRead(int indexFD, MemorySegment buffer, int readSize, long offset) {
        return prepareReadInternal(indexFD, buffer, readSize, offset, (byte) 0, true);
    }

    /**
     * Prepares a read of at most {@code readSize} bytes from a registered-file slot into
     * {@code buffer}, with explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param buffer     destination buffer
     * @param readSize   maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareRead(int indexFD, MemorySegment buffer, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, buffer, readSize, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Configures the read-buffer pool used by {@link #prepareReadPooled}.
     *
     * <p>The pool holds {@code capacity} off-heap buffers of {@code bufferSize} bytes each.
     * Calling this method again replaces and closes the previous pool.
     *
     * @param bufferSize size of one pooled buffer in bytes
     * @param capacity   number of buffers in the pool
     * @throws IllegalArgumentException when {@code bufferSize} is not positive or
     *                                  {@code capacity} is negative
     */
    public void setupReadBufferPool(int bufferSize, int capacity) {
        ensureOpenAndOwner();
        if (bufferSize <= 0 || capacity < 0) {
            throw new IllegalArgumentException("Invalid read buffer pool configuration: bufferSize=" + bufferSize + ", capacity=" + capacity);
        }
        if (readBufferPool != null) {
            int outstanding = readBufferPool.outstanding();
            if (outstanding > 0) {
                throw new IllegalStateException("Cannot replace the read buffer pool while "
                        + outstanding + " pooled buffer(s) are still checked out");
            }
            readBufferPool.close();
        }
        readBufferPool = new ReadBufferPool(capacity, bufferSize);
    }

    /**
     * Prepares a read of {@code readSize} bytes into a buffer checked out of the read-buffer
     * pool.
     *
     * <p>The buffer is checked out at preparation time; after reaping the completion the
     * caller returns it with {@link #checkInReadBuffer(Result)}.
     *
     * @param fd       file to read
     * @param readSize maximum number of bytes to read
     * @param offset   file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalStateException    when no read-buffer pool was configured
     * @throws IllegalArgumentException when {@code readSize} is out of the pool buffer range
     */
    public long prepareReadPooled(FileDescriptor fd, int readSize, long offset) {
        return prepareReadPooledInternal(fd.getFd(), readSize, offset, (byte) 0, false);
    }

    /**
     * Prepares a read into a buffer checked out of the read-buffer pool, with explicit SQE
     * flags.
     *
     * @param fd         file to read
     * @param readSize   maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareReadPooled(FileDescriptor fd, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadPooledInternal(fd.getFd(), readSize, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a read from a registered-file slot into a buffer checked out of the
     * read-buffer pool.
     *
     * @param indexFD  registered-file slot number
     * @param readSize maximum number of bytes to read
     * @param offset   file offset in bytes
     * @return the operation handle ({@code user_data})
     */
    public long prepareReadPooled(int indexFD, int readSize, long offset) {
        return prepareReadPooledInternal(indexFD, readSize, offset, (byte) 0, true);
    }

    /**
     * Prepares a read from a registered-file slot into a pooled buffer, with explicit SQE
     * flags.
     *
     * @param indexFD    registered-file slot number
     * @param readSize   maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareReadPooled(int indexFD, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadPooledInternal(indexFD, readSize, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Returns a pooled buffer to the read-buffer pool after its completion was reaped.
     *
     * @param result completion whose buffer was checked out by {@link #prepareReadPooled}
     * @throws IllegalArgumentException when {@code result} is {@code null} or is not a
     *                                  {@link OperationType#READ_POOLED} completion
     */
    public void checkInReadBuffer(Result result) {
        ensureOpenAndOwner();
        if (result == null) {
            throw new IllegalArgumentException("Result cannot be null");
        }
        if (result.type() != OperationType.READ_POOLED) {
            throw new IllegalArgumentException("Result must be of type READ_POOLED");
        }
        long address = result.bufferAddress();
        if (address != 0L) {
            checkInReadBuffer(address);
        }
    }

    void checkInReadBuffer(long address) {
        if (readBufferPool != null) {
            readBufferPool.checkIn(address);
        }
    }

    /**
     * Prepares a read of {@code readSize} bytes into a registered fixed buffer.
     *
     * <p>The buffer must have been registered with {@link #registerBuffers(int, int)}; the
     * completion reports the buffer index in {@link Result#bufferIdx()}.
     *
     * @param fd          file to read
     * @param readSize    maximum number of bytes to read
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered destination buffer
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the index or size is out of range
     */
    public long prepareReadFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex) {
        return prepareReadFixedInternal(fd.getFd(), readSize, offset, bufferIndex, (byte) 0, false);
    }

    /**
     * Prepares a read into a registered fixed buffer, with explicit SQE flags.
     *
     * @param fd          file to read
     * @param readSize    maximum number of bytes to read
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered destination buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareReadFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareReadFixedInternal(fd.getFd(), readSize, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a read from a registered-file slot into a registered fixed buffer.
     *
     * @param indexFD     registered-file slot number
     * @param readSize    maximum number of bytes to read
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered destination buffer
     * @return the operation handle ({@code user_data})
     */
    public long prepareReadFixed(int indexFD, int readSize, long offset, int bufferIndex) {
        return prepareReadFixedInternal(indexFD, readSize, offset, bufferIndex, (byte) 0, true);
    }

    /**
     * Prepares a read from a registered-file slot into a registered fixed buffer, with
     * explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param readSize   maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param bufferIndex index of the registered destination buffer
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareReadFixed(int indexFD, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareReadFixedInternal(indexFD, readSize, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Prepares a no-op operation that completes immediately; useful to probe reaping.
     *
     * @return the operation handle ({@code user_data})
     */
    public long prepareNop() {
        return prepareNopInternal((byte) 0);
    }

    /**
     * Prepares a no-op operation with explicit SQE flags.
     *
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareNop(SqeOptions... sqeOptions) {
        return prepareNopInternal(SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareNopInternal(byte flags) {
        long sqe = getSqe(flags, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.NOP, opId);
        ioUring.prepareNop(sqe);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /**
     * Prepares a write of {@code bytes} at file offset {@code offset}.
     *
     * <p>The bytes are copied into a native buffer at preparation time and that buffer is
     * released by the ring when the completion is reaped; the array is not retained.
     *
     * @param fd     file to write
     * @param bytes  bytes to write
     * @param offset file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalStateException when the ring is closed or the submission queue is full
     */
    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, (byte) 0, false);
    }

    /**
     * Prepares a write of {@code bytes} at {@code offset}, with explicit SQE flags.
     *
     * @param fd         file to write
     * @param bytes      bytes to write
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a zero-copy write of {@code bytes} at file offset {@code offset}.
     *
     * <p>The segment must be native, alive and accessible by the calling thread, and must
     * stay valid until the completion is reaped.
     *
     * @param fd     file to write
     * @param bytes  source bytes; pinned for the duration of the operation
     * @param offset file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the segment is not native memory
     * @throws IllegalStateException    when the ring is closed or the segment scope is closed
     * @throws WrongThreadException     when the segment is confined to another thread
     */
    public long prepareWrite(FileDescriptor fd, MemorySegment bytes, long offset) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, (byte) 0, false);
    }

    /**
     * Prepares a zero-copy write of {@code bytes} at {@code offset}, with explicit SQE flags.
     *
     * @param fd         file to write
     * @param bytes      source bytes; must stay valid until the completion is reaped
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWrite(FileDescriptor fd, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a write of {@code bytes} at file offset {@code offset} on a registered-file
     * slot.
     *
     * @param indexFD registered-file slot number
     * @param bytes   bytes to write
     * @param offset  file offset in bytes
     * @return the operation handle ({@code user_data})
     */
    public long prepareWrite(int indexFD, byte[] bytes, long offset) {
        return prepareWriteInternal(indexFD, bytes, offset, (byte) 0, true);
    }

    /**
     * Prepares a write of {@code bytes} on a registered-file slot, with explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param bytes      bytes to write
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWrite(int indexFD, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(indexFD, bytes, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Prepares a zero-copy write of {@code bytes} on a registered-file slot.
     *
     * @param indexFD registered-file slot number
     * @param bytes   source bytes; must stay valid until the completion is reaped
     * @param offset  file offset in bytes
     * @return the operation handle ({@code user_data})
     */
    public long prepareWrite(int indexFD, MemorySegment bytes, long offset) {
        return prepareWriteInternal(indexFD, bytes, offset, (byte) 0, true);
    }

    /**
     * Prepares a zero-copy write on a registered-file slot, with explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param bytes      source bytes; must stay valid until the completion is reaped
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWrite(int indexFD, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(indexFD, bytes, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Prepares a write from a registered fixed buffer, copying {@code bytes} into it first.
     *
     * @param fd          file to write
     * @param bytes       bytes to write; copied into the registered buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the index is out of range or the bytes do not fit
     */
    public long prepareWriteFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, (byte) 0, false);
    }

    /**
     * Prepares a write from a registered fixed buffer, copying {@code bytes} into it first,
     * with explicit SQE flags.
     *
     * @param fd          file to write
     * @param bytes       bytes to write; copied into the registered buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a write from a registered fixed buffer on a registered-file slot, copying
     * {@code bytes} into the buffer first.
     *
     * @param indexFD     registered-file slot number
     * @param bytes       bytes to write
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(int indexFD, byte[] bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, (byte) 0, true);
    }

    /**
     * Prepares a write from a registered fixed buffer on a registered-file slot, with
     * explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param bytes      bytes to write
     * @param offset     file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(int indexFD, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Prepares a write from a registered fixed buffer.
     *
     * <p>The bytes are copied into the registered buffer unless {@code bytes} is itself the
     * registered buffer at {@code bufferIndex} and has the same address, in which case the
     * write is zero-copy.
     *
     * @param fd          file to write
     * @param bytes       source bytes; must stay valid until the completion is reaped
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the index is out of range or the bytes do not fit
     */
    public long prepareWriteFixed(FileDescriptor fd, MemorySegment bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, (byte) 0, false);
    }

    /**
     * Prepares a write from a registered fixed buffer, with explicit SQE flags.
     *
     * @param fd          file to write
     * @param bytes       source bytes; must stay valid until the completion is reaped
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(FileDescriptor fd, MemorySegment bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a write from a registered fixed buffer on a registered-file slot.
     *
     * @param indexFD     registered-file slot number
     * @param bytes       source bytes; must stay valid until the completion is reaped
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(int indexFD, MemorySegment bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, (byte) 0, true);
    }

    /**
     * Prepares a write from a registered fixed buffer on a registered-file slot, with
     * explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param bytes      source bytes; must stay valid until the completion is reaped
     * @param offset     file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(int indexFD, MemorySegment bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Prepares a zero-copy write of the first {@code writeSize} bytes of a registered fixed
     * buffer.
     *
     * <p>The caller must have filled the registered buffer with the data to write.
     *
     * @param fd          file to write
     * @param writeSize   number of bytes to write from the buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the index or size is out of range
     */
    public long prepareWriteFixed(FileDescriptor fd, int writeSize, long offset, int bufferIndex) {
        return prepareWriteFixedZeroCopyInternal(fd.getFd(), writeSize, offset, bufferIndex, (byte) 0, false);
    }

    /**
     * Prepares a zero-copy write from a registered fixed buffer, with explicit SQE flags.
     *
     * @param fd          file to write
     * @param writeSize   number of bytes to write from the buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(FileDescriptor fd, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedZeroCopyInternal(fd.getFd(), writeSize, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares a zero-copy write from a raw native address that must be one of the registered
     * buffers.
     *
     * @param fd           file to write
     * @param bufferAddress native address of the data to write
     * @param writeSize    number of bytes to write
     * @param offset       file offset in bytes
     * @param bufferIndex  index of the registered buffer the address belongs to
     * @param sqeOptions   SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(FileDescriptor fd, long bufferAddress, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long sqe = getRwSqe(SqeOptions.combineOptions(sqeOptions), false);
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);
        ioUring.prepareWriteFixed(sqe, fd.getFd(), bufferAddress, writeSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /**
     * Prepares a zero-copy write of the first {@code writeSize} bytes of a registered fixed
     * buffer on a registered-file slot.
     *
     * @param indexFD     registered-file slot number
     * @param writeSize   number of bytes to write from the buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(int indexFD, int writeSize, long offset, int bufferIndex) {
        return prepareWriteFixedZeroCopyInternal(indexFD, writeSize, offset, bufferIndex, (byte) 0, true);
    }

    /**
     * Prepares a zero-copy write from a registered fixed buffer on a registered-file slot,
     * with explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param writeSize  number of bytes to write from the buffer
     * @param offset     file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWriteFixed(int indexFD, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedZeroCopyInternal(indexFD, writeSize, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Returns the memory segment of a buffer registered with
     * {@link #registerBuffers(int, int)}.
     *
     * @param bufferIndex index of the registered buffer
     * @return the buffer's memory segment
     * @throws IllegalArgumentException when the index is out of range
     * @throws IllegalStateException    when the ring is closed
     */
    public MemorySegment getRegisteredBuffer(int bufferIndex) {
        ensureOpenAndOwner();
        if (bufferIndex < 0 || bufferIndex >= registeredBuffersArray.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }
        return registeredBuffersArray[bufferIndex];
    }

    /**
     * Prepares a vectored read into ring-allocated buffers of the given sizes.
     *
     * <p>The completion's {@link Result#buffers()} exposes one segment per iovec;
     * {@link Result#freeBuffers()} releases them.
     *
     * @param fd          file to read
     * @param bufferSizes sizes of the individual iovec buffers
     * @param offset      file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the vector count or a size is out of range
     */
    public long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset) {
        return prepareReadv(fd, bufferSizes, offset, (byte) 0);
    }

    /**
     * Prepares a vectored read into ring-allocated buffers, with explicit SQE flags.
     *
     * @param fd          file to read
     * @param bufferSizes sizes of the individual iovec buffers
     * @param offset      file offset in bytes
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset, SqeOptions... sqeOptions) {
        return prepareReadv(fd, bufferSizes, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset, byte flags) {
        int nrVecs = bufferSizes.length;
        if (nrVecs <= 0 || nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " out of range (1.." + IovecBlockPool.MAX_VECS + ")");
        }
        for (int i = 0; i < nrVecs; i++) {
            if (bufferSizes[i] < 0) {
                throw new IllegalArgumentException("Invalid negative buffer size at index " + i + ": " + bufferSizes[i]);
            }
        }

        long sqe = getRwSqe(flags, false);
        long blockAddr = ioUring.allocateIovecBlock();
        IovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            MemorySegment dataBuf = NativeDispatcher.C.alloc(Math.max(1, bufferSizes[i]));
            IovecBlock.setIovBase(blockAddr, i, dataBuf.address());
            IovecBlock.setIovLen(blockAddr, i, bufferSizes[i]);
        }

        long userData = UserData.encode(OperationType.READV, blockAddr);

        ioUring.prepareReadvAddress(sqe, fd.getFd(), IovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    /**
     * Prepares a vectored read into the given registered fixed buffers.
     *
     * @param fd            file to read
     * @param bufferIndices indices of the registered destination buffers
     * @param offset        file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the vector count or an index is out of range
     */
    public long prepareReadvFixed(FileDescriptor fd, int[] bufferIndices, long offset) {
        return prepareReadvFixed(fd, bufferIndices, offset, (byte) 0);
    }

    /**
     * Prepares a vectored read into registered fixed buffers, with explicit SQE flags.
     *
     * @param fd            file to read
     * @param bufferIndices indices of the registered destination buffers
     * @param offset        file offset in bytes
     * @param sqeOptions    SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareReadvFixed(FileDescriptor fd, int[] bufferIndices, long offset, SqeOptions... sqeOptions) {
        return prepareReadvFixed(fd, bufferIndices, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareReadvFixed(FileDescriptor fd, int[] bufferIndices, long offset, byte flags) {
        int nrVecs = bufferIndices.length;
        if (nrVecs <= 0 || nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " out of range (1.." + IovecBlockPool.MAX_VECS + ")");
        }
        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            if (idx < 0 || idx >= registeredBuffers.size()) {
                throw new IllegalArgumentException("Buffer index out of range: " + idx);
            }
        }

        long sqe = getRwSqe(flags, false);
        long blockAddr = ioUring.allocateIovecBlock();
        IovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            MemorySegment registeredBuffer = registeredBuffers.get(idx);
            IovecBlock.setIovBase(blockAddr, i, registeredBuffer.address());
            IovecBlock.setIovLen(blockAddr, i, registeredBuffer.byteSize());
        }

        long userData = UserData.encode(OperationType.READV_FIXED, blockAddr);

        ioUring.prepareReadvAddress(sqe, fd.getFd(), IovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    /**
     * Prepares a vectored zero-copy write from registered fixed buffers.
     *
     * @param fd            file to write
     * @param bufferIndices indices of the registered source buffers
     * @param lengths       number of bytes to write from each buffer
     * @param offset        file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the arrays differ in length, an index is out of
     *                                  range or a length exceeds its buffer size
     */
    public long prepareWritevFixed(FileDescriptor fd, int[] bufferIndices, long[] lengths, long offset) {
        return prepareWritevFixed(fd, bufferIndices, lengths, offset, (byte) 0);
    }

    /**
     * Prepares a vectored zero-copy write from registered fixed buffers, with explicit SQE
     * flags.
     *
     * @param fd            file to write
     * @param bufferIndices indices of the registered source buffers
     * @param lengths       number of bytes to write from each buffer
     * @param offset        file offset in bytes
     * @param sqeOptions    SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWritevFixed(FileDescriptor fd, int[] bufferIndices, long[] lengths, long offset, SqeOptions... sqeOptions) {
        return prepareWritevFixed(fd, bufferIndices, lengths, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareWritevFixed(FileDescriptor fd, int[] bufferIndices, long[] lengths, long offset, byte flags) {
        int nrVecs = bufferIndices.length;
        if (lengths.length != nrVecs) {
            throw new IllegalArgumentException("lengths array must have the same length as bufferIndices");
        }
        if (nrVecs <= 0 || nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " out of range (1.." + IovecBlockPool.MAX_VECS + ")");
        }
        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            if (idx < 0 || idx >= registeredBufferAddresses.length) {
                throw new IllegalArgumentException("Buffer index out of range: " + idx);
            }
            if (lengths[i] < 0 || lengths[i] > registeredBufferSizes[idx]) {
                throw new IllegalArgumentException("Length " + lengths[i] + " exceeds registered buffer size for index " + idx);
            }
        }

        long sqe = getRwSqe(flags, false);
        long blockAddr = ioUring.allocateIovecBlock();
        IovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            IovecBlock.setIovBase(blockAddr, i, registeredBufferAddresses[idx]);
            IovecBlock.setIovLen(blockAddr, i, lengths[i]);
        }

        long userData = UserData.encode(OperationType.WRITEV_FIXED, blockAddr);

        ioUring.prepareWritevAddress(sqe, fd.getFd(), IovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    /**
     * Prepares a vectored write that copies the given heap arrays into native memory.
     *
     * @param fd      file to write
     * @param buffers byte arrays to write, in iovec order
     * @param offset  file offset in bytes
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when the vector count is out of range or an array is
     *                                  {@code null}
     */
    public long prepareWritev(FileDescriptor fd, byte[][] buffers, long offset) {
        return prepareWritev(fd, buffers, offset, (byte) 0);
    }

    /**
     * Prepares a vectored write from heap arrays, with explicit SQE flags.
     *
     * @param fd         file to write
     * @param buffers    byte arrays to write, in iovec order
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareWritev(FileDescriptor fd, byte[][] buffers, long offset, SqeOptions... sqeOptions) {
        return prepareWritev(fd, buffers, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareWritev(FileDescriptor fd, byte[][] buffers, long offset, byte flags) {
        int nrVecs = buffers.length;
        if (nrVecs <= 0 || nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " out of range (1.." + IovecBlockPool.MAX_VECS + ")");
        }
        for (int i = 0; i < nrVecs; i++) {
            if (buffers[i] == null) {
                throw new IllegalArgumentException("Null buffer at index " + i);
            }
        }

        long sqe = getRwSqe(flags, false);
        long blockAddr = ioUring.allocateIovecBlock();
        IovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            MemorySegment dataBuf = NativeDispatcher.C.alloc(buffers[i].length);
            MemorySegment.copy(buffers[i], 0, dataBuf, JAVA_BYTE, 0, buffers[i].length);
            IovecBlock.setIovBase(blockAddr, i, dataBuf.address());
            IovecBlock.setIovLen(blockAddr, i, buffers[i].length);
        }

        long userData = UserData.encode(OperationType.WRITEV, blockAddr);

        ioUring.prepareWritevAddress(sqe, fd.getFd(), IovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    /**
     * Prepares an asynchronous {@code openat(2)} of {@code filePath}.
     *
     * <p>{@code O_CLOEXEC} is added to {@code flags}. The completion's result is the new
     * file descriptor (see {@link Result#fd()} / {@link Result#fileDescriptor()}); the caller
     * owns and must close it.
     *
     * @param filePath path to open
     * @param flags    Linux {@code open(2)} flags (see {@link LinuxOpenOptions#getValue()})
     * @param mode     permission bits used when the file is created
     * @return the operation handle ({@code user_data})
     */
    public long prepareOpen(String filePath, int flags, int mode) {
        return prepareOpen(filePath, flags, mode, (byte) 0);
    }

    /**
     * Prepares an asynchronous {@code openat(2)} with explicit SQE flags.
     *
     * @param filePath   path to open
     * @param flags      Linux {@code open(2)} flags
     * @param mode       permission bits used when the file is created
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareOpen(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        return prepareOpen(filePath, flags, mode, SqeOptions.combineOptions(sqeOptions));
    }

    /**
     * Prepares an asynchronous {@code openat(2)} of a {@link Path} with
     * {@link LinuxOpenOptions} flags.
     *
     * @param filePath    path to open
     * @param mode        permission bits used when the file is created
     * @param openOptions open flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareOpen(Path filePath, int mode, LinuxOpenOptions... openOptions) {
        return prepareOpen(filePath.toString(), LinuxOpenOptions.combineOptions(openOptions), mode, (byte) 0);
    }

    private long prepareOpen(String filePath, int flags, int mode, byte sqeFlags) {
        byte[] pathBytes = LibCDispatcher.encodePath(filePath);
        long sqe = getSqe(sqeFlags, false);
        MemorySegment pathBuffer = NativeDispatcher.C.calloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathBuffer, JAVA_BYTE, 0, pathBytes.length);

        long userData = UserData.encode(OperationType.OPEN, pathBuffer.address());

        ioUring.prepareOpenAt(sqe, pathBuffer.address(), flags | 0x80000 /* O_CLOEXEC */, mode);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    /**
     * Prepares an asynchronous {@code openat(2)} that installs the opened file into a
     * registered-file slot.
     *
     * <p>The file is opened and placed in slot {@code fileIndex} of the ring's registered-file
     * table; the completion result is {@code 0} on success rather than a new descriptor, and
     * the slot can then be used with the {@code indexFD} overloads.
     *
     * @param filePath  path to open
     * @param flags     Linux {@code open(2)} flags
     * @param mode      permission bits used when the file is created
     * @param fileIndex registered-file slot to install the opened file into
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when {@code fileIndex} is negative
     */
    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex) {
        return prepareOpenDirect(filePath, flags, mode, fileIndex, (byte) 0);
    }

    /**
     * Prepares an asynchronous direct-descriptor {@code openat(2)} with explicit SQE flags.
     *
     * @param filePath   path to open
     * @param flags      Linux {@code open(2)} flags
     * @param mode       permission bits used when the file is created
     * @param fileIndex  registered-file slot to install the opened file into
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        return prepareOpenDirect(filePath, flags, mode, fileIndex, SqeOptions.combineOptions(sqeOptions));
    }

    /**
     * Prepares an asynchronous direct-descriptor {@code openat(2)} of a {@link Path}.
     *
     * @param filePath    path to open
     * @param mode        permission bits used when the file is created
     * @param fileIndex   registered-file slot to install the opened file into
     * @param openOptions open flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareOpenDirect(Path filePath, int mode, int fileIndex, LinuxOpenOptions... openOptions) {
        return prepareOpenDirect(filePath.toString(), LinuxOpenOptions.combineOptions(openOptions), mode, fileIndex, (byte) 0);
    }

    private long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex, byte sqeFlags) {
        if (fileIndex < 0) {
            throw new IllegalArgumentException("Invalid fileIndex: " + fileIndex);
        }
        byte[] pathBytes = LibCDispatcher.encodePath(filePath);
        long sqe = getSqe(sqeFlags, false);
        MemorySegment pathBuffer = NativeDispatcher.C.alloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathBuffer, JAVA_BYTE, 0, pathBytes.length);
        pathBuffer.set(JAVA_BYTE, pathBytes.length, (byte) 0);

        long userData = UserData.encode(OperationType.OPEN, pathBuffer.address());

        ioUring.prepareOpenDirectAt(sqe, pathBuffer.address(), flags & ~0x80000 /* O_CLOEXEC not allowed for direct */, mode, fileIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    /**
     * Prepares an asynchronous close of a file descriptor.
     *
     * <p>The descriptor must not be used by any other in-flight operation once the close is
     * submitted; the completion result is {@code 0} on success.
     *
     * @param fd file descriptor to close
     * @return the operation handle ({@code user_data})
     */
    public long prepareClose(FileDescriptor fd) {
        return prepareCloseInternal(fd.getFd(), (byte) 0);
    }

    /**
     * Prepares an asynchronous close of a file descriptor, with explicit SQE flags.
     *
     * @param fd         file descriptor to close
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareClose(FileDescriptor fd, SqeOptions... sqeOptions) {
        return prepareCloseInternal(fd.getFd(), SqeOptions.combineOptions(sqeOptions));
    }

    /**
     * Prepares an asynchronous close of a file held in a registered-file slot.
     *
     * <p>The completion result is {@code 0} on success; the slot is released by the kernel.
     *
     * @param fileIndex registered-file slot to close
     * @return the operation handle ({@code user_data})
     * @throws IllegalArgumentException when {@code fileIndex} is negative
     */
    public long prepareCloseDirect(int fileIndex) {
        return prepareCloseDirect(fileIndex, (byte) 0);
    }

    /**
     * Prepares an asynchronous close of a registered-file slot, with explicit SQE flags.
     *
     * @param fileIndex  registered-file slot to close
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareCloseDirect(int fileIndex, SqeOptions... sqeOptions) {
        return prepareCloseDirect(fileIndex, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareCloseDirect(int fileIndex, byte flags) {
        if (fileIndex < 0) {
            throw new IllegalArgumentException("Invalid fileIndex: " + fileIndex);
        }
        long sqe = getSqe(flags, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.CLOSE, opId);

        ioUring.prepareCloseDirect(sqe, fileIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadInternal(int fdOrIndex, int readSize, long offset, byte flags, boolean fixedFile) {
        if (readSize < 0) {
            throw new IllegalArgumentException("Invalid readSize: " + readSize);
        }
        long sqe = getRwSqe(flags, fixedFile);
        long address = NativeDispatcher.C.mallocAddress(Math.max(1L, readSize));
        long userData = UserData.encode(OperationType.READ, address);

        ioUring.prepareRead(sqe, fdOrIndex, address, readSize, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    /**
     * B10 F1 fix: prepares a non-fixed read whose {@code user_data} is a unique operation id
     * instead of the buffer address, so concurrent reads into the same segment are
     * distinguishable. Used by {@link JuringFile}; the raw public {@code prepareRead}
     * overloads keep the address payload for {@link Result#validBuffer()} compatibility.
     */
    long prepareReadNoBuffer(FileDescriptor fd, MemorySegment buffer, int readSize, long offset) {
        return prepareReadNoBuffer(fd, buffer, readSize, offset, (byte) 0);
    }

    long prepareReadNoBuffer(FileDescriptor fd, MemorySegment buffer, int readSize, long offset, byte flags) {
        Objects.requireNonNull(fd, "fd");
        return prepareReadNoBuffer(fd.getFd(), buffer, readSize, offset, flags, false);
    }

    long prepareReadNoBuffer(int fdOrIndex, MemorySegment buffer, int readSize, long offset,
            byte flags, boolean fixedFile) {
        Objects.requireNonNull(buffer, "buffer");
        if (readSize < 0 || readSize > buffer.byteSize()) {
            throw new IllegalArgumentException("readSize " + readSize + " out of range (0.." + buffer.byteSize() + ")");
        }
        validateNativeSegment(buffer, true);
        long sqe = getRwSqe(flags, fixedFile);
        long userData = UserData.encodeInternalReadExt(opSeq++);
        ioUring.prepareRead(sqe, fdOrIndex, buffer.address(), readSize, offset);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    private long prepareReadInternal(int fdOrIndex, MemorySegment buffer, int readSize, long offset, byte flags, boolean fixedFile) {
        if (readSize < 0 || readSize > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid readSize: " + readSize);
        }
        validateNativeSegment(buffer, true);
        long sqe = getRwSqe(flags, fixedFile);
        long userData = UserData.encode(OperationType.READ_EXT, buffer.address());

        ioUring.prepareRead(sqe, fdOrIndex, buffer.address(), readSize, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadPooledInternal(int fdOrIndex, int readSize, long offset, byte flags, boolean fixedFile) {
        if (readBufferPool == null) {
            throw new IllegalStateException("Read buffer pool has not been initialized; call setupReadBufferPool() first");
        }
        if (readSize < 0 || readSize > readBufferPool.bufferSize()) {
            throw new IllegalArgumentException("Read size " + readSize + " out of range (0.." + readBufferPool.bufferSize() + ")");
        }
        // Check the buffer out before taking an SQE: an exhausted pool must not consume a
        // submission-queue slot (or an in-flight ledger slot) that is never submitted.
        long address = readBufferPool.checkOut();
        long sqe;
        try {
            sqe = getRwSqe(flags, fixedFile);
        } catch (RuntimeException e) {
            readBufferPool.checkIn(address);
            throw e;
        }
        long userData = UserData.encode(OperationType.READ_POOLED, address);

        ioUring.prepareRead(sqe, fdOrIndex, address, readSize, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    static void validateNativeSegment(MemorySegment segment, boolean forRead) {
        if (!segment.isNative()) {
            throw new IllegalArgumentException("Segment must be native memory");
        }
        if (forRead && segment.isReadOnly()) {
            throw new IllegalArgumentException("Segment must not be read-only for read operations");
        }
        if (!segment.scope().isAlive()) {
            throw new IllegalStateException("Segment scope is already closed");
        }
        if (!segment.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("Segment is confined to another thread");
        }
    }

    private long prepareWriteInternal(int fdOrIndex, MemorySegment bytes, long offset, byte flags, boolean fixedFile) {
        validateNativeSegment(bytes, false);
        long sqe = getRwSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.WRITE_EXT, opId);

        ioUring.prepareWrite(sqe, fdOrIndex, bytes.address(), bytes.byteSize(), offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteInternal(int fdOrIndex, byte[] bytes, long offset, byte flags, boolean fixedFile) {
        Objects.requireNonNull(bytes, "bytes");
        long sqe = getRwSqe(flags, fixedFile);
        MemorySegment buff = NativeDispatcher.C.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);

        long userData = UserData.encode(OperationType.WRITE, buff.address());

        ioUring.prepareWrite(sqe, fdOrIndex, buff.address(), bytes.length, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadFixedInternal(int fdOrIndex, int readSize, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        if (readSize < 0 || readSize > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Read size exceeds registered buffer size");
        }

        long sqe = getRwSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.READ_FIXED, bufferIndex, opId);

        ioUring.prepareReadFixed(sqe, fdOrIndex, registeredBufferAddresses[bufferIndex], readSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, byte[] bytes, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        if (bytes.length > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        long sqe = getRwSqe(flags, fixedFile);
        MemorySegment.copy(bytes, 0, registeredBuffersArray[bufferIndex], JAVA_BYTE, 0, bytes.length);
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);

        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBufferAddresses[bufferIndex], bytes.length, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, MemorySegment bytes, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        long byteSize = bytes.byteSize();
        if (byteSize < 0 || byteSize > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        if (!bytes.scope().isAlive()) {
            throw new IllegalStateException("Segment scope is already closed");
        }
        if (!bytes.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("Segment is confined to another thread");
        }

        long sqe = getRwSqe(flags, fixedFile);
        long targetAddr = registeredBufferAddresses[bufferIndex];
        if (!bytes.isNative() || bytes.address() != targetAddr) {
            MemorySegment.copy(bytes, 0, registeredBuffersArray[bufferIndex], 0, byteSize);
        }
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);

        ioUring.prepareWriteFixed(sqe, fdOrIndex, targetAddr, byteSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteFixedZeroCopyInternal(int fdOrIndex, int writeSize, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        if (writeSize < 0 || writeSize > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        long sqe = getRwSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);

        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBufferAddresses[bufferIndex], writeSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareCloseInternal(int fdOrIndex, byte flags) {
        long sqe = getSqe(flags, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.CLOSE, opId);

        ioUring.prepareClose(sqe, fdOrIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    /**
     * Prepares an {@code fsync(2)} of a file.
     *
     * @param fd file to flush
     * @return the operation handle ({@code user_data}); the completion result is {@code 0} on
     *         success
     */
    public long prepareFsync(FileDescriptor fd) {
        return prepareFsync(fd, 0);
    }

    /**
     * Prepares an {@code fsync(2)}/ {@code fdatasync(2)} of a file.
     *
     * @param fd         file to flush
     * @param fsyncFlags {@code 0} for {@code fsync}, or {@link #IORING_FSYNC_DATASYNC} for
     *                   {@code fdatasync}
     * @return the operation handle ({@code user_data})
     */
    public long prepareFsync(FileDescriptor fd, int fsyncFlags) {
        return prepareFsyncInternal(fd.getFd(), fsyncFlags, (byte) 0, false);
    }

    /**
     * Prepares an {@code fsync(2)}/ {@code fdatasync(2)} with explicit SQE flags.
     *
     * @param fd         file to flush
     * @param fsyncFlags {@code 0} for {@code fsync}, or {@link #IORING_FSYNC_DATASYNC} for
     *                   {@code fdatasync}
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareFsync(FileDescriptor fd, int fsyncFlags, SqeOptions... sqeOptions) {
        return prepareFsyncInternal(fd.getFd(), fsyncFlags, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares an {@code fsync(2)}/ {@code fdatasync(2)} of a registered-file slot.
     *
     * @param indexFD    registered-file slot number
     * @param fsyncFlags {@code 0} for {@code fsync}, or {@link #IORING_FSYNC_DATASYNC} for
     *                   {@code fdatasync}
     * @return the operation handle ({@code user_data})
     */
    public long prepareFsync(int indexFD, int fsyncFlags) {
        return prepareFsyncInternal(indexFD, fsyncFlags, (byte) 0, true);
    }

    /**
     * Prepares an {@code fsync(2)}/ {@code fdatasync(2)} of a registered-file slot, with
     * explicit SQE flags.
     *
     * @param indexFD    registered-file slot number
     * @param fsyncFlags {@code 0} for {@code fsync}, or {@link #IORING_FSYNC_DATASYNC} for
     *                   {@code fdatasync}
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareFsync(int indexFD, int fsyncFlags, SqeOptions... sqeOptions) {
        return prepareFsyncInternal(indexFD, fsyncFlags, SqeOptions.combineOptions(sqeOptions), true);
    }

    private long prepareFsyncInternal(int fdOrIndex, int fsyncFlags, byte flags, boolean fixedFile) {
        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.FSYNC, opId);
        ioUring.prepareFsync(sqe, fdOrIndex, fsyncFlags);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /**
     * Prepares an {@code IORING_OP_ASYNC_CANCEL} that cancels the request identified by
     * {@code targetOpId}.
     *
     * @param targetOpId the target request's {@code user_data} / operation handle
     * @return the cancel request's own operation handle
     * @deprecated the argument is the target request's {@code user_data} value (the handle
     *             returned by the {@code prepare*} methods), not a logical operation id; use
     *             {@link #prepareCancelByUserData(long)} for explicit semantics.
     */
    @Deprecated
    public long prepareCancel(long targetOpId) {
        return prepareCancelByUserData(targetOpId);
    }

    /**
     * Prepares an {@code IORING_OP_ASYNC_CANCEL} that cancels the request whose
     * {@code user_data} equals {@code userData}. Every {@code prepare*} method returns that
     * {@code user_data} as the operation handle, so pass the handle of the request to cancel.
     *
     * <p>Kernel semantics: the target request receives exactly one terminal completion; when
     * the cancellation wins that completion is {@code -ECANCELED}, otherwise it is the
     * request's real result. The cancel request itself also posts one completion whose
     * result is {@code 0} (cancelled), {@code -ENOENT} (no matching in-flight request: it
     * completed already or never existed) or another {@code -errno}. Exactly one completion
     * is posted for the cancel request and one for the target; nothing is lost or duplicated.
     *
     * @param userData the target request's {@code user_data} / operation handle
     * @return the cancel request's own operation handle
     */
    public long prepareCancelByUserData(long userData) {
        long sqe = getSqe((byte) 0, false);
        long opId = opSeq++;
        long cancelUserData = UserData.encode(OperationType.CANCEL, opId);

        ioUring.prepareCancelByUserData(sqe, userData);
        ioUring.setUserData(sqe, cancelUserData);

        return cancelUserData;
    }

    /**
     * Prepares a bulk {@code IORING_OP_ASYNC_CANCEL} that cancels every in-flight request
     * operating on one file ({@code IORING_ASYNC_CANCEL_FD} plus {@code
     * IORING_ASYNC_CANCEL_FD_FIXED} for registered slots, and {@code IORING_ASYNC_CANCEL_ALL}).
     * <p>
     * The match is by the kernel {@code struct file} behind the descriptor, so it cancels both
     * plain-fd and fixed-file requests on the same file. The file must stay open until the
     * cancel request is submitted and resolved (the kernel pins it at prep time).
     *
     * @param fdOrSlot  a plain file descriptor, or a registered-file slot when
     *                  {@code fixedFile} is {@code true}
     * @param fixedFile {@code true} to interpret {@code fdOrSlot} as a registered-file slot
     * @return the cancel request's own operation handle; its completion result is the number
     *         of cancelled requests ({@code 0} = none matched, negative = {@code -errno})
     */
    public long prepareCancelFile(int fdOrSlot, boolean fixedFile) {
        long sqe = getSqe((byte) 0, false);
        long opId = opSeq++;
        long cancelUserData = UserData.encode(OperationType.CANCEL, opId);

        ioUring.prepareCancelFile(sqe, fdOrSlot, fixedFile, IoUringDispatcher.IORING_ASYNC_CANCEL_ALL);
        ioUring.setUserData(sqe, cancelUserData);

        return cancelUserData;
    }

    /**
     * Prepares an {@code IORING_OP_ASYNC_CANCEL} with {@code IORING_ASYNC_CANCEL_ALL |
     * IORING_ASYNC_CANCEL_ANY}: cancels every request in flight on this ring.
     *
     * <p><b>Dangerous:</b> this is a ring-wide operation, not scoped to a {@code JuringFile}.
     * It is intended for shutdown paths where the caller owns every request on the ring.
     * {@code JuringFile} exposes its own scoped cancellation.
     *
     * @return the cancel request's own operation handle
     */
    public long prepareCancelAny() {
        long sqe = getSqe((byte) 0, false);
        long opId = opSeq++;
        long cancelUserData = UserData.encode(OperationType.CANCEL, opId);

        ioUring.prepareCancelAny(sqe);
        ioUring.setUserData(sqe, cancelUserData);

        return cancelUserData;
    }

    // ------------------------------------------------------------------
    // B4 shard-runtime helpers (package-private; reserved internal user_data)
    // ------------------------------------------------------------------

    /**
     * Prepares an {@code IORING_OP_READ} of 8 bytes from a raw file descriptor into
     * {@code buffer}, using a caller-supplied (reserved) {@code user_data}. Used by the B4
     * shard loop to keep an eventfd wakeup read armed on its own ring.
     *
     * <p>The completion is reported by {@code Result.id() == userData}; the reserved
     * {@code 0xFF} type byte keeps {@code populateResultFromCqe} on its buffer-less default
     * branch, so no buffer ownership is fabricated.
     *
     * @return {@code userData}
     */
    long prepareWakeupRead(int fd, MemorySegment buffer, long userData) {
        ensureOpenAndOwner();
        Objects.requireNonNull(buffer, "buffer");
        validateNativeSegment(buffer, true);
        if (buffer.byteSize() < 8L) {
            throw new IllegalArgumentException("wakeup read buffer must be at least 8 bytes: " + buffer.byteSize());
        }
        long sqe = getRwSqe((byte) 0, false);
        ioUring.prepareRead(sqe, fd, buffer.address(), 8L, 0L);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /**
     * Prepares the ring-wide {@code IORING_OP_ASYNC_CANCEL} ({@code ALL | ANY}) with a
     * caller-supplied (reserved) {@code user_data}, so the shard loop can consume its own
     * completion internally instead of counting it as unattributed user work.
     *
     * @return {@code userData}
     */
    long prepareCancelAnyInternal(long userData) {
        ensureOpenAndOwner();
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_ASYNC_CANCEL, "IORING_OP_ASYNC_CANCEL");
        long sqe = getSqe((byte) 0, false);
        ioUring.prepareCancelAny(sqe);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /** Number of SQEs prepared but not yet handed to the kernel ({@code sqeTail - sqHead}). */
    int pendingSqes() {
        ensureOpenAndOwner();
        return ioUring.pendingSqes();
    }

    /** The underlying dispatcher (B4 loop-body seam; shard-thread confined). */
    IoUringDispatcher dispatcher() {
        return ioUring;
    }

    // ------------------------------------------------------------------
    // Optional metadata ops: fallocate, ftruncate, statx, timeout
    // ------------------------------------------------------------------

    private static final int EOPNOTSUPP = 95;

    /**
     * Prepares an async {@code fallocate(2)} (opcode {@code IORING_OP_FALLOCATE}) on a plain
     * file descriptor.
     *
     * <p>Requires kernel support for {@code IORING_OP_FALLOCATE}; otherwise
     * {@link UnsupportedOperationException} is thrown before touching the submission queue.
     *
     * @param fd     file to allocate space in
     * @param mode   fallocate mode flags (0 for plain allocation, or {@code FALLOC_FL_*} bits)
     * @param offset file offset in bytes
     * @param length byte length to allocate
     * @return the operation handle to pass to {@code waitForResult}/{@code await}
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_FALLOCATE}
     * @throws IllegalArgumentException when a mode, offset or length is negative
     */
    public long prepareFallocate(FileDescriptor fd, int mode, long offset, long length) {
        return prepareFallocateInternal(fd.getFd(), mode, offset, length, (byte) 0, false);
    }

    /**
     * Prepares an async {@code fallocate(2)} on a plain file descriptor, with explicit SQE
     * flags.
     *
     * @param fd         file to allocate space in
     * @param mode       fallocate mode flags
     * @param offset     file offset in bytes
     * @param length     byte length to allocate
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareFallocate(FileDescriptor fd, int mode, long offset, long length, SqeOptions... sqeOptions) {
        return prepareFallocateInternal(fd.getFd(), mode, offset, length, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares an async {@code fallocate(2)} on a slot of the registered-file table.
     *
     * @param indexFD slot index in the registered-file table (submitted with
     *                {@code IOSQE_FIXED_FILE})
     * @param mode    fallocate mode flags
     * @param offset  file offset in bytes
     * @param length  byte length to allocate
     * @return the operation handle ({@code user_data})
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_FALLOCATE}
     * @throws IllegalArgumentException when a mode, offset or length is negative
     */
    public long prepareFallocate(int indexFD, int mode, long offset, long length) {
        return prepareFallocateInternal(indexFD, mode, offset, length, (byte) 0, true);
    }

    /**
     * Prepares an async {@code fallocate(2)} on a registered-file slot, with explicit SQE
     * flags.
     *
     * @param indexFD    slot index in the registered-file table
     * @param mode       fallocate mode flags
     * @param offset     file offset in bytes
     * @param length     byte length to allocate
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareFallocate(int indexFD, int mode, long offset, long length, SqeOptions... sqeOptions) {
        return prepareFallocateInternal(indexFD, mode, offset, length, SqeOptions.combineOptions(sqeOptions), true);
    }

    private long prepareFallocateInternal(int fdOrIndex, int mode, long offset, long length, byte flags, boolean fixedFile) {
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_FALLOCATE, "IORING_OP_FALLOCATE");
        if (mode < 0) {
            throw new IllegalArgumentException("Invalid fallocate mode: " + mode);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Invalid fallocate offset: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("Invalid fallocate length: " + length);
        }
        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.FALLOCATE, opId);
        ioUring.prepareFallocate(sqe, fdOrIndex, mode, offset, length);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /**
     * Prepares an async {@code ftruncate(2)} (opcode {@code IORING_OP_FTRUNCATE}) on a plain
     * file descriptor.
     *
     * <p>Requires kernel support for {@code IORING_OP_FTRUNCATE}.
     *
     * @param fd        file to truncate
     * @param newLength new file length in bytes
     * @return the operation handle ({@code user_data})
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_FTRUNCATE}
     * @throws IllegalArgumentException when {@code newLength} is negative
     */
    public long prepareFtruncate(FileDescriptor fd, long newLength) {
        return prepareFtruncateInternal(fd.getFd(), newLength, (byte) 0, false);
    }

    /**
     * Prepares an async {@code ftruncate(2)} on a plain file descriptor, with explicit SQE
     * flags.
     *
     * @param fd         file to truncate
     * @param newLength  new file length in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareFtruncate(FileDescriptor fd, long newLength, SqeOptions... sqeOptions) {
        return prepareFtruncateInternal(fd.getFd(), newLength, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares an async {@code ftruncate(2)} on a slot of the registered-file table.
     *
     * @param indexFD   registered-file slot number
     * @param newLength new file length in bytes
     * @return the operation handle ({@code user_data})
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_FTRUNCATE}
     */
    public long prepareFtruncate(int indexFD, long newLength) {
        return prepareFtruncateInternal(indexFD, newLength, (byte) 0, true);
    }

    /**
     * Prepares an async {@code ftruncate(2)} on a registered-file slot, with explicit SQE
     * flags.
     *
     * @param indexFD    registered-file slot number
     * @param newLength  new file length in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareFtruncate(int indexFD, long newLength, SqeOptions... sqeOptions) {
        return prepareFtruncateInternal(indexFD, newLength, SqeOptions.combineOptions(sqeOptions), true);
    }

    private long prepareFtruncateInternal(int fdOrIndex, long newLength, byte flags, boolean fixedFile) {
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_FTRUNCATE, "IORING_OP_FTRUNCATE");
        if (newLength < 0) {
            throw new IllegalArgumentException("Invalid truncate length: " + newLength);
        }
        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.FTRUNCATE, opId);
        ioUring.prepareFtruncate(sqe, fdOrIndex, newLength);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /**
     * Prepares an async {@code statx(2)} with {@code AT_EMPTY_PATH} and {@code STATX_BASIC_STATS}
     * against a plain fd, writing the result into {@code statxBuffer}.
     * <p>
     * {@code statxBuffer} must be a live native segment of at least 256 bytes (the
     * {@code struct statx} size); the buffer must stay valid until the operation completes.
     * Requires kernel support for {@code IORING_OP_STATX}.
     *
     * @param fd          file to stat
     * @param statxBuffer native segment of at least 256 bytes receiving the {@code struct statx}
     * @return the operation handle; the completion result is 0 on success
     */
    public long prepareStatx(FileDescriptor fd, MemorySegment statxBuffer) {
        return prepareStatxInternal(fd.getFd(), statxBuffer, (byte) 0, false);
    }

    /**
     * Prepares an async {@code statx(2)} on a plain file descriptor, with explicit SQE flags.
     *
     * @param fd          file to stat
     * @param statxBuffer native segment receiving the {@code struct statx}
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareStatx(FileDescriptor fd, MemorySegment statxBuffer, SqeOptions... sqeOptions) {
        return prepareStatxInternal(fd.getFd(), statxBuffer, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares an async {@code statx(2)} with {@code AT_EMPTY_PATH} against a slot of the
     * registered-file table.
     * <p>
     * Kernel note: {@code IORING_OP_STATX} does not accept {@code IOSQE_FIXED_FILE} (the kernel's
     * statx prep returns {@code -EBADF} for fixed files). This method therefore resolves the slot
     * to the file descriptor tracked for it and submits a plain {@code AT_EMPTY_PATH} statx against
     * that descriptor; the observable result is the same. This means the tracked descriptor must
     * still be open: if you closed the original fd after {@code registerFiles} (which is legal for
     * the other fixed-file operations, because the kernel keeps its own reference), statx on the
     * slot fails with {@code -EBADF}. If the slot is empty/untracked, the operation is submitted as
     * a fixed-file statx and completes with {@code -EBADF}, matching the other fixed-file operations.
     *
     * @param indexFD     registered-file slot to stat
     * @param statxBuffer native segment of at least 256 bytes receiving the {@code struct statx}
     * @return the operation handle; the completion result is 0 on success
     */
    public long prepareStatx(int indexFD, MemorySegment statxBuffer) {
        return prepareStatxInternal(indexFD, statxBuffer, (byte) 0, true);
    }

    /**
     * Prepares an async {@code statx(2)} on a registered-file slot, with explicit SQE flags.
     *
     * @param indexFD    registered-file slot to stat
     * @param statxBuffer native segment receiving the {@code struct statx}
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareStatx(int indexFD, MemorySegment statxBuffer, SqeOptions... sqeOptions) {
        return prepareStatxInternal(indexFD, statxBuffer, SqeOptions.combineOptions(sqeOptions), true);
    }

    private long prepareStatxInternal(int fdOrIndex, MemorySegment statxBuffer, byte flags, boolean fixedFile) {
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_STATX, "IORING_OP_STATX");
        Objects.requireNonNull(statxBuffer, "statxBuffer");
        if (!statxBuffer.isNative()) {
            throw new IllegalArgumentException("statxBuffer must be native memory");
        }
        if (statxBuffer.byteSize() < 256L) {
            throw new IllegalArgumentException("statxBuffer must be at least 256 bytes (struct statx size), got " + statxBuffer.byteSize());
        }
        if (!statxBuffer.scope().isAlive()) {
            throw new IllegalStateException("statxBuffer scope is already closed");
        }
        if (!statxBuffer.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("statxBuffer is confined to another thread");
        }
        int dfd = fdOrIndex;
        boolean useFixedFile = fixedFile;
        if (fixedFile) {
            int registeredFd = registeredFileFd(fdOrIndex);
            if (registeredFd >= 0) {
                // IORING_OP_STATX rejects IOSQE_FIXED_FILE with -EBADF, so resolve the slot to the
                // tracked descriptor and issue a plain AT_EMPTY_PATH statx on the same file.
                dfd = registeredFd;
                useFixedFile = false;
            }
        }
        long sqe = getSqe(flags, useFixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.STATX, opId);
        ioUring.prepareStatx(sqe, dfd, ioUring.emptyPathAddress(), AT_EMPTY_PATH, STATX_BASIC_STATS, statxBuffer.address());
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /** Returns the file descriptor tracked for a registered-file slot, or -1 when empty/unknown. */
    private int registeredFileFd(int slot) {
        if (slot < 0 || slot >= registeredFileTable.length) {
            return -1;
        }
        return registeredFileTable[slot];
    }

    /** Size of a {@code struct __kernel_timespec}: two little-endian i64 fields (tv_sec, tv_nsec). */
    public static final long TIMESPEC_BYTES = 16L;

    /**
     * Prepares an {@code IORING_OP_TIMEOUT} (opcode 11) with {@code count=1} and {@code flags=0}.
     * The completion result is {@code -ETIME} when the timeout fires, or 0 when {@code count}
     * completions arrive first.
     * <p>
     * <b>Caller-managed timespec:</b> {@code timespec} must be a native segment of at least
     * {@link #TIMESPEC_BYTES} bytes holding a {@code struct __kernel_timespec}: {@code tv_sec} as
     * an i64 at offset 0 and {@code tv_nsec} as an i64 at offset 8. The segment must stay valid
     * until the timeout is submitted (and until it completes for absolute timeouts or if you
     * re-arm the timer). JUring does <b>not</b> allocate the timespec: the previous
     * nanos-convenience overloads allocated ring-lifetime arena memory per call and were removed
     * because repeated calls leaked without bound.
     * <pre>{@code
     * try (Arena arena = Arena.ofConfined()) {
     *     MemorySegment ts = arena.allocate(16, 8);
     *     ts.set(JAVA_LONG, 0, 0);           // tv_sec
     *     ts.set(JAVA_LONG, 8, 20_000_000);  // tv_nsec = 20 ms
     *     long timeout = ring.prepareTimeout(ts);
     *     ring.submit();
     * }
     * }</pre>
     *
     * @param timespec native 16-byte {@code __kernel_timespec}, caller-managed
     * @return the operation handle
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_TIMEOUT}
     */
    public long prepareTimeout(MemorySegment timespec) {
        return prepareTimeoutInternal(timespec, 1, 0, (byte) 0);
    }

    /**
     * Prepares an {@code IORING_OP_TIMEOUT} with explicit SQE flags.
     *
     * <p>Equivalent to {@link #prepareTimeout(MemorySegment)} with the given SQE flags; the
     * timespec rules of that method apply.
     *
     * @param timespec   native 16-byte {@code __kernel_timespec}, caller-managed
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareTimeout(MemorySegment timespec, SqeOptions... sqeOptions) {
        return prepareTimeoutInternal(timespec, 1, 0, SqeOptions.combineOptions(sqeOptions));
    }

    /**
     * Prepares an {@code IORING_OP_TIMEOUT} with an explicit completion {@code count} and
     * {@code IORING_TIMEOUT_*} flags. The timespec is caller-managed; see
     * {@link #prepareTimeout(MemorySegment)}.
     *
     * @param timespec native 16-byte {@code __kernel_timespec}, caller-managed
     * @param count    number of completions that complete the timeout early (0 = a plain timeout)
     * @param flags    {@code IORING_TIMEOUT_*} flags ({@code ABS}, {@code BOOTTIME}, {@code ETIME})
     * @return the operation handle ({@code user_data})
     */
    public long prepareTimeout(MemorySegment timespec, int count, int flags) {
        return prepareTimeoutInternal(timespec, count, flags, (byte) 0);
    }

    /**
     * Prepares an {@code IORING_OP_TIMEOUT} with an explicit completion count, timeout flags
     * and SQE flags.
     *
     * @param timespec   native 16-byte {@code __kernel_timespec}, caller-managed
     * @param count      number of completions that complete the timeout early
     * @param flags      {@code IORING_TIMEOUT_*} flags
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the operation handle ({@code user_data})
     */
    public long prepareTimeout(MemorySegment timespec, int count, int flags, SqeOptions... sqeOptions) {
        return prepareTimeoutInternal(timespec, count, flags, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareTimeoutInternal(MemorySegment timespec, int count, int flags, byte sqeFlags) {
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_TIMEOUT, "IORING_OP_TIMEOUT");
        Objects.requireNonNull(timespec, "timespec");
        if (!timespec.isNative()) {
            throw new IllegalArgumentException("timespec must be native memory, not a heap segment");
        }
        if (timespec.byteSize() < TIMESPEC_BYTES) {
            throw new IllegalArgumentException("timespec must be at least " + TIMESPEC_BYTES
                    + " bytes (struct __kernel_timespec: tv_sec + tv_nsec), got " + timespec.byteSize());
        }
        if (!timespec.scope().isAlive()) {
            throw new IllegalStateException("timespec scope is already closed");
        }
        if (!timespec.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("timespec is confined to another thread");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Invalid timeout count: " + count);
        }
        long sqe = getSqe(sqeFlags, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.TIMEOUT, opId);
        ioUring.prepareTimeout(sqe, timespec.address(), count, flags);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    private void requireOpcodeSupported(byte opcode, String name) {
        if (!ioUring.isOpcodeSupported(opcode)) {
            throw new UnsupportedOperationException("kernel does not support " + name + " (errno " + EOPNOTSUPP + ": " + NativeDispatcher.C.strerror(EOPNOTSUPP) + ")");
        }
    }

    // ------------------------------------------------------------------
    // Linked timeouts (B3)
    // ------------------------------------------------------------------

    /**
     * Prepares an {@code IORING_OP_LINK_TIMEOUT} (opcode 15) that arms a timer for the
     * immediately preceding request in the current submission batch. The target request must
     * carry {@link SqeOptions#IOSQE_IO_LINK} and must be prepared immediately before this
     * call; no other {@code prepare*} call may be queued in between. Both SQEs are submitted
     * by the next {@code submit()/submitAndWait*()}.
     *
     * <p><b>Semantics (frozen, verified on the live kernel):</b>
     * <ul>
     *   <li>timeout fires first: the target completes with {@code -ECANCELED} and this
     *       timeout request completes with {@code -ETIME} ({@code 0} with
     *       {@link #IORING_TIMEOUT_ETIME_SUCCESS});</li>
     *   <li>target completes first: this timeout request is disarmed and completes with
     *       {@code -ECANCELED}; the target keeps its real result;</li>
     *   <li>each request posts exactly one completion.</li>
     * </ul>
     *
     * <p><b>Caller-managed timespec:</b> same rule as {@link #prepareTimeout(MemorySegment)}:
     * a native, alive, thread-confined segment of at least 16 bytes holding
     * {@code struct __kernel_timespec}; it must stay valid until the timeout request's
     * completion has been reaped (conservative lifetime rule; the kernel copies the value at
     * submission).
     *
     * @param timespec native 16-byte {@code __kernel_timespec}, caller-managed
     * @param flags    {@code IORING_TIMEOUT_ABS | BOOTTIME | REALTIME | ETIME_SUCCESS}
     *                 (update/multishot flags are rejected by the kernel for linked timeouts)
     * @return the timeout request's operation handle
     * @throws IllegalStateException         when the preceding SQE is not a linkable target in
     *                                       the same unsubmitted batch
     * @throws IllegalArgumentException      when {@code timespec}/{@code flags} are invalid
     * @throws UnsupportedOperationException when the kernel lacks {@code IORING_OP_LINK_TIMEOUT}
     */
    public long prepareLinkTimeout(MemorySegment timespec, int flags) {
        return prepareLinkTimeoutInternal(timespec, flags, (byte) 0);
    }

    /**
     * Prepares an {@code IORING_OP_LINK_TIMEOUT} for the preceding linked request, with
     * explicit SQE flags.
     *
     * <p>The target SQE rules of {@link #prepareLinkTimeout(MemorySegment, int)} apply.
     *
     * @param timespec   native 16-byte {@code __kernel_timespec}, caller-managed
     * @param flags      {@code IORING_TIMEOUT_*} flags accepted by linked timeouts
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the timeout request's operation handle
     */
    public long prepareLinkTimeout(MemorySegment timespec, int flags, SqeOptions... sqeOptions) {
        return prepareLinkTimeoutInternal(timespec, flags, SqeOptions.combineOptions(sqeOptions));
    }

    /**
     * Linked-timeout variant with relative time and no {@code IORING_TIMEOUT_*} flags.
     *
     * <p>The target SQE rules of {@link #prepareLinkTimeout(MemorySegment, int)} apply.
     *
     * @param timespec native 16-byte {@code __kernel_timespec}, caller-managed
     * @return the timeout request's operation handle
     */
    public long prepareLinkTimeout(MemorySegment timespec) {
        return prepareLinkTimeoutInternal(timespec, 0, (byte) 0);
    }

    /** Flags accepted by {@code IORING_OP_LINK_TIMEOUT} on this kernel. */
    private static final int LINK_TIMEOUT_ALLOWED_FLAGS = IORING_TIMEOUT_ABS | IORING_TIMEOUT_BOOTTIME
            | IORING_TIMEOUT_REALTIME | IORING_TIMEOUT_ETIME_SUCCESS;

    private long prepareLinkTimeoutInternal(MemorySegment timespec, int flags, byte sqeFlags) {
        ensureOpenAndOwner();
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_LINK_TIMEOUT, "IORING_OP_LINK_TIMEOUT");
        Objects.requireNonNull(timespec, "timespec");
        if (!timespec.isNative()) {
            throw new IllegalArgumentException("timespec must be native memory, not a heap segment");
        }
        if (timespec.byteSize() < TIMESPEC_BYTES) {
            throw new IllegalArgumentException("timespec must be at least " + TIMESPEC_BYTES
                    + " bytes (struct __kernel_timespec: tv_sec + tv_nsec), got " + timespec.byteSize());
        }
        if (!timespec.scope().isAlive()) {
            throw new IllegalStateException("timespec scope is already closed");
        }
        if (!timespec.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("timespec is confined to another thread");
        }
        int invalidFlags = flags & ~LINK_TIMEOUT_ALLOWED_FLAGS;
        if (invalidFlags != 0) {
            throw new IllegalArgumentException("unsupported IORING_OP_LINK_TIMEOUT flags 0x"
                    + Integer.toHexString(invalidFlags) + " (allowed: ABS, BOOTTIME, REALTIME, ETIME_SUCCESS)");
        }
        // The linked timeout only has meaning directly after a linkable SQE in the same batch.
        long previous = ioUring.lastQueuedSqe();
        if (previous == 0L) {
            throw new IllegalStateException("prepareLinkTimeout requires the target SQE to be prepared first:"
                    + " call prepare*(..., SqeOptions.IOSQE_IO_LINK) immediately before this method, in the same"
                    + " unsubmitted submission batch");
        }
        if ((Sqe.getFlags(previous) & SqeOptions.IOSQE_IO_LINK.value) == 0) {
            throw new IllegalStateException("prepareLinkTimeout: the immediately preceding SQE does not carry"
                    + " IOSQE_IO_LINK; prepare the target with SqeOptions.IOSQE_IO_LINK");
        }
        if (Sqe.getOpcode(previous) == IoUringDispatcher.IORING_OP_LINK_TIMEOUT) {
            throw new IllegalStateException("prepareLinkTimeout: a linked timeout cannot follow another"
                    + " IORING_OP_LINK_TIMEOUT; the target must be an operation, not a timeout");
        }
        long sqe = getSqe(sqeFlags, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.LINK_TIMEOUT, opId);
        ioUring.prepareLinkTimeout(sqe, timespec.address(), flags);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    boolean supportsLinkTimeout() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_LINK_TIMEOUT);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JUring is closed");
        }
    }

    /**
     * Enforces the one-owner-thread contract documented on the class: the ring may only be
     * prepared/submitted/reaped/registered from the thread that created it. A ring is cheap;
     * create one per thread instead of sharing.
     */
    private void checkOwner() {
        Thread owner = ownerThread;
        if (owner != null && Thread.currentThread() != owner) {
            throw new WrongThreadException("JUring is owned by thread '" + owner.getName()
                    + "' but was called from '" + Thread.currentThread().getName()
                    + "'; create one ring per thread");
        }
    }

    private void ensureOpenAndOwner() {
        ensureOpen();
        checkOwner();
    }

    private long getSqe(byte flags, boolean fixedFile) {
        ensureOpenAndOwner();
        long sqe = ioUring.getSqe();
        if (sqe == 0L) {
            throw new IllegalStateException("Submission queue ring is full; call submit() before preparing more operations");
        }
        previousSqeAddress = lastSqeAddress;
        lastSqeAddress = sqe;
        if (fixedFile) flags |= SqeOptions.IOSQE_FIXED_FILE.value;
        if (flags != 0) {
            ioUring.setSqeFlag(sqe, flags);
        }
        return sqe;
    }

    /**
     * B10b: {@link #getSqe} for the read/write opcode family, where {@code sqe->ioprio}
     * carries the block-layer priority. Other opcodes reject a non-zero {@code ioprio} on
     * current kernels (verified: {@code -EINVAL} for NOP/FSYNC/STATX), so the ring default is
     * applied only here.
     */
    private long getRwSqe(byte flags, boolean fixedFile) {
        long sqe = getSqe(flags, fixedFile);
        if (defaultIoprio != 0) {
            Sqe.setIoprio(sqe, defaultIoprio);
        }
        return sqe;
    }

    /** Whether {@code sqe->ioprio} carries a block-layer priority for this opcode. */
    private static boolean supportsIoprio(byte opcode) {
        return opcode == IoUringDispatcher.IORING_OP_READ
                || opcode == IoUringDispatcher.IORING_OP_WRITE
                || opcode == IoUringDispatcher.IORING_OP_READV
                || opcode == IoUringDispatcher.IORING_OP_WRITEV
                || opcode == IoUringDispatcher.IORING_OP_READ_FIXED
                || opcode == IoUringDispatcher.IORING_OP_WRITE_FIXED;
    }

    /**
     * Submits all SQEs queued so far to the kernel without waiting for completions.
     *
     * <p>When the ring uses {@code IORING_SETUP_SQPOLL} this wakes the SQPOLL thread when
     * needed; otherwise it issues {@code io_uring_enter()}.
     */
    public void submit() {
        ensureOpenAndOwner();
        ioUring.submit();
    }

    /**
     * Submits all queued SQEs and waits until at least {@code minComplete} CQEs are available.
     *
     * @param minComplete minimum number of completions to wait for; must not exceed the ring's
     *                    completion-queue size (otherwise the wait could never be satisfied)
     * @return number of completions currently available in the CQ ring
     * @throws IllegalArgumentException when {@code minComplete} exceeds the CQ size
     */
    public int submitAndWait(int minComplete) {
        ensureOpenAndOwner();
        return ioUring.submitAndWait(minComplete);
    }

    /**
     * Submits all queued SQEs, waits until at least {@code minComplete} CQEs are available,
     * and drains ready CQEs into {@code consumer}.
     *
     * <p>{@code consumer} receives the same reusable {@link Result} instance for every
     * completion (see {@link ResultConsumer}); copy anything needed beyond the callback. If the
     * visible CQ is empty while work is outstanding, the implementation may issue one
     * non-blocking {@code enter(GETEVENTS)} to drain completions parked in the kernel CQ
     * overflow list.
     *
     * @param minComplete minimum number of completions to wait for
     * @param consumer    zero-allocation completion callback
     * @return number of completions reaped
     */
    public int submitAndWaitForBatchResult(int minComplete, ResultConsumer consumer) {
        ensureOpenAndOwner();
        return ioUring.submitAndWaitForBatchResult(minComplete, Integer.MAX_VALUE, consumer);
    }

    /**
     * Submits all queued SQEs, waits until at least {@code minComplete} CQEs are available,
     * and reaps up to {@code maxReap} CQEs into {@code consumer}.
     *
     * @param minComplete minimum number of completions to wait for before returning
     * @param maxReap     maximum number of completions to reap
     * @param consumer    zero-allocation completion callback
     * @return number of completions reaped
     */
    public int submitAndWaitForBatchResult(int minComplete, int maxReap, ResultConsumer consumer) {
        ensureOpenAndOwner();
        return ioUring.submitAndWaitForBatchResult(minComplete, maxReap, consumer);
    }

    /**
     * Reaps up to {@code batchSize} completions that are already available, without
     * blocking.
     *
     * @param batchSize maximum number of completions to reap
     * @param consumer  zero-allocation completion callback
     * @return the number of completions reaped (0 when none are ready)
     */
    public int forEachBatchResult(int batchSize, ResultConsumer consumer) {
        ensureOpenAndOwner();
        return ioUring.peekForBatchResult(batchSize, consumer);
    }

    /**
     * Reaps up to {@code batchSize} completions that are already available, without
     * blocking.
     *
     * @param batchSize maximum number of completions to reap
     * @param consumer  zero-allocation completion callback
     * @return the number of completions reaped (0 when none are ready)
     */
    public int peekForBatchResult(int batchSize, ResultConsumer consumer) {
        ensureOpenAndOwner();
        return ioUring.peekForBatchResult(batchSize, consumer);
    }

    /**
     * Reaps up to {@code batchSize} completions that are already available, without
     * blocking, and returns them as new {@link Result} instances.
     *
     * <p>The caller owns the returned results and must close them to release any native
     * buffer they carry.
     *
     * @param batchSize maximum number of completions to reap
     * @return the reaped results, empty when none are ready
     */
    public List<Result> peekForBatchResult(int batchSize) {
        ensureOpenAndOwner();
        return ioUring.peekForBatchResult(batchSize);
    }

    /**
     * Non-blocking reap for lifecycle helpers: reaps currently available completions and,
     * when none are visible and the ring uses {@code IORING_SETUP_DEFER_TASKRUN}, performs a
     * non-blocking {@code io_uring_enter(GETEVENTS, min_complete=0)} to run deferred task
     * work before peeking once more. Never blocks.
     *
     * @param batchSize maximum number of completions to reap
     * @param consumer  zero-allocation completion callback
     * @return number of completions reaped (0 when none are ready)
     */
    int pollForBatchResult(int batchSize, ResultConsumer consumer) {
        ensureOpenAndOwner();
        int reaped = ioUring.peekForBatchResult(batchSize, consumer);
        if (reaped == 0 && ioUring.pollCq() > 0) {
            reaped = ioUring.peekForBatchResult(batchSize, consumer);
        }
        return reaped;
    }

    /**
     * Waits until at least one completion is available, then reaps up to {@code batchSize}
     * completions and returns them as new {@link Result} instances.
     *
     * <p>The caller owns the returned results and must close them to release any native
     * buffer they carry.
     *
     * @param batchSize maximum number of completions to reap
     * @return the reaped results
     */
    public List<Result> waitForBatchResult(int batchSize) {
        ensureOpenAndOwner();
        return ioUring.waitForBatchResult(batchSize);
    }

    /**
     * Waits until at least {@code minComplete} CQEs are available and reaps them into {@code consumer}.
     *
     * <p>{@code consumer} receives the same reusable {@link Result} instance for every
     * completion (see {@link ResultConsumer}); copy anything needed beyond the callback.
     *
     * @param minComplete minimum number of completions to wait for; must not exceed the CQ size
     * @param consumer    zero-allocation completion callback
     * @return number of completions reaped
     * @throws IllegalArgumentException when {@code minComplete} exceeds the CQ size
     */
    public int waitForBatchResult(int minComplete, ResultConsumer consumer) {
        ensureOpenAndOwner();
        return ioUring.waitForBatchResult(minComplete, Integer.MAX_VALUE, consumer);
    }

    /**
     * Waits until at least {@code minComplete} CQEs are available and reaps up to {@code maxReap} CQEs into {@code consumer}.
     *
     * <p>{@code consumer} receives the same reusable {@link Result} instance for every
     * completion (see {@link ResultConsumer}); copy anything needed beyond the callback.
     *
     * @param minComplete minimum number of completions to wait for; must not exceed the CQ size
     * @param maxReap     maximum number of completions to reap
     * @param consumer    zero-allocation completion callback
     * @return number of completions reaped
     * @throws IllegalArgumentException when {@code minComplete} exceeds the CQ size
     */
    public int waitForBatchResult(int minComplete, int maxReap, ResultConsumer consumer) {
        ensureOpenAndOwner();
        return ioUring.waitForBatchResult(minComplete, maxReap, consumer);
    }

    /**
     * Waits for one completion and returns it as a new {@link Result}.
     *
     * <p>The caller owns the returned result and must close it to release any native buffer
     * it carries.
     *
     * @return the reaped completion
     */
    public Result waitForResult() {
        ensureOpenAndOwner();
        return ioUring.waitForResult();
    }

    /**
     * Waits for one completion and fills the supplied reusable {@link Result} with it.
     *
     * @param target result instance to reuse; returns the same instance
     * @return the populated {@code target}
     */
    public Result waitForResult(Result target) {
        ensureOpenAndOwner();
        return ioUring.waitForResult(target);
    }

    /**
     * Registers {@code nrOfBuffers} off-heap buffers of {@code size} bytes each for fixed
     * buffer I/O ({@code IORING_REGISTER_BUFFERS}).
     *
     * <p>Buffers can be registered only once per ring; unregister them with
     * {@link #unregisterBuffers()} before registering again. The returned segments must stay
     * valid until the buffers are unregistered or the ring is closed.
     *
     * @param size        size of one buffer in bytes
     * @param nrOfBuffers number of buffers to register
     * @return the registered buffer segments, in index order
     * @throws IllegalStateException when buffers are already registered or the ring is closed
     */
    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        ensureOpenAndOwner();
        if (this.registeredBuffersArray != null && this.registeredBuffersArray.length > 0) {
            throw new IllegalStateException("Buffers are already registered; cannot register twice");
        }
        MemorySegment[] result = ioUring.registerBuffers(size, nrOfBuffers);
        registeredBuffers.clear();
        registeredBuffers.addAll(Arrays.asList(result));
        this.registeredBuffersArray = result;
        this.registeredBufferAddresses = new long[result.length];
        this.registeredBufferSizes = new long[result.length];
        this.bufferInUse = new boolean[nrOfBuffers];
        for (int i = 0; i < result.length; i++) {
            this.registeredBufferAddresses[i] = result[i].address();
            this.registeredBufferSizes[i] = result[i].byteSize();
        }
        ioUring.setRegisteredBuffers(result);
        freeBufferStack = new int[nrOfBuffers];
        freeBufferTop = nrOfBuffers;
        for (int i = 0; i < nrOfBuffers; i++) {
            freeBufferStack[i] = i;
        }
        return result;
    }

    /**
     * Checks out a free registered buffer for exclusive use.
     *
     * @return the index of the checked-out buffer, or {@code -1} when all buffers are in use
     */
    public int checkOutBuffer() {
        ensureOpenAndOwner();
        if (freeBufferTop == 0) {
            return -1;
        }
        int idx = freeBufferStack[--freeBufferTop];
        if (bufferInUse != null && idx >= 0 && idx < bufferInUse.length) {
            bufferInUse[idx] = true;
        }
        return idx;
    }

    /**
     * Returns a registered buffer to the free list after it is no longer in use.
     *
     * @param bufferIndex index previously returned by {@link #checkOutBuffer()}
     * @throws IllegalArgumentException when the index is out of range
     * @throws IllegalStateException    when the buffer is not currently checked out
     */
    public void checkInBuffer(int bufferIndex) {
        ensureOpenAndOwner();
        if (bufferInUse == null || bufferIndex < 0 || bufferIndex >= bufferInUse.length) {
            throw new IllegalArgumentException("Invalid bufferIndex: " + bufferIndex);
        }
        if (!bufferInUse[bufferIndex]) {
            throw new IllegalStateException("Buffer index " + bufferIndex + " is already checked in (double check-in)");
        }
        bufferInUse[bufferIndex] = false;
        freeBufferStack[freeBufferTop++] = bufferIndex;
    }

    boolean isRingFdRegistered() {
        return ioUring.isRingFdRegistered();
    }

    boolean isSqNeedWakeup() {
        return ioUring.isSqNeedWakeup();
    }

    int ringFd() {
        return ioUring.ringFd();
    }

    /**
     * Registers file descriptors for fixed-file I/O ({@code IORING_REGISTER_FILES}).
     *
     * <p>The slot index of each descriptor is its position in the argument list; that index
     * is used by the {@code indexFD} overloads and by {@code IOSQE_FIXED_FILE}. A descriptor
     * may be closed after registration because the kernel holds its own reference.
     *
     * @param fileDescriptors files to register, in slot order
     * @return the kernel's result code ({@code 0} on success)
     * @throws IllegalStateException when the ring is closed
     */
    public int registerFiles(FileDescriptor... fileDescriptors) {
        ensureOpenAndOwner();
        int[] fds = new int[fileDescriptors.length];
        for (int i = 0; i < fileDescriptors.length; i++) {
            fds[i] = fileDescriptors[i].getFd();
        }
        int ret = ioUring.registerFiles(fds);
        registeredFileTable = fds.clone();
        return ret;
    }

    /**
     * Replaces a range of the registered-file table with new file descriptors
     * ({@code IORING_REGISTER_FILES_UPDATE}).
     *
     * @param offset          first slot to update
     * @param fileDescriptors new descriptors, one per slot starting at {@code offset}
     * @return the kernel's result code ({@code 0} on success)
     * @throws IllegalStateException when the range exceeds the tracked table capacity
     */
    public int registerFilesUpdate(int offset, int[] fileDescriptors) {
        ensureOpenAndOwner();
        int end = offset + fileDescriptors.length;
        if (offset < 0 || registeredFileTable.length < end) {
            // The kernel-side table and the JUring tracking table are created together and have the
            // same capacity, so this can only trigger on a broken invariant. Fail loudly instead of
            // silently leaving tracked fds stale (statx slot resolution reads this table).
            throw new IllegalStateException("Registered-file tracking out of sync: update range ["
                    + offset + ", " + end + ") exceeds tracked table capacity " + registeredFileTable.length
                    + "; register files through JUring.registerFiles/registerFileAdd first");
        }
        int ret = ioUring.registerFilesUpdate(offset, fileDescriptors);
        System.arraycopy(fileDescriptors, 0, registeredFileTable, offset, fileDescriptors.length);
        return ret;
    }

    // ------------------------------------------------------------------
    // Registration lifecycle: sparse fixed-file table + buffers
    // ------------------------------------------------------------------

    /**
     * Lazily registers a sparse fixed-file table of {@code capacity} empty ({@code -1}) slots.
     * Called automatically by {@link #registerFileAdd(FileDescriptor)} with
     * {@link #DEFAULT_FILE_TABLE_CAPACITY} slots.
     *
     * @throws IllegalArgumentException  if {@code capacity <= 0}
     * @throws IllegalStateException     if a file table is already registered on this ring
     */
    void initFileTable(int capacity) {
        ensureOpenAndOwner();
        if (capacity <= 0) {
            throw new IllegalArgumentException("Invalid file table capacity: " + capacity);
        }
        if (registeredFileTable.length > 0) {
            throw new IllegalStateException("A registered-file table is already initialised (capacity " + registeredFileTable.length + ")");
        }
        int[] fds = new int[capacity];
        Arrays.fill(fds, -1);
        ioUring.registerFiles(fds);
        registeredFileTable = fds;
    }

    /**
     * Adds a file to the sparse registered-file table, lazily initialising the table with
     * {@link #DEFAULT_FILE_TABLE_CAPACITY} slots on first use. The first free slot is filled with
     * {@code IORING_REGISTER_FILES_UPDATE}.
     *
     * @param fd file to register
     * @return the slot index, usable as {@code indexFD} with the {@code prepare*}/{@code *Fixed}
     * methods and with {@code IOSQE_FIXED_FILE}
     * @throws IllegalStateException if the table is full
     */
    public int registerFileAdd(FileDescriptor fd) {
        ensureOpenAndOwner();
        Objects.requireNonNull(fd, "fd");
        if (registeredFileTable.length == 0) {
            initFileTable(DEFAULT_FILE_TABLE_CAPACITY);
        }
        int slot = -1;
        for (int i = 0; i < registeredFileTable.length; i++) {
            if (registeredFileTable[i] == -1) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            throw new IllegalStateException("Registered-file table is full (capacity " + registeredFileTable.length + "); unregister a slot first");
        }
        ioUring.registerFilesUpdate(slot, new int[]{fd.getFd()});
        registeredFileTable[slot] = fd.getFd();
        return slot;
    }

    /**
     * Frees a slot of the sparse registered-file table by updating it with {@code -1}
     * ({@code IORING_REGISTER_FILES_UPDATE}). The slot is immediately reusable.
     *
     * @param slot slot index to free
     * @throws IllegalArgumentException when the slot is out of range
     * @throws IllegalStateException    when the slot is not registered
     */
    public void unregisterFileSlot(int slot) {
        ensureOpenAndOwner();
        if (slot < 0 || slot >= registeredFileTable.length) {
            throw new IllegalArgumentException("File slot out of range: " + slot + " (table capacity " + registeredFileTable.length + ")");
        }
        if (registeredFileTable[slot] == -1) {
            throw new IllegalStateException("File slot " + slot + " is not registered");
        }
        ioUring.registerFilesUpdate(slot, new int[]{-1});
        registeredFileTable[slot] = -1;
    }

    /**
     * Unregisters the whole registered-file set ({@code IORING_UNREGISTER_FILES}) and resets the
     * slot tracking so a later {@link #registerFileAdd(FileDescriptor)} re-initialises the table.
     * No-op when no file table is registered.
     */
    public void unregisterFiles() {
        ensureOpenAndOwner();
        if (registeredFileTable.length == 0) {
            return;
        }
        int ret = ioUring.unregisterFiles();
        if (ret < 0) {
            throw new IllegalStateException("Failed to unregister files: " + NativeDispatcher.C.strerror(-ret) + " (errno " + (-ret) + ")");
        }
        registeredFileTable = new int[0];
    }

    /**
     * Unregisters all registered buffers ({@code IORING_UNREGISTER_BUFFERS}) and resets the
     * buffer tracking/check-out state so a later {@link #registerBuffers(int, int)} succeeds again.
     * No-op when no buffers are registered.
     */
    public void unregisterBuffers() {
        ensureOpenAndOwner();
        if (registeredBuffersArray.length == 0) {
            return;
        }
        int ret = ioUring.unregisterBuffers();
        if (ret < 0) {
            throw new IllegalStateException("Failed to unregister buffers: " + NativeDispatcher.C.strerror(-ret) + " (errno " + (-ret) + ")");
        }
        registeredBuffers.clear();
        registeredBuffersArray = new MemorySegment[0];
        registeredBufferAddresses = new long[0];
        registeredBufferSizes = new long[0];
        bufferInUse = null;
        freeBufferStack = new int[0];
        freeBufferTop = 0;
    }

    /**
     * Best-effort runtime update of registered buffers through {@code IORING_REGISTER_BUFFERS_UPDATE}
     * (kernel dependent; available since Linux 5.13 when the kernel implements the opcode).
     * On success the tracking arrays are updated so subsequent fixed-buffer operations use the new
     * memory. The replacement segments must stay valid until the buffers are re-registered or the
     * ring is closed.
     *
     * @param offset  first buffer index to replace
     * @param buffers replacement segments, one per index starting at {@code offset}
     * @throws IllegalStateException         if no buffers are registered
     * @throws IllegalArgumentException      if the range or a segment is invalid
     * @throws UnsupportedOperationException if the kernel rejects {@code IORING_REGISTER_BUFFERS_UPDATE}
     *                                       (the message carries the errno)
     */
    public void updateRegisteredBuffers(int offset, MemorySegment... buffers) {
        ensureOpenAndOwner();
        Objects.requireNonNull(buffers, "buffers");
        if (registeredBuffersArray.length == 0) {
            throw new IllegalStateException("No registered buffers to update; call registerBuffers() first");
        }
        if (buffers.length == 0) {
            return;
        }
        if (offset < 0 || (long) offset + buffers.length > registeredBuffersArray.length) {
            throw new IllegalArgumentException("Update range out of bounds: offset=" + offset + ", count=" + buffers.length
                    + ", registered=" + registeredBuffersArray.length);
        }
        for (int i = 0; i < buffers.length; i++) {
            MemorySegment buffer = Objects.requireNonNull(buffers[i], "buffers[" + i + "]");
            if (!buffer.isNative()) {
                throw new IllegalArgumentException("Buffers must be native memory (index " + i + ")");
            }
            if (!buffer.scope().isAlive()) {
                throw new IllegalStateException("Segment scope is already closed (index " + i + ")");
            }
            if (!buffer.isAccessibleBy(Thread.currentThread())) {
                throw new WrongThreadException("Segment is confined to another thread (index " + i + ")");
            }
        }
        int ret = ioUring.registerBuffersUpdate(offset, buffers);
        if (ret < 0) {
            throw new UnsupportedOperationException("kernel does not support IORING_REGISTER_BUFFERS_UPDATE for this configuration (errno "
                    + (-ret) + ": " + NativeDispatcher.C.strerror(-ret) + ")");
        }
        for (int i = 0; i < buffers.length; i++) {
            int index = offset + i;
            registeredBuffersArray[index] = buffers[i];
            registeredBufferAddresses[index] = buffers[i].address();
            registeredBufferSizes[index] = buffers[i].byteSize();
            registeredBuffers.set(index, buffers[i]);
        }
        ioUring.setRegisteredBuffers(registeredBuffersArray);
    }

    // ------------------------------------------------------------------
    // B10: shared file multiplexing and block-layer I/O priority
    // ------------------------------------------------------------------

    /**
     * Returns the ring-wide default block-layer I/O priority.
     *
     * <p>The default is written into every read/write SQE prepared by this ring (other
     * opcodes are left untouched because the kernel rejects {@code ioprio} on them). A
     * non-zero handle-level {@link JuringFile#withIoPriority(short)} overrides it, and
     * {@link #setLastSqeIoprio(short)} overrides it for one raw SQE. The returned value is the
     * encoded {@code ioprio} field, where {@code 0} means "no ring default" (the handle or
     * submitting thread's priority applies).
     *
     * @return the default {@code ioprio} value
     */
    public short defaultIoprio() {
        return defaultIoprio;
    }

    /**
     * Sets the ring-wide default block-layer I/O priority applied to every read/write SQE
     * prepared by this ring.
     *
     * <p>This is a <b>hint</b> for the block layer: it only changes ordering when the active
     * I/O scheduler supports priorities ({@code bfq}/{@code mq-deadline}); {@code none} and
     * {@code kyber} ignore it. Build values with {@link IoPriority}. {@code 0} clears the ring
     * default so requests inherit the handle's or submitting thread's priority (for example
     * from {@code ionice}); a handle {@code withIoPriority(0)} does not by itself override a
     * non-zero ring default — use {@link #setLastSqeIoprio(short)} with {@code 0} to clear one
     * specific raw SQE.
     *
     * @param ioprio the encoded {@code ioprio} value ({@code 0} = no ring default)
     * @throws IllegalArgumentException when the value is not a valid encoding
     * @throws IllegalStateException    when the ring is closed
     */
    public void setDefaultIoprio(short ioprio) {
        ensureOpenAndOwner();
        if (ioprio != 0 && !IoPriority.isValid(ioprio)) {
            throw new IllegalArgumentException("invalid I/O priority encoding: " + ioprio);
        }
        this.defaultIoprio = ioprio;
    }

    /**
     * Applies an I/O priority to the SQE most recently prepared by a {@code prepare*} method.
     *
     * <p>This is the raw per-request escape hatch for callers that use the low-level
     * {@code prepare*}/{@link #submit()} API; it must be called on the owner thread after the
     * prepare call and before {@link #submit()}. {@code 0} clears the field on this SQE, so the
     * kernel inherits the thread's priority and any {@linkplain #setDefaultIoprio(short) ring
     * default} is overridden for this request only.
     *
     * <p>Only the read/write opcode family ({@code READ}, {@code WRITE}, {@code READV},
     * {@code WRITEV}, {@code READ_FIXED}, {@code WRITE_FIXED}) accepts {@code ioprio}: the
     * kernel rejects the request with {@code EINVAL} for other opcodes (verified for
     * {@code NOP}, {@code FSYNC} and {@code STATX}), so those are rejected here client-side.
     *
     * @param ioprio the encoded {@code ioprio} value ({@code 0} = inherit)
     * @throws IllegalArgumentException when the value is not a valid encoding, or when the
     *                                  most recently prepared SQE is not a read/write operation
     * @throws IllegalStateException    when the ring is closed or no SQE has been prepared yet
     */
    public void setLastSqeIoprio(short ioprio) {
        ensureOpenAndOwner();
        if (ioprio != 0 && !IoPriority.isValid(ioprio)) {
            throw new IllegalArgumentException("invalid I/O priority encoding: " + ioprio);
        }
        if (lastSqeAddress == 0L) {
            throw new IllegalStateException("setLastSqeIoprio: no SQE has been prepared by this ring yet");
        }
        byte opcode = Sqe.getOpcode(lastSqeAddress);
        if (!supportsIoprio(opcode)) {
            throw new IllegalArgumentException("setLastSqeIoprio: opcode " + opcode
                    + " does not carry a block-layer I/O priority; only read/write operations accept ioprio");
        }
        Sqe.setIoprio(lastSqeAddress, ioprio);
    }

    /** Address of the most recently prepared SQE ({@code 0} when none); package-private for tests. */
    long lastPreparedSqeAddress() {
        return lastSqeAddress;
    }

    /** Address of the previous SQE before the last one; package-private for tests. */
    long previousPreparedSqeAddress() {
        return previousSqeAddress;
    }

    /**
     * Attaches a {@link JuringFile} handle so its completions are routed to its own ledger
     * when any handle on this ring reaps (B10a). Called by {@code JuringFile.open} on the
     * owner thread; allocates only when the registry grows (open-time, never per request).
     */
    void attachFile(JuringFile file) {
        ensureOpenAndOwner();
        if (attachedFileCount == attachedFiles.length) {
            int newLength = attachedFiles.length == 0 ? 4 : attachedFiles.length << 1;
            attachedFiles = Arrays.copyOf(attachedFiles, newLength);
        }
        attachedFiles[attachedFileCount++] = file;
    }

    /** Detaches a closing {@link JuringFile}; safe after {@link #close()} of the ring. */
    void detachFile(JuringFile file) {
        for (int i = 0; i < attachedFileCount; i++) {
            if (attachedFiles[i] == file) {
                int last = --attachedFileCount;
                attachedFiles[i] = attachedFiles[last];
                attachedFiles[last] = null;
                return;
            }
        }
    }

    /** Sum of the in-flight ring requests owned by all attached {@link JuringFile} handles. */
    int attachedFilesInFlightCount() {
        int total = 0;
        for (int i = 0; i < attachedFileCount; i++) {
            total += attachedFiles[i].inFlightOperations();
        }
        return total;
    }

    /**
     * Routes one reaped completion to the attached {@link JuringFile} that issued it (B10a).
     *
     * <p>The reaping file is tried first (single-file fast path); siblings are scanned only
     * when the handle is not in the caller's ledger. A completion that matches no handle is
     * either a stray timer (ignored, as before) or raw un-reaped ring work, which is counted
     * on the reaping file so the existing ownership guard still rejects blocking helpers.
     *
     * @param result      the reaped completion
     * @param reapingFile the handle whose reaping path delivered the completion
     */
    void dispatchFileCompletion(Result result, JuringFile reapingFile) {
        if (reapingFile != null && reapingFile.deliverCompletion(result)) {
            return;
        }
        for (int i = 0; i < attachedFileCount; i++) {
            JuringFile file = attachedFiles[i];
            if (file != reapingFile && file.deliverCompletion(result)) {
                return;
            }
        }
        OperationType type = result.type();
        if (type == OperationType.TIMEOUT || type == OperationType.LINK_TIMEOUT) {
            // Stray timer completions carry no file operation and are ignored (B1 behaviour).
            return;
        }
        if (reapingFile != null) {
            reapingFile.noteForeignCompletion();
        }
    }

    /** Number of queued-but-not-yet-completed operations on the underlying ring. */
    /**
     * Number of queued-but-not-completed operations on the underlying ring. Read-only and safe
     * from any thread (used by {@code IoShard}/{@code RingGroup} stats); submission, reaping and
     * registration still require the owner thread.
     */
    int inFlightCount() {
        ensureOpen();
        return ioUring.inFlightCount();
    }

    /** Reads {@code stx_size} from a completed statx buffer (package-private helper for JuringFile). */
    long staticxSize(MemorySegment statxBuffer) {
        ensureOpenAndOwner();
        return ioUring.staticxSize(statxBuffer);
    }

    /**
     * Tests whether the ring was closed.
     *
     * @return {@code true} once {@link #close()} has been called
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Immutable snapshot of this ring's counters and occupancy (B5).
     *
     * <p>Readable from any thread, before/while the ring is open and after
     * {@link #close()}; unlike the native-touching B1 paths it does not call
     * {@code ensureOpen()} and never throws for a closed ring. While open, the snapshot is a
     * best-effort telemetry view (design §5). After close it returns the final frozen
     * snapshot: counters keep their final values, {@code sqQueued}/{@code cqReady} are
     * {@code 0} and the effective ring depths are retained.
     *
     * <p>Each {@link #getSharedWorkerRing(int, IoUringOptions...)} ring reports the counters
     * of its own underlying ring object.
     *
     * @return this ring's current (or final, once closed) immutable metrics snapshot
     */
    public RingMetrics ringMetrics() {
        RingMetrics frozen = this.frozenRingMetrics;
        return frozen != null ? frozen : ioUring.ringMetrics();
    }

    boolean supportsFallocate() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_FALLOCATE);
    }

    boolean supportsFtruncate() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_FTRUNCATE);
    }

    boolean supportsStatx() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_STATX);
    }

    boolean supportsTimeout() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_TIMEOUT);
    }

    /**
     * Closes the ring and releases all associated off-heap resources, using
     * {@link #DEFAULT_CLOSE_TIMEOUT_MILLIS} as the deadline for in-flight requests.
     * <p>
     * For rings initialized with {@link IoUringOptions#IORING_SETUP_SINGLE_ISSUER}, {@code close()}
     * must be invoked on the owner (submitting) thread so that kernel-registered ring file descriptors
     * are properly unregistered from the calling task table.
     */
    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }

    /**
     * Bounded ring shutdown. Submits queued SQEs, cancels every in-flight request
     * ({@code IORING_OP_ASYNC_CANCEL} with {@code ALL|ANY}), reaps completions until the
     * deadline expires using non-blocking polls, then force-closes the ring. Never blocks
     * longer than {@code timeoutMillis} waiting for completions and never throws because an
     * operation failed; idempotent (a second call is a no-op).
     *
     * <p>Requests that are still in flight when the deadline expires are counted in
     * {@link #abandonedAtClose()} and dropped with the ring mapping; the kernel releases
     * them with the ring descriptor. Completions with {@code -ECANCELED} reaped during the
     * drain are counted in {@link #cancelledAtClose()}.
     *
     * @param timeoutMillis deadline in milliseconds, {@code >= 0}
     */
    public void close(long timeoutMillis) {
        if (closed) {
            return;
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("close timeout must be >= 0: " + timeoutMillis);
        }
        checkOwner();
        closed = true;
        try {
            ioUring.closeBounded(timeoutMillis);
            this.abandonedAtClose = ioUring.abandonedAtClose();
            this.cancelledAtClose = ioUring.cancelledAtClose();
            // Cache the dispatcher's frozen final snapshot for the JUring-level accessor.
            this.frozenRingMetrics = ioUring.ringMetrics();
        } finally {
            // The SQE mapping is unmapped after close: never hand out a stale address.
            lastSqeAddress = 0L;
            previousSqeAddress = 0L;
            if (readBufferPool != null) {
                readBufferPool.close();
                readBufferPool = null;
            }
        }
    }

    /**
     * Returns the number of {@code -ECANCELED} completions reaped during the most recent
     * {@link #close()}.
     *
     * @return the count of cancelled requests observed during shutdown
     */
    public long cancelledAtClose() {
        return cancelledAtClose;
    }

    /**
     * Returns the number of requests that were still in flight when the most recent bounded
     * {@link #close(long)} gave up.
     *
     * @return the abandoned-request count
     */
    public int abandonedAtClose() {
        return abandonedAtClose;
    }
}
