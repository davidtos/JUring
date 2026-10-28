package bench.suite;

import bench.suite.common.CpuAuxCounters;
import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import bench.suite.common.PreflightCheck;
import bench.suite.common.StorageAuxCounters;
import bench.suite.direct.DirectLatencyBenchmark;
import bench.suite.direct.VirtualThreadBenchmark;
import org.openjdk.jmh.infra.Blackhole;
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

    @Test
    @DisplayName("Virtual Thread Integrity: 100 concurrent virtual threads read with JUringBlocking and FileChannel")
    void testVirtualThreadJUringBlockingVsFileChannelValidation() throws Exception {
        DatasetManager dm = new DatasetManager(DatasetManager.WorkloadType.READ, DatasetManager.FileDistribution.MULTI_FILE);
        int vts = 100;
        int tasksPerVt = 5;
        int totalTasks = vts * tasksPerVt;

        FileDescriptor[] fds = dm.openFileDescriptors(LinuxOpenOptions.READ);
        FileChannel[] channels = dm.openFileChannels(false);

        try (JUringBlocking blocking = new JUringBlocking(256, IORING_SETUP_SINGLE_ISSUER)) {
            blocking.registerFiles(fds);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Boolean>> futures = new ArrayList<>();

                for (int t = 0; t < vts; t++) {
                    final int vtId = t;
                    futures.add(executor.submit(() -> {
                        ByteBuffer fcBuf = ByteBuffer.allocateDirect(4096);
                        ByteBuffer juBuf = ByteBuffer.allocateDirect(4096);
                        MemorySegment juSeg = MemorySegment.ofBuffer(juBuf);

                        for (int k = 0; k < tasksPerVt; k++) {
                            int taskIdx = (vtId * tasksPerVt + k) % dm.getTaskCount();
                            int fileIdx = dm.getTaskFileIndices()[taskIdx];
                            long offset = dm.getTaskOffsets()[taskIdx];

                            // Read via FileChannel
                            fcBuf.clear();
                            int fcBytes = channels[fileIdx].read(fcBuf, offset);
                            assertEquals(4096, fcBytes);

                            // Read via JUringBlocking (registered file)
                            long juBytes = blocking.read(fileIdx, juSeg, offset);
                            assertEquals(4096L, juBytes);

                            // Bit-accurate comparison
                            byte[] fcArr = new byte[4096];
                            byte[] juArr = new byte[4096];
                            fcBuf.flip();
                            fcBuf.get(fcArr);
                            MemorySegment.copy(juSeg, 0L, MemorySegment.ofArray(juArr), 0L, 4096);
                            assertArrayEquals(fcArr, juArr);
                        }
                        return true;
                    }));
                }

                for (Future<Boolean> f : futures) {
                    assertTrue(f.get(10, TimeUnit.SECONDS));
                }
            }
        } finally {
            DatasetManager.closeFileDescriptors(fds);
            DatasetManager.closeChannels(channels);
        }
    }

    @Test
    @DisplayName("VirtualThreadBenchmark Pipeline Execution: State lifecycle and benchmark methods execute cleanly")
    void testVirtualThreadBenchmarkExecution() throws Exception {
        VirtualThreadBenchmark bench = new VirtualThreadBenchmark();
        VirtualThreadBenchmark.BenchmarkState bState = new VirtualThreadBenchmark.BenchmarkState();
        bState.distribution = "MULTI_FILE";
        bState.setup();

        VirtualThreadBenchmark.ThreadState tState = new VirtualThreadBenchmark.ThreadState();
        tState.virtualThreads = 100;
        tState.setup(bState);

        try {
            StorageAuxCounters storage = new StorageAuxCounters();
            CpuAuxCounters cpu = new CpuAuxCounters();

            bench.juringVirtualThreadRead(tState, bState, storage, cpu, null);
            bench.fileChannelVirtualThreadRead(tState, bState, storage, cpu, null);
            assertTrue(storage.bytesProcessed > 0, "Storage counter should have recorded read bytes");
        } finally {
            tState.tearDown();
        }
    }

    @Test
    @DisplayName("Ergonomics & Resilience: Result.asString() and JUringBlocking non-positive poller clamping")
    void testResultAsStringAndPollerClamping() {
        try (JUring ring = new JUring(8);
             FileDescriptor fd = new FileDescriptor(java.nio.file.Path.of("src/test/resources/read_file"), LinuxOpenOptions.READ)) {
            ring.prepareRead(fd, 13, 0);
            ring.submit();
            try (Result res = ring.waitForResult()) {
                assertEquals("Hello, World!", res.asString());
                assertEquals(res.getString(), res.asString());
            }
        }

        try (JUringBlocking jbZero = new JUringBlocking(16, 0);
             JUringBlocking jbNeg = new JUringBlocking(16, -2);
             FileDescriptor fd = new FileDescriptor(java.nio.file.Path.of("src/test/resources/read_file"), LinuxOpenOptions.READ);
             java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment buf = arena.allocate(14);
            assertEquals(13L, jbZero.read(fd, buf, 14, 0));
            assertEquals(13L, jbNeg.read(fd, buf, 14, 0));
        }
    }

    @Test
    @DisplayName("Verify FioPrng uniform bounds, sector alignment, and 10GB address space coverage")
    void testFioPrngUniformBoundsAndSectorAlignment() {
        final long maxBlocks = 2_621_440L; // 10 GB / 4KB
        final int blockSize = 4096;
        final long maxFileSize = maxBlocks * blockSize;

        DatasetManager.FioPrng prng = new DatasetManager.FioPrng(42424242L);
        long minBlock = Long.MAX_VALUE;
        long maxBlock = Long.MIN_VALUE;

        for (int i = 0; i < 100_000; i++) {
            long offset = prng.nextOffset(maxBlocks, blockSize);
            long blockIdx = offset / blockSize;

            assertTrue(blockIdx >= 0 && blockIdx < maxBlocks,
                    "Block index must be in [0, " + maxBlocks + "): got " + blockIdx);
            assertEquals(blockIdx * blockSize, offset,
                    "Offset must equal blockIdx * blockSize");
            assertTrue(IoHelper.checkOffsetAlignment(offset, blockSize),
                    "Offset must be 4KB sector aligned: " + offset);
            assertTrue(offset + blockSize <= maxFileSize,
                    "Offset + 4KB must not exceed 10GB file size: " + (offset + blockSize));

            if (blockIdx < minBlock) minBlock = blockIdx;
            if (blockIdx > maxBlock) maxBlock = blockIdx;
        }

        // Verify broad coverage across the entire 10GB range
        assertTrue(minBlock < 100_000L, "PRNG should reach the start of the 10GB file");
        assertTrue(maxBlock > 2_500_000L, "PRNG should reach the end of the 10GB file");
    }

    @Test
    @DisplayName("Verify FioLatencyHistogram percentile precision, stddev, and formatting")
    void testFioLatencyHistogramAccuracyAndPercentiles() {
        DatasetManager.FioLatencyHistogram hist = new DatasetManager.FioLatencyHistogram();
        assertEquals(0, hist.getCount());
        assertEquals(0, hist.getMinNs());
        assertEquals(0, hist.getMaxNs());

        // Feed uniform values from 100,000 ns (100 µs) to 500,000 ns (500 µs) in steps of 1,000 ns (401 samples)
        for (long ns = 100_000L; ns <= 500_000L; ns += 1_000L) {
            hist.record(ns);
        }

        assertEquals(401, hist.getCount());
        assertEquals(100_000L, hist.getMinNs());
        assertEquals(500_000L, hist.getMaxNs());
        assertEquals(100.0, hist.getMinMicros(), 0.001);
        assertEquals(500.0, hist.getMaxMicros(), 0.001);

        // Expected mean = 300,000 ns (300.0 µs)
        assertEquals(300.0, hist.getMeanMicros(), 0.01);

        // Expected p50 ~= 300 µs (within 1.5% bin resolution)
        double p50 = hist.getPercentileMicros(50.0);
        assertTrue(Math.abs(p50 - 300.0) < 6.0, "p50 should be ~300 µs, got: " + p50);

        // Expected p99 ~= 496 µs
        double p99 = hist.getPercentileMicros(99.0);
        assertTrue(p99 >= 480.0 && p99 <= 505.0, "p99 should be ~496 µs, got: " + p99);

        // Verify summary and percentile string formatting
        String summary = hist.formatFioSummary("clat");
        assertNotNull(summary);
        assertTrue(summary.contains("clat (usec):"));
        assertTrue(summary.contains("avg=300.00"));

        String pctReport = hist.formatFioPercentiles();
        assertNotNull(pctReport);
        assertTrue(pctReport.contains("50.00th="));
        assertTrue(pctReport.contains("99.00th="));
    }

    @Test
    @DisplayName("Verify FioComparisonBenchmark pipeline execution with telemetry ON and OFF")
    void testFioComparisonBenchmarkPipelineExecution() throws Exception {
        Path benchFile = DatasetManager.resolveSingleBenchFile();
        assertNotNull(benchFile, "Single 10GB bench file must resolve");
        assertTrue(Files.exists(benchFile), "Single 10GB bench file must exist");

        DirectLatencyBenchmark.FioComparisonBenchmark.FioBenchmarkState bState =
                new DirectLatencyBenchmark.FioComparisonBenchmark.FioBenchmarkState();
        bState.setup();
        assertEquals(Files.size(benchFile) / DirectLatencyBenchmark.FioComparisonBenchmark.BLOCK_SIZE,
                bState.maxBlocks, "File block count must match size / BLOCK_SIZE");

        DirectLatencyBenchmark.FioComparisonBenchmark.FioThreadState tState =
                new DirectLatencyBenchmark.FioComparisonBenchmark.FioThreadState();
        tState.qd = 16;
        tState.telemetry = true;

        try {
            tState.setup(bState, null);
            StorageAuxCounters storage = new StorageAuxCounters();
            CpuAuxCounters cpu = new CpuAuxCounters();
            DirectLatencyBenchmark.FioComparisonBenchmark bench =
                    new DirectLatencyBenchmark.FioComparisonBenchmark();

            // Run direct read with telemetry ON
            bench.juringDirectRead(tState, bState, storage, cpu, null);
            assertTrue(tState.reapedCount > 0, "Read operations must be reaped");
            assertTrue(tState.clatHistogram.getCount() > 0, "clat histogram must have recorded samples");
            assertTrue(tState.slatHistogram.getCount() > 0, "slat histogram must have recorded samples");
            assertTrue(tState.clatHistogram.getMeanMicros() > 0.0, "clat mean must be positive");

            // Run direct write with telemetry ON
            bench.juringDirectWrite(tState, bState, storage, cpu, null);
            assertTrue(tState.reapedCount > 0, "Write operations must be reaped");

            // Run FileChannel reads and writes
            bench.fileChannelDirectRead(tState, bState, storage, cpu, null);
            bench.fileChannelDirectWrite(tState, bState, storage, cpu, null);
        } catch (Exception e) {
            fail("FioComparisonBenchmark execution failed: " + e.getMessage(), e);
        } finally {
            tState.tearDown();
        }
    }

    @Test
    @DisplayName("Verify FioComparisonBenchmark parseFioJson extracts exact metrics from JSON")
    void testParseFioJsonAccuracy() {
        String sampleJson = """
        {
          "jobs" : [
            {
              "jobname" : "fio_bench",
              "read" : {
                "io_bytes" : 419430400,
                "bw_bytes" : 139810133,
                "iops" : 34133.333333,
                "slat_ns" : { "mean" : 2540.50 },
                "clat_ns" : {
                  "mean" : 185200.75,
                  "percentile" : {
                    "50.000000" : 182000,
                    "99.000000" : 325000,
                    "99.900000" : 410000
                  }
                }
              }
            }
          ]
        }
        """;
        var res = DirectLatencyBenchmark.FioComparisonBenchmark.parseFioJson(sampleJson, "read");
        assertNotNull(res);
        assertEquals(34133L, res.iops());
        assertEquals(139810133.0 / (1024.0 * 1024.0), res.bwMBs(), 0.01);
        assertEquals(2.5405, res.slatAvgUs(), 0.001);
        assertEquals(185.20075, res.clatAvgUs(), 0.001);
        assertEquals(182.0, res.clatP50Us(), 0.001);
        assertEquals(325.0, res.clatP99Us(), 0.001);
        assertEquals(410.0, res.clatP99_9Us(), 0.001);
    }
}

