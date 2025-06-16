package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface Open {

    int open(MemorySegment path, int flags, int mode);
}
