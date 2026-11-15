package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DIRECT;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE_DIRECT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Conformance tests for the B6 {@code posix_fadvise} support
 * ({@code docs/b6-design.md} §2.1, §2.3 and §7.1): enum constants and values, argument
 * validation, closed-handle errors, every advice on buffered and {@code O_DIRECT}
 * read-only/read-write files, the whole-file convenience overload, the "advisory only"
 * contract (no content/length/counter change) and the dispatcher's direct-errno
 * convention.
 *
 * <p>Stream-level {@code withFadvise} hints are covered by {@code B6StreamDurabilityTest}
 * (design §2.4/§2.5/§7.3); this class exercises the {@link JuringFile} layer itself.
 */
class B6FadviseTest {

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
    // enum contract
    // ------------------------------------------------------------------

    @Test
    void adviceConstantsCarryThePosixValues() {
        assertEquals(0, FadviseAdvice.NORMAL.value());
        assertEquals(1, FadviseAdvice.RANDOM.value());
        assertEquals(2, FadviseAdvice.SEQUENTIAL.value());
        assertEquals(3, FadviseAdvice.WILLNEED.value());
        assertEquals(4, FadviseAdvice.DONTNEED.value());
        assertEquals(5, FadviseAdvice.NOREUSE.value());
        assertEquals(6, FadviseAdvice.values().length);
        // Declaration order is the POSIX advice order (0..5).
        FadviseAdvice[] values = FadviseAdvice.values();
        for (int i = 0; i < values.length; i++) {
            assertEquals(i, values[i].value());
        }
    }

    // ------------------------------------------------------------------
    // argument validation
    // ------------------------------------------------------------------

