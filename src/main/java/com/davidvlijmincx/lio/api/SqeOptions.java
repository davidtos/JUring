package com.davidvlijmincx.lio.api;

public enum SqeOptions {

    IOSQE_FIXED_FILE((byte) (1)),    // 0x01
    IOSQE_IO_DRAIN((byte) (1 << 1)),     // 0x02
    IOSQE_IO_LINK((byte) (1 << 2)),      // 0x04
    IOSQE_IO_HARDLINK((byte) (1 << 3)),  // 0x08
    IOSQE_ASYNC((byte) (1 << 4)),        // 0x10
    IOSQE_BUFFER_SELECT((byte) (1 << 5)), // 0x20
    IOSQE_CQE_SKIP_SUCCESS((byte) (1 << 6)), // 0x40
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
