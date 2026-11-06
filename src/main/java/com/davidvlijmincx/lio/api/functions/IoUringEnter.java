package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code io_uring_enter(2)}.
 */
public interface IoUringEnter {
    long ioUringEnter(MemorySegment callState, long fd, long toSubmit, long minComplete, long flags, MemorySegment arg, long argSize);
}
