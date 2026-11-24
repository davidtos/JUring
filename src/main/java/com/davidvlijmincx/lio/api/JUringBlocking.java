package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.nio.file.Path;
import java.util.Objects;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static com.davidvlijmincx.lio.api.IoUringDispatcher.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Virtual-thread friendly blocking wrapper over {@code io_uring}.
 *
 * <p>The no-option {@code read}/{@code write}/{@code *Fixed} overloads are allocation-free on
 * the calling thread after setup; passing {@link SqeOptions} explicitly allocates the varargs
 * array at the call site. This is a hot-path property, not a general "zero-GC" guarantee.
 *
 * <p>An instance owns a {@link RingGroup} of poller shards (one platform thread and one
 * {@code io_uring} per worker). A blocking call picks the worker bound to the calling thread,
 * hands the request to that worker's event loop and parks the caller; the worker submits the
 * request, reaps its completion and unparks the caller. Callers park instead of blocking in a
 * native syscall, so virtual threads waiting on I/O do not pin their carrier thread and a
 * small platform-thread budget serves many virtual threads.
 *
 * <p><b>Capacity.</b> Each worker owns a pre-allocated pool of call records bound by
 * {@link BlockingOptions#withMaxConcurrentCalls(int)} (default: derived from the queue depth,
 * exactly as in 0.1.0). A blocking call admits itself by taking one record and releases it when
 * the completion is reaped; the record is the wait node, so no per-call object is ever
 * allocated. A call that arrives when every record is in use follows
 * {@link BlockingOptions#overflow()}: {@link OverflowPolicy#BLOCK} (default) parks on the
 * worker's admission monitor until a record is released, {@link OverflowPolicy#FAIL} throws
 * {@link IllegalStateException}. Size the budget to the expected number of concurrently blocked
 * calls; {@link BlockingOptions#estimatedBytes()} reports the resulting heap footprint
 * (~90 bytes per record). {@link #maxConcurrentCalls()}, {@link #parkedCallers()} and
 * {@link #overflowWaits()} expose the resulting state for tests and diagnostics.
 *
 * <p>Calls return the raw io_uring completion result: the transferred byte count for reads
 * and writes, the new file descriptor for opens, {@code 0} for closes, or the negative errno
 * on failure. They do not throw {@link JuringException}; misuse (closed instance, invalid
 * buffer or size) throws unchecked exceptions. A caller interrupted while parked requests a
 * kernel cancellation of its own request (issued once the request is in the ring) and gets
 * {@code -}{@link JuringException#EINTR} after the terminal completion is reaped, so the
 * caller's buffer is not touched after the method returns; the interrupt flag is preserved.
 * Methods are safe to call from multiple threads and from virtual threads.
 *
 * <p>Registered buffers and files are installed on every worker ring, so the {@code *Fixed}
 * and {@code indexFD} overloads work from any calling thread. {@link #close()} is bounded
 * and idempotent, and the instance must not be used after it.
 */
public class JUringBlocking implements AutoCloseable {

    private static final int STATE_IDLE = 0;
    private static final int STATE_PARKED = 1;
    private static final int STATE_DONE = 2;
    private static final int STATE_ABANDONED = 3;
    /** The record was handed to the ring; only now can a kernel cancellation target it. */
    private static final int STATE_SUBMITTED = 4;

    private static final int SPINS_BEFORE_YIELD = 64;

    static int onContendedSpin(int spins) {
        if (spins < SPINS_BEFORE_YIELD) {
            Thread.onSpinWait();
            return spins + 1;
        }
        Thread.yield();
        return 0;
    }

    private static final VarHandle VH_INT_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle VH_LONG_ARRAY = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle VH_BYTE_ARRAY = MethodHandles.arrayElementVarHandle(byte[].class);
    /** Top bit of {@code slotSqeFlags}: "cancel this request when it is submitted". */
    private static final byte CANCEL_FLAG_BIT = (byte) 0x80;
    private static final VarHandle VH_FREE_BUF_HEAD;
    private static final VarHandle VH_FREE_BUF_TAIL;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            VH_FREE_BUF_HEAD = l.findVarHandle(JUringBlocking.class, "freeBufHead", long.class);
            VH_FREE_BUF_TAIL = l.findVarHandle(JUringBlocking.class, "freeBufTail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final LibCDispatcher libc = NativeDispatcher.C;
    private final Worker[] workers;
    private final int workerMask;
    private final RingGroup group;
    private final BlockingOptions options;
    private final int recordsPerWorker;
    private final int maxConcurrentCalls;
    private final Object closeLock = new Object();
    private boolean closed;

    // Registered buffer state & lock-free MPMC buffer index pool
    /**
     * Owns the memory for the registered-buffer iovec table and buffers. It is deliberately not
     * a dispatcher arena: the same buffers are registered on every worker ring, so their memory
     * must outlive every worker/shard and may only be freed after the whole group terminated.
     */
    private final Arena registeredBufferArena = Arena.ofShared();
    private volatile boolean registeredBufferArenaClosed;
    private volatile MemorySegment[] registeredBuffers;
    private volatile long[] registeredBufferAddresses;
    private volatile long[] registeredBufferSizes;
    private volatile int registeredBufMask;
    private volatile int[] freeBufBuffer;
    private volatile long[] freeBufSeq;
    /**
     * Per-index ownership ledger for the registered-buffer pool (1 = checked out). Prevents a
     * double check-in from duplicating an index in the free queue and handing the same buffer
     * to two concurrent callers.
     */
    private volatile AtomicIntegerArray bufferInUse;
    @SuppressWarnings("unused")
    private volatile long freeBufHead;
    @SuppressWarnings("unused")
    private volatile long freeBufTail;

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

    private static final class Worker implements ShardBody {
        private static final MemorySegment GLOBAL_MEMORY = MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE);
        private static final VarHandle VH_LONG = JAVA_LONG.varHandle();
        private static final VarHandle VH_WORKER_MPSC_TAIL;
        private static final VarHandle VH_WORKER_FREE_SLOT_HEAD;
        private static final VarHandle VH_WORKER_FREE_SLOT_TAIL;
        private static final VarHandle VH_WORKER_POLLER_PARKED;
        private static final VarHandle VH_WORKER_IN_KERNEL;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                VH_WORKER_MPSC_TAIL = l.findVarHandle(Worker.class, "mpscTail", long.class);
                VH_WORKER_FREE_SLOT_HEAD = l.findVarHandle(Worker.class, "freeSlotHead", long.class);
                VH_WORKER_FREE_SLOT_TAIL = l.findVarHandle(Worker.class, "freeSlotTail", long.class);
                VH_WORKER_POLLER_PARKED = l.findVarHandle(Worker.class, "pollerParked", int.class);
                VH_WORKER_IN_KERNEL = l.findVarHandle(Worker.class, "inKernel", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final long EVENTFD_SENTINEL = Long.MIN_VALUE;
        static final long CANCEL_SENTINEL = Long.MIN_VALUE + 1;
        final JUringBlocking parent;
        final BlockingOptions options;
        final int workerId;
        final int eventFd;
        final long eventfdReadBuf;
        final long eventfdWriteBuf;
        @SuppressWarnings("unused")
        long padInKernel01, padInKernel02, padInKernel03, padInKernel04,
                padInKernel05, padInKernel06, padInKernel07;
        @SuppressWarnings("unused")
        volatile int inKernel;
        boolean eventfdArmed;
        final int slotCapacity;
        final int slotMask;
        final int[] freeSlotBuffer;
        final long[] freeSlotSeq;
        @SuppressWarnings("unused")
        long padFreeHead01, padFreeHead02, padFreeHead03, padFreeHead04,
                padFreeHead05, padFreeHead06, padFreeHead07;
        @SuppressWarnings("unused")
        volatile long freeSlotHead;
        @SuppressWarnings("unused")
        long padFreeTail01, padFreeTail02, padFreeTail03, padFreeTail04,
                padFreeTail05, padFreeTail06, padFreeTail07;
        @SuppressWarnings("unused")
        volatile long freeSlotTail;
        final int[] mpscBuffer;
        final long[] mpscSeq;
        @SuppressWarnings("unused")
        long padMpsc01, padMpsc02, padMpsc03, padMpsc04, padMpsc05, padMpsc06, padMpsc07;
        @SuppressWarnings("unused")
        volatile long mpscTail;
        long mpscHead;
        /**
         * Guards the saturation path only: callers wait here when every call record is in use.
         * The monitor is never taken while a record is available, and the wait set is the
         * JDK's own allocation-free queue of parked threads, so no per-waiter node exists.
         */
        final Object admissionLock = new Object();
        @SuppressWarnings("unused")
        long padWaiters01, padWaiters02, padWaiters03, padWaiters04,
                padWaiters05, padWaiters06, padWaiters07;
        @SuppressWarnings("unused")
        volatile int slotWaiters;
        final java.util.concurrent.atomic.AtomicLong overflowWaits =
                new java.util.concurrent.atomic.AtomicLong();
        /** Sized above the in-flight cap: at most one ready cancellation per in-flight request. */
        final int cancelRingSize;
        final long[] cancelRing;
        final Object cancelLock = new Object();
        @SuppressWarnings("unused")
        long padCancel01, padCancel02, padCancel03, padCancel04,
                padCancel05, padCancel06, padCancel07;
        volatile int cancelPending;
        /**
         * Set when a cancellation that can actually be issued is queued (its target is already
         * in the ring). Deferred cancellations do not set it, so a pending-but-not-drainable
         * cancellation never makes the poller spin instead of waiting.
         */
        volatile int cancelWake;
        final byte[] slotOpcode;
        final byte[] slotSqeFlags;
        final int[] slotFd;
        final long[] slotOff;
        final long[] slotAddr;
        final int[] slotLen;
        final int[] slotRwFlags;
        final short[] slotBufIndex;
        final int[] slotFileIndex;
        final long[] slotFreeAddr;
        final long[] slotResult;
        final int[] slotState;
        final Thread[] slotThread;
        final AtomicReference<ControlTask<?>> controlTask = new AtomicReference<>();
        volatile IoUringDispatcher dispatcher;
        volatile boolean running = true;
        volatile long shutdownDeadlineNanos;
        volatile Thread shardThread;
        volatile Throwable startupError;
        IoShard shard;
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        boolean resourcesFreed;
        @SuppressWarnings("unused")
        volatile int pollerParked;

        Worker(JUringBlocking parent, int workerId, int slotCapacity, int ringEntries, BlockingOptions options) {
            this.parent = parent;
            this.options = options;
            this.workerId = workerId;
            if (slotCapacity < 2 || Integer.bitCount(slotCapacity) != 1) {
                throw new IllegalArgumentException("slotCapacity must be a power of two >= 2: " + slotCapacity);
            }
            int cap = slotCapacity;
            this.slotCapacity = cap;
            this.slotMask = cap - 1;
            // The kernel rounds the requested SQ entries up to a power of two, so this bound
            // is above the real in-flight cap: at most one ready cancellation per in-flight
            // request can exist.
            int roundedEntries = Integer.highestOneBit(Math.max(8, ringEntries));
            if (roundedEntries != Math.max(8, ringEntries)) {
                roundedEntries <<= 1;
            }
            this.cancelRingSize = roundedEntries + 8;
            this.cancelRing = new long[cancelRingSize];

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

            this.eventFd = parent.libc.eventfd(0, LibCDispatcher.EFD_CLOEXEC | LibCDispatcher.EFD_NONBLOCK);
            if (this.eventFd < 0) {
                throw new RuntimeException("Failed to create eventfd for worker " + workerId);
            }
            this.eventfdReadBuf = parent.libc.mallocAddress(8L);
            this.eventfdWriteBuf = parent.libc.mallocAddress(8L);
            VH_LONG.set(GLOBAL_MEMORY, this.eventfdReadBuf, 0L);
            VH_LONG.set(GLOBAL_MEMORY, this.eventfdWriteBuf, 1L);
        }

        // ------------------------------------------------------------------
        // ShardBody seam: the tuned worker loop runs on the IoShard thread.
        // ------------------------------------------------------------------

    /**
         * Runs the existing poller loop on the shard platform thread. The ring, its native
         * memory, the thread, CPU affinity and the bounded ring teardown all belong to
         * {@link IoShard}; this body only borrows the shard-thread-confined dispatcher.
         */
        @Override
        public void run(ShardLoopContext ctx) {
            try {
                this.dispatcher = ctx.dispatcher();
                this.shardThread = Thread.currentThread();
            } catch (Throwable t) {
                this.startupError = t;
                throw t;
            } finally {
                bodyStarted.countDown();
            }
            try {
                pollerLoop(ctx);
            } finally {
                // No caller may stay parked once the body stops: requests that never got a
                // completion are abandoned and their callers observe IllegalStateException.
                running = false;
                failPendingControlTask();
                unparkQueuedSlots();
                wakeAdmissionWaiters();
            }
        }

        /** Foreign-thread wake hook used by {@code IoShard.requestStop()}/{@code close(...)}. */
        @Override
        public void wake() {
            wakePoller();
        }

        /**
         * Runs on the shard thread after the ring is closed: callers whose requests were
         * still in flight can now be released without touching a buffer the kernel still owns.
         */
        @Override
        public void afterRingClose() {
            unparkSubmittedSlots();
            unparkQueuedSlots();
            wakeAdmissionWaiters();
        }

        void failPendingControlTask() {
            ControlTask<?> task = controlTask.getAndSet(null);
            if (task != null) {
                if (!task.done) {
                    task.error = new IllegalStateException("JUringBlocking is closed");
                    task.done = true;
                }
                LockSupport.unpark(task.caller);
            }
        }

        /**
         * Unparks callers whose request was never handed to the ring. This is always safe: no
         * kernel operation can be using their buffer.
         */
        void unparkQueuedSlots() {
            for (int i = 0; i < slotCapacity; i++) {
                if ((int) VH_INT_ARRAY.getAcquire(slotState, i) == STATE_PARKED) {
                    Thread t = slotThread[i];
                    if (t != null) {
                        LockSupport.unpark(t);
                    }
                }
            }
        }

        /**
         * Unparks callers whose request is in the ring. Only safe once the ring is gone: until
         * then the kernel may still write into the caller's buffer. Called by
         * {@link JUringBlocking#close(long)} after the shard group terminated.
         */
        void unparkSubmittedSlots() {
            for (int i = 0; i < slotCapacity; i++) {
                if ((int) VH_INT_ARRAY.getAcquire(slotState, i) == STATE_SUBMITTED) {
                    Thread t = slotThread[i];
                    if (t != null) {
                        LockSupport.unpark(t);
                    }
                }
            }
        }

    /**
         * Frees this worker's own eventfd and its two 8-byte wakeup buffers, but only once the
         * shard thread has fully terminated (the armed wakeup READ may otherwise still write
         * into the read buffer while the ring is being closed).
         */
        void freeEventfdResourcesIfTerminated() {
            if (resourcesFreed) {
                return;
            }
            IoShard s = shard;
            if (s != null && !s.isTerminated()) {
                return;
            }
            resourcesFreed = true;
            if (eventFd >= 0) {
                parent.libc.close(eventFd);
            }
            if (eventfdReadBuf != 0L) {
                parent.libc.free(eventfdReadBuf);
            }
            if (eventfdWriteBuf != 0L) {
                parent.libc.free(eventfdWriteBuf);
            }
        }

        private void pollerLoop(ShardLoopContext ctx) {
            final IoUringDispatcher dispatcher = this.dispatcher;
            final int sqEntries = dispatcher.sqRingEntries();
            final int[] inFlightHolder = new int[2]; // [0] = totalInFlight, [1] = expectedFromUnpark

            final IoUringDispatcher.RawCqeConsumer cqeConsumer = (userData, res, flags) -> {
                if (userData == EVENTFD_SENTINEL || userData == CANCEL_SENTINEL) {
                    if (userData == EVENTFD_SENTINEL) {
                        eventfdArmed = false;
                    }
                    return;
                }
                int slotId = (int) userData;
                inFlightHolder[0]--;
                slotResult[slotId] = res;
                Thread vt = slotThread[slotId];
                if (VH_INT_ARRAY.compareAndSet(slotState, slotId, STATE_SUBMITTED, STATE_DONE)) {
                    if (vt != null) {
                        inFlightHolder[1]++;
                        LockSupport.unpark(vt);
                    }
                } else {
                    // The request was abandoned (close or an exception): release the record.
                    freeSlot(slotId);
                }
            };

            int unsubmitted = 0;

            while (running && !ctx.stopRequested()) {
                runPendingControlTask();
                cancelWake = 0;
                int drainedCancels = drainCancelRequests();
                if (drainedCancels > 0) {
                    unsubmitted += drainedCancels;
                }

                while (inFlightHolder[0] < (sqEntries - 1) && unsubmitted < (sqEntries - 1)) {
                    long head = this.mpscHead;
                    int idx = (int) (head & this.slotMask);
                    long seq = (long) VH_LONG_ARRAY.getAcquire(this.mpscSeq, idx);
                    if (seq != head + 1) {
                        break;
                    }

                    int slotId = this.mpscBuffer[idx];
                    if ((int) VH_INT_ARRAY.getAcquire(slotState, slotId) == STATE_ABANDONED) {
                        // The caller gave up while the record was queued: consume the queue
                        // entry and release the record without submitting anything.
                        VH_LONG_ARRAY.setRelease(this.mpscSeq, idx, head + this.slotCapacity);
                        this.mpscHead = head + 1;
                        freeSlot(slotId);
                        continue;
                    }

                    long sqe = dispatcher.getSqe();
                    if (sqe == 0L) {
                        break;
                    }

                    VH_LONG_ARRAY.setRelease(this.mpscSeq, idx, head + this.slotCapacity);
                    this.mpscHead = head + 1;

                    // Hand the record to the ring. The state flip is what allows a caller's
                    // interrupt to target this request in the kernel: a queued record has no
                    // in-flight request to cancel yet. A record abandoned between the check
                    // above and this CAS burns the reserved SQE as an ignored NOP.
                    if (!VH_INT_ARRAY.compareAndSet(slotState, slotId, STATE_PARKED, STATE_SUBMITTED)) {
                        Sqe.zero(sqe);
                        Sqe.setOpcode(sqe, IORING_OP_NOP);
                        Sqe.setUserData(sqe, CANCEL_SENTINEL);
                        unsubmitted++;
                        freeSlot(slotId);
                        continue;
                    }
                    byte sqeFlags = (byte) VH_BYTE_ARRAY.getVolatile(slotSqeFlags, slotId);
                    boolean cancelOnSubmit = (sqeFlags & CANCEL_FLAG_BIT) != 0;
                    if (cancelOnSubmit) {
                        VH_BYTE_ARRAY.setVolatile(slotSqeFlags, slotId, (byte) (sqeFlags & ~CANCEL_FLAG_BIT));
                        sqeFlags &= ~CANCEL_FLAG_BIT;
                    }
                    byte op = slotOpcode[slotId];
                    Sqe.setOpcode(sqe, op);
                    Sqe.setFlags(sqe, sqeFlags);
                    Sqe.setFd(sqe, slotFd[slotId]);
                    Sqe.setOff(sqe, slotOff[slotId]);
                    Sqe.setAddr(sqe, slotAddr[slotId]);
                    Sqe.setLen(sqe, slotLen[slotId]);
                    Sqe.setRwFlags(sqe, slotRwFlags[slotId]);
                    Sqe.setBufIndex(sqe, slotBufIndex[slotId]);
                    Sqe.setFileIndex(sqe, slotFileIndex[slotId]);
                    Sqe.setUserData(sqe, userData(head, slotId));

                    inFlightHolder[0]++;
                    if (inFlightHolder[1] > 0) {
                        inFlightHolder[1]--;
                    }
                    unsubmitted++;
                    if (cancelOnSubmit) {
                        // The caller interrupted while this record was still queued. Both SQEs
                        // go out in the same batch, target first, so the kernel sees the target
                        // in flight when it processes the cancellation. A linked target cannot
                        // take a successor SQE (it would join the user's chain), so its
                        // cancellation is queued for the next batch instead.
                        byte linkMask = (byte) (SqeOptions.IOSQE_IO_LINK.value
                                | SqeOptions.IOSQE_IO_HARDLINK.value);
                        long cancelSqe = (sqeFlags & linkMask) == 0 ? dispatcher.getSqe() : 0L;
                        if (cancelSqe != 0L) {
                            writeCancelSqe(cancelSqe, userData(head, slotId));
                            unsubmitted++;
                        } else {
                            enqueueReadyCancel(userData(head, slotId));
                        }
                    }
                }

                int reaped = dispatcher.reapRawCqes(Integer.MAX_VALUE, cqeConsumer);

                boolean canAcceptMore = inFlightHolder[0] < (sqEntries - 1);
                if (reaped > 0 || (canAcceptMore && hasPendingMpsc()) || controlTask.get() != null) {
                    if (unsubmitted >= (sqEntries >>> 1)) {
                        dispatcher.submit();
                        ctx.countRingSubmit();
                        unsubmitted = 0;
                    }
                    continue;
                }

                // Coalesce submissions from recently unparked VTs or concurrent submission bursts
                // before setting inKernel = 1 or entering the kernel.
                if (canAcceptMore && (inFlightHolder[1] > 0 || (unsubmitted > 0 && inFlightHolder[0] > 1))) {
                    int maxSpins = inFlightHolder[1] > 0 ? 2000 : 200;
                    boolean found = false;
                    for (int spin = 0; spin < maxSpins; spin++) {
                        if (hasPendingMpsc() || !running || ctx.stopRequested() || controlTask.get() != null) {
                            found = true;
                            break;
                        }
                        Thread.onSpinWait();
                    }
                    inFlightHolder[1] = 0;
                    if (found) {
                        continue;
                    }
                }

                if (inFlightHolder[0] > 0) {
                    if (controlTask.get() == null && running) {
                        if (!eventfdArmed) {
                            long sqe = dispatcher.getSqe();
                            if (sqe != 0L) {
                                Sqe.zero(sqe);
                                Sqe.setOpcode(sqe, IORING_OP_READ);
                                Sqe.setFd(sqe, eventFd);
                                Sqe.setAddr(sqe, eventfdReadBuf);
                                Sqe.setLen(sqe, 8);
                                Sqe.setUserData(sqe, EVENTFD_SENTINEL);
                                eventfdArmed = true;
                                unsubmitted++;
                            }
                        }
                        canAcceptMore = inFlightHolder[0] < (sqEntries - 1);
                        VH_WORKER_IN_KERNEL.setVolatile(this, 1);
                        VarHandle.fullFence();
                        if (!running || ctx.stopRequested() || controlTask.get() != null
                                || cancelWake != 0
                                || (canAcceptMore && hasPendingMpsc())) {
                            VH_WORKER_IN_KERNEL.setVolatile(this, 0);
                            if (unsubmitted > 0) {
                                dispatcher.submit();
                                ctx.countRingSubmit();
                                unsubmitted = 0;
                            }
                        } else {
                            if (unsubmitted > 0) {
                                ctx.countRingSubmit();
                            }
                            unsubmitted = 0;
                            dispatcher.submitAndWait(1);
                            VH_WORKER_IN_KERNEL.setVolatile(this, 0);
                            dispatcher.reapRawCqes(Integer.MAX_VALUE, cqeConsumer);
                        }
                    } else if (unsubmitted > 0) {
                        dispatcher.submit();
                        ctx.countRingSubmit();
                        unsubmitted = 0;
                    }
                } else {
                    if (unsubmitted > 0) {
                        dispatcher.submit();
                        ctx.countRingSubmit();
                        unsubmitted = 0;
                    }
                    if (controlTask.get() == null && running && !ctx.stopRequested()) {
                        VH_WORKER_POLLER_PARKED.setVolatile(this, 1);
                        VarHandle.fullFence();
                        if (!hasPendingMpsc() && controlTask.get() == null && running
                                && !ctx.stopRequested() && cancelWake == 0) {
                            LockSupport.park(this);
                        }
                        VH_WORKER_POLLER_PARKED.setVolatile(this, 0);
                    }
                }
            }

            runPendingControlTask();

            // Bounded shutdown drain: cancel every in-flight request and dispatch
            // the resulting completions so parked callers unblock. Unlike the earlier loop this
            // never blocks indefinitely on a cancellation completion that never arrives: the
            // drain is bounded by shutdownDeadlineNanos (set by JUringBlocking.close, or the
            // shard's shutdown budget) and uses non-blocking enters + short parks only.
            if (inFlightHolder[0] > 0) {
                try {
                    dispatcher.submit();
                    if (unsubmitted > 0) {
                        ctx.countRingSubmit();
                    }
                } catch (Throwable ignored) {
                    // best effort; the ring's own bounded close is the backstop
                }
                try {
                    long cancelSqe = dispatcher.getSqe();
                    if (cancelSqe == 0L) {
                        dispatcher.submit();
                        cancelSqe = dispatcher.getSqe();
                    }
                    if (cancelSqe != 0L) {
                        Sqe.zero(cancelSqe);
                        Sqe.setOpcode(cancelSqe, IORING_OP_ASYNC_CANCEL);
                        Sqe.setFd(cancelSqe, -1);
                        Sqe.setRwFlags(cancelSqe, 5); // IORING_ASYNC_CANCEL_ALL | IORING_ASYNC_CANCEL_ANY
                        Sqe.setUserData(cancelSqe, CANCEL_SENTINEL);
                        dispatcher.submit();
                    }
                } catch (Throwable ignored) {
                    // best effort; the ring's own bounded close is the backstop
                }
                long deadline = shutdownDeadlineNanos;
                if (deadline == 0L) {
                    deadline = System.nanoTime()
                            + Math.max(0L, shard.options().shutdownTimeoutMillis()) * 1_000_000L;
                }
                while (inFlightHolder[0] > 0) {
                    int reaped = 0;
                    try {
                        reaped = dispatcher.reapRawCqes(Integer.MAX_VALUE, cqeConsumer);
                    } catch (Throwable ignored) {
                        // fall through to the deadline check
                    }
                    if (reaped > 0) {
                        continue;
                    }
                    try {
                        if (dispatcher.pollCq() > 0) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        // fall through to the deadline check
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    LockSupport.parkNanos(Math.min(remaining, 100_000L));
                }
            }
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
            if (Thread.currentThread() == shardThread) {
                try {
                    return action.call();
                } catch (RuntimeException re) {
                    throw re;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
            ControlTask<T> task = new ControlTask<>(action, Thread.currentThread());
            int spins = 0;
            while (!controlTask.compareAndSet(null, task)) {
                if (!running) {
                    throw new IllegalStateException("JUringBlocking is closed");
                }
                spins = onContendedSpin(spins);
            }
            wakePoller();
            boolean interrupted = false;
            while (!task.done) {
                if (!running && !task.done) {
                    throw new IllegalStateException("JUringBlocking is closed");
                }
                LockSupport.park(this);
                if (!task.done) {
                    wakePoller();
                }
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
            VarHandle.fullFence();
            if ((int) VH_WORKER_IN_KERNEL.getVolatile(this) != 0) {
                if (VH_WORKER_IN_KERNEL.compareAndSet(this, 1, 0)) {
                    parent.libc.write(eventFd, eventfdWriteBuf, 8L);
                }
            }
            Thread poller = this.shardThread;
            if (poller != null && (int) VH_WORKER_POLLER_PARKED.getVolatile(this) != 0) {
                LockSupport.unpark(poller);
            }
        }

        private int acquireSlot() {
            if (!running) {
                throw new IllegalStateException("JUringBlocking is closed");
            }
            int slotId = tryAcquireSlot();
            if (slotId >= 0) {
                return slotId;
            }
            if (!running) {
                throw new IllegalStateException("JUringBlocking is closed");
            }
            if (options.overflow() == OverflowPolicy.FAIL) {
                throw new IllegalStateException("JUringBlocking is at its concurrent-call capacity ("
                        + slotCapacity + " records per worker, " + parent.workers.length
                        + " worker(s)); raise BlockingOptions.withMaxConcurrentCalls(...) or use "
                        + "OverflowPolicy.BLOCK");
            }
            return acquireSlotBlocking();
        }

        /**
         * Lock-free admission fast path: pops a free call record.
         *
         * @return a record id, or {@code -1} when every record of this worker is in use
         */
        private int tryAcquireSlot() {
            final int mask = this.slotMask;
            final int cap = this.slotCapacity;
            int spins = 0;
            while (true) {
                long pos = (long) VH_WORKER_FREE_SLOT_HEAD.getVolatile(this);
                int idx = (int) (pos & mask);
                long seq = (long) VH_LONG_ARRAY.getAcquire(this.freeSlotSeq, idx);
                long dif = seq - (pos + 1);
                if (dif == 0) {
                    if (VH_WORKER_FREE_SLOT_HEAD.compareAndSet(this, pos, pos + 1)) {
                        int slotId = this.freeSlotBuffer[idx];
                        VH_LONG_ARRAY.setRelease(this.freeSlotSeq, idx, pos + cap);
                        return slotId;
                    }
                } else if (dif < 0) {
                    return -1;
                } else {
                    spins = onContendedSpin(spins);
                }
            }
        }

        /**
         * Saturation path: wait on the admission monitor until a record is released. The wait
         * set is the JDK's own VM-managed queue of parked threads, so millions of blocked
         * callers need no per-waiter library object and no timer. {@code Object.wait} on a
         * virtual thread unmounts (JDK 25 floor), so the carrier is not pinned. The fast path
         * never enters this monitor.
         *
         * @return a record id, or {@code -1} when the caller was interrupted while waiting
         */
        private int acquireSlotBlocking() {
            overflowWaits.incrementAndGet();
            if (!running) {
                throw new IllegalStateException("JUringBlocking is closed");
            }
            synchronized (admissionLock) {
                slotWaiters++;
                try {
                    for (;;) {
                        if (!running) {
                            throw new IllegalStateException("JUringBlocking is closed");
                        }
                        int slotId = tryAcquireSlot();
                        if (slotId >= 0) {
                            return slotId;
                        }
                        try {
                            admissionLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return -1;
                        }
                    }
                } finally {
                    slotWaiters--;
                }
            }
        }

        private void releaseSlot(int slotId) {
            final int mask = this.slotMask;
            int spins = 0;
            for (;;) {
                long pos = (long) VH_WORKER_FREE_SLOT_TAIL.getVolatile(this);
                int idx = (int) (pos & mask);
                long seq = (long) VH_LONG_ARRAY.getAcquire(this.freeSlotSeq, idx);
                long dif = seq - pos;
                if (dif == 0) {
                    if (VH_WORKER_FREE_SLOT_TAIL.compareAndSet(this, pos, pos + 1)) {
                        this.freeSlotBuffer[idx] = slotId;
                        VH_LONG_ARRAY.setRelease(this.freeSlotSeq, idx, pos + 1);
                        break;
                    }
                } else {
                    spins = onContendedSpin(spins);
                }
            }
            // Wake one saturated caller. The fence publishes the released record before the
            // slotWaiters read, closing the classic Dekker lost-wakeup window against
            // acquireSlotBlocking (which increments slotWaiters before re-checking the queue).
            VarHandle.fullFence();
            if (this.slotWaiters != 0) {
                synchronized (admissionLock) {
                    admissionLock.notify();
                }
            }
        }

        /**
         * Marks a record abandoned, whichever phase it is in.
         *
         * @return {@code true} when the record was still owned by this call (the poller or
         *         close() will release it); {@code false} when it is already terminal
         */
        private boolean abandonSlot(int slotId) {
            if (VH_INT_ARRAY.compareAndSet(slotState, slotId, STATE_PARKED, STATE_ABANDONED)) {
                return true;
            }
            return VH_INT_ARRAY.compareAndSet(slotState, slotId, STATE_SUBMITTED, STATE_ABANDONED);
        }

        /** Releases a record whose request is terminal (completed, cancelled or abandoned). */
        private void freeSlot(int slotId) {
            long toFree = slotFreeAddr[slotId];
            if (toFree != 0L) {
                slotFreeAddr[slotId] = 0L;
                parent.libc.free(toFree);
            }
            slotThread[slotId] = null;
            VH_INT_ARRAY.setRelease(slotState, slotId, STATE_IDLE);
            releaseSlot(slotId);
        }

        /** Unparks every caller waiting on the admission monitor (used by close). */
        void wakeAdmissionWaiters() {
            VarHandle.fullFence();
            if (this.slotWaiters != 0) {
                synchronized (admissionLock) {
                    admissionLock.notifyAll();
                }
            }
        }

        long countParkedSlots() {
            long parked = 0;
            for (int i = 0; i < slotCapacity; i++) {
                int state = (int) VH_INT_ARRAY.getAcquire(slotState, i);
                if (state == STATE_PARKED || state == STATE_SUBMITTED) {
                    parked++;
                }
            }
            return parked;
        }

        /**
         * Requests a best-effort kernel cancellation of the request identified by
         * {@code userData}. The request is queued for the poller, which submits the
         * {@code IORING_OP_ASYNC_CANCEL}; the caller still waits for its own completion, so its
         * buffer stays valid until the kernel is done with it. Interrupts are rare, so this is
         * a small monitor-guarded ring rather than a hot-path structure.
         *
         * @param userData target request's {@code user_data}, as encoded by
         *                 {@link JUringBlocking#userData(long, int)} at submission
         */
        boolean requestCancel(long userData) {
            int slotId = (int) userData;
            byte flags = (byte) VH_BYTE_ARRAY.getVolatile(slotSqeFlags, slotId);
            if ((flags & CANCEL_FLAG_BIT) == 0) {
                VH_BYTE_ARRAY.setVolatile(slotSqeFlags, slotId, (byte) (flags | CANCEL_FLAG_BIT));
            }
            // Order the flag store before the state read (Dekker against the poller's
            // PARKED->SUBMITTED CAS + flag read) so a cancellation cannot be lost.
            VarHandle.fullFence();
            boolean queued = true;
            if ((int) VH_INT_ARRAY.getAcquire(slotState, slotId) == STATE_SUBMITTED) {
                // Already in the ring: the poller cannot see a flag that is set after its
                // submit-time check, so queue a ready cancellation for it. Ready
                // cancellations are bounded by the in-flight requests.
                queued = enqueueReadyCancel(userData);
            }
            wakePoller();
            return queued;
        }

        private boolean enqueueReadyCancel(long userData) {
            boolean queued = false;
            synchronized (cancelLock) {
                if (cancelPending < cancelRingSize) {
                    cancelRing[cancelPending++] = userData;
                    queued = true;
                }
            }
            if (queued) {
                cancelWake = 1;
            }
            return queued;
        }

        /**
         * Drains queued cancellations into SQEs (they use the {@link #CANCEL_SENTINEL} user
         * data, so their completions are ignored and no call record is consumed).
         *
         * @return the number of cancel SQEs written
         */
        private int drainCancelRequests() {
            if (cancelPending == 0) {
                return 0;
            }
            int drained = 0;
            synchronized (cancelLock) {
                int i = 0;
                while (i < cancelPending) {
                    long target = cancelRing[i];
                    int slotId = (int) target;
                    int state = (int) VH_INT_ARRAY.getAcquire(slotState, slotId);
                    if (state == STATE_PARKED) {
                        // Still queued: the poller has not submitted it, so a kernel cancel
                        // would find nothing. Leave it pending and retry on the next pass.
                        i++;
                        continue;
                    }
                    if (state != STATE_SUBMITTED) {
                        // Terminal (completed or abandoned): the request is already done, so
                        // the cancel is no longer needed. Drop it.
                        cancelRing[i] = cancelRing[--cancelPending];
                        continue;
                    }
                    long sqe = dispatcher.getSqe();
                    if (sqe == 0L) {
                        break;
                    }
                    writeCancelSqe(sqe, target);
                    cancelRing[i] = cancelRing[--cancelPending];
                    drained++;
                }
            }
            return drained;
        }

        private static void writeCancelSqe(long sqe, long target) {
            Sqe.zero(sqe);
            Sqe.setOpcode(sqe, IORING_OP_ASYNC_CANCEL);
            Sqe.setFd(sqe, -1);
            Sqe.setAddr(sqe, target);
            Sqe.setLen(sqe, 0);
            Sqe.setRwFlags(sqe, 0);
            Sqe.setUserData(sqe, CANCEL_SENTINEL);
        }

        /**
         * Submits one request and parks until it completes.
         *
         * <p>Zero library allocation on the hot path: the request lives in the pre-allocated
         * record arrays, the queue entry is an {@code int}, and the caller parks with plain
         * {@link LockSupport#park(Object)}. The completion side is O(1): the poller looks the
         * record up from {@code user_data} and unparks the stored thread.
         *
         * <p>An interrupt requests a kernel cancellation of this request and is reported as
         * {@code -EINTR} once the kernel completion (the cancellation or the real result) has
         * been reaped, so the caller's buffer is not touched after the method returns.
         */
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
            int slotId;
            try {
                slotId = acquireSlot();
            } catch (Throwable t) {
                if (freeAddrOnComplete != 0L) {
                    parent.libc.free(freeAddrOnComplete);
                }
                throw t;
            }
            if (slotId < 0) {
                // The caller was interrupted while waiting for admission.
                if (freeAddrOnComplete != 0L) {
                    parent.libc.free(freeAddrOnComplete);
                }
                return -JuringException.EINTR;
            }
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

            long tail = (long) VH_WORKER_MPSC_TAIL.getAndAdd(this, 1L);
            long userData = userData(tail, slotId);
            int idx = (int) (tail & this.slotMask);
            int spins = 0;
            while ((long) VH_LONG_ARRAY.getAcquire(this.mpscSeq, idx) != tail) {
                spins = onContendedSpin(spins);
            }
            this.mpscBuffer[idx] = slotId;
            VH_LONG_ARRAY.setRelease(this.mpscSeq, idx, tail + 1);

            wakePoller();

            boolean completed = false;
            boolean interrupted = false;
            boolean cancelPending = false;
            try {
                for (;;) {
                    if ((int) VH_INT_ARRAY.getAcquire(slotState, slotId) == STATE_DONE) {
                        break;
                    }
                    if (!running) {
                        int state = (int) VH_INT_ARRAY.getAcquire(slotState, slotId);
                        if (state == STATE_DONE) {
                            break;
                        }
                        if (state == STATE_SUBMITTED && shard != null && !shard.isTerminated()) {
                            // The ring is closing but has not cancelled this request yet. Wait
                            // for the terminal completion instead of returning while the kernel
                            // may still be writing into the caller's buffer.
                            LockSupport.parkNanos(this, 100_000L);
                            continue;
                        }
                        throw new IllegalStateException("JUringBlocking closed while waiting for I/O completion");
                    }
                    int preParkSpins = options.preParkSpins();
                    if (preParkSpins > 0) {
                        int spun = 0;
                        while (spun < preParkSpins
                                && (int) VH_INT_ARRAY.getAcquire(slotState, slotId) != STATE_DONE) {
                            Thread.onSpinWait();
                            spun++;
                        }
                        if ((int) VH_INT_ARRAY.getAcquire(slotState, slotId) == STATE_DONE) {
                            break;
                        }
                    }
                    if (cancelPending) {
                        // The (correctly sized) ready ring was momentarily full: retry on the
                        // next park return instead of losing the cancellation.
                        LockSupport.parkNanos(this, 1_000_000L);
                    } else {
                        LockSupport.park(this);
                    }
                    if ((int) VH_INT_ARRAY.getAcquire(slotState, slotId) != STATE_DONE) {
                        wakePoller();
                    }
                    if (Thread.interrupted()) {
                        interrupted = true;
                        cancelPending = true;
                    }
                    if (cancelPending && requestCancel(userData)) {
                        cancelPending = false;
                    }
                }
                completed = true;
                if (interrupted) {
                    Thread.currentThread().interrupt();
                    return -JuringException.EINTR;
                }
                return slotResult[slotId];
            } finally {
                if (completed || !abandonSlot(slotId)) {
                    freeSlot(slotId);
                }
                // otherwise an exception left the request queued or in flight: the poller or
                // close() releases the record when it is skipped or completed
            }
        }
    }

    /**
     * Creates a wrapper with the default configuration.
     *
     * <p>The worker count comes from the {@code juring.blocking.pollers} system property when
     * set (rounded down to a power of two, at most sixteen); otherwise it is {@code 1} for
     * queue depths below {@code 64} and a small CPU-derived value (1 to 4) for larger depths.
     * The concurrent-call budget is derived from the queue depth, as in 0.1.0.
     *
     * @param queueDepth   submission queue entries per worker ring
     * @param ioUringFlags {@link IoUringOptions} setup flags for each worker ring
     * @throws RuntimeException when a worker ring or its poller thread cannot be started
     */
    public JUringBlocking(int queueDepth, IoUringOptions... ioUringFlags) {
        this(BlockingOptions.defaults().withQueueDepth(queueDepth), ioUringFlags);
    }

    /**
     * Creates a wrapper with an explicit number of poller workers.
     *
     * <p>{@code numPollers} is rounded down to a power of two (minimum one); each worker gets
     * its own ring with {@code max(8, queueDepth)} submission queue entries and the
     * concurrent-call budget is derived from the queue depth, as in 0.1.0.
     *
     * @param queueDepth   submission queue entries per worker ring
     * @param numPollers   requested number of poller workers
     * @param ioUringFlags {@link IoUringOptions} setup flags for each worker ring
     * @throws RuntimeException when a worker ring or its poller thread cannot be started
     */
    public JUringBlocking(int queueDepth, int numPollers, IoUringOptions... ioUringFlags) {
        this(BlockingOptions.defaults()
                .withQueueDepth(queueDepth)
                .withPollers(Math.max(1, numPollers)), ioUringFlags);
    }

    /**
     * Creates a wrapper from an explicit configuration (queue depth, poller count,
     * concurrent-call budget and oversubscription policy).
     *
     * @param options      the configuration, must not be {@code null}
     * @param ioUringFlags {@link IoUringOptions} setup flags for each worker ring
     * @throws RuntimeException         when a worker ring or its poller thread cannot be started
     * @throws IllegalArgumentException when the options are invalid
     */
    public JUringBlocking(BlockingOptions options, IoUringOptions... ioUringFlags) {
        Objects.requireNonNull(options, "options");
        int workerCount = options.resolvedPollers();
        int ringEntries = Math.max(8, options.queueDepth());
        int recordCapacity = options.perWorkerCapacity(workerCount);
        Worker[] newWorkers = new Worker[workerCount];
        RingGroup newGroup = null;
        try {
            for (int i = 0; i < workerCount; i++) {
                newWorkers[i] = new Worker(this, i, recordCapacity, ringEntries, options);
            }
            newGroup = RingGroup.create(workerCount, ShardOptions.defaults()
                    .withName("JUringBlocking-Poller")
                    .withRingEntries(ringEntries)
                    .withRingOptions(ioUringFlags));
            for (int i = 0; i < workerCount; i++) {
                newWorkers[i].shard = newGroup.shard(i);
                newGroup.installBody(i, newWorkers[i]);
            }
            newGroup.start();
            awaitBodies(newWorkers);
            throwStartupErrors(newWorkers);
        } catch (Throwable t) {
            cleanupFailedStart(newGroup, newWorkers);
            throw rethrow(t);
        }
        this.workers = newWorkers;
        this.workerMask = workerCount - 1;
        this.group = newGroup;
        this.options = options;
        this.recordsPerWorker = recordCapacity;
        this.maxConcurrentCalls = recordCapacity * workerCount;
    }

    /**
     * Adopts a caller-supplied, <b>unstarted</b> {@link RingGroup}: its shards become
     * the worker rings, {@code group.size()} is the worker/poller count and the group's CPU
     * pinning is inherited. Ownership transfers to this instance: {@link #close()} closes the
     * group.
     *
     * @param group unstarted group that becomes the worker rings; closed by {@link #close()}
     * @throws NullPointerException      when {@code group} is {@code null}
     * @throws IllegalStateException     when the group is started, stopping or closed
     */
    public JUringBlocking(RingGroup group) {
        Objects.requireNonNull(group, "group");
        if (group.isClosed()) {
            throw new IllegalStateException("RingGroup is closed");
        }
        if (group.isStarted()) {
            throw new IllegalStateException("RingGroup is already started; the group must be unstarted");
        }
        if (group.isStopping()) {
            throw new IllegalStateException("RingGroup is stopping");
        }
        int workerCount = group.size();
        int ringEntries = Math.max(8, group.shard(0).options().ringEntries());
        BlockingOptions groupOptions = BlockingOptions.forGroup(ringEntries, workerCount);
        int recordCapacity = groupOptions.perWorkerCapacity(workerCount);
        Worker[] newWorkers = new Worker[workerCount];
        try {
            for (int i = 0; i < workerCount; i++) {
                newWorkers[i] = new Worker(this, i, recordCapacity, ringEntries, groupOptions);
                newWorkers[i].shard = group.shard(i);
            }
            for (int i = 0; i < workerCount; i++) {
                group.installBody(i, newWorkers[i]);
            }
            group.start();
            awaitBodies(newWorkers);
            throwStartupErrors(newWorkers);
        } catch (Throwable t) {
            cleanupFailedStart(group, newWorkers);
            throw rethrow(t);
        }
        this.workers = newWorkers;
        this.workerMask = workerCount - 1;
        this.group = group;
        this.options = groupOptions;
        this.recordsPerWorker = recordCapacity;
        this.maxConcurrentCalls = recordCapacity * workerCount;
    }

    private static void awaitBodies(Worker[] workers) {
        boolean interrupted = false;
        for (Worker w : workers) {
            while (true) {
                try {
                    w.bodyStarted.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void throwStartupErrors(Worker[] workers) {
        for (Worker w : workers) {
            Throwable t = w.startupError;
            if (t != null) {
                throw rethrow(t);
            }
        }
    }

    private static RuntimeException cleanupFailedStart(RingGroup group, Worker[] workers) {
        if (group != null) {
            try {
                group.close(0L);
            } catch (Throwable ignored) {
                // best effort: propagate the original failure
            }
        }
        for (Worker w : workers) {
            if (w == null) {
                continue;
            }
            if (w.shard != null) {
                // bounded wait so a partially-started shard can release its ring first
                w.shard.awaitTermination(3_000L);
            }
            w.freeEventfdResourcesIfTerminated();
        }
        return null;
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        throw new RuntimeException("Failed to initialize JUringBlocking", t);
    }

    int workerCount() {
        return workers.length;
    }

    /**
     * Returns the configuration this instance was created with. For an instance that adopted a
     * caller-supplied {@link RingGroup}, the poller count is the group's shard count and the
     * other values are the defaults used to size the call-record pool.
     *
     * @return the immutable options
     */
    public BlockingOptions options() {
        return options;
    }

    /**
     * Returns the effective concurrent-call budget: the number of blocking calls that may be
     * admitted (parked or in flight) at once. Every admitted call owns one pre-allocated
     * record, so this value also fixes the memory bound (see {@link BlockingOptions#estimatedBytes()}).
     *
     * @return the effective total record capacity across all workers
     */
    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    /**
     * Returns the call-record capacity of one worker. The pool is pre-allocated at construction
     * (a power of two, at least 256).
     *
     * @return the per-worker record capacity
     */
    public int recordsPerWorker() {
        return recordsPerWorker;
    }

    /**
     * Returns the number of callers currently parked waiting for their I/O completion. The
     * count is computed by scanning the record states, so it is an observability snapshot for
     * tests and diagnostics, not a hot-path counter.
     *
     * @return the number of parked callers across all workers
     */
    public long parkedCallers() {
        long parked = 0;
        for (Worker w : workers) {
            parked += w.countParkedSlots();
        }
        return parked;
    }

    /**
     * Returns how many calls had to wait for a free call record (the saturation path). A
     * growing value means {@link BlockingOptions#withMaxConcurrentCalls(int)} is undersized
     * for the workload; with {@link OverflowPolicy#FAIL} those calls throw instead.
     *
     * @return the number of saturated admissions since construction
     */
    public long overflowWaits() {
        long waits = 0;
        for (Worker w : workers) {
            waits += w.overflowWaits.get();
        }
        return waits;
    }

    private Worker getWorker() {
        if (workers.length == 1) {
            return workers[0];
        }
        long tid = Thread.currentThread().threadId();
        if ((workers.length & (workers.length - 1)) == 0) {
            return workers[(int) (tid & workerMask)];
        }
        // RingGroup allows any size in 1..1024; a mask would strand workers when it is not a
        // power of two (for example size 3 only ever picks workers 0 and 2).
        return workers[(int) Long.remainderUnsigned(tid, workers.length)];
    }

    static void validateNativeSegment(MemorySegment segment, boolean forRead) {
        JUring.validateNativeSegment(segment, forRead);
    }

    /**
     * Encodes a call record's {@code user_data} as {@code (queue sequence, slot id)}. The queue
     * sequence makes the value unique per submission, so a cancellation requested for an
     * earlier use of the same record can never match a later request's {@code user_data}.
     *
     * @param queueSequence per-worker submission sequence (the MPSC tail/head value)
     * @param slotId        call-record id
     * @return a positive {@code user_data} value
     */
    static long userData(long queueSequence, int slotId) {
        return ((queueSequence & 0x7FFF_FFFFL) << 32) | (slotId & 0xFFFF_FFFFL);
    }

    /**
     * Reads into the whole of {@code buffer} at file offset {@code offset}, blocking until the
     * read completes.
     *
     * @param fd         file to read
     * @param buffer     native destination buffer; its size determines the read size
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes read, or the negative errno
     * @throws IllegalArgumentException when the segment is not native writeable memory
     * @throws IllegalStateException    when this instance is closed
     */
    public long read(FileDescriptor fd, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return read(fd, buffer, Math.toIntExact(buffer.byteSize()), offset, sqeOptions);
    }

    /**
     * Reads at most {@code length} bytes into {@code buffer} at file offset {@code offset},
     * blocking until the read completes.
     *
     * @param fd         file to read
     * @param buffer     native destination buffer
     * @param length     maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes read, or the negative errno
     * @throws IllegalArgumentException when the length exceeds the buffer or a segment check fails
     */
    public long read(FileDescriptor fd, MemorySegment buffer, int length, long offset, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid read length: " + length);
        }
        validateNativeSegment(buffer, true);
        try {
            return getWorker().submitAndAwaitSlot(
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
        } finally {
            Reference.reachabilityFence(buffer);
        }
    }

    /**
     * Reads into the whole of {@code buffer} from a registered-file slot, blocking until the
     * read completes.
     *
     * @param indexFD    registered-file slot number
     * @param buffer     native destination buffer; its size determines the read size
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes read, or the negative errno
     */
    public long read(int indexFD, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return read(indexFD, buffer, Math.toIntExact(buffer.byteSize()), offset, sqeOptions);
    }

    /**
     * Reads at most {@code length} bytes from a registered-file slot, blocking until the read
     * completes.
     *
     * @param indexFD    registered-file slot number
     * @param buffer     native destination buffer
     * @param length     maximum number of bytes to read
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes read, or the negative errno
     */
    public long read(int indexFD, MemorySegment buffer, int length, long offset, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid read length: " + length);
        }
        validateNativeSegment(buffer, true);
        try {
            return getWorker().submitAndAwaitSlot(
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
        } finally {
            Reference.reachabilityFence(buffer);
        }
    }

    /**
     * Writes the whole of {@code buffer} at file offset {@code offset}, blocking until the
     * write completes.
     *
     * @param fd         file to write
     * @param buffer     native source buffer; its size determines the write size
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long write(FileDescriptor fd, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return write(fd, buffer, Math.toIntExact(buffer.byteSize()), offset, sqeOptions);
    }

    /**
     * Writes at most {@code length} bytes from {@code buffer} at file offset {@code offset},
     * blocking until the write completes.
     *
     * @param fd         file to write
     * @param buffer     native source buffer
     * @param length     number of bytes to write
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long write(FileDescriptor fd, MemorySegment buffer, int length, long offset, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid write length: " + length);
        }
        validateNativeSegment(buffer, false);
        try {
            return getWorker().submitAndAwaitSlot(
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
        } finally {
            Reference.reachabilityFence(buffer);
        }
    }

    /**
     * Writes a heap array at file offset {@code offset}, blocking until the write completes.
     *
     * <p>The bytes are copied into native memory for the duration of the call and released
     * after the completion is reaped.
     *
     * @param fd         file to write
     * @param bytes      bytes to write
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long write(FileDescriptor fd, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        MemorySegment buff = libc.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        return getWorker().submitAndAwaitSlot(
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

    /**
     * Writes the whole of {@code buffer} to a registered-file slot, blocking until the write
     * completes.
     *
     * @param indexFD    registered-file slot number
     * @param buffer     native source buffer; its size determines the write size
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long write(int indexFD, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return write(indexFD, buffer, Math.toIntExact(buffer.byteSize()), offset, sqeOptions);
    }

    /**
     * Writes at most {@code length} bytes from {@code buffer} to a registered-file slot,
     * blocking until the write completes.
     *
     * @param indexFD    registered-file slot number
     * @param buffer     native source buffer
     * @param length     number of bytes to write
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long write(int indexFD, MemorySegment buffer, int length, long offset, SqeOptions... sqeOptions) {
        if (length < 0 || length > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid write length: " + length);
        }
        validateNativeSegment(buffer, false);
        try {
            return getWorker().submitAndAwaitSlot(
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
        } finally {
            Reference.reachabilityFence(buffer);
        }
    }

    /**
     * Writes a heap array to a registered-file slot, blocking until the write completes.
     *
     * @param indexFD    registered-file slot number
     * @param bytes      bytes to write
     * @param offset     file offset in bytes
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long write(int indexFD, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        MemorySegment buff = libc.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        return getWorker().submitAndAwaitSlot(
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

    /**
     * Registers {@code nrOfBuffers} off-heap buffers of {@code size} bytes on every worker
     * ring.
     *
     * <p>The buffers must stay valid until the instance is closed and should be registered
     * only once per instance; the returned segments are indexed by the buffer index used by
     * the {@code *Fixed} methods.
     *
     * @param size        size of one buffer in bytes
     * @param nrOfBuffers number of buffers to register
     * @return the registered buffer segments, in index order
     * @throws IllegalStateException when this instance is closed
     */
    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        if (size <= 0 || nrOfBuffers <= 0) {
            throw new IllegalArgumentException("Invalid registered buffer configuration: size=" + size + ", nrOfBuffers=" + nrOfBuffers);
        }
        if (registeredBuffers != null) {
            throw new IllegalStateException("Buffers are already registered on this JUringBlocking instance");
        }
        if (closed) {
            throw new IllegalStateException("JUringBlocking is closed");
        }
        var iovecStructure = libc.allocateIovec(registeredBufferArena, size, nrOfBuffers);
        for (Worker w : workers) {
            w.runOnPoller(() -> {
                w.dispatcher.registerExistingIovecStructure(iovecStructure, nrOfBuffers);
                return null;
            });
        }
        MemorySegment[] buffers = iovecStructure.buffers();
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
        this.bufferInUse = new AtomicIntegerArray(nrOfBuffers);
        this.freeBufHead = 0L;
        this.freeBufTail = nrOfBuffers;
        return buffers;
    }

    /**
     * Returns the memory segment of a registered buffer.
     *
     * @param bufferIndex index of the registered buffer
     * @return the buffer's memory segment
     * @throws IllegalArgumentException when no buffers are registered or the index is out of range
     */
    public MemorySegment getRegisteredBuffer(int bufferIndex) {
        if (closed) {
            throw new IllegalStateException("JUringBlocking is closed");
        }
        MemorySegment[] bufs = this.registeredBuffers;
        if (bufs == null || bufferIndex < 0 || bufferIndex >= bufs.length) {
            throw new IllegalArgumentException("Invalid registered buffer index: " + bufferIndex);
        }
        return bufs[bufferIndex];
    }

    /**
     * Checks out a free registered buffer for exclusive use.
     *
     * @return the index of the checked-out buffer, or {@code -1} when all buffers are in use
     * @throws IllegalStateException when no buffers are registered
     */
    public int checkOutBuffer() {
        final int[] bufArr = this.freeBufBuffer;
        final long[] seqArr = this.freeBufSeq;
        if (bufArr == null || seqArr == null) {
            throw new IllegalStateException("Buffers have not been registered");
        }
        final int mask = this.registeredBufMask;
        final int cap = mask + 1;
        int spins = 0;
        while (true) {
            long pos = (long) VH_FREE_BUF_HEAD.getVolatile(this);
            int idx = (int) (pos & mask);
            long seq = (long) VH_LONG_ARRAY.getAcquire(seqArr, idx);
            long dif = seq - (pos + 1);
            if (dif == 0) {
                if (VH_FREE_BUF_HEAD.compareAndSet(this, pos, pos + 1)) {
                    int bufIdx = bufArr[idx];
                    VH_LONG_ARRAY.setRelease(seqArr, idx, pos + cap);
                    AtomicIntegerArray inUse = this.bufferInUse;
                    if (inUse == null || !inUse.compareAndSet(bufIdx, 0, 1)) {
                        throw new IllegalStateException("Registered buffer " + bufIdx + " is already checked out");
                    }
                    return bufIdx;
                }
            } else if (dif < 0) {
                return -1;
            } else {
                spins = onContendedSpin(spins);
            }
        }
    }

    /**
     * Returns a registered buffer to the free list.
     *
     * @param bufferIndex index previously returned by {@link #checkOutBuffer()}
     * @throws IllegalStateException    when no buffers are registered or the pool already
     *                                  holds every buffer
     * @throws IllegalArgumentException when the index is out of range
     */
    public void checkInBuffer(int bufferIndex) {
        final MemorySegment[] bufs = this.registeredBuffers;
        final int[] bufArr = this.freeBufBuffer;
        final long[] seqArr = this.freeBufSeq;
        if (bufs == null || bufArr == null || seqArr == null) {
            throw new IllegalStateException("Buffers have not been registered");
        }
        final int totalBuffers = bufs.length;
        if (bufferIndex < 0 || bufferIndex >= totalBuffers) {
            throw new IllegalArgumentException("Invalid buffer index: " + bufferIndex);
        }
        final AtomicIntegerArray inUse = this.bufferInUse;
        if (inUse == null) {
            throw new IllegalStateException("Buffers have not been registered");
        }
        if (!inUse.compareAndSet(bufferIndex, 1, 0)) {
            throw new IllegalStateException("Buffer index " + bufferIndex
                    + " is not checked out (double or foreign check-in)");
        }
        final int mask = this.registeredBufMask;
        int spins = 0;
        try {
            while (true) {
                long pos = (long) VH_FREE_BUF_TAIL.getVolatile(this);
                long head = (long) VH_FREE_BUF_HEAD.getVolatile(this);
                if (pos - head >= totalBuffers) {
                    throw new IllegalStateException("Buffer pool is full: cannot check in buffer " + bufferIndex);
                }
                int idx = (int) (pos & mask);
                long seq = (long) VH_LONG_ARRAY.getAcquire(seqArr, idx);
                long dif = seq - pos;
                if (dif == 0) {
                    if (VH_FREE_BUF_TAIL.compareAndSet(this, pos, pos + 1)) {
                        bufArr[idx] = bufferIndex;
                        VH_LONG_ARRAY.setRelease(seqArr, idx, pos + 1);
                        return;
                    }
                } else if (dif < 0) {
                    long currentHead = (long) VH_FREE_BUF_HEAD.getVolatile(this);
                    if (pos - currentHead >= totalBuffers) {
                        throw new IllegalStateException("Buffer pool is full: cannot check in buffer " + bufferIndex);
                    }
                    spins = onContendedSpin(spins);
                } else {
                    spins = onContendedSpin(spins);
                }
            }
        } catch (RuntimeException e) {
            // The index was not actually returned to the queue: restore the ledger so a retry
            // (or a later failure report) sees the true ownership state.
            inUse.set(bufferIndex, 1);
            throw e;
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

    /**
     * Reads into a registered fixed buffer, blocking until the read completes.
     *
     * @param fd          file to read
     * @param readSize    maximum number of bytes to read
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered destination buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the number of bytes read, or the negative errno
     * @throws IllegalArgumentException when the index or size is out of range
     */
    public long readFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long bufAddr = validateRegisteredBuffer(bufferIndex, readSize);
        return getWorker().submitAndAwaitSlot(
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

    /**
     * Reads from a registered-file slot into a registered fixed buffer, blocking until the
     * read completes.
     *
     * @param indexFD     registered-file slot number
     * @param readSize    maximum number of bytes to read
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered destination buffer
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the number of bytes read, or the negative errno
     */
    public long readFixed(int indexFD, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long bufAddr = validateRegisteredBuffer(bufferIndex, readSize);
        return getWorker().submitAndAwaitSlot(
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

    /**
     * Writes from a registered fixed buffer, blocking until the write completes.
     *
     * @param fd          file to write
     * @param writeSize   number of bytes to write from the buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     * @throws IllegalArgumentException when the index or size is out of range
     */
    public long writeFixed(FileDescriptor fd, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long bufAddr = validateRegisteredBuffer(bufferIndex, writeSize);
        return getWorker().submitAndAwaitSlot(
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

    /**
     * Writes from a registered fixed buffer to a registered-file slot, blocking until the
     * write completes.
     *
     * @param indexFD     registered-file slot number
     * @param writeSize   number of bytes to write from the buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long writeFixed(int indexFD, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long bufAddr = validateRegisteredBuffer(bufferIndex, writeSize);
        return getWorker().submitAndAwaitSlot(
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

    /**
     * Copies a heap array into a registered fixed buffer and writes it, blocking until the
     * write completes.
     *
     * @param fd          file to write
     * @param bytes       bytes to write; copied into the registered buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long writeFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        validateRegisteredBuffer(bufferIndex, bytes.length);
        MemorySegment.copy(bytes, 0, this.registeredBuffers[bufferIndex], JAVA_BYTE, 0, bytes.length);
        return writeFixed(fd, bytes.length, offset, bufferIndex, sqeOptions);
    }

    /**
     * Copies a heap array into a registered fixed buffer and writes it to a registered-file
     * slot, blocking until the write completes.
     *
     * @param indexFD     registered-file slot number
     * @param bytes       bytes to write; copied into the registered buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @param sqeOptions  SQE flags, combined with bitwise OR
     * @return the number of bytes written, or the negative errno
     */
    public long writeFixed(int indexFD, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        validateRegisteredBuffer(bufferIndex, bytes.length);
        MemorySegment.copy(bytes, 0, this.registeredBuffers[bufferIndex], JAVA_BYTE, 0, bytes.length);
        return writeFixed(indexFD, bytes.length, offset, bufferIndex, sqeOptions);
    }

    // ------------------------------------------------------------------
    // Allocation-free hot-path overloads
    //
    // The varargs forms above allocate an empty SqeOptions[] at every call site, even when
    // no flags are supplied. These exact-arity overloads delegate with a shared empty array
    // so the common blocking call is allocation-free on the caller thread after setup.
    // ------------------------------------------------------------------

    /**
     * Allocation-free overload: reads into the whole of {@code buffer}.
     *
     * @param fd     file to read
     * @param buffer native destination buffer; its size determines the read size
     * @param offset file offset in bytes
     * @return the number of bytes read, or the negative errno
     * @throws IllegalArgumentException when the segment is not native writeable memory
     * @throws IllegalStateException    when this instance is closed
     */
    public long read(FileDescriptor fd, MemorySegment buffer, long offset) {
        return read(fd, buffer, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: reads at most {@code length} bytes into {@code buffer}.
     *
     * @param fd     file to read
     * @param buffer native destination buffer
     * @param length maximum number of bytes to read
     * @param offset file offset in bytes
     * @return the number of bytes read, or the negative errno
     * @throws IllegalArgumentException when the length exceeds the buffer or a segment check fails
     */
    public long read(FileDescriptor fd, MemorySegment buffer, int length, long offset) {
        return read(fd, buffer, length, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: reads into the whole of {@code buffer} from a registered-file
     * slot.
     *
     * @param indexFD registered-file slot number
     * @param buffer  native destination buffer; its size determines the read size
     * @param offset  file offset in bytes
     * @return the number of bytes read, or the negative errno
     */
    public long read(int indexFD, MemorySegment buffer, long offset) {
        return read(indexFD, buffer, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: reads at most {@code length} bytes from a registered-file slot.
     *
     * @param indexFD registered-file slot number
     * @param buffer  native destination buffer
     * @param length  maximum number of bytes to read
     * @param offset  file offset in bytes
     * @return the number of bytes read, or the negative errno
     */
    public long read(int indexFD, MemorySegment buffer, int length, long offset) {
        return read(indexFD, buffer, length, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: writes the whole of {@code buffer}.
     *
     * @param fd     file to write
     * @param buffer native source buffer; its size determines the write size
     * @param offset file offset in bytes
     * @return the number of bytes written, or the negative errno
     */
    public long write(FileDescriptor fd, MemorySegment buffer, long offset) {
        return write(fd, buffer, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: writes at most {@code length} bytes from {@code buffer}.
     *
     * @param fd     file to write
     * @param buffer native source buffer
     * @param length number of bytes to write
     * @param offset file offset in bytes
     * @return the number of bytes written, or the negative errno
     */
    public long write(FileDescriptor fd, MemorySegment buffer, int length, long offset) {
        return write(fd, buffer, length, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: writes a heap array.
     *
     * @param fd     file to write
     * @param bytes  bytes to write
     * @param offset file offset in bytes
     * @return the number of bytes written, or the negative errno
     */
    public long write(FileDescriptor fd, byte[] bytes, long offset) {
        return write(fd, bytes, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: writes the whole of {@code buffer} to a registered-file slot.
     *
     * @param indexFD registered-file slot number
     * @param buffer  native source buffer; its size determines the write size
     * @param offset  file offset in bytes
     * @return the number of bytes written, or the negative errno
     */
    public long write(int indexFD, MemorySegment buffer, long offset) {
        return write(indexFD, buffer, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: writes at most {@code length} bytes to a registered-file slot.
     *
     * @param indexFD registered-file slot number
     * @param buffer  native source buffer
     * @param length  number of bytes to write
     * @param offset  file offset in bytes
     * @return the number of bytes written, or the negative errno
     */
    public long write(int indexFD, MemorySegment buffer, int length, long offset) {
        return write(indexFD, buffer, length, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: writes a heap array to a registered-file slot.
     *
     * @param indexFD registered-file slot number
     * @param bytes   bytes to write
     * @param offset  file offset in bytes
     * @return the number of bytes written, or the negative errno
     */
    public long write(int indexFD, byte[] bytes, long offset) {
        return write(indexFD, bytes, offset, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: reads into a registered fixed buffer.
     *
     * @param fd          file to read
     * @param readSize    maximum number of bytes to read
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered destination buffer
     * @return the number of bytes read, or the negative errno
     * @throws IllegalArgumentException when the index or size is out of range
     */
    public long readFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex) {
        return readFixed(fd, readSize, offset, bufferIndex, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: reads from a registered-file slot into a fixed buffer.
     *
     * @param indexFD     registered-file slot number
     * @param readSize    maximum number of bytes to read
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered destination buffer
     * @return the number of bytes read, or the negative errno
     */
    public long readFixed(int indexFD, int readSize, long offset, int bufferIndex) {
        return readFixed(indexFD, readSize, offset, bufferIndex, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: writes from a registered fixed buffer.
     *
     * @param fd          file to write
     * @param writeSize   number of bytes to write from the buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the number of bytes written, or the negative errno
     * @throws IllegalArgumentException when the index or size is out of range
     */
    public long writeFixed(FileDescriptor fd, int writeSize, long offset, int bufferIndex) {
        return writeFixed(fd, writeSize, offset, bufferIndex, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: writes from a fixed buffer to a registered-file slot.
     *
     * @param indexFD     registered-file slot number
     * @param writeSize   number of bytes to write from the buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the number of bytes written, or the negative errno
     */
    public long writeFixed(int indexFD, int writeSize, long offset, int bufferIndex) {
        return writeFixed(indexFD, writeSize, offset, bufferIndex, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: copies a heap array into a fixed buffer and writes it.
     *
     * @param fd          file to write
     * @param bytes       bytes to write; copied into the registered buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the number of bytes written, or the negative errno
     */
    public long writeFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex) {
        return writeFixed(fd, bytes, offset, bufferIndex, SqeOptions.NO_OPTIONS);
    }

    /**
     * Allocation-free overload: copies a heap array into a fixed buffer and writes it to a
     * registered-file slot.
     *
     * @param indexFD     registered-file slot number
     * @param bytes       bytes to write; copied into the registered buffer
     * @param offset      file offset in bytes
     * @param bufferIndex index of the registered source buffer
     * @return the number of bytes written, or the negative errno
     */
    public long writeFixed(int indexFD, byte[] bytes, long offset, int bufferIndex) {
        return writeFixed(indexFD, bytes, offset, bufferIndex, SqeOptions.NO_OPTIONS);
    }

    /**
     * Registers file descriptors for fixed-file I/O on every worker ring.
     *
     * <p>The slot index of each descriptor is its position in the argument list, matching the
     * {@code indexFD} overloads.
     *
     * @param fileDescriptors files to register, in slot order
     * @return {@code 0} on success, otherwise the first negative kernel result
     */
    public int registerFiles(FileDescriptor... fileDescriptors) {
        int[] fds = Arrays.stream(fileDescriptors).mapToInt(FileDescriptor::getFd).toArray();
        for (Worker w : workers) {
            int ret = w.runOnPoller(() -> w.dispatcher.registerFiles(fds));
            if (ret != 0) {
                return ret;
            }
        }
        return 0;
    }

    /**
     * Replaces a range of the registered-file table on every worker ring.
     *
     * @param offset          first slot to update
     * @param fileDescriptors new descriptors, one per slot
     * @return {@code 0} on success, otherwise the first negative kernel result
     */
    public int registerFilesUpdate(int offset, int[] fileDescriptors) {
        int ret = 0;
        for (Worker w : workers) {
            ret = w.runOnPoller(() -> w.dispatcher.registerFilesUpdate(offset, fileDescriptors));
            if (ret < 0) {
                return ret;
            }
        }
        return ret;
    }

    /**
     * Opens a path, blocking until the open completes.
     *
     * <p>{@code O_CLOEXEC} is added to {@code flags}.
     *
     * @param filePath   path to open
     * @param flags      Linux {@code open(2)} flags
     * @param mode       permission bits used when the file is created
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return the new file descriptor, or the negative errno
     */
    public int open(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        byte[] pathBytes = LibCDispatcher.encodePath(filePath);
        MemorySegment pathAddr = libc.calloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathAddr, JAVA_BYTE, 0, pathBytes.length);
        return (int) getWorker().submitAndAwaitSlot(
                IORING_OP_OPENAT,
                SqeOptions.combineOptions(sqeOptions),
                AT_FDCWD,
                0L,
                pathAddr.address(),
                mode,
                flags | 0x80000 /* O_CLOEXEC */,
                (short) 0,
                0,
                pathAddr.address()
        );
    }

    /**
     * Opens a {@link Path} with {@link LinuxOpenOptions} flags, blocking until the open
     * completes.
     *
     * @param path        path to open
     * @param mode        permission bits used when the file is created
     * @param openOptions open flags, combined with bitwise OR
     * @return the new file descriptor, or the negative errno
     */
    public int open(Path path, int mode, LinuxOpenOptions... openOptions) {
        return open(path.toString(), LinuxOpenOptions.combineOptions(openOptions), mode);
    }

    /**
     * Opens a path and wraps the resulting descriptor in a {@link FileDescriptor}.
     *
     * @param filePath   path to open
     * @param flags      Linux {@code open(2)} flags
     * @param mode       permission bits used when the file is created
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return a file descriptor for the opened file
     * @throws IllegalStateException when the open fails (the message carries the errno)
     */
    public FileDescriptor openFile(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        int fd = open(filePath, flags, mode, sqeOptions);
        if (fd < 0) {
            throw new IllegalStateException("Failed to open file '" + filePath + "': " + libc.strerror(-fd));
        }
        return new FileDescriptor(fd);
    }

    /**
     * Opens a {@link Path} and wraps the resulting descriptor in a {@link FileDescriptor}.
     *
     * @param path        path to open
     * @param mode        permission bits used when the file is created
     * @param openOptions open flags, combined with bitwise OR
     * @return a file descriptor for the opened file
     * @throws IllegalStateException when the open fails (the message carries the errno)
     */
    public FileDescriptor openFile(Path path, int mode, LinuxOpenOptions... openOptions) {
        return openFile(path.toString(), LinuxOpenOptions.combineOptions(openOptions), mode);
    }

    /**
     * Opens a path into a registered-file slot on every worker ring, blocking until the opens
     * complete.
     *
     * @param filePath   path to open
     * @param flags      open flags
     * @param mode       permission bits used when the file is created
     * @param fileIndex  registered-file slot to install the opened file into
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return {@code 0} on success, otherwise the first negative kernel result
     */
    public int openDirect(String filePath, LinuxOpenOptions flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        return openDirect(filePath, flags.getValue(), mode, fileIndex, sqeOptions);
    }

    /**
     * Opens a path into a registered-file slot on every worker ring, blocking until the opens
     * complete.
     *
     * <p>{@code O_CLOEXEC} is stripped from {@code flags} because it is not allowed for a
     * direct-descriptor open. On success the slot can be used with the {@code indexFD}
     * overloads.
     *
     * @param filePath   path to open
     * @param flags      Linux {@code open(2)} flags
     * @param mode       permission bits used when the file is created
     * @param fileIndex  registered-file slot to install the opened file into
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return {@code 0} on success, otherwise the first negative kernel result
     * @throws IllegalArgumentException when {@code fileIndex} is negative
     */
    public int openDirect(String filePath, int flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        if (fileIndex < 0) {
            throw new IllegalArgumentException("Invalid fileIndex: " + fileIndex);
        }
        int directFlags = flags & ~0x80000 /* Strip O_CLOEXEC for direct fixed file open */;
        int res = 0;
        for (Worker w : workers) {
            byte[] pathBytes = LibCDispatcher.encodePath(filePath);
            MemorySegment pathAddr = libc.calloc(pathBytes.length + 1);
            MemorySegment.copy(pathBytes, 0, pathAddr, JAVA_BYTE, 0, pathBytes.length);
            res = (int) w.submitAndAwaitSlot(
                    IORING_OP_OPENAT,
                    SqeOptions.combineOptions(sqeOptions),
                    AT_FDCWD,
                    0L,
                    pathAddr.address(),
                    mode,
                    directFlags,
                    (short) 0,
                    fileIndex + 1,
                    pathAddr.address()
            );
            if (res < 0) {
                return res;
            }
        }
        return res;
    }

    /**
     * Opens a {@link Path} into a registered-file slot on every worker ring.
     *
     * @param path        path to open
     * @param mode        permission bits used when the file is created
     * @param fileIndex   registered-file slot to install the opened file into
     * @param openOptions open flags, combined with bitwise OR
     * @return {@code 0} on success, otherwise the first negative kernel result
     */
    public int openDirect(Path path, int mode, int fileIndex, LinuxOpenOptions... openOptions) {
        return openDirect(path.toString(), LinuxOpenOptions.combineOptions(openOptions), mode, fileIndex);
    }

    /**
     * Closes a file descriptor, blocking until the close completes.
     *
     * @param fd         file descriptor to close
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return {@code 0} on success, or the negative errno
     */
    public int closeFd(FileDescriptor fd, SqeOptions... sqeOptions) {
        return closeFd(fd.getFd(), sqeOptions);
    }

    /**
     * Closes a raw file descriptor, blocking until the close completes.
     *
     * @param rawFd      native file descriptor to close
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return {@code 0} on success, or the negative errno
     */
    public int closeFd(int rawFd, SqeOptions... sqeOptions) {
        return (int) getWorker().submitAndAwaitSlot(
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

    /**
     * Closes a file held in a registered-file slot on every worker ring, blocking until the
     * closes complete.
     *
     * @param fileIndex  registered-file slot to close
     * @param sqeOptions SQE flags, combined with bitwise OR
     * @return {@code 0} on success, otherwise the first negative kernel result
     * @throws IllegalArgumentException when {@code fileIndex} is negative
     */
    public int closeDirect(int fileIndex, SqeOptions... sqeOptions) {
        if (fileIndex < 0) {
            throw new IllegalArgumentException("Invalid fileIndex: " + fileIndex);
        }
        int res = 0;
        for (Worker w : workers) {
            res = (int) w.submitAndAwaitSlot(
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
            if (res < 0) {
                return res;
            }
        }
        return res;
    }

    /**
     * Closes this wrapper with the default {@link JUring#DEFAULT_CLOSE_TIMEOUT_MILLIS}
     * shutdown budget.
     */
    @Override
    public void close() {
        close(JUring.DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }

    /**
     * Bounded, idempotent close: stops accepting new operations, cancels in-flight
     * requests and dispatches the cancellations so parked callers unblock, then closes the
     * shard group against a shared deadline bounded by {@code timeoutMillis + 250 ms}. A
     * cancellation completion that never arrives is abandoned and counted by the ring's
     * bounded close instead of blocking forever.
     *
     * @param timeoutMillis graceful-shutdown budget in milliseconds ({@code >= 0})
     * @throws IllegalArgumentException when {@code timeoutMillis < 0}
     * @throws IllegalStateException    when called from one of this instance's shard threads
     */
    public void close(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("close timeout must be >= 0: " + timeoutMillis);
        }
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            for (Worker w : workers) {
                IoShard s = w.shard;
                if (s != null && s.inShardThread()) {
                    throw new IllegalStateException("cannot close JUringBlocking from one of its shard threads");
                }
            }
            closed = true;
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            for (Worker w : workers) {
                w.running = false;
                w.shutdownDeadlineNanos = deadline;
            }
            for (Worker w : workers) {
                w.wakePoller();
            }
            try {
                group.close(timeoutMillis);
            } catch (RuntimeException ignored) {
                // bounded close is best effort by contract; cleanup below still runs
            }
            boolean terminated = group.awaitTermination(0);
            for (Worker w : workers) {
                w.failPendingControlTask();
                w.unparkQueuedSlots();
                if (terminated) {
                    // The rings are closed, so the kernel no longer owns any caller buffer.
                    w.unparkSubmittedSlots();
                }
                w.wakeAdmissionWaiters();
            }
            for (Worker w : workers) {
                w.freeEventfdResourcesIfTerminated();
            }
            // The registered-buffer memory is shared by every worker ring. It may be freed only
            // after all shards have terminated; if one failed to terminate, keep it alive rather
            // than free memory a still-running ring could touch (a bounded leak beats a UAF).
            if (!registeredBufferArenaClosed && group.awaitTermination(0)) {
                registeredBufferArenaClosed = true;
                try {
                    registeredBufferArena.close();
                } catch (RuntimeException ignored) {
                    // Bounded close is best effort; a failed arena close leaks, never crashes.
                } finally {
                    registeredBuffers = null;
                    registeredBufferAddresses = null;
                    registeredBufferSizes = null;
                    freeBufBuffer = null;
                    freeBufSeq = null;
                    bufferInUse = null;
                }
            }
        }
    }
}
