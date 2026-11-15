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
    READ(0),
    WRITE(1),
    RDWR(2),
    CREATE(0x40),
    EXCLUSIVE(0x80),
    TRUNCATE(0x200),
    APPEND(0x400),
    /** {@code O_DSYNC}: writes complete durably (data; metadata needed to retrieve it). */
    DSYNC(0x1000),
    DIRECT(resolveDirectFlag()),
    READ_DIRECT(resolveDirectFlag()),
    WRITE_DIRECT(1 | resolveDirectFlag()),
    /** {@code O_SYNC}: like {@link #DSYNC} and also durably updates the file metadata. */
    SYNC(0x101000),
    CLOEXEC(0x80000);

    private final int flag;

    LinuxOpenOptions(int value) {
        flag = value;
    }

    public int getValue() {
        return flag;
    }

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
