package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code io_uring_register(2)}.
 */
public interface IoUringRegister {
    long ioUringRegister(MemorySegment callState, long fd, long opcode, MemorySegment arg, long nrArgs);
}
