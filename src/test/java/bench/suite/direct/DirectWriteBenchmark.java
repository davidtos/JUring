package bench.suite.direct;

import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import bench.suite.common.StorageAuxCounters;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import com.davidvlijmincx.lio.api.ResultConsumer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.ThreadParams;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * Dual-Mode Write Benchmark Suite (Option 3: Burst & Sustained Modes).
 *
 * SSD Physics & Methodology:
 * NVMe SSDs dynamically allocate a pool of TLC/QLC flash cells as fast 1-bit pseudo-SLC cache (~10-24 GB).
 * Direct writes hitting the pseudo-SLC cache reach peak hardware throughput (~300k - 470k IOPS).
 * When the SLC cache is exhausted, throughput drops to the flash memory's TLC folding speed (~190k - 215k IOPS).
 *
 * If FileChannel and JUring are run sequentially without drive isolation, whichever runs second
 * inherits an exhausted SLC cache and suffers unfair throttling and high variance.
 *
 * This suite provides two distinct, fully isolated benchmark operating modes:
 *
 * 1. Burst Mode (--burst):
 *    - Objective: Measure peak pseudo-SLC queue depth scaling on a rested drive.
 *    - Procedure: TRIM the drive (fstrim -v + sync) and idle rest (15s) before EACH engine runs.
 *    - Measurement: Short 5-second measurement window (1s warmup) so total writes stay within SLC cache.
 *    - Matching fio config:
 *        [fio_randwrite_burst_qd64]
 *        ioengine=io_uring, direct=1, bs=4k, iodepth=64, fixedbufs=1, registerfiles=1
 *        runtime=5s, ramp_time=0s, filename=single_file_bench.bin
 *
 * 2. Sustained Mode (--sustained):
 *    - Objective: Measure steady-state TLC folding limits under heavy write saturation.
 *    - Procedure: Precondition the SSD by direct-writing 20 GB of data before EACH engine runs,
 *                 forcing the NVMe controller into steady-state TLC folding.
 *    - Measurement: 10s measurement window (5s warmup) without TRIM/rest pauses.
 *    - Matching fio config:
 *        [fio_randwrite_sustained_qd64]
 *        ioengine=io_uring, direct=1, bs=4k, iodepth=64, fixedbufs=1, registerfiles=1
 *        runtime=30s, ramp_time=10s, filename=single_file_bench.bin
 */
public class DirectWriteBenchmark {

    public enum WriteBenchmarkMode {
        BURST,
        SUSTAINED;

        public static WriteBenchmarkMode resolveMode() {
            String prop = System.getProperty("juring.bench.write.mode");
            if (prop != null && !prop.isBlank()) {
                try {
                    return WriteBenchmarkMode.valueOf(prop.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    System.err.printf("[DirectWriteBenchmark] Unknown mode '%s', defaulting to BURST.%n", prop);
                }
            }
            return BURST;
        }
    }

    public static class SsdCacheManager {
        public static final long DEFAULT_PRECONDITION_BYTES = 20L * 1024L * 1024L * 1024L; // 20 GB
        public static final int DEFAULT_REST_SECONDS = 15;

        public static void prepareDriveForTrial(String engine, DatasetManager dataset) {
            if (Boolean.getBoolean("juring.bench.drive.prepped")) {
                return;
            }
            WriteBenchmarkMode mode = WriteBenchmarkMode.resolveMode();
            Path[] targetFiles = dataset.getUniquePaths();
            Path baseDir = dataset.getBaseDir() != null ? dataset.getBaseDir() : (targetFiles.length > 0 ? targetFiles[0].getParent() : null);
            prepareDrive(mode, engine, baseDir, targetFiles);
        }

        public static void prepareDrive(WriteBenchmarkMode mode, String engine, Path baseDir, Path targetFile) {
            prepareDrive(mode, engine, baseDir, targetFile != null ? new Path[]{targetFile} : new Path[0]);
        }

