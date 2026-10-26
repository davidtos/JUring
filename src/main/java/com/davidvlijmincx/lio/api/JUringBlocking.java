package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static com.davidvlijmincx.lio.api.DirectoryFileDescriptorFlags.AT_FDCWD;
import static com.davidvlijmincx.lio.api.IoUringDispatcher.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Zero-GC, Virtual-Thread-friendly blocking wrapper over {@code io_uring}.
 */
public class JUringBlocking implements AutoCloseable {

    private static final int STATE_IDLE = 0;
    private static final int STATE_PARKED = 1;
    private static final int STATE_DONE = 2;

    private static final VarHandle VH_INT_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle VH_LONG_ARRAY = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle VH_MPSC_TAIL;
    private static final VarHandle VH_FREE_SLOT_HEAD;
    private static final VarHandle VH_FREE_SLOT_TAIL;
    private static final VarHandle VH_FREE_BUF_HEAD;
    private static final VarHandle VH_FREE_BUF_TAIL;
    private static final VarHandle VH_POLLER_PARKED;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            VH_MPSC_TAIL = l.findVarHandle(JUringBlocking.class, "mpscTail", long.class);
            VH_FREE_SLOT_HEAD = l.findVarHandle(JUringBlocking.class, "freeSlotHead", long.class);
            VH_FREE_SLOT_TAIL = l.findVarHandle(JUringBlocking.class, "freeSlotTail", long.class);
            VH_FREE_BUF_HEAD = l.findVarHandle(JUringBlocking.class, "freeBufHead", long.class);
            VH_FREE_BUF_TAIL = l.findVarHandle(JUringBlocking.class, "freeBufTail", long.class);
            VH_POLLER_PARKED = l.findVarHandle(JUringBlocking.class, "pollerParked", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public final Duration timeout;
    private final LibCDispatcher libc = NativeDispatcher.C;
    private IoUringDispatcher ioUringDispatcher;
    private volatile boolean running = true;
    private final Thread pollerThread;
    @SuppressWarnings("unused")
    private volatile int pollerParked;

    // Pre-allocated slot table (power-of-two size)
    private final int slotCapacity;
    private final int slotMask;

    // Lock-free Vyukov MPMC free-slot pool
    private final int[] freeSlotBuffer;
    private final long[] freeSlotSeq;
    @SuppressWarnings("unused")
    private volatile long freeSlotHead;
    @SuppressWarnings("unused")
    private volatile long freeSlotTail;

    // Lock-free Vyukov MPSC submission ring (many Virtual Threads -> 1 Poller Thread)
    private final int[] mpscBuffer;
    private final long[] mpscSeq;
    @SuppressWarnings("unused")
    private volatile long mpscTail;
    private long mpscHead;

    // Flat primitive request/response slot arrays
    private final byte[] slotOpcode;
    private final byte[] slotSqeFlags;
    private final int[] slotFd;
    private final long[] slotOff;
    private final long[] slotAddr;
    private final int[] slotLen;
    private final int[] slotRwFlags;
    private final short[] slotBufIndex;
    private final int[] slotFileIndex;
    private final long[] slotFreeAddr;
    private final long[] slotResult;
    private final int[] slotState;
    private final Thread[] slotThread;

    // Registered buffer state & lock-free MPMC buffer index pool
    private volatile MemorySegment[] registeredBuffers;
    private volatile long[] registeredBufferAddresses;
    private volatile long[] registeredBufferSizes;
    private volatile int registeredBufMask;
    private volatile int[] freeBufBuffer;
    private volatile long[] freeBufSeq;
    @SuppressWarnings("unused")
    private volatile long freeBufHead;
    @SuppressWarnings("unused")
    private volatile long freeBufTail;

    // Synchronous control task handoff to single-issuer poller thread
    private final AtomicReference<ControlTask<?>> controlTask = new AtomicReference<>();

    private static final class ControlTask<T> {
        final Callable<T> action;
        final Thread caller;
        volatile T result;
        volatile Throwable error;
        volatile boolean done;

        ControlTask(Callable<T> action, Thread caller) {
            this.action = action;
            this.caller = caller;
        }
    }

    public JUringBlocking(int queueDepth, IoUringOptions... ioUringFlags) {
        this(queueDepth, Duration.ofMillis(-1), ioUringFlags);
    }

    public JUringBlocking(int queueDepth, Duration timeout, IoUringOptions... ioUringFlags) {
        this.timeout = timeout;
        int minCap = Math.max(256, queueDepth * 8);
        int cap = Integer.highestOneBit(minCap - 1) << 1;
        if (cap < 256) {
            cap = 256;
        }
        this.slotCapacity = cap;
        this.slotMask = cap - 1;

        this.freeSlotBuffer = new int[cap];
        this.freeSlotSeq = new long[cap];
        for (int i = 0; i < cap; i++) {
            this.freeSlotBuffer[i] = i;
            this.freeSlotSeq[i] = i + 1;
        }
        this.freeSlotHead = 0L;
        this.freeSlotTail = cap;

        this.mpscBuffer = new int[cap];
        this.mpscSeq = new long[cap];
        for (int i = 0; i < cap; i++) {
            this.mpscSeq[i] = i;
        }
        this.mpscTail = 0L;
        this.mpscHead = 0L;

        this.slotOpcode = new byte[cap];
        this.slotSqeFlags = new byte[cap];
        this.slotFd = new int[cap];
        this.slotOff = new long[cap];
        this.slotAddr = new long[cap];
        this.slotLen = new int[cap];
        this.slotRwFlags = new int[cap];
        this.slotBufIndex = new short[cap];
        this.slotFileIndex = new int[cap];
        this.slotFreeAddr = new long[cap];
        this.slotResult = new long[cap];
        this.slotState = new int[cap];
        this.slotThread = new Thread[cap];

        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicReference<Throwable> initError = new AtomicReference<>();

        this.pollerThread = Thread.ofPlatform()
                .name("JUringBlocking-Poller")
                .daemon(true)
                .start(() -> {
                    try {
                        this.ioUringDispatcher = IoUringDispatcher.create(queueDepth, ioUringFlags);
                    } catch (Throwable t) {
                        initError.set(t);
                        initLatch.countDown();
                        return;
                    }
                    initLatch.countDown();
                    pollerLoop();
                });

        try {
            initLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while initializing JUringBlocking", e);
        }

        Throwable err = initError.get();
        if (err != null) {
            if (err instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to initialize JUringBlocking", err);
        }
    }

    private void pollerLoop() {
        final IoUringDispatcher dispatcher = this.ioUringDispatcher;
        final int sqEntries = dispatcher.sqRingEntries();
        final int[] inFlightHolder = new int[2]; // [0] = totalInFlight, [1] = unboundedInFlight

        final IoUringDispatcher.RawCqeConsumer cqeConsumer = (userData, res, flags) -> {
            int slotId = (int) userData;
            byte op = slotOpcode[slotId];
            if ( isUnboundedOp(op) ) {
                inFlightHolder[1]--;
            }
            inFlightHolder[0]--;
            slotResult[slotId] = res;
            Thread vt = slotThread[slotId];
            VH_INT_ARRAY.setRelease(slotState, slotId, STATE_DONE);
            if (vt != null) {
                LockSupport.unpark(vt);
            }
        };

        while (running) {
            runPendingControlTask();

            int preparedNow = 0;
            while ((inFlightHolder[0] - inFlightHolder[1]) < sqEntries && preparedNow < sqEntries) {
                long head = this.mpscHead;
                int idx = (int) (head & this.slotMask);
                long seq = (long) VH_LONG_ARRAY.getAcquire(this.mpscSeq, idx);
                if (seq != head + 1) {
                    break;
                }

                long sqe = dispatcher.getSqe();
                if (sqe == 0L) {
                    break;
                }

                int slotId = this.mpscBuffer[idx];
                VH_LONG_ARRAY.setRelease(this.mpscSeq, idx, head + this.slotCapacity);
                this.mpscHead = head + 1;

                byte op = slotOpcode[slotId];
                ZeroGcSqe.setOpcode(sqe, op);
                ZeroGcSqe.setFlags(sqe, slotSqeFlags[slotId]);
                ZeroGcSqe.setFd(sqe, slotFd[slotId]);
                ZeroGcSqe.setOff(sqe, slotOff[slotId]);
                ZeroGcSqe.setAddr(sqe, slotAddr[slotId]);
                ZeroGcSqe.setLen(sqe, slotLen[slotId]);
                ZeroGcSqe.setRwFlags(sqe, slotRwFlags[slotId]);
                ZeroGcSqe.setBufIndex(sqe, slotBufIndex[slotId]);
                ZeroGcSqe.setFileIndex(sqe, slotFileIndex[slotId]);
                ZeroGcSqe.setUserData(sqe, slotId);

                inFlightHolder[0]++;
                if (isUnboundedOp(op)) {
                    inFlightHolder[1]++;
                }
                preparedNow++;
            }

            dispatcher.reapRawCqes(Integer.MAX_VALUE, cqeConsumer);

            int totalInFlight = inFlightHolder[0];
            int unboundedInFlight = inFlightHolder[1];

            if (totalInFlight > 0) {
                if (unboundedInFlight == 0) {
                    dispatcher.submitAndWait(1);
                    dispatcher.reapRawCqes(Integer.MAX_VALUE, cqeConsumer);
                } else {
                    if (preparedNow > 0) {
                        dispatcher.submit();
                    }
                    int reaped = dispatcher.reapRawCqes(Integer.MAX_VALUE, cqeConsumer);
                    if (reaped == 0 && dispatcher.isDeferTaskrun()) {
                        dispatcher.flushDeferredTaskrun();
                        reaped = dispatcher.reapRawCqes(Integer.MAX_VALUE, cqeConsumer);
                    }
                    if (reaped == 0 && !hasPendingMpsc() && controlTask.get() == null) {
                        VH_POLLER_PARKED.setVolatile(this, 1);
                        if (!hasPendingMpsc() && controlTask.get() == null && running) {
                            LockSupport.parkNanos(this, 50_000L);
                        }
                        VH_POLLER_PARKED.setRelease(this, 0);
                    }
                }
            } else {
                if (!hasPendingMpsc() && controlTask.get() == null && running) {
                    VH_POLLER_PARKED.setVolatile(this, 1);
                    if (!hasPendingMpsc() && controlTask.get() == null && running) {
                        LockSupport.park(this);
                    }
                    VH_POLLER_PARKED.setRelease(this, 0);
                }
            }
        }

        runPendingControlTask();
    }

    private static boolean isUnboundedOp(byte op) {
        return op == IORING_OP_ACCEPT || op == IORING_OP_RECV || op == IORING_OP_CONNECT || op == IORING_OP_SEND;
    }

    private boolean hasPendingMpsc() {
        long head = this.mpscHead;
        int idx = (int) (head & this.slotMask);
        long seq = (long) VH_LONG_ARRAY.getAcquire(this.mpscSeq, idx);
        return seq == head + 1;
    }

    private void runPendingControlTask() {
        ControlTask<?> task = controlTask.getAndSet(null);
        if (task != null) {
            executeControlTask(task);
        }
    }

    private static <T> void executeControlTask(ControlTask<T> task) {
        try {
            task.result = task.action.call();
        } catch (Throwable t) {
            task.error = t;
        } finally {
            task.done = true;
            LockSupport.unpark(task.caller);
        }
    }

    private <T> T runOnPoller(Callable<T> action) {
        if (Thread.currentThread() == pollerThread) {
            try {
                return action.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        ControlTask<T> task = new ControlTask<>(action, Thread.currentThread());
        while (!controlTask.compareAndSet(null, task)) {
            if (!running) {
                throw new IllegalStateException("JUringBlocking is closed");
            }
            Thread.onSpinWait();
        }
        wakePoller();
        boolean interrupted = false;
        while (!task.done) {
            if (!running && !task.done) {
                throw new IllegalStateException("JUringBlocking is closed");
            }
            LockSupport.park(this);
            if (Thread.interrupted()) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (task.error != null) {
            if (task.error instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(task.error);
        }
        return task.result;
    }

    private void wakePoller() {
        if ((int) VH_POLLER_PARKED.getAcquire(this) != 0) {
            LockSupport.unpark(pollerThread);
        }
    }

    private int acquireSlot() {
        final int mask = this.slotMask;
        final int cap = this.slotCapacity;
        while (true) {
            if (!running) {
                throw new IllegalStateException("JUringBlocking is closed");
            }
            long pos = (long) VH_FREE_SLOT_HEAD.getVolatile(this);
            int idx = (int) (pos & mask);
            long seq = (long) VH_LONG_ARRAY.getAcquire(this.freeSlotSeq, idx);
            long dif = seq - (pos + 1);
            if (dif == 0) {
                if (VH_FREE_SLOT_HEAD.compareAndSet(this, pos, pos + 1)) {
                    int slotId = this.freeSlotBuffer[idx];
                    VH_LONG_ARRAY.setRelease(this.freeSlotSeq, idx, pos + cap);
                    return slotId;
                }
            } else if (dif < 0) {
                LockSupport.parkNanos(this, 10_000L);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void releaseSlot(int slotId) {
        final int mask = this.slotMask;
        while (true) {
            long pos = (long) VH_FREE_SLOT_TAIL.getVolatile(this);
            int idx = (int) (pos & mask);
            long seq = (long) VH_LONG_ARRAY.getAcquire(this.freeSlotSeq, idx);
            long dif = seq - pos;
            if (dif == 0) {
                if (VH_FREE_SLOT_TAIL.compareAndSet(this, pos, pos + 1)) {
                    this.freeSlotBuffer[idx] = slotId;
                    VH_LONG_ARRAY.setRelease(this.freeSlotSeq, idx, pos + 1);
                    return;
                }
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private long submitAndAwaitSlot(
            byte opcode,
            byte sqeFlags,
            int fd,
            long off,
            long addr,
            int len,
            int rwFlags,
            short bufIndex,
            int fileIndex,
            long freeAddrOnComplete
    ) {
        int slotId = acquireSlot();
        slotOpcode[slotId] = opcode;
        slotSqeFlags[slotId] = sqeFlags;
        slotFd[slotId] = fd;
        slotOff[slotId] = off;
        slotAddr[slotId] = addr;
        slotLen[slotId] = len;
        slotRwFlags[slotId] = rwFlags;
        slotBufIndex[slotId] = bufIndex;
        slotFileIndex[slotId] = fileIndex;
        slotFreeAddr[slotId] = freeAddrOnComplete;
        slotThread[slotId] = Thread.currentThread();
        VH_INT_ARRAY.setRelease(slotState, slotId, STATE_PARKED);

        long tail = (long) VH_MPSC_TAIL.getAndAdd(this, 1L);
        int idx = (int) (tail & this.slotMask);
        while ((long) VH_LONG_ARRAY.getAcquire(this.mpscSeq, idx) != tail) {
            Thread.onSpinWait();
        }
        this.mpscBuffer[idx] = slotId;
        VH_LONG_ARRAY.setRelease(this.mpscSeq, idx, tail + 1);

        wakePoller();

        boolean interrupted = false;
        while ((int) VH_INT_ARRAY.getAcquire(slotState, slotId) != STATE_DONE) {
            if (!running && (int) VH_INT_ARRAY.getAcquire(slotState, slotId) != STATE_DONE) {
                throw new IllegalStateException("JUringBlocking closed while waiting for I/O completion");
            }
            LockSupport.park(this);
            if (Thread.interrupted()) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        long res = slotResult[slotId];
        long toFree = slotFreeAddr[slotId];
        if (toFree != 0L) {
            slotFreeAddr[slotId] = 0L;
            libc.free(toFree);
        }
        slotThread[slotId] = null;
        VH_INT_ARRAY.setRelease(slotState, slotId, STATE_IDLE);
        releaseSlot(slotId);
        return res;
    }

    public long read(FileDescriptor fd, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return read(fd, buffer, Math.toIntExact(buffer.byteSize()), offset, sqeOptions);
    }

    public long read(FileDescriptor fd, MemorySegment buffer, int length, long offset, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid read length: " + length);
        }
        return submitAndAwaitSlot(
                IORING_OP_READ,
                SqeOptions.combineOptions(sqeOptions),
                fd.getFd(),
                offset,
                buffer.address(),
                length,
                0,
                (short) 0,
                0,
                0L
        );
    }

    public long read(int indexFD, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return read(indexFD, buffer, Math.toIntExact(buffer.byteSize()), offset, sqeOptions);
    }

    public long read(int indexFD, MemorySegment buffer, int length, long offset, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid read length: " + length);
        }
        return submitAndAwaitSlot(
                IORING_OP_READ,
                (byte) (SqeOptions.combineOptions(sqeOptions) | SqeOptions.IOSQE_FIXED_FILE.value),
                indexFD,
                offset,
                buffer.address(),
                length,
                0,
                (short) 0,
                0,
                0L
        );
    }

    public long write(FileDescriptor fd, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return write(fd, buffer, Math.toIntExact(buffer.byteSize()), offset, sqeOptions);
    }

    public long write(FileDescriptor fd, MemorySegment buffer, int length, long offset, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid write length: " + length);
        }
        return submitAndAwaitSlot(
                IORING_OP_WRITE,
                SqeOptions.combineOptions(sqeOptions),
                fd.getFd(),
                offset,
                buffer.address(),
                length,
                0,
                (short) 0,
                0,
                0L
        );
    }

    public long write(FileDescriptor fd, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        MemorySegment buff = libc.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        return submitAndAwaitSlot(
                IORING_OP_WRITE,
                SqeOptions.combineOptions(sqeOptions),
                fd.getFd(),
                offset,
                buff.address(),
                bytes.length,
                0,
                (short) 0,
                0,
                buff.address()
        );
    }

    public long write(int indexFD, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return write(indexFD, buffer, Math.toIntExact(buffer.byteSize()), offset, sqeOptions);
    }

    public long write(int indexFD, MemorySegment buffer, int length, long offset, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid write length: " + length);
        }
        return submitAndAwaitSlot(
                IORING_OP_WRITE,
                (byte) (SqeOptions.combineOptions(sqeOptions) | SqeOptions.IOSQE_FIXED_FILE.value),
                indexFD,
                offset,
                buffer.address(),
                length,
                0,
                (short) 0,
                0,
                0L
        );
    }

    public long write(int indexFD, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        MemorySegment buff = libc.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        return submitAndAwaitSlot(
                IORING_OP_WRITE,
                (byte) (SqeOptions.combineOptions(sqeOptions) | SqeOptions.IOSQE_FIXED_FILE.value),
                indexFD,
                offset,
                buff.address(),
                bytes.length,
                0,
                (short) 0,
                0,
                buff.address()
        );
    }

    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        MemorySegment[] buffers = runOnPoller(() -> ioUringDispatcher.registerBuffers(size, nrOfBuffers));
        long[] addrs = new long[buffers.length];
        long[] sizes = new long[buffers.length];
        for (int i = 0; i < buffers.length; i++) {
            addrs[i] = buffers[i].address();
            sizes[i] = buffers[i].byteSize();
        }
        int poolCap = Integer.highestOneBit(Math.max(2, nrOfBuffers) - 1) << 1;
        int[] bufArr = new int[poolCap];
        long[] seqArr = new long[poolCap];
        for (int i = 0; i < poolCap; i++) {
            if (i < nrOfBuffers) {
                bufArr[i] = i;
                seqArr[i] = i + 1;
            } else {
                seqArr[i] = i;
            }
        }
        this.registeredBuffers = buffers;
        this.registeredBufferAddresses = addrs;
        this.registeredBufferSizes = sizes;
        this.registeredBufMask = poolCap - 1;
        this.freeBufBuffer = bufArr;
        this.freeBufSeq = seqArr;
        this.freeBufHead = 0L;
        this.freeBufTail = nrOfBuffers;
        return buffers;
    }

    public MemorySegment getRegisteredBuffer(int bufferIndex) {
        MemorySegment[] bufs = this.registeredBuffers;
        if (bufs == null || bufferIndex < 0 || bufferIndex >= bufs.length) {
            throw new IllegalArgumentException("Invalid registered buffer index: " + bufferIndex);
        }
        return bufs[bufferIndex];
    }

    /**
     * Checks out a registered buffer index from the pool.
     *
     * @return registered buffer index, or {@code -1} if none available
     */
    public int checkOutBuffer() {
        final int[] bufArr = this.freeBufBuffer;
        final long[] seqArr = this.freeBufSeq;
        if (bufArr == null || seqArr == null) {
            throw new IllegalStateException("Buffers have not been registered");
        }
        final int mask = this.registeredBufMask;
        final int cap = mask + 1;
        while (true) {
            long pos = (long) VH_FREE_BUF_HEAD.getVolatile(this);
            int idx = (int) (pos & mask);
            long seq = (long) VH_LONG_ARRAY.getAcquire(seqArr, idx);
            long dif = seq - (pos + 1);
            if (dif == 0) {
                if (VH_FREE_BUF_HEAD.compareAndSet(this, pos, pos + 1)) {
                    int bufIdx = bufArr[idx];
                    VH_LONG_ARRAY.setRelease(seqArr, idx, pos + cap);
                    return bufIdx;
                }
            } else if (dif < 0) {
                return -1;
            } else {
                Thread.onSpinWait();
            }
        }
    }

    /**
     * Returns a registered buffer index to the pool.
     *
     * @param bufferIndex index of the buffer to return
     */
    public void checkInBuffer(int bufferIndex) {
        final int[] bufArr = this.freeBufBuffer;
        final long[] seqArr = this.freeBufSeq;
        if (bufArr == null || seqArr == null) {
            throw new IllegalStateException("Buffers have not been registered");
        }
        final int mask = this.registeredBufMask;
        while (true) {
            long pos = (long) VH_FREE_BUF_TAIL.getVolatile(this);
            int idx = (int) (pos & mask);
            long seq = (long) VH_LONG_ARRAY.getAcquire(seqArr, idx);
            long dif = seq - pos;
            if (dif == 0) {
                if (VH_FREE_BUF_TAIL.compareAndSet(this, pos, pos + 1)) {
                    bufArr[idx] = bufferIndex;
                    VH_LONG_ARRAY.setRelease(seqArr, idx, pos + 1);
                    return;
                }
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private long validateRegisteredBuffer(int bufferIndex, int ioSize) {
        long[] addrs = this.registeredBufferAddresses;
        long[] sizes = this.registeredBufferSizes;
        if (addrs == null || sizes == null || bufferIndex < 0 || bufferIndex >= addrs.length) {
            throw new IllegalArgumentException("Invalid buffer index: " + bufferIndex);
        }
        if (ioSize < 0 || ioSize > sizes[bufferIndex]) {
            throw new IllegalArgumentException("I/O size " + ioSize + " exceeds registered buffer capacity " + sizes[bufferIndex]);
        }
        return addrs[bufferIndex];
    }

    public long readFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long bufAddr = validateRegisteredBuffer(bufferIndex, readSize);
        return submitAndAwaitSlot(
                IORING_OP_READ_FIXED,
                SqeOptions.combineOptions(sqeOptions),
                fd.getFd(),
                offset,
                bufAddr,
                readSize,
                0,
                (short) bufferIndex,
                0,
                0L
        );
    }

    public long readFixed(int indexFD, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long bufAddr = validateRegisteredBuffer(bufferIndex, readSize);
        return submitAndAwaitSlot(
                IORING_OP_READ_FIXED,
                (byte) (SqeOptions.combineOptions(sqeOptions) | SqeOptions.IOSQE_FIXED_FILE.value),
                indexFD,
                offset,
                bufAddr,
                readSize,
                0,
                (short) bufferIndex,
                0,
                0L
        );
    }

    public long writeFixed(FileDescriptor fd, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long bufAddr = validateRegisteredBuffer(bufferIndex, writeSize);
        return submitAndAwaitSlot(
                IORING_OP_WRITE_FIXED,
                SqeOptions.combineOptions(sqeOptions),
                fd.getFd(),
                offset,
                bufAddr,
                writeSize,
                0,
                (short) bufferIndex,
                0,
                0L
        );
    }

    public long writeFixed(int indexFD, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long bufAddr = validateRegisteredBuffer(bufferIndex, writeSize);
        return submitAndAwaitSlot(
                IORING_OP_WRITE_FIXED,
                (byte) (SqeOptions.combineOptions(sqeOptions) | SqeOptions.IOSQE_FIXED_FILE.value),
                indexFD,
                offset,
                bufAddr,
                writeSize,
                0,
                (short) bufferIndex,
                0,
                0L
        );
    }

    public long writeFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        validateRegisteredBuffer(bufferIndex, bytes.length);
        MemorySegment.copy(bytes, 0, this.registeredBuffers[bufferIndex], JAVA_BYTE, 0, bytes.length);
        return writeFixed(fd, bytes.length, offset, bufferIndex, sqeOptions);
    }

    public long writeFixed(int indexFD, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        validateRegisteredBuffer(bufferIndex, bytes.length);
        MemorySegment.copy(bytes, 0, this.registeredBuffers[bufferIndex], JAVA_BYTE, 0, bytes.length);
        return writeFixed(indexFD, bytes.length, offset, bufferIndex, sqeOptions);
    }

    public int registerFiles(FileDescriptor... fileDescriptors) {
        int[] fds = Arrays.stream(fileDescriptors).mapToInt(FileDescriptor::getFd).toArray();
        return runOnPoller(() -> ioUringDispatcher.registerFiles(fds));
    }

    public int registerFilesUpdate(int offset, int[] fileDescriptors) {
        return runOnPoller(() -> ioUringDispatcher.registerFilesUpdate(offset, fileDescriptors));
    }

    public int open(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        byte[] pathBytes = filePath.getBytes();
        MemorySegment pathAddr = libc.calloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathAddr, JAVA_BYTE, 0, pathBytes.length);
        return (int) submitAndAwaitSlot(
                IORING_OP_OPENAT,
                SqeOptions.combineOptions(sqeOptions),
                AT_FDCWD.value,
                0L,
                pathAddr.address(),
                mode,
                flags,
                (short) 0,
                0,
                pathAddr.address()
        );
    }

    public int open(Path path, int mode, LinuxOpenOptions... openOptions) {
        return open(path.toString(), LinuxOpenOptions.combineOptions(openOptions), mode);
    }

    public FileDescriptor openFile(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        int fd = open(filePath, flags, mode, sqeOptions);
        if (fd < 0) {
            throw new IllegalStateException("Failed to open file '" + filePath + "': " + libc.strerror(-fd));
        }
        return new FileDescriptor(fd);
    }

    public FileDescriptor openFile(Path path, int mode, LinuxOpenOptions... openOptions) {
        return openFile(path.toString(), LinuxOpenOptions.combineOptions(openOptions), mode);
    }

    public int openDirect(String filePath, LinuxOpenOptions flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        return openDirect(filePath, flags.getValue(), mode, fileIndex, sqeOptions);
    }

    public int openDirect(String filePath, int flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        if (fileIndex < 0) {
            throw new IllegalArgumentException("Invalid fileIndex: " + fileIndex);
        }
        byte[] pathBytes = filePath.getBytes();
        MemorySegment pathAddr = libc.calloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathAddr, JAVA_BYTE, 0, pathBytes.length);
        return (int) submitAndAwaitSlot(
                IORING_OP_OPENAT,
                SqeOptions.combineOptions(sqeOptions),
                AT_FDCWD.value,
                0L,
                pathAddr.address(),
                mode,
                flags,
                (short) 0,
                fileIndex + 1,
                pathAddr.address()
        );
    }

    public int openDirect(Path path, int mode, int fileIndex, LinuxOpenOptions... openOptions) {
        return openDirect(path.toString(), LinuxOpenOptions.combineOptions(openOptions), mode, fileIndex);
    }

    public int closeFd(FileDescriptor fd, SqeOptions... sqeOptions) {
        return closeFd(fd.getFd(), sqeOptions);
    }

    public int closeFd(int rawFd, SqeOptions... sqeOptions) {
        return (int) submitAndAwaitSlot(
                IORING_OP_CLOSE,
                SqeOptions.combineOptions(sqeOptions),
                rawFd,
                0L,
                0L,
                0,
                0,
                (short) 0,
                0,
                0L
        );
    }

    public int closeDirect(int fileIndex, SqeOptions... sqeOptions) {
        if (fileIndex < 0) {
            throw new IllegalArgumentException("Invalid fileIndex: " + fileIndex);
        }
        return (int) submitAndAwaitSlot(
                IORING_OP_CLOSE,
                SqeOptions.combineOptions(sqeOptions),
                0,
                0L,
                0L,
                0,
                0,
                (short) 0,
                fileIndex + 1,
                0L
        );
    }

    public int createServerSocket(int port, int backlog) {
        int fd = libc.createSocket();
        libc.setReuseAddrAndPort(fd);
        libc.bindAndListen(fd, port, backlog);
        return fd;
    }

    public int createClientSocket() {
        return libc.createSocket();
    }

    public int accept(int serverFd, SqeOptions... sqeOptions) {
        return (int) submitAndAwaitSlot(
                IORING_OP_ACCEPT,
                SqeOptions.combineOptions(sqeOptions),
                serverFd,
                0L,
                0L,
                0,
                0,
                (short) 0,
                0,
                0L
        );
    }

    public int connect(int fd, String host, int port, SqeOptions... sqeOptions) {
        MemorySegment sockAddr = libc.allocSockaddrIn(host, port);
        return (int) submitAndAwaitSlot(
                IORING_OP_CONNECT,
                SqeOptions.combineOptions(sqeOptions),
                fd,
                LibCDispatcher.SOCKADDR_IN_LAYOUT.byteSize(),
                sockAddr.address(),
                0,
                0,
                (short) 0,
                0,
                sockAddr.address()
        );
    }

    public long recv(int fd, MemorySegment buffer, int length, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid recv length: " + length);
        }
        return submitAndAwaitSlot(
                IORING_OP_RECV,
                SqeOptions.combineOptions(sqeOptions),
                fd,
                0L,
                buffer.address(),
                length,
                0,
                (short) 0,
                0,
                0L
        );
    }

    public long send(int fd, MemorySegment buffer, int length, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid send length: " + length);
        }
        return submitAndAwaitSlot(
                IORING_OP_SEND,
                SqeOptions.combineOptions(sqeOptions),
                fd,
                0L,
                buffer.address(),
                length,
                0,
                (short) 0,
                0,
                0L
        );
    }

    public long send(int fd, byte[] bytes, SqeOptions... sqeOptions) {
        MemorySegment sendBuf = libc.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, sendBuf, JAVA_BYTE, 0, bytes.length);
        return submitAndAwaitSlot(
                IORING_OP_SEND,
                SqeOptions.combineOptions(sqeOptions),
                fd,
                0L,
                sendBuf.address(),
                bytes.length,
                0,
                (short) 0,
                0,
                sendBuf.address()
        );
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(pollerThread);
        try {
            pollerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (ioUringDispatcher != null) {
            ioUringDispatcher.close();
        }
        for (int i = 0; i < slotCapacity; i++) {
            Thread t = slotThread[i];
            if (t != null) {
                LockSupport.unpark(t);
            }
        }
    }
}
