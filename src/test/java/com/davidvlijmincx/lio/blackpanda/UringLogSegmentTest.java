package com.davidvlijmincx.lio.blackpanda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UringLogSegmentTest {

    @Test
    void testSegmentCreationAndRolloverCondition(@TempDir Path tempDir) {
        long baseOffset = 0L;
        long maxSize = 8192L; // 8KB segment
        int indexInterval = 4096;

        try (UringLogSegment segment = new UringLogSegment(tempDir, baseOffset, maxSize, indexInterval)) {
            assertEquals(baseOffset, segment.getBaseOffset());
            assertEquals(0L, segment.getPhysicalSize());
            assertFalse(segment.isSealed());

            assertFalse(segment.shouldRoll(4096));
            segment.updateFlushedPosition(4096);
            assertEquals(4096, segment.getPhysicalSize());

            assertTrue(segment.shouldRoll(4097)); // 4096 + 4097 > 8192

            segment.appendIndexEntry(0L, 1000L, 0L);
            segment.appendIndexEntry(1L, 1001L, 4096L);

            segment.seal();
            assertTrue(segment.isSealed());
        }

        assertTrue(Files.exists(tempDir.resolve(String.format("%020d.log", baseOffset))));
        assertTrue(Files.exists(tempDir.resolve(String.format("%020d.index", baseOffset))));
        assertTrue(Files.exists(tempDir.resolve(String.format("%020d.timeindex", baseOffset))));
    }

    @Test
    void testPartitionManagerSegmentDiscoveryAndRollover(@TempDir Path tempDir) {
        long maxSize = 8192L;
        int indexInterval = 4096;

        try (UringPartitionManager manager = new UringPartitionManager(tempDir, maxSize, indexInterval)) {
            UringLogSegment seg1 = manager.getActiveSegment();
            assertEquals(0L, seg1.getBaseOffset());

            // Roll to offset 100
            UringLogSegment seg2 = manager.roll(100L);
            assertEquals(100L, seg2.getBaseOffset());
            assertTrue(seg1.isSealed());

            // Find segment for offset
            assertEquals(seg1, manager.findSegmentForOffset(50L));
            assertEquals(seg2, manager.findSegmentForOffset(100L));
            assertEquals(seg2, manager.findSegmentForOffset(150L));

            assertEquals(2, manager.getAllSegments().size());
        }

        // Reopen manager and verify discovery of existing segments
        try (UringPartitionManager manager = new UringPartitionManager(tempDir, maxSize, indexInterval)) {
            assertEquals(2, manager.getAllSegments().size());
            assertEquals(100L, manager.getActiveSegment().getBaseOffset());
        }
    }
}