        public static void prepareDrive(WriteBenchmarkMode mode, String engine, Path baseDir, Path[] targetFiles) {
            boolean doTrim = !"false".equalsIgnoreCase(System.getProperty("juring.bench.trim", "true"));
            int restSeconds = Integer.getInteger("juring.bench.rest.seconds", mode == WriteBenchmarkMode.BURST ? DEFAULT_REST_SECONDS : 0);
            long preconditionGb = Long.getLong("juring.bench.precondition.gb", mode == WriteBenchmarkMode.SUSTAINED ? 20L : 0L);

            System.out.printf("%n================================================================================%n");
            System.out.printf("  Preparing SSD for [%s] Direct Write (Mode: %s)%n", engine, mode);
            System.out.printf("================================================================================%n");

            if (mode == WriteBenchmarkMode.BURST) {
                if (doTrim && baseDir != null) {
                    executeTrim(baseDir);
                }
                if (restSeconds > 0) {
                    restDrive(restSeconds, engine + " Burst Mode");
                }
            } else { // SUSTAINED
                if (preconditionGb > 0 && targetFiles != null && targetFiles.length > 0) {
                    precondition(targetFiles, preconditionGb * 1024L * 1024L * 1024L);
                }
            }
        }

        public static boolean executeTrim(Path path) {
            if (path == null) return false;
            Path target = Files.isDirectory(path) ? path : path.getParent();
            if (target == null) target = Path.of(".");
            System.out.printf("[SsdCacheManager] Executing TRIM on %s...%n", target.toAbsolutePath());
            try {
                Process process = new ProcessBuilder("fstrim", "-v", target.toAbsolutePath().toString())
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    String output = new String(process.getInputStream().readAllBytes()).trim();
                    System.out.printf("[SsdCacheManager] TRIM successful: %s%n", output);
                } else {
                    String output = "";
                    try {
                        output = new String(process.getInputStream().readAllBytes()).trim();
                    } catch (Exception ignored) {}
                    System.out.printf("[SsdCacheManager] Note: fstrim exited with code %d: %s (run with sudo or ensure fstrim permissions if needed).%n",
                            finished ? process.exitValue() : -1, output);
                }
            } catch (Exception e) {
                System.out.printf("[SsdCacheManager] Note: Could not execute fstrim (%s). Continuing.%n", e.getMessage());
            }

            try {
                System.out.println("[SsdCacheManager] Syncing storage buffers (sync)...");
                Process syncProc = new ProcessBuilder("sync").start();
                syncProc.waitFor(30, TimeUnit.SECONDS);
                System.out.println("[SsdCacheManager] Storage sync complete.");
                return true;
            } catch (Exception e) {
                System.out.printf("[SsdCacheManager] sync warning: %s%n", e.getMessage());
                return false;
            }
        }