    @Test
    void nullAdviceThrowsNullPointerException(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("null-advice.bin"), BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            assertThrows(NullPointerException.class, () -> file.fadvise(null));
            assertThrows(NullPointerException.class, () -> file.fadvise(null, 0, 0));
            assertThrows(NullPointerException.class, () -> file.fadvise(null, 0, BLOCK));
        }
    }

    @Test
    void negativeOffsetOrLengthThrowsIllegalArgumentException(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("bad-args.bin"), BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            assertThrows(IllegalArgumentException.class, () -> file.fadvise(FadviseAdvice.NORMAL, -1, 0));
            assertThrows(IllegalArgumentException.class, () -> file.fadvise(FadviseAdvice.SEQUENTIAL, 0, -1));
            assertThrows(IllegalArgumentException.class, () -> file.fadvise(FadviseAdvice.WILLNEED, -4096, -4096));
        }
    }

    @Test
    void fadviseAfterCloseThrowsIllegalStateException(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("closed.bin"), BLOCK);
        JuringFile file = JuringFile.open(ring, path, READ);
        file.close();
        assertThrows(IllegalStateException.class, () -> file.fadvise(FadviseAdvice.NORMAL));
        assertThrows(IllegalStateException.class, () -> file.fadvise(FadviseAdvice.DONTNEED, 0, 0));
    }

    @Test
    void fadviseWithClosedRingThrowsIllegalStateException(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("closed-ring.bin"), BLOCK);
        JUring otherRing = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
        JuringFile file = JuringFile.open(otherRing, path, READ);
        otherRing.close();
        try {
            assertThrows(IllegalStateException.class, () -> file.fadvise(FadviseAdvice.NORMAL));
        } finally {
            file.close();
        }
    }

    // ------------------------------------------------------------------
    // forwarding: every advice on real files
    // ------------------------------------------------------------------

    @Test
    void everyAdviceSucceedsOnBufferedReadOnlyAndReadWriteFiles(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("advice-buffered.bin"), 2 * BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            applyEveryAdvice(file);
        }
        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            applyEveryAdvice(file);
        }
    }

    @Test
    void everyAdviceSucceedsOnDirectIoReadOnlyAndReadWriteFiles(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("advice-direct.bin"), 2 * BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ, DIRECT)) {
            applyEveryAdvice(file);
        }
        try (JuringFile file = JuringFile.open(ring, path, RDWR, DIRECT)) {
            applyEveryAdvice(file);
        }
        try (JuringFile file = JuringFile.open(ring, path, WRITE_DIRECT)) {
            applyEveryAdvice(file);
        }
    }

    @Test
    void wholeFileConvenienceIsEquivalentToZeroOffsetZeroLength(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("convenience.bin"), 2 * BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            for (FadviseAdvice advice : FadviseAdvice.values()) {
                long issued = file.issuedOperations();
                file.fadvise(advice);
                assertEquals(issued, file.issuedOperations(),
                        "fadvise(" + advice + ") must not touch the ring counters");
                file.fadvise(advice, 0, 0);
                assertEquals(issued, file.issuedOperations(),
                        "fadvise(" + advice + ", 0, 0) must not touch the ring counters");
            }
        }
    }

    // ------------------------------------------------------------------
    // advisory-only contract
    // ------------------------------------------------------------------

    @Test
    void fadviseDoesNotChangeContentLengthOrCounters(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("unchanged.bin");
        byte[] data = pattern(3 * BLOCK);
        Files.write(path, data);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            assertEquals(data.length, file.length());

            long issued = file.issuedOperations();
            long completed = file.completedOperations();
            long failed = file.failedOperations();
            long control = file.controlOperations();
            long syncOperations = file.syncOperations();
            long syncCompletions = file.syncCompletions();

            applyEveryAdvice(file);

            assertEquals(issued, file.issuedOperations(), "fadvise is not a ring request");
            assertEquals(completed, file.completedOperations());
            assertEquals(failed, file.failedOperations());
            assertEquals(control, file.controlOperations());
            assertEquals(syncOperations, file.syncOperations());
            assertEquals(syncCompletions, file.syncCompletions());
            assertEquals(0, file.inFlightOperations());
            assertEquals(data.length, file.length());
        }

        assertEquals(data.length, Files.size(path));
        assertArrayEquals(data, Files.readAllBytes(path));
    }

    @Test
    void normalAdviceIssuesNoRingOperationAndKeepsTheHandleUsable(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("normal.bin"), BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            long issued = file.issuedOperations();
            // NORMAL is the "no hint" sentinel; an explicit call is still legal and
            // forwards 0. Observably it is a successful no-op on the file/ring state.
            file.fadvise(FadviseAdvice.NORMAL);
            file.fadvise(FadviseAdvice.NORMAL, 0, 0);
            file.fadvise(FadviseAdvice.NORMAL, 0, BLOCK);
            assertEquals(issued, file.issuedOperations());
            assertEquals(0, file.inFlightOperations());

            // The handle still works normally afterwards.
            assertEquals(BLOCK, file.length());
        }
    }

    @Test
    void fadviseDoesNotRequireRingOwnership(@TempDir Path dir) throws Exception {
        Path path = createFile(dir.resolve("foreign-work.bin"), BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            // A raw ring request that this handle does not own: blocking file helpers
            // reject that state, but fadvise must not (it does not touch the ring).
            long nop = ring.prepareNop();
            ring.submit();
            assertEquals(1, ring.inFlightCount());
            long issued = file.issuedOperations();

            file.fadvise(FadviseAdvice.SEQUENTIAL);
            file.fadvise(FadviseAdvice.NORMAL, 0, 0);

            assertEquals(issued, file.issuedOperations());
            assertEquals(nop, ring.waitForResult().id());
        }
    }

    // ------------------------------------------------------------------
    // dispatcher convention
    // ------------------------------------------------------------------

    @Test
    void posixFadviseReturnsTheErrnoDirectly() {
        // glibc's posix_fadvise returns the error number directly (EBADF == 9 for a bad
        // descriptor), never the -1/last-errno convention the other libc calls use.
        assertEquals(JuringException.EBADF,
                NativeDispatcher.C.posixFadvise(-1, 0, 0, FadviseAdvice.NORMAL.value()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void applyEveryAdvice(JuringFile file) throws JuringException {
        for (FadviseAdvice advice : FadviseAdvice.values()) {
            file.fadvise(advice);
            file.fadvise(advice, 0, BLOCK);
            file.fadvise(advice, BLOCK, 0);
            file.fadvise(advice, BLOCK, BLOCK);
        }
    }

    private static Path createFile(Path path, int length) throws Exception {
        Files.write(path, pattern(length));
        return path;
    }

    private static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return data;
    }
}
