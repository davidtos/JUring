package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B10a tests: multiple {@link JuringFile}/{@link JuringInputStream}/{@link JuringOutputStream}/
 * {@link GroupCommit} handles multiplexed on one {@link JUring} (frozen contract:
 * {@code docs/b10-design.md} §2).
 *
 * <p>Covers interleaved completion routing, stream copy on one ring, group commit with a
 * sibling read, scoped {@code cancel}/{@code cancelAll}/{@code close}, and the surviving
 * guard against un-reaped raw ring work.
 */
@Timeout(120)
class JuringFileMultiplexingTest {

    private static final int BLOCK = 4096;
    private static final int KIB = 1024;

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
    // interleaved completions
    // ------------------------------------------------------------------

    @Test
    void twoFilesInterleaveAndAwaitOutOfOrder(@TempDir Path dir) throws Exception {
        Path pathA = patternFile(dir.resolve("a.bin"), 2 * BLOCK, 3);
        Path pathB = patternFile(dir.resolve("b.bin"), 2 * BLOCK, 11);

        try (JuringFile fileA = JuringFile.open(ring, pathA, READ);
             JuringFile fileB = JuringFile.open(ring, pathB, READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment bufferA = arena.allocate(BLOCK);
            MemorySegment bufferB = arena.allocate(BLOCK);

            long handleA = fileA.prepareRead(0, BLOCK, bufferA);
            long handleB = fileB.prepareRead(BLOCK, BLOCK, bufferB);
            assertEquals(1, fileA.inFlightOperations());
            assertEquals(1, fileB.inFlightOperations());

            // await B before A: whichever completion the ring reaps first is routed by handle
            assertEquals(BLOCK, fileB.await(handleB));
            assertEquals(BLOCK, fileA.await(handleA));

            assertEquals(3, bufferA.get(JAVA_BYTE, 0));
            assertEquals(11, bufferB.get(JAVA_BYTE, 0));
            assertEquals(0, fileA.inFlightOperations());
            assertEquals(0, fileB.inFlightOperations());
            assertEquals(1, fileA.completedOperations());
            assertEquals(1, fileB.completedOperations());
        }
    }

    @Test
    void interleavedReadsAndWritesOnTwoFiles(@TempDir Path dir) throws Exception {
        Path readPath = patternFile(dir.resolve("read.bin"), BLOCK, 5);
        Path writePath = dir.resolve("write.bin");

        try (JuringFile reader = JuringFile.open(ring, readPath, READ);
             JuringFile writer = JuringFile.open(ring, writePath, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(BLOCK);
            for (int i = 0; i < BLOCK; i++) {
                source.set(JAVA_BYTE, i, (byte) (i * 7 + 1));
            }
            long writeHandle = writer.prepareWrite(0, source);
            long readHandle = reader.prepareRead(0, BLOCK, arena.allocate(BLOCK));

            assertEquals(BLOCK, reader.await(readHandle));
            assertEquals(BLOCK, writer.await(writeHandle));
            assertEquals(BLOCK, Files.size(writePath));
            assertArrayEquals(bytesOf(source), Files.readAllBytes(writePath));
        }
    }

    @Test
    void streamReadAndStreamWriteShareOneRing(@TempDir Path dir) throws Exception {
        int size = 256 * KIB;
        Path sourcePath = patternFile(dir.resolve("stream-source.bin"), size, 2);
        Path targetPath = dir.resolve("stream-target.bin");
        byte[] expected = Files.readAllBytes(sourcePath);

        try (JuringFile sourceFile = JuringFile.open(ring, sourcePath, READ);
             JuringFile targetFile = JuringFile.open(ring, targetPath, RDWR, CREATE, TRUNCATE);
             JuringInputStream in = JuringInputStream.open(sourceFile,
                     InputStreamOptions.defaults().withBufferSize(16 * KIB).withReadAhead(2));
             JuringOutputStream out = JuringOutputStream.open(targetFile,
                     OutputStreamOptions.defaults().withBufferSize(16 * KIB).withWriteBehind(2))) {
            byte[] chunk = new byte[8 * KIB];
            int read;
            while ((read = in.read(chunk, 0, chunk.length)) > 0) {
                out.write(chunk, 0, read);
            }
            out.flush();
            assertTrue(sourceFile.isOpen());
            assertTrue(targetFile.isOpen());
        }

        assertArrayEquals(expected, Files.readAllBytes(targetPath));
    }

    @Test
    void groupCommitWorksWhileSiblingReadIsInFlight(@TempDir Path dir) throws Exception {
        Path sourcePath = patternFile(dir.resolve("gc-source.bin"), BLOCK, 9);
        Path targetPath = dir.resolve("gc-target.bin");

        try (JuringFile sourceFile = JuringFile.open(ring, sourcePath, READ);
             JuringFile targetFile = JuringFile.open(ring, targetPath, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined()) {
            MemorySegment readBuffer = arena.allocate(BLOCK);
            long readHandle = sourceFile.prepareRead(0, BLOCK, readBuffer);

            MemorySegment batch = arena.allocate(BLOCK);
            for (int i = 0; i < BLOCK; i++) {
                batch.set(JAVA_BYTE, i, (byte) (i * 13 + 5));
            }
            try (GroupCommit group = GroupCommit.open(targetFile)) {
                group.write(0, batch);
                assertEquals(1, group.commit());
                assertEquals(1, group.stats().syncs());
            }

            assertEquals(BLOCK, sourceFile.await(readHandle));
            assertEquals(9, readBuffer.get(JAVA_BYTE, 0));
            assertArrayEquals(bytesOf(batch), Files.readAllBytes(targetPath));
        }
    }

    @Test
    void concurrentNonFixedReadsIntoSameBufferGetDistinctHandles(@TempDir Path dir) throws Exception {
        Path sourcePath = patternFile(dir.resolve("same-buffer.bin"), 2 * BLOCK, 7);

        // two handles, one ring, one shared caller buffer: JuringFile reads use a unique
        // internal op id, so the completions stay distinguishable (B3 F1 fixed for this path)
        try (JuringFile fileA = JuringFile.open(ring, sourcePath, READ);
             JuringFile fileB = JuringFile.open(ring, sourcePath, READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment shared = arena.allocate(BLOCK);
            long handleA = fileA.prepareRead(0, BLOCK, shared);
            long handleB = fileB.prepareRead(BLOCK, BLOCK, shared);
            assertNotEquals(handleA, handleB, "handles must be unique even for the same buffer");
            ring.submit();
            assertEquals(BLOCK, fileA.await(handleA));
            assertEquals(BLOCK, fileB.await(handleB));
            assertEquals(0, fileA.inFlightOperations());
            assertEquals(0, fileB.inFlightOperations());
        }

        // same handle, two concurrent reads into the same buffer
        try (JuringFile file = JuringFile.open(ring, sourcePath, READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment shared = arena.allocate(BLOCK);
            long first = file.prepareRead(0, BLOCK, shared);
            long second = file.prepareRead(BLOCK, BLOCK, shared);
            assertNotEquals(first, second, "handles must be unique even for the same buffer");
            ring.submit();
            assertEquals(BLOCK, file.await(second));
            assertEquals(BLOCK, file.await(first));
            assertEquals(0, file.inFlightOperations());
        }
    }

    @Test
    void concurrentNonFixedReadsOnRegisteredFilesGetDistinctHandles(@TempDir Path dir) throws Exception {
        Path pathA = patternFile(dir.resolve("registered-a.bin"), 2 * BLOCK, 21);
        Path pathB = patternFile(dir.resolve("registered-b.bin"), 2 * BLOCK, 22);

        // registered-file branch: reads still use a caller buffer; handles must stay unique
        try (JuringFile fileA = JuringFile.open(ring, pathA, READ);
             JuringFile fileB = JuringFile.open(ring, pathB, READ);
             Arena arena = Arena.ofConfined()) {
            assertTrue(fileA.register() >= 0);
            assertTrue(fileB.register() >= 0);
            MemorySegment shared = arena.allocate(BLOCK);
            long handleA = fileA.prepareRead(0, BLOCK, shared);
            long handleB = fileB.prepareRead(BLOCK, BLOCK, shared);
            assertNotEquals(handleA, handleB, "registered-file reads must also get unique handles");
            ring.submit();
            assertEquals(BLOCK, fileA.await(handleA));
            assertEquals(BLOCK, fileB.await(handleB));
            assertEquals(0, fileA.inFlightOperations());
            assertEquals(0, fileB.inFlightOperations());
        }

        // one registered handle, two concurrent reads into the same segment
        try (JuringFile file = JuringFile.open(ring, pathA, READ);
             Arena arena = Arena.ofConfined()) {
            assertTrue(file.register() >= 0);
            MemorySegment shared = arena.allocate(BLOCK);
            long first = file.prepareRead(0, BLOCK, shared);
            long second = file.prepareRead(BLOCK, BLOCK, shared);
            assertNotEquals(first, second, "registered-file reads must also get unique handles");
            ring.submit();
            assertEquals(BLOCK, file.await(second));
            assertEquals(BLOCK, file.await(first));
            assertEquals(0, file.inFlightOperations());
        }

        // timed read on a registered handle uses the same internal id (target + LINK_TIMEOUT)
        try (JuringFile file = JuringFile.open(ring, pathA, READ);
             Arena arena = Arena.ofConfined()) {
            assertTrue(file.register() >= 0);
            MemorySegment shared = arena.allocate(BLOCK);
            long timed = file.prepareRead(0, BLOCK, shared, 1_000);
            assertEquals(BLOCK, file.await(timed));
            assertEquals(0, file.inFlightOperations());
        }
    }

    // ------------------------------------------------------------------
    // scoped cancellation / close
    // ------------------------------------------------------------------

    @Test
    void cancelOnOneHandleLeavesTheSiblingUntouched(@TempDir Path dir) throws Exception {
        Path sourcePath = patternFile(dir.resolve("cancel-source.bin"), BLOCK, 4);

        try (JuringFile fileA = JuringFile.open(ring, sourcePath, READ);
             JuringFile fileB = JuringFile.open(ring, sourcePath, READ);
             Arena arena = Arena.ofConfined()) {
            long timer = fileA.prepareTimeout(30_000);
            long read = fileB.prepareRead(0, BLOCK, arena.allocate(BLOCK));

            assertEquals(JuringFile.CancelStatus.CANCELLED, fileA.cancel(timer));
            JuringException cancelled = assertThrows(JuringException.class, () -> fileA.await(timer));
            assertEquals(JuringException.ECANCELED, cancelled.errno());

            assertEquals(BLOCK, fileB.await(read));
        }
    }

    @Test
    void cancelAllOnOneHandleLeavesTheSiblingUntouched(@TempDir Path dir) throws Exception {
        Path sourcePath = patternFile(dir.resolve("cancelall-source.bin"), BLOCK, 6);

        try (JuringFile fileA = JuringFile.open(ring, sourcePath, READ);
             JuringFile fileB = JuringFile.open(ring, sourcePath, READ);
             Arena arena = Arena.ofConfined()) {
            long firstTimer = fileA.prepareTimeout(30_000);
            long secondTimer = fileA.prepareTimeout(30_000);
            long read = fileB.prepareRead(0, BLOCK, arena.allocate(BLOCK));

            assertEquals(2, fileA.cancelAll());
            assertEquals(JuringException.ECANCELED,
                    assertThrows(JuringException.class, () -> fileA.await(firstTimer)).errno());
            assertEquals(JuringException.ECANCELED,
                    assertThrows(JuringException.class, () -> fileA.await(secondTimer)).errno());

            assertEquals(BLOCK, fileB.await(read));
        }
    }

    @Test
    void closeOnOneHandleLeavesTheSiblingUntouched(@TempDir Path dir) throws Exception {
        Path sourcePath = patternFile(dir.resolve("close-source.bin"), BLOCK, 8);

        try (JuringFile fileA = JuringFile.open(ring, sourcePath, READ);
             JuringFile fileB = JuringFile.open(ring, sourcePath, READ);
             Arena arena = Arena.ofConfined()) {
            fileA.prepareTimeout(30_000);
            long read = fileB.prepareRead(0, BLOCK, arena.allocate(BLOCK));

            fileA.close(250);
            assertFalse(fileA.isOpen());

            assertEquals(BLOCK, fileB.await(read));
            assertTrue(fileB.isOpen());
        }
    }

    // ------------------------------------------------------------------
    // raw foreign work still guarded
    // ------------------------------------------------------------------

    @Test
    void unReapedRawRingWorkStillRejectsBlockingHelpers(@TempDir Path dir) throws Exception {
        Path sourcePath = patternFile(dir.resolve("foreign-source.bin"), BLOCK, 12);

        try (JuringFile file = JuringFile.open(ring, sourcePath, READ);
             Arena arena = Arena.ofConfined()) {
            long nop = ring.prepareNop();
            long timer = file.prepareTimeout(30_000);

            IllegalStateException guard = assertThrows(IllegalStateException.class, () -> file.await(timer));
            assertTrue(guard.getMessage().contains("in-flight operation"));

            // reap the raw NOP, then the blocking helper is allowed again
            ring.submit();
            Result rawResult = ring.waitForResult();
            assertEquals(nop, rawResult.id());
            assertEquals(0, rawResult.result());

            assertEquals(JuringFile.CancelStatus.CANCELLED, file.cancel(timer));
            assertEquals(JuringException.ECANCELED,
                    assertThrows(JuringException.class, () -> file.await(timer)).errno());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Path patternFile(Path path, int size, int seed) throws Exception {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + seed);
        }
        Files.write(path, bytes);
        return path;
    }

    private static byte[] bytesOf(MemorySegment segment) {
        byte[] bytes = new byte[(int) segment.byteSize()];
        MemorySegment.copy(segment, JAVA_BYTE, 0, bytes, 0, bytes.length);
        return bytes;
    }
}
