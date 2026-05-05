package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public record ReadResultFixed(long id, MemorySegment buffer, long result, int bufferIdx) implements Result {

}
