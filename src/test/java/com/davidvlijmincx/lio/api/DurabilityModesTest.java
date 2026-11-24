package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DIRECT;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DSYNC;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.SYNC;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Conformance tests for the durability-mode report and sync observability: the mode
 * derived from the open flags,
 * {@code O_DSYNC}/{@code O_SYNC} data visibility after reopen, the unchanged explicit
 * {@code fsync}/{@code fdatasync} behaviour, the additive
 * {@link JuringFile#syncOperations()}/{@link JuringFile#syncCompletions()} classification
 * and {@link JuringFile#inFlightCapacity()}.
 *
 * <p>Stream/group-commit durability points are covered by {@code StreamDurabilityTest}
 * and {@code GroupCommitTest}; this class exercises the {@link JuringFile} layer itself.
 */
class DurabilityModesTest {

    private static final int BLOCK = 4096;

    private JUring ring;

    @BeforeEach
    void setUp() {
        ring = new JUring(64, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close();
            ring = null;
        }
    }

    // ------------------------------------------------------------------
    // durability() derivation from the open flags
    // ------------------------------------------------------------------

    @Test
    void normalOpensReportWriteback(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("plain.bin"), BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            assertEquals(DurabilityMode.WRITEBACK, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            assertEquals(DurabilityMode.WRITEBACK, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, path, READ, DIRECT)) {
            assertEquals(DurabilityMode.WRITEBACK, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, path, RDWR, DIRECT)) {
            assertEquals(DurabilityMode.WRITEBACK, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, dir.resolve("created.bin"),
                0644, WRITE, CREATE, TRUNCATE)) {
            assertEquals(DurabilityMode.WRITEBACK, file.durability());
        }
    }

    @Test
    void dsyncAndSyncFlagsReportDsync(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("sync-flags.bin"), BLOCK);

        // Alone: O_DSYNC / O_SYNC on a read-only descriptor.
        try (JuringFile file = JuringFile.open(ring, path, DSYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, path, SYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
        }
        // Combined with READ / WRITE / RDWR / DIRECT.
        try (JuringFile file = JuringFile.open(ring, path, READ, DSYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, path, WRITE, DSYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, path, RDWR, SYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, path, READ, DIRECT, DSYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, path, RDWR, DIRECT, SYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
        }
        try (JuringFile file = JuringFile.open(ring, dir.resolve("dsync-create.bin"),
                0644, WRITE, CREATE, TRUNCATE, DSYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
        }
    }

    @Test
    void durabilityIsReadableAfterCloseAndReportsTheOpenTimeValue(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("after-close.bin"), BLOCK);

        JuringFile writeback = JuringFile.open(ring, path, READ);
        assertEquals(DurabilityMode.WRITEBACK, writeback.durability());
        writeback.close();
        // Documented implementation choice: the frozen open-time report stays readable.
        assertEquals(DurabilityMode.WRITEBACK, writeback.durability());

        JuringFile dsync = JuringFile.open(ring, path, READ, DSYNC);
        assertEquals(DurabilityMode.DSYNC, dsync.durability());
        dsync.close();
        assertEquals(DurabilityMode.DSYNC, dsync.durability());
    }

    // ------------------------------------------------------------------
    // O_DSYNC data visibility
    // ------------------------------------------------------------------

    @Test
    void dsyncOpenWritesAreVisibleAfterCloseAndReopen(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("dsync-data.bin");
        byte[] data = pattern(2 * BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, 0644, WRITE, CREATE, TRUNCATE, DSYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(data.length);
                MemorySegment.copy(MemorySegment.ofArray(data), 0, buffer, 0, data.length);
                assertEquals(data.length, file.write(0, buffer));
            }
        }

        assertEquals(data.length, Files.size(path));
        assertArrayEquals(data, Files.readAllBytes(path));

        // Reopen without the durability flag: the bytes are readable normally.
        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            assertEquals(DurabilityMode.WRITEBACK, file.durability());
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(data.length);
                assertEquals(data.length, file.read(0, buffer));
                byte[] actual = new byte[data.length];
                MemorySegment.copy(buffer, 0, MemorySegment.ofArray(actual), 0, data.length);
                assertArrayEquals(data, actual);
            }
        }
    }

    // ------------------------------------------------------------------
    // explicit syncs unchanged + additive counters
    // ------------------------------------------------------------------

    @Test
    void explicitFsyncAndFdatasyncStillWorkAndAreCounted(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("explicit-sync.bin"), BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            assertEquals(0, file.syncOperations());
            assertEquals(0, file.syncCompletions());

            long issuedBeforeWrite = file.issuedOperations();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                MemorySegment.copy(MemorySegment.ofArray(pattern(BLOCK)), 0, buffer, 0, BLOCK);
                assertEquals(BLOCK, file.write(0, buffer));
            }
            // A write is not an FSYNC request: the sync counters are untouched.
            assertEquals(issuedBeforeWrite + 1, file.issuedOperations());
            assertEquals(0, file.syncOperations());
            assertEquals(0, file.syncCompletions());

            long issuedBeforeFdatasync = file.issuedOperations();
            file.fdatasync();
            assertEquals(issuedBeforeFdatasync + 1, file.issuedOperations());
            assertEquals(1, file.syncOperations());
            assertEquals(1, file.syncCompletions());

            long issuedBeforeFsync = file.issuedOperations();
            file.fsync();
            assertEquals(issuedBeforeFsync + 1, file.issuedOperations());
            assertEquals(2, file.syncOperations());
            assertEquals(2, file.syncCompletions());

            // Everything issued has been reaped by the blocking helpers.
            assertEquals(file.syncOperations(), file.syncCompletions());
            assertEquals(0, file.inFlightOperations());
        }

        assertArrayEquals(pattern(BLOCK), Files.readAllBytes(path));
    }

    @Test
    void preparedFsyncCountsOnlyWhenReapedThroughTheHandle(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("prepared-sync.bin"), BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            long handle = file.prepareFsync(true);
            assertEquals(1, file.syncOperations());
            assertEquals(0, file.syncCompletions());
            assertEquals(0, file.await(handle));
            assertEquals(1, file.syncCompletions());
        }
    }

    @Test
    void fsyncAndFdatasyncRemainLegalOnADsyncFile(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("dsync-redundant-sync.bin"), BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, RDWR, DSYNC)) {
            assertEquals(DurabilityMode.DSYNC, file.durability());
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                MemorySegment.copy(MemorySegment.ofArray(pattern(BLOCK)), 0, buffer, 0, BLOCK);
                assertEquals(BLOCK, file.write(0, buffer));
            }
            // O_DSYNC already makes completion durable; an explicit sync is redundant
            // but must remain legal and be tracked.
            file.fdatasync();
            file.fsync();
            assertEquals(2, file.syncOperations());
            assertEquals(2, file.syncCompletions());
        }
    }

    // ------------------------------------------------------------------
    // inFlightCapacity()
    // ------------------------------------------------------------------

    @Test
    void inFlightCapacityReportsTheConfiguredValue(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("capacity.bin"), BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            assertEquals(JuringFile.DEFAULT_IN_FLIGHT_CAPACITY, file.inFlightCapacity());
            assertEquals(1024, file.inFlightCapacity());
            assertThrows(IllegalArgumentException.class, () -> file.withInFlightCapacity(0));
            assertEquals(JuringFile.DEFAULT_IN_FLIGHT_CAPACITY, file.inFlightCapacity());
        }

        try (JuringFile file = JuringFile.open(ring, path, READ).withInFlightCapacity(7)) {
            assertEquals(7, file.inFlightCapacity());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Path createFile(Path path, int length) throws Exception {
        Files.write(path, pattern(length));
        return path;
    }

    private static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ((i * 17 + 3) & 0xFF);
        }
        return data;
    }
}