        public static void restDrive(int seconds, String reason) {
            if (seconds <= 0) return;
            System.out.printf("[SsdCacheManager] Idle cooldown: resting drive for %d seconds (%s) to allow NVMe pseudo-SLC cache recovery...%n",
                    seconds, reason);
            long startTime = System.currentTimeMillis();
            for (int i = seconds; i > 0; i--) {
                if (i % 5 == 0 || i <= 3) {
                    System.out.printf("[SsdCacheManager] Cooling down... %d s remaining%n", i);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("[SsdCacheManager] Cooldown complete (%,d ms elapsed).%n", elapsed);
        }

        public static void precondition(Path targetFile, long bytesToPrecondition) {
            precondition(targetFile != null ? new Path[]{targetFile} : new Path[0], bytesToPrecondition);
        }

        public static void precondition(Path[] targetFiles, long bytesToPrecondition) {
            if (targetFiles == null || targetFiles.length == 0 || bytesToPrecondition <= 0) {
                return;
            }
            try {
                Path firstFile = targetFiles[0];
                if (!Files.exists(firstFile)) {
                    System.out.printf("[SsdCacheManager] Target file %s does not exist, creating...%n", firstFile);
                    DatasetManager.ensureSingleBenchFile(firstFile.getParent(), DatasetManager.DEFAULT_SINGLE_FILE_SIZE);
                }
                int chunkSize = 1024 * 1024; // 1 MB direct chunk
                System.out.printf("[SsdCacheManager] Preconditioning: writing %,d MB direct writes across %d target file(s) to saturate pseudo-SLC cache...%n",
                        bytesToPrecondition / (1024 * 1024), targetFiles.length);
                long startTime = System.currentTimeMillis();

                ByteBuffer buf = IoHelper.allocateAligned(chunkSize, IoHelper.DEFAULT_SECTOR_SIZE);
                for (int i = 0; i < chunkSize; i++) {
                    buf.put((byte) 0xA5);
                }
                buf.flip();

                OpenOption direct = IoHelper.getDirectOpenOption();
                OpenOption[] openOpts = (direct != null)
                        ? new OpenOption[]{ StandardOpenOption.WRITE, direct }
                        : new OpenOption[]{ StandardOpenOption.WRITE };

                long written = 0;
                int fileIdx = 0;
                long offset = 0;

                while (written < bytesToPrecondition) {
                    Path file = targetFiles[fileIdx % targetFiles.length];
                    if (!Files.exists(file)) {
                        fileIdx++;
                        continue;
                    }
                    long fileSize = Files.size(file);
                    if (fileSize < DatasetManager.DEFAULT_BUFFER_SIZE) {
                        fileIdx++;
                        continue;
                    }
                    try (FileChannel fc = FileChannel.open(file, openOpts)) {
                        while (written < bytesToPrecondition && offset < fileSize) {
                            long toWrite = Math.min(chunkSize, Math.min(bytesToPrecondition - written, fileSize - offset));
                            if (toWrite < IoHelper.DEFAULT_SECTOR_SIZE) {
                                break;
                            }
                            // Sector-align toWrite
                            toWrite = (toWrite / IoHelper.DEFAULT_SECTOR_SIZE) * IoHelper.DEFAULT_SECTOR_SIZE;
                            if (toWrite <= 0) break;

                            buf.position(0);
                            buf.limit((int) toWrite);
                            int n = fc.write(buf, offset);
                            if (n <= 0) break;
                            offset += n;
                            written += n;
                        }
                        fc.force(true);
                    }
                    offset = 0;
                    fileIdx++;
                }

                try {
                    Process syncProc = new ProcessBuilder("sync").start();
                    syncProc.waitFor(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {}

                long elapsed = Math.max(1, System.currentTimeMillis() - startTime);
                double mbWritten = written / (1024.0 * 1024.0);
                double sec = elapsed / 1000.0;
                System.out.printf("[SsdCacheManager] Preconditioning complete: %,.1f MB written in %.2f s (%,.1f MB/s). Pseudo-SLC cache saturated.%n",
                        mbWritten, sec, mbWritten / sec);
            } catch (Exception e) {
                System.err.printf("[SsdCacheManager] Preconditioning warning: %s%n", e.getMessage());
            }
        }
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        @Param({"SINGLE_FILE", "MULTI_FILE"})
        public String distribution = "SINGLE_FILE";

        public DatasetManager dataset;

        @Setup(Level.Trial)
        public void setup() {
            DatasetManager.FileDistribution dist = DatasetManager.FileDistribution.valueOf(distribution);
            dataset = new DatasetManager(
                    DatasetManager.WorkloadType.WRITE,
                    dist
            );
        }
    }

    @State(Scope.Benchmark)
    public static class JuringBenchmarkState {

        @Param({"SINGLE_FILE", "MULTI_FILE"})
        public String distribution = "SINGLE_FILE";

        public DatasetManager dataset;

        @Setup(Level.Trial)
        public void setup() {
            DatasetManager.FileDistribution dist = DatasetManager.FileDistribution.valueOf(distribution);
            dataset = new DatasetManager(
                    DatasetManager.WorkloadType.WRITE,
                    dist
            );
            SsdCacheManager.prepareDriveForTrial("JUring", dataset);
        }
    }

    @State(Scope.Benchmark)
    public static class FileChannelBenchmarkState {

        @Param({"SINGLE_FILE", "MULTI_FILE"})
        public String distribution = "SINGLE_FILE";

        public DatasetManager dataset;

        @Setup(Level.Trial)
        public void setup() {
            DatasetManager.FileDistribution dist = DatasetManager.FileDistribution.valueOf(distribution);
            dataset = new DatasetManager(
                    DatasetManager.WorkloadType.WRITE,
                    dist
            );
            SsdCacheManager.prepareDriveForTrial("FileChannel", dataset);
        }
    }

    @State(Scope.Thread)
    public static class JuringThreadState {

        @Param({"1", "64"})
        public int qd = 64;

        public JUring ring;
        public FileDescriptor[] fileDescriptors;
        public int inFlight;
        public int reapedCount;
        public ResultConsumer consumer;
        public boolean isSingleFile;
        public long maxBlocks;
        public int startFile;
        public int localNumFiles;
        public long[] fileMaxBlocks;
        public DatasetManager.FioPrng prng;

        @Setup(Level.Trial)
        public void setup(JuringBenchmarkState benchState, ThreadParams params) {
            int threadIndex = params.getThreadIndex();
            int threadCount = params.getThreadCount();
            int safeBufferCount = Math.max(qd, Math.min(64, IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount)));

            this.isSingleFile = "SINGLE_FILE".equals(benchState.distribution);
            long seed = System.nanoTime() ^ ((long) threadIndex * 31L);
            this.prng = new DatasetManager.FioPrng(seed);
            if (this.isSingleFile) {
                long size = benchState.dataset.getFileSizes()[0];
                this.maxBlocks = size / DatasetManager.DEFAULT_BUFFER_SIZE;
                this.startFile = 0;
                this.localNumFiles = 1;
                this.fileMaxBlocks = new long[]{this.maxBlocks};
                System.out.printf("[DirectWriteBenchmark] JUring Thread #%d initialized PRNG with seed 0x%016x over %,d blocks (%,.1f MB)%n",
                        threadIndex, seed, maxBlocks, (size / (1024.0 * 1024.0)));
            } else {
                long[] sizes = benchState.dataset.getFileSizes();
                int numFiles = sizes.length;
                int filesPerThread = Math.max(1, numFiles / Math.max(1, threadCount));
                this.startFile = threadIndex * filesPerThread;
                int endFile = (threadIndex == threadCount - 1) ? numFiles : Math.min(numFiles, this.startFile + filesPerThread);
                this.localNumFiles = Math.max(1, endFile - this.startFile);
                this.fileMaxBlocks = new long[numFiles];
                long totalBlocks = 0;
                for (int i = 0; i < numFiles; i++) {
                    this.fileMaxBlocks[i] = Math.max(1L, sizes[i] / DatasetManager.DEFAULT_BUFFER_SIZE);
                    if (i >= this.startFile && i < endFile) {
                        totalBlocks += this.fileMaxBlocks[i];
                    }
                }
                this.maxBlocks = this.fileMaxBlocks[this.startFile];
                System.out.printf("[DirectWriteBenchmark] JUring Thread #%d initialized MULTI_FILE PRNG over files [%d..%d) (%,d blocks)%n",
                        threadIndex, this.startFile, endFile, totalBlocks);
            }
            this.inFlight = 0;

            String ringFlagsMode = System.getProperty("juring.bench.ring.flags", "defer").trim().toLowerCase();
            if ("default".equals(ringFlagsMode) || "fio".equals(ringFlagsMode) || "standard".equals(ringFlagsMode)) {
                System.out.printf("[DirectWriteBenchmark] Ring initialized in STANDARD FIO mode (no DEFER_TASKRUN, 0 flags)%n");
                ring = new JUring(Math.max(256, qd * 2));
            } else if ("sqpoll".equals(ringFlagsMode)) {
                System.out.printf("[DirectWriteBenchmark] Ring initialized with IORING_SETUP_SQPOLL%n");
                ring = new JUring(Math.max(256, qd * 2), 2000, IORING_SETUP_SQPOLL);
            } else {
                System.out.printf("[DirectWriteBenchmark] Ring initialized in DEFER_TASKRUN mode (SINGLE_ISSUER + DEFER_TASKRUN)%n");
                ring = new JUring(Math.max(256, qd * 2),
                        IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN,
                        IORING_SETUP_DEFER_TASKRUN);
            }

            fileDescriptors = benchState.dataset.openFileDescriptors(LinuxOpenOptions.WRITE_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register write files in JUring: " + regRet);
            }

            MemorySegment[] registeredBuffers = ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, safeBufferCount);
            for (MemorySegment regBuf : registeredBuffers) {
                regBuf.fill((byte) 0x5A);
            }

            this.reapedCount = 0;
            this.consumer = result -> {
                long res = result.result();
                if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("Direct write failed: expected "
                            + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + res);
                }
                this.reapedCount++;
                this.ring.checkInBuffer(result.bufferIdx());
            };
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (ring != null && inFlight > 0) {
                try {
                    while (inFlight > 0) {
                        int reaped = ring.submitAndWaitForBatchResult(1, qd, result -> {
                            this.ring.checkInBuffer(result.bufferIdx());
                        });
                        inFlight = Math.max(0, inFlight - reaped);
                    }
                } catch (Exception ignored) {}
            }
            DatasetManager.closeFileDescriptors(fileDescriptors);
            if (ring != null) {
                ring.close();
            }
        }
    }

