package bench.suite;

import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import bench.suite.common.PreflightCheck;
import com.davidvlijmincx.lio.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness and integrity validation suite for the benchmark suite.
 * Verifies bit-accurate Direct I/O, sector alignment, pipeline zero-leak lifecycle,
 * and multi-threaded ring isolation.
 */
class BenchmarkSuiteValidationTest {

    @Test
    @DisplayName("Verify direct buffer sector alignment (multiple of 4096)")
    void testDirectBufferSectorAlignment() {
        int[] capacities = {4096, 8192, 16384, 65536};
        for (int cap : capacities) {
            ByteBuffer buf = IoHelper.allocateAligned(cap, IoHelper.DEFAULT_SECTOR_SIZE);
            assertNotNull(buf);
            assertTrue(buf.isDirect(), "Buffer must be off-heap direct");
            assertEquals(cap, buf.capacity(), "Buffer capacity must match requested");
            assertEquals(cap, buf.limit(), "Buffer limit must match requested");

            long address = IoHelper.getAddress(buf);
            assertTrue(IoHelper.checkAlignment(address, IoHelper.DEFAULT_SECTOR_SIZE),
                    "Buffer address 0x" + Long.toHexString(address) + " must be 4KB-aligned");
        }
    }

    @Test
    @DisplayName("Verify DatasetManager precomputed tasks are sector-aligned and within bounds")
    void testDatasetManagerAlignedTasks() {
        DatasetManager manager = new DatasetManager(
                DatasetManager.WorkloadType.READ,
                DatasetManager.FileDistribution.MULTI_FILE
        );

        int[] fileIndices = manager.getTaskFileIndices();
        long[] offsets = manager.getTaskOffsets();
        int taskCount = manager.getTaskCount();

        assertThat(fileIndices).hasSize(taskCount);
        assertThat(offsets).hasSize(taskCount);

        for (int i = 0; i < taskCount; i++) {
            assertThat(fileIndices[i]).isGreaterThanOrEqualTo(0)
                    .isLessThan(manager.getUniqueFileCount());

            assertThat(IoHelper.checkOffsetAlignment(offsets[i], IoHelper.DEFAULT_SECTOR_SIZE))
                    .as("Task " + i + " offset " + offsets[i] + " must be 4KB-aligned")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Bit-Accurate Read: FileChannel vs JUring direct reads match byte-for-byte")
    void testBitAccurateDirectRead() throws IOException {
        Path testFile = DatasetManager.DEFAULT_READ_DIR.resolve("file_1000.bin");
        if (!Files.exists(testFile)) {
            // Find any available file
            DatasetManager dm = new DatasetManager(DatasetManager.WorkloadType.READ, DatasetManager.FileDistribution.MULTI_FILE);
            testFile = dm.getUniquePaths()[0];
        }

        final int readSize = 4096;
        final long readOffset = 8192; // 4KB-aligned offset

        // 1. Read via FileChannel with ExtendedOpenOption.DIRECT
        ByteBuffer fcBuffer = IoHelper.allocateAligned(readSize, IoHelper.DEFAULT_SECTOR_SIZE);
        try (FileChannel fc = FileChannel.open(testFile, StandardOpenOption.READ, IoHelper.getDirectOpenOption())) {
            int n = fc.read(fcBuffer, readOffset);
            assertEquals(readSize, n, "FileChannel must read 4096 bytes");
        }
        byte[] fcBytes = new byte[readSize];
        fcBuffer.flip();
        fcBuffer.get(fcBytes);

        // 2. Read via JUring with LinuxOpenOptions.READ_DIRECT
        byte[] uringBytes = new byte[readSize];
        try (JUring ring = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor(testFile.toString(), LinuxOpenOptions.READ_DIRECT, 0)) {

            ring.registerFiles(fd);
            MemorySegment[] bufs = ring.registerBuffers(readSize, 2);

            ring.prepareReadFixed(0, readSize, readOffset, 0);
            ring.submit();

            Result result = ring.waitForResult();
            assertEquals(readSize, result.result(), "JUring must read 4096 bytes");

            MemorySegment.copy(bufs[0], 0L, MemorySegment.ofArray(uringBytes), 0L, readSize);
        }

        // 3. Assert bit-accurate match
        assertArrayEquals(fcBytes, uringBytes, "FileChannel and JUring direct read bytes must match identically");
    }

    @Test
    @DisplayName("Bit-Accurate Write: JUring write -> FileChannel read and FileChannel write -> JUring read")
    void testBitAccurateDirectWrite(@TempDir Path tempDir) throws IOException {
        Path tempFile = tempDir.resolve("direct_io_test.bin");
        // Pre-allocate 64KB sector-aligned file
        try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
            raf.setLength(65536);
        }

        final int blockSize = 4096;
        final long writeOffset = 4096;

        byte[] patternA = new byte[blockSize];
        new Random(1111L).nextBytes(patternA);

        byte[] patternB = new byte[blockSize];
        new Random(2222L).nextBytes(patternB);

        // Phase 1: Write patternA with JUring WRITE_DIRECT, read back with FileChannel DIRECT
        try (JUring ring = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor(tempFile.toString(), LinuxOpenOptions.WRITE_DIRECT, 0)) {

            ring.registerFiles(fd);
            MemorySegment[] bufs = ring.registerBuffers(blockSize, 2);
            MemorySegment.copy(MemorySegment.ofArray(patternA), 0L, bufs[0], 0L, blockSize);

            ring.prepareWriteFixed(0, bufs[0], writeOffset, 0);
            ring.submit();

            Result res = ring.waitForResult();
            assertEquals(blockSize, res.result(), "JUring write must succeed with 4096 bytes");
        }

        ByteBuffer fcReadBuf = IoHelper.allocateAligned(blockSize, IoHelper.DEFAULT_SECTOR_SIZE);
        try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.READ, IoHelper.getDirectOpenOption())) {
            int n = fc.read(fcReadBuf, writeOffset);
            assertEquals(blockSize, n);
        }
        byte[] readBackA = new byte[blockSize];
        fcReadBuf.flip();
        fcReadBuf.get(readBackA);
        assertArrayEquals(patternA, readBackA, "Pattern written by JUring must match FileChannel read-back byte-for-byte");

