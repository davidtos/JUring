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
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * NVMe Direct I/O (O_DIRECT) Write Benchmark.
 *
 * Compares FileChannel vs JUring random writes across both multi-file
 * (preventing VFS inode lock serialization) and single-file contention modes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED"
})
@Threads(1)
public class DirectWriteBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        @Param({"MULTI_FILE", "SINGLE_FILE"})
        public String distribution;

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

    @State(Scope.Thread)
    public static class JuringThreadState {

        @Param({"1", "64"})
        public int qd = 64;

        public JUring ring;
        public FileDescriptor[] fileDescriptors;
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public int threadOffset;
        public int inFlight;
        public int reapedCount;
        public ResultConsumer consumer;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int safeBufferCount = Math.max(qd, Math.min(64, IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount)));

            DatasetManager.TaskPool pool = benchState.dataset.generateThreadTasks(
                    params.getThreadIndex(), threadCount, 65536);
            this.taskFileIndices = pool.fileIndices();
            this.taskOffsets = pool.offsets();
            this.threadOffset = 0;
            this.inFlight = 0;

            ring = new JUring(Math.max(256, qd * 2),
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

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
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public int threadOffset;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            DatasetManager.TaskPool pool = benchState.dataset.generateThreadTasks(
                    params.getThreadIndex(), threadCount, 65536);
            this.taskFileIndices = pool.fileIndices();
            this.taskOffsets = pool.offsets();
            this.threadOffset = 0;

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

    @Benchmark
    public void juringDirectWrite(JuringThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
        final JUring ring = state.ring;
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int qd = state.qd;
        final int waitBatch = 1;

        int inFlight = state.inFlight;
        int threadOffset = state.threadOffset;

        while (inFlight < qd) {
            int bufIdx = ring.checkOutBuffer();
            if (bufIdx < 0) break;
            int idx = (taskMask > 0 && (taskMask & (taskMask + 1)) == 0)
                    ? (threadOffset++ & taskMask)
                    : (threadOffset++ % taskFileIndices.length);
            ring.prepareWriteFixed(taskFileIndices[idx], DatasetManager.DEFAULT_BUFFER_SIZE, taskOffsets[idx], bufIdx);
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
                int idx = (taskMask > 0 && (taskMask & (taskMask + 1)) == 0)
                        ? (threadOffset++ & taskMask)
                        : (threadOffset++ % taskFileIndices.length);
                ring.prepareWriteFixed(taskFileIndices[idx], DatasetManager.DEFAULT_BUFFER_SIZE, taskOffsets[idx], bufIdx);
                inFlight++;
            }
        }

        state.inFlight = inFlight;
        state.threadOffset = (taskMask > 0 && (taskMask & (taskMask + 1)) == 0)
                ? (threadOffset & taskMask)
                : (threadOffset % taskFileIndices.length);
        state.reapedCount = reapedTotal;

        counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        blackhole.consume(state.reapedCount);
    }

    @Benchmark
    public void fileChannelDirectWrite(FileChannelThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
        final FileChannel[] channels = state.channels;
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final ByteBuffer buf = state.writeBuffer;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int localOffset = state.threadOffset;
        state.threadOffset = (localOffset + totalTasks) & taskMask;

        for (int i = 0; i < totalTasks; i++) {
            int idx = (localOffset + i) & taskMask;
            FileChannel fc = channels[taskFileIndices[idx]];
            buf.position(0);
            int written = fc.write(buf, taskOffsets[idx]);
            if (written != DatasetManager.DEFAULT_BUFFER_SIZE) {
                throw new IllegalStateException("FileChannel direct write failed: expected "
                        + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + written);
            }
            blackhole.consume(written);
        }

        counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
    }
}
