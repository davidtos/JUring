package com.davidvlijmincx.lio.api;

public enum IoUringflags {

    IORING_SETUP_IOPOLL(1 << 0),           // 1
    IORING_SETUP_SQPOLL(1 << 1),           // 2
    IORING_SETUP_SQ_AFF(1 << 2),           // 4
    IORING_SETUP_CQSIZE(1 << 3),           // 8
    IORING_SETUP_CLAMP(1 << 4),            // 16
    IORING_SETUP_ATTACH_WQ(1 << 5),        // 32
    IORING_SETUP_R_DISABLED(1 << 6),       // 64
    IORING_SETUP_SUBMIT_ALL(1 << 7),       // 128
    IORING_SETUP_COOP_TASKRUN(1 << 8),     // 256
    IORING_SETUP_TASKRUN_FLAG(1 << 9),     // 512
    IORING_SETUP_SQE128(1 << 10),          // 1024
    IORING_SETUP_CQE32(1 << 11),           // 2048
    IORING_SETUP_SINGLE_ISSUER(1 << 12),   // 4096
    IORING_SETUP_DEFER_TASKRUN(1 << 13),   // 8192
    IORING_SETUP_NO_MMAP(1 << 14),         // 16384
    IORING_SETUP_REGISTERED_FD_ONLY(1 << 15), // 32768
    IORING_SETUP_NO_SQARRAY(1 << 16),      // 65536
    IORING_SETUP_HYBRID_IOPOLL(1 << 17);   // 131072

    int value;
    IoUringflags(int value) {
        this.value = value;
    }
}
