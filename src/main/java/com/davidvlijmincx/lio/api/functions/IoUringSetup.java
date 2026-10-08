package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * io_uring_setup(2): {@code int io_uring_setup(u32 entries, struct io_uring_params *p)}.
 * <p>
 * Invoked through libc's {@code syscall(2)}. The first parameter is the captured call state
 * (errno), because {@code syscall} returns -1 and stores the error in errno.
 */
public interface IoUringSetup {
    long ioUringSetup(MemorySegment callState, int entries, MemorySegment params);
}