    @State(Scope.Thread)
    public static class FileChannelThreadState {
        public FileChannel[] channels;
        public ByteBuffer writeBuffer;
        public MemorySegment writeSegment;
        public boolean isSingleFile;
        public long maxBlocks;
        public int startFile;
        public int localNumFiles;
        public long[] fileMaxBlocks;
        public DatasetManager.FioPrng prng;

        @Setup(Level.Trial)
        public void setup(FileChannelBenchmarkState benchState, ThreadParams params) {
            int threadIndex = params.getThreadIndex();
            int threadCount = params.getThreadCount();
            this.isSingleFile = "SINGLE_FILE".equals(benchState.distribution);
            long seed = System.nanoTime() ^ ((long) threadIndex * 31L);
            this.prng = new DatasetManager.FioPrng(seed);
            if (this.isSingleFile) {
                long size = benchState.dataset.getFileSizes()[0];
                this.maxBlocks = size / DatasetManager.DEFAULT_BUFFER_SIZE;
                this.startFile = 0;
                this.localNumFiles = 1;
                this.fileMaxBlocks = new long[]{this.maxBlocks};
            } else {
                long[] sizes = benchState.dataset.getFileSizes();
                int numFiles = sizes.length;
                int filesPerThread = Math.max(1, numFiles / Math.max(1, threadCount));
                this.startFile = threadIndex * filesPerThread;
                int endFile = (threadIndex == threadCount - 1) ? numFiles : Math.min(numFiles, this.startFile + filesPerThread);
                this.localNumFiles = Math.max(1, endFile - this.startFile);
                this.fileMaxBlocks = new long[numFiles];
                for (int i = 0; i < numFiles; i++) {
                    this.fileMaxBlocks[i] = Math.max(1L, sizes[i] / DatasetManager.DEFAULT_BUFFER_SIZE);
                }
                this.maxBlocks = this.fileMaxBlocks[this.startFile];
            }

            channels = benchState.dataset.openFileChannels(true);

            writeBuffer = IoHelper.allocateAligned(DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);
            for (int i = 0; i < DatasetManager.DEFAULT_BUFFER_SIZE; i++) {
                writeBuffer.put((byte) 0x5A);
            }
            writeBuffer.flip();
            writeSegment = MemorySegment.ofBuffer(writeBuffer);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            DatasetManager.closeChannels(channels);
        }
    }

