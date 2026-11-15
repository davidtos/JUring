package com.davidvlijmincx.lio.api;

/**
 * Submission queue entry (SQE) flags.
 */
public enum SqeOptions {

    /** The {@code fd} field of the SQE is an index into the ring's registered files. */
    IOSQE_FIXED_FILE((byte) (1)),
    /** Do not start this operation until all previously submitted operations have completed. */
    IOSQE_IO_DRAIN((byte) (1 << 1)),
    /** Link this operation to the next SQE: the next one starts only after this one completes, and a failure breaks the chain. */
    IOSQE_IO_LINK((byte) (1 << 2)),
    /** Hard link to the next SQE: the chain continues even when this operation fails. */
    IOSQE_IO_HARDLINK((byte) (1 << 3)),
    /** Force the operation through an asynchronous worker instead of executing it inline. */
    IOSQE_ASYNC((byte) (1 << 4)),
    /** Select the buffer for this operation from a provided-buffer group instead of a fixed buffer. */
    IOSQE_BUFFER_SELECT((byte) (1 << 5)),
    /** Do not post a completion when the operation succeeds; only failures are reported. */
    IOSQE_CQE_SKIP_SUCCESS((byte) (1 << 6));

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
