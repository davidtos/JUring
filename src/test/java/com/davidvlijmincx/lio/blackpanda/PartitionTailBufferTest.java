package com.davidvlijmincx.lio.blackpanda;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartitionTailBufferTest {

    @Test
    void testTailBufferPublishAndRead() {
        int capacity = 16;
        try (PartitionTailBuffer buffer = new PartitionTailBuffer(capacity)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = arena.allocate(1024);
                MemorySegment dst = arena.allocate(1024);

                // Publish 10 batches
                for (int i = 0; i < 10; i++) {
                    src.set(ValueLayout.JAVA_INT, 0, 100 + i);
                    buffer.publish((long) i, 1, 1000L + i, src, 0L, 1024);
                }

                assertEquals(0L, buffer.getMinAvailableOffset());
                assertEquals(9L, buffer.getMaxAvailableOffset());

                // Read batch 5
                int bytesRead = buffer.read(5L, dst);
                assertEquals(1024, bytesRead);
                assertEquals(105, dst.get(ValueLayout.JAVA_INT, 0));

                // Read beyond max (tip) -> should return 0 (NO_DATA)
                assertEquals(PartitionTailBuffer.RESULT_NO_DATA, buffer.read(10L, dst));
            }
        }
    }

    @Test
    void testTailBufferEvictionAndLagging() {
        int capacity = 8;
        try (PartitionTailBuffer buffer = new PartitionTailBuffer(capacity)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = arena.allocate(128);
                MemorySegment dst = arena.allocate(128);

                // Publish 20 batches into capacity of 8
                for (int i = 0; i < 20; i++) {
                    src.set(ValueLayout.JAVA_INT, 0, i);
                    buffer.publish((long) i, 1, 1000L + i, src, 0L, 128);
                }

                // Min available offset should have advanced
                long min = buffer.getMinAvailableOffset();
                assertEquals(12L, min);
                assertEquals(19L, buffer.getMaxAvailableOffset());

                // Reading an evicted offset should return RESULT_LAGGING (-1)
                assertEquals(PartitionTailBuffer.RESULT_LAGGING, buffer.read(5L, dst));
                assertEquals(PartitionTailBuffer.RESULT_LAGGING, buffer.read(11L, dst));

                // Reading an available offset should succeed
                int readBytes = buffer.read(15L, dst);
                assertEquals(128, readBytes);
                assertEquals(15, dst.get(ValueLayout.JAVA_INT, 0));
            }
        }
    }
}
