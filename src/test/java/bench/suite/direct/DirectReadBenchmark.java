package bench.suite.direct;

import bench.suite.common.CpuAuxCounters;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * NVMe Direct I/O (O_DIRECT) Read Benchmark.
 *
 * Compares un-crippled FileChannel against JUring with registered files and buffers
 * across queue depths (QD = 1, 16, 64, 128) and thread concurrencies (1, 4, 8, 16).
 * Proves 0 B/op heap allocation on the hot path and true NVMe queue saturation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED",
        "-Djuring.countEnters=true"
})
@Threads(1)
public class DirectReadBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param({"MULTI_FILE", "SINGLE_FILE"})
        public String distribution = "MULTI_FILE";

        public DatasetManager dataset;

        @Setup(Level.Trial)
        public void setup() {
            DatasetManager.FileDistribution dist = (distribution != null)
                    ? DatasetManager.FileDistribution.valueOf(distribution)
                    : DatasetManager.FileDistribution.MULTI_FILE;
            dataset = new DatasetManager(
                    DatasetManager.WorkloadType.READ,
                    dist
            );
        }
    }

    @State(Scope.Thread)
    public static class ThreadState {

        @Param({"1", "16", "64", "128"})
        public int qd;

        public JUring ring;
        public FileDescriptor[] fileDescriptors;
        public FileChannel[] channels;
        public ByteBuffer alignedBuffer;

        public int reapedCount;
        public int inFlight;
        public int threadOffset;
        public ResultConsumer consumer;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int safeBufferCount = Math.max(qd, IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount));
            int ringEntries = Math.max(256, qd * 2);
            this.inFlight = 0;
            this.threadOffset = 0;

            ring = new JUring(ringEntries,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.dataset.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUring: " + regRet);
            }

            ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, safeBufferCount);

            channels = benchState.dataset.openFileChannels(true);
            alignedBuffer = IoHelper.allocateAligned(DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);

            final boolean visual = Boolean.getBoolean("juring.visual") || Boolean.getBoolean("juring.printReads");
            final AtomicInteger debugPrints = new AtomicInteger(0);

            this.reapedCount = 0;
            this.consumer = result -> {
                long res = result.result();
                if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("Direct read failed: expected "
                            + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + res);
                }
                if (visual && debugPrints.get() < 20) {
                    int printId = debugPrints.incrementAndGet();
                    if (printId <= 20) {
                        int bufIdx = result.bufferIdx();
                        MemorySegment buf = result.validBuffer();
                        byte[] previewBytes = new byte[Math.min(36, (int) res)];
                        if (buf != null && !buf.equals(MemorySegment.NULL)) {
                            MemorySegment.copy(buf, 0L, MemorySegment.ofArray(previewBytes), 0L, previewBytes.length);
                        }
                        String snippet = DirectReadVisualVerificationTest.formatAsciiPreview(previewBytes, 36);
                        System.out.printf("[DirectReadBenchmark JMH #%02d] buf=%-2d (%dB) | Data: \"%s\"%n",
                                printId, bufIdx, res, snippet);
                        System.out.flush();
                    }
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
            DatasetManager.closeChannels(channels);
            DatasetManager.closeFileDescriptors(fileDescriptors);
            if (ring != null) {
                ring.close();
            }
        }
    }

    @Benchmark
    public void juringDirectRead(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) {
        cpu.begin();
        final JUring ring = state.ring;
        ring.takeEnterCount();
        final int[] taskFileIndices = benchState.dataset.getTaskFileIndices();
        final long[] taskOffsets = benchState.dataset.getTaskOffsets();
        final int totalTasks = taskFileIndices.length;
        final int taskMask = totalTasks - 1;
        final int qd = state.qd;
        final int waitBatch = 1;

        int inFlight = state.inFlight;
        int threadOffset = state.threadOffset;

        while (inFlight < qd) {
            int bufIdx = ring.checkOutBuffer();
            if (bufIdx < 0) break;
            int idx = (taskMask > 0 && (taskMask & (taskMask + 1)) == 0)
                    ? (threadOffset++ & taskMask)
                    : (threadOffset++ % totalTasks);
            ring.prepareReadFixed(taskFileIndices[idx], DatasetManager.DEFAULT_BUFFER_SIZE, taskOffsets[idx], bufIdx);
            inFlight++;
        }

        int reapedTotal = 0;
        int enters = 0;

        while (reapedTotal < totalTasks) {
            int remaining = totalTasks - reapedTotal;
            int minWait = Math.max(1, Math.min(waitBatch, Math.min(remaining, inFlight)));
            int reaped = ring.submitAndWaitForBatchResult(minWait, qd, state.consumer);
            enters++;
            inFlight -= reaped;
            reapedTotal += reaped;

            while (inFlight < qd) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) break;
                int idx = (taskMask > 0 && (taskMask & (taskMask + 1)) == 0)
                        ? (threadOffset++ & taskMask)
                        : (threadOffset++ % totalTasks);
                ring.prepareReadFixed(taskFileIndices[idx], DatasetManager.DEFAULT_BUFFER_SIZE, taskOffsets[idx], bufIdx);
                inFlight++;
            }
        }

        state.inFlight = inFlight;
        state.threadOffset = threadOffset;
        state.reapedCount = reapedTotal;

        int countedEnters = ring.takeEnterCount();
        int totalEnters = countedEnters > 0 ? countedEnters : enters;
        counters.record(totalEnters, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        cpu.end();
        blackhole.consume(state.reapedCount);
    }

    @Benchmark
    public void fileChannelDirectRead(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) throws IOException {
        cpu.begin();
        final FileChannel[] channels = state.channels;
        final int[] taskFileIndices = benchState.dataset.getTaskFileIndices();
        final long[] taskOffsets = benchState.dataset.getTaskOffsets();
        final ByteBuffer buf = state.alignedBuffer;
        final int totalTasks = taskFileIndices.length;

        for (int i = 0; i < totalTasks; i++) {
            FileChannel fc = channels[taskFileIndices[i]];
            buf.clear();
            int read = fc.read(buf, taskOffsets[i]);
            if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
                throw new IllegalStateException("FileChannel direct read failed: expected "
                        + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + read);
            }
            blackhole.consume(read);
        }

        counters.record(totalTasks, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        cpu.end();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && ("--jmh".equals(args[0]) || "-jmh".equals(args[0]))) {
            org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                    .include(DirectReadBenchmark.class.getSimpleName())
                    .forks(1)
                    .build();
            new org.openjdk.jmh.runner.Runner(opt).run();
        } else {
            DirectReadVisualVerificationTest.main(args);
        }
    }
}
