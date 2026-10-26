package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code io_uring_register(2)}.
 */
public interface IoUringRegister {
    long ioUringRegister(MemorySegment callState, int fd, int opcode, MemorySegment arg, int nrArgs);
}
