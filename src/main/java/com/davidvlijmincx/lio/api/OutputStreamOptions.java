package com.davidvlijmincx.lio.api;

import java.util.Objects;

/**
 * Immutable options for {@link JuringOutputStream}, modelled on seastar's
 * {@code file_output_stream_options} (see {@code SEASTAR-PLAN.md} §4 B2 and
 * {@code docs/b2-design.md} §1.2).
 *
 * <p>Defaults mirror seastar: a 64 KiB buffer, write-behind depth 1, no preallocation
 * hint, no {@code fdatasync} on close and 4096-byte alignment (safe for O_DIRECT on this
 * host's ext4 data NVMe and on tmpfs test files).
 *
 * <p>B6 adds two durability dimensions (see {@code docs/b6-design.md} §2.5): a
 * {@link DurabilityMode} that makes {@code flush()}/{@code close()} durability points, and
 * an optional {@code posix_fadvise(2)} page-cache hint applied once at {@code open}. Both
 * default to today's behaviour ({@link DurabilityMode#WRITEBACK},
 * {@link FadviseAdvice#NORMAL} = no syscall).
 */
public final class OutputStreamOptions {

    /** Default buffer size in bytes ({@code 65536}, seastar default). */
    public static final int DEFAULT_BUFFER_SIZE = 65536;

    /** Default buffer/write alignment in bytes ({@code 4096}). */
    public static final int DEFAULT_ALIGNMENT = 4096;

    private static final OutputStreamOptions DEFAULTS =
            new OutputStreamOptions(DEFAULT_BUFFER_SIZE, 1, DEFAULT_ALIGNMENT, 0L, false,
                    DurabilityMode.WRITEBACK, FadviseAdvice.NORMAL);

    private final int bufferSize;
    private final int writeBehind;
    private final int alignment;
    private final long preallocationSize;
    private final boolean syncOnClose;
    private final DurabilityMode durability;
    private final FadviseAdvice fadvise;

