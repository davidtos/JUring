package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

/**
 * {@code void *mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset)}.
 * <p>
 * The first parameter is the captured call state (errno), mmap returns MAP_FAILED ((void *) -1)
 * and stores the error in errno.
 */
public interface Mmap {
    long mmap(MemorySegment callState, long address, long length, int prot, int flags, int fd, long offset);
}
