package com.davidvlijmincx.lio.api;

/**
 * What a blocking call does when every pre-allocated concurrent-call record of its worker is
 * already in use.
 *
 * <p>{@link JUringBlocking} pre-allocates one record per concurrent blocking call (see
 * {@link BlockingOptions#withMaxConcurrentCalls(int)}). A call that finds no free record is
 * <em>oversubscribed</em>; this policy decides whether it waits for one or fails fast.
 *
 * @see BlockingOptions
 * @see JUringBlocking
 */
public enum OverflowPolicy {

    /**
     * Park the caller until a record is released, then proceed. This is the default and keeps
     * the historical "block until the I/O can be submitted" semantics. Waiting happens on the
     * worker's admission monitor, so virtual threads unmount instead of pinning their carrier.
     */
    BLOCK,

    /**
     * Reject the call immediately with an {@link IllegalStateException}. Use this when a
     * bounded queue is preferred over blocking (for example to make an undersized
     * {@code maxConcurrentCalls} a visible configuration error instead of latency).
     */
    FAIL
}
