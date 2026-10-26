package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code io_uring_enter(2)}.
 */
public interface IoUringEnter {
    long ioUringEnter(MemorySegment callState, int fd, int toSubmit, int minComplete, int flags, MemorySegment arg, long argSize);
}
