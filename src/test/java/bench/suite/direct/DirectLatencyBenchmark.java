package bench.suite.direct;

import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import bench.suite.common.StorageAuxCounters;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import com.davidvlijmincx.lio.api.Result;
import com.davidvlijmincx.lio.api.ResultConsumer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.infra.ThreadParams;
import org.openjdk.jmh.runner.IterationType;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@OperationsPerInvocation(1)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED"
})
@Threads(1)
public class DirectLatencyBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        public DatasetManager dataset;

        @Setup(Level.Trial)
        public void setup() {
            dataset = new DatasetManager(
                    DatasetManager.WorkloadType.READ,
                    DatasetManager.FileDistribution.MULTI_FILE
            );
        }
    }

    @State(Scope.Thread)
    public static class ThreadState {
        public JUring ring;
        public FileDescriptor[] fileDescriptors;
        public FileChannel[] channels;
        public ByteBuffer alignedBuffer;
        public Result reusableResult;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState) {
            ring = new JUring(64,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.dataset.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUring: " + regRet);
            }

            ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, 8);

            channels = benchState.dataset.openFileChannels(true);
            alignedBuffer = IoHelper.allocateAligned(DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);
            reusableResult = new Result();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            DatasetManager.closeChannels(channels);
            DatasetManager.closeFileDescriptors(fileDescriptors);
            if (ring != null) {
                ring.close();
            }
        }
    }

    @Benchmark
    public void juringDirectReadLatency(ThreadState state, BenchmarkState benchState, Blackhole blackhole) {
        int idx = benchState.dataset.nextTaskIndex();
        int fileIdx = benchState.dataset.getTaskFileIndices()[idx];
        long offset = benchState.dataset.getTaskOffsets()[idx];

        state.ring.prepareReadFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, 0);
        state.ring.submit();
        Result r = state.ring.waitForResult(state.reusableResult);
        long res = r.result();
        if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
            throw new IllegalStateException("Direct read latency failed: expected "
                    + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + res);
        }
        blackhole.consume(res);
    }

    @Benchmark
    public void juringDirectReadLatencyFused(ThreadState state, BenchmarkState benchState, Blackhole blackhole) {
        int idx = benchState.dataset.nextTaskIndex();
        int fileIdx = benchState.dataset.getTaskFileIndices()[idx];
        long offset = benchState.dataset.getTaskOffsets()[idx];

        state.ring.prepareReadFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, 0);
        state.ring.submitAndWait(1);
        Result r = state.ring.waitForResult(state.reusableResult);
        long res = r.result();
        if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
            throw new IllegalStateException("Direct read latency fused failed: expected "
                    + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + res);
        }
        blackhole.consume(res);
    }

    @Benchmark
    public void fileChannelDirectReadLatency(ThreadState state, BenchmarkState benchState, Blackhole blackhole) throws IOException {
        int idx = benchState.dataset.nextTaskIndex();
        int fileIdx = benchState.dataset.getTaskFileIndices()[idx];
        long offset = benchState.dataset.getTaskOffsets()[idx];

        FileChannel fc = state.channels[fileIdx];
        state.alignedBuffer.clear();
        int read = fc.read(state.alignedBuffer, offset);
        if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
            throw new IllegalStateException("FileChannel direct read latency failed: expected "
                    + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + read);
        }
        blackhole.consume(read);
    }

    /**
     *  Benchmark matching fio's exact ioengine=io_uring workload
     */
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(FioComparisonBenchmark.OPERATIONS_PER_INVOCATION)
    @Fork(value = 1, jvmArgs = {
            "--enable-native-access=ALL-UNNAMED"
    })
    @Threads(1)
    public static class FioComparisonBenchmark {

        public static final int OPERATIONS_PER_INVOCATION = 2048;
        public static final int BLOCK_SIZE = 4096;

        @State(Scope.Benchmark)
        public static class FioBenchmarkState {
            public Path readFilePath;
            public Path writeFilePath;
            public Path filePath;
            public long fileSize;
            public long maxBlocks;
            public long maxReadBlocks;
            public long maxWriteBlocks;

            @Setup(Level.Trial)
            public void setup() {
                this.readFilePath = DatasetManager.resolveSingleBenchFile(DatasetManager.WorkloadType.READ);
                this.writeFilePath = DatasetManager.resolveSingleBenchFile(DatasetManager.WorkloadType.WRITE);
                this.filePath = this.readFilePath;
                try {
                    this.fileSize = Files.size(this.readFilePath);
                    long wSize = Files.size(this.writeFilePath);
                    this.maxReadBlocks = this.fileSize / BLOCK_SIZE;
                    this.maxWriteBlocks = wSize / BLOCK_SIZE;
                    this.maxBlocks = this.maxReadBlocks;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read file size: " + this.readFilePath, e);
                }
            }
        }

        @State(Scope.Thread)
        public static class FioThreadState {
            @Param({"64"})
            public int qd = 64;

            @Param({"true", "false"})
            public boolean telemetry = true;

            public JUring ring;
            public FileDescriptor readFd;
            public FileDescriptor writeFd;
            public FileChannel readChannel;
            public FileChannel writeChannel;
            public ByteBuffer alignedBuf;

            public DatasetManager.FioPrng prng;
            public Histogram iterationHistogram;
            public Histogram trialHistogram;

            public long[] startTimes;

            public int inFlight;
            public int reapedCount;
            public ResultConsumer readConsumer;
            public ResultConsumer writeConsumer;

            private String benchmarkName;
            private String resultsDir;

            @Setup(Level.Trial)
            public void setup(FioBenchmarkState benchState, ThreadParams params, BenchmarkParams benchParams) {
                int threadIndex = (params != null) ? params.getThreadIndex() : 0;
                int threadCount = (params != null) ? params.getThreadCount() : 1;
                int safeBufferCount = Math.max(qd, IoHelper.getSafeBufferCount(BLOCK_SIZE, threadCount));
                int ringEntries = Math.max(256, qd * 2);

                long seed = System.nanoTime() ^ ((long) threadIndex * 31L);
                this.prng = new DatasetManager.FioPrng(seed);
                System.out.printf("[FioComparisonBenchmark] Initialized PRNG with seed: 0x%016x%n", seed);
                this.iterationHistogram = new Histogram(1L, 60_000_000_000L, 3);
                this.trialHistogram = new Histogram(1L, 60_000_000_000L, 3);
                this.benchmarkName = (benchParams != null) ? benchParams.getBenchmark() : "FioComparisonBenchmark";
                this.resultsDir = System.getProperty("juring.results.dir", null);

                this.startTimes = new long[safeBufferCount];
                this.inFlight = 0;
                this.reapedCount = 0;

                ring = new JUring(ringEntries,
                        IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN,
                        IORING_SETUP_DEFER_TASKRUN);

                readFd = new FileDescriptor(benchState.readFilePath.toString(), LinuxOpenOptions.READ_DIRECT, 0);
                writeFd = new FileDescriptor(benchState.writeFilePath.toString(), LinuxOpenOptions.WRITE_DIRECT, 0);
                int regRet = ring.registerFiles(readFd, writeFd);
                if (regRet != 0) {
                    throw new RuntimeException("Failed to register files in JUring: " + regRet);
                }

                MemorySegment[] regBufs = ring.registerBuffers(BLOCK_SIZE, safeBufferCount);
                for (MemorySegment regBuf : regBufs) {
                    regBuf.fill((byte) 0x5A);
                }

                try {
                    readChannel = FileChannel.open(benchState.readFilePath, StandardOpenOption.READ, IoHelper.getDirectOpenOption());
                    writeChannel = FileChannel.open(benchState.writeFilePath, StandardOpenOption.WRITE, IoHelper.getDirectOpenOption());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to open FileChannels with DIRECT option", e);
                }

                alignedBuf = IoHelper.allocateAligned(BLOCK_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);
                for (int i = 0; i < BLOCK_SIZE; i++) {
                    alignedBuf.put((byte) 0x5A);
                }
                alignedBuf.flip();

                this.readConsumer = result -> {
                    long res = result.result();
                    if (res != BLOCK_SIZE) {
                        throw new IllegalStateException("Direct read failed: expected " + BLOCK_SIZE + " bytes, got " + res);
                    }
                    int bufIdx = result.bufferIdx();
                    if (this.telemetry) {
                        long compNow = System.nanoTime();
                        long lat = compNow - this.startTimes[bufIdx];
                        if (lat < 1L) lat = 1L;
                        else if (lat > 60_000_000_000L) lat = 60_000_000_000L;
                        this.iterationHistogram.recordValue(lat);
                    }
                    this.reapedCount++;
                    this.ring.checkInBuffer(bufIdx);
                };

                this.writeConsumer = result -> {
                    long res = result.result();
                    if (res != BLOCK_SIZE) {
                        throw new IllegalStateException("Direct write failed: expected " + BLOCK_SIZE + " bytes, got " + res);
                    }
                    int bufIdx = result.bufferIdx();
                    if (this.telemetry) {
                        long compNow = System.nanoTime();
                        long lat = compNow - this.startTimes[bufIdx];
                        if (lat < 1L) lat = 1L;
                        else if (lat > 60_000_000_000L) lat = 60_000_000_000L;
                        this.iterationHistogram.recordValue(lat);
                    }
                    this.reapedCount++;
                    this.ring.checkInBuffer(bufIdx);
                };
            }

            @Setup(Level.Iteration)
            public void setupIteration() {
                if (this.iterationHistogram != null) {
                    this.iterationHistogram.reset();
                }
            }

            @TearDown(Level.Iteration)
            public void tearDownIteration(IterationParams iterationParams) {
                if (iterationParams != null && iterationParams.getType() == IterationType.MEASUREMENT) {
                    if (this.trialHistogram != null && this.iterationHistogram != null) {
                        this.trialHistogram.add(this.iterationHistogram);
                    }
                }
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
                if (readChannel != null) { try { readChannel.close(); } catch (Exception ignored) {} }
                if (writeChannel != null) { try { writeChannel.close(); } catch (Exception ignored) {} }
                if (readFd != null) { try { readFd.close(); } catch (Exception ignored) {} }
                if (writeFd != null) { try { writeFd.close(); } catch (Exception ignored) {} }
                if (ring != null) { ring.close(); }

                if (this.telemetry && this.trialHistogram != null && this.trialHistogram.getTotalCount() > 0) {
                    writeHistogramOutput();
                }
            }

            private void writeHistogramOutput() {
                try {
                    Path outDir;
                    if (resultsDir != null && !resultsDir.isBlank()) {
                        outDir = Path.of(resultsDir);
                    } else {
                        String date = new java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new java.util.Date());
                        outDir = Path.of("results", date);
                    }
                    Files.createDirectories(outDir);

                    String cleanName = benchmarkName != null
                            ? benchmarkName.substring(benchmarkName.lastIndexOf('.') + 1)
                            : "FioComparisonBenchmark";
                    String fork = System.getProperty("jmh.forkId", String.valueOf(System.nanoTime() & 0xFFFF));
                    String baseName = cleanName + "-qd" + qd + "-fork" + fork;

                    Path percentilesFile = outDir.resolve(baseName + ".percentiles");
                    Path hlogFile = outDir.resolve(baseName + ".hlog");

                    try (PrintStream ps = new PrintStream(Files.newOutputStream(percentilesFile))) {
                        trialHistogram.outputPercentileDistribution(ps, 1000.0);
                    }

                    try (PrintStream ps = new PrintStream(Files.newOutputStream(hlogFile))) {
                        HistogramLogWriter logWriter = new HistogramLogWriter(ps);
                        logWriter.outputLogFormatVersion();
                        logWriter.outputLegend();
                        logWriter.outputIntervalHistogram(trialHistogram);
                    }

                    System.out.printf("[FioComparisonBenchmark] Wrote latency percentiles to %s and .hlog to %s (%,d ops)%n",
                            percentilesFile, hlogFile, trialHistogram.getTotalCount());
                } catch (Exception e) {
                    System.err.println("[FioComparisonBenchmark] Notice: Failed to write histogram output: " + e.getMessage());
                }
            }
        }

        @Benchmark
        public void juringDirectRead(FioThreadState state, FioBenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
            final JUring ring = state.ring;
            final long maxBlocks = benchState.maxReadBlocks;
            final int qd = state.qd;
            final boolean telemetry = state.telemetry;
            final DatasetManager.FioPrng prng = state.prng;
            final long[] startTimes = state.startTimes;
            final int totalTasks = OPERATIONS_PER_INVOCATION;

            int inFlight = state.inFlight;

            while (inFlight < qd) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) break;
                long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                if (telemetry) {
                    startTimes[bufIdx] = System.nanoTime();
                }
                ring.prepareReadFixed(0, BLOCK_SIZE, offset, bufIdx);
                inFlight++;
            }

            int reapedTotal = 0;

            while (reapedTotal < totalTasks) {
                int remaining = totalTasks - reapedTotal;
                int minWait = Math.max(1, Math.min(1, Math.min(remaining, inFlight)));
                int reaped = ring.submitAndWaitForBatchResult(minWait, qd, state.readConsumer);
                inFlight -= reaped;
                reapedTotal += reaped;

                while (inFlight < qd) {
                    int bufIdx = ring.checkOutBuffer();
                    if (bufIdx < 0) break;
                    long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                    if (telemetry) {
                        startTimes[bufIdx] = System.nanoTime();
                    }
                    ring.prepareReadFixed(0, BLOCK_SIZE, offset, bufIdx);
                    inFlight++;
                }
            }

            state.inFlight = inFlight;
            state.reapedCount = reapedTotal;

            counters.record((long) totalTasks * BLOCK_SIZE);
            if (blackhole != null) blackhole.consume(state.reapedCount);
        }

        @Benchmark
        public void juringDirectWrite(FioThreadState state, FioBenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
            final JUring ring = state.ring;
            final long maxBlocks = benchState.maxWriteBlocks;
            final int qd = state.qd;
            final boolean telemetry = state.telemetry;
            final DatasetManager.FioPrng prng = state.prng;
            final long[] startTimes = state.startTimes;
            final int totalTasks = OPERATIONS_PER_INVOCATION;

            int inFlight = state.inFlight;

            while (inFlight < qd) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) break;
                long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                if (telemetry) {
                    startTimes[bufIdx] = System.nanoTime();
                }
                ring.prepareWriteFixed(1, BLOCK_SIZE, offset, bufIdx);
                inFlight++;
            }

            int reapedTotal = 0;

            while (reapedTotal < totalTasks) {
                int remaining = totalTasks - reapedTotal;
                int minWait = Math.max(1, Math.min(1, Math.min(remaining, inFlight)));
                int reaped = ring.submitAndWaitForBatchResult(minWait, qd, state.writeConsumer);
                inFlight -= reaped;
                reapedTotal += reaped;

                while (inFlight < qd) {
                    int bufIdx = ring.checkOutBuffer();
                    if (bufIdx < 0) break;
                    long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                    if (telemetry) {
                        startTimes[bufIdx] = System.nanoTime();
                    }
                    ring.prepareWriteFixed(1, BLOCK_SIZE, offset, bufIdx);
                    inFlight++;
                }
            }

            state.inFlight = inFlight;
            state.reapedCount = reapedTotal;

            counters.record((long) totalTasks * BLOCK_SIZE);
            if (blackhole != null) blackhole.consume(state.reapedCount);
        }

        @Benchmark
        public void fileChannelDirectRead(FioThreadState state, FioBenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
            final FileChannel fc = state.readChannel;
            final ByteBuffer buf = state.alignedBuf;
            final long maxBlocks = benchState.maxReadBlocks;
            final boolean telemetry = state.telemetry;
            final DatasetManager.FioPrng prng = state.prng;
            final int totalTasks = OPERATIONS_PER_INVOCATION;

            for (int i = 0; i < totalTasks; i++) {
                long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                buf.clear();
                long start = telemetry ? System.nanoTime() : 0L;
                int n = fc.read(buf, offset);
                long end = telemetry ? System.nanoTime() : 0L;
                if (n != BLOCK_SIZE) {
                    throw new IllegalStateException("FileChannel direct read failed: expected "
                            + BLOCK_SIZE + " bytes, got " + n);
                }
                if (telemetry) {
                    long lat = end - start;
                    if (lat < 1L) lat = 1L;
                    else if (lat > 60_000_000_000L) lat = 60_000_000_000L;
                    state.iterationHistogram.recordValue(lat);
                }
                if (blackhole != null) blackhole.consume(n);
            }

            counters.record((long) totalTasks * BLOCK_SIZE);
        }

        @Benchmark
        public void fileChannelDirectWrite(FioThreadState state, FioBenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
            final FileChannel fc = state.writeChannel;
            final ByteBuffer buf = state.alignedBuf;
            final long maxBlocks = benchState.maxWriteBlocks;
            final boolean telemetry = state.telemetry;
            final DatasetManager.FioPrng prng = state.prng;
            final int totalTasks = OPERATIONS_PER_INVOCATION;

            for (int i = 0; i < totalTasks; i++) {
                long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                buf.position(0);
                long start = telemetry ? System.nanoTime() : 0L;
                int n = fc.write(buf, offset);
                long end = telemetry ? System.nanoTime() : 0L;
                if (n != BLOCK_SIZE) {
                    throw new IllegalStateException("FileChannel direct write failed: expected "
                            + BLOCK_SIZE + " bytes, got " + n);
                }
                if (telemetry) {
                    long lat = end - start;
                    if (lat < 1L) lat = 1L;
                    else if (lat > 60_000_000_000L) lat = 60_000_000_000L;
                    state.iterationHistogram.recordValue(lat);
                }
                if (blackhole != null) blackhole.consume(n);
            }

            counters.record((long) totalTasks * BLOCK_SIZE);
        }

        public static void main(String[] args) throws Exception {
            org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                    .include(".*FioComparisonBenchmark.*")
                    .forks(1)
                    .build();
            new org.openjdk.jmh.runner.Runner(opt).run();
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                .include(DirectLatencyBenchmark.class.getSimpleName())
                .forks(1)
                .build();
        new org.openjdk.jmh.runner.Runner(opt).run();
    }
}
