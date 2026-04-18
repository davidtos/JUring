package com.davidvlijmincx.lio.api;

public enum OperationType {
    READ(0),
    WRITE(1),
    WRITE_FIXED(2),
    OPEN(3),
    CLOSE(4),
    READV(5),
    WRITEV(6);

    private static final OperationType[] VALUES = values();

    private final int index;

    OperationType(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public static OperationType valueOf(int index) {
        return VALUES[index];
    }
}
