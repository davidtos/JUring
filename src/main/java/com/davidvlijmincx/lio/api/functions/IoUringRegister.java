package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * io_uring_register(2): {@code int io_uring_register(unsigned int fd, unsigned int opcode,
 * void *arg, unsigned int nr_args)}.
 * <p>
 * Invoked through libc's {@code syscall(2)}. The first parameter is the captured call state
 * (errno), because {@code syscall} returns -1 and stores the error in errno.
 */
public interface IoUringRegister {
    long ioUringRegister(MemorySegment callState, int fd, int opcode, MemorySegment arg, int nrArgs);
}
