package com.davidvlijmincx.lio.api;

/**
 * Types of asynchronous operations supported by io_uring.
 */
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
    CANCEL(9),
    READ_FIXED(10),
    READ_POOLED(11),
    NOP(12),
    WRITE_EXT(13),
    READ_EXT(14),
    FSYNC(15),
    TIMEOUT(16),
    FALLOCATE(17),
    FTRUNCATE(18),
    STATX(19),
    /** B3: {@code IORING_OP_LINK_TIMEOUT} (opcode 15); appended, never renumber existing indices. */
    LINK_TIMEOUT(20);

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
