package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DSYNC;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.SYNC;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stream durability tests (frozen contract).
 *
 * <p>Every test uses an isolated {@link JUring} and a {@code @TempDir} file. The sync-count
 * claims are proven with <strong>two independent observables</strong>:
 * {@link JuringOutputStream#syncs()} and the {@link JuringFile#syncOperations()}/
 * {@link JuringFile#syncCompletions()} deltas, with {@link JuringFile#issuedOperations()}
 * as the ordering cross-check. {@code @TempDir} is tmpfs, so the visibility assertions prove
 * page-cache visibility across a fresh open, not media durability (disclosed in the stream
 * durability report/status).
 */
class StreamDurabilityTest {

    private static final int BLOCK = 4096;
    private static final int KIB = 1024;

    private JUring ring;

    @BeforeEach
    void setUp() {
        ring = new JUring(256, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close();
            ring = null;
        }
    }

    // ==================================================================
    // option value objects
    // ==================================================================

    @Test
    void outputOptionsNewDefaultsAndRoundTrips() {
        OutputStreamOptions defaults = OutputStreamOptions.defaults();
        assertThat(defaults.durability()).as("default durability").isEqualTo(DurabilityMode.WRITEBACK);
        assertThat(defaults.fadvise()).as("default fadvise").isEqualTo(FadviseAdvice.NORMAL);
        assertThat(defaults.syncOnClose()).isFalse();

        OutputStreamOptions custom = defaults
                .withDurabilityMode(DurabilityMode.FSYNC)
                .withFadvise(FadviseAdvice.SEQUENTIAL);
        assertThat(custom.durability()).isEqualTo(DurabilityMode.FSYNC);
        assertThat(custom.fadvise()).isEqualTo(FadviseAdvice.SEQUENTIAL);

        // immutable: the source object is unchanged
        assertThat(defaults.durability()).isEqualTo(DurabilityMode.WRITEBACK);
        assertThat(defaults.fadvise()).isEqualTo(FadviseAdvice.NORMAL);

        // the other with*() copies preserve the new fields
        assertThat(custom.withBufferSize(2 * KIB).durability()).isEqualTo(DurabilityMode.FSYNC);
        assertThat(custom.withBufferSize(2 * KIB).fadvise()).isEqualTo(FadviseAdvice.SEQUENTIAL);
        assertThat(custom.withWriteBehind(4).durability()).isEqualTo(DurabilityMode.FSYNC);
        assertThat(custom.withAlignment(BLOCK).fadvise()).isEqualTo(FadviseAdvice.SEQUENTIAL);
        assertThat(custom.withPreallocationSize(BLOCK).durability()).isEqualTo(DurabilityMode.FSYNC);
        assertThat(custom.withSyncOnClose(true).durability()).isEqualTo(DurabilityMode.FSYNC);
        assertThat(custom.withSyncOnClose(true).syncOnClose()).isTrue();

        // value semantics include both new fields
        OutputStreamOptions equal = OutputStreamOptions.defaults()
                .withFadvise(FadviseAdvice.SEQUENTIAL)
                .withDurabilityMode(DurabilityMode.FSYNC);
        assertThat(custom).isEqualTo(equal);
        assertThat(custom.hashCode()).isEqualTo(equal.hashCode());
        assertThat(custom).isNotEqualTo(defaults.withDurabilityMode(DurabilityMode.FSYNC));
        assertThat(custom).isNotEqualTo(defaults.withFadvise(FadviseAdvice.SEQUENTIAL));
        assertThat(custom.toString())
                .contains("durability=FSYNC")
                .contains("fadvise=SEQUENTIAL");

        assertThatThrownBy(() -> defaults.withDurabilityMode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> defaults.withFadvise(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void inputOptionsFadviseDefaultsAndRoundTrips() {
        InputStreamOptions defaults = InputStreamOptions.defaults();
        assertThat(defaults.fadvise()).as("default fadvise").isEqualTo(FadviseAdvice.NORMAL);

        InputStreamOptions custom = defaults
                .withFadvise(FadviseAdvice.RANDOM)
                .withBufferSize(2 * KIB)
                .withReadAhead(2)
                .withAlignment(BLOCK)
                .withDynamicAdjustments(true);
        assertThat(custom.fadvise()).isEqualTo(FadviseAdvice.RANDOM);
        assertThat(defaults.fadvise()).isEqualTo(FadviseAdvice.NORMAL);

        InputStreamOptions equal = InputStreamOptions.defaults()
                .withBufferSize(2 * KIB)
                .withReadAhead(2)
                .withAlignment(BLOCK)
                .withDynamicAdjustments(true)
                .withFadvise(FadviseAdvice.RANDOM);
        assertThat(custom).isEqualTo(equal);
        assertThat(custom.hashCode()).isEqualTo(equal.hashCode());
        assertThat(custom).isNotEqualTo(equal.withFadvise(FadviseAdvice.NORMAL));
        assertThat(custom.toString()).contains("fadvise=RANDOM");

        assertThatThrownBy(() -> defaults.withFadvise(null)).isInstanceOf(NullPointerException.class);
    }

    // ==================================================================
    // DSYNC assertion at open
    // ==================================================================

    @Test
    void dsyncModeOnNonDsyncFileFailsAtOpenBeforeAnySideEffect(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("dsync-mismatch.bin");
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            assertThat(file.durability()).isEqualTo(DurabilityMode.WRITEBACK);
            OutputStreamOptions options = OutputStreamOptions.defaults()
                    .withDurabilityMode(DurabilityMode.DSYNC)
                    .withPreallocationSize(BLOCK); // must not run when the assertion fails

            assertThatThrownBy(() -> JuringOutputStream.open(file, options))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DSYNC")
                    .hasMessageContaining("WRITEBACK");

            // the DSYNC assertion is checked before preallocation/fadvise: no ring op, no write
            assertThat(file.isOpen()).isTrue();
            assertThat(file.issuedOperations()).isZero();
            assertThat(Files.size(path)).as("preallocation must not have run").isZero();
        }
    }

    @Test
    void dsyncStreamOnDsyncFileIssuesNoExplicitSync(@TempDir Path dir) throws Exception {
        assertDsyncStreamIssuesNoExplicitSync(dir, "dsync-flag.bin", DSYNC);
        assertDsyncStreamIssuesNoExplicitSync(dir, "dsync-sync-flag.bin", SYNC);
    }

    private void assertDsyncStreamIssuesNoExplicitSync(Path dir, String name, LinuxOpenOptions flag) throws Exception {
        Path path = dir.resolve(name);
        byte[] data = patternBytes(0, 2 * BLOCK);
        long syncOpsBefore;
        long syncCompBefore;
        long syncsAfterClose;
        try (JuringFile file = JuringFile.open(ring, path, 0644, RDWR, CREATE, TRUNCATE, flag)) {
            assertThat(file.durability()).isEqualTo(DurabilityMode.DSYNC);
            syncOpsBefore = file.syncOperations();
            syncCompBefore = file.syncCompletions();
            JuringOutputStream out = JuringOutputStream.open(file, OutputStreamOptions.defaults()
                    .withDurabilityMode(DurabilityMode.DSYNC)
                    .withSyncOnClose(true) // redundant for DSYNC: must not add a sync
                    .withFadvise(FadviseAdvice.SEQUENTIAL));
            assertThat(out.durability()).isEqualTo(DurabilityMode.DSYNC);
            out.write(data, 0, data.length);
            out.flush();
            assertThat(out.syncs()).as("DSYNC flush must not sync").isZero();
            out.write(data, 0, BLOCK);
            out.close();
            syncsAfterClose = out.syncs();
            assertThat(out.durability()).as("durability() readable after close").isEqualTo(DurabilityMode.DSYNC);
            assertThat(syncsAfterClose).as("DSYNC close must not sync").isZero();
            assertThat(file.isOpen()).as("stream close must not close the JuringFile").isTrue();
            assertThat(file.syncOperations() - syncOpsBefore).as("no fdatasync/fsync prepared").isZero();
            assertThat(file.syncCompletions() - syncCompBefore).isZero();
            assertThat(file.length()).isEqualTo(2 * BLOCK + BLOCK);
        }
        // kernel (O_DSYNC) durability + fresh open visibility
        byte[] expected = new byte[3 * BLOCK];
        System.arraycopy(data, 0, expected, 0, 2 * BLOCK);
        System.arraycopy(data, 0, expected, 2 * BLOCK, BLOCK);
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    // ==================================================================
    // WRITEBACK reproduces the frozen writeback behaviour exactly
    // ==================================================================

    @Test
    void writebackFlushAndCloseIssueNoSync(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("writeback.bin");
        byte[] data = patternBytes(0, 2 * BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            JuringOutputStream out = JuringOutputStream.open(file, OutputStreamOptions.defaults());
            assertThat(out.durability()).isEqualTo(DurabilityMode.WRITEBACK);
            out.write(data, 0, data.length);
            out.flush();
            assertThat(out.syncs()).isZero();
            out.close();
            assertThat(out.syncs()).as("syncs() valid after close").isZero();
            assertThat(file.syncOperations() - syncOpsBefore).isZero();
            assertThat(file.syncCompletions() - syncCompBefore).isZero();
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(data);
    }

    @Test
    void writebackSyncOnCloseSyncsExactlyOnceAtClose(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("writeback-sync-on-close.bin");
        byte[] data = patternBytes(0, 2 * BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            long issuedBefore;
            JuringOutputStream out = JuringOutputStream.open(file,
                    OutputStreamOptions.defaults().withSyncOnClose(true));
            out.write(data, 0, data.length);
            out.flush();
            assertThat(out.syncs()).as("WRITEBACK flush is drain-only").isZero();
            assertThat(file.syncOperations() - syncOpsBefore).isZero();
            issuedBefore = file.issuedOperations();
            out.close();
            assertThat(out.syncs()).as("one writeback fdatasync on close").isEqualTo(1);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(1);
            assertThat(file.issuedOperations() - issuedBefore).as("close issues exactly the sync").isEqualTo(1);
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(data);
    }

    @Test
    void writebackStreamMayBeOpenedOnDsyncFile(@TempDir Path dir) throws Exception {
        // A non-DSYNC mode on an O_DSYNC file is allowed (the implicit syncs are redundant).
        Path path = dir.resolve("writeback-on-dsync-file.bin");
        byte[] data = patternBytes(0, 2 * BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, 0644, RDWR, CREATE, TRUNCATE, DSYNC)) {
            long syncOpsBefore = file.syncOperations();
            try (JuringOutputStream out = JuringOutputStream.open(file)) {
                assertThat(out.durability()).isEqualTo(DurabilityMode.WRITEBACK);
                out.write(data, 0, data.length);
                out.flush();
                assertThat(out.syncs()).isZero();
            }
            assertThat(file.syncOperations() - syncOpsBefore).isZero();
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(data);
    }

    // ==================================================================
    // FDATASYNC / FSYNC durability points
    // ==================================================================

    @Test
    void fdatasyncStreamSyncsOncePerFlushAndOnceOnClose(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("fdatasync.bin");
        int size = 2 * BLOCK;
        byte[] data = patternBytes(0, size);
        byte[] tail = patternBytes(size, BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            long issuedBefore = file.issuedOperations();
            JuringOutputStream out = JuringOutputStream.open(file,
                    OutputStreamOptions.defaults()
                            .withBufferSize(BLOCK)
                            .withDurabilityMode(DurabilityMode.FDATASYNC));
            assertThat(out.durability()).isEqualTo(DurabilityMode.FDATASYNC);
            out.write(data, 0, data.length);
            assertThat(out.syncs()).as("no sync before the first durability point").isZero();

            long issuedBeforeFlush = file.issuedOperations();
            out.flush();
            assertThat(out.syncs()).as("one fdatasync per flush").isEqualTo(1);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(1);
            assertThat(file.issuedOperations() - issuedBeforeFlush)
                    .as("flush issues exactly the sync after the drained write").isEqualTo(1);

            // continuing to write after a durability flush at an aligned position works
            out.write(tail, 0, tail.length);
            out.close();
            assertThat(out.syncs()).as("one mode sync in close, no second syncOnClose sync").isEqualTo(2);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(2);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(2);
            assertThat(file.issuedOperations() - issuedBefore)
                    .as("3 aligned writes + 2 syncs (no padding truncates)").isEqualTo(5);
            assertThat(file.isOpen()).isTrue();
            assertThat(file.length()).isEqualTo(size + tail.length);
        }
        byte[] expected = new byte[size + tail.length];
        System.arraycopy(data, 0, expected, 0, size);
        System.arraycopy(tail, 0, expected, size, tail.length);
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    @Test
    void fsyncStreamSyncsOncePerFlushAndOnceOnClose(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("fsync.bin");
        int size = 2 * BLOCK;
        byte[] data = patternBytes(0, size);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            long issuedBefore = file.issuedOperations();
            JuringOutputStream out = JuringOutputStream.open(file,
                    OutputStreamOptions.defaults().withDurabilityMode(DurabilityMode.FSYNC));
            out.write(data, 0, data.length);
            out.flush();
            assertThat(out.syncs()).as("one fsync per flush").isEqualTo(1);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(1);
            out.close();
            assertThat(out.syncs()).isEqualTo(2);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(2);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(2);
            assertThat(file.issuedOperations() - issuedBefore).isEqualTo(4); // 2 writes + 2 syncs
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(data);
    }

    @Test
    void durabilityFlushOrderIsDrainPadTruncateThenOneSync(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("fdatasync-partial.bin");
        byte[] partial = patternBytes(0, 1000);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            JuringOutputStream out = JuringOutputStream.open(file,
                    OutputStreamOptions.defaults()
                            .withBufferSize(BLOCK)
                            .withDurabilityMode(DurabilityMode.FDATASYNC));
            out.write(partial, 0, partial.length);
            long issuedBeforeFlush = file.issuedOperations();
            out.flush();
            // one padded write + the padding undo (truncate) + exactly one fdatasync
            assertThat(file.issuedOperations() - issuedBeforeFlush).isEqualTo(3);
            assertThat(out.syncs()).isEqualTo(1);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(1);
            assertThat(file.length()).as("padding is truncated before the sync").isEqualTo(partial.length);
            out.close();
            assertThat(out.syncs()).isEqualTo(2); // close's single mode sync
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(partial);
    }

    @Test
    void emptyDurabilityFlushStillIssuesTheModeSync(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("fdatasync-empty-flush.bin");
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            long syncOpsBefore = file.syncOperations();
            JuringOutputStream out = JuringOutputStream.open(file,
                    OutputStreamOptions.defaults().withDurabilityMode(DurabilityMode.FDATASYNC));
            out.flush();
            out.flush();
            assertThat(out.syncs()).as("flush is a durability point even with nothing pending").isEqualTo(2);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(2);
            out.close();
            assertThat(out.syncs()).isEqualTo(3);
        }
    }

    @Test
    void syncOnCloseIsRedundantForDurabilityModes(@TempDir Path dir) throws Exception {
        for (DurabilityMode mode : new DurabilityMode[]{DurabilityMode.FDATASYNC, DurabilityMode.FSYNC}) {
            Path path = dir.resolve("sync-on-close-" + mode + ".bin");
            try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
                long syncOpsBefore = file.syncOperations();
                JuringOutputStream out = JuringOutputStream.open(file,
                        OutputStreamOptions.defaults()
                                .withDurabilityMode(mode)
                                .withSyncOnClose(true));
                out.write(patternBytes(0, BLOCK), 0, BLOCK);
                out.flush();
                assertThat(out.syncs()).as(mode + ": one sync per flush").isEqualTo(1);
                out.close();
                assertThat(out.syncs()).as(mode + ": close adds exactly one sync").isEqualTo(2);
                assertThat(file.syncOperations() - syncOpsBefore)
                        .as(mode + ": syncOnClose must not add a second close sync").isEqualTo(2);
            }
        }
    }

    // ==================================================================
    // stream fadvise applied at open
    // ==================================================================

    @Test
    void outputStreamAppliesFadviseHintAtOpen(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("out-fadvise.bin");
        byte[] data = patternBytes(0, 2 * BLOCK);
        for (FadviseAdvice advice : FadviseAdvice.values()) {
            try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
                try (JuringOutputStream out = JuringOutputStream.open(file,
                        OutputStreamOptions.defaults().withFadvise(advice))) {
                    out.write(data, 0, data.length);
                }
                assertThat(file.length()).as(advice + ": hint must not change the write result").isEqualTo(data.length);
            }
            assertThat(Files.readAllBytes(path)).as(advice + ": content unchanged").isEqualTo(data);
        }
    }

    @Test
    void inputStreamAppliesFadviseHintInAllOpenOverloads(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("in-fadvise.bin");
        byte[] data = patternBytes(0, 3 * BLOCK);
        Files.write(path, data);
        for (FadviseAdvice advice : new FadviseAdvice[]{FadviseAdvice.NORMAL, FadviseAdvice.SEQUENTIAL,
                FadviseAdvice.RANDOM, FadviseAdvice.WILLNEED, FadviseAdvice.NOREUSE}) {
            InputStreamOptions options = InputStreamOptions.defaults().withFadvise(advice);
            // whole-file overload with default options, then with explicit options
            try (JuringFile file = JuringFile.open(ring, path, READ)) {
                assertThat(readAll(JuringInputStream.open(file, options))).as(advice + ": until-EOF overload")
                        .isEqualTo(data);
            }
            // declared window overload
            try (JuringFile file = JuringFile.open(ring, path, READ)) {
                JuringInputStream in = JuringInputStream.open(file, BLOCK, 2L * BLOCK, options);
                byte[] window = new byte[2 * BLOCK];
                assertThat(in.read(window, 0, window.length)).isEqualTo(window.length);
                assertThat(window).as(advice + ": windowed overload").isEqualTo(java.util.Arrays.copyOfRange(data, BLOCK, 3 * BLOCK));
                in.close();
            }
            // length -1 (until EOF) windowed overload
            try (JuringFile file = JuringFile.open(ring, path, READ)) {
                assertThat(readAll(JuringInputStream.open(file, 0L, -1L, options)))
                        .as(advice + ": windowed until-EOF overload").isEqualTo(data);
            }
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static byte[] readAll(JuringInputStream in) throws JuringException {
        byte[] collected = new byte[0];
        MemorySegment chunk;
        while ((chunk = in.readChunk()).byteSize() > 0) {
            byte[] next = new byte[(int) chunk.byteSize()];
            MemorySegment.copy(chunk, 0, MemorySegment.ofArray(next), 0, next.length);
            byte[] merged = new byte[collected.length + next.length];
            System.arraycopy(collected, 0, merged, 0, collected.length);
            System.arraycopy(next, 0, merged, collected.length, next.length);
            collected = merged;
        }
        in.close();
        return collected;
    }

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
}
