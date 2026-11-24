package com.davidvlijmincx.lio.api;

/**
 * Types of asynchronous operations supported by io_uring.
 */
public enum OperationType {
    /** Asynchronous read into a ring-allocated or caller-supplied buffer. */
    READ(0),
    /** Asynchronous write from a caller-supplied buffer. */
    WRITE(1),
    /** Write from a registered fixed buffer. */
    WRITE_FIXED(2),
    /** Open a path and return the new file descriptor as the completion result. */
    OPEN(3),
    /** Close a file descriptor. */
    CLOSE(4),
    /** Vectored read ({@code readv}) into ring-allocated buffers. */
    READV(5),
    /** Vectored write ({@code writev}) from caller-supplied buffers. */
    WRITEV(6),
    /** Vectored read into registered fixed buffers. */
    READV_FIXED(7),
    /** Vectored write from registered fixed buffers. */
    WRITEV_FIXED(8),
    /** Cancel an in-flight operation. */
    CANCEL(9),
    /** Read into a registered fixed buffer. */
    READ_FIXED(10),
    /** Read that selects a buffer from the ring's provided-buffer pool. */
    READ_POOLED(11),
    /** No operation; completes immediately. */
    NOP(12),
    /** Extended write. */
    WRITE_EXT(13),
    /** Extended read. */
    READ_EXT(14),
    /** Flush file data and/or metadata ({@code fsync}/{@code fdatasync}). */
    FSYNC(15),
    /** Standalone io_uring timeout. */
    TIMEOUT(16),
    /** Allocate or deallocate file space ({@code fallocate}). */
    FALLOCATE(17),
    /** Change the file length ({@code ftruncate}). */
    FTRUNCATE(18),
    /** Retrieve file status ({@code statx}). */
    STATX(19),
    /** {@code IORING_OP_LINK_TIMEOUT} (opcode 15); appended, never renumber existing indices. */
    LINK_TIMEOUT(20);

    private static final OperationType[] VALUES = values();

    private final int index;

    OperationType(int index) {
        this.index = index;
    }

    /**
     * Returns the stable identifier used to encode this type in an operation's
     * {@code user_data}.
     *
     * <p>The identifier is the position of the constant in the enum; new types are appended
     * so existing identifiers never change. It is not necessarily the kernel io_uring opcode.
     *
     * @return the stable operation-type identifier
     */
    public int getIndex() {
        return index;
    }

    /**
     * Returns the operation type with the given stable identifier.
     *
     * @param index operation-type identifier
     * @return the matching operation type
     * @throws ArrayIndexOutOfBoundsException when no type carries {@code index}
     */
    public static OperationType valueOf(int index) {
        return VALUES[index];
    }
}