    @BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
    @Fork(value = 1, jvmArgs = {
            "--enable-native-access=ALL-UNNAMED"
    })
    @Threads(1)
    public static class JuringDirectWriteBenchmark {

        @Benchmark
        public void juringDirectWrite(JuringThreadState state, JuringBenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
            final JUring ring = state.ring;
            final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
            final int qd = state.qd;
            final int waitBatch = 1;
            final boolean isSingle = state.isSingleFile;
            final long maxBlocks = state.maxBlocks;
            final int startFile = state.startFile;
            final int localNumFiles = state.localNumFiles;
            final long[] fileMaxBlocks = state.fileMaxBlocks;
            final DatasetManager.FioPrng prng = state.prng;

            int inFlight = state.inFlight;

            while (inFlight < qd) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) break;
                if (isSingle) {
                    long offset = prng.nextOffset(maxBlocks, DatasetManager.DEFAULT_BUFFER_SIZE);
                    ring.prepareWriteFixed(0, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                } else {
                    int fileIdx = startFile + (int) prng.nextBlockIndex(localNumFiles);
                    long offset = prng.nextOffset(fileMaxBlocks[fileIdx], DatasetManager.DEFAULT_BUFFER_SIZE);
                    ring.prepareWriteFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                }
                inFlight++;
            }

            int reapedTotal = 0;

            while (reapedTotal < totalTasks) {
                int remaining = totalTasks - reapedTotal;
                int minWait = Math.max(1, Math.min(waitBatch, Math.min(remaining, inFlight)));
                int reaped = ring.submitAndWaitForBatchResult(minWait, qd, state.consumer);
                inFlight -= reaped;
                reapedTotal += reaped;

                while (inFlight < qd) {
                    int bufIdx = ring.checkOutBuffer();
                    if (bufIdx < 0) break;
                    if (isSingle) {
                        long offset = prng.nextOffset(maxBlocks, DatasetManager.DEFAULT_BUFFER_SIZE);
                        ring.prepareWriteFixed(0, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                    } else {
                        int fileIdx = startFile + (int) prng.nextBlockIndex(localNumFiles);
                        long offset = prng.nextOffset(fileMaxBlocks[fileIdx], DatasetManager.DEFAULT_BUFFER_SIZE);
                        ring.prepareWriteFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                    }
                    inFlight++;
                }
            }

            state.inFlight = inFlight;
            state.reapedCount = reapedTotal;

            counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
            blackhole.consume(state.reapedCount);
        }
    }

