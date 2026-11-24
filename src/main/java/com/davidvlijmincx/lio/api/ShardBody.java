package com.davidvlijmincx.lio.api;

/**
 * Package-private loop-body seam: a shard can host a custom event loop
 * instead of the generic task/handoff loop. Used by the {@code JUringBlocking} integration
 * so its tuned per-worker slot protocol runs on top of {@code IoShard}'s ring, thread,
 * affinity, lifecycle and bounded close.
 *
 * <p>The body's {@link #run(ShardLoopContext)} executes on the shard's platform thread,
 * after the ring has been created there and before any generic-loop work. When it returns
 * (or throws), {@code IoShard} performs its normal bounded ring teardown; a body must not
 * close the ring itself.
 */
interface ShardBody {

    /** Runs the body's event loop on the shard thread until it decides to stop. */
    void run(ShardLoopContext ctx);

    /**
     * Called from a foreign thread by {@code IoShard.requestStop()} / {@code close(...)}
     * after the stopping flag is set, to unblock a parked or kernel-blocked body. Must be
     * safe to call at any time; the default is a no-op.
     */
    default void wake() {
    }

    /**
     * Called on the shard thread after the ring has been closed and before the shard is
     * marked terminated. A body uses it to unblock callers whose requests were still in
     * flight when the bounded shutdown gave up: only now is the kernel done with their
     * buffers. The default is a no-op.
     */
    default void afterRingClose() {
    }
}
