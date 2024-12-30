package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public interface ReadResult {


    long getId();
    MemorySegment getBuffer();
    long getResult();

}
