package bench.suite.direct;

import bench.suite.common.CpuAuxCounters;
import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import bench.suite.common.StorageAuxCounters;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import com.davidvlijmincx.lio.api.Result;
import com.davidvlijmincx.lio.api.ResultConsumer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.ThreadParams;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * NVMe Direct I/O (O_DIRECT) Unbatched Tail Latency Benchmark.
 *
 * Runs in JMH Mode.SampleTime with OperationsPerInvocation(1) to isolate
 * per-request completion percentiles (P50, P90, P99, P99.9, Max) and eliminate
 * batch-smoothing distortion.
 */
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

        @Setup(Level.Iteration)
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

        @TearDown(Level.Iteration)
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
     * Head-to-head empirical benchmark matching fio's exact ioengine=io_uring workload:
     * <ul>
     *   <li>Target: 10 GB pre-allocated file (single_file_bench.bin)</li>
     *   <li>4KB block size, O_DIRECT (direct=1)</li>
     *   <li>Queue depth = 64 (configurable via -p qd=...)</li>
     *   <li>Random offsets generated uniformly across the full 10 GB file (2,621,440 blocks)</li>
     *   <li>Fixed files (registerfiles=1) and fixed buffers (fixedbufs=1)</li>
     *   <li>Full latency telemetry (clat / slat / lat histograms) matching fio's default behavior,
     *       with toggleable telemetry parameter (true vs false) to measure latency tracking overhead vs raw I/O</li>
     * </ul>
     */
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(FioComparisonBenchmark.OPERATIONS_PER_INVOCATION)
    @Fork(value = 1, jvmArgs = {
            "--enable-native-access=ALL-UNNAMED",
            "-Djuring.countEnters=true"
    })
    @Threads(1)
    public static class FioComparisonBenchmark {

        public static final int OPERATIONS_PER_INVOCATION = 2048;
        public static final int BLOCK_SIZE = 4096;

        @State(Scope.Benchmark)
        public static class FioBenchmarkState {
            public Path filePath;
            public long fileSize;
            public long maxBlocks;

            @Setup(Level.Trial)
            public void setup() {
                this.filePath = DatasetManager.resolveSingleBenchFile();
                try {
                    this.fileSize = Files.size(this.filePath);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read file size: " + this.filePath, e);
                }
                if (this.fileSize < DatasetManager.DEFAULT_SINGLE_FILE_SIZE) {
                    throw new IllegalStateException("File size too small: expected >= "
                            + DatasetManager.DEFAULT_SINGLE_FILE_SIZE + ", got " + this.fileSize);
                }
                this.maxBlocks = this.fileSize / BLOCK_SIZE;
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
            public DatasetManager.FioLatencyHistogram clatHistogram;
            public DatasetManager.FioLatencyHistogram slatHistogram;
            public DatasetManager.FioLatencyHistogram latHistogram;

            public long[] startTimes;
            public long[] issueTimes;
            public int[] queuedBufs;
            public int queuedCount;

            public int inFlight;
            public int reapedCount;
            public ResultConsumer readConsumer;
            public ResultConsumer writeConsumer;

            @Setup(Level.Trial)
            public void setup(FioBenchmarkState benchState, ThreadParams params) {
                int threadIndex = (params != null) ? params.getThreadIndex() : 0;
                int threadCount = (params != null) ? params.getThreadCount() : 1;
                int safeBufferCount = Math.max(qd, IoHelper.getSafeBufferCount(BLOCK_SIZE, threadCount));
                int ringEntries = Math.max(256, qd * 2);

                this.prng = new DatasetManager.FioPrng(42424242L + ((long) threadIndex * 31L));
                this.clatHistogram = new DatasetManager.FioLatencyHistogram();
                this.slatHistogram = new DatasetManager.FioLatencyHistogram();
                this.latHistogram = new DatasetManager.FioLatencyHistogram();

                this.startTimes = new long[safeBufferCount];
                this.issueTimes = new long[safeBufferCount];
                this.queuedBufs = new int[safeBufferCount];
                this.queuedCount = 0;
                this.inFlight = 0;
                this.reapedCount = 0;

                ring = new JUring(ringEntries,
                        IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN,
                        IORING_SETUP_DEFER_TASKRUN);

                readFd = new FileDescriptor(benchState.filePath.toString(), LinuxOpenOptions.READ_DIRECT, 0);
                writeFd = new FileDescriptor(benchState.filePath.toString(), LinuxOpenOptions.WRITE_DIRECT, 0);
                int regRet = ring.registerFiles(readFd, writeFd);
                if (regRet != 0) {
                    throw new RuntimeException("Failed to register files in JUring: " + regRet);
                }

                MemorySegment[] regBufs = ring.registerBuffers(BLOCK_SIZE, safeBufferCount);
                for (MemorySegment regBuf : regBufs) {
                    regBuf.fill((byte) 0x5A);
                }

                try {
                    readChannel = FileChannel.open(benchState.filePath, StandardOpenOption.READ, IoHelper.getDirectOpenOption());
                    writeChannel = FileChannel.open(benchState.filePath, StandardOpenOption.WRITE, IoHelper.getDirectOpenOption());
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
                        this.clatHistogram.record(compNow - this.issueTimes[bufIdx]);
                        this.latHistogram.record(compNow - this.startTimes[bufIdx]);
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
                        this.clatHistogram.record(compNow - this.issueTimes[bufIdx]);
                        this.latHistogram.record(compNow - this.startTimes[bufIdx]);
                    }
                    this.reapedCount++;
                    this.ring.checkInBuffer(bufIdx);
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
                if (readChannel != null) { try { readChannel.close(); } catch (Exception ignored) {} }
                if (writeChannel != null) { try { writeChannel.close(); } catch (Exception ignored) {} }
                if (readFd != null) { try { readFd.close(); } catch (Exception ignored) {} }
                if (writeFd != null) { try { writeFd.close(); } catch (Exception ignored) {} }
                if (ring != null) { ring.close(); }
            }
        }

        @Benchmark
        public void juringDirectRead(FioThreadState state, FioBenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) {
            cpu.begin();
            final JUring ring = state.ring;
            ring.takeEnterCount();
            final long maxBlocks = benchState.maxBlocks;
            final int qd = state.qd;
            final boolean telemetry = state.telemetry;
            final DatasetManager.FioPrng prng = state.prng;
            final long[] startTimes = state.startTimes;
            final long[] issueTimes = state.issueTimes;
            final int[] queuedBufs = state.queuedBufs;
            final DatasetManager.FioLatencyHistogram slatHist = state.slatHistogram;
            final int totalTasks = OPERATIONS_PER_INVOCATION;

            int inFlight = state.inFlight;
            int queuedCount = state.queuedCount;

            while (inFlight < qd) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) break;
                long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                if (telemetry) {
                    startTimes[bufIdx] = System.nanoTime();
                    queuedBufs[queuedCount++] = bufIdx;
                }
                ring.prepareReadFixed(0, BLOCK_SIZE, offset, bufIdx);
                inFlight++;
            }

            int reapedTotal = 0;
            int enters = 0;

            while (reapedTotal < totalTasks) {
                if (telemetry && queuedCount > 0) {
                    ring.submit();
                    long issueNow = System.nanoTime();
                    for (int i = 0; i < queuedCount; i++) {
                        int bIdx = queuedBufs[i];
                        issueTimes[bIdx] = issueNow;
                        slatHist.record(issueNow - startTimes[bIdx]);
                    }
                    queuedCount = 0;
                }

                int remaining = totalTasks - reapedTotal;
                int minWait = Math.max(1, Math.min(1, Math.min(remaining, inFlight)));
                int reaped = ring.submitAndWaitForBatchResult(minWait, qd, state.readConsumer);
                enters++;
                inFlight -= reaped;
                reapedTotal += reaped;

                while (inFlight < qd) {
                    int bufIdx = ring.checkOutBuffer();
                    if (bufIdx < 0) break;
                    long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                    if (telemetry) {
                        startTimes[bufIdx] = System.nanoTime();
                        queuedBufs[queuedCount++] = bufIdx;
                    }
                    ring.prepareReadFixed(0, BLOCK_SIZE, offset, bufIdx);
                    inFlight++;
                }
            }

            state.inFlight = inFlight;
            state.queuedCount = queuedCount;
            state.reapedCount = reapedTotal;

            int countedEnters = ring.takeEnterCount();
            int totalEnters = countedEnters > 0 ? countedEnters : enters;
            counters.record(totalEnters, (long) totalTasks * BLOCK_SIZE);
            cpu.end();
            if (blackhole != null) blackhole.consume(state.reapedCount);
        }

        @Benchmark
        public void juringDirectWrite(FioThreadState state, FioBenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) {
            cpu.begin();
            final JUring ring = state.ring;
            ring.takeEnterCount();
            final long maxBlocks = benchState.maxBlocks;
            final int qd = state.qd;
            final boolean telemetry = state.telemetry;
            final DatasetManager.FioPrng prng = state.prng;
            final long[] startTimes = state.startTimes;
            final long[] issueTimes = state.issueTimes;
            final int[] queuedBufs = state.queuedBufs;
            final DatasetManager.FioLatencyHistogram slatHist = state.slatHistogram;
            final int totalTasks = OPERATIONS_PER_INVOCATION;

            int inFlight = state.inFlight;
            int queuedCount = state.queuedCount;

            while (inFlight < qd) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) break;
                long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                if (telemetry) {
                    startTimes[bufIdx] = System.nanoTime();
                    queuedBufs[queuedCount++] = bufIdx;
                }
                ring.prepareWriteFixed(1, BLOCK_SIZE, offset, bufIdx);
                inFlight++;
            }

            int reapedTotal = 0;
            int enters = 0;

            while (reapedTotal < totalTasks) {
                if (telemetry && queuedCount > 0) {
                    ring.submit();
                    long issueNow = System.nanoTime();
                    for (int i = 0; i < queuedCount; i++) {
                        int bIdx = queuedBufs[i];
                        issueTimes[bIdx] = issueNow;
                        slatHist.record(issueNow - startTimes[bIdx]);
                    }
                    queuedCount = 0;
                }

                int remaining = totalTasks - reapedTotal;
                int minWait = Math.max(1, Math.min(1, Math.min(remaining, inFlight)));
                int reaped = ring.submitAndWaitForBatchResult(minWait, qd, state.writeConsumer);
                enters++;
                inFlight -= reaped;
                reapedTotal += reaped;

                while (inFlight < qd) {
                    int bufIdx = ring.checkOutBuffer();
                    if (bufIdx < 0) break;
                    long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                    if (telemetry) {
                        startTimes[bufIdx] = System.nanoTime();
                        queuedBufs[queuedCount++] = bufIdx;
                    }
                    ring.prepareWriteFixed(1, BLOCK_SIZE, offset, bufIdx);
                    inFlight++;
                }
            }

            state.inFlight = inFlight;
            state.queuedCount = queuedCount;
            state.reapedCount = reapedTotal;

            int countedEnters = ring.takeEnterCount();
            int totalEnters = countedEnters > 0 ? countedEnters : enters;
            counters.record(totalEnters, (long) totalTasks * BLOCK_SIZE);
            cpu.end();
            if (blackhole != null) blackhole.consume(state.reapedCount);
        }

        @Benchmark
        public void fileChannelDirectRead(FioThreadState state, FioBenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) throws IOException {
            cpu.begin();
            final FileChannel fc = state.readChannel;
            final ByteBuffer buf = state.alignedBuf;
            final long maxBlocks = benchState.maxBlocks;
            final boolean telemetry = state.telemetry;
            final DatasetManager.FioPrng prng = state.prng;
            final DatasetManager.FioLatencyHistogram clatHist = state.clatHistogram;
            final DatasetManager.FioLatencyHistogram latHist = state.latHistogram;
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
                    clatHist.record(lat);
                    latHist.record(lat);
                }
                if (blackhole != null) blackhole.consume(n);
            }

            counters.record(totalTasks, (long) totalTasks * BLOCK_SIZE);
            cpu.end();
        }

        @Benchmark
        public void fileChannelDirectWrite(FioThreadState state, FioBenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) throws IOException {
            cpu.begin();
            final FileChannel fc = state.writeChannel;
            final ByteBuffer buf = state.alignedBuf;
            final long maxBlocks = benchState.maxBlocks;
            final boolean telemetry = state.telemetry;
            final DatasetManager.FioPrng prng = state.prng;
            final DatasetManager.FioLatencyHistogram clatHist = state.clatHistogram;
            final DatasetManager.FioLatencyHistogram latHist = state.latHistogram;
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
                    clatHist.record(lat);
                    latHist.record(lat);
                }
                if (blackhole != null) blackhole.consume(n);
            }

            counters.record(totalTasks, (long) totalTasks * BLOCK_SIZE);
            cpu.end();
        }

        public record BenchResult(long iops, double bwMBs, DatasetManager.FioLatencyHistogram slatHist, DatasetManager.FioLatencyHistogram clatHist, DatasetManager.FioLatencyHistogram latHist) {}
        public record FioResult(long iops, double bwMBs, double slatAvgUs, double clatAvgUs, double clatP50Us, double clatP99Us, double clatP99_9Us) {}

        public static void main(String[] args) throws Exception {
            for (String arg : args) {
                if ("--jmh".equals(arg) || "-jmh".equals(arg)) {
                    org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                            .include(".*FioComparisonBenchmark.*")
                            .forks(1)
                            .build();
                    new org.openjdk.jmh.runner.Runner(opt).run();
                    return;
                }
            }
            runDirectComparison(args);
        }

        public static void runDirectComparison(String[] args) throws Exception {
            int durationSec = 3;
            String mode = "read";
            int qd = 64;
            boolean verbose = false;
            Path customFile = null;

            for (int i = 0; i < args.length; i++) {
                if ("--runtime".equals(args[i]) && i + 1 < args.length) durationSec = Integer.parseInt(args[++i]);
                if ("--mode".equals(args[i]) && i + 1 < args.length) mode = args[++i];
                if ("--qd".equals(args[i]) && i + 1 < args.length) qd = Integer.parseInt(args[++i]);
                if ("--file".equals(args[i]) && i + 1 < args.length) customFile = Path.of(args[++i]);
                if ("--verbose".equals(args[i]) || "-v".equals(args[i])) verbose = true;
            }

            Path file = (customFile != null) ? customFile : DatasetManager.resolveSingleBenchFile();
            long size = Files.size(file);
            long maxBlocks = size / BLOCK_SIZE;
            boolean isWrite = "write".equalsIgnoreCase(mode);

            System.out.println("==================================================================================");
            System.out.println("          JUring vs Native C fio Head-to-Head Workload Comparison");
            System.out.println("==================================================================================");
            System.out.printf(" Target File:       %s (%,d MB)%n", file, size / (1024 * 1024));
            System.out.printf(" Total 4KB Blocks:  %,d blocks%n", maxBlocks);
            System.out.printf(" Block Size:        %d bytes (O_DIRECT, 4KB page-aligned)%n", BLOCK_SIZE);
            System.out.printf(" Queue Depth:       %d%n", qd);
            System.out.printf(" Workload Mode:     rand%s%n", mode);
            System.out.printf(" Benchmark Runtime: %d seconds per phase%n", durationSec);
            System.out.println("----------------------------------------------------------------------------------");

            // Cooldown before write benchmark
            syncDevice(isWrite);

            // 1. Run JUring with Telemetry ON (matching fio default)
            System.out.printf("[Phase 1/4] Running JUring rand%s with Latency Telemetry (clat/slat histograms)...%n", mode);
            BenchResult juringTelem = runJuringBench(file, mode, qd, durationSec, true);
            System.out.printf("  JUring (Telemetry ON) : %,d IOPS (%,.1f MB/s) | slat avg=%.2f µs | clat avg=%.2f µs (p50=%.1f µs, p99=%.1f µs)%n",
                    juringTelem.iops, juringTelem.bwMBs,
                    juringTelem.slatHist.getMeanMicros(),
                    juringTelem.clatHist.getMeanMicros(),
                    juringTelem.clatHist.getPercentileMicros(50.0),
                    juringTelem.clatHist.getPercentileMicros(99.0));

            syncDevice(isWrite);

            // 2. Run JUring with Telemetry OFF (raw peak throughput)
            System.out.printf("[Phase 2/4] Running JUring rand%s with Telemetry OFF (raw FFM engine speed)...%n", mode);
            BenchResult juringRaw = runJuringBench(file, mode, qd, durationSec, false);
            System.out.printf("  JUring (Telemetry OFF): %,d IOPS (%,.1f MB/s)%n", juringRaw.iops, juringRaw.bwMBs);

            syncDevice(isWrite);

            // 3. Run Native C fio with default telemetry
            boolean hasFio = isFioAvailable();
            FioResult fioTelem = null;
            FioResult fioRaw = null;
            if (hasFio) {
                System.out.printf("[Phase 3/4] Running Native C fio rand%s (ioengine=io_uring, default telemetry)...%n", mode);
                fioTelem = runNativeFio(file, mode, qd, durationSec, true, maxBlocks);
                if (fioTelem != null) {
                    System.out.printf("  Native fio (Default)  : %,d IOPS (%,.1f MB/s) | slat avg=%.2f µs | clat avg=%.2f µs (p50=%.1f µs, p99=%.1f µs)%n",
                            fioTelem.iops, fioTelem.bwMBs, fioTelem.slatAvgUs, fioTelem.clatAvgUs, fioTelem.clatP50Us, fioTelem.clatP99Us);
                }

                syncDevice(isWrite);

                System.out.printf("[Phase 4/4] Running Native C fio rand%s (disable_lat=1, raw mode)...%n", mode);
                fioRaw = runNativeFio(file, mode, qd, durationSec, false, maxBlocks);
                if (fioRaw != null) {
                    System.out.printf("  Native fio (No Telem) : %,d IOPS (%,.1f MB/s)%n", fioRaw.iops, fioRaw.bwMBs);
                }
            } else {
                System.out.println("[Notice] Native fio executable not found; skipping native fio live execution.");
            }

            // Print Summary Table
            System.out.println("==================================================================================");
            System.out.println("                       Final Head-to-Head Comparison Table");
            System.out.println("==================================================================================");
            System.out.printf("%-30s | %-12s | %-12s | %-14s | %-14s%n", "Engine & Configuration", "Throughput", "Bandwidth", "clat avg (µs)", "clat p99 (µs)");
            System.out.println("----------------------------------------------------------------------------------");
            System.out.printf("%-30s | %,9d ops/s | %,8.1f MB/s | %14.2f | %14.2f%n",
                    "JUring (Telemetry ON)", juringTelem.iops, juringTelem.bwMBs,
                    juringTelem.clatHist.getMeanMicros(), juringTelem.clatHist.getPercentileMicros(99.0));
            if (fioTelem != null) {
                System.out.printf("%-30s | %,9d IOPS  | %,8.1f MB/s | %14.2f | %14.2f%n",
                        "Native C fio (Default)", fioTelem.iops, fioTelem.bwMBs, fioTelem.clatAvgUs, fioTelem.clatP99Us);
                double delta = ((double) juringTelem.iops / Math.max(1, fioTelem.iops) - 1.0) * 100.0;
                System.out.printf("  --> JUring vs fio (Telemetry ON): %+.2f%%%n", delta);
            }
            System.out.println("----------------------------------------------------------------------------------");
            System.out.printf("%-30s | %,9d ops/s | %,8.1f MB/s | %14s | %14s%n",
                    "JUring (Telemetry OFF)", juringRaw.iops, juringRaw.bwMBs, "N/A", "N/A");
            if (fioRaw != null) {
                System.out.printf("%-30s | %,9d IOPS  | %,8.1f MB/s | %14s | %14s%n",
                        "Native C fio (No Telem)", fioRaw.iops, fioRaw.bwMBs, "N/A", "N/A");
                double rawDelta = ((double) juringRaw.iops / Math.max(1, fioRaw.iops) - 1.0) * 100.0;
                System.out.printf("  --> JUring vs fio (Telemetry OFF): %+.2f%%%n", rawDelta);
            }
            System.out.println("==================================================================================");

            if (verbose) {
                System.out.println();
                System.out.println("=== JUring Detailed Completion Latency Percentiles ===");
                System.out.print(juringTelem.clatHist.formatFioPercentiles());
                System.out.println();
            }
        }

        private static void syncDevice(boolean isWrite) {
            if (!isWrite) return;
            try {
                System.out.print("  [NVMe Cache Sync] Flushing dirty write buffers... ");
                Process p = new ProcessBuilder("sync").start();
                p.waitFor();
                Thread.sleep(1000);
                System.out.println("Ready.");
            } catch (Exception ignored) {}
        }

        private static BenchResult runJuringBench(Path file, String mode, int qd, int durationSec, boolean telemetry) throws Exception {
            long size = Files.size(file);
            long maxBlocks = size / BLOCK_SIZE;
            DatasetManager.FioPrng prng = new DatasetManager.FioPrng(42424242L);
            DatasetManager.FioLatencyHistogram clatHist = new DatasetManager.FioLatencyHistogram();
            DatasetManager.FioLatencyHistogram slatHist = new DatasetManager.FioLatencyHistogram();
            DatasetManager.FioLatencyHistogram latHist = new DatasetManager.FioLatencyHistogram();

            long[] startTimes = new long[qd];
            long[] issueTimes = new long[qd];
            int[] queuedBufs = new int[qd];
            long[] opCount = new long[1];

            boolean isWrite = "write".equalsIgnoreCase(mode);
            LinuxOpenOptions openOpt = isWrite ? LinuxOpenOptions.WRITE_DIRECT : LinuxOpenOptions.READ_DIRECT;

            try (JUring ring = new JUring(qd * 2, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN);
                 FileDescriptor fd = new FileDescriptor(file.toString(), openOpt, 0)) {

                ring.registerFiles(fd);
                MemorySegment[] bufs = ring.registerBuffers(BLOCK_SIZE, qd);
                if (isWrite) {
                    for (MemorySegment b : bufs) b.fill((byte) 0x5A);
                }

                ResultConsumer consumer = result -> {
                    int bIdx = result.bufferIdx();
                    if (telemetry) {
                        long compNow = System.nanoTime();
                        clatHist.record(compNow - issueTimes[bIdx]);
                        latHist.record(compNow - startTimes[bIdx]);
                    }
                    opCount[0]++;
                    ring.checkInBuffer(bIdx);
                };

                // Warmup 1 sec
                long warmupDeadline = System.currentTimeMillis() + 1000;
                runLoop(ring, prng, maxBlocks, qd, isWrite, telemetry, startTimes, issueTimes, queuedBufs, slatHist, consumer, warmupDeadline);

                // Measurement run
                opCount[0] = 0;
                clatHist.reset();
                slatHist.reset();
                latHist.reset();

                long startNanos = System.nanoTime();
                long measureDeadline = System.currentTimeMillis() + (durationSec * 1000L);
                runLoop(ring, prng, maxBlocks, qd, isWrite, telemetry, startTimes, issueTimes, queuedBufs, slatHist, consumer, measureDeadline);
                long elapsedNanos = System.nanoTime() - startNanos;

                double elapsedSec = elapsedNanos / 1_000_000_000.0;
                long totalOps = opCount[0];
                long iops = Math.round(totalOps / elapsedSec);
                double bwMBs = (totalOps * (double) BLOCK_SIZE) / (elapsedSec * 1024.0 * 1024.0);

                return new BenchResult(iops, bwMBs, slatHist, clatHist, latHist);
            }
        }

        private static void runLoop(JUring ring, DatasetManager.FioPrng prng, long maxBlocks, int qd, boolean isWrite,
                                    boolean telemetry, long[] startTimes, long[] issueTimes, int[] queuedBufs,
                                    DatasetManager.FioLatencyHistogram slatHist,
                                    ResultConsumer consumer, long deadlineMs) {
            int inFlight = 0;
            int queuedCount = 0;
            while (inFlight < qd) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) break;
                long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                if (telemetry) {
                    startTimes[bufIdx] = System.nanoTime();
                    queuedBufs[queuedCount++] = bufIdx;
                }
                if (isWrite) {
                    ring.prepareWriteFixed(0, BLOCK_SIZE, offset, bufIdx);
                } else {
                    ring.prepareReadFixed(0, BLOCK_SIZE, offset, bufIdx);
                }
                inFlight++;
            }

            while (System.currentTimeMillis() < deadlineMs) {
                if (telemetry && queuedCount > 0) {
                    ring.submit();
                    long issueNow = System.nanoTime();
                    for (int i = 0; i < queuedCount; i++) {
                        int bIdx = queuedBufs[i];
                        issueTimes[bIdx] = issueNow;
                        if (slatHist != null) slatHist.record(issueNow - startTimes[bIdx]);
                    }
                    queuedCount = 0;
                }
                int reaped = ring.submitAndWaitForBatchResult(1, qd, consumer);
                inFlight -= reaped;

                while (inFlight < qd) {
                    int bufIdx = ring.checkOutBuffer();
                    if (bufIdx < 0) break;
                    long offset = prng.nextOffset(maxBlocks, BLOCK_SIZE);
                    if (telemetry) {
                        startTimes[bufIdx] = System.nanoTime();
                        queuedBufs[queuedCount++] = bufIdx;
                    }
                    if (isWrite) {
                        ring.prepareWriteFixed(0, BLOCK_SIZE, offset, bufIdx);
                    } else {
                        ring.prepareReadFixed(0, BLOCK_SIZE, offset, bufIdx);
                    }
                    inFlight++;
                }
            }

            while (inFlight > 0) {
                int reaped = ring.submitAndWaitForBatchResult(1, qd, consumer);
                inFlight = Math.max(0, inFlight - reaped);
            }
        }

        private static boolean isFioAvailable() {
            try {
                Process p = new ProcessBuilder("fio", "--version").start();
                return p.waitFor() == 0;
            } catch (Exception e) {
                return false;
            }
        }

        private static FioResult runNativeFio(Path file, String mode, int qd, int durationSec, boolean telemetry, long maxBlocks) {
            try {
                long sizeMB = Math.max(1, (maxBlocks * BLOCK_SIZE) / (1024 * 1024));
                List<String> cmd = new ArrayList<>(List.of(
                        "fio",
                        "--name=fio_bench",
                        "--ioengine=io_uring",
                        "--direct=1",
                        "--bs=4k",
                        "--size=" + sizeMB + "M",
                        "--filename=" + file.toAbsolutePath(),
                        "--rw=rand" + mode,
                        "--iodepth=" + qd,
                        "--fixedbufs=1",
                        "--registerfiles=1",
                        "--time_based",
                        "--runtime=" + durationSec + "s",
                        "--output-format=json"
                ));
                if (!telemetry) {
                    cmd.add("--disable_lat=1");
                    cmd.add("--disable_slat=1");
                    cmd.add("--disable_clat=1");
                }

                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                StringBuilder stdout = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                }
                p.waitFor();

                String json = stdout.toString();
                return parseFioJson(json, mode);
            } catch (Exception e) {
                System.err.println("Failed to run native fio: " + e.getMessage());
                return null;
            }
        }

        public static FioResult parseFioJson(String json, String mode) {
            try {
                String lowerMode = mode.toLowerCase();
                int modeIdx = json.indexOf("\"" + lowerMode + "\" :");
                if (modeIdx < 0) {
                    modeIdx = json.indexOf("\"" + lowerMode + "\":");
                }
                String sub = modeIdx >= 0 ? json.substring(modeIdx) : json;

                long iops = 0;
                double bwMBs = 0;
                double slatAvgUs = 0;
                double clatAvgUs = 0;
                double clatP50Us = 0;
                double clatP99Us = 0;
                double clatP99_9Us = 0;

                Matcher mIops = Pattern.compile("\"iops\"\\s*:\\s*([0-9.]+)").matcher(sub);
                if (mIops.find()) {
                    iops = Math.round(Double.parseDouble(mIops.group(1)));
                }

                Matcher mBw = Pattern.compile("\"bw_bytes\"\\s*:\\s*([0-9.]+)").matcher(sub);
                if (mBw.find()) {
                    bwMBs = Double.parseDouble(mBw.group(1)) / (1024.0 * 1024.0);
                }

                Matcher mSlat = Pattern.compile("\"slat_ns\"\\s*:\\s*\\{[^}]*\"mean\"\\s*:\\s*([0-9.]+)").matcher(sub);
                if (mSlat.find()) {
                    slatAvgUs = Double.parseDouble(mSlat.group(1)) / 1000.0;
                }

                Matcher mClat = Pattern.compile("\"clat_ns\"\\s*:\\s*\\{[^}]*\"mean\"\\s*:\\s*([0-9.]+)").matcher(sub);
                if (mClat.find()) {
                    clatAvgUs = Double.parseDouble(mClat.group(1)) / 1000.0;
                }

                Matcher mP50 = Pattern.compile("\"50\\.000000\"\\s*:\\s*([0-9.]+)").matcher(sub);
                if (mP50.find()) {
                    clatP50Us = Double.parseDouble(mP50.group(1)) / 1000.0;
                }

                Matcher mP99 = Pattern.compile("\"99\\.000000\"\\s*:\\s*([0-9.]+)").matcher(sub);
                if (mP99.find()) {
                    clatP99Us = Double.parseDouble(mP99.group(1)) / 1000.0;
                }

                Matcher mP99_9 = Pattern.compile("\"99\\.900000\"\\s*:\\s*([0-9.]+)").matcher(sub);
                if (mP99_9.find()) {
                    clatP99_9Us = Double.parseDouble(mP99_9.group(1)) / 1000.0;
                }

                return new FioResult(iops, bwMBs, slatAvgUs, clatAvgUs, clatP50Us, clatP99Us, clatP99_9Us);
            } catch (Exception e) {
                System.err.println("Notice: Could not parse fio JSON output: " + e.getMessage());
                return null;
            }
        }
    }
}
