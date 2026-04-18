package com.davidvlijmincx.lio.api;

public enum OperationType {
    READ(0),
    WRITE(1),
    WRITE_FIXED(2),
    OPEN(3),
    CLOSE(4),
    READV(5),
    WRITEV(6),
    READV_FIXED(7),
    WRITEV_FIXED(8),
    ACCEPT(9),
    CONNECT(10),
    RECV(11),
    SEND(12),
    SEND_EXT(13),
    RECV_EXT(14),
    MULTISHOT_ACCEPT(15),
    CANCEL(16);

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
