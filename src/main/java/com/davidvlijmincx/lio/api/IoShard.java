package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A thin, single-threaded event loop that owns exactly one {@link JUring}.
 *
 * <p>The ring is created, submitted to, reaped and closed only on the shard's dedicated
 * daemon platform thread ({@code juring-shard-<id>-<name>}); that thread is the
 * {@code IORING_SETUP_SINGLE_ISSUER} owner. Foreign threads interact only through
 * {@link #execute(ShardTask)}, {@link #handoff(Runnable)}, {@link #call(Callable)},
 * {@link #requestStop()}, the lifecycle methods and {@link #stats()}.
 *
 * <p>{@link #start()} applies the requested CPU affinity
 * ({@link ShardOptions#cpuAffinity()}) on a best-effort basis; a denied or out-of-range
 * CPU is recorded in {@link ShardStats#affinityApplied()} and does not fail the shard.
 *
 * <p>Loop shape (see {@code docs/b4-design.md} §4): reap/dispatch up to
 * {@link ShardOptions#cqeBatch()} completions, run up to
 * {@link ShardOptions#taskBatch()} inbound tasks, submit prepared SQEs once, repeat; when
 * idle, spin {@link ShardOptions#spinIterations()} times and then block inside
 * {@code io_uring_enter} with an armed eventfd wakeup read. Completions are always
 * serviced before the next task batch, so a submission flood cannot starve handlers.
 *
 * <p>Backpressure/handoff overflow follow the frozen table in §4: foreign
 * {@code execute} blocks while the inbound queue is full, shard-thread {@code execute}
 * throws {@link IllegalStateException}, and a handoff whose target queue stays full runs
 * the continuation inline and counts {@link ShardStats#handoffsInlineFallback()}.
 *
 * <p>Shutdown ({@code §7}) is a bounded graceful drain: accepted tasks run while the
 * budget allows, in-flight requests are cancelled ring-wide, their {@code -ECANCELED}
 * completions are dispatched to handlers, then the ring is closed with the remaining
 * budget. {@link #close()} is idempotent, never throws for an operation failure, and may
 * only be called from a foreign thread.
 *
 * <p>Inside tasks, prefer raw {@code ring.prepare*} + {@link ShardContext#onCompletion(long, CompletionHandler)} for
 * asynchronous file I/O: {@link JuringFile} blocking helpers assert single-owner ring
 * ownership and can observe the loop's internal armed eventfd wakeup {@code READ} as foreign
 * in-flight work when a task starts behind an already-running batch (the pre-existing B3
 * §11.9/F1 class of limitation; see {@link ShardContext}). A task that starts from an idle
 * shard is safe because the wakeup read is consumed to deliver it.
 *
 * <p>B5 observability: {@link #metrics()} returns a {@link ShardMetrics} snapshot
 * ({@link ShardStats} + ring {@link RingMetrics} + dispatch {@link LatencySnapshot}) from any
 * thread at any life stage; dispatch latency is sampled deterministically every Nth successful
 * completion registration when {@link ShardOptions#withLatencySampleEvery(int)} is enabled
 * (disabled by default). See {@code docs/b5-design.md} §2.5/§4/§5.
 *
 * <p>Instances are created with {@link #create(int, ShardOptions)}; {@link #execute} and
 * {@link #handoff} lazily start the shard. See {@link RingGroup} for a fixed pool with
 * round-robin/hash distribution.
 */
public final class IoShard implements AutoCloseable {

    /** Reserved {@code user_data} for the shard's own eventfd wakeup read. */
    static final long SHARD_WAKEUP_USER_DATA = 0xFF00_0000_0000_0001L;
    /** Reserved {@code user_data} for the shard's ring-wide shutdown cancel. */
    static final long SHARD_CANCEL_USER_DATA = 0xFF00_0000_0000_0002L;

    /** Every reserved shard-internal {@code user_data} has 0xFF in the type byte. */
    private static final long INTERNAL_TYPE_MASK = 0xFF00_0000_0000_0000L;
    private static final long INTERNAL_TYPE_VALUE = 0xFF00_0000_0000_0000L;

    private static final long CLOSE_GRACE_NANOS = 250_000_000L;
    private static final long SHUTDOWN_POLL_NANOS = 50_000L;
    private static final long BACKPRESSURE_PARK_NANOS = 50_000L;
    private static final int HANDOFF_FALLBACK_SPINS = 1024;
    private static final int MAX_CPU_ID = 1024;

    /**
     * All-zero {@link RingMetrics} used before the shard has created its ring (or if the ring
     * snapshot is unavailable). Frozen shape of B5 design §2.1: zero counters, zero occupancy,
     * zero effective entries.
     */
    private static final RingMetrics ZERO_RING_METRICS = new RingMetrics(
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,   // sqesPrepared .. inFlight
            0L, 0L, 0L, 0L, 0L, 0L,           // enters .. sqQueued
            0, 0L, 0, 0L);                    // sqEntries, cqReady, cqEntries, cqOverflowEvents

    private static final int EFD_CLOEXEC = LibCDispatcher.EFD_CLOEXEC;
    private static final int EFD_NONBLOCK = LibCDispatcher.EFD_NONBLOCK;
    private static final LibCDispatcher LIBC = NativeDispatcher.C;

    private final int shardId;
    private final ShardOptions options;
    private final Object lifecycle = new Object();
    private final MpscQueue inbound;
    private final CompletionTable completions;
    private final Context context;
    private final ShardLoopContext loopContext;
    private final ResultConsumer completionConsumer;

    // Cross-thread state / counters.
    private final AtomicInteger wakePending = new AtomicInteger();
    private final AtomicLong tasksSubmitted = new AtomicLong();
    private final AtomicLong tasksRejected = new AtomicLong();
    private final AtomicLong handoffsSent = new AtomicLong();
    private final AtomicLong handoffsInlineFallback = new AtomicLong();
    private final AtomicLong eventfdWrites = new AtomicLong();

    private volatile boolean started;
    private volatile boolean stopping;
    private volatile boolean closed;
    private volatile boolean terminated;
    private volatile boolean loopExited;
    private volatile Thread thread;
    private volatile String threadName;
    private volatile ShardBody body;
    private volatile Throwable loopFailure;
    private volatile Throwable startFailure;
    private volatile long stopDeadlineNanos;
    /** Upper bound on teardown derived from an explicit close/group deadline (0 = none). */
    private volatile long closeBudgetDeadlineNanos;
    private volatile long lastInFlight;
    private volatile boolean wakeupArmed;
    private volatile boolean closeTimedOut;
    private volatile int requestedCpu;
    private volatile int currentCpu = -1;
    private volatile boolean affinityApplied;
    private volatile long cancelledAtClose;
    private volatile long abandonedAtClose;

    // B5 dispatch-latency sampling state: the histogram is allocated only when sampling is
    // enabled (>= 1), recorded on the shard thread only, and snapshotted from any thread as a
    // best-effort telemetry read (design §4/§5).
    private final int latencySampleEvery;
    private final LatencyHistogram dispatchLatencyHistogram;
    /** Last ring snapshot captured before the ring reference is dropped on teardown. */
    private volatile RingMetrics frozenRingMetrics;

    // Managed on the shard thread (read from foreign threads only through volatile snapshots).
    // taskFailures is an AtomicLong because the inline handoff fallback can throw and count
    // from arbitrary foreign threads concurrently; everything else has one writer but is
    // volatile so stats() snapshots are safely published.
    private volatile JUring ring;
    private int eventFd = -1;
    private MemorySegment wakeupReadBuf;
    private long wakeupWriteBuf;
    private boolean cancelPending;
    private volatile long shutdownCancelled;
    private volatile long tasksExecuted;
    private final AtomicLong taskFailures = new AtomicLong();
    private volatile long completionsRegistered;
    private volatile long completionsDispatched;
    private volatile long completionsUnattributed;
    private volatile long completionFailures;
    private volatile long ringSubmits;
    private CountDownLatch initLatch;
    private Throwable initError;

    private IoShard(int shardId, ShardOptions options) {
        this.shardId = shardId;
        this.options = options;
        this.inbound = new MpscQueue(options.inboundCapacity());
        this.completions = new CompletionTable((int) Math.min(Integer.MAX_VALUE,
                Math.max(1024L, (long) options.ringEntries() * 2L)));
        this.context = new Context();
        this.loopContext = new ShardLoopContext(this);
        this.completionConsumer = this::dispatchCompletion;
        this.requestedCpu = options.cpuAffinity();
        this.latencySampleEvery = options.latencySampleEvery();
        this.dispatchLatencyHistogram = latencySampleEvery > 0 ? new LatencyHistogram() : null;
    }

    /**
     * Creates an unstarted shard.
     *
     * @param shardId non-negative shard id (used in the thread name and stats)
     * @param options shard configuration
     * @return a new, unstarted shard
     */
    public static IoShard create(int shardId, ShardOptions options) {
        if (shardId < 0) {
            throw new IllegalArgumentException("shardId must be >= 0: " + shardId);
        }
        Objects.requireNonNull(options, "options");
        return new IoShard(shardId, options);
    }

    /**
     * Returns the shard id.
     *
     * @return the shard id
     */
    public int id() {
        return shardId;
    }

    /**
     * Returns the immutable options this shard was created with.
     *
     * @return the immutable options this shard was created with
     */
    public ShardOptions options() {
        return options;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts the shard thread, which creates the ring on that thread. Idempotent.
     *
     * @throws IllegalStateException when the shard is closed/stopping/already terminated
     * @throws RuntimeException      wrapped ring-creation failure (the shard is then closed)
     */
    public void start() {
        startInternal(null);
    }

    /**
     * Package-private body seam (B4 design §9): installs the event loop this shard should
     * run instead of the generic task/handoff loop. Must be called before {@link #start()}.
     */
    void setBody(ShardBody loopBody) {
        Objects.requireNonNull(loopBody, "body");
        synchronized (lifecycle) {
            requireInstallableLocked();
            if (body != null) {
                throw new IllegalStateException("IoShard " + shardId + " already has a loop body");
            }
            body = loopBody;
        }
    }

    /** Package-private body seam: install and start in one call. */
    void startWithBody(ShardBody loopBody) {
        setBody(loopBody);
        start();
    }

    private void requireInstallableLocked() {
        if (closed) {
            throw new IllegalStateException("IoShard " + shardId + " is closed");
        }
        if (started || terminated) {
            throw new IllegalStateException("IoShard " + shardId + " is already started");
        }
        if (stopping) {
            throw new IllegalStateException("IoShard " + shardId + " is stopping");
        }
    }

    private void startInternal(ShardBody requestedBody) {
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("IoShard " + shardId + " is closed");
            }
            if (started) {
                if (requestedBody != null && body != requestedBody) {
                    throw new IllegalStateException("IoShard " + shardId + " is already started with a different body");
                }
                return;
            }
            if (terminated) {
                throw new IllegalStateException("IoShard " + shardId + " has terminated");
            }
            if (stopping) {
                throw new IllegalStateException("IoShard " + shardId + " is stopping");
            }
            if (requestedBody != null) {
                if (body != null) {
                    throw new IllegalStateException("IoShard " + shardId + " already has a loop body");
                }
                body = requestedBody;
            }
            initError = null;
            initLatch = new CountDownLatch(1);
            Thread shardThread = Thread.ofPlatform()
                    .name("juring-shard-" + shardId + "-" + options.name())
                    .daemon(true)
                    .unstarted(this::runShardThread);
            this.thread = shardThread;
            this.threadName = shardThread.getName();
            shardThread.start();
            awaitInitLatch();
            Throwable failure = initError;
            if (failure != null) {
                startFailure = failure;
                started = false;
                closed = true;
                terminated = true;
                throw asShardStartFailure(failure);
            }
            started = true;
        }
    }

    private void awaitInitLatch() {
        CountDownLatch latch = initLatch;
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException asShardStartFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new RuntimeException("Failed to start IoShard", failure);
    }

    /**
     * Returns whether the shard loop is currently running.
     *
     * @return whether the shard loop is currently running
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Returns whether a stop was requested (new work is rejected).
     *
     * @return whether a stop was requested
     */
    public boolean isStopping() {
        return stopping;
    }

    /**
     * Returns whether the shard is closed.
     *
     * @return whether {@link #close(long)} completed (or the shard failed to start)
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Non-blocking stop request: sets the stopping flag, rejects new
     * {@code execute}/{@code handoff}/{@code call}, wakes the loop and returns immediately.
     * Accepted tasks are still drained while the shutdown budget allows; the ring is closed
     * by the shard thread once the loop exits.
     */
    public void requestStop() {
        boolean first;
        synchronized (lifecycle) {
            if (closed || stopping) {
                return;
            }
            first = beginStopLocked();
        }
        if (first) {
            wakeBody();
            forceWakeupSignal();
        }
    }

    private boolean beginStopLocked() {
        if (stopping) {
            return false;
        }
        stopping = true;
        stopDeadlineLocked();
        return true;
    }

    /**
     * Effective shutdown deadline: the explicit close/group budget when one was supplied,
     * otherwise (or when smaller) the {@link ShardOptions#shutdownTimeoutMillis()} budget.
     * Must be called on the shard thread or under {@link #lifecycle}.
     */
    private long stopDeadlineLocked() {
        if (stopDeadlineNanos == 0L) {
            long optionsDeadline = System.nanoTime() + options.shutdownTimeoutMillis() * 1_000_000L;
            long closeDeadline = closeBudgetDeadlineNanos;
            stopDeadlineNanos = closeDeadline == 0L
                    ? optionsDeadline
                    : Math.min(optionsDeadline, closeDeadline);
        }
        return stopDeadlineNanos;
    }

    /**
     * Waits up to {@code timeoutMillis} for the shard thread to end.
     *
     * @param timeoutMillis maximum wait in milliseconds, {@code >= 0}
     * @return whether the thread ended; {@code false} on timeout or interrupt (the
     * interrupt flag is restored)
     */
    public boolean awaitTermination(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        Thread shardThread = thread;
        if (shardThread == null) {
            return true;
        }
        if (shardThread == Thread.currentThread()) {
            return false;
        }
        try {
            shardThread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !shardThread.isAlive();
    }

    /**
     * Closes the shard with {@link ShardOptions#shutdownTimeoutMillis()}. Equivalent to
     * {@code close(options.shutdownTimeoutMillis())}; see {@link #close(long)}.
     */
    @Override
    public void close() {
        close(options.shutdownTimeoutMillis());
    }

    /**
     * Graceful, bounded, idempotent close from a foreign thread.
     *
     * <p>Requests the stop, waits for the shard thread to finish its bounded shutdown
     * (drain accepted tasks, cancel in-flight requests, close the ring) up to
     * {@code timeoutMillis + 250 ms}, and records {@link ShardStats#closeTimedOut()} when
     * the wait expires. The ring is still released by the shard thread once a stuck user
     * task returns. Never throws for an operation failure or when already closed.
     *
     * @param timeoutMillis graceful-shutdown budget in milliseconds ({@code >= 0})
     * @throws IllegalArgumentException when {@code timeoutMillis < 0}
     * @throws IllegalStateException    when called from the shard's own thread
     */
    public void close(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        long budgetDeadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        closeUntil(budgetDeadline + CLOSE_GRACE_NANOS, budgetDeadline);
    }

    /**
     * Package-private shared-deadline close used by {@link RingGroup#close(long)}: one
     * absolute join deadline (caller budget + 250 ms grace) and one absolute teardown
     * budget deadline, both on the {@link System#nanoTime()} clock, are applied to every
     * shard so group shutdown is bounded by the caller budget, not {@code N * budget}.
     *
     * <p>The teardown budget tightens the shard's own
     * {@link ShardOptions#shutdownTimeoutMillis()} budget (whichever is earlier), so a
     * {@code close(0)}/short-budget caller cannot leave the shard thread draining the ring
     * for the full default budget after the join returned.
     */
    void closeUntil(long hardDeadlineNanos, long budgetDeadlineNanos) {
        Thread shardThread;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            if (thread == Thread.currentThread()) {
                throw new IllegalStateException("cannot close a shard from its own thread");
            }
            if (thread == null && !started) {
                stopping = true;
                closed = true;
                return;
            }
            long existingBudget = closeBudgetDeadlineNanos;
            closeBudgetDeadlineNanos = existingBudget == 0L
                    ? budgetDeadlineNanos
                    : Math.min(existingBudget, budgetDeadlineNanos);
            beginStopLocked();
            if (stopDeadlineNanos > closeBudgetDeadlineNanos) {
                stopDeadlineNanos = closeBudgetDeadlineNanos;
            }
            shardThread = thread;
        }
        wakeBody();
        forceWakeupSignal();
        boolean interrupted = false;
        if (shardThread != null) {
            if (shardThread == Thread.currentThread()) {
                throw new IllegalStateException("cannot close a shard from its own thread");
            }
            long waitNanos = Math.max(0L, hardDeadlineNanos - System.nanoTime());
            try {
                shardThread.join(TimeUnit.NANOSECONDS.toMillis(waitNanos) + 1L);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (shardThread.isAlive()) {
                closeTimedOut = true;
            }
        }
        synchronized (lifecycle) {
            closed = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Foreign-thread work submission
    // ------------------------------------------------------------------

    /**
     * Enqueues a task on the shard (auto-starts it). From a foreign thread this blocks
     * while the bounded inbound queue is full (until space, stop or close); from the shard
     * thread it throws immediately instead of blocking on itself.
     *
     * @param task the task to run on the shard thread
     * @throws IllegalStateException when the shard is stopping/closed/terminated or the
     *                               inbound queue is full and the caller is the shard thread
     */
    public void execute(ShardTask task) {
        Objects.requireNonNull(task, "task");
        try {
            requireGenericLoop();
            ensureRunning();
        } catch (IllegalStateException e) {
            tasksRejected.incrementAndGet();
            throw e;
        }
        while (true) {
            if (inbound.offer(task)) {
                tasksSubmitted.incrementAndGet();
                signalWakeup();
                return;
            }
            if (inShardThread()) {
                tasksRejected.incrementAndGet();
                throw new IllegalStateException("IoShard " + shardId
                        + " inbound queue is full; a task must not block its own shard");
            }
            if (stopping || closed || terminated) {
                tasksRejected.incrementAndGet();
                throw new IllegalStateException("IoShard " + shardId + " is stopping/closed");
            }
            LockSupport.parkNanos(BACKPRESSURE_PARK_NANOS);
        }
    }

    /**
     * Enqueues a continuation on this shard (auto-starts it). Never blocks: if the inbound
     * queue stays full after a bounded spin, the continuation runs inline on the calling
     * thread and {@link ShardStats#handoffsInlineFallback()} is counted. Such a
     * continuation must therefore not assume target-shard-thread confinement.
     *
     * @param continuation the continuation to run on this shard (may run inline on overflow)
     * @throws IllegalStateException when the shard is stopping/closed/terminated
     */
    public void handoff(Runnable continuation) {
        Objects.requireNonNull(continuation, "continuation");
        requireGenericLoop();
        ensureRunning();
        if (inbound.offer(continuation)) {
            handoffsSent.incrementAndGet();
            signalWakeup();
            return;
        }
        for (int i = 0; i < HANDOFF_FALLBACK_SPINS; i++) {
            if (inbound.offer(continuation)) {
                handoffsSent.incrementAndGet();
                signalWakeup();
                return;
            }
            if (stopping || closed) {
                throw new IllegalStateException("IoShard " + shardId + " is stopping/closed");
            }
            Thread.onSpinWait();
        }
        if (stopping || closed || terminated) {
            throw new IllegalStateException("IoShard " + shardId + " is stopping/closed");
        }
        handoffsInlineFallback.incrementAndGet();
        try {
            continuation.run();
        } catch (Throwable t) {
            taskFailures.incrementAndGet();
        }
    }

    /**
     * Runs {@code action} on the shard thread and blocks the caller until it completes.
     * From the shard thread itself the action runs inline (no deadlock).
     *
     * @param <T>    the action's result type
     * @param action the action to run on the shard thread
     * @return the action's result
     * @throws IllegalStateException when the shard is stopping/closed or terminates before
     *                               the action runs
     */
    public <T> T call(Callable<T> action) {
        Objects.requireNonNull(action, "action");
        requireGenericLoop();
        ensureRunning();
        if (inShardThread()) {
            if (stopping) {
                throw new IllegalStateException("IoShard " + shardId + " is stopping");
            }
            return invokeCallable(action);
        }
        CallRequest<T> request = new CallRequest<>(action);
        while (true) {
            if (inbound.offer(request)) {
                signalWakeup();
                break;
            }
            if (stopping || closed || terminated) {
                throw new IllegalStateException("IoShard " + shardId + " is stopping/closed");
            }
            LockSupport.parkNanos(BACKPRESSURE_PARK_NANOS);
        }
        try {
            while (!request.latch.await(50L, TimeUnit.MILLISECONDS)) {
                if (terminated) {
                    throw new IllegalStateException("IoShard " + shardId
                            + " terminated before the call was executed");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for IoShard.call", e);
        }
        if (request.error != null) {
            if (request.error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (request.error instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(request.error);
        }
        return request.value;
    }

    private static <T> T invokeCallable(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Returns whether the calling thread is this shard's event-loop thread.
     *
     * @return whether the calling thread is the shard thread
     */
    public boolean inShardThread() {
        return Thread.currentThread() == thread;
    }

    /**
     * Returns the shard platform thread.
     *
     * @return the shard platform thread, or {@code null} before {@link #start()}
     */
    public Thread shardThread() {
        return thread;
    }

    /**
     * Returns a snapshot of the shard's lifecycle, affinity and counters. Safe from any
     * thread, before start, while running and after close.
     *
     * @return an immutable stats snapshot
     */
    public ShardStats stats() {
        long inFlight = lastInFlight;
        ShardBody currentBody = body;
        JUring currentRing = ring;
        if (currentBody != null && started && currentRing != null) {
            try {
                inFlight = Math.max(0, currentRing.inFlightCount());
            } catch (Throwable ignored) {
                // Ring closed underneath us: keep the cached snapshot.
            }
        }
        return new ShardStats(
                shardId, threadName,
                requestedCpu, currentCpu, affinityApplied,
                started, stopping, closed,
                tasksSubmitted.get(), tasksExecuted, taskFailures.get(), tasksRejected.get(),
                completionsRegistered, completionsDispatched,
                completionsUnattributed, completionFailures,
                handoffsSent.get(), handoffsInlineFallback.get(),
                eventfdWrites.get(), ringSubmits,
                inFlight,
                wakeupArmed,
                cancelledAtClose, abandonedAtClose, closeTimedOut);
    }

    /**
     * Snapshot of the shard's B4 lifecycle/counters, B5 ring metrics and B5 dispatch latency.
     * Safe from any thread, before start, while running and after close; never throws.
     *
     * <p>The three components are three separate best-effort snapshots, not an atomic view
     * (design §2.5/§5). {@code dispatchLatency} is {@link LatencySnapshot#empty()} when
     * sampling is disabled, for body-hosted shards, and until a sampled registration has been
     * dispatched. Ring metrics stay readable after close (the final ring snapshot is cached
     * before the ring reference is released).
     *
     * @return an immutable metrics snapshot
     */
    public ShardMetrics metrics() {
        return new ShardMetrics(stats(), snapshotRingMetrics(), snapshotDispatchLatency());
    }

    private RingMetrics snapshotRingMetrics() {
        JUring localRing = ring;
        if (localRing != null) {
            try {
                return localRing.ringMetrics();
            } catch (Throwable ignored) {
                // JUring.ringMetrics() is total; keep the cached/zero snapshot defensively.
            }
        }
        RingMetrics frozen = frozenRingMetrics;
        return frozen != null ? frozen : ZERO_RING_METRICS;
    }

    private LatencySnapshot snapshotDispatchLatency() {
        LatencyHistogram histogram = dispatchLatencyHistogram;
        if (histogram == null || body != null) {
            return LatencySnapshot.empty();
        }
        try {
            return histogram.snapshot();
        } catch (Throwable ignored) {
            return LatencySnapshot.empty();
        }
    }

    /**
     * Package-private: merges this shard's dispatch-latency histogram into {@code target}
     * (bucket-wise) for {@link RingGroup#aggregateMetrics()}. Best effort: disabled sampling
     * and body-hosted shards contribute nothing.
     */
    void mergeDispatchLatencyInto(LatencyHistogram target) {
        LatencyHistogram histogram = dispatchLatencyHistogram;
        if (histogram == null) {
            return;
        }
        try {
            target.merge(histogram);
        } catch (Throwable ignored) {
            // Telemetry aggregation is best effort; never propagate.
        }
    }

    // ------------------------------------------------------------------
    // Shard thread
    // ------------------------------------------------------------------

    private void runShardThread() {
        try {
            initializeOnShardThread();
        } catch (Throwable t) {
            initError = t;
            initLatch.countDown();
            cleanupNativeResources();
            synchronized (lifecycle) {
                started = false;
                closed = true;
                terminated = true;
                stopping = true;
            }
            return;
        }
        initLatch.countDown();
        try {
            ShardBody loopBody = body;
            if (loopBody != null) {
                loopBody.run(loopContext);
            } else {
                genericLoop();
            }
        } catch (Throwable t) {
            loopFailure = t;
        } finally {
            stopping = true;
            try {
                shutdownAndCloseRing();
            } catch (Throwable t) {
                if (loopFailure == null) {
                    loopFailure = t;
                }
            } finally {
                synchronized (lifecycle) {
                    started = false;
                    terminated = true;
                    loopExited = true;
                }
            }
        }
    }

    private void initializeOnShardThread() {
        applyCpuAffinity();
        JUring newRing = new JUring(options.ringEntries(), options.ringOptions());
        this.ring = newRing;
        if (body == null) {
            int efd = LIBC.eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
            if (efd < 0) {
                try {
                    newRing.close(0L);
                } catch (Throwable ignored) {
                    // best effort on the failure path
                }
                throw new IllegalStateException("Failed to create eventfd for shard " + shardId);
            }
            this.eventFd = efd;
            this.wakeupReadBuf = LIBC.malloc(8L);
            this.wakeupWriteBuf = LIBC.mallocAddress(8L);
            MemorySegment.ofAddress(wakeupWriteBuf).reinterpret(8L).set(JAVA_LONG, 0L, 1L);
        }
    }

    private void applyCpuAffinity() {
        int cpu = options.cpuAffinity();
        requestedCpu = cpu;
        boolean applied = false;
        if (cpu != ShardOptions.NO_CPU_AFFINITY && cpu >= 0 && cpu < MAX_CPU_ID) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mask = arena.allocate(128L);
                mask.set(JAVA_LONG, (long) (cpu / 64) * 8L, 1L << (cpu % 64));
                applied = LIBC.schedSetAffinity(0, 128L, mask) == 0;
            } catch (Throwable ignored) {
                applied = false;
            }
        }
        affinityApplied = applied;
        try {
            currentCpu = LIBC.schedGetCpu();
        } catch (Throwable ignored) {
            currentCpu = -1;
        }
    }

    // ------------------------------------------------------------------
    // Generic event loop (frozen behaviour, design §4)
    // ------------------------------------------------------------------

    private void genericLoop() {
        final JUring localRing = ring;
        while (!stopping) {
            boolean progress = false;

            // 1. Reap and dispatch up to cqeBatch completions (handlers run inline).
            if (localRing.peekForBatchResult(options.cqeBatch(), completionConsumer) > 0) {
                progress = true;
            }
            updateInFlightStats();

            // 2. Run up to taskBatch inbound tasks.
            if (runInbound(options.taskBatch()) > 0) {
                progress = true;
            }

            // 3. Submit prepared SQEs once (batched).
            if (submitIfPending()) {
                progress = true;
            }
            if (progress) {
                continue;
            }

            // 5a. Idle spin, checking inbound and the (already posted) completion ring.
            boolean activity = false;
            int spins = options.spinIterations();
            for (int i = 0; i < spins; i++) {
                if (stopping || !inbound.isEmpty()) {
                    activity = true;
                    break;
                }
                if (localRing.peekForBatchResult(options.cqeBatch(), completionConsumer) > 0) {
                    activity = true;
                    break;
                }
                Thread.onSpinWait();
            }
            updateInFlightStats();
            if (stopping) {
                break;
            }
            if (activity) {
                continue;
            }

            // 5b. Arm the eventfd wakeup read and block inside the kernel. Always arming
            // (even when a stop races in) guarantees the foreign stop wake can complete it.
            if (!wakeupArmed) {
                armWakeupRead(localRing);
            }
            localRing.submitAndWait(1);
            wakePending.set(0);
            updateInFlightStats();
        }
    }

    private void armWakeupRead(JUring localRing) {
        localRing.prepareWakeupRead(eventFd, wakeupReadBuf, SHARD_WAKEUP_USER_DATA);
        wakeupArmed = true;
    }

    private int runInbound(int maxEntries) {
        int ran = 0;
        while (ran < maxEntries) {
            Object entry = inbound.poll();
            if (entry == null) {
                break;
            }
            ran++;
            runEntry(entry);
        }
        return ran;
    }

    private void runEntry(Object entry) {
        if (entry instanceof CallRequest<?> request) {
            request.runRequest();
            return;
        }
        if (entry instanceof ShardTask task) {
            tasksExecuted++;
            try {
                task.run(context);
            } catch (Throwable t) {
                taskFailures.incrementAndGet();
            }
            return;
        }
        if (entry instanceof Runnable continuation) {
            try {
                continuation.run();
            } catch (Throwable t) {
                taskFailures.incrementAndGet();
            }
        }
    }

    private boolean submitIfPending() {
        JUring localRing = ring;
        if (localRing == null) {
            return false;
        }
        if (localRing.pendingSqes() <= 0) {
            return false;
        }
        localRing.submit();
        ringSubmits++;
        return true;
    }

    /** Dispatches one reaped completion; never throws. */
    private void dispatchCompletion(Result result) {
        long userData = result.id();
        if ((userData & INTERNAL_TYPE_MASK) == INTERNAL_TYPE_VALUE) {
            if (userData == SHARD_WAKEUP_USER_DATA) {
                wakeupArmed = false;
                wakePending.set(0);
            } else if (userData == SHARD_CANCEL_USER_DATA) {
                cancelPending = false;
            }
            return;
        }
        if (stopping && result.result() == -JuringException.ECANCELED) {
            shutdownCancelled++;
        }
        CompletionHandler handler = completions.remove(userData);
        if (handler == null) {
            completionsUnattributed++;
            return;
        }
        completionsDispatched++;
        try {
            LatencyHistogram histogram = dispatchLatencyHistogram;
            if (histogram != null) {
                long registeredAtNanos = completions.lastRemovedTimestamp();
                if (registeredAtNanos != 0L) {
                    // B5 dispatch-latency window: registration -> immediately before the
                    // handler body runs. Recorded on the shard thread; Handler exceptions are
                    // recorded too (the record happens before invoking the handler).
                    histogram.record(System.nanoTime() - registeredAtNanos);
                }
            }
            handler.completed(result);
        } catch (Throwable t) {
            completionFailures++;
        }
    }

    private void updateInFlightStats() {
        lastInFlight = computeUserInFlight();
    }

    private long computeUserInFlight() {
        JUring localRing = ring;
        if (localRing == null) {
            return 0L;
        }
        try {
            int internal = (wakeupArmed ? 1 : 0) + (cancelPending ? 1 : 0);
            return Math.max(0L, localRing.inFlightCount() - internal);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // Wakeups
    // ------------------------------------------------------------------

    /** Coalesced eventfd wake: at most one pending write per queued burst. */
    private void signalWakeup() {
        if (eventFd < 0 || wakeupWriteBuf == 0L) {
            return;
        }
        if (wakePending.compareAndSet(0, 1)) {
            eventfdWrites.incrementAndGet();
            try {
                LIBC.write(eventFd, wakeupWriteBuf, 8L);
            } catch (Throwable ignored) {
                // EAGAIN = counter saturated and a wake is already pending.
            }
        }
    }

    /** Stop-specific wake: always writes, so a stop can never be coalesced away. */
    private void forceWakeupSignal() {
        if (eventFd < 0 || wakeupWriteBuf == 0L) {
            return;
        }
        wakePending.set(1);
        eventfdWrites.incrementAndGet();
        try {
            LIBC.write(eventFd, wakeupWriteBuf, 8L);
        } catch (Throwable ignored) {
            // EAGAIN = a wake is already pending.
        }
    }

    private void wakeBody() {
        ShardBody currentBody = body;
        if (currentBody == null) {
            return;
        }
        try {
            currentBody.wake();
        } catch (Throwable ignored) {
            // A body wake hook must not break lifecycle calls.
        }
    }

    // ------------------------------------------------------------------
    // Shutdown (design §7)
    // ------------------------------------------------------------------

    private void shutdownAndCloseRing() {
        long deadline = stopDeadlineNanos;
        if (deadline == 0L) {
            long optionsDeadline = System.nanoTime() + options.shutdownTimeoutMillis() * 1_000_000L;
            long closeDeadline = closeBudgetDeadlineNanos;
            deadline = closeDeadline == 0L ? optionsDeadline : Math.min(optionsDeadline, closeDeadline);
            stopDeadlineNanos = deadline;
        }
        JUring localRing = ring;
        try {
            if (localRing != null && !localRing.isClosed()) {
                if (body == null) {
                    shutdownGenericLoop(localRing, deadline);
                }
                int wakeupInFlight = wakeupArmed ? 1 : 0;
                int internalInFlight = wakeupInFlight + (cancelPending ? 1 : 0);
                long remainingMillis = Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L);
                try {
                    localRing.close(remainingMillis);
                } catch (Throwable t) {
                    if (loopFailure == null) {
                        loopFailure = t;
                    }
                }
                long ringCancelled = localRing.cancelledAtClose();
                long ringAbandoned = localRing.abandonedAtClose();
                cancelledAtClose = shutdownCancelled + Math.max(0L, ringCancelled - wakeupInFlight);
                abandonedAtClose = Math.max(0L, ringAbandoned - internalInFlight);
            }
        } finally {
            JUring lastRing = ring;
            if (lastRing != null) {
                try {
                    // B5: keep ring metrics readable after close (frozen final snapshot).
                    frozenRingMetrics = lastRing.ringMetrics();
                } catch (Throwable ignored) {
                    // Best effort; metrics() falls back to the zero snapshot.
                }
            }
            lastInFlight = 0L;
            ring = null;
            completions.clear();
            cleanupNativeResources();
        }
    }

    /**
     * Generic-loop graceful drain: accepted tasks and completions first, then one ring-wide
     * cancel so in-flight requests deliver {@code -ECANCELED} to their handlers, all bounded
     * by {@code deadlineNanos}.
     */
    private void shutdownGenericLoop(JUring localRing, long deadlineNanos) {
        // 1. Drain accepted inbound work and keep dispatching completions.
        while (true) {
            boolean progress = false;
            if (safePoll(localRing) > 0) {
                progress = true;
            }
            if (runInbound(options.taskBatch()) > 0) {
                progress = true;
            }
            if (submitIfPending()) {
                progress = true;
            }
            updateInFlightStats();
            if (inbound.isEmpty()) {
                break;
            }
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }
            if (!progress) {
                LockSupport.parkNanos(SHUTDOWN_POLL_NANOS);
            }
        }

        // 2. Cancel every request still in flight and dispatch its -ECANCELED completion.
        if (computeUserInFlight() > 0) {
            try {
                localRing.prepareCancelAnyInternal(SHARD_CANCEL_USER_DATA);
                cancelPending = true;
                if (localRing.pendingSqes() > 0) {
                    localRing.submit();
                    ringSubmits++;
                }
            } catch (Throwable t) {
                cancelPending = false;
            }
            while (true) {
                int reaped = safePoll(localRing);
                updateInFlightStats();
                if (computeUserInFlight() <= 0) {
                    break;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
                if (reaped == 0) {
                    LockSupport.parkNanos(SHUTDOWN_POLL_NANOS);
                }
            }
        }
    }

    private int safePoll(JUring localRing) {
        try {
            return localRing.pollForBatchResult(options.cqeBatch(), completionConsumer);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void cleanupNativeResources() {
        if (eventFd >= 0) {
            try {
                LIBC.close(eventFd);
            } catch (Throwable ignored) {
                // best effort
            }
            eventFd = -1;
        }
        if (wakeupReadBuf != null) {
            try {
                LIBC.free(wakeupReadBuf);
            } catch (Throwable ignored) {
                // best effort
            }
            wakeupReadBuf = null;
        }
        if (wakeupWriteBuf != 0L) {
            try {
                LIBC.free(wakeupWriteBuf);
            } catch (Throwable ignored) {
                // best effort
            }
            wakeupWriteBuf = 0L;
        }
    }

    // ------------------------------------------------------------------
    // Internals shared with ShardContext / ShardLoopContext
    // ------------------------------------------------------------------

    private void requireGenericLoop() {
        if (body != null) {
            throw new IllegalStateException("IoShard " + shardId
                    + " is body-hosted; generic execute/handoff/call is not supported");
        }
    }

    /** Package-private: ring accessor for {@link ShardLoopContext} (shard-thread confined). */
    JUring requireRing() {
        if (Thread.currentThread() != thread) {
            throw new WrongThreadException("IoShard " + shardId
                    + " ring is confined to shard thread " + (thread == null ? "<unstarted>" : thread.getName()));
        }
        JUring localRing = ring;
        if (localRing == null || localRing.isClosed()) {
            throw new IllegalStateException("IoShard " + shardId + " ring is not available");
        }
        return localRing;
    }

    /**
     * Package-private body seam: {@code true} once the shard loop has fully ended and the
     * ring has been released (as opposed to {@link #isClosed()}, which is set when the
     * caller's bounded close returns even if the shard thread is still finishing teardown).
     */
    boolean isTerminated() {
        return terminated;
    }

    /** Package-private: {@code true} once the loop body / event loop has returned. */
    boolean isLoopExited() {
        return loopExited;
    }

    /** Package-private stats hook for body-hosted loops. */
    void recordRingSubmit() {
        ringSubmits++;
    }

    private void ensureRunning() {
        if (closed) {
            throw new IllegalStateException("IoShard " + shardId + " is closed");
        }
        if (!started) {
            start();
        }
        if (stopping) {
            throw new IllegalStateException("IoShard " + shardId + " is stopping");
        }
        if (terminated) {
            throw new IllegalStateException("IoShard " + shardId + " has terminated");
        }
    }

    /** One-shot request used by {@link #call(Callable)}. */
    private static final class CallRequest<T> {
        private final Callable<T> action;
        private final CountDownLatch latch = new CountDownLatch(1);
        private T value;
        private Throwable error;

        private CallRequest(Callable<T> action) {
            this.action = action;
        }

        private void runRequest() {
            try {
                value = action.call();
            } catch (Throwable t) {
                error = t;
            } finally {
                latch.countDown();
            }
        }
    }

    private final class Context implements ShardContext {
        @Override
        public int shardId() {
            return shardId;
        }

        @Override
        public JUring ring() {
            return requireRing();
        }

        @Override
        public void onCompletion(long userData, CompletionHandler handler) {
            if (Thread.currentThread() != thread) {
                throw new IllegalStateException("onCompletion must be called on the shard thread");
            }
            if (stopping || closed) {
                throw new IllegalStateException("IoShard " + shardId + " is stopping; cannot register completions");
            }
            int sampleEvery = latencySampleEvery;
            long registrationIndex = completionsRegistered + 1L;
            long registeredAtNanos = 0L;
            if (sampleEvery > 0 && registrationIndex % sampleEvery == 0L) {
                // B5: only every Nth successful registration pays System.nanoTime(); the
                // timestamp is stored with the registration itself (no extra table).
                registeredAtNanos = System.nanoTime();
            }
            completions.put(userData, handler, registeredAtNanos);
            completionsRegistered = registrationIndex;
        }

        @Override
        public void submit() {
            JUring localRing = requireRing();
            if (localRing.pendingSqes() > 0) {
                localRing.submit();
                ringSubmits++;
            }
        }

        @Override
        public void execute(ShardTask task) {
            IoShard.this.execute(task);
        }

        @Override
        public void handoff(IoShard target, Runnable continuation) {
            Objects.requireNonNull(target, "target");
            target.handoff(continuation);
        }

        @Override
        public boolean stopping() {
            return stopping;
        }
    }
}
