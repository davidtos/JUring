package com.davidvlijmincx.lio.api;

import java.io.IOException;

/**
 * Checked exception for JUring file operations, carrying the Linux {@code errno}.
 *
 * <p>Every failure that originates from the kernel is reported as the positive
 * {@code errno} value (the negative return value of an io_uring opcode negated, or the
 * errno captured from a failed libc call). Synthetic failures that are not kernel errors
 * use {@code errno == 0}.
 *
 * <p>Example:
 * <pre>{@code
 * try (JuringFile file = JuringFile.open(ring, path, LinuxOpenOptions.READ)) {
 *     file.read(0, buffer);
 * } catch (JuringException e) {
 *     if (e.isErrno(JuringException.ENOENT)) { ... }
 * }
 * }</pre>
 */
public final class JuringException extends IOException {

    /** {@code EAGAIN} — the operation would block and can be retried. */
    public static final int EAGAIN = 11;
    /** {@code ENOENT} — no such file or directory. */
    public static final int ENOENT = 2;
    /** {@code EBADF} — bad file descriptor. */
    public static final int EBADF = 9;
    /** {@code EINVAL} — invalid argument. */
    public static final int EINVAL = 22;
    /** {@code EIO} — I/O error. */
    public static final int EIO = 5;
    /** {@code ENOSPC} — no space left on device. */
    public static final int ENOSPC = 28;
    /** {@code ECANCELED} — operation cancelled. */
    public static final int ECANCELED = 125;
    /** {@code ETIMEDOUT} — operation timed out (the JUring/file-level timeout errno). */
    public static final int ETIMEDOUT = 110;
    /**
     * {@code ETIME} — the kernel's io_uring timeout completion errno. {@code JuringFile}
     * translates it to {@link #ETIMEDOUT} for its high-level timeout helpers; raw
     * {@code JUring} completions report {@code -ETIME}.
     */
    public static final int ETIME = 62;
    /** {@code EALREADY} — a cancellation target was already completing/completed. */
    public static final int EALREADY = 114;

    /**
     * Positive Linux errno carried by this failure, or {@code 0} for a synthetic failure.
     *
     * @serial
     */
    private final int errno;
    /**
     * Name of the operation that failed (for example {@code "read"}).
     *
     * @serial
     */
    private final String operation;
    /**
     * Human-readable context supplied at construction; may be {@code null}.
     *
     * @serial
     */
    private final String detail;

    /**
     * Creates a typed JUring failure.
     *
     * @param errno     positive Linux errno, or {@code 0} for a synthetic (non-kernel) failure
     * @param operation the operation that failed, e.g. {@code "read"} or {@code "open"}
     * @param detail    human-readable context (path, offset, handle, ...), may be {@code null}
     */
    public JuringException(int errno, String operation, String detail) {
        this(errno, operation, detail, null);
    }

    /**
     * Creates a typed JUring failure with a cause.
     *
     * @param errno     positive Linux errno, or {@code 0} for a synthetic (non-kernel) failure
     * @param operation the operation that failed
     * @param detail    human-readable context, may be {@code null}
     * @param cause     underlying cause, may be {@code null}
     */
    public JuringException(int errno, String operation, String detail, Throwable cause) {
        super(formatMessage(errno, operation, detail), cause);
        this.errno = Math.max(errno, 0);
        this.operation = operation != null ? operation : "operation";
        this.detail = detail;
    }

    /**
     * Creates a typed failure from a negative io_uring completion result.
     *
     * @param negativeResult the negative {@code cqe.res} value ({@code -errno})
     * @param operation      the operation that failed
     * @param detail         human-readable context, may be {@code null}
     * @return a checked {@code JuringException} carrying the positive errno
     */
    public static JuringException fromResult(int negativeResult, String operation, String detail) {
        return new JuringException(negativeResult < 0 ? -negativeResult : negativeResult, operation, detail);
    }

    /**
     * Returns the positive Linux errno carried by this failure.
     *
     * @return the positive errno, or {@code 0} for a synthetic failure
     */
    public int errno() {
        return errno;
    }

    /**
     * Returns the name of the operation that failed.
     *
     * @return the operation name (for example {@code "read"})
     */
    public String operation() {
        return operation;
    }

    /**
     * Returns the human-readable context supplied at construction.
     *
     * @return the context string, may be {@code null}
     */
    public String detail() {
        return detail;
    }

    /**
     * Returns the libc error text for the carried errno.
     *
     * @return the {@code strerror} text, or {@code "no errno"} for synthetic failures
     */
    public String strerror() {
        if (errno == 0) {
            return "no errno";
        }
        return NativeDispatcher.C.strerror(errno);
    }

    /**
     * Tests whether this failure carries the given errno.
     *
     * @param errno positive Linux errno
     * @return {@code true} when {@link #errno()} equals {@code errno}
     */
    public boolean isErrno(int errno) {
        return this.errno == errno && this.errno != 0;
    }

    /**
     * Tests whether this failure is {@code ENOENT}.
     *
     * @return {@code true} for {@code ENOENT}
     */
    public boolean isNotFound() {
        return isErrno(ENOENT);
    }

    /**
     * Tests whether this failure is {@code EBADF}.
     *
     * @return {@code true} for {@code EBADF}
     */
    public boolean isBadFileDescriptor() {
        return isErrno(EBADF);
    }

    /**
     * Tests whether this failure is {@code EINVAL}.
     *
     * @return {@code true} for {@code EINVAL}
     */
    public boolean isInvalidArgument() {
        return isErrno(EINVAL);
    }

    /**
     * Tests whether this failure is {@code EAGAIN}.
     *
     * @return {@code true} for {@code EAGAIN}
     */
    public boolean isAgain() {
        return isErrno(EAGAIN);
    }

    /**
     * Tests whether this failure is {@code ETIMEDOUT}.
     *
     * @return {@code true} for {@code ETIMEDOUT}
     */
    public boolean isTimedOut() {
        return isErrno(ETIMEDOUT);
    }

    /**
     * Tests whether this failure is {@code ECANCELED}, that is the request was canceled.
     *
     * @return {@code true} for {@code ECANCELED}
     */
    public boolean isCancelled() {
        return isErrno(ECANCELED);
    }

    private static String formatMessage(int errno, String operation, String detail) {
        String op = operation != null ? operation : "operation";
        StringBuilder sb = new StringBuilder(64);
        sb.append("JUring ").append(op).append(" failed");
        if (errno > 0) {
            sb.append(": ").append(NativeDispatcher.C.strerror(errno)).append(" (errno ").append(errno).append(')');
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(" [").append(detail).append(']');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "JuringException{errno=" + errno + ", operation=" + operation + ", detail=" + detail + "}";
    }
}
