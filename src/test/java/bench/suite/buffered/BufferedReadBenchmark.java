package bench.suite.buffered;

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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * Buffered / Page-Cache Read Benchmark.
 *
 * Compares fair, un-crippled FileChannel against batched JUring (batch 64)
 * when file content is fully resident in Linux page cache (DRAM).
 * Quantifies pure system call context switch amortization.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED"
})
@Threads(1)
public class BufferedReadBenchmark {

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
        public ByteBuffer directBuffer;

        public int reapedCount;
        public ResultConsumer consumer;

        @Setup(Level.Iteration)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int safeBufferCount = Math.max(16, Math.min(128, IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount)));

            ring = new JUring(512,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.dataset.openFileDescriptors(LinuxOpenOptions.READ);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUring: " + regRet);
            }

            ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, safeBufferCount);

            // Open buffered FileChannels (without ExtendedOpenOption.DIRECT)
            channels = benchState.dataset.openFileChannels(false);

            // Fair pre-allocated direct ByteBuffer (eliminates heap allocation & GC stalls)
            directBuffer = ByteBuffer.allocateDirect(DatasetManager.DEFAULT_BUFFER_SIZE);

            this.reapedCount = 0;
            this.consumer = result -> {
                this.reapedCount++;
                this.ring.checkInBuffer(result.bufferIdx());
            };
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
    public void juringBufferedRead(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
        final JUring ring = state.ring;
        final int[] taskFileIndices = benchState.dataset.getTaskFileIndices();
        final long[] taskOffsets = benchState.dataset.getTaskOffsets();
        final int totalTasks = taskFileIndices.length;
        final int batchSize = 64;

        state.reapedCount = 0;
        int submitted = 0;
        int taskIndex = 0;
        int submitsIssued = 0;

        while (state.reapedCount < totalTasks) {
            while ((submitted - state.reapedCount) < 128 && taskIndex < totalTasks) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) {
                    break;
                }

                int fileIdx = taskFileIndices[taskIndex];
                long offset = taskOffsets[taskIndex];

                ring.prepareReadFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                submitted++;
                taskIndex++;

                if (submitted % batchSize == 0) {
                    ring.submit();
                    submitsIssued++;
                    ring.peekForBatchResult(batchSize, state.consumer);
                }
            }

            if (submitted > state.reapedCount) {
                ring.submit();
                submitsIssued++;
            }

            int batchToReap = Math.min(submitted - state.reapedCount, batchSize);
            int reaped = ring.peekForBatchResult(batchToReap, state.consumer);
            if (reaped == 0 && state.reapedCount < totalTasks) {
                ring.waitForBatchResult(1, state.consumer);
            }
        }

        counters.record(submitsIssued, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        blackhole.consume(state.reapedCount);
    }

    @Benchmark
    public void fileChannelBufferedRead(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
        final FileChannel[] channels = state.channels;
        final int[] taskFileIndices = benchState.dataset.getTaskFileIndices();
        final long[] taskOffsets = benchState.dataset.getTaskOffsets();
        final ByteBuffer buf = state.directBuffer;
        final int totalTasks = taskFileIndices.length;

        for (int i = 0; i < totalTasks; i++) {
            FileChannel fc = channels[taskFileIndices[i]];
            buf.clear();
            int read = fc.read(buf, taskOffsets[i]);
            blackhole.consume(read);
        }

        counters.record(totalTasks, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
    }
}
