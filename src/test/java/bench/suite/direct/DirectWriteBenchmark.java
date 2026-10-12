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
    public static class ThreadState {

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
            int safeBufferCount = Math.max(16, Math.min(64, IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount)));

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
    public void juringDirectWrite(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
        final JUring ring = state.ring;
        final int[] taskFileIndices = benchState.dataset.getTaskFileIndices();
        final long[] taskOffsets = benchState.dataset.getTaskOffsets();
        final int totalTasks = taskFileIndices.length;
        final int qd = 64;
        final int submitBatch = 16;

        state.reapedCount = 0;
        int submitted = 0;
        int taskIndex = 0;
        int submitsIssued = 0;

        while (state.reapedCount < totalTasks) {
            while ((submitted - state.reapedCount) < qd && taskIndex < totalTasks) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) {
                    break;
                }

                int fileIdx = taskFileIndices[taskIndex];
                long offset = taskOffsets[taskIndex];

                ring.prepareWriteFixed(fileIdx, state.writeSegment, offset, bufIdx);
                submitted++;
                taskIndex++;

                if (submitted % submitBatch == 0) {
                    ring.submit();
                    submitsIssued++;
                    ring.peekForBatchResult(submitBatch, state.consumer);
                }
            }

            if (submitted > state.reapedCount) {
                ring.submit();
                submitsIssued++;
            }

            int batchToReap = Math.min(submitted - state.reapedCount, submitBatch);
            int reaped = ring.peekForBatchResult(batchToReap, state.consumer);
            if (reaped == 0 && state.reapedCount < totalTasks) {
                ring.waitForBatchResult(1, state.consumer);
            }
        }

        counters.record(submitsIssued, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        blackhole.consume(state.reapedCount);
    }

    @Benchmark
    public void fileChannelDirectWrite(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
        final FileChannel[] channels = state.channels;
        final int[] taskFileIndices = benchState.dataset.getTaskFileIndices();
        final long[] taskOffsets = benchState.dataset.getTaskOffsets();
        final ByteBuffer buf = state.writeBuffer;
        final int totalTasks = taskFileIndices.length;

        for (int i = 0; i < totalTasks; i++) {
            FileChannel fc = channels[taskFileIndices[i]];
            buf.position(0);
            int written = fc.write(buf, taskOffsets[i]);
            blackhole.consume(written);
        }

        counters.record(totalTasks, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
    }
}
