package com.davidvlijmincx.lio.api;

/**
 * Linux block-layer I/O priority encoding for the {@code io_uring_sqe.ioprio} field.
 *
 * <p>The value is the same one accepted by {@code ioprio_set(2)} / {@code ionice}: a
 * 3-bit class in bits 13-15 and an 8-level class data value in the low bits
 * ({@code (class << 13) | level}). See {@code Documentation/block/ioprio.rst} in the
 * Linux kernel tree.
 *
 * <h2>What it does and does not do</h2>
 * <p>This is a <b>hint</b> for the block layer, not a fairness or QoS mechanism: it only
 * affects request ordering when the active I/O scheduler is priority-aware. The kernel
 * documents support as scheduler dependent, currently implemented by {@code bfq} and
 * {@code mq-deadline} ({@code none} and {@code kyber} ignore it). {@code mq-deadline}
 * dispatches by class only (RT &gt; BE &gt; IDLE); {@code bfq} also uses the 8 levels.
 *
 * <p>Because the kernel falls back to the submitting thread's priority when the SQE field
 * is zero, {@link #NONE} ({@code 0}) means <b>inherit</b> — it keeps plain
 * {@code ionice}/{@code nice} behaviour for every request that does not set a value.
 *
 * <p>Use separate rings/shards or OS-level controls (for example cgroup v2
 * {@code io.weight}/{@code io.max}) when guaranteed isolation is required.
 */
public final class IoPriority {

    /** {@code IOPRIO_CLASS_NONE}: no priority is set; the request inherits the thread's. */
    public static final short CLASS_NONE = 0;
    /** {@code IOPRIO_CLASS_RT}: real-time class; needs {@code CAP_SYS_ADMIN} or {@code CAP_SYS_NICE}. */
    public static final short CLASS_RT = 1;
    /** {@code IOPRIO_CLASS_BE}: best-effort class ({@code 0} is highest, {@code 7} lowest). */
    public static final short CLASS_BE = 2;
    /** {@code IOPRIO_CLASS_IDLE}: idle class; served only when nothing else needs the device. */
    public static final short CLASS_IDLE = 3;

    /** Encoded "no priority": the request inherits the submitting thread's I/O priority. */
    public static final short NONE = 0;

    /** {@code IOPRIO_CLASS_SHIFT}: the class occupies the top 3 bits of the 16-bit value. */
    public static final int CLASS_SHIFT = 13;

    /** Highest valid class data value ({@code 0..7}). */
    public static final int MAX_LEVEL = 7;

    private static final int LEVEL_MASK = (1 << CLASS_SHIFT) - 1;

    private IoPriority() {
    }

    /**
     * Encodes a class and level into the 16-bit {@code ioprio} value.
     *
     * @param prioClass one of {@link #CLASS_NONE}, {@link #CLASS_RT}, {@link #CLASS_BE},
     *                  {@link #CLASS_IDLE}
     * @param level     class data, {@code 0..7} (highest to lowest); must be {@code 0} for
     *                  {@link #CLASS_NONE} and {@link #CLASS_IDLE}
     * @return the encoded {@code ioprio} value
     * @throws IllegalArgumentException when the class or level is invalid
     */
    public static short ioprio(int prioClass, int level) {
        if (prioClass < CLASS_NONE || prioClass > CLASS_IDLE) {
            throw new IllegalArgumentException("invalid I/O priority class " + prioClass
                    + ": expected 0 (none), 1 (RT), 2 (BE) or 3 (IDLE)");
        }
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("invalid I/O priority level " + level
                    + ": expected 0.." + MAX_LEVEL);
        }
        if (prioClass == CLASS_NONE && level != 0) {
            // The kernel rejects a class-NONE value that carries data (ioprio_check_cap).
            throw new IllegalArgumentException("IOPRIO_CLASS_NONE requires level 0, got " + level);
        }
        if (prioClass == CLASS_IDLE && level != 0) {
            // IDLE has no class data; keep this exception client-side instead of relying on
            // scheduler-dependent interpretation.
            throw new IllegalArgumentException("IOPRIO_CLASS_IDLE requires level 0, got " + level);
        }
        return (short) ((prioClass << CLASS_SHIFT) | level);
    }

    /**
     * Returns the encoded best-effort priority.
     *
     * @param level best-effort level, {@code 0} highest to {@code 7} lowest
     * @return the encoded {@code ioprio} value
     */
    public static short bestEffort(int level) {
        return ioprio(CLASS_BE, level);
    }

    /**
     * Returns the encoded real-time priority.
     *
     * @param level real-time level, {@code 0} highest to {@code 7} lowest
     * @return the encoded {@code ioprio} value
     */
    public static short realTime(int level) {
        return ioprio(CLASS_RT, level);
    }

    /**
     * Returns the encoded idle-class priority.
     *
     * @return the encoded {@code ioprio} value ({@code CLASS_IDLE}, level 0)
     */
    public static short idle() {
        return ioprio(CLASS_IDLE, 0);
    }

    /**
     * Tests whether a raw 16-bit value is a valid {@code ioprio} encoding.
     *
     * @param ioprio the raw value (only the low 16 bits are significant)
     * @return {@code true} when the class/level combination is a valid encoding
     */
    public static boolean isValid(short ioprio) {
        int bits = ioprio & 0xFFFF;
        int prioClass = bits >>> CLASS_SHIFT;
        int level = bits & LEVEL_MASK;
        return switch (prioClass) {
            case CLASS_NONE, CLASS_IDLE -> level == 0;
            case CLASS_RT, CLASS_BE -> level <= MAX_LEVEL;
            default -> false;
        };
    }
}
