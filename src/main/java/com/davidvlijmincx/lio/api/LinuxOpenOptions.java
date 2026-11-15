package com.davidvlijmincx.lio.api;

import java.nio.file.OpenOption;
import java.util.Locale;

/**
 * Linux file open flags.
 *
 * <p>Opening a file with {@link #DSYNC} or {@link #SYNC} makes every write completion
 * durable (the kernel completes the write only once the data is on stable storage). A
 * {@link com.davidvlijmincx.lio.api.JuringFile} opened with either flag reports
 * {@link com.davidvlijmincx.lio.api.DurabilityMode#DSYNC} from
 * {@link com.davidvlijmincx.lio.api.JuringFile#durability()}; explicit
 * {@code fsync}/{@code fdatasync} calls on such a file are redundant but harmless
 * (B6, see {@code docs/b6-design.md} §2.2).
 */
public enum LinuxOpenOptions implements OpenOption {
    /** {@code O_RDONLY}: open for reading only. */
    READ(0),
    /** {@code O_WRONLY}: open for writing only. */
    WRITE(1),
    /** {@code O_RDWR}: open for reading and writing. */
    RDWR(2),
    /** {@code O_CREAT}: create the file when it does not exist. */
    CREATE(0x40),
    /** {@code O_EXCL}: fail when the file already exists; only meaningful together with {@link #CREATE}. */
    EXCLUSIVE(0x80),
    /** {@code O_TRUNC}: truncate the file to zero length when it is opened for writing. */
    TRUNCATE(0x200),
    /** {@code O_APPEND}: writes append to the end of the file. */
    APPEND(0x400),
    /** {@code O_DSYNC}: writes complete durably (data; metadata needed to retrieve it). */
    DSYNC(0x1000),
    /** {@code O_DIRECT}: bypass the page cache; buffers and offsets must be sector-aligned. */
    DIRECT(resolveDirectFlag()),
    /** {@code O_RDONLY | O_DIRECT}: direct reads only. */
    READ_DIRECT(resolveDirectFlag()),
    /** {@code O_WRONLY | O_DIRECT}: direct writes only. */
    WRITE_DIRECT(1 | resolveDirectFlag()),
    /** {@code O_SYNC}: like {@link #DSYNC} and also durably updates the file metadata. */
    SYNC(0x101000),
    /** {@code O_CLOEXEC}: close the descriptor on {@code exec}. */
    CLOEXEC(0x80000);

    private final int flag;

    LinuxOpenOptions(int value) {
        flag = value;
    }

    /**
     * Returns the native Linux open-flag value of this constant.
     *
     * @return the {@code open(2)} flag bits
     */
    public int getValue() {
        return flag;
    }

    /**
     * Combines open flags with bitwise OR.
     *
     * <p>The access-mode constants ({@link #READ}, {@link #WRITE}, {@link #RDWR}) are not
     * independent bits and must not be combined with each other.
     *
     * @param options flags to combine
     * @return the combined native open-flag value
     */
    public static int combineOptions(LinuxOpenOptions... options) {
        int combined = 0;
        for (LinuxOpenOptions opt : options) {
            combined |= opt.flag;
        }
        return combined;
    }

    static int resolveDirectFlag() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm")) {
            return 0x10000; // 65536 on ARM / aarch64
        }
        if (arch.contains("ppc")) {
            return 0x20000; // 131072 on PowerPC
        }
        return 0x4000; // 16384 on x86_64, riscv64, s390x
    }
}
