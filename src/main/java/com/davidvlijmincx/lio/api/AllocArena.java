package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.davidvlijmincx.lio.api.LibCWrapper.*;

class AllocArena implements Arena {

    final Arena arena = Arena.ofConfined();

    @Override
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        if (byteSize >= 4000) {
           return nativeCalloc(byteSize).reinterpret(byteSize, arena, LibCWrapper::freeBuffer);
        } else {
            return nativeMalloc(byteSize).reinterpret(byteSize, arena, LibCWrapper::freeBuffer);
        }
    }

    @Override
    public MemorySegment.Scope scope() {
        return arena.scope();
    }

    @Override
    public void close() {
        arena.close();
    }
}
