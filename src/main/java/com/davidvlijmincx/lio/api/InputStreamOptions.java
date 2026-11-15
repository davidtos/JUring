package com.davidvlijmincx.lio.api;

import java.util.Objects;

/**
 * Immutable options for {@link JuringInputStream}, modelled on seastar's
 * {@code file_input_stream_options} (see {@code SEASTAR-PLAN.md} §4 B2 and
 * {@code docs/b2-design.md} §1.1).
 *
 * <p>Defaults mirror seastar: an 8 KiB buffer, no extra readahead (one read in flight),
 * adaptive adjustments disabled and 4096-byte alignment (safe on this host's ext4 data
 * NVMe and on tmpfs test files).
 *
 * <p>Example:
 * <pre>{@code
 * try (JuringInputStream in = JuringInputStream.open(file,
 *         InputStreamOptions.defaults().withBufferSize(128 * 1024).withReadAhead(7))) {
 *     MemorySegment chunk;
 *     while ((chunk = in.readChunk()).byteSize() > 0) { ... }
 * }
 * }</pre>
 */
public final class InputStreamOptions {

    /** Default chunk/buffer size in bytes ({@code 8192}, seastar default). */
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    /** Default buffer/read alignment in bytes ({@code 4096}). */
    public static final int DEFAULT_ALIGNMENT = 4096;

    private static final InputStreamOptions DEFAULTS =
            new InputStreamOptions(DEFAULT_BUFFER_SIZE, 0, DEFAULT_ALIGNMENT, false, FadviseAdvice.NORMAL);

    private final int bufferSize;
    private final int readAhead;
    private final int alignment;
    private final boolean dynamicAdjustments;
    private final FadviseAdvice fadvise;

    private InputStreamOptions(int bufferSize, int readAhead, int alignment, boolean dynamicAdjustments,
                               FadviseAdvice fadvise) {
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must be >= 1: " + bufferSize);
        }
        if (readAhead < 0 || readAhead > 1024) {
            throw new IllegalArgumentException("readAhead must be in [0, 1024]: " + readAhead);
        }
        if (alignment < 1 || Integer.bitCount(alignment) != 1 || alignment > (1 << 20)) {
            throw new IllegalArgumentException("alignment must be a power of two in [1, 1 MiB]: " + alignment);
        }
        this.bufferSize = bufferSize;
        this.readAhead = readAhead;
        this.alignment = alignment;
        this.dynamicAdjustments = dynamicAdjustments;
        this.fadvise = Objects.requireNonNull(fadvise, "advice");
    }

    /**
     * Returns the shared default options (buffer 8192, readahead 0, alignment 4096,
     * adaptive adjustments off, no {@code fadvise} hint).
     *
     * @return the shared default options
     */
    public static InputStreamOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the configured maximum chunk/buffer size in bytes.
     *
     * @return the configured maximum chunk/buffer size
     */
    public int bufferSize() {
        return bufferSize;
    }

    /**
     * Returns the configured maximum number of <em>extra</em> prefetch buffers.
     *
     * <p>The maximum number of reads in flight is {@code readAhead() + 1}.
     *
     * @return the configured maximum number of extra prefetch buffers
     */
    public int readAhead() {
        return readAhead;
    }

    /**
     * Returns the buffer and read alignment in bytes.
     *
     * @return the configured alignment (a power of two)
     */
    public int alignment() {
        return alignment;
    }

    /**
     * Returns whether seastar-style adaptive buffer-size/readahead adjustments are enabled.
     *
     * @return {@code true} when adaptive adjustments are enabled
     */
    public boolean dynamicAdjustments() {
        return dynamicAdjustments;
    }

    /**
     * Returns the {@code posix_fadvise(2)} hint applied once at {@link JuringInputStream#open}
     * (B6, see {@code docs/b6-design.md} §2.4).
     *
     * <p>{@link FadviseAdvice#NORMAL} is the "no hint" sentinel: it issues no syscall.
     *
     * @return the configured page-cache advice
     */
    public FadviseAdvice fadvise() {
        return fadvise;
    }

    /**
     * Returns a copy with a different maximum buffer size.
     *
     * @param bufferSize bytes, {@code >= 1}
     * @return a new options object
     */
    public InputStreamOptions withBufferSize(int bufferSize) {
        return new InputStreamOptions(bufferSize, readAhead, alignment, dynamicAdjustments, fadvise);
    }

    /**
     * Returns a copy with a different maximum readahead depth.
     *
     * @param readAhead extra prefetch buffers, {@code 0..1024}
     * @return a new options object
     */
    public InputStreamOptions withReadAhead(int readAhead) {
        return new InputStreamOptions(bufferSize, readAhead, alignment, dynamicAdjustments, fadvise);
    }

    /**
     * Returns a copy with a different alignment.
     *
     * @param alignment power of two in {@code [1, 1 MiB]}
     * @return a new options object
     */
    public InputStreamOptions withAlignment(int alignment) {
        return new InputStreamOptions(bufferSize, readAhead, alignment, dynamicAdjustments, fadvise);
    }

    /**
     * Returns a copy with adaptive adjustments enabled or disabled.
     *
     * @param enabled {@code true} for seastar-style slow-start/dynamic readahead
     * @return a new options object
     */
    public InputStreamOptions withDynamicAdjustments(boolean enabled) {
        return new InputStreamOptions(bufferSize, readAhead, alignment, enabled, fadvise);
    }

    /**
     * Returns a copy with a different {@code posix_fadvise(2)} page-cache hint, applied once
     * when the stream is opened (B6, see {@code docs/b6-design.md} §2.4).
     *
     * @param advice the hint, must not be {@code null}; {@link FadviseAdvice#NORMAL} disables it
     * @return a new options object
     */
    public InputStreamOptions withFadvise(FadviseAdvice advice) {
        return new InputStreamOptions(bufferSize, readAhead, alignment, dynamicAdjustments, advice);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InputStreamOptions other)) {
            return false;
        }
        return bufferSize == other.bufferSize
                && readAhead == other.readAhead
                && alignment == other.alignment
                && dynamicAdjustments == other.dynamicAdjustments
                && fadvise == other.fadvise;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bufferSize, readAhead, alignment, dynamicAdjustments, fadvise);
    }

    @Override
    public String toString() {
        return "InputStreamOptions{bufferSize=" + bufferSize
                + ", readAhead=" + readAhead
                + ", alignment=" + alignment
                + ", dynamicAdjustments=" + dynamicAdjustments
                + ", fadvise=" + fadvise + '}';
    }
}
