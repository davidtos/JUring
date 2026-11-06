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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED"
})
@Threads(1)
public class EqualQueueDepthBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param({"SINGLE_FILE"})
        public String distribution = "SINGLE_FILE";

        public DatasetManager dataset;

        @Setup(Level.Trial)
        public void setup() {
            DatasetManager.FileDistribution dist = (distribution != null)
                    ? DatasetManager.FileDistribution.valueOf(distribution)
                    : DatasetManager.FileDistribution.SINGLE_FILE;
            dataset = new DatasetManager(
                    DatasetManager.WorkloadType.READ,
                    dist
            );
        }
    }

    @State(Scope.Thread)
    public static class JuringThreadState {

        @Param({"1", "16", "32", "64"})
        public int qd;

        public JUring ring;
        public FileDescriptor[] fileDescriptors;
        public int[] taskFileIndices;
        public long[] taskOffsets;

        public int reapedCount;
        public int inFlight;
        public int threadOffset;
        public ResultConsumer consumer;
        public boolean isSingleFile;
        public long maxBlocks;
        public DatasetManager.FioPrng prng;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState) {
            this.inFlight = 0;
            this.isSingleFile = "SINGLE_FILE".equals(benchState.distribution);
            if (this.isSingleFile) {
                long size = benchState.dataset.getFileSizes()[0];
                this.maxBlocks = size / DatasetManager.DEFAULT_BUFFER_SIZE;
                long seed = System.nanoTime() ^ 0x5DEECE66DL;
                this.prng = new DatasetManager.FioPrng(seed);
                this.taskFileIndices = new int[]{0};
                this.taskOffsets = new long[]{0L};
                this.threadOffset = 0;
            } else {
                DatasetManager.TaskPool pool = benchState.dataset.generateThreadTasks(0, 1, 65536);
                this.taskFileIndices = pool.fileIndices();
                this.taskOffsets = pool.offsets();
                this.threadOffset = 0;
            }

            int safeBufferCount = Math.max(128, qd * 2);
            int ringEntries = Math.max(256, qd * 2);

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

            this.reapedCount = 0;
            this.consumer = result -> {
                long res = result.result();
                if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("EqualQueueDepth JUring read failed: expected "
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

        @Param({"1", "16", "32", "64"})
        public int qd;

        public FileChannel[] channels;
        public ByteBuffer alignedBuffer;
        public boolean isSingleFile;
        public long maxBlocks;
        public DatasetManager.FioPrng prng;
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public int threadOffset;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState) {
            this.isSingleFile = "SINGLE_FILE".equals(benchState.distribution);
            if (this.isSingleFile) {
                long size = benchState.dataset.getFileSizes()[0];
                this.maxBlocks = size / DatasetManager.DEFAULT_BUFFER_SIZE;
                long seed = System.nanoTime() ^ 0x5DEECE66DL;
                this.prng = new DatasetManager.FioPrng(seed);
                this.taskFileIndices = new int[]{0};
                this.taskOffsets = new long[]{0L};
                this.threadOffset = 0;
            } else {
                DatasetManager.TaskPool pool = benchState.dataset.generateThreadTasks(0, 1, 65536);
                this.taskFileIndices = pool.fileIndices();
                this.taskOffsets = pool.offsets();
                this.threadOffset = 0;
            }

            this.channels = benchState.dataset.openFileChannels(true);
            this.alignedBuffer = IoHelper.allocateAligned(
                    DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            DatasetManager.closeChannels(channels);
        }
    }

    @Benchmark
    public void juringDirectRead(JuringThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
        final JUring ring = state.ring;
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int qd = state.qd;
        final int waitBatch = 1;
        final boolean isSingle = state.isSingleFile;
        final long maxBlocks = state.maxBlocks;
        final DatasetManager.FioPrng prng = state.prng;

        int inFlight = state.inFlight;
        int threadOffset = state.threadOffset;

        while (inFlight < qd) {
            int bufIdx = ring.checkOutBuffer();
            if (bufIdx < 0) break;
            if (isSingle) {
                long offset = prng.nextOffset(maxBlocks, DatasetManager.DEFAULT_BUFFER_SIZE);
                ring.prepareReadFixed(0, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
            } else {
                int idx = (taskMask > 0 && (taskMask & (taskMask + 1)) == 0)
                        ? (threadOffset++ & taskMask)
                        : (threadOffset++ % taskFileIndices.length);
                ring.prepareReadFixed(taskFileIndices[idx], DatasetManager.DEFAULT_BUFFER_SIZE, taskOffsets[idx], bufIdx);
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
                    ring.prepareReadFixed(0, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                } else {
                    int idx = (taskMask > 0 && (taskMask & (taskMask + 1)) == 0)
                            ? (threadOffset++ & taskMask)
                            : (threadOffset++ % taskFileIndices.length);
                    ring.prepareReadFixed(taskFileIndices[idx], DatasetManager.DEFAULT_BUFFER_SIZE, taskOffsets[idx], bufIdx);
                }
                inFlight++;
            }
        }

        state.inFlight = inFlight;
        state.threadOffset = (taskMask > 0 && (taskMask & (taskMask + 1)) == 0)
                ? (threadOffset & taskMask)
                : (threadOffset % taskFileIndices.length);
        state.reapedCount = reapedTotal;

        if (counters != null) {
            counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        }
        if (blackhole != null) {
            blackhole.consume(state.reapedCount);
        }
    }

    @Benchmark
    public void fileChannelDirectRead(FileChannelThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
        final FileChannel[] channels = state.channels;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final ByteBuffer buf = state.alignedBuffer;

        if (state.isSingleFile) {
            final FileChannel fc = channels[0];
            final long maxBlocks = state.maxBlocks;
            final DatasetManager.FioPrng prng = state.prng;

            for (int i = 0; i < totalTasks; i++) {
                long offset = prng.nextOffset(maxBlocks, DatasetManager.DEFAULT_BUFFER_SIZE);
                buf.clear();
                int read = fc.read(buf, offset);
                if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("FileChannel direct read failed: expected "
                            + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + read);
                }
                if (blackhole != null) {
                    blackhole.consume(read);
                }
            }
        } else {
            final int[] taskFileIndices = state.taskFileIndices;
            final long[] taskOffsets = state.taskOffsets;
            final int taskMask = taskFileIndices.length - 1;
            final int localOffset = state.threadOffset;
            state.threadOffset = (localOffset + totalTasks) & taskMask;

            for (int i = 0; i < totalTasks; i++) {
                int idx = (localOffset + i) & taskMask;
                FileChannel fc = channels[taskFileIndices[idx]];
                buf.clear();
                int read = fc.read(buf, taskOffsets[idx]);
                if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("FileChannel direct read failed: expected "
                            + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + read);
                }
                if (blackhole != null) {
                    blackhole.consume(read);
                }
            }
        }

        if (counters != null) {
            counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                .include(EqualQueueDepthBenchmark.class.getSimpleName())
                .forks(1)
                .build();
        new org.openjdk.jmh.runner.Runner(opt).run();
    }
}