    private OutputStreamOptions(int bufferSize, int writeBehind, int alignment,
                                long preallocationSize, boolean syncOnClose,
                                DurabilityMode durability, FadviseAdvice fadvise) {
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must be >= 1: " + bufferSize);
        }
        if (writeBehind < 1 || writeBehind > 1024) {
            throw new IllegalArgumentException("writeBehind must be in [1, 1024]: " + writeBehind);
        }
        if (alignment < 1 || Integer.bitCount(alignment) != 1 || alignment > (1 << 20)) {
            throw new IllegalArgumentException("alignment must be a power of two in [1, 1 MiB]: " + alignment);
        }
        if (preallocationSize < 0) {
            throw new IllegalArgumentException("preallocationSize must be >= 0: " + preallocationSize);
        }
        this.bufferSize = bufferSize;
        this.writeBehind = writeBehind;
        this.alignment = alignment;
        this.preallocationSize = preallocationSize;
        this.syncOnClose = syncOnClose;
        this.durability = Objects.requireNonNull(durability, "durability");
        this.fadvise = Objects.requireNonNull(fadvise, "advice");
    }

    /**
     * Returns the shared default options (buffer 65536, write-behind 1, alignment 4096,
     * no preallocation, no sync on close, {@link DurabilityMode#WRITEBACK} and no
     * {@code fadvise} hint).
     *
     * @return the shared default options
     */
    public static OutputStreamOptions defaults() {
        return DEFAULTS;
    }

    /** @return the configured logical buffer size in bytes */
    public int bufferSize() {
        return bufferSize;
    }

    /** @return the maximum number of concurrent write-behind operations */
    public int writeBehind() {
        return writeBehind;
    }

    /** @return buffer and write alignment in bytes (power of two) */
    public int alignment() {
        return alignment;
    }

    /** @return the {@code fallocate} preallocation hint issued at open, {@code 0} for none */
    public long preallocationSize() {
        return preallocationSize;
    }

    /** @return {@code true} when {@link JuringOutputStream#close()} calls {@code fdatasync} */
    public boolean syncOnClose() {
        return syncOnClose;
    }

    /**
     * Returns the durability mode of the stream (B6, see {@code docs/b6-design.md} §2.5/§3.1).
     *
     * <p>{@link DurabilityMode#WRITEBACK} reproduces the frozen B2 behaviour: no sync in
     * {@link JuringOutputStream#flush()}, one {@code fdatasync} in
     * {@link JuringOutputStream#close()} iff {@link #syncOnClose()}. Any other mode makes
     * every {@code flush()}/{@code close()} a durability point.
     *
     * @return the configured durability mode
     */
    public DurabilityMode durability() {
        return durability;
    }

    /**
     * Returns the {@code posix_fadvise(2)} hint applied once to the whole file when the
     * stream is opened (B6, see {@code docs/b6-design.md} §2.6).
     *
     * @return the configured page-cache advice; {@link FadviseAdvice#NORMAL} issues no syscall
     */
    public FadviseAdvice fadvise() {
        return fadvise;
    }

    /**
     * Returns a copy with a different logical buffer size.
     *
     * @param bufferSize bytes, {@code >= 1}; rounded up to the alignment internally
     * @return a new options object
     */
    public OutputStreamOptions withBufferSize(int bufferSize) {
        return new OutputStreamOptions(bufferSize, writeBehind, alignment, preallocationSize, syncOnClose, durability, fadvise);
    }

    /**
     * Returns a copy with a different write-behind depth.
     *
     * @param writeBehind concurrent writes, {@code 1..1024}
     * @return a new options object
     */
    public OutputStreamOptions withWriteBehind(int writeBehind) {
        return new OutputStreamOptions(bufferSize, writeBehind, alignment, preallocationSize, syncOnClose, durability, fadvise);
    }

    /**
     * Returns a copy with a different alignment.
     *
     * @param alignment power of two in {@code [1, 1 MiB]}
     * @return a new options object
     */
    public OutputStreamOptions withAlignment(int alignment) {
        return new OutputStreamOptions(bufferSize, writeBehind, alignment, preallocationSize, syncOnClose, durability, fadvise);
    }

    /**
     * Returns a copy with a different preallocation hint.
     *
     * @param preallocationSize bytes, {@code >= 0} ({@code 0} disables the hint)
     * @return a new options object
     */
    public OutputStreamOptions withPreallocationSize(long preallocationSize) {
        return new OutputStreamOptions(bufferSize, writeBehind, alignment, preallocationSize, syncOnClose, durability, fadvise);
    }

    /**
     * Returns a copy with {@code fdatasync}-on-close enabled or disabled.
     *
     * @param syncOnClose {@code true} to make {@link JuringOutputStream#close()} durable
     * @return a new options object
     */
    public OutputStreamOptions withSyncOnClose(boolean syncOnClose) {
        return new OutputStreamOptions(bufferSize, writeBehind, alignment, preallocationSize, syncOnClose, durability, fadvise);
    }

    /**
     * Returns a copy with a different durability mode (B6). The mode makes every
     * {@link JuringOutputStream#flush()} and {@link JuringOutputStream#close()} a durability
     * point; {@link DurabilityMode#DSYNC} additionally asserts that the backing file was
     * opened with {@code O_DSYNC} (checked by {@link JuringOutputStream#open}).
     *
     * @param durability the mode, must not be {@code null}
     * @return a new options object
     */
    public OutputStreamOptions withDurabilityMode(DurabilityMode durability) {
        return new OutputStreamOptions(bufferSize, writeBehind, alignment, preallocationSize, syncOnClose, durability, fadvise);
    }

    /**
     * Returns a copy with a different {@code posix_fadvise(2)} hint, applied once to the whole
     * file when the stream is opened (B6).
     *
     * @param advice the hint, must not be {@code null}; {@link FadviseAdvice#NORMAL} disables it
     * @return a new options object
     */
    public OutputStreamOptions withFadvise(FadviseAdvice advice) {
        return new OutputStreamOptions(bufferSize, writeBehind, alignment, preallocationSize, syncOnClose, durability, advice);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OutputStreamOptions other)) {
            return false;
        }
        return bufferSize == other.bufferSize
                && writeBehind == other.writeBehind
                && alignment == other.alignment
                && preallocationSize == other.preallocationSize
                && syncOnClose == other.syncOnClose
                && durability == other.durability
                && fadvise == other.fadvise;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bufferSize, writeBehind, alignment, preallocationSize, syncOnClose,
                durability, fadvise);
    }

    @Override
    public String toString() {
        return "OutputStreamOptions{bufferSize=" + bufferSize
                + ", writeBehind=" + writeBehind
                + ", alignment=" + alignment
                + ", preallocationSize=" + preallocationSize
                + ", syncOnClose=" + syncOnClose
                + ", durability=" + durability
                + ", fadvise=" + fadvise + '}';
    }
}