    @BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
    @Fork(value = 1, jvmArgs = {
            "--enable-native-access=ALL-UNNAMED"
    })
    @Threads(1)
    public static class FileChannelDirectWriteBenchmark {

        @Benchmark
        public void fileChannelDirectWrite(FileChannelThreadState state, FileChannelBenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
            final FileChannel[] channels = state.channels;
            final ByteBuffer buf = state.writeBuffer;
            final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;

            if (state.isSingleFile) {
                final FileChannel fc = channels[0];
                final long maxBlocks = state.maxBlocks;
                final DatasetManager.FioPrng prng = state.prng;
                for (int i = 0; i < totalTasks; i++) {
                    long offset = prng.nextOffset(maxBlocks, DatasetManager.DEFAULT_BUFFER_SIZE);
                    buf.position(0);
                    int written = fc.write(buf, offset);
                    if (written != DatasetManager.DEFAULT_BUFFER_SIZE) {
                        throw new IllegalStateException("FileChannel direct write failed: expected "
                                + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + written);
                    }
                    blackhole.consume(written);
                }
            } else {
                final int startFile = state.startFile;
                final int localNumFiles = state.localNumFiles;
                final long[] fileMaxBlocks = state.fileMaxBlocks;
                final DatasetManager.FioPrng prng = state.prng;

                for (int i = 0; i < totalTasks; i++) {
                    int fileIdx = startFile + (int) prng.nextBlockIndex(localNumFiles);
                    long offset = prng.nextOffset(fileMaxBlocks[fileIdx], DatasetManager.DEFAULT_BUFFER_SIZE);
                    FileChannel fc = channels[fileIdx];
                    buf.position(0);
                    int written = fc.write(buf, offset);
                    if (written != DatasetManager.DEFAULT_BUFFER_SIZE) {
                        throw new IllegalStateException("FileChannel direct write failed: expected "
                                + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + written);
                    }
                    blackhole.consume(written);
                }
            }

            counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        }
    }

    private static void printUsage() {
        System.out.println("Option 3: Dual-Mode Write Benchmark Suite");
        System.out.println("Usage: java DirectWriteBenchmark [options]");
        System.out.println();
        System.out.println("Modes:");
        System.out.println("  --burst              Burst mode: test peak pseudo-SLC cache performance on rested drive");
        System.out.println("                       (1s warmup, 5s measurement, TRIM + 15s rest between runs)");
        System.out.println("  --sustained          Sustained mode: test steady-state TLC folding limits");
        System.out.println("                       (5s warmup, 10s measurement, 20 GB direct preconditioning)");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --engine=<eng>       'all' (default), 'juring', or 'filechannel'");
        System.out.println("  --distribution=<dst> 'SINGLE_FILE' (default) or 'MULTI_FILE'");
        System.out.println("  --qd=<n>             Queue depth for JUring (default: 64)");
        System.out.println("  --runtime=<sec>      Measurement time per iteration in seconds (default: 5 burst, 10 sustained)");
        System.out.println("  --warmup=<sec>       Warmup time per iteration in seconds (default: 1 burst, 5 sustained)");
        System.out.println("  --iterations=<n>     Measurement iterations (default: 3)");
        System.out.println("  --forks=<n>          JMH forks (default: 1)");
        System.out.println("  --trim / --no-trim   Enable/disable TRIM execution");
        System.out.println("  --rest-seconds=<n>   Rest cooldown seconds between runs (default: 15 for burst, 0 for sustained)");
        System.out.println("  --precondition-gb=<n>GB to pre-write before run (default: 20 for sustained, 0 for burst)");
        System.out.println("  --help, -h           Show this help message");
    }

