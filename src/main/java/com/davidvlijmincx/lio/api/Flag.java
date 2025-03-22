package com.davidvlijmincx.lio.api;

public enum Flag {
    READ(0),
    WRITE(1 | 16384);

    private int flag;

    Flag(int value) {
        flag = value;
    }

    int getValue() {
        return flag;
    }
}
