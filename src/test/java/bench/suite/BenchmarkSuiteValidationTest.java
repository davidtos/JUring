package bench.suite;

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

        VirtualThreadBenchmark.JuringVirtualThreadState jState = new VirtualThreadBenchmark.JuringVirtualThreadState();
        jState.virtualThreads = 100;
        jState.setup(bState);

        try {
            StorageAuxCounters storage = new StorageAuxCounters();
            VirtualThreadBenchmark.DeviceCounters devCounters = new VirtualThreadBenchmark.DeviceCounters();
            bench.juringVirtualThreadRead(jState, bState, storage, devCounters, null);
            assertTrue(storage.bytesProcessed > 0, "Storage counter should have recorded read bytes for JUring");
        } finally {
            jState.tearDown();
        }

        VirtualThreadBenchmark.FileChannelVirtualThreadState fcState = new VirtualThreadBenchmark.FileChannelVirtualThreadState();
        fcState.virtualThreads = 100;
        fcState.setup(bState);

        try {
            StorageAuxCounters storage = new StorageAuxCounters();
            VirtualThreadBenchmark.DeviceCounters devCounters = new VirtualThreadBenchmark.DeviceCounters();
            bench.fileChannelVirtualThreadRead(fcState, bState, storage, devCounters, null);
            assertTrue(storage.bytesProcessed > 0, "Storage counter should have recorded read bytes for FileChannel");
        } finally {
            fcState.tearDown();
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
            tState.setup(bState, null, null);
            StorageAuxCounters storage = new StorageAuxCounters();
            DirectLatencyBenchmark.FioComparisonBenchmark bench =
                    new DirectLatencyBenchmark.FioComparisonBenchmark();

            // Run direct read with telemetry ON
            bench.juringDirectRead(tState, bState, storage, null);
            assertTrue(tState.reapedCount > 0, "Read operations must be reaped");
            assertTrue(tState.iterationHistogram.getTotalCount() > 0, "Iteration histogram must have recorded samples");
            assertTrue(tState.iterationHistogram.getMean() > 0.0, "Latency mean must be positive");

            // Run direct write with telemetry ON
            bench.juringDirectWrite(tState, bState, storage, null);
            assertTrue(tState.reapedCount > 0, "Write operations must be reaped");

            // Run FileChannel reads and writes
            bench.fileChannelDirectRead(tState, bState, storage, null);
            bench.fileChannelDirectWrite(tState, bState, storage, null);
        } catch (Exception e) {
            fail("FioComparisonBenchmark execution failed: " + e.getMessage(), e);
        } finally {
            tState.tearDown();
        }
    }

    @Test
    @DisplayName("Verify scaling suite options respect custom forks and iterations (--f 3 --i 4)")
    void testScalingSuiteCustomOptions() {
        org.openjdk.jmh.runner.options.Options options =
                BenchmarkRunner.buildScalingOptions(new String[]{"--threads-scaling", "--f", "3", "--i", "4"}, 1);
        assertEquals(3, options.getForkCount().orElse(-1), "Forks must be 3");
        assertEquals(4, options.getMeasurementIterations().orElse(-1), "Measurement iterations must be 4");
    }

    @Test
    @DisplayName("Verify record_env.sh generates valid JSON and falls back to unknown")
    void testRecordEnvScript() throws Exception {
        Path benchDir = Path.of("bench");
        Path recordEnvSh = benchDir.resolve("record_env.sh");
        assertTrue(Files.exists(recordEnvSh), "bench/record_env.sh must exist");

        // Delete bench/run_remaining.sh as required by Step 1
        Path runRemaining = benchDir.resolve("run_remaining.sh");
        Files.deleteIfExists(runRemaining);

        // Run the script and verify JSON output
        Path tmpJson = Files.createTempFile("env_test", ".json");
        try {
            Process p = new ProcessBuilder("bash", recordEnvSh.toAbsolutePath().toString(), tmpJson.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            int exit = p.waitFor();
            assertEquals(0, exit, "record_env.sh must exit with 0");
            assertTrue(Files.size(tmpJson) > 0, "env.json must not be empty");

            // Verify with jq
            Process jqProc = new ProcessBuilder("jq", "-e", ".", tmpJson.toAbsolutePath().toString())
                    .start();
            assertEquals(0, jqProc.waitFor(), "jq -e . must succeed on env.json");

            // Test stripped PATH without fio
            Path tmpNoFio = Files.createTempFile("env_nofio", ".json");
            try {
                ProcessBuilder pbNoFio = new ProcessBuilder("bash", recordEnvSh.toAbsolutePath().toString(), tmpNoFio.toAbsolutePath().toString());
                pbNoFio.environment().put("PATH", "/usr/bin:/bin"); // remove any local fio path or test
                // If fio is in /usr/bin, override fio function or PATH to a directory without fio
                Path cleanDir = Files.createTempDirectory("empty_bin");
                // Copy jq, bash, sed, awk to cleanDir so script can run
                for (String cmd : List.of("jq", "bash", "sed", "awk", "uname", "cat", "nproc", "date")) {
                    Path src = Path.of("/usr/bin/" + cmd);
                    if (!Files.exists(src)) src = Path.of("/bin/" + cmd);
                    if (Files.exists(src)) {
                        Files.createSymbolicLink(cleanDir.resolve(cmd), src);
                    }
                }
                pbNoFio.environment().put("PATH", cleanDir.toAbsolutePath().toString());
                Process pNoFio = pbNoFio.start();
                assertEquals(0, pNoFio.waitFor(), "record_env.sh without fio must exit 0");

                Process checkFio = new ProcessBuilder("jq", "-r", ".fio_version", tmpNoFio.toAbsolutePath().toString()).start();
                String fioVer = new String(checkFio.getInputStream().readAllBytes()).trim();
                assertEquals("unknown", fioVer, "fio_version must be 'unknown' when fio is missing");
            } finally {
                Files.deleteIfExists(tmpNoFio);
            }
        } finally {
            Files.deleteIfExists(tmpJson);
        }
    }

    @Test
    @DisplayName("Verify /sys/block/<dev>/stat parsing of field 3 (sectors read)")
    void testBlockStatParser() {
        String fixture = "907621852     3570 7261009042 398507646 123306981    12170 986553400 80843512        0  2573272 479428133   386827        0 435182712    76271      232      703";
        long sectors = IoHelper.parseSectorsRead(fixture);
        assertEquals(7261009042L, sectors, "Field 3 must be parsed as sectors read");
    }

    @Test
    @DisplayName("Verify posix_fadvise(DONTNEED) drops page cache on freshly written file")
    void testPosixFadviseDropsCache() throws Exception {
        Files.createDirectories(Path.of("target"));
        Path tmpFile = Files.createTempFile(Path.of("target"), "cache_drop_test", ".bin");
        try {
            byte[] data = new byte[65536];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
            try (FileChannel fc = FileChannel.open(tmpFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                fc.write(java.nio.ByteBuffer.wrap(data));
                fc.force(true);
            }

            try (FileChannel fc = FileChannel.open(tmpFile, StandardOpenOption.READ)) {
                try (var arena = java.lang.foreign.Arena.ofConfined()) {
                    MemorySegment mapped = fc.map(FileChannel.MapMode.READ_ONLY, 0, data.length, arena);
                    boolean residentBefore = IoHelper.isPageResident(mapped, 0);
                    assertTrue(residentBefore, "Pages should be resident in page cache after write/load");
                }
            }

            // Drop page cache while file is unmapped and synced
            int ret = IoHelper.posixFadvise(tmpFile, 0, 0, IoHelper.POSIX_FADV_DONTNEED);
            assertEquals(0, ret, "posix_fadvise should succeed with 0");

            // Verify page is no longer resident
            try (FileChannel fc = FileChannel.open(tmpFile, StandardOpenOption.READ)) {
                try (var arena = java.lang.foreign.Arena.ofConfined()) {
                    MemorySegment mapped = fc.map(FileChannel.MapMode.READ_ONLY, 0, data.length, arena);
                    boolean residentAfter = IoHelper.isPageResident(mapped, 0);
                    assertFalse(residentAfter, "Page must NOT be resident after posix_fadvise(DONTNEED)");
                }
            }
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    @DisplayName("Verify bench/fio-compare-table.sh on fixture directory with known values")
    void testFioCompareTableFixture() throws Exception {
        Path fixtureDir = Path.of("target/fio-fixture-test");
        Files.createDirectories(fixtureDir);

        Files.writeString(fixtureDir.resolve("jmh-read-1.json"),
                "[{\"primaryMetric\":{\"score\":440000.0,\"scoreError\":2000.0},\"secondaryMetrics\":{\"bytesProcessed\":{\"score\":1760000000.0}}}]");
        Files.writeString(fixtureDir.resolve("jmh-read-2.json"),
                "[{\"primaryMetric\":{\"score\":442000.0,\"scoreError\":2000.0},\"secondaryMetrics\":{\"bytesProcessed\":{\"score\":1768000000.0}}}]");
        Files.writeString(fixtureDir.resolve("fio-default-read-1.json"),
                "{\"jobs\":[{\"read\":{\"iops\":350000.0,\"bw_bytes\":1400000000},\"error\":0}]}");
        Files.writeString(fixtureDir.resolve("fio-default-read-2.json"),
                "{\"jobs\":[{\"read\":{\"iops\":352000.0,\"bw_bytes\":1408000000},\"error\":0}]}");
        Files.writeString(fixtureDir.resolve("fio-best-read-1.json"),
                "{\"jobs\":[{\"read\":{\"iops\":400000.0,\"bw_bytes\":1600000000},\"error\":0}]}");
        Files.writeString(fixtureDir.resolve("fio-best-read-2.json"),
                "{\"jobs\":[{\"read\":{\"iops\":402000.0,\"bw_bytes\":1608000000},\"error\":0}]}");
        Files.writeString(fixtureDir.resolve("fio-best-read.txt"), "32\n");
        Files.writeString(fixtureDir.resolve("fio-enters-read.json"),
                "{\"mode\":\"read\",\"best_batch\":\"32\",\"fio_default_enters_op\":\"0.9850\",\"fio_best_enters_op\":\"0.4410\",\"juring_enters_op\":\"0.4395\"}");

        Process process = new ProcessBuilder("bash", "bench/fio-compare-table.sh", fixtureDir.toString(), "read")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();

        assertEquals(0, code, "fio-compare-table.sh should exit 0 on valid fixture: " + output);
        assertTrue(output.contains("fio (default settings)"), "Output must contain default fio row");
        assertTrue(output.contains("fio (best batching: B=32)"), "Output must contain best fio row");
        assertTrue(output.contains("JUring (JMH)"), "Output must contain JUring row");
        assertTrue(output.contains("0.9850"), "Output must contain fio default enters/op");
        assertTrue(output.contains("0.4410"), "Output must contain fio best enters/op");
        assertTrue(output.contains("0.4395"), "Output must contain JUring enters/op");
        assertTrue(output.contains("+10.0%") || output.contains("+10.1%"), "Output must contain delta against fio best (+10.0%)");
    }

    @Test
    @DisplayName("Verify bench.sh --fio-compare and --fio-sweep fail if fio exits with non-zero code")
    void testFioStubExitCodeFails() throws Exception {
        Path stubDir = Path.of("target/fio-stub-fail");
        Files.createDirectories(stubDir);
        Path stubBin = stubDir.resolve("fio");
        Files.writeString(stubBin, "#!/bin/sh\nexit 1\n");
        stubBin.toFile().setExecutable(true);

        String pathEnv = stubDir.toAbsolutePath() + ":" + System.getenv("PATH");

        // Test --fio-compare
        ProcessBuilder pbCompare = new ProcessBuilder("bash", "bench.sh", "--fio-compare", "1", "--runtime", "1",
                "--results-dir", "target/stub-fail-compare-out");
        pbCompare.environment().put("PATH", pathEnv);
        Process pCompare = pbCompare.start();
        int compareCode = pCompare.waitFor();
        assertNotEquals(0, compareCode, "bench.sh --fio-compare must fail when fio exits 1");

        // Test --fio-sweep
        ProcessBuilder pbSweep = new ProcessBuilder("bash", "bench.sh", "--fio-sweep", "--runtime", "1",
                "--results-dir", "target/stub-fail-sweep-out");
        pbSweep.environment().put("PATH", pathEnv);
        Process pSweep = pbSweep.start();
        int sweepCode = pSweep.waitFor();
        assertNotEquals(0, sweepCode, "bench.sh --fio-sweep must fail when fio exits 1");
    }

    @Test
    @DisplayName("Verify bench.sh --fio-compare and --fio-sweep fail if fio reports zero IOPS")
    void testFioStubZeroIopsFails() throws Exception {
        Path stubDir = Path.of("target/fio-stub-zero");
        Files.createDirectories(stubDir);
        Path stubBin = stubDir.resolve("fio");
        String stubScript = "#!/bin/sh\n" +
                "for arg in \"$@\"; do\n" +
                "  case \"$arg\" in\n" +
                "    --output=*)\n" +
                "      out=\"${arg#--output=}\"\n" +
                "      echo '{\"jobs\":[{\"read\":{\"iops\":0,\"bw_bytes\":0,\"total_ios\":0},\"write\":{\"iops\":0,\"bw_bytes\":0,\"total_ios\":0},\"error\":0}]}' > \"$out\"\n" +
                "      ;;\n" +
                "  esac\n" +
                "done\n" +
                "exit 0\n";
        Files.writeString(stubBin, stubScript);
        stubBin.toFile().setExecutable(true);

        String pathEnv = stubDir.toAbsolutePath() + ":" + System.getenv("PATH");

        // Test --fio-compare
        ProcessBuilder pbCompare = new ProcessBuilder("bash", "bench.sh", "--fio-compare", "1", "--runtime", "1",
                "--results-dir", "target/stub-zero-compare-out");
        pbCompare.environment().put("PATH", pathEnv);
        Process pCompare = pbCompare.start();
        int compareCode = pCompare.waitFor();
        assertNotEquals(0, compareCode, "bench.sh --fio-compare must fail when fio reports zero IOPS");

        // Test --fio-sweep
        ProcessBuilder pbSweep = new ProcessBuilder("bash", "bench.sh", "--fio-sweep", "--runtime", "1",
                "--results-dir", "target/stub-zero-sweep-out");
        pbSweep.environment().put("PATH", pathEnv);
        Process pSweep = pbSweep.start();
        int sweepCode = pSweep.waitFor();
        assertNotEquals(0, sweepCode, "bench.sh --fio-sweep must fail when fio reports zero IOPS");
    }

    @Test
    @DisplayName("Verify python3 generate_readme_tables.py unit tests and --check / --write pass")
    void testGenerateReadmeTables() throws Exception {
        Process pyTest = new ProcessBuilder("python3", "-m", "unittest", "bench/test_generate_readme_tables.py")
                .redirectErrorStream(true)
                .start();
        String testOutput = new String(pyTest.getInputStream().readAllBytes());
        int testExit = pyTest.waitFor();
        assertEquals(0, testExit, "generate_readme_tables.py unit tests must pass: " + testOutput);

        Path tempReadme = Path.of("target/test-README.md");
        Files.copy(Path.of("README.md"), tempReadme, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Process pyWrite = new ProcessBuilder("python3", "bench/generate_readme_tables.py", "--write", tempReadme.toString(), "results/2026-11-05")
                .redirectErrorStream(true)
                .start();
        String writeOutput = new String(pyWrite.getInputStream().readAllBytes());
        int writeExit = pyWrite.waitFor();
        assertEquals(0, writeExit, "generate_readme_tables.py --write must succeed: " + writeOutput);

        Process pyCheck = new ProcessBuilder("python3", "bench/generate_readme_tables.py", "--check", tempReadme.toString(), "results/2026-11-05")
                .redirectErrorStream(true)
                .start();
        String checkOutput = new String(pyCheck.getInputStream().readAllBytes());
        int checkExit = pyCheck.waitFor();
        assertEquals(0, checkExit, "generate_readme_tables.py --check must exit 0: " + checkOutput);
    }

    @Test
    @DisplayName("Verify bench/log_drive_state.sh outputs valid JSON with drive and mount metadata")
    void testLogDriveStateScript() throws Exception {
        Path outJson = Path.of("target/test-drive-state.json");
        Files.deleteIfExists(outJson);
        Process p = new ProcessBuilder("bash", "bench/log_drive_state.sh", outJson.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        assertEquals(0, exitCode, "log_drive_state.sh must exit 0: " + output);
        assertTrue(Files.exists(outJson), "Output file must exist");
        String content = Files.readString(outJson);
        assertTrue(content.contains("\"mount_point\""), "JSON must contain mount_point");
        assertTrue(content.contains("\"device\""), "JSON must contain device");
        assertTrue(content.contains("\"temperature_c\""), "JSON must contain temperature_c");
    }

    @Test
    @DisplayName("Verify bench/count_iou_wrk.sh samples worker threads and outputs valid JSON")
    void testCountIouWrkScript() throws Exception {
        Process dummy = new ProcessBuilder("sleep", "2").start();
        long pid = dummy.pid();

        Path outJson = Path.of("target/test-iou-wrk.json");
        Files.deleteIfExists(outJson);
        Process p = new ProcessBuilder("bash", "bench/count_iou_wrk.sh", String.valueOf(pid), outJson.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        dummy.waitFor();

        assertEquals(0, exitCode, "count_iou_wrk.sh must exit 0: " + output);
        assertTrue(Files.exists(outJson), "Output file must exist");
        String content = Files.readString(outJson);
        assertTrue(content.contains("\"target_pid\""), "JSON must contain target_pid");
        assertTrue(content.contains("\"max_iou_wrk_threads\""), "JSON must contain max_iou_wrk_threads");
        assertTrue(content.contains("\"sample_count\""), "JSON must contain sample_count");
    }

    @Test
    @DisplayName("Verify bench/precondition.sh runs fio randwrite windows and logs JSON")
    void testPreconditionScript() throws Exception {
        Path tempFile = Path.of("target/precond-test.bin");
        try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            for (int i = 0; i < 4; i++) {
                buf.clear();
                fc.write(buf);
            }
            fc.force(true);
        }

        Path resDir = Path.of("target/precond-test-results");
        Files.createDirectories(resDir);
        Process p = new ProcessBuilder("bash", "bench/precondition.sh", tempFile.toString(), "0.05", resDir.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        assertEquals(0, exitCode, "precondition.sh must exit 0: " + output);

        Path outJson = resDir.resolve("precondition-precond-test.json");
        assertTrue(Files.exists(outJson), "precondition JSON output must exist: " + output);
        String content = Files.readString(outJson);
        assertTrue(content.contains("\"target_file\""), "JSON must contain target_file");
        assertTrue(content.contains("\"windows\""), "JSON must contain windows");
    }
}


