package com.davidvlijmincx.lio.api;

public enum Flag {
    READ(0),
    WRITE(1),
    READ_DIRECT(0x4000),  // O_DIRECT
    WRITE_DIRECT(1 | 0x4000);

    private int flag;

    Flag(int value) {
        flag = value;
    }

    int getValue() {
        return flag;
    }
}
