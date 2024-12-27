package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

record Cqe(long UserData, long result, MemorySegment cqePointer) {
}
