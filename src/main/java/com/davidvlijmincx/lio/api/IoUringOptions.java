package com.davidvlijmincx.lio.api;

public enum IoUringOptions {

    IORING_SETUP_IOPOLL((byte) (1 << 0)),           // 1
    IORING_SETUP_SQPOLL((byte) (1 << 1)),           // 2
    IORING_SETUP_SQ_AFF((byte) (1 << 2)),           // 4
    IORING_SETUP_CQSIZE((byte) (1 << 3)),           // 8
    IORING_SETUP_CLAMP((byte) (1 << 4)),            // 16
    IORING_SETUP_ATTACH_WQ((byte) (1 << 5)),        // 32
    IORING_SETUP_R_DISABLED((byte) (1 << 6)),       // 64
    IORING_SETUP_SUBMIT_ALL((byte) (1 << 7)),       // 128
    IORING_SETUP_COOP_TASKRUN((byte) (1 << 8)),     // 256
    IORING_SETUP_TASKRUN_FLAG((byte) (1 << 9)),     // 512
    IORING_SETUP_SQE128((byte) (1 << 10)),          // 1024
    IORING_SETUP_CQE32((byte) (1 << 11)),           // 2048
    IORING_SETUP_SINGLE_ISSUER((byte) (1 << 12)),   // 4096
    IORING_SETUP_DEFER_TASKRUN((byte) (1 << 13)),   // 8192
    IORING_SETUP_NO_MMAP((byte) (1 << 14)),         // 16384
    IORING_SETUP_REGISTERED_FD_ONLY((byte) (1 << 15)), // 32768
    IORING_SETUP_NO_SQARRAY((byte) (1 << 16)),      // 65536
    IORING_SETUP_HYBRID_IOPOLL((byte) (1 << 17));   // 131072

    byte value;

    IoUringOptions(byte value) {
        this.value = value;
    }

    static byte combineOptions(IoUringOptions... options) {
        byte combinedFlags = 0;
        for (IoUringOptions b : options) {
            combinedFlags |= b.value;
        }
        return combinedFlags;
    }
}
