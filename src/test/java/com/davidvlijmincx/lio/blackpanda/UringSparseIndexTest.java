package com.davidvlijmincx.lio.blackpanda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UringSparseIndexTest {

    @Test
    void testSparseIndexLookupAndPersistence(@TempDir Path tempDir) {
        long baseOffset = 1000L;
        int intervalBytes = 4096;

        try (UringSparseIndex index = new UringSparseIndex(tempDir, baseOffset, intervalBytes)) {
            // Append 10 entries every 4096 bytes
            for (int i = 0; i < 10; i++) {
                long offset = baseOffset + (i * 10L);
                long timestamp = 1700000000000L + (i * 1000L);
                long pos = (long) i * 4096L;
                index.maybeAppend(offset, timestamp, pos);
            }

            assertEquals(10, index.getOffsetEntriesCount());
            assertEquals(10, index.getTimeEntriesCount());

            // Exact lookup
            assertEquals(0L, index.lookup(baseOffset));
            assertEquals(4096L, index.lookup(baseOffset + 10L));
            assertEquals(8192L, index.lookup(baseOffset + 20L));

            // Between-entry lookup (should return floor entry position)
            assertEquals(4096L, index.lookup(baseOffset + 15L));
            assertEquals(8192L, index.lookup(baseOffset + 29L));
            assertEquals(36864L, index.lookup(baseOffset + 100L));

            // Lookup by timestamp
            assertEquals(baseOffset, index.lookupByTimestamp(1700000000000L));
            assertEquals(baseOffset + 10L, index.lookupByTimestamp(1700000001000L));
            assertEquals(baseOffset + 10L, index.lookupByTimestamp(1700000001500L)); // floor
        }

        // Reopen from disk and verify persistence
        try (UringSparseIndex index = new UringSparseIndex(tempDir, baseOffset, intervalBytes)) {
            assertEquals(10, index.getOffsetEntriesCount());
            assertEquals(10, index.getTimeEntriesCount());

            assertEquals(4096L, index.lookup(baseOffset + 10L));
            assertEquals(8192L, index.lookup(baseOffset + 20L));
            assertEquals(baseOffset + 20L, index.lookupByTimestamp(1700000002000L));
        }
    }
}