        // Phase 2: Write patternB with FileChannel DIRECT, read back with JUring READ_DIRECT
        ByteBuffer fcWriteBuf = IoHelper.allocateAligned(blockSize, IoHelper.DEFAULT_SECTOR_SIZE);
        fcWriteBuf.put(patternB);
        fcWriteBuf.flip();

        try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.WRITE, IoHelper.getDirectOpenOption())) {
            int n = fc.write(fcWriteBuf, writeOffset);
            assertEquals(blockSize, n);
        }

        byte[] readBackB = new byte[blockSize];
        try (JUring ring = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor(tempFile.toString(), LinuxOpenOptions.READ_DIRECT, 0)) {

            ring.registerFiles(fd);
            MemorySegment[] bufs = ring.registerBuffers(blockSize, 2);

            ring.prepareReadFixed(0, blockSize, writeOffset, 0);
            ring.submit();

            Result res = ring.waitForResult();
            assertEquals(blockSize, res.result());
            MemorySegment.copy(bufs[0], 0L, MemorySegment.ofArray(readBackB), 0L, blockSize);
        }
        assertArrayEquals(patternB, readBackB, "Pattern written by FileChannel must match JUring read-back byte-for-byte");
    }

    @Test
    @DisplayName("Zero-Allocation Pipeline Integrity: Reaping CQEs with ResultConsumer leaves ring clean")
    void testZeroAllocationPipelineIntegrity() {
        DatasetManager dm = new DatasetManager(DatasetManager.WorkloadType.READ, DatasetManager.FileDistribution.MULTI_FILE);
        final int numTasks = 128;
        final int qd = 32;

        try (JUring ring = new JUring(128, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN)) {
            FileDescriptor[] fds = dm.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
            ring.registerFiles(fds);
            ring.registerBuffers(4096, qd);

            int[] reapedCount = new int[1];
            ResultConsumer consumer = result -> {
                assertEquals(4096, result.result(), "Read operation must return 4096 bytes");
                reapedCount[0]++;
                ring.checkInBuffer(result.bufferIdx());
            };

            int submitted = 0;
            int unsubmitted = 0;
            int taskIdx = 0;

            while (reapedCount[0] < numTasks) {
                while ((submitted - reapedCount[0]) < qd && taskIdx < numTasks) {
                    int bufIdx = ring.checkOutBuffer();
                    if (bufIdx < 0) break;

                    int fileIdx = dm.getTaskFileIndices()[taskIdx];
                    long offset = dm.getTaskOffsets()[taskIdx];

                    ring.prepareReadFixed(fileIdx, 4096, offset, bufIdx);
                    submitted++;
                    unsubmitted++;
                    taskIdx++;

                    if (unsubmitted >= 16) {
                        ring.submit();
                        unsubmitted = 0;
                        ring.peekForBatchResult(16, consumer);
                    }
                }

                if (unsubmitted > 0) {
                    ring.submit();
                    unsubmitted = 0;
                }

                int batch = Math.min(submitted - reapedCount[0], 16);
                int reaped = ring.peekForBatchResult(batch, consumer);
                if (reaped == 0 && reapedCount[0] < numTasks) {
                    ring.waitForBatchResult(1, consumer);
                }
            }

            assertEquals(numTasks, reapedCount[0], "All pipeline operations must be reaped");
            DatasetManager.closeFileDescriptors(fds);
        }
    }

    @Test
    @DisplayName("Multi-Thread Lifecycle & Isolation: 4 concurrent rings execute and close without leak")
    void testMultiThreadLifecycleAndNoRingLeak() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();

        DatasetManager dm = new DatasetManager(DatasetManager.WorkloadType.READ, DatasetManager.FileDistribution.MULTI_FILE);

        for (int t = 0; t < threads; t++) {
            futures.add(executor.submit(() -> {
                // Each thread creates its own isolated ring (IORING_SETUP_SINGLE_ISSUER requires same thread submit)
                try (JUring ring = new JUring(64, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN)) {
                    FileDescriptor[] fds = dm.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
                    ring.registerFiles(fds);
                    ring.registerBuffers(4096, 16);

                    for (int i = 0; i < 32; i++) {
                        int fileIdx = dm.getTaskFileIndices()[i];
                        long offset = dm.getTaskOffsets()[i];
                        ring.prepareReadFixed(fileIdx, 4096, offset, i % 16);
                        ring.submit();
                        Result r = ring.waitForResult();
                        if (r.result() != 4096) {
                            return false;
                        }
                    }

                    DatasetManager.closeFileDescriptors(fds);
                }
                return true;
            }));
        }

        for (Future<Boolean> f : futures) {
            assertTrue(f.get(10, TimeUnit.SECONDS), "Thread execution must succeed with 0 errors");
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Preflight Checks return success on supported Linux environment")
    void testPreflightChecks() {
        PreflightCheck.Report report = PreflightCheck.runChecks();
        assertNotNull(report);
        assertTrue(report.isSuccess(), "Preflight checks must pass on Linux testbed");
        assertThat(report.getInfo()).isNotEmpty();
    }
}
