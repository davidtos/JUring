package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code io_uring_setup(2)}.
 */
public interface IoUringSetup {
    long ioUringSetup(MemorySegment callState, int entries, MemorySegment params);
}
