package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DIRECT;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Conformance tests for {@link JuringInputStream} and {@link JuringOutputStream}
 * against the frozen stream contract.
 *
 * <p>Every test uses an isolated {@link JUring} created in {@link #setUp()} and closed in
 * {@link #tearDown()}, and {@link TempDir} scratch files, mirroring
 * {@link JuringFileConformanceTest}. The bounded-memory contract (item 10) is covered by
 * {@link JuringStreamsBoundedMemoryTest}.
 */
class JuringStreamsConformanceTest {

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

    // ==================================================================
    // 1. round trip / EOF (buffered + O_DIRECT)
    // ==================================================================

    @Test
    void bufferedRoundTripAndExactFileLength(@TempDir Path dir) throws Exception {
        int size = 4 * MIB;
        Path path = dir.resolve("roundtrip-buffered.bin");

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            try (JuringOutputStream out = JuringOutputStream.open(file)) {
                // byte[] overload of write()
                byte[] source = patternBytes(0, size);
                out.write(source, 0, size);
                assertThat(out.position()).isEqualTo(size);
                assertThat(out.bytesWritten()).isEqualTo(size);
            }
            assertThat(file.isOpen()).as("stream close must not close the JuringFile").isTrue();
            assertThat(file.length()).isEqualTo(size);
        }
        assertThat(Files.size(path)).isEqualTo(size);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file)) {
            assertSame(file, in.file());
            assertThat(in.bufferSize()).isEqualTo(InputStreamOptions.DEFAULT_BUFFER_SIZE);
            assertThat(in.readAhead()).isEqualTo(InputStreamOptions.defaults().readAhead());
            assertThat(in.currentBufferSize()).isEqualTo(in.bufferSize());
            assertThat(in.currentReadAhead()).isEqualTo(in.readAhead());

            byte[] actual = drain(in);
            assertArrayEquals(patternBytes(0, size), actual, "buffered round trip content mismatch");
            assertThat(in.position()).isEqualTo(size);
            assertThat(in.bytesRead()).isEqualTo(size);
            assertThat(in.eof()).isTrue();
            assertThat(in.readChunk().byteSize()).as("readChunk is empty at EOF").isZero();
        }
    }

    @Test
    void directIoRoundTrip(@TempDir Path dir) throws Exception {
        int size = 4 * MIB;
        Path path = dir.resolve("roundtrip-direct.bin");

        try (JuringFile file = JuringFile.open(ring, path, 0644, RDWR, DIRECT, CREATE, TRUNCATE)) {
            try (JuringOutputStream out = JuringOutputStream.open(file)) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment source = arena.allocate(size, BLOCK);
                    fillPattern(source, 0, size);
                    out.write(source);
                }
                assertThat(out.position()).isEqualTo(size);
            }
            assertThat(file.length()).isEqualTo(size);
        }
        assertThat(Files.size(path)).isEqualTo(size);

        try (JuringFile file = JuringFile.open(ring, path, READ, DIRECT);
             JuringInputStream in = JuringInputStream.open(file)) {
            assertArrayEquals(patternBytes(0, size), drain(in), "O_DIRECT round trip content mismatch");
        }
    }

    @Test
    void emptyFileYieldsEmptyChunksAndZeroReads(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("empty.bin");
        Files.createFile(path);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file);
             Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(2 * BLOCK);

            assertThat(in.readChunk().byteSize()).as("empty file, first chunk").isZero();
            assertThat(in.readChunk().byteSize()).as("EOF stays EOF").isZero();
            assertThat(in.read(destination)).isZero();
            assertThat(in.read(new byte[BLOCK], 0, BLOCK)).isZero();
            assertThat(in.position()).isZero();
            assertThat(in.bytesRead()).isZero();
            assertThat(in.eof()).isTrue();
        }
    }

    // ==================================================================
    // 2. complete-until-done reads
    // ==================================================================

    @Test
    void readDestinationFillsAcrossChunkBoundaries(@TempDir Path dir) throws Exception {
        int chunk = 8192;
        int size = 4 * chunk + 123;
        Path path = createPatternFile(dir.resolve("complete-until-done.bin"), size);
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(chunk)
                .withAlignment(BLOCK)
                .withReadAhead(1);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options);
             Arena arena = Arena.ofConfined()) {
            // dst is twice the configured chunk size, so a full read must cross boundaries.
            MemorySegment destination = arena.allocate(2 * chunk);

            assertThat(in.read(destination)).isEqualTo(2 * chunk);
            assertSegmentMatches(destination, 0);
            assertThat(in.position()).isEqualTo(2 * chunk);

            assertThat(in.read(destination)).isEqualTo(2 * chunk);
            assertSegmentMatches(destination, 2 * chunk);
            assertThat(in.position()).isEqualTo(4 * chunk);

            // Only 123 bytes are left: short count is legal only at EOF.
            assertThat(in.read(destination)).isEqualTo(123);
            assertSegmentMatches(destination.asSlice(0, 123), 4 * chunk);
            assertThat(in.position()).isEqualTo(size);
            assertThat(in.eof()).isTrue();

            // Past EOF the complete-until-done read returns 0, repeatedly.
            assertThat(in.read(destination)).isZero();
            assertThat(in.read(destination)).isZero();
        }
    }

    @Test
    void readDestinationLargerThanFileReturnsShortAtEof(@TempDir Path dir) throws Exception {
        int size = 1000;
        Path path = createPatternFile(dir.resolve("short-destination.bin"), size);
        InputStreamOptions options = InputStreamOptions.defaults().withBufferSize(4 * BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options);
             Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(4 * BLOCK);

            assertThat(in.read(destination)).isEqualTo(size);
            assertSegmentMatches(destination.asSlice(0, size), 0);
            assertThat(in.eof()).isTrue();
            assertThat(in.read(destination)).isZero();
        }
    }

    @Test
    void readByteArrayRangeAndArgumentChecks(@TempDir Path dir) throws Exception {
        int chunk = 8192;
        int size = 3 * chunk + 7;
        Path path = createPatternFile(dir.resolve("byte-array-read.bin"), size);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file,
                     InputStreamOptions.defaults().withBufferSize(chunk).withAlignment(BLOCK))) {
            byte[] destination = new byte[2 * chunk + 16];
            Arrays.fill(destination, (byte) 0x7F);

            int read = in.read(destination, 8, 2 * chunk);
            assertThat(read).isEqualTo(2 * chunk);
            assertArrayEquals(patternBytes(0, 2 * chunk), Arrays.copyOfRange(destination, 8, 8 + 2 * chunk),
                    "byte[] read content mismatch");
            for (int i = 0; i < 8; i++) {
                assertThat(destination[i]).as("read must not touch bytes before offset, index " + i)
                        .isEqualTo((byte) 0x7F);
            }
            for (int i = 8 + 2 * chunk; i < destination.length; i++) {
                assertThat(destination[i]).as("read must not touch bytes after offset+length, index " + i)
                        .isEqualTo((byte) 0x7F);
            }

            // Standard IndexOutOfBoundsException argument checks.
            assertThrows(IndexOutOfBoundsException.class, () -> in.read(destination, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> in.read(destination, 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> in.read(destination, destination.length, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> in.read(destination, destination.length - 2, 3));
        }
    }

    @Test
    void partialReadKeepsCarrySoSubsequentCallsAreContiguous(@TempDir Path dir) throws Exception {
        int chunk = 8192;
        int size = 4 * chunk + 321;
        Path path = createPatternFile(dir.resolve("carry.bin"), size);
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(chunk)
                .withAlignment(BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options);
             Arena arena = Arena.ofConfined()) {
            // The destination is smaller than the internal chunk, so a partial read() must
            // leave the unconsumed tail internally for the next call.
            MemorySegment destination = arena.allocate(3000);

            assertThat(in.read(destination)).isEqualTo(3000);
            assertSegmentMatches(destination, 0);
            assertThat(in.position()).isEqualTo(3000);
            assertThat(in.bytesRead()).isEqualTo(3000);
            assertThat(in.remaining()).isEqualTo(-1);
            assertThat(in.eof()).as("a pending carry means more bytes are still buffered").isFalse();

            // The next read() must continue exactly at byte 3000 from the carried remainder.
            assertThat(in.read(destination)).isEqualTo(3000);
            assertSegmentMatches(destination, 3000);
            assertThat(in.position()).isEqualTo(6000);
            assertThat(in.bytesRead()).isEqualTo(6000);

            // readChunk() must also continue from the carry instead of skipping it.
            MemorySegment chunkSegment = in.readChunk();
            long chunkLength = chunkSegment.byteSize();
            assertThat(chunkLength).isGreaterThan(0);
            assertSegmentMatches(chunkSegment, 6000);
            long afterChunk = 6000 + chunkLength;
            assertThat(in.position()).isEqualTo(afterChunk);

            // skip() consumes carried bytes as real stream bytes.
            assertThat(in.skip(1000)).isEqualTo(1000);
            assertThat(in.position()).isEqualTo(afterChunk + 1000);

            // Drain the rest: the concatenation of everything consumed/skipped must be the
            // file content with no gap and no duplicated byte range.
            ByteArrayOutputStream rest = new ByteArrayOutputStream();
            MemorySegment remainingChunk;
            while ((remainingChunk = in.readChunk()).byteSize() > 0) {
                rest.write(bytesOf(remainingChunk));
            }
            assertThat(in.eof()).isTrue();
            assertThat(in.position()).isEqualTo(size);
            assertArrayEquals(
                    patternBytes(afterChunk + 1000, size - (int) (afterChunk + 1000)),
                    rest.toByteArray(),
                    "bytes after the read/skip sequence must be contiguous");
        }
    }

    @Test
    void windowRemainingAndPositionAccountForUnreadCarry(@TempDir Path dir) throws Exception {
        int size = 64 * KIB;
        Path path = createPatternFile(dir.resolve("carry-window.bin"), size);
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(8192)
                .withAlignment(BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, 1000, 5000, options);
             Arena arena = Arena.ofConfined()) {
            MemorySegment destination = arena.allocate(3000);

            assertThat(in.read(destination)).isEqualTo(3000);
            assertSegmentMatches(destination, 1000);
            // 2000 logical bytes of the first chunk are still unread (carried).
            assertThat(in.position()).isEqualTo(4000);
            assertThat(in.remaining()).isEqualTo(2000);
            assertThat(in.bytesRead()).isEqualTo(3000);
            assertThat(in.eof()).isFalse();

            assertThat(in.read(destination)).isEqualTo(2000);
            assertSegmentMatches(destination.asSlice(0, 2000), 4000);
            assertThat(in.position()).isEqualTo(6000);
            assertThat(in.remaining()).isZero();
            assertThat(in.bytesRead()).isEqualTo(5000);
            assertThat(in.eof()).isTrue();

            assertThat(in.read(destination)).isZero();
            assertThat(in.readChunk().byteSize()).isZero();
        }
    }

    // ==================================================================
    // 3. zero-copy chunk contract
    // ==================================================================

    @Test
    void chunkContractBoundedSumsToContentAndEmptyOnlyAtEof(@TempDir Path dir) throws Exception {
        int chunk = 8192;
        int size = 3 * chunk + 5000;
        Path path = createPatternFile(dir.resolve("chunks.bin"), size);
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(chunk)
                .withAlignment(BLOCK)
                .withReadAhead(2);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options)) {
            long consumed = 0;
            int chunks = 0;
            MemorySegment chunkSegment;
            while ((chunkSegment = in.readChunk()).byteSize() > 0) {
                assertThat(chunkSegment.byteSize()).isLessThanOrEqualTo(in.currentBufferSize());
                assertThat(chunkSegment.byteSize()).isLessThanOrEqualTo(chunk);
                assertThat(chunkSegment.isNative()).as("chunks are views into native stream buffers").isTrue();
                assertSegmentMatches(chunkSegment, consumed);
                consumed += chunkSegment.byteSize();
                chunks++;
                assertThat(in.position()).isEqualTo(consumed);
            }

            assertThat(chunks).isGreaterThan(1);
            assertThat(consumed).isEqualTo(size);
            assertThat(in.bytesRead()).isEqualTo(size);
            assertThat(in.position()).isEqualTo(size);
            assertThat(in.eof()).isTrue();
            assertThat(in.readChunk().byteSize()).as("EOF chunk is empty").isZero();
        }
    }

    @Test
    void chunkViewStaysValidUntilNextStreamCallAndNextChunkContinues(@TempDir Path dir) throws Exception {
        int chunk = 8192;
        int size = 5 * chunk + 11;
        Path path = createPatternFile(dir.resolve("chunk-view.bin"), size);
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(chunk)
                .withAlignment(BLOCK)
                .withReadAhead(1);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options)) {
            MemorySegment first = in.readChunk();
            int firstLength = (int) first.byteSize();
            assertThat(firstLength).isGreaterThan(0);
            // Copy while the view is still owned by us (callers must copy to keep data).
            byte[] firstCopy = bytesOf(first);
            assertArrayEquals(patternBytes(0, firstLength), firstCopy);

            // The next stream call invalidates `first`, and must continue at the right offset.
            MemorySegment second = in.readChunk();
            int secondLength = (int) second.byteSize();
            assertThat(secondLength).isGreaterThan(0);
            assertArrayEquals(patternBytes(firstLength, secondLength), bytesOf(second));
            assertThat(in.position()).isEqualTo((long) firstLength + secondLength);
        }
    }

    // ==================================================================
    // 4. offset / length window
    // ==================================================================

    @Test
    void windowWithUnalignedOffsetReturnsExactlyThatRange(@TempDir Path dir) throws Exception {
        int size = 256 * KIB;
        Path path = createPatternFile(dir.resolve("window.bin"), size);
        long offset = 1234;
        int length = 100_000;
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(BLOCK)
                .withAlignment(BLOCK)
                .withReadAhead(1);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, offset, length, options)) {
            assertThat(in.position()).isEqualTo(offset);
            assertThat(in.remaining()).isEqualTo(length);

            long consumed = 0;
            MemorySegment chunk;
            while ((chunk = in.readChunk()).byteSize() > 0) {
                assertThat(chunk.byteSize()).isLessThanOrEqualTo(BLOCK);
                assertSegmentMatches(chunk, offset + consumed);
                consumed += chunk.byteSize();
                assertThat(in.position()).isEqualTo(offset + consumed);
                assertThat(in.remaining()).isEqualTo(length - consumed);
            }

            assertThat(consumed).isEqualTo(length);
            assertThat(in.position()).isEqualTo(offset + length);
            assertThat(in.remaining()).isZero();
            assertThat(in.bytesRead()).isEqualTo(length);
            assertThat(in.eof()).isTrue();
            assertThat(in.readChunk().byteSize()).isZero();
        }
    }

    @Test
    void windowLengthPastEofClampsAtEof(@TempDir Path dir) throws Exception {
        int size = 64 * KIB;
        Path path = createPatternFile(dir.resolve("window-eof.bin"), size);
        long offset = 60_000;
        int length = 100_000;

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, offset, length,
                     InputStreamOptions.defaults().withBufferSize(BLOCK).withAlignment(BLOCK))) {
            assertThat(in.position()).isEqualTo(offset);
            assertThat(in.remaining()).as("bytes left in the declared window").isEqualTo(length);

            byte[] actual = drain(in);
            assertArrayEquals(patternBytes(offset, size - (int) offset), actual,
                    "window must clamp at EOF");
            assertThat(in.position()).isEqualTo(size);
            assertThat(in.eof()).isTrue();
            assertThat(in.readChunk().byteSize()).isZero();
        }
    }

    @Test
    void windowLengthZeroIsEmpty(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("window-zero.bin"), 64 * KIB);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, 1000, 0,
                     InputStreamOptions.defaults());
             Arena arena = Arena.ofConfined()) {
            assertThat(in.position()).isEqualTo(1000);
            assertThat(in.remaining()).isZero();
            assertThat(in.readChunk().byteSize()).isZero();
            assertThat(in.read(arena.allocate(BLOCK))).isZero();
            assertThat(in.bytesRead()).isZero();
        }
    }

    @Test
    void untilEofWindowReportsMinusOneRemaining(@TempDir Path dir) throws Exception {
        int size = 128 * KIB;
        Path path = createPatternFile(dir.resolve("window-until-eof.bin"), size);
        long offset = 1234;

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, offset, -1,
                     InputStreamOptions.defaults().withBufferSize(BLOCK).withAlignment(BLOCK))) {
            assertThat(in.position()).isEqualTo(offset);
            assertThat(in.remaining()).as("until-EOF streams report -1").isEqualTo(-1);

            byte[] actual = drain(in);
            assertArrayEquals(patternBytes(offset, size - (int) offset), actual);
            assertThat(in.position()).isEqualTo(size);
            assertThat(in.remaining()).as("until-EOF streams stay at -1").isEqualTo(-1);
            assertThat(in.eof()).isTrue();
        }
    }

    @Test
    void defaultOpenReadsFromZeroUntilEof(@TempDir Path dir) throws Exception {
        int size = 32 * KIB + 17;
        Path path = createPatternFile(dir.resolve("default-open.bin"), size);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file)) {
            assertThat(in.position()).isZero();
            assertThat(in.remaining()).isEqualTo(-1);
            assertArrayEquals(patternBytes(0, size), drain(in));
            assertThat(in.eof()).isTrue();
        }
    }

    // ==================================================================
    // 5. skip
    // ==================================================================

    @Test
    void skipWithinAndAcrossChunksThenReadMatchesNewPosition(@TempDir Path dir) throws Exception {
        int chunk = 8192;
        int size = 128 * KIB;
        Path path = createPatternFile(dir.resolve("skip.bin"), size);
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(chunk)
                .withAlignment(BLOCK)
                .withReadAhead(2);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options);
             Arena arena = Arena.ofConfined()) {
            assertThat(in.skip(0)).as("skip(0) is a no-op").isZero();
            assertThat(in.position()).isZero();

            // Inside the (prefetched) front chunk.
            assertThat(in.skip(5000)).isEqualTo(5000);
            assertThat(in.position()).isEqualTo(5000);

            MemorySegment destination = arena.allocate(BLOCK);
            assertThat(in.read(destination)).isEqualTo(BLOCK);
            assertSegmentMatches(destination, 5000);
            assertThat(in.position()).isEqualTo(5000 + BLOCK);

            // Across multiple chunk boundaries.
            assertThat(in.skip(20_000)).isEqualTo(20_000);
            assertThat(in.position()).isEqualTo(29_096);

            MemorySegment chunkSegment = in.readChunk();
            assertThat(chunkSegment.byteSize()).isGreaterThan(0);
            assertSegmentMatches(chunkSegment, 29_096);
            assertThat(in.position()).isEqualTo(29_096 + chunkSegment.byteSize());

            // Skip past EOF on an until-EOF stream clamps at the first observed EOF.
            long remaining = size - in.position();
            assertThat(in.skip(1L << 30)).isEqualTo(remaining);
            assertThat(in.position()).isEqualTo(size);
            assertThat(in.readChunk().byteSize()).isZero();
            assertThat(in.read(destination)).isZero();
        }
    }

    @Test
    void skipPastDeclaredWindowClampsAtWindowEnd(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("skip-window.bin"), 128 * KIB);
        InputStreamOptions options = InputStreamOptions.defaults().withBufferSize(BLOCK).withAlignment(BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, 1000, 5000, options)) {
            assertThat(in.skip(100_000)).as("skip past the declared window clamps").isEqualTo(5000);
            assertThat(in.position()).isEqualTo(6000);
            assertThat(in.remaining()).isZero();
            assertThat(in.readChunk().byteSize()).isZero();
        }
    }

    // ==================================================================
    // 6. write-size matrix + flush/continue
    // ==================================================================

    @Test
    void writeSizeMatrixProducesExactContentAndLength(@TempDir Path dir) throws Exception {
        int[] sizes = {1, BLOCK - 1, 8191, 8192, 8193, 4 * MIB + 3};
        for (int size : sizes) {
            Path path = dir.resolve("write-matrix-" + size + ".bin");
            try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
                try (JuringOutputStream out = JuringOutputStream.open(file)) {
                    if (size == 8193) {
                        // byte[] overload, written from a non-zero array offset.
                        byte[] source = patternBytes(1000, size);
                        byte[] wrapped = new byte[size + 9];
                        System.arraycopy(source, 0, wrapped, 4, size);
                        out.write(wrapped, 4, size);
                    } else {
                        try (Arena arena = Arena.ofConfined()) {
                            MemorySegment source = arena.allocate(size, BLOCK);
                            fillPattern(source, 1000, size);
                            out.write(source);
                        }
                    }
                    assertThat(out.position()).as("position for size " + size).isEqualTo(size);
                    assertThat(out.bytesWritten()).as("bytesWritten for size " + size).isEqualTo(size);
                }
                assertThat(file.length()).as("file length for size " + size).isEqualTo(size);
            }
            assertThat(Files.size(path)).as("filesystem length for size " + size).isEqualTo(size);
            assertArrayEquals(patternBytes(1000, size), Files.readAllBytes(path),
                    "content mismatch for size " + size);
        }
    }

    @Test
    void flushMidStreamAtAlignedPositionThenContinueWriting(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("flush-continue-aligned.bin");
        int chunk = 8192;
        OutputStreamOptions options = OutputStreamOptions.defaults()
                .withBufferSize(chunk)
                .withAlignment(BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            try (JuringOutputStream out = JuringOutputStream.open(file, options)) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment source = arena.allocate(3 * chunk + 1000, BLOCK);
                    fillPattern(source, 0, (int) source.byteSize());

                    // Write exactly N whole buffers, flush (no partial buffer, position is
                    // alignment-aligned) and keep writing: must be legal per the frozen contract.
                    out.write(source.asSlice(0, chunk));
                    assertThat(out.position()).isEqualTo(chunk);
                    out.flush();
                    assertThat(Files.size(path)).as("aligned flush exposes the logical length")
                            .isEqualTo(chunk);

                    out.write(source.asSlice(chunk, chunk));
                    assertThat(out.position()).isEqualTo(2L * chunk);
                    out.flush();
                    assertThat(Files.size(path)).isEqualTo(2L * chunk);

                    out.write(source.asSlice(2L * chunk, chunk + 1000));
                    assertThat(out.position()).isEqualTo(3L * chunk + 1000);
                }
            }
            assertThat(file.length()).isEqualTo(3L * chunk + 1000);
        }
        assertThat(Files.size(path)).isEqualTo(3L * chunk + 1000);
        assertArrayEquals(patternBytes(0, 3 * chunk + 1000), Files.readAllBytes(path));
    }

    @Test
    void writeAfterPaddedUnalignedFlushThrowsIllegalState(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("flush-continue-unaligned.bin");
        int chunk = 8192;
        OutputStreamOptions options = OutputStreamOptions.defaults()
                .withBufferSize(chunk)
                .withAlignment(BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            JuringOutputStream out = JuringOutputStream.open(file, options);
            try {
                out.write(patternBytes(0, 100), 0, 100);
                out.flush(); // padded flush at unaligned position 100
                assertThat(Files.size(path)).as("alignment padding must be truncated away")
                        .isEqualTo(100);

                // A padded flush leaves an unaligned position; O_DIRECT cannot restart there.
                assertThrows(IllegalStateException.class,
                        () -> out.write(patternBytes(100, 100), 0, 100));
            } finally {
                out.close();
            }
            assertThat(file.isOpen()).isTrue();
            assertThat(file.length()).isEqualTo(100);
        }
        assertThat(Files.size(path)).isEqualTo(100);
        assertArrayEquals(patternBytes(0, 100), Files.readAllBytes(path));
    }

    @Test
    void positionAndBytesWrittenTrackAcceptedBytesBeforeFlush(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("position.bin");
        OutputStreamOptions options = OutputStreamOptions.defaults()
                .withBufferSize(8192)
                .withAlignment(BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            try (JuringOutputStream out = JuringOutputStream.open(file, options)) {
                out.write(patternBytes(0, 100), 0, 100);
                assertThat(out.position()).isEqualTo(100);
                assertThat(out.bytesWritten()).isEqualTo(100);

                out.write(patternBytes(100, 8100), 0, 8100);
                assertThat(out.position()).isEqualTo(8200);
                assertThat(out.bytesWritten()).isEqualTo(8200);

                out.write(patternBytes(8200, 1), 0, 1);
                assertThat(out.position()).isEqualTo(8201);
                out.flush();
                assertThat(Files.size(path)).isEqualTo(8201);
            }
        }
        assertArrayEquals(patternBytes(0, 8201), Files.readAllBytes(path));
    }

    // ==================================================================
    // 7. write-behind + alignment padding leaves no tail
    // ==================================================================

    @Test
    void writeBehindDeeperThanWindowKeepsContentExact(@TempDir Path dir) throws Exception {
        int chunk = 8192;
        int writeBehind = 2;
        int total = 5 * chunk + 123; // > (writeBehind + 1) buffers, no intermediate flush
        Path path = dir.resolve("write-behind.bin");
        OutputStreamOptions options = OutputStreamOptions.defaults()
                .withBufferSize(chunk)
                .withWriteBehind(writeBehind)
                .withAlignment(BLOCK);

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            try (JuringOutputStream out = JuringOutputStream.open(file, options)) {
                assertThat(out.writeBehind()).isEqualTo(writeBehind);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment source = arena.allocate(total, BLOCK);
                    fillPattern(source, 0, total);
                    for (int offset = 0; offset < total; offset += 1000) {
                        int length = Math.min(1000, total - offset);
                        out.write(source.asSlice(offset, length));
                    }
                }
                assertThat(out.position()).as("position tracks accepted bytes with no flush")
                        .isEqualTo(total);
            }
            assertThat(file.length()).isEqualTo(total);
        }
        assertThat(Files.size(path)).isEqualTo(total);
        assertArrayEquals(patternBytes(0, total), Files.readAllBytes(path));
    }

    @Test
    void finalAlignmentPaddingIsTruncatedAwayOverLargerFile(@TempDir Path dir) throws Exception {
        int aligned = OutputStreamOptions.DEFAULT_BUFFER_SIZE; // 65536
        int originalSize = 3 * aligned;
        int written = aligned + 100;
        Path path = createPatternFile(dir.resolve("padding-tail.bin"), originalSize);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            try (JuringOutputStream out = JuringOutputStream.open(file)) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment source = arena.allocate(written, BLOCK);
                    fillPattern(source, 0, written);
                    out.write(source);
                }
                out.flush();
                assertThat(Files.size(path)).as("padding written for alignment must be truncated away")
                        .isEqualTo(written);
            }
            assertThat(file.length()).isEqualTo(written);
        }
        assertArrayEquals(patternBytes(0, written), Files.readAllBytes(path),
                "only the logical bytes may be visible after flush");
    }

    @Test
    void fullBuffersOverLargerFileLeaveTailUntouched(@TempDir Path dir) throws Exception {
        int aligned = OutputStreamOptions.DEFAULT_BUFFER_SIZE; // 65536
        int originalSize = 3 * aligned;
        int written = 2 * aligned; // whole buffers only: no partial buffer, so no padding/truncate
        Path path = createPatternFile(dir.resolve("tail-untouched.bin"), originalSize);

        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            try (JuringOutputStream out = JuringOutputStream.open(file)) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment source = arena.allocate(written, BLOCK);
                    fillPattern(source, 0, written);
                    out.write(source);
                }
                out.flush();
                assertThat(Files.size(path))
                        .as("no alignment padding was needed, so the tail must stay untouched")
                        .isEqualTo(originalSize);
            }
            assertThat(file.length()).isEqualTo(originalSize);
        }
        byte[] actual = Files.readAllBytes(path);
        assertArrayEquals(patternBytes(0, written), Arrays.copyOfRange(actual, 0, written),
                "written prefix mismatch");
        assertArrayEquals(patternBytes(written, aligned), Arrays.copyOfRange(actual, written, originalSize),
                "untouched tail mismatch");
    }

    @Test
    void closeWithoutFlushTruncatesPaddingAndDrains(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("close-truncate.bin");

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            JuringOutputStream out = JuringOutputStream.open(file);
            out.write(patternBytes(0, 100), 0, 100);
            assertThat(out.position()).isEqualTo(100);
            out.close();

            assertThat(ring.inFlightCount()).as("close drains every stream-owned operation").isZero();
            assertThat(file.isOpen()).as("stream close must not close the JuringFile").isTrue();
            assertThat(file.length()).isEqualTo(100);
        }
        assertThat(Files.size(path)).isEqualTo(100);
        assertArrayEquals(patternBytes(0, 100), Files.readAllBytes(path));
    }

    @Test
    void closeReleasesBufferMemory(@TempDir Path dir) throws Exception {
        Path inputPath = createPatternFile(dir.resolve("released-input.bin"), 32 * KIB);
        JuringInputStream in = null;
        try (JuringFile file = JuringFile.open(ring, inputPath, READ)) {
            in = JuringInputStream.open(file,
                    InputStreamOptions.defaults().withBufferSize(BLOCK).withReadAhead(1));
            assertThat(in.bufferMemoryBytes()).as("input buffers are allocated while open")
                    .isGreaterThan(0);
            in.close();
            assertThat(in.bufferMemoryBytes())
                    .as("close() releases the input buffers, so bufferMemoryBytes() must report 0")
                    .isZero();
        } finally {
            if (in != null) {
                in.close();
            }
        }

        Path outputPath = dir.resolve("released-output.bin");
        JuringOutputStream out = null;
        try (JuringFile file = JuringFile.open(ring, outputPath, RDWR, CREATE, TRUNCATE)) {
            out = JuringOutputStream.open(file,
                    OutputStreamOptions.defaults().withBufferSize(BLOCK).withWriteBehind(1));
            assertThat(out.bufferMemoryBytes()).as("output buffers are allocated while open")
                    .isGreaterThan(0);
            out.write(patternBytes(0, 100), 0, 100);
            out.close();
            assertThat(out.bufferMemoryBytes())
                    .as("close() releases the output buffers, so bufferMemoryBytes() must report 0")
                    .isZero();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    // ==================================================================
    // 8. options: defaults, immutability, validation, dynamic adjustments
    // ==================================================================

    @Test
    void inputStreamOptionsDefaultsAndImmutability() {
        InputStreamOptions defaults = InputStreamOptions.defaults();
        assertThat(InputStreamOptions.DEFAULT_BUFFER_SIZE).isEqualTo(8192);
        assertThat(InputStreamOptions.DEFAULT_ALIGNMENT).isEqualTo(4096);
        assertThat(defaults.bufferSize()).isEqualTo(8192);
        assertThat(defaults.readAhead()).isZero();
        assertThat(defaults.alignment()).isEqualTo(4096);
        assertThat(defaults.dynamicAdjustments()).isFalse();

        InputStreamOptions custom = defaults.withBufferSize(16 * KIB)
                .withReadAhead(3)
                .withAlignment(512)
                .withDynamicAdjustments(true);
        assertThat(custom.bufferSize()).isEqualTo(16 * KIB);
        assertThat(custom.readAhead()).isEqualTo(3);
        assertThat(custom.alignment()).isEqualTo(512);
        assertThat(custom.dynamicAdjustments()).isTrue();

        // with* must not mutate the receiver.
        assertThat(defaults.bufferSize()).isEqualTo(8192);
        assertThat(defaults.readAhead()).isZero();
        assertThat(defaults.alignment()).isEqualTo(4096);
        assertThat(defaults.dynamicAdjustments()).isFalse();

        assertThat(JuringInputStream.DEFAULT_CLOSE_TIMEOUT_MILLIS).isEqualTo(2000L);
    }

    @Test
    void inputStreamOptionsValidation() {
        InputStreamOptions defaults = InputStreamOptions.defaults();

        assertThrows(IllegalArgumentException.class, () -> defaults.withBufferSize(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withBufferSize(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withReadAhead(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withReadAhead(1025));

        // Valid boundary values.
        assertThat(defaults.withReadAhead(0).readAhead()).isZero();
        assertThat(defaults.withReadAhead(1024).readAhead()).isEqualTo(1024);
        assertThat(defaults.withBufferSize(1).bufferSize()).isEqualTo(1);

        for (int invalid : new int[]{0, -1, 3, 5000, 1 << 21, 1 << 22}) {
            assertThrows(IllegalArgumentException.class, () -> defaults.withAlignment(invalid),
                    "alignment " + invalid + " must be rejected");
        }
        assertThat(defaults.withAlignment(1).alignment()).isEqualTo(1);
        assertThat(defaults.withAlignment(1 << 20).alignment()).isEqualTo(1 << 20);
        assertThat(defaults.withAlignment(BLOCK).alignment()).isEqualTo(BLOCK);
    }

    @Test
    void outputStreamOptionsDefaultsAndImmutability() {
        OutputStreamOptions defaults = OutputStreamOptions.defaults();
        assertThat(OutputStreamOptions.DEFAULT_BUFFER_SIZE).isEqualTo(65536);
        assertThat(OutputStreamOptions.DEFAULT_ALIGNMENT).isEqualTo(4096);
        assertThat(defaults.bufferSize()).isEqualTo(65536);
        assertThat(defaults.writeBehind()).isEqualTo(1);
        assertThat(defaults.alignment()).isEqualTo(4096);
        assertThat(defaults.preallocationSize()).isZero();
        assertThat(defaults.syncOnClose()).isFalse();

        OutputStreamOptions custom = defaults.withBufferSize(32 * KIB)
                .withWriteBehind(4)
                .withAlignment(1024)
                .withPreallocationSize(8 * MIB)
                .withSyncOnClose(true);
        assertThat(custom.bufferSize()).isEqualTo(32 * KIB);
        assertThat(custom.writeBehind()).isEqualTo(4);
        assertThat(custom.alignment()).isEqualTo(1024);
        assertThat(custom.preallocationSize()).isEqualTo(8L * MIB);
        assertThat(custom.syncOnClose()).isTrue();

        assertThat(defaults.bufferSize()).isEqualTo(65536);
        assertThat(defaults.writeBehind()).isEqualTo(1);
        assertThat(defaults.alignment()).isEqualTo(4096);
        assertThat(defaults.preallocationSize()).isZero();
        assertThat(defaults.syncOnClose()).isFalse();

        assertThat(JuringOutputStream.DEFAULT_CLOSE_TIMEOUT_MILLIS).isEqualTo(2000L);
    }

    @Test
    void outputStreamOptionsValidation() {
        OutputStreamOptions defaults = OutputStreamOptions.defaults();

        assertThrows(IllegalArgumentException.class, () -> defaults.withBufferSize(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withBufferSize(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withWriteBehind(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withWriteBehind(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withWriteBehind(1025));
        assertThrows(IllegalArgumentException.class, () -> defaults.withPreallocationSize(-1));

        assertThat(defaults.withWriteBehind(1).writeBehind()).isEqualTo(1);
        assertThat(defaults.withWriteBehind(1024).writeBehind()).isEqualTo(1024);
        assertThat(defaults.withBufferSize(1).bufferSize()).isEqualTo(1);
        assertThat(defaults.withPreallocationSize(0).preallocationSize()).isZero();

        for (int invalid : new int[]{0, -1, 3, 5000, 1 << 21}) {
            assertThrows(IllegalArgumentException.class, () -> defaults.withAlignment(invalid),
                    "alignment " + invalid + " must be rejected");
        }
        assertThat(defaults.withAlignment(1 << 20).alignment()).isEqualTo(1 << 20);
        assertThat(defaults.withAlignment(BLOCK).alignment()).isEqualTo(BLOCK);
    }

    @Test
    void nullOptionObjectsThrowNullPointerException(@TempDir Path dir) throws Exception {
        Path readPath = createPatternFile(dir.resolve("null-options-read.bin"), BLOCK);
        try (JuringFile file = JuringFile.open(ring, readPath, READ)) {
            assertThrows(NullPointerException.class,
                    () -> JuringInputStream.open(file, (InputStreamOptions) null));
            assertThrows(NullPointerException.class,
                    () -> JuringInputStream.open(file, 0, BLOCK, (InputStreamOptions) null));
        }

        Path writePath = dir.resolve("null-options-write.bin");
        try (JuringFile file = JuringFile.open(ring, writePath, RDWR, CREATE, TRUNCATE)) {
            assertThrows(NullPointerException.class,
                    () -> JuringOutputStream.open(file, (OutputStreamOptions) null));
        }
    }

    @Test
    void dynamicAdjustmentsKeepFunctionalParityAndStayBounded(@TempDir Path dir) throws Exception {
        int size = MIB;
        Path path = createPatternFile(dir.resolve("dynamic.bin"), size);
        InputStreamOptions adaptive = InputStreamOptions.defaults()
                .withBufferSize(32 * KIB)
                .withAlignment(BLOCK)
                .withReadAhead(2)
                .withDynamicAdjustments(true);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, adaptive)) {
            assertThat(in.bufferSize()).isEqualTo(32 * KIB);
            assertThat(in.readAhead()).isEqualTo(2);
            assertThat(in.currentBufferSize())
                    .as("adaptive slow start never exceeds the configured buffer size")
                    .isBetween(1, 32 * KIB);
            assertThat(in.currentReadAhead()).isBetween(0, 2);

            long memoryBound = (2L + 1) * (alignUp(32 * KIB, BLOCK) + BLOCK);
            assertThat(in.bufferMemoryBytes()).isLessThanOrEqualTo(memoryBound);

            ByteArrayOutputStream collected = new ByteArrayOutputStream(size);
            MemorySegment chunk;
            while ((chunk = in.readChunk()).byteSize() > 0) {
                collected.write(bytesOf(chunk));
                assertThat(in.currentBufferSize()).isBetween(1, 32 * KIB);
                assertThat(in.currentReadAhead()).isBetween(0, 2);
                assertThat(in.bufferMemoryBytes()).isLessThanOrEqualTo(memoryBound);
            }
            assertArrayEquals(patternBytes(0, size), collected.toByteArray(),
                    "adaptive readahead must not change the bytes returned");
        }

        // With dynamic adjustments off the current values equal the configured maxima.
        InputStreamOptions fixed = InputStreamOptions.defaults().withBufferSize(8192).withReadAhead(1);
        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, fixed)) {
            assertThat(in.currentBufferSize()).isEqualTo(in.bufferSize());
            assertThat(in.currentReadAhead()).isEqualTo(in.readAhead());
        }
    }

    // ==================================================================
    // 9. lifecycle / typed errors / close with in-flight work
    // ==================================================================

    @Test
    void inputUseAfterCloseThrowsIllegalStateAndDoubleCloseIsNoOp(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("input-close.bin"), 64 * KIB);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             Arena arena = Arena.ofConfined()) {
            JuringInputStream in = JuringInputStream.open(file);
            in.readChunk();
            in.close();

            MemorySegment destination = arena.allocate(BLOCK);
            assertThrows(IllegalStateException.class, in::readChunk);
            assertThrows(IllegalStateException.class, () -> in.read(destination));
            assertThrows(IllegalStateException.class, () -> in.read(new byte[16], 0, 16));
            assertThrows(IllegalStateException.class, () -> in.skip(1));
            // close() is idempotent
            assertDoesNotThrow(in::close);
        }
        assertThat(ring.inFlightCount()).isZero();
    }

    @Test
    void outputUseAfterCloseThrowsIllegalStateAndDoubleCloseIsNoOp(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("output-close.bin");

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined()) {
            JuringOutputStream out = JuringOutputStream.open(file);
            out.write(patternBytes(0, 16), 0, 16);
            out.close();

            MemorySegment source = arena.allocate(16, BLOCK);
            fillPattern(source, 0, 16);
            assertThrows(IllegalStateException.class, () -> out.write(source));
            assertThrows(IllegalStateException.class, () -> out.write(new byte[16], 0, 16));
            assertThrows(IllegalStateException.class, out::flush);
            assertDoesNotThrow(out::close);

            assertThat(file.isOpen()).isTrue();
            assertThat(file.length()).isEqualTo(16);
        }
    }

    @Test
    void closeWithPrefetchedReadsDoesNotHangAndDrains(@TempDir Path dir) throws Exception {
        int size = 8 * MIB;
        Path path = createPatternFile(dir.resolve("close-inflight-read.bin"), size);
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(8192)
                .withAlignment(BLOCK)
                .withReadAhead(4);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            // Open mid-file through the window overload, then leave prefetches in flight.
            JuringInputStream in = JuringInputStream.open(file, BLOCK, -1, options);
            try {
                assertThat(in.readChunk().byteSize()).isGreaterThan(0);
                assertThat(in.readChunk().byteSize()).isGreaterThan(0);

                long start = System.nanoTime();
                in.close();
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

                assertThat(elapsedMillis).as("close() with in-flight reads must not hang")
                        .isLessThan(1500);
                assertThat(ring.inFlightCount()).as("close() drains stream-owned operations").isZero();
                assertThat(file.isOpen()).as("stream close must not close the JuringFile").isTrue();
                assertThrows(IllegalStateException.class, in::readChunk);
            } finally {
                try {
                    in.close();
                } catch (JuringException ignored) {
                    // already closed / drained
                }
            }
        }
        assertThat(ring.inFlightCount()).isZero();
    }

    @Test
    void readFromWriteOnlyFileThrowsTypedEbadf(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("write-only-read.bin");

        try (JuringFile file = JuringFile.open(ring, path, WRITE, CREATE, TRUNCATE)) {
            JuringException failure = assertThrows(JuringException.class, () -> {
                try (JuringInputStream in = JuringInputStream.open(file)) {
                    in.readChunk();
                }
            });
            assertThat(failure.errno()).isEqualTo(JuringException.EBADF);
            assertThat(failure.isBadFileDescriptor()).isTrue();
        }
    }

    @Test
    void writeToReadOnlyFileThrowsTypedEbadfAndLatches(@TempDir Path dir) throws Exception {
        Path path = createPatternFile(dir.resolve("read-only-write.bin"), 64 * KIB);

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            JuringOutputStream out = null;
            try {
                out = JuringOutputStream.open(file);
                JuringOutputStream stream = out;
                JuringException failure = assertThrows(JuringException.class, () -> {
                    stream.write(new byte[]{1, 2, 3}, 0, 3);
                    stream.flush();
                });
                assertThat(failure.errno()).isEqualTo(JuringException.EBADF);

                // After an I/O error the first failure is latched and rethrown.
                JuringException latched = assertThrows(JuringException.class, stream::close);
                assertThat(latched.errno()).isEqualTo(JuringException.EBADF);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (JuringException ignored) {
                        // latched failure already asserted
                    }
                }
            }
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** Deterministic per-file-offset content pattern. */
    private static byte pattern(long offset) {
        return (byte) ((offset * 31 + (offset >>> 12) + 7) & 0xFF);
    }

    private static byte[] patternBytes(long offset, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = pattern(offset + i);
        }
        return bytes;
    }

    private static void fillPattern(MemorySegment segment, long fileOffset, int length) {
        for (int i = 0; i < length; i++) {
            segment.set(JAVA_BYTE, i, pattern(fileOffset + i));
        }
    }

    private static byte[] bytesOf(MemorySegment segment) {
        int length = (int) segment.byteSize();
        byte[] bytes = new byte[length];
        MemorySegment.copy(segment, 0, MemorySegment.ofArray(bytes), 0, length);
        return bytes;
    }

    private static void assertSegmentMatches(MemorySegment segment, long fileOffset) {
        assertArrayEquals(patternBytes(fileOffset, (int) segment.byteSize()), bytesOf(segment),
                "content mismatch at stream offset " + fileOffset);
    }

    private static byte[] drain(JuringInputStream in) throws JuringException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        MemorySegment chunk;
        while ((chunk = in.readChunk()).byteSize() > 0) {
            collected.writeBytes(bytesOf(chunk));
        }
        return collected.toByteArray();
    }

    private static int alignUp(int value, int alignment) {
        return (int) (((long) value + alignment - 1) / alignment * alignment);
    }

    private static Path createPatternFile(Path path, int size) throws IOException {
        Files.write(path, patternBytes(0, size));
        return path;
    }
}
