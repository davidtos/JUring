package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Random;
import java.util.Set;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DIRECT;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DSYNC;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance tests for {@link JuringFile} against docs/b1-design.md §4/§5.
 *
 * <p>Covers open flags and typed open errors, sequential and random-offset round
 * trips (plain-fd and registered-file + fixed-buffer, plus O_DIRECT), EOF and short
 * IO, metadata operations, the bounded close-with-in-flight drain, the registration
 * lifecycle and typed error mapping. Every test uses an isolated ring created in
 * {@link #setUp()} and closed in {@link #tearDown()}.
 */
class JuringFileConformanceTest {

    private static final int BLOCK = 4096;
    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;

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
    // open / flags / typed open errors
    // ------------------------------------------------------------------

    @Test
    void readOnlyFlagReadsButRejectsWrites(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("read-only.bin"), BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            assertTrue(file.isOpen());
            assertFalse(file.isRegistered());
            assertEquals(BLOCK, file.length());
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                assertEquals(BLOCK, file.read(0, buffer));
                assertContent(buffer, 0, BLOCK);

                long handle = file.prepareWrite(0, buffer);
                JuringException e = assertThrows(JuringException.class, () -> file.await(handle));
                assertEquals(JuringException.EBADF, e.errno());
                assertTrue(e.isBadFileDescriptor());
            }
        }
    }

    @Test
    void createAndTruncateFlagsProduceEmptyReadWriteFile(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("read-write.bin");

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            assertTrue(Files.exists(path));
            assertTrue(file.isOpen());
            assertEquals(0, file.length());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                fillPattern(buffer, 0, BLOCK);
                assertEquals(BLOCK, file.write(0, buffer));
                assertEquals(BLOCK, file.length());

                fillZero(buffer, BLOCK);
                assertEquals(BLOCK, file.read(0, buffer));
                assertContent(buffer, 0, BLOCK);
            }
        }

        // TRUNCATE on an existing non-empty file drops its previous content.
        Path existing = createPatternFile(dir.resolve("truncated.bin"), 4 * BLOCK);
        try (JuringFile file = JuringFile.open(ring, existing, RDWR, CREATE, TRUNCATE)) {
            assertEquals(0, file.length());
        }
    }

    @Test
    void dsyncFlagWriteAndFlushSucceed(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("dsync.bin");

        try (JuringFile file = JuringFile.open(ring, path, 0644, WRITE, CREATE, TRUNCATE, DSYNC)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                fillPattern(buffer, 0, BLOCK);
                assertEquals(BLOCK, file.write(0, buffer));
            }
            assertEquals(BLOCK, file.length());
            file.fdatasync();
        }
    }

    @Test
    void defaultAndExplicitCreationModes(@TempDir Path dir) throws Exception {
        Path defaultMode = dir.resolve("default-mode.bin");
        try (JuringFile file = JuringFile.open(ring, defaultMode, CREATE, WRITE)) {
            assertTrue(file.isOpen());
        }
        int mode = posixMode(defaultMode);
        assertEquals(0600, mode & 0600, "default 0644 mode must grant owner read+write");
        assertEquals(0, mode & 0111, "default creation mode must not set execute bits");

        Path explicitMode = dir.resolve("explicit-mode.bin");
        try (JuringFile file = JuringFile.open(ring, explicitMode, 0640, CREATE, WRITE)) {
            assertTrue(file.isOpen());
        }
        assertEquals(0640, posixMode(explicitMode) & 0777, "explicit creation mode must be honoured");
    }

    @Test
    void openMissingPathThrowsTypedEnoent(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.bin");

        JuringException e = assertThrows(JuringException.class, () -> JuringFile.open(ring, missing, READ));
        assertEquals(2, e.errno());
        assertEquals(JuringException.ENOENT, e.errno());
        assertTrue(e.isNotFound());
        assertTrue(e.isErrno(JuringException.ENOENT));
        assertEquals("open", e.operation());
        assertTrue(e.getMessage().contains("errno 2"), "message should carry the errno: " + e.getMessage());
        assertFalse(e.strerror().isEmpty());
    }

    @Test
    void useAfterCloseThrowsIllegalState(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("closed.bin"), BLOCK);
        JuringFile file = JuringFile.open(ring, path, RDWR);
        file.close();

        assertFalse(file.isOpen());
        assertFalse(file.isRegistered());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(BLOCK);
            assertThrows(IllegalStateException.class, () -> file.read(0, buffer));
            assertThrows(IllegalStateException.class, () -> file.write(0, buffer));
            assertThrows(IllegalStateException.class, () -> file.prepareRead(0, BLOCK, buffer));
            assertThrows(IllegalStateException.class, () -> file.prepareReadFixed(0, BLOCK, 0));
            assertThrows(IllegalStateException.class, () -> file.length());
            assertThrows(IllegalStateException.class, () -> file.truncate(0));
            assertThrows(IllegalStateException.class, () -> file.await(1L));
            assertThrows(IllegalStateException.class, file::register);
        }
        // close() is idempotent
        file.close();
    }

    // ------------------------------------------------------------------
    // sequential / random round trips
    // ------------------------------------------------------------------

    @Test
    void sequentialWriteThenReadRoundTrip(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("sequential.bin");

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            writePattern(file, MIB);
            assertEquals(MIB, file.length());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                for (long offset = 0; offset < MIB; offset += BLOCK) {
                    assertEquals(BLOCK, file.read(offset, buffer));
                    assertContent(buffer, offset, BLOCK);
                }
            }
        }

        assertEquals(MIB, Files.size(path));
    }

    @Test
    void randomOffsetRoundTripPlainFd(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("random-plain.bin"), MIB);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            assertFalse(file.isRegistered(), "this test exercises the plain-fd path");
            assertEquals(MIB, file.length());

            Random random = new Random(42);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                for (int i = 0; i < 64; i++) {
                    long offset = (long) random.nextInt(MIB / BLOCK) * BLOCK;
                    long handle = file.prepareRead(offset, BLOCK, buffer);
                    assertEquals(BLOCK, file.await(handle));
                    assertContent(buffer, offset, BLOCK);
                }
            }
        }
    }

    @Test
    void registeredFileFixedBufferRoundTrip(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("fixed-buffer.bin"), MIB);
        ring.registerBuffers(BLOCK, 16);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            int slot = file.register();
            assertTrue(slot >= 0, "register() must return a fixed-file slot");
            assertTrue(file.isRegistered());
            assertEquals(slot, file.registeredSlot());
            assertEquals(slot, file.register(), "register() must be idempotent");
            // statx works through the registered fixed-file slot
            assertEquals(MIB, file.length());

            try (Arena arena = Arena.ofConfined()) {
                long offset = 512 * KIB;
                MemorySegment writeBuffer = ring.getRegisteredBuffer(0);
                fillPattern(writeBuffer, offset, BLOCK);
                assertEquals(BLOCK, file.await(file.prepareWriteFixed(offset, BLOCK, 0)));

                MemorySegment readBuffer = ring.getRegisteredBuffer(1);
                fillZero(readBuffer, BLOCK);
                assertEquals(BLOCK, file.await(file.prepareReadFixed(offset, BLOCK, 1)));
                assertContent(readBuffer, offset, BLOCK);

                // plain-buffer read on a registered file (IOSQE_FIXED_FILE READ path)
                fillZero(readBuffer, BLOCK);
                assertEquals(BLOCK, file.await(file.prepareRead(offset, BLOCK, readBuffer)));
                assertContent(readBuffer, offset, BLOCK);
            }
        }
    }

    @Test
    void directIoAlignedRoundTrip(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("direct.bin");
        ring.registerBuffers(BLOCK, 4);

        try (JuringFile file = JuringFile.open(ring, path, 0644, RDWR, DIRECT, CREATE, TRUNCATE)) {
            assertTrue(file.register() >= 0);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment writeBuffer = ring.getRegisteredBuffer(0);
                fillPattern(writeBuffer, 0, BLOCK);
                assertEquals(BLOCK, file.await(file.prepareWriteFixed(0, BLOCK, 0)));

                MemorySegment readBuffer = ring.getRegisteredBuffer(1);
                fillZero(readBuffer, BLOCK);
                assertEquals(BLOCK, file.await(file.prepareReadFixed(0, BLOCK, 1)));
                assertContent(readBuffer, 0, BLOCK);
            }
            assertEquals(BLOCK, file.length());
        }

        assertEquals(BLOCK, Files.size(path));
    }

    @Test
    void writeExtendsFileAndReturnsBufferSize(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("extend.bin");

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                fillPattern(buffer, 2 * BLOCK, BLOCK);
                assertEquals(BLOCK, file.write(2 * BLOCK, buffer));
            }
            assertEquals(3 * BLOCK, file.length());
        }

        assertEquals(3 * BLOCK, Files.size(path));
    }

    // ------------------------------------------------------------------
    // EOF / short IO
    // ------------------------------------------------------------------

    @Test
    void readPastEofReturnsZero(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("eof.bin"), 4100);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                assertEquals(0, file.read(4100, buffer));
                assertEquals(0, file.read(100_000, buffer));

                long handle = file.prepareRead(8192, BLOCK, buffer);
                assertEquals(0, file.await(handle));
            }
        }
    }

    @Test
    void shortReadAtEofReturnsPartialCount(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("short.bin"), BLOCK + 100);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(2 * BLOCK);
                // 4 KiB + 100 bytes into an 8 KiB buffer: short read at EOF
                assertEquals(BLOCK + 100, file.read(0, buffer));
                assertContent(buffer, 0, BLOCK + 100);
                // short read when only the trailing 100 bytes remain before EOF
                assertEquals(100, file.read(BLOCK, buffer));
                assertEquals(0, file.read(BLOCK + 100, buffer));
            }
        }

        // literal 4100-byte file from the test contract
        Path file4100 = createPatternFile(dir.resolve("short-4100.bin"), 4100);
        try (JuringFile file = JuringFile.open(ring, file4100, READ)) {
            try (Arena arena = Arena.ofConfined()) {
                assertEquals(4100, file.read(0, arena.allocate(2 * BLOCK)));
            }
        }
    }

    @Test
    void eofWithShortReadDisallowedThrowsSyntheticError(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("policy.bin"), BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            file.withPolicy(IoPolicy.defaults().withShortReadAtEofAllowed(false));
            try (Arena arena = Arena.ofConfined()) {
                JuringException e = assertThrows(JuringException.class,
                        () -> file.read(0, arena.allocate(2 * BLOCK)));
                assertEquals(0, e.errno(), "synthetic EOF failures carry errno 0");
                assertFalse(e.isErrno(JuringException.EINVAL));
                assertTrue(e.getMessage().contains("EOF"), "message should mention EOF: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // metadata
    // ------------------------------------------------------------------

    @Test
    void metadataLengthTruncateAndSync(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("metadata.bin"), 64 * KIB);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            assertEquals(64 * KIB, file.length());

            file.truncate(32 * KIB);
            assertEquals(32 * KIB, file.length());
            assertEquals(32 * KIB, Files.size(path));

            // surviving prefix keeps its data
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                assertEquals(BLOCK, file.read(0, buffer));
                assertContent(buffer, 0, BLOCK);
            }

            file.fsync();
            file.fdatasync();
            assertEquals(32 * KIB, file.length());
        }
    }

    @Test
    void metadataAllocateExtendsFile(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("allocate.bin");

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            assertEquals(0, file.length());
            try {
                file.allocate(0, BLOCK);
                assertEquals(BLOCK, file.length());
                file.allocate(BLOCK, BLOCK);
                assertEquals(2 * BLOCK, file.length());
            } catch (UnsupportedOperationException e) {
                Assumptions.assumeTrue(false,
                        "kernel does not support IORING_OP_FALLOCATE: " + e.getMessage());
            } catch (JuringException e) {
                Assumptions.assumeTrue(e.errno() == 95 || e.errno() == 38,
                        "filesystem does not support fallocate mode 0: " + e.getMessage());
            }
        }

        assertEquals(2 * BLOCK, Files.size(path));
    }

    @Test
    void metadataDiscardPunchesHoleAndKeepsLength(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("discard.bin"), 64 * KIB);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            try {
                file.discard(0, BLOCK);
            } catch (UnsupportedOperationException e) {
                Assumptions.assumeTrue(false,
                        "kernel does not support IORING_OP_FALLOCATE: " + e.getMessage());
            } catch (JuringException e) {
                Assumptions.assumeTrue(e.errno() == 95 || e.errno() == 38 || e.errno() == 22,
                        "filesystem rejects page-aligned PUNCH_HOLE: " + e.getMessage());
            }

            assertEquals(64 * KIB, file.length(), "PUNCH_HOLE|KEEP_SIZE must keep the file size");
            assertEquals(64 * KIB, Files.size(path));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                assertEquals(BLOCK, file.read(0, buffer));
                for (int i = 0; i < BLOCK; i++) {
                    assertEquals(0, buffer.get(JAVA_BYTE, i), "discarded range must read back as zeros (byte " + i + ")");
                }
                assertEquals(BLOCK, file.read(BLOCK, buffer));
                assertContent(buffer, BLOCK, BLOCK);
            }
        }
    }

    // ------------------------------------------------------------------
    // close with in-flight operations
    // ------------------------------------------------------------------

    @Test
    void closeDrainsInflightOperations(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("close-inflight.bin"), MIB);
        JUring dedicated = new JUring(128, IORING_SETUP_SINGLE_ISSUER);
        JuringFile file = null;

        try {
            dedicated.registerBuffers(BLOCK, 64);
            file = JuringFile.open(dedicated, path, READ);
            assertEquals(0, file.register());

            for (int i = 0; i < 64; i++) {
                file.prepareReadFixed((long) i * BLOCK, BLOCK, i);
            }
            assertEquals(64, file.issuedOperations());
            assertEquals(64, file.inFlightOperations());

            long start = System.nanoTime();
            file.close();
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(elapsedMillis < 1500,
                    "close() with 64 in-flight reads took " + elapsedMillis + " ms (expected well under 1500 ms)");
            assertEquals(64, file.issuedOperations(), "close() must not issue extra user-visible operations");
            assertEquals(64, file.completedOperations(), "every issued operation must be reaped by close()");
            assertEquals(0, file.inFlightOperations());
            assertEquals(0, file.abandonedOperations(), "a local registered ring must drain without abandoning operations");
            assertFalse(file.isOpen());
            assertEquals(-1, file.registeredSlot(), "close() must unregister the fixed-file slot");
            assertEquals(0, dedicated.inFlightCount(), "ring must have no in-flight work after close()");

            // close() is idempotent: a second call is a no-op
            file.close();
            assertEquals(64, file.issuedOperations());
            assertEquals(64, file.completedOperations());
            assertEquals(0, file.abandonedOperations());
        } finally {
            if (file != null) {
                file.close();
            }
            dedicated.close();
        }
    }

    // ------------------------------------------------------------------
    // registration lifecycle
    // ------------------------------------------------------------------

    @Test
    void registrationLifecycleSlotsAreReused(@TempDir Path dir) throws Exception {
        Path first = createPatternFile(dir.resolve("reg-first.bin"), BLOCK);
        Path second = createPatternFile(dir.resolve("reg-second.bin"), BLOCK);

        try (JuringFile file1 = JuringFile.open(ring, first, RDWR);
             JuringFile file2 = JuringFile.open(ring, second, RDWR)) {

            int slot1 = file1.register();
            assertTrue(slot1 >= 0);
            assertTrue(file1.isRegistered());
            assertEquals(slot1, file1.register(), "register() must be idempotent");

            file1.unregister();
            assertFalse(file1.isRegistered());
            assertEquals(-1, file1.registeredSlot());

            int slot2 = file2.register();
            assertEquals(slot1, slot2, "a slot freed by unregister() must be reused by the next register()");

            // file operations still work on the non-registered path
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                fillZero(buffer, BLOCK);
                assertEquals(BLOCK, file1.read(0, buffer));
                assertContent(buffer, 0, BLOCK);
                assertEquals(BLOCK, file1.await(file1.prepareRead(0, BLOCK, buffer)));
            }

            // and on the registered path
            assertEquals(BLOCK, file2.length());
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                assertEquals(BLOCK, file2.await(file2.prepareRead(0, BLOCK, buffer)));
                assertContent(buffer, 0, BLOCK);
            }

            file2.unregister();
            assertEquals(slot1, file2.register(), "the same slot must be reusable again after unregister()");
            file2.unregister();
        }
        // closing the ring afterwards must not leave anything registered
        assertTrue(ring.inFlightCount() == 0);
    }

    // ------------------------------------------------------------------
    // typed errors
    // ------------------------------------------------------------------

    @Test
    void readOnWriteOnlyFdThrowsTypedEbadf(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("write-only.bin");

        try (JuringFile file = JuringFile.open(ring, path, WRITE, CREATE, TRUNCATE)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                long handle = file.prepareRead(0, BLOCK, buffer);
                JuringException e = assertThrows(JuringException.class, () -> file.await(handle));
                assertEquals(JuringException.EBADF, e.errno());
                assertEquals(9, e.errno());
                assertTrue(e.isBadFileDescriptor());
            }
        }
    }

    @Test
    void truncateNegativeLengthThrowsTypedEinv(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("truncate-invalid.bin"), BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            JuringException e = assertThrows(JuringException.class, () -> file.truncate(-1));
            assertEquals(JuringException.EINVAL, e.errno());
            assertEquals(22, e.errno());
            assertTrue(e.isInvalidArgument());
        }
    }

    @Test
    void discardUnalignedRangeThrowsTypedEinv(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("discard-invalid.bin"), 64 * KIB);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            try {
                // 512 is not a filesystem block/page boundary
                file.discard(512, BLOCK);
            } catch (UnsupportedOperationException e) {
                Assumptions.assumeTrue(false,
                        "kernel does not support IORING_OP_FALLOCATE: " + e.getMessage());
            } catch (JuringException e) {
                assertEquals(JuringException.EINVAL, e.errno(),
                        "unaligned discard must surface EINVAL, got errno " + e.errno());
                return;
            }

            // This kernel/filesystem (tmpfs) rounds an unaligned PUNCH_HOLE up instead of
            // rejecting it. The contract says to skip in that case rather than assert EINVAL.
            Assumptions.assumeTrue(false,
                    "filesystem accepted an unaligned PUNCH_HOLE range; nothing to assert on this filesystem");
        }
    }

    // ------------------------------------------------------------------
    // await semantics
    // ------------------------------------------------------------------

    @Test
    void awaitWithTimeoutOnCompletedHandleReturnsImmediately(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("await-completed.bin"), 2 * BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                long handle = file.prepareRead(0, BLOCK, buffer);
                // drain() records the completion in the ledger without consuming the handle
                assertEquals(1, file.drain());
                assertEquals(0, file.inFlightOperations());

                long start = System.nanoTime();
                int result = file.await(handle, 1000);
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

                assertEquals(BLOCK, result);
                assertTrue(elapsedMillis < 500,
                        "await(handle, timeout) on an already-completed handle took " + elapsedMillis + " ms");
                assertContent(buffer, 0, BLOCK);
            }
        }
    }

    // ------------------------------------------------------------------
    // IoPolicy unit checks
    // ------------------------------------------------------------------

    @Test
    void ioPolicyDefaultsAndImmutability() {
        IoPolicy defaults = IoPolicy.defaults();
        assertSame(defaults, IoPolicy.defaults());
        assertEquals(8, defaults.eagainRetries());
        assertEquals(50_000L, defaults.eagainBackoffNanos());
        assertTrue(defaults.shortReadAtEofAllowed());

        IoPolicy fewerRetries = defaults.withEagainRetries(3);
        assertNotSame(defaults, fewerRetries);
        assertEquals(3, fewerRetries.eagainRetries());
        assertEquals(8, defaults.eagainRetries(), "with* must not mutate the original policy");
        assertEquals(50_000L, fewerRetries.eagainBackoffNanos());
        assertTrue(fewerRetries.shortReadAtEofAllowed());

        IoPolicy custom = defaults.withEagainBackoffNanos(7L).withShortReadAtEofAllowed(false);
        assertNotSame(defaults, custom);
        assertEquals(7L, custom.eagainBackoffNanos());
        assertFalse(custom.shortReadAtEofAllowed());
        assertEquals(8, custom.eagainRetries());
        assertNotEquals(defaults, custom);

        // equals/hashCode are value based and order independent
        assertEquals(defaults.withEagainRetries(0), defaults.withEagainRetries(0));
        assertNotEquals(defaults.withEagainRetries(0), defaults.withEagainRetries(1));
        assertEquals(defaults.withEagainRetries(3).withEagainBackoffNanos(7L),
                defaults.withEagainBackoffNanos(7L).withEagainRetries(3));
        assertEquals(defaults.withEagainRetries(3).withEagainBackoffNanos(7L).hashCode(),
                defaults.withEagainBackoffNanos(7L).withEagainRetries(3).hashCode());

        assertThrows(IllegalArgumentException.class, () -> defaults.withEagainRetries(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withEagainBackoffNanos(-1));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Deterministic per-file-offset content pattern. */
    private static byte pattern(long offset) {
        return (byte) ((offset * 31 + (offset >>> 12) + 7) & 0xFF);
    }

    private static byte[] expected(long offset, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = pattern(offset + i);
        }
        return bytes;
    }

    private static void fillPattern(MemorySegment buffer, long offset, int length) {
        for (int i = 0; i < length; i++) {
            buffer.set(JAVA_BYTE, i, pattern(offset + i));
        }
    }

    private static void fillZero(MemorySegment buffer, int length) {
        for (int i = 0; i < length; i++) {
            buffer.set(JAVA_BYTE, i, (byte) 0);
        }
    }

    private static void assertContent(MemorySegment buffer, long offset, int length) {
        byte[] actual = new byte[length];
        MemorySegment.copy(buffer, 0, MemorySegment.ofArray(actual), 0, length);
        assertArrayEquals(expected(offset, length), actual, "content mismatch at file offset " + offset);
    }

    private static Path createPatternFile(Path path, int size) throws IOException {
        Files.write(path, expected(0, size));
        return path;
    }

    private static void writePattern(JuringFile file, int size) throws JuringException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(BLOCK, BLOCK);
            for (long offset = 0; offset < size; offset += BLOCK) {
                int length = (int) Math.min(BLOCK, size - offset);
                fillPattern(buffer, offset, length);
                assertEquals(length, file.write(offset, buffer.asSlice(0, length)));
            }
        }
    }

    private static int posixMode(Path path) throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        int mode = 0;
        if (permissions.contains(PosixFilePermission.OWNER_READ)) mode |= 0400;
        if (permissions.contains(PosixFilePermission.OWNER_WRITE)) mode |= 0200;
        if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) mode |= 0100;
        if (permissions.contains(PosixFilePermission.GROUP_READ)) mode |= 0040;
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)) mode |= 0020;
        if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) mode |= 0010;
        if (permissions.contains(PosixFilePermission.OTHERS_READ)) mode |= 0004;
        if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) mode |= 0002;
        if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) mode |= 0001;
        return mode;
    }
}
