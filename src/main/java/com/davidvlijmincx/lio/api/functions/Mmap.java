package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * Downcall interface for {@code mmap(2)}.
 */
public interface Mmap {
    long mmap(MemorySegment callState, long address, long length, int prot, int flags, int fd, long offset);
}
