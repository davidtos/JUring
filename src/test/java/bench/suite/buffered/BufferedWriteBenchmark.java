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
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * Buffered / Page-Cache Write Benchmark.
 *
 * Compares FileChannel vs JUring when dirtying pages in Linux page cache (RAM).
 * Measures pure in-memory syscall batching efficiency.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED"
})
@Threads(1)
public class BufferedWriteBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        public DatasetManager dataset;

        @Setup(Level.Trial)
        public void setup() {
            dataset = new DatasetManager(
                    DatasetManager.WorkloadType.WRITE,
                    DatasetManager.FileDistribution.MULTI_FILE
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

        @Setup(Level.Iteration)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int safeBufferCount = Math.max(16, Math.min(128, IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount)));

            ring = new JUring(512,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.dataset.openFileDescriptors(LinuxOpenOptions.WRITE);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUring: " + regRet);
            }

            MemorySegment[] registeredBuffers = ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, safeBufferCount);
            for (MemorySegment regBuf : registeredBuffers) {
                regBuf.fill((byte) 0x3C);
            }

            channels = benchState.dataset.openFileChannels(false);

            writeBuffer = ByteBuffer.allocateDirect(DatasetManager.DEFAULT_BUFFER_SIZE);
            for (int i = 0; i < DatasetManager.DEFAULT_BUFFER_SIZE; i++) {
                writeBuffer.put((byte) 0x3C);
            }
            writeBuffer.flip();
            writeSegment = MemorySegment.ofBuffer(writeBuffer);

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
    public void juringBufferedWrite(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
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

                ring.prepareWriteFixed(fileIdx, state.writeSegment, offset, bufIdx);
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
    public void fileChannelBufferedWrite(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
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
