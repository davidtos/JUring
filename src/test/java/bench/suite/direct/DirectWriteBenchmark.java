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
 * NVMe Direct I/O (O_DIRECT) Write Benchmark.
 *
 * Compares FileChannel vs JUring random writes across both multi-file
 * (preventing VFS inode lock serialization) and single-file contention modes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED",
        "-Djuring.countEnters=true"
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
    public static class ThreadState {

        public JUring ring;
        public FileDescriptor[] fileDescriptors;
        public FileChannel[] channels;
        public ByteBuffer writeBuffer;
        public MemorySegment writeSegment;
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public int threadOffset;

        public int reapedCount;
        public ResultConsumer consumer;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int safeBufferCount = Math.max(16, Math.min(64, IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount)));

            DatasetManager.TaskPool pool = benchState.dataset.generateThreadTasks(
                    params.getThreadIndex(), threadCount, 65536);
            this.taskFileIndices = pool.fileIndices();
            this.taskOffsets = pool.offsets();
            this.threadOffset = 0;

            ring = new JUring(256,
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

            channels = benchState.dataset.openFileChannels(true);

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
                    throw new IllegalStateException("Direct write failed: expected "
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
    public void juringDirectWrite(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) {
        cpu.begin();
        final JUring ring = state.ring;
        ring.takeEnterCount();
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int localOffset = state.threadOffset;
        state.threadOffset = (localOffset + totalTasks) & taskMask;
        final int qd = 64;
        final int submitBatch = 32;
        final int waitBatch = 8;

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
                    int minComplete = Math.max(1, Math.min(inFlight, Math.min(remaining, waitBatch)));
                    ring.submitAndWaitForBatchResult(minComplete, qd, state.consumer);
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
    public void fileChannelDirectWrite(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) throws IOException {
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
