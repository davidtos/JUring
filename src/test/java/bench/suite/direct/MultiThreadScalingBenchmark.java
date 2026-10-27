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

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * Multi-Thread NVMe Flash Bus Saturation & Concurrency Scaling Suite.
 *
 * Compares Java standard FileChannel against JUring across 1, 4, 8, and 16 concurrent threads
 * for both direct reads and direct writes.
 * Demonstrates:
 *   1. Single-threaded JUring outperforming 16-threaded FileChannel.
 *   2. Multi-threaded JUring saturating the physical PCIe NVMe flash controller bus.
 *   3. Adaptive buffer pool sizing conforming to system RLIMIT_MEMLOCK restrictions.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED",
        "-Djuring.countEnters=true"
})
@Threads(1)
public class MultiThreadScalingBenchmark {

    public static final int SCALING_TASK_POOL_SIZE = 65536;

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        @Param({"MULTI_FILE", "SINGLE_FILE"})
        public String distribution = "MULTI_FILE";

        public DatasetManager readDataset;
        public DatasetManager writeDataset;

        @Setup(Level.Trial)
        public void setup() {
            DatasetManager.FileDistribution dist = (distribution != null)
                    ? DatasetManager.FileDistribution.valueOf(distribution)
                    : DatasetManager.FileDistribution.MULTI_FILE;
            readDataset = new DatasetManager(
                    DatasetManager.WorkloadType.READ,
                    dist,
                    SCALING_TASK_POOL_SIZE
            );
            writeDataset = new DatasetManager(
                    DatasetManager.WorkloadType.WRITE,
                    dist,
                    SCALING_TASK_POOL_SIZE
            );
        }
    }

    @State(Scope.Thread)
    public static class ReadThreadState {
        public int qd;
        public int submitBatch;
        public int waitBatch;
        public int threadOffset;
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public JUring ring;
        public FileDescriptor[] fileDescriptors;
        public FileChannel[] channels;
        public ByteBuffer alignedBuffer;

        public int reapedCount;
        public ResultConsumer consumer;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int safeBufferCount = IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount);
            this.qd = Math.max(16, Math.min(64, safeBufferCount));
            this.submitBatch = (qd <= 1) ? 1 : (threadCount >= 16 ? 8 : (threadCount >= 8 ? 16 : Math.min(qd, 32)));
            this.waitBatch = (qd <= 1) ? 1 : Math.max(1, Math.min(8, qd >> 3));
            DatasetManager.TaskPool pool = benchState.readDataset.generateThreadTasks(
                    params.getThreadIndex(), threadCount, SCALING_TASK_POOL_SIZE);
            this.taskFileIndices = pool.fileIndices();
            this.taskOffsets = pool.offsets();
            this.threadOffset = 0;
            int ringEntries = Math.max(128, qd * 2);

            ring = new JUring(ringEntries,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.readDataset.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUring: " + regRet);
            }

            ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, qd);

            channels = benchState.readDataset.openFileChannels(true);
            alignedBuffer = IoHelper.allocateAligned(DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);

            this.reapedCount = 0;
            this.consumer = result -> {
                long res = result.result();
                if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("MultiThread direct read failed: expected "
                            + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + res);
                }
                this.reapedCount++;
                this.ring.checkInBuffer(result.bufferIdx());
            };
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

    @State(Scope.Thread)
    public static class WriteThreadState {
        public int qd;
        public int submitBatch;
        public int waitBatch;
        public int threadOffset;
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public JUring ring;
        public FileDescriptor[] fileDescriptors;
        public FileChannel[] channels;
        public ByteBuffer writeBuffer;
        public MemorySegment writeSegment;

        public int reapedCount;
        public ResultConsumer consumer;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int safeBufferCount = IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount);
            this.qd = Math.max(16, Math.min(64, safeBufferCount));
            this.submitBatch = (qd <= 1) ? 1 : (threadCount >= 16 ? 8 : (threadCount >= 8 ? 16 : Math.min(qd, 32)));
            this.waitBatch = (qd <= 1) ? 1 : Math.max(1, Math.min(8, qd >> 3));
            DatasetManager.TaskPool pool = benchState.writeDataset.generateThreadTasks(
                    params.getThreadIndex(), threadCount, SCALING_TASK_POOL_SIZE);
            this.taskFileIndices = pool.fileIndices();
            this.taskOffsets = pool.offsets();
            this.threadOffset = 0;
            int ringEntries = Math.max(128, qd * 2);

            ring = new JUring(ringEntries,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.writeDataset.openFileDescriptors(LinuxOpenOptions.WRITE_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register write files in JUring: " + regRet);
            }

            MemorySegment[] registeredBuffers = ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, qd);
            for (MemorySegment regBuf : registeredBuffers) {
                regBuf.fill((byte) 0x5A);
            }

            channels = benchState.writeDataset.openFileChannels(true);

            writeBuffer = IoHelper.allocateAligned(DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);
            for (int i = 0; i < DatasetManager.DEFAULT_BUFFER_SIZE; i++) {
                writeBuffer.put((byte) 0x5A);
            }
            writeBuffer.flip();
            writeSegment = MemorySegment.ofBuffer(writeBuffer);

            this.reapedCount = 0;
            this.consumer = result -> {
                long res = result.result();
                if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("MultiThread direct write failed: expected "
                            + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + res);
                }
                this.reapedCount++;
                this.ring.checkInBuffer(result.bufferIdx());
            };
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
    public void juringDirectRead(ReadThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) {
        cpu.begin();
        final JUring ring = state.ring;
        ring.takeEnterCount();
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int localOffset = state.threadOffset;
        state.threadOffset = (localOffset + totalTasks) & taskMask;
        final int qd = state.qd;
        final int submitBatch = state.submitBatch;
        final int waitBatch = state.waitBatch;

        state.reapedCount = 0;
        int submitted = 0;
        int taskIndex = 0;
        int submitsIssued = 0;
        int waitsIssued = 0;
        int unsubmitted = 0;

        while (state.reapedCount < totalTasks) {
            while ((submitted - state.reapedCount) < qd && taskIndex < totalTasks) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) {
                    break;
                }

                int idx = (localOffset + taskIndex) & taskMask;
                int fileIdx = taskFileIndices[idx];
                long offset = taskOffsets[idx];

                ring.prepareReadFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                submitted++;
                unsubmitted++;
                taskIndex++;

                if (unsubmitted >= submitBatch) {
                    ring.submit();
                    submitsIssued++;
                    unsubmitted = 0;
                    ring.peekForBatchResult(qd, state.consumer);
                }
            }

            int reaped = ring.peekForBatchResult(qd, state.consumer);
            if (reaped == 0 && state.reapedCount < totalTasks) {
                int remaining = totalTasks - state.reapedCount;
                int inFlight = submitted - state.reapedCount;
                if (inFlight > 0) {
                    int minWait = Math.max(1, Math.min(waitBatch, Math.min(remaining, inFlight)));
                    ring.submitAndWaitForBatchResult(minWait, qd, state.consumer);
                    waitsIssued++;
                    unsubmitted = 0;
                }
            }
        }

        int countedEnters = ring.takeEnterCount();
        int totalEnters = countedEnters > 0 ? countedEnters : (submitsIssued + waitsIssued);
        counters.record(totalEnters, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        cpu.end();
        blackhole.consume(state.reapedCount);
    }

    @Benchmark
    public void fileChannelDirectRead(ReadThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) throws IOException {
        cpu.begin();
        final FileChannel[] channels = state.channels;
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final ByteBuffer buf = state.alignedBuffer;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
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
            blackhole.consume(read);
        }

        counters.record(totalTasks, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        cpu.end();
    }

    @Benchmark
    public void juringDirectWrite(WriteThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) {
        cpu.begin();
        final JUring ring = state.ring;
        ring.takeEnterCount();
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int localOffset = state.threadOffset;
        state.threadOffset = (localOffset + totalTasks) & taskMask;
        final int qd = state.qd;
        final int submitBatch = state.submitBatch;
        final int waitBatch = state.waitBatch;

        state.reapedCount = 0;
        int submitted = 0;
        int taskIndex = 0;
        int submitsIssued = 0;
        int waitsIssued = 0;
        int unsubmitted = 0;

        while (state.reapedCount < totalTasks) {
            while ((submitted - state.reapedCount) < qd && taskIndex < totalTasks) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) {
                    break;
                }

                int idx = (localOffset + taskIndex) & taskMask;
                int fileIdx = taskFileIndices[idx];
                long offset = taskOffsets[idx];

                ring.prepareWriteFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                submitted++;
                unsubmitted++;
                taskIndex++;

                if (unsubmitted >= submitBatch) {
                    ring.submit();
                    submitsIssued++;
                    unsubmitted = 0;
                    ring.peekForBatchResult(qd, state.consumer);
                }
            }

            int reaped = ring.peekForBatchResult(qd, state.consumer);
            if (reaped == 0 && state.reapedCount < totalTasks) {
                int remaining = totalTasks - state.reapedCount;
                int inFlight = submitted - state.reapedCount;
                if (inFlight > 0) {
                    int minWait = Math.max(1, Math.min(waitBatch, Math.min(remaining, inFlight)));
                    ring.submitAndWaitForBatchResult(minWait, qd, state.consumer);
                    waitsIssued++;
                    unsubmitted = 0;
                }
            }
        }

        int countedEnters = ring.takeEnterCount();
        int totalEnters = countedEnters > 0 ? countedEnters : (submitsIssued + waitsIssued);
        counters.record(totalEnters, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        cpu.end();
        blackhole.consume(state.reapedCount);
    }

    @Benchmark
    public void fileChannelDirectWrite(WriteThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) throws IOException {
        cpu.begin();
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

        counters.record(totalTasks, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        cpu.end();
    }
}