    public static void main(String[] args) throws Exception {
        WriteBenchmarkMode mode = WriteBenchmarkMode.BURST;
        String engine = "all";
        String distribution = "SINGLE_FILE";
        int qd = 64;
        int warmupTime = -1;
        int warmupIters = -1;
        int runTime = -1;
        int runIters = -1;
        int forks = 1;
        Boolean explicitTrim = null;
        int restSeconds = -1;
        long preconditionGb = -1;
        String ringFlags = "defer";

        for (String arg : args) {
            if ("--burst".equalsIgnoreCase(arg) || "-burst".equalsIgnoreCase(arg)) {
                mode = WriteBenchmarkMode.BURST;
            } else if ("--sustained".equalsIgnoreCase(arg) || "-sustained".equalsIgnoreCase(arg)) {
                mode = WriteBenchmarkMode.SUSTAINED;
            } else if (arg.startsWith("--engine=")) {
                engine = arg.substring("--engine=".length()).toLowerCase();
            } else if (arg.startsWith("--ring-flags=")) {
                ringFlags = arg.substring("--ring-flags=".length()).toLowerCase();
            } else if (arg.startsWith("--distribution=")) {
                distribution = arg.substring("--distribution=".length()).toUpperCase();
            } else if (arg.startsWith("--qd=")) {
                qd = Integer.parseInt(arg.substring("--qd=".length()));
            } else if (arg.startsWith("--runtime=")) {
                runTime = Integer.parseInt(arg.substring("--runtime=".length()));
            } else if (arg.startsWith("--warmup=")) {
                warmupTime = Integer.parseInt(arg.substring("--warmup=".length()));
            } else if (arg.startsWith("--iterations=")) {
                runIters = Integer.parseInt(arg.substring("--iterations=".length()));
            } else if (arg.startsWith("--forks=")) {
                forks = Integer.parseInt(arg.substring("--forks=".length()));
            } else if ("--trim".equalsIgnoreCase(arg)) {
                explicitTrim = true;
            } else if ("--no-trim".equalsIgnoreCase(arg)) {
                explicitTrim = false;
            } else if (arg.startsWith("--rest-seconds=")) {
                restSeconds = Integer.parseInt(arg.substring("--rest-seconds=".length()));
            } else if (arg.startsWith("--precondition-gb=")) {
                preconditionGb = Long.parseLong(arg.substring("--precondition-gb=".length()));
            } else if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                printUsage();
                return;
            }
        }

        boolean trim;
        if (mode == WriteBenchmarkMode.BURST) {
            if (warmupTime < 0) warmupTime = 1;
            if (warmupIters < 0) warmupIters = 1;
            if (runTime < 0) runTime = 5;
            if (runIters < 0) runIters = 3;
            if (restSeconds < 0) restSeconds = SsdCacheManager.DEFAULT_REST_SECONDS;
            if (preconditionGb < 0) preconditionGb = 0;
            trim = explicitTrim != null ? explicitTrim : true;
        } else {
            if (warmupTime < 0) warmupTime = 5;
            if (warmupIters < 0) warmupIters = 2;
            if (runTime < 0) runTime = 10;
            if (runIters < 0) runIters = 3;
            if (restSeconds < 0) restSeconds = 0;
            if (preconditionGb < 0) preconditionGb = 20;
            trim = explicitTrim != null ? explicitTrim : false;
        }

        System.out.println("================================================================================");
        System.out.println("  Direct Write Benchmark Suite (Option 3 Dual-Mode)");
        System.out.printf("  Active Mode:     %s (%s)%n", mode,
                mode == WriteBenchmarkMode.BURST ? "Peak pseudo-SLC cache speed on rested drive"
                        : "Steady-state TLC folding speed with preconditioning");
        System.out.printf("  Engine(s):       %s%n", engine);
        System.out.printf("  Ring Flags:      %s (%s)%n", ringFlags,
                "fio".equals(ringFlags) || "standard".equals(ringFlags) ? "standard multi-issuer fio-like (0 flags)"
                        : "sqpoll".equals(ringFlags) ? "IORING_SETUP_SQPOLL kernel thread polling"
                        : "IORING_SETUP_DEFER_TASKRUN + SINGLE_ISSUER");
        System.out.printf("  Distribution:    %s%n", distribution);
        System.out.printf("  Queue Depth:     %d%n", qd);
        System.out.printf("  Warmup:          %d iters x %d s%n", warmupIters, warmupTime);
        System.out.printf("  Measurement:     %d iters x %d s%n", runIters, runTime);
        System.out.printf("  TRIM:            %s%n", trim);
        System.out.printf("  Rest Seconds:    %d s%n", restSeconds);
        System.out.printf("  Precondition:    %d GB%n", preconditionGb);
        System.out.println("================================================================================");

