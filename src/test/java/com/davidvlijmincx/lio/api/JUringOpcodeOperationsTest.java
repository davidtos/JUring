package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the optional engine opcodes added for B1 through the raw {@link JUring} API:
 * {@code IORING_OP_FALLOCATE}, {@code IORING_OP_FTRUNCATE}, {@code IORING_OP_STATX} and
 * {@code IORING_OP_TIMEOUT}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JUringOpcodeOperationsTest {

    private static final int ETIME = 62;
    private static final int EBADF = 9;

    @TempDir
    Path tempDir;

    private JUring ring;

    @BeforeEach
    void setUp() {
        ring = new JUring(32, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close();
        }
    }

    private Path writeFile(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    @Test
    void thisKernelAdvertisesAllOptionalOpcodes() {
        // Verified environment fact for docs/b1-design.md §1: kernel 7.0.12 supports all four.
        assertTrue(ring.supportsFallocate(), "IORING_OP_FALLOCATE must be advertised by the probe");
        assertTrue(ring.supportsFtruncate(), "IORING_OP_FTRUNCATE must be advertised by the probe");
        assertTrue(ring.supportsStatx(), "IORING_OP_STATX must be advertised by the probe");
        assertTrue(ring.supportsTimeout(), "IORING_OP_TIMEOUT must be advertised by the probe");
    }

    @Test
    void inFlightCountTracksUnreapedOperations() {
        assertEquals(0, ring.inFlightCount());
        long handle = ring.prepareNop();
        assertEquals(1, ring.inFlightCount(), "prepared but not yet completed ops are in flight");

        ring.submit();
        Result result = ring.waitForResult();

        assertEquals(handle, result.id());
        assertEquals(0, ring.inFlightCount(), "reaping a completion must decrement the in-flight count");
    }

    @Test
    void fallocateExtendsFile() throws IOException {
        Assumptions.assumeTrue(ring.supportsFallocate());
        Path file = writeFile("fallocate-extend.bin", new byte[]{1});
        assertEquals(1L, Files.size(file));

        try (FileDescriptor fd = new FileDescriptor(file, WRITE)) {
            long handle = ring.prepareFallocate(fd, 0, 0, 4096);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(handle, result.id());
            assertEquals(OperationType.FALLOCATE, result.type());
            assertTrue(result.isSuccess(), "fallocate failed: " + result.result());
            assertEquals(0, result.result(), "metadata ops return 0");
        }
        assertEquals(4096L, Files.size(file), "fallocate(mode=0) must extend the file");
    }

    @Test
    void fallocatePunchHoleKeepsSizeAndZeroesRange() throws IOException {
        Assumptions.assumeTrue(ring.supportsFallocate());
        byte[] content = new byte[8192];
        Arrays.fill(content, (byte) 0xAA);
        Path file = writeFile("fallocate-punch.bin", content);

        int fallocKeepSize = 0x01;
        int fallocPunchHole = 0x02;
        try (FileDescriptor fd = new FileDescriptor(file, WRITE)) {
            long handle = ring.prepareFallocate(fd, fallocPunchHole | fallocKeepSize, 0, 4096);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(handle, result.id());
            assertEquals(OperationType.FALLOCATE, result.type());
            assertTrue(result.isSuccess(), "punch hole failed: " + result.result());
        }

        byte[] after = Files.readAllBytes(file);
        assertEquals(8192, after.length, "KEEP_SIZE punch hole must not change the file length");
        for (int i = 0; i < 4096; i++) {
            assertEquals(0, after[i], "punched range must read as zero at offset " + i);
        }
        for (int i = 4096; i < 8192; i++) {
            assertEquals((byte) 0xAA, after[i], "unpunched range must be untouched at offset " + i);
        }
    }

    @Test
    void ftruncateShrinksAndGrowsFile() throws IOException {
        Assumptions.assumeTrue(ring.supportsFtruncate());
        Path file = writeFile("ftruncate.bin", new byte[8192]);

        try (FileDescriptor fd = new FileDescriptor(file, RDWR)) {
            long shrinkHandle = ring.prepareFtruncate(fd, 4096);
            ring.submit();
            Result shrink = ring.waitForResult();
            assertEquals(shrinkHandle, shrink.id());
            assertEquals(OperationType.FTRUNCATE, shrink.type());
            assertTrue(shrink.isSuccess(), "ftruncate shrink failed: " + shrink.result());
        }
        assertEquals(4096L, Files.size(file));

        try (FileDescriptor fd = new FileDescriptor(file, WRITE)) {
            long growHandle = ring.prepareFtruncate(fd, 8192);
            ring.submit();
            Result grow = ring.waitForResult();
            assertEquals(growHandle, grow.id());
            assertTrue(grow.isSuccess(), "ftruncate grow failed: " + grow.result());
        }
        assertEquals(8192L, Files.size(file));
    }

    @Test
    void statxReturnsFileSize() throws IOException {
        Assumptions.assumeTrue(ring.supportsStatx());
        byte[] content = "statx-payload".getBytes(StandardCharsets.UTF_8);
        Path file = writeFile("statx.bin", content);
        long expectedSize = content.length;

        try (FileDescriptor fd = new FileDescriptor(file, READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment statxBuffer = arena.allocate(256L);
            long handle = ring.prepareStatx(fd, statxBuffer);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(handle, result.id());
            assertEquals(OperationType.STATX, result.type());
            assertTrue(result.isSuccess(), "statx failed: " + result.result());
            assertEquals(0, result.result());
            assertEquals(expectedSize, ring.staticxSize(statxBuffer));
            assertEquals(expectedSize, statxBuffer.get(JAVA_LONG, 40L), "stx_size lives at offset 40");
        }
    }

    @Test
    void statxWorksForRegisteredFileSlot() throws IOException {
        Assumptions.assumeTrue(ring.supportsStatx());
        byte[] content = new byte[777];
        Arrays.fill(content, (byte) 0x5A);
        Path file = writeFile("statx-fixed-slot.bin", content);

        try (FileDescriptor fd = new FileDescriptor(file, READ);
             Arena arena = Arena.ofConfined()) {
            int slot = ring.registerFileAdd(fd);
            assertEquals(0, slot, "first sparse table slot");

            MemorySegment statxBuffer = arena.allocate(256L);
            long handle = ring.prepareStatx(slot, statxBuffer);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(handle, result.id());
            assertEquals(OperationType.STATX, result.type());
            assertTrue(result.isSuccess(), "statx on a fixed-file slot failed: " + result.result());
            assertEquals(777L, ring.staticxSize(statxBuffer));
        }
    }

    @Test
    void fallocateAndFtruncateWorkOnRegisteredFileSlot() throws IOException {
        Assumptions.assumeTrue(ring.supportsFallocate() && ring.supportsFtruncate());
        Path file = writeFile("fixed-metadata.bin", new byte[100]);

        try (FileDescriptor fd = new FileDescriptor(file, RDWR)) {
            int slot = ring.registerFileAdd(fd);

            long fallocateHandle = ring.prepareFallocate(slot, 0, 0, 512);
            ring.submit();
            Result fallocate = ring.waitForResult();
            assertEquals(fallocateHandle, fallocate.id());
            assertEquals(OperationType.FALLOCATE, fallocate.type());
            assertTrue(fallocate.isSuccess(), "fixed-file fallocate failed: " + fallocate.result());
            assertEquals(512L, Files.size(file));

            long truncateHandle = ring.prepareFtruncate(slot, 256);
            ring.submit();
            Result truncate = ring.waitForResult();
            assertEquals(truncateHandle, truncate.id());
            assertEquals(OperationType.FTRUNCATE, truncate.type());
            assertTrue(truncate.isSuccess(), "fixed-file ftruncate failed: " + truncate.result());
            assertEquals(256L, Files.size(file));
        }
    }

    @Test
    void statxOnEmptyFileSlotFailsWithEbadf() throws IOException {
        Assumptions.assumeTrue(ring.supportsStatx());
        Path file = writeFile("statx-empty-slot.bin", new byte[]{1, 2, 3, 4});
        try (FileDescriptor fd = new FileDescriptor(file, READ);
             Arena arena = Arena.ofConfined()) {
            int slot = ring.registerFileAdd(fd);
            ring.unregisterFileSlot(slot);

            MemorySegment statxBuffer = arena.allocate(256L);
            long handle = ring.prepareStatx(slot, statxBuffer);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(handle, result.id());
            assertFalse(result.isSuccess(), "statx on an empty fixed-file slot must fail");
            assertEquals(-EBADF, result.result());
        }
    }

    @Test
    void timeoutFiresWithEtime() {
        Assumptions.assumeTrue(ring.supportsTimeout());
        long timeoutNanos = 20_000_000L; // 20 ms

        long start = System.nanoTime();
        long handle = ring.prepareTimeout(timeoutNanos);
        ring.submit();
        Result result = ring.waitForResult();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(handle, result.id());
        assertEquals(OperationType.TIMEOUT, result.type());
        assertEquals(-ETIME, result.result(), "an expired IORING_OP_TIMEOUT completes with -ETIME");
        assertTrue(elapsedMillis >= 15L, "timeout fired too early: " + elapsedMillis + " ms");
    }

    @Test
    void timeoutCompletesEarlyWhenCountIsReached() {
        Assumptions.assumeTrue(ring.supportsTimeout());

        long timeoutHandle = ring.prepareTimeout(10_000_000_000L, 1, 0); // 10 s safety margin
        long nopHandle = ring.prepareNop();
        ring.submit();

        Result first = ring.waitForResult();
        Result second = ring.waitForResult();

        Result nop = first.type() == OperationType.NOP ? first : second;
        Result timeout = first.type() == OperationType.TIMEOUT ? first : second;

        assertEquals(nopHandle, nop.id());
        assertTrue(nop.isSuccess());
        assertEquals(timeoutHandle, timeout.id());
        assertEquals(OperationType.TIMEOUT, timeout.type());
        assertTrue(timeout.isSuccess(), "timeout reached its completion count and must complete with 0, got " + timeout.result());
    }

    @Test
    void sqeOptionsOverloadsRejectInvalidArgumentsBeforeTouchingTheRing() {
        Assumptions.assumeTrue(ring.supportsFallocate() && ring.supportsFtruncate()
                && ring.supportsStatx() && ring.supportsTimeout());
        // These calls exercise the SqeOptions overloads; validation must throw before a SQE is taken.
        assertThrows(IllegalArgumentException.class,
                () -> ring.prepareFallocate(0, 0, -1L, 1L, SqeOptions.IOSQE_IO_DRAIN));
        assertThrows(IllegalArgumentException.class,
                () -> ring.prepareFtruncate(0, -1L, SqeOptions.IOSQE_IO_DRAIN));
        assertThrows(IllegalArgumentException.class,
                () -> ring.prepareStatx(0, MemorySegment.NULL, SqeOptions.IOSQE_IO_DRAIN));
        assertThrows(IllegalArgumentException.class,
                () -> ring.prepareTimeout(-1L, 1, 0, SqeOptions.IOSQE_IO_DRAIN));
        assertEquals(0, ring.inFlightCount(), "rejected prepares must not consume SQEs");
    }

    @Test
    void prepareStatxValidatesBuffer() throws IOException {
        Assumptions.assumeTrue(ring.supportsStatx());
        Path file = writeFile("statx-validation.bin", new byte[]{1});
        try (FileDescriptor fd = new FileDescriptor(file, READ);
             Arena arena = Arena.ofConfined()) {
            assertThrows(NullPointerException.class, () -> ring.prepareStatx(fd, null));
            assertThrows(IllegalArgumentException.class,
                    () -> ring.prepareStatx(fd, MemorySegment.ofArray(new byte[256])));
            assertThrows(IllegalArgumentException.class,
                    () -> ring.prepareStatx(fd, arena.allocate(255L)));

            long handle = ring.prepareStatx(fd, arena.allocate(256L));
            ring.submit();
            Result result = ring.waitForResult();
            assertEquals(handle, result.id());
            assertTrue(result.isSuccess(), "statx with a valid 256-byte buffer failed: " + result.result());
        }
    }

    @Test
    void prepareTimeoutValidatesArguments() {
        Assumptions.assumeTrue(ring.supportsTimeout());
        assertThrows(IllegalArgumentException.class, () -> ring.prepareTimeout(-1L));
        assertThrows(IllegalArgumentException.class, () -> ring.prepareTimeout(0L, -1, 0));
        assertEquals(0, ring.inFlightCount());
    }

    @Test
    void prepareMetadataOpsOnSqeOptionsOverloadsWork() throws IOException {
        Assumptions.assumeTrue(ring.supportsFtruncate() && ring.supportsFallocate());
        Path file = writeFile("metadata-overloads.bin", new byte[4096]);

        try (FileDescriptor fd = new FileDescriptor(file, RDWR)) {
            long fallocateHandle = ring.prepareFallocate(fd, 0, 0, 8192, SqeOptions.IOSQE_IO_DRAIN);
            ring.submit();
            Result fallocate = ring.waitForResult();
            assertEquals(fallocateHandle, fallocate.id());
            assertEquals(OperationType.FALLOCATE, fallocate.type());
            assertTrue(fallocate.isSuccess(), "fallocate with SqeOptions failed: " + fallocate.result());

            long truncateHandle = ring.prepareFtruncate(fd, 2048, SqeOptions.IOSQE_IO_DRAIN);
            ring.submit();
            Result truncate = ring.waitForResult();
            assertEquals(truncateHandle, truncate.id());
            assertTrue(truncate.isSuccess(), "ftruncate with SqeOptions failed: " + truncate.result());
        }
        assertEquals(2048L, Files.size(file));
    }

    @Test
    void fixedBufferReadStillWorksAfterMetadataOps() throws IOException {
        // Sanity check that the new opcodes do not disturb the existing fixed-buffer hot path.
        byte[] content = "fixed-buffer-still-works".getBytes(StandardCharsets.UTF_8);
        Path file = writeFile("fixed-buffer.bin", content);

        ring.registerBuffers(64, 1);
        try (FileDescriptor fd = new FileDescriptor(file, READ)) {
            long handle = ring.prepareReadFixed(fd, content.length, 0, 0);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(handle, result.id());
            assertTrue(result.isSuccess());
            assertEquals(content.length, result.result());
            MemorySegment buffer = ring.getRegisteredBuffer(0);
            byte[] actual = new byte[content.length];
            MemorySegment.copy(buffer, 0, MemorySegment.ofArray(actual), 0, content.length);
            assertArrayEquals(content, actual);
        }
    }
}
