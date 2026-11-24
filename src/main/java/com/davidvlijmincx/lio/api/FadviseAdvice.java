package com.davidvlijmincx.lio.api;

/**
 * POSIX {@code posix_fadvise(2)} advice values, forwarded verbatim to the kernel by
 * {@link JuringFile#fadvise(FadviseAdvice, long, long)}.
 *
 * <p>The constants are the Linux {@code fadvise.h} advice values:
 * {@code NORMAL=0, RANDOM=1, SEQUENTIAL=2, WILLNEED=3, DONTNEED=4, NOREUSE=5}.
 * {@link #NORMAL} is the "no hint" sentinel for the stream options ({@link #value()} is
 * forwarded as {@code 0} by an explicit {@code JuringFile.fadvise} call).
 *
 * <p>This enum is the argument-validation domain for the advice parameter: no other value
 * can be expressed. It is an advisory hint and never changes file content, stream position
 * or ring state.
 */
public enum FadviseAdvice {

    /** No hint (kernel default behaviour); the stream-option sentinel. */
    NORMAL(0),
    /** Expect random page accesses; the kernel may read ahead more conservatively. */
    RANDOM(1),
    /** Expect sequential page accesses; the kernel may read ahead more aggressively. */
    SEQUENTIAL(2),
    /** Prefetch the range into the page cache. */
    WILLNEED(3),
    /** Free the page cache for the range. */
    DONTNEED(4),
    /** The range is expected to be accessed once; the kernel may avoid retaining it. */
    NOREUSE(5);

    private final int value;

    FadviseAdvice(int value) {
        this.value = value;
    }

    /**
     * Returns the POSIX {@code posix_fadvise(2)} advice constant forwarded to the kernel.
     *
     * @return the native advice value
     */
    public int value() {
        return value;
    }
}