        System.setProperty("juring.bench.write.mode", mode.name().toLowerCase());
        System.setProperty("juring.bench.ring.flags", ringFlags);
        System.setProperty("juring.bench.trim", String.valueOf(trim));
        System.setProperty("juring.bench.rest.seconds", String.valueOf(restSeconds));
        System.setProperty("juring.bench.precondition.gb", String.valueOf(preconditionGb));

        // Fix classpath for JMH forked processes when running under Maven exec:java or custom class loaders
        if (!System.getProperty("java.class.path", "").contains("jmh-core")) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl instanceof java.net.URLClassLoader ucl) {
                StringBuilder sb = new StringBuilder(System.getProperty("java.class.path", ""));
                for (java.net.URL url : ucl.getURLs()) {
                    if (!sb.isEmpty()) sb.append(java.io.File.pathSeparator);
                    sb.append(new java.io.File(url.getFile()).getAbsolutePath());
                }
                System.setProperty("java.class.path", sb.toString());
            }
        }

        DatasetManager.FileDistribution distEnum = DatasetManager.FileDistribution.valueOf(distribution);
        DatasetManager prepDataset = new DatasetManager(DatasetManager.WorkloadType.WRITE, distEnum);
        Path[] targetFiles = prepDataset.getUniquePaths();
        Path baseDir = prepDataset.getBaseDir() != null ? prepDataset.getBaseDir() : (targetFiles.length > 0 ? targetFiles[0].getParent() : null);

        String[] jvmArgsArr = new String[]{
                "--enable-native-access=ALL-UNNAMED",
                "-Djuring.bench.drive.prepped=true",
                "-Djuring.bench.write.mode=" + mode.name().toLowerCase(),
                "-Djuring.bench.ring.flags=" + ringFlags,
                "-Djuring.bench.trim=" + trim,
                "-Djuring.bench.rest.seconds=" + restSeconds,
                "-Djuring.bench.precondition.gb=" + preconditionGb
        };

        boolean runFc = "all".equals(engine) || "filechannel".equals(engine) || "fc".equals(engine);
        boolean runJu = "all".equals(engine) || "juring".equals(engine);

        if (runFc) {
            System.out.printf("%n>>> [Phase 1] Isolating FileChannel Direct Write Benchmark...%n");
            SsdCacheManager.prepareDrive(mode, "FileChannel", baseDir, targetFiles);

            System.setProperty("juring.bench.drive.prepped", "true");
            try {
                org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                        .include(FileChannelDirectWriteBenchmark.class.getSimpleName())
                        .param("distribution", distribution)
                        .warmupIterations(warmupIters)
                        .warmupTime(org.openjdk.jmh.runner.options.TimeValue.seconds(warmupTime))
                        .measurementIterations(runIters)
                        .measurementTime(org.openjdk.jmh.runner.options.TimeValue.seconds(runTime))
                        .forks(forks)
                        .jvmArgsAppend(jvmArgsArr)
                        .build();
                new org.openjdk.jmh.runner.Runner(opt).run();
            } finally {
                System.clearProperty("juring.bench.drive.prepped");
            }
        }

        if (runJu) {
            System.out.printf("%n>>> [Phase 2] Isolating JUring Direct Write Benchmark...%n");
            SsdCacheManager.prepareDrive(mode, "JUring", baseDir, targetFiles);

            System.setProperty("juring.bench.drive.prepped", "true");
            try {
                org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                        .include(JuringDirectWriteBenchmark.class.getSimpleName())
                        .param("distribution", distribution)
                        .param("qd", String.valueOf(qd))
                        .warmupIterations(warmupIters)
                        .warmupTime(org.openjdk.jmh.runner.options.TimeValue.seconds(warmupTime))
                        .measurementIterations(runIters)
                        .measurementTime(org.openjdk.jmh.runner.options.TimeValue.seconds(runTime))
                        .forks(forks)
                        .jvmArgsAppend(jvmArgsArr)
                        .build();
                new org.openjdk.jmh.runner.Runner(opt).run();
            } finally {
                System.clearProperty("juring.bench.drive.prepped");
            }
        }

        System.out.println("\n[DirectWriteBenchmark] Option 3 Dual-Mode Suite run completed.");
    }
}
