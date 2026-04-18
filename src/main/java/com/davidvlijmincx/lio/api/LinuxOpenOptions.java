package com.davidvlijmincx.lio.api;

import java.nio.file.OpenOption;

public enum LinuxOpenOptions implements OpenOption {
    READ(0),
    // 16384 is 0x4000
    READ_DIRECT(16384),
    WRITE(1),
    // 1 | 16384
    WRITE_DIRECT(16385),
    CREATE(64);

    // Use int, not byte
    private final int flag;

    LinuxOpenOptions(int value) {
        flag = value;
    }

    int getValue() {
        return flag;
    }
}