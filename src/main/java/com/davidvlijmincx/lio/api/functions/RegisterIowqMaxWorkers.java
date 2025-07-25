package com.davidvlijmincx.lio.api.functions;

import java.lang.foreign.MemorySegment;

public interface RegisterIowqMaxWorkers {
    int registerMaxIoWqWorkers(MemorySegment ring, MemorySegment values);
}