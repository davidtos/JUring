package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * io_uring_enter(2): {@code int io_uring_enter(unsigned int fd, unsigned int to_submit,
 * unsigned int min_complete, unsigned int flags, const void *arg, size_t argsz)}.
 * <p>
 * Invoked through libc's {@code syscall(2)}. The first parameter is the captured call state
 * (errno), because {@code syscall} returns -1 and stores the error in errno.
 */
public interface IoUringEnter {
    long ioUringEnter(MemorySegment callState, int fd, int toSubmit, int minComplete, int flags, MemorySegment arg, long argSize);
}
