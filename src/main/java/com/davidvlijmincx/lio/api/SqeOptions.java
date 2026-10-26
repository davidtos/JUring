package com.davidvlijmincx.lio.api;

/**
 * Submission queue entry (SQE) flags.
 */
public enum SqeOptions {

    IOSQE_FIXED_FILE((byte) (1)),
    IOSQE_IO_DRAIN((byte) (1 << 1)),
    IOSQE_IO_LINK((byte) (1 << 2)),
    IOSQE_IO_HARDLINK((byte) (1 << 3)),
    IOSQE_ASYNC((byte) (1 << 4)),
    IOSQE_BUFFER_SELECT((byte) (1 << 5)),
    IOSQE_CQE_SKIP_SUCCESS((byte) (1 << 6)),
    WF_NOWAIT((byte) (8));

    final byte value;

    SqeOptions(byte value) {
        this.value = value;
    }

    static byte combineOptions(SqeOptions... options) {
        byte combinedFlags = 0;
        if (options.length == 1) {
            return options[0].value;
        }
        for (SqeOptions b : options) {
            combinedFlags |= b.value;
        }
        return combinedFlags;
    }
}
