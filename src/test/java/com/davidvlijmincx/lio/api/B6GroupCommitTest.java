package com.davidvlijmincx.lio.api;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DSYNC;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6 group-commit tests (frozen contract: {@code docs/b6-design.md} §2.7-§2.9, §3.1, §7.4).
 *
 * <p>The "one sync per batch" claim is proven with two independent observables:
 * {@link GroupCommitStats#syncs()} and the {@link JuringFile#syncOperations()}/
 * {@link JuringFile#syncCompletions()} deltas; {@link JuringFile#issuedOperations()} is the
 * ordering cross-check ({@code n} writes + at most one sync per committed batch).
 * {@code @TempDir} is tmpfs, so the visibility assertions prove page-cache visibility across
 * a fresh open, not media durability (disclosed in the B6 report/status).
 */
class B6GroupCommitTest {

    private static final int BLOCK = 4096;
    private static final int KIB = 1024;

    private static final ThreadMXBean THREAD_BEAN;

    static {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean sunBean)) {
            throw new UnsupportedOperationException("com.sun.management.ThreadMXBean not available");
        }
        if (!sunBean.isThreadAllocatedMemorySupported()) {
            throw new UnsupportedOperationException("Thread allocated memory measurement is not supported");
        }
        if (!sunBean.isThreadAllocatedMemoryEnabled()) {
            sunBean.setThreadAllocatedMemoryEnabled(true);
        }
        THREAD_BEAN = sunBean;
    }

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
    // options (defaults, validation, value semantics)
    // ==================================================================

    @Test
    void optionsDefaultsValidationAndValueSemantics() {
        GroupCommitOptions defaults = GroupCommitOptions.defaults();
        assertThat(defaults.durability()).isEqualTo(DurabilityMode.FDATASYNC);
        assertThat(defaults.maxBatchSize()).isEqualTo(GroupCommit.DEFAULT_MAX_BATCH_SIZE);
        assertThat(GroupCommit.DEFAULT_MAX_BATCH_SIZE).isEqualTo(32);

        GroupCommitOptions custom = defaults
                .withDurabilityMode(DurabilityMode.FSYNC)
                .withMaxBatchSize(128);
        assertThat(custom.durability()).isEqualTo(DurabilityMode.FSYNC);
        assertThat(custom.maxBatchSize()).isEqualTo(128);

        // immutable value object
        GroupCommitOptions equal = GroupCommitOptions.defaults()
                .withMaxBatchSize(128)
                .withDurabilityMode(DurabilityMode.FSYNC);
        assertThat(custom).isEqualTo(equal);
        assertThat(custom.hashCode()).isEqualTo(equal.hashCode());
        assertThat(custom).isNotEqualTo(defaults);
        assertThat(custom.toString()).contains("durability=FSYNC").contains("maxBatchSize=128");

        assertThatThrownBy(() -> defaults.withDurabilityMode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> defaults.withMaxBatchSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withMaxBatchSize(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withMaxBatchSize(4097)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> defaults.withMaxBatchSize(1)).doesNotThrowAnyException();
        assertThatCode(() -> defaults.withMaxBatchSize(4096)).doesNotThrowAnyException();
    }

    // ==================================================================
    // open / validation (§2.7)
    // ==================================================================

    @Test
    void openDefaultsAndAccessors(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("defaults.bin");
        Files.write(path, new byte[BLOCK]);
        try (JuringFile file = JuringFile.open(ring, path, RDWR);
             GroupCommit group = GroupCommit.open(file)) {
            assertSame(file, group.file());
            assertThat(group.durabilityMode()).isEqualTo(DurabilityMode.FDATASYNC);
            assertThat(group.maxBatchSize()).isEqualTo(32);
            assertThat(group.pendingWrites()).isZero();
            assertThat(group.pendingBytes()).isZero();
            assertThat(group.stats())
                    .isEqualTo(new GroupCommitStats(0L, 0L, 0L, 0L, 0L, 0));
        }
    }

    @Test
    void openValidation(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("open-validation.bin");
        Files.write(path, new byte[BLOCK]);
        try (JuringFile file = JuringFile.open(ring, path, RDWR)) {
            assertThatThrownBy(() -> GroupCommit.open(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> GroupCommit.open(file, null)).isInstanceOf(NullPointerException.class);

            // maxBatchSize + 1 <= file.inFlightCapacity()
            file.withInFlightCapacity(4);
            assertThat(file.inFlightCapacity()).isEqualTo(4);
            assertThatThrownBy(() -> GroupCommit.open(file,
                    GroupCommitOptions.defaults().withMaxBatchSize(4)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("in-flight");
            // exactly maxBatchSize + 1 == capacity is legal
            try (GroupCommit group = GroupCommit.open(file,
                    GroupCommitOptions.defaults().withMaxBatchSize(3))) {
                assertThat(group.maxBatchSize()).isEqualTo(3);
            }

            // DSYNC requires a file opened with O_DSYNC
            assertThatThrownBy(() -> GroupCommit.open(file,
                    GroupCommitOptions.defaults().withMaxBatchSize(3)
                            .withDurabilityMode(DurabilityMode.DSYNC)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DSYNC")
                    .hasMessageContaining("WRITEBACK");
        }
        // closed file
        JuringFile closed = JuringFile.open(ring, path, RDWR);
        closed.close();
        assertThatThrownBy(() -> GroupCommit.open(closed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void writeValidatesOffsetAndSegmentLikeJuringFile(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("write-validation.bin");
        Files.write(path, new byte[BLOCK]);
        try (JuringFile file = JuringFile.open(ring, path, RDWR);
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults().withMaxBatchSize(4));
             Arena arena = Arena.ofConfined()) {
            MemorySegment nativeSegment = arena.allocate(BLOCK, BLOCK);
            assertThatThrownBy(() -> group.write(-1L, nativeSegment))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative offset");
            assertThatThrownBy(() -> group.write(0L, MemorySegment.ofArray(new byte[8])))
                    .as("a heap segment is not a B1 native buffer")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> group.write(0L, null)).isInstanceOf(NullPointerException.class);
            // rejected writes are not queued
            assertThat(group.pendingWrites()).isZero();
            assertThat(file.issuedOperations()).isZero();
        }
    }

    @Test
    void nonDsyncModeOnDsyncFileIsAllowed(@TempDir Path dir) throws Exception {
        // A redundant explicit sync on an O_DSYNC file is allowed (harmless), per §2.2.
        Path path = dir.resolve("fdatasync-on-dsync-file.bin");
        byte[] expected = patternBytes(0, BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, 0644, RDWR, CREATE, TRUNCATE, DSYNC);
             Arena arena = Arena.ofConfined();
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults()
                     .withMaxBatchSize(4)
                     .withDurabilityMode(DurabilityMode.FDATASYNC))) {
            long syncOpsBefore = file.syncOperations();
            MemorySegment source = arena.allocate(BLOCK, BLOCK);
            MemorySegment.copy(expected, 0, source, JAVA_BYTE, 0, BLOCK);
            group.write(0L, source);
            assertThat(group.commit()).isEqualTo(1);
            assertThat(group.stats().syncs()).isEqualTo(1);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    // ==================================================================
    // caller-controlled batches: no auto-commit (§2.7)
    // ==================================================================

    @Test
    void batchFullWriteThrowsWithoutAutoCommit(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("batch-full.bin");
        byte[] data = patternBytes(0, 3 * BLOCK);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE);
             GroupCommit group = GroupCommit.open(file,
                     GroupCommitOptions.defaults()
                             .withMaxBatchSize(2)
                             .withDurabilityMode(DurabilityMode.WRITEBACK));
             Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(data.length, BLOCK);
            MemorySegment.copy(data, 0, source, JAVA_BYTE, 0, data.length);
            group.write(0, source.asSlice(0, BLOCK));
            group.write(BLOCK, source.asSlice(BLOCK, BLOCK));
            long issuedWhenFull = file.issuedOperations();
            long completedWhenFull = file.completedOperations();

            assertThatThrownBy(() -> group.write(2L * BLOCK, source.asSlice(2L * BLOCK, BLOCK)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("commit() first");
            // the rejected write did not reach the ring and did not auto-commit the batch
            assertThat(group.pendingWrites()).isEqualTo(2);
            assertThat(file.issuedOperations()).isEqualTo(issuedWhenFull);
            assertThat(file.completedOperations()).isEqualTo(completedWhenFull);
            assertThat(file.syncOperations()).isZero();

            assertThat(group.commit()).isEqualTo(2);
            assertThat(group.pendingWrites()).isZero();
            assertThat(group.commit()).as("empty commit is a no-op").isZero();
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(java.util.Arrays.copyOf(data, 2 * BLOCK));
    }

    // ==================================================================
    // basic batch: N writes -> 1 commit -> exactly one sync (§2.7, §3.5)
    // ==================================================================

    @Test
    void eightWriteBatchIssuesExactlyOneFdatasync(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("batch-one-sync.bin");
        int batchSize = 8;
        int size = batchSize * BLOCK;
        byte[] expected = patternBytes(0, size);
        long syncOpsBefore;
        long syncCompBefore;
        long issuedBefore;
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(size, BLOCK);
            MemorySegment.copy(expected, 0, source, JAVA_BYTE, 0, size);
            syncOpsBefore = file.syncOperations();
            syncCompBefore = file.syncCompletions();
            issuedBefore = file.issuedOperations();

            GroupCommit group = GroupCommit.open(file,
                    GroupCommitOptions.defaults().withMaxBatchSize(batchSize));
            for (int i = 0; i < batchSize; i++) {
                group.write((long) i * BLOCK, source.asSlice((long) i * BLOCK, BLOCK));
            }
            // write() only prepares: the batch is queued asynchronously, not submitted/drained
            assertThat(group.pendingWrites()).isEqualTo(batchSize);
            assertThat(group.pendingBytes()).isEqualTo(size);
            assertThat(file.issuedOperations() - issuedBefore).isEqualTo(batchSize);
            assertThat(file.completedOperations()).as("write() does not await").isZero();
            assertThat(group.stats()).isEqualTo(new GroupCommitStats(0L, 0L, 0L, 0L, 0L, 0));

            assertThat(group.commit()).isEqualTo(batchSize);
            assertThat(file.issuedOperations() - issuedBefore)
                    .as("8 writes + exactly one sync").isEqualTo(batchSize + 1);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(1);
            assertThat(group.pendingWrites()).isZero();
            assertThat(group.pendingBytes()).isZero();
            assertThat(group.stats())
                    .isEqualTo(new GroupCommitStats(1L, batchSize, size, 1L, 0L, batchSize));

            // empty commit issues neither a write nor a sync
            long issuedAfterBatch = file.issuedOperations();
            long syncsAfterBatch = file.syncOperations();
            assertThat(group.commit()).isZero();
            assertThat(file.issuedOperations()).isEqualTo(issuedAfterBatch);
            assertThat(file.syncOperations()).isEqualTo(syncsAfterBatch);
            assertThat(group.stats().batches()).isEqualTo(1);

            group.close();
            assertThat(file.isOpen()).as("group close must not close the JuringFile").isTrue();
            assertThat(file.length()).isEqualTo(size);
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    @Test
    void partialBatchCommitsOnlyTheQueuedWrites(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("partial.bin");
        int size = 5 * BLOCK;
        byte[] expected = patternBytes(0, size);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined();
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults().withMaxBatchSize(8))) {
            long syncOpsBefore = file.syncOperations();
            long issuedBefore = file.issuedOperations();
            MemorySegment source = arena.allocate(expected.length, BLOCK);
            MemorySegment.copy(expected, 0, source, JAVA_BYTE, 0, expected.length);
            for (int i = 0; i < 5; i++) {
                group.write((long) i * BLOCK, source.asSlice((long) i * BLOCK, BLOCK));
            }
            assertThat(group.commit()).isEqualTo(5);
            assertThat(file.issuedOperations() - issuedBefore).isEqualTo(6);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
            assertThat(group.stats())
                    .isEqualTo(new GroupCommitStats(1L, 5L, size, 1L, 0L, 5));
            assertThat(group.commit()).isZero();
            assertThat(group.stats().batches()).isEqualTo(1);
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    @Test
    void twoBatchesIssueTwoSyncs(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("two-batches.bin");
        int size = 5 * BLOCK;
        byte[] expected = patternBytes(0, size);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined();
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults().withMaxBatchSize(16))) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            long issuedBefore = file.issuedOperations();
            MemorySegment source = arena.allocate(expected.length, BLOCK);
            MemorySegment.copy(expected, 0, source, JAVA_BYTE, 0, expected.length);
            for (int i = 0; i < 3; i++) {
                group.write((long) i * BLOCK, source.asSlice((long) i * BLOCK, BLOCK));
            }
            assertThat(group.commit()).isEqualTo(3);
            for (int i = 3; i < 5; i++) {
                group.write((long) i * BLOCK, source.asSlice((long) i * BLOCK, BLOCK));
            }
            assertThat(group.commit()).isEqualTo(2);
            assertThat(file.issuedOperations() - issuedBefore).as("5 writes + 2 syncs").isEqualTo(7);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(2);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(2);
            assertThat(group.stats())
                    .isEqualTo(new GroupCommitStats(2L, 5L, size, 2L, 0L, 3));
            assertThat(group.stats().maxObservedBatch()).isLessThanOrEqualTo(group.maxBatchSize());
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    // ==================================================================
    // durability modes (§2.2, §3.1)
    // ==================================================================

    @Test
    void writebackModeCompletesWritesWithoutSync(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("writeback.bin");
        int size = 3 * BLOCK;
        byte[] expected = patternBytes(0, size);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined();
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults()
                     .withMaxBatchSize(8)
                     .withDurabilityMode(DurabilityMode.WRITEBACK))) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            long issuedBefore = file.issuedOperations();
            MemorySegment source = arena.allocate(expected.length, BLOCK);
            MemorySegment.copy(expected, 0, source, JAVA_BYTE, 0, expected.length);
            for (int i = 0; i < 3; i++) {
                group.write((long) i * BLOCK, source.asSlice((long) i * BLOCK, BLOCK));
            }
            assertThat(group.commit()).isEqualTo(3);
            assertThat(group.durabilityMode()).isEqualTo(DurabilityMode.WRITEBACK);
            assertThat(file.syncOperations() - syncOpsBefore).isZero();
            assertThat(file.syncCompletions() - syncCompBefore).isZero();
            assertThat(file.issuedOperations() - issuedBefore).as("3 writes, no sync").isEqualTo(3);
            assertThat(group.stats())
                    .isEqualTo(new GroupCommitStats(1L, 3L, size, 0L, 0L, 3));
            assertThat(group.stats().syncs()).isZero();
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    @Test
    void dsyncModeIssuesNoExplicitSync(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("dsync.bin");
        int size = 2 * BLOCK;
        byte[] expected = patternBytes(0, size);
        try (JuringFile file = JuringFile.open(ring, path, 0644, RDWR, CREATE, TRUNCATE, DSYNC);
             Arena arena = Arena.ofConfined();
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults()
                     .withMaxBatchSize(8)
                     .withDurabilityMode(DurabilityMode.DSYNC))) {
            assertThat(group.durabilityMode()).isEqualTo(DurabilityMode.DSYNC);
            long syncOpsBefore = file.syncOperations();
            long issuedBefore = file.issuedOperations();
            MemorySegment source = arena.allocate(expected.length, BLOCK);
            MemorySegment.copy(expected, 0, source, JAVA_BYTE, 0, expected.length);
            for (int i = 0; i < 2; i++) {
                group.write((long) i * BLOCK, source.asSlice((long) i * BLOCK, BLOCK));
            }
            assertThat(group.commit()).isEqualTo(2);
            assertThat(file.syncOperations() - syncOpsBefore).isZero();
            assertThat(file.issuedOperations() - issuedBefore).as("2 writes, no explicit sync").isEqualTo(2);
            assertThat(group.stats())
                    .isEqualTo(new GroupCommitStats(1L, 2L, size, 0L, 0L, 2));
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    @Test
    void fsyncModeIssuesOneFsyncPerBatch(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("fsync.bin");
        int size = 2 * BLOCK;
        byte[] expected = patternBytes(0, size);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined();
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults()
                     .withMaxBatchSize(8)
                     .withDurabilityMode(DurabilityMode.FSYNC))) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            MemorySegment source = arena.allocate(expected.length, BLOCK);
            MemorySegment.copy(expected, 0, source, JAVA_BYTE, 0, expected.length);
            for (int i = 0; i < 2; i++) {
                group.write((long) i * BLOCK, source.asSlice((long) i * BLOCK, BLOCK));
            }
            assertThat(group.commit()).isEqualTo(2);
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(1);
            assertThat(group.stats().syncs()).isEqualTo(1);
            assertThat(group.stats().batches()).isEqualTo(group.stats().syncs());
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    // ==================================================================
    // pending bytes / maxObservedBatch / stats invariants
    // ==================================================================

    @Test
    void pendingBytesAndMaxObservedBatchTrackTheBatches(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("stats.bin");
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined();
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults().withMaxBatchSize(4))) {
            MemorySegment source = arena.allocate(4 * BLOCK, BLOCK);
            // batch of 2, then batch of 3
            group.write(0, source.asSlice(0, BLOCK));
            group.write(BLOCK, source.asSlice(BLOCK, KIB));
            assertThat(group.pendingWrites()).isEqualTo(2);
            assertThat(group.pendingBytes()).isEqualTo(BLOCK + KIB);
            assertThat(group.commit()).isEqualTo(2);
            assertThat(group.pendingBytes()).isZero();
            assertThat(group.stats().maxObservedBatch()).isEqualTo(2);

            group.write(0, source.asSlice(0, BLOCK));
            group.write(BLOCK, source.asSlice(BLOCK, BLOCK));
            group.write(2L * BLOCK, source.asSlice(2L * BLOCK, BLOCK));
            assertThat(group.pendingBytes()).isEqualTo(3L * BLOCK);
            assertThat(group.commit()).isEqualTo(3);

            GroupCommitStats stats = group.stats();
            assertThat(stats.batches()).isEqualTo(2);
            assertThat(stats.committedWrites()).isEqualTo(5);
            assertThat(stats.committedBytes()).isEqualTo(BLOCK + KIB + 3L * BLOCK);
            assertThat(stats.syncs()).isEqualTo(2);
            assertThat(stats.failedCommits()).isZero();
            assertThat(stats.maxObservedBatch()).isEqualTo(3);
            assertThat(stats.syncs()).isLessThanOrEqualTo(stats.batches());
            assertThat(stats.maxObservedBatch()).isLessThanOrEqualTo(group.maxBatchSize());
            // pendingWrites() + committedWrites() == accepted write() calls
            assertThat(group.pendingWrites() + stats.committedWrites()).isEqualTo(5);
        }
    }

    // ==================================================================
    // lifecycle: close commits a pending batch, idempotent, never closes the file
    // ==================================================================

    @Test
    void closeCommitsPendingBatchOnceAndIsIdempotent(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("close-commits.bin");
        int size = 3 * BLOCK;
        byte[] expected = patternBytes(0, size);
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            long syncOpsBefore = file.syncOperations();
            long syncCompBefore = file.syncCompletions();
            long issuedBefore = file.issuedOperations();
            GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults().withMaxBatchSize(8));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment source = arena.allocate(expected.length, BLOCK);
                MemorySegment.copy(expected, 0, source, JAVA_BYTE, 0, expected.length);
                for (int i = 0; i < 3; i++) {
                    group.write((long) i * BLOCK, source.asSlice((long) i * BLOCK, BLOCK));
                }
            }
            group.close();
            assertThat(file.isOpen()).as("group close never closes the JuringFile").isTrue();
            assertThat(group.stats())
                    .isEqualTo(new GroupCommitStats(1L, 3L, size, 1L, 0L, 3));
            assertThat(file.syncOperations() - syncOpsBefore).isEqualTo(1);
            assertThat(file.syncCompletions() - syncCompBefore).isEqualTo(1);
            assertThat(file.issuedOperations() - issuedBefore).isEqualTo(4); // 3 writes + 1 sync

            long issuedAfterClose = file.issuedOperations();
            assertThatCode(group::close).as("close is idempotent").doesNotThrowAnyException();
            assertThat(file.issuedOperations()).isEqualTo(issuedAfterClose);
            assertThat(group.stats().batches()).isEqualTo(1);
            // after close the group is unusable but the stats stay readable
            assertThatThrownBy(() -> group.write(0, MemorySegment.NULL))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(group::commit).isInstanceOf(IllegalStateException.class);
            assertThat(group.pendingWrites()).isZero();
            assertThat(group.pendingBytes()).isZero();
            assertThat(group.durabilityMode()).isEqualTo(DurabilityMode.FDATASYNC);
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(expected);
    }

    @Test
    void useAfterCloseThrowsIllegalStateException(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("use-after-close.bin");
        Files.write(path, new byte[BLOCK]);
        try (JuringFile file = JuringFile.open(ring, path, RDWR);
             Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(BLOCK, BLOCK);
            GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults().withMaxBatchSize(4));
            group.write(0, source);
            group.close();
            assertThatThrownBy(() -> group.write(0, source)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(group::commit).isInstanceOf(IllegalStateException.class);
            assertThat(group.stats()).isEqualTo(new GroupCommitStats(1L, 1L, BLOCK, 1L, 0L, 1));
        }
    }

    // ==================================================================
    // error latching (§2.7, §8)
    // ==================================================================

    @Test
    void failedWriteCompletionLatchesAndPoisonsSubsequentCalls(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("read-only.bin");
        Files.write(path, new byte[2 * BLOCK]);
        try (JuringFile file = JuringFile.open(ring, path, READ); // writes complete with EBADF
             Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(2 * BLOCK, BLOCK);
            GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults().withMaxBatchSize(4));
            long syncOpsBefore = file.syncOperations();
            group.write(0, source.asSlice(0, BLOCK));
            group.write(BLOCK, source.asSlice(BLOCK, BLOCK));

            JuringException first = assertThrows(JuringException.class, group::commit);
            // the failed batch is fully drained and the first exception is latched
            assertThat(file.inFlightOperations()).isZero();
            assertThat(file.syncOperations() - syncOpsBefore).as("no sync for a failed batch").isZero();
            assertThat(group.pendingWrites()).isZero();
            assertThat(group.pendingBytes()).isZero();
            GroupCommitStats failedStats = group.stats();
            assertThat(failedStats.batches()).isZero();
            assertThat(failedStats.committedWrites()).isZero();
            assertThat(failedStats.syncs()).isZero();
            assertThat(failedStats.failedCommits()).isEqualTo(1);

            assertSame(first, assertThrows(JuringException.class, () -> group.write(0, source)));
            assertSame(first, assertThrows(JuringException.class, group::commit));
            assertSame(first, assertThrows(JuringException.class, group::close));
            assertThat(group.stats().failedCommits()).as("counters frozen after the latch").isEqualTo(1);

            // the failed close marked the group closed: use-after-close is ISE, not the latch
            assertThatThrownBy(() -> group.write(0, source)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(group::commit).isInstanceOf(IllegalStateException.class);
            assertDoesNotThrow(group::close);
        }
    }

    // ==================================================================
    // allocation-free steady state (§2.7, §7.4)
    // ==================================================================

    @Test
    void steadyStateWriteAndCommitAllocateNothing(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("allocation-free.bin");
        Files.write(path, new byte[256 * BLOCK]);
        try (JuringFile file = JuringFile.open(ring, path, RDWR);
             Arena arena = Arena.ofConfined();
             GroupCommit group = GroupCommit.open(file, GroupCommitOptions.defaults().withMaxBatchSize(4))) {
            MemorySegment source = arena.allocate(BLOCK, BLOCK);
            for (int i = 0; i < BLOCK; i++) {
                source.set(JAVA_BYTE, i, pattern(i));
            }
            long threadId = Thread.currentThread().threadId();
            int zeroCount = 0;
            for (int warmup = 0; warmup < 30; warmup++) {
                long before = getAllocatedBytes(threadId);
                runWriteAndCommit(group, source, 200);
                long after = getAllocatedBytes(threadId);
                if (after - before == 0L) {
                    if (++zeroCount >= 3) {
                        break;
                    }
                } else {
                    zeroCount = 0;
                }
            }
            assertTrue(zeroCount >= 3, "warmup must reach 3 consecutive 0-allocation rounds before measuring");

            long bytesBefore = getAllocatedBytes(threadId);
            int committed = runWriteAndCommit(group, source, 1000);
            long allocated = getAllocatedBytes(threadId) - bytesBefore;
            assertThat(allocated).as("GroupCommit write+commit hot path allocated " + allocated + " bytes").isZero();
            assertThat(committed).isEqualTo(1000);
            assertThat(group.stats().committedWrites()).isGreaterThanOrEqualTo(1000L);
        }
    }

    private static int runWriteAndCommit(GroupCommit group, MemorySegment source, int ops) throws JuringException {
        int committed = 0;
        for (int i = 0; i < ops; i++) {
            group.write(0L, source);
            committed += group.commit();
        }
        return committed;
    }

    private static long getAllocatedBytes(long threadId) {
        return THREAD_BEAN.getThreadAllocatedBytes(threadId);
    }

    // ==================================================================
    // helpers
    // ==================================================================

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
