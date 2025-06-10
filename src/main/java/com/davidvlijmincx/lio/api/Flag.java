package com.davidvlijmincx.lio.api;

public enum Flag {
    READ(0),
    READ_DIRECT(16384),
    WRITE(1),
    WRITE_DIRECT(1 | 16384),
    CREATE(64);

    private int flag;

    Flag(int value) {
        flag = value;
    }

    int getValue() {
        return flag;
    }
}
