package com.davidvlijmincx.lio.api;

/**
 * Setup flags for initializing an io_uring instance.
 */
public enum IoUringOptions {

    IORING_SETUP_IOPOLL(1 << 0),
    IORING_SETUP_SQPOLL(1 << 1),
    IORING_SETUP_SQ_AFF(1 << 2),
    IORING_SETUP_CQSIZE(1 << 3),
    IORING_SETUP_CLAMP(1 << 4),
    IORING_SETUP_ATTACH_WQ(1 << 5),
    IORING_SETUP_R_DISABLED(1 << 6),
    IORING_SETUP_SUBMIT_ALL(1 << 7),
    IORING_SETUP_COOP_TASKRUN(1 << 8),
    IORING_SETUP_TASKRUN_FLAG(1 << 9),
    IORING_SETUP_SQE128(1 << 10),
    IORING_SETUP_CQE32(1 << 11),
    IORING_SETUP_SINGLE_ISSUER(1 << 12),
    IORING_SETUP_DEFER_TASKRUN(1 << 13),
    IORING_SETUP_NO_MMAP(1 << 14),
    IORING_SETUP_REGISTERED_FD_ONLY(1 << 15),
    IORING_SETUP_NO_SQARRAY(1 << 16),
    IORING_SETUP_HYBRID_IOPOLL(1 << 17);

    int value;

    IoUringOptions(int value) {
        this.value = value;
    }

    static int combineOptions(IoUringOptions... options) {
        int combinedFlags = 0;
        for (IoUringOptions b : options) {
            combinedFlags |= b.value;
        }
        return combinedFlags;
    }
}
