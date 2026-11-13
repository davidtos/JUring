package com.davidvlijmincx.lio.api;

import java.util.Objects;

/**
 * Short-I/O and {@code EAGAIN} policy for the blocking convenience methods of
 * {@link JuringFile} ({@code read}, {@code write}, metadata helpers).
 *
 * <p>The asynchronous fast path ({@link JuringFile#prepareRead} and friends) is always
 * single-shot and never retries: this policy only applies where JUring drives the
 * operation to completion on the caller's behalf.
 *
 * <p>Instances are immutable; use the {@code with*} methods to derive a customised
 * policy. The default policy is:
 * <ul>
 *   <li>{@code eagainRetries = 8} — retry an operation that completes with
 *       {@code -EAGAIN} up to eight times,</li>
 *   <li>{@code eagainBackoffNanos = 50_000} — park for 50 µs between retries,</li>
 *   <li>{@code shortReadAtEofAllowed = true} — a read that reaches EOF returns the
 *       bytes read so far instead of failing.</li>
 * </ul>
 */
public final class IoPolicy {

    private static final IoPolicy DEFAULT = new IoPolicy(8, 50_000L, true);

    private final int eagainRetries;
    private final long eagainBackoffNanos;
    private final boolean shortReadAtEofAllowed;

    private IoPolicy(int eagainRetries, long eagainBackoffNanos, boolean shortReadAtEofAllowed) {
        if (eagainRetries < 0) {
            throw new IllegalArgumentException("eagainRetries must be >= 0: " + eagainRetries);
        }
        if (eagainBackoffNanos < 0) {
            throw new IllegalArgumentException("eagainBackoffNanos must be >= 0: " + eagainBackoffNanos);
        }
        this.eagainRetries = eagainRetries;
        this.eagainBackoffNanos = eagainBackoffNanos;
        this.shortReadAtEofAllowed = shortReadAtEofAllowed;
    }

    /**
     * Returns the default policy (8 {@code EAGAIN} retries, 50 µs backoff, short reads at
     * EOF allowed).
     *
     * @return the shared default policy
     */
    public static IoPolicy defaults() {
        return DEFAULT;
    }

    /**
     * Returns a copy with a different number of {@code EAGAIN} retries.
     *
     * @param eagainRetries maximum retries, {@code >= 0}; {@code 0} disables retrying
     * @return a new policy
     */
    public IoPolicy withEagainRetries(int eagainRetries) {
        return new IoPolicy(eagainRetries, eagainBackoffNanos, shortReadAtEofAllowed);
    }

    /**
     * Returns a copy with a different backoff between {@code EAGAIN} retries.
     *
     * @param eagainBackoffNanos park time in nanoseconds, {@code >= 0}
     * @return a new policy
     */
    public IoPolicy withEagainBackoffNanos(long eagainBackoffNanos) {
        return new IoPolicy(eagainRetries, eagainBackoffNanos, shortReadAtEofAllowed);
    }

    /**
     * Returns a copy that either returns short reads at EOF (default) or raises a
     * {@link JuringException} when EOF is reached before the buffer is full.
     *
     * @param shortReadAtEofAllowed {@code true} to return the short byte count at EOF
     * @return a new policy
     */
    public IoPolicy withShortReadAtEofAllowed(boolean shortReadAtEofAllowed) {
        return new IoPolicy(eagainRetries, eagainBackoffNanos, shortReadAtEofAllowed);
    }

    /** @return maximum number of {@code EAGAIN} retries per blocking operation */
    public int eagainRetries() {
        return eagainRetries;
    }

    /** @return park time in nanoseconds between {@code EAGAIN} retries */
    public long eagainBackoffNanos() {
        return eagainBackoffNanos;
    }

    /** @return {@code true} when a short read at EOF is returned instead of failing */
    public boolean shortReadAtEofAllowed() {
        return shortReadAtEofAllowed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IoPolicy other)) {
            return false;
        }
        return eagainRetries == other.eagainRetries
                && eagainBackoffNanos == other.eagainBackoffNanos
                && shortReadAtEofAllowed == other.shortReadAtEofAllowed;
    }

    @Override
    public int hashCode() {
        return Objects.hash(eagainRetries, eagainBackoffNanos, shortReadAtEofAllowed);
    }

    @Override
    public String toString() {
        return "IoPolicy{eagainRetries=" + eagainRetries
                + ", eagainBackoffNanos=" + eagainBackoffNanos
                + ", shortReadAtEofAllowed=" + shortReadAtEofAllowed + '}';
    }
}
