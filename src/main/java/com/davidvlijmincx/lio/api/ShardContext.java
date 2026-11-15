package com.davidvlijmincx.lio.api;

/**
 * Shard-thread-confined view of one {@link IoShard}, handed to every {@link ShardTask} and
 * to top-level {@link IoShard#handoff(Runnable)} continuations.
 *
 * <p>All methods except {@link #handoff(IoShard, Runnable)} and {@link #stopping()} must be
 * called on the shard thread. Prepared operations are flushed by the loop; call
 * {@link #submit()} only when a task needs an explicit flush.
 *
 * <h2>B3 limitation preserved</h2>
 * <p>Concurrent non-fixed {@code READ_EXT} submissions into the same {@link java.lang.foreign.MemorySegment}
 * share one {@code user_data} (the buffer address) and can therefore confuse the
 * {@link JuringFile} ledger or a completion-handler registration. Do not issue two
 * concurrent non-fixed reads into the same segment from one shard; use fixed-buffer I/O or
 * distinct segments. This is the B3 §11.9/F1 limitation and is deliberately not fixed by B4.
 *
 * <h2>JuringFile blocking helpers</h2>
 * <p>{@link JuringFile}'s blocking helpers ({@code read}/{@code write}/{@code await}/
 * {@code close}) require that every in-flight request on the ring belongs to the file
 * ({@code JuringFile.checkRingOwnership}). The shard loop keeps an internal eventfd wakeup
 * {@code READ} armed while idle; a task that starts while that read is in flight sees one
 * foreign request and the blocking helper throws {@link IllegalStateException}. In practice
 * the idle wakeup that delivers a task consumes that read first, so a task that starts from
 * an idle shard is safe; a task queued behind an already-running batch can hit the window.
 * Inside shard tasks prefer raw {@code ring.prepare*} + {@link #onCompletion(long,
 * CompletionHandler)}, or run the file work from a task that starts when the shard is idle.
 * This is the pre-existing B3 §11.9/F1 class of limitation, deliberately not changed by B4.
 */
public interface ShardContext {

    /**
     * Returns the owning shard id.
     *
     * @return the owning shard id
     */
    int shardId();

    /**
     * Returns the shard's {@link JUring}.
     *
     * @return the shard's ring (confined to the shard thread)
     * @throws WrongThreadException when called from a thread other than the shard thread
     * @throws IllegalStateException when the shard is not running
     */
    JUring ring();

    /**
     * Registers the handler that will be invoked when the request whose operation handle is
     * {@code userData} completes. Must be called on the shard thread <b>before</b> the
     * {@code ring.submit()} that can complete the request.
     *
     * @param userData the raw {@code user_data} returned by the matching {@code ring.prepare*}
     * @param handler  the handler to invoke on the shard thread when the request completes
     * @throws IllegalStateException when called off the shard thread, after
     *                               {@link IoShard#requestStop()}, for a duplicate
     *                               {@code userData}, or when the handler table is full
     */
    void onCompletion(long userData, CompletionHandler handler);

    /** Flushes SQEs prepared so far (optional; the event loop also batches and submits). */
    void submit();

    /**
     * Enqueues a task on this shard from the shard thread.
     *
     * @param task the task to enqueue on this shard
     * @throws IllegalStateException when the inbound queue is full (a shard must not block
     *                               on itself)
     */
    void execute(ShardTask task);

    /**
     * Enqueues a continuation on {@code target} (MPSC queue plus eventfd wake; inline
     * fallback on overflow).
     *
     * @param target       the shard that should run the continuation
     * @param continuation the continuation (may run on the current thread after an overflow
     *                     fallback, so it must not assume target-shard confinement)
     * @throws IllegalStateException when {@code target} is stopping or closed
     */
    void handoff(IoShard target, Runnable continuation);

    /**
     * Returns whether {@link IoShard#requestStop()} has been observed.
     *
     * @return whether a stop was requested
     */
    boolean stopping();
}
