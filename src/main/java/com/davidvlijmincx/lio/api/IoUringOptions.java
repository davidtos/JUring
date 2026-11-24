package com.davidvlijmincx.lio.api;

/**
 * Setup flags for initializing an io_uring instance.
 */
public enum IoUringOptions {

    /** Busy-poll for completions instead of using interrupts; file I/O must use {@code O_DIRECT}. */
    IORING_SETUP_IOPOLL(1 << 0),
    /** Let a kernel thread poll the submission queue, reducing {@code io_uring_enter()} calls. */
    IORING_SETUP_SQPOLL(1 << 1),
    /** Pin the SQPOLL kernel thread to the CPU requested through the setup parameters. */
    IORING_SETUP_SQ_AFF(1 << 2),
    /** Use an explicitly requested completion-queue size instead of the default of twice the entries. */
    IORING_SETUP_CQSIZE(1 << 3),
    /** Clamp the requested queue size to the kernel maximum instead of failing the setup. */
    IORING_SETUP_CLAMP(1 << 4),
    /** Share the asynchronous-worker pool of another ring. */
    IORING_SETUP_ATTACH_WQ(1 << 5),
    /** Create the ring disabled; it is enabled later by an explicit registration. */
    IORING_SETUP_R_DISABLED(1 << 6),
    /** Keep submitting remaining SQEs even when one submission fails. */
    IORING_SETUP_SUBMIT_ALL(1 << 7),
    /** Run task work cooperatively in the submitting task instead of raising an IPI. */
    IORING_SETUP_COOP_TASKRUN(1 << 8),
    /** Mark the ring's task-run state in the SQ flags. */
    IORING_SETUP_TASKRUN_FLAG(1 << 9),
    /** Use 128-byte submission queue entries. */
    IORING_SETUP_SQE128(1 << 10),
    /** Use 32-byte completion queue entries. */
    IORING_SETUP_CQE32(1 << 11),
    /** Declare that only one task submits to the ring, which enables kernel optimizations. */
    IORING_SETUP_SINGLE_ISSUER(1 << 12),
    /** Defer task work to explicit {@code io_uring_enter()}/{@code io_uring_getevents} calls. */
    IORING_SETUP_DEFER_TASKRUN(1 << 13),
    /** Do not memory-map the rings; the application provides the backing memory. */
    IORING_SETUP_NO_MMAP(1 << 14),
    /** Register the ring descriptor only, without mapping the rings. */
    IORING_SETUP_REGISTERED_FD_ONLY(1 << 15),
    /** Omit the submission-queue array indirection and consume SQEs in order. */
    IORING_SETUP_NO_SQARRAY(1 << 16),
    /** Busy-poll with a hybrid scheme that avoids pinning a core while idle. */
    IORING_SETUP_HYBRID_IOPOLL(1 << 17);

    final int value;

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
