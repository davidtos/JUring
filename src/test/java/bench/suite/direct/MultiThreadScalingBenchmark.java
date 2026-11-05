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
 * Multi-Thread NVMe Flash Bus Saturation & Concurrency Scaling.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED"
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
        public int inFlight;
        public ResultConsumer consumer;
        public boolean isSingleFile;
        public long maxBlocks;
        public DatasetManager.FioPrng prng;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int safeBufferCount = IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount);
            this.qd = Math.max(16, Math.min(64, safeBufferCount));
            this.submitBatch = (qd <= 1) ? 1 : 8;
            this.waitBatch = 1;

            this.isSingleFile = "SINGLE_FILE".equals(benchState.distribution);
            if (this.isSingleFile) {
                long size = benchState.readDataset.getFileSizes()[0];
                this.maxBlocks = size / DatasetManager.DEFAULT_BUFFER_SIZE;
                long seed = System.nanoTime() ^ ((long) params.getThreadIndex() * 31L);
                this.prng = new DatasetManager.FioPrng(seed);
                this.taskFileIndices = new int[]{0};
                this.taskOffsets = new long[]{0L};
                this.threadOffset = 0;
                System.out.printf("[MultiThreadScalingBenchmark] Read Thread #%d initialized PRNG with seed 0x%016x over %,d blocks (%,.1f MB)%n",
                        params.getThreadIndex(), seed, maxBlocks, (size / (1024.0 * 1024.0)));
            } else {
                DatasetManager.TaskPool pool = benchState.readDataset.generateThreadTasks(
                        params.getThreadIndex(), threadCount, SCALING_TASK_POOL_SIZE);
                this.taskFileIndices = pool.fileIndices();
                this.taskOffsets = pool.offsets();
                this.threadOffset = 0;
            }
            this.inFlight = 0;
            int ringEntries = Math.max(256, qd * 2);

            ring = new JUring(ringEntries,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.readDataset.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUring: " + regRet);
            }

            int bufCount = Math.min(safeBufferCount, Math.max(128, qd * 2));
            ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, bufCount);

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

        public int inFlight;
        public int reapedCount;
        public ResultConsumer consumer;
        public boolean isSingleFile;
        public long maxBlocks;
        public int startFile;
        public int localNumFiles;
        public long[] fileMaxBlocks;
        public DatasetManager.FioPrng prng;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadCount = params.getThreadCount();
            int threadIndex = params.getThreadIndex();
            int safeBufferCount = IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount);
            this.qd = Math.max(16, Math.min(64, safeBufferCount));
            this.submitBatch = (qd <= 1) ? 1 : 8;
            this.waitBatch = 1;

            long seed = System.nanoTime() ^ ((long) threadIndex * 31L);
            this.prng = new DatasetManager.FioPrng(seed);
            this.taskFileIndices = new int[]{0};
            this.taskOffsets = new long[]{0L};
            this.threadOffset = 0;

            this.isSingleFile = "SINGLE_FILE".equals(benchState.distribution);
            if (this.isSingleFile) {
                long size = benchState.writeDataset.getFileSizes()[0];
                this.maxBlocks = size / DatasetManager.DEFAULT_BUFFER_SIZE;
                this.startFile = 0;
                this.localNumFiles = 1;
                this.fileMaxBlocks = new long[]{this.maxBlocks};
                System.out.printf("[MultiThreadScalingBenchmark] Write Thread #%d initialized PRNG with seed 0x%016x over %,d blocks (%,.1f MB)%n",
                        threadIndex, seed, maxBlocks, (size / (1024.0 * 1024.0)));
            } else {
                long[] sizes = benchState.writeDataset.getFileSizes();
                int numFiles = sizes.length;
                int filesPerThread = Math.max(1, numFiles / Math.max(1, threadCount));
                this.startFile = threadIndex * filesPerThread;
                int endFile = (threadIndex == threadCount - 1) ? numFiles : Math.min(numFiles, this.startFile + filesPerThread);
                this.localNumFiles = Math.max(1, endFile - this.startFile);
                this.fileMaxBlocks = new long[numFiles];
                long totalBlocks = 0;
                for (int i = 0; i < numFiles; i++) {
                    this.fileMaxBlocks[i] = Math.max(1L, sizes[i] / DatasetManager.DEFAULT_BUFFER_SIZE);
                    if (i >= this.startFile && i < endFile) {
                        totalBlocks += this.fileMaxBlocks[i];
                    }
                }
                this.maxBlocks = this.fileMaxBlocks[this.startFile];
                System.out.printf("[MultiThreadScalingBenchmark] Write Thread #%d initialized MULTI_FILE PRNG over files [%d..%d) (%,d blocks)%n",
                        threadIndex, this.startFile, endFile, totalBlocks);
            }
            this.inFlight = 0;
            int ringEntries = Math.max(256, qd * 2);

            ring = new JUring(ringEntries,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.writeDataset.openFileDescriptors(LinuxOpenOptions.WRITE_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register write files in JUring: " + regRet);
            }

            int bufCount = Math.min(safeBufferCount, Math.max(128, qd * 2));
            MemorySegment[] registeredBuffers = ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, bufCount);
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
    public void juringDirectRead(ReadThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
        final JUring ring = state.ring;
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int qd = state.qd;
        final int submitBatch = state.submitBatch;
        final int waitBatch = state.waitBatch;
        final boolean isSingle = state.isSingleFile;
        final long maxBlocks = state.maxBlocks;
        final DatasetManager.FioPrng prng = state.prng;

        int inFlight = state.inFlight;
        int threadOffset = state.threadOffset;
        int unsubmitted = 0;
        int reapedTotal = 0;

        while (inFlight < qd) {
            int bufIdx = ring.checkOutBuffer();
            if (bufIdx < 0) break;
            if (isSingle) {
                long offset = prng.nextOffset(maxBlocks, DatasetManager.DEFAULT_BUFFER_SIZE);
                ring.prepareReadFixed(0, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
            } else {
                int idx = threadOffset++ & taskMask;
                ring.prepareReadFixed(taskFileIndices[idx], DatasetManager.DEFAULT_BUFFER_SIZE, taskOffsets[idx], bufIdx);
            }
            inFlight++;
            unsubmitted++;
        }

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
                    int idx = threadOffset++ & taskMask;
                    ring.prepareReadFixed(taskFileIndices[idx], DatasetManager.DEFAULT_BUFFER_SIZE, taskOffsets[idx], bufIdx);
                }
                inFlight++;
            }
        }

        state.inFlight = inFlight;
        state.threadOffset = threadOffset;
        state.reapedCount = reapedTotal;

        counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        blackhole.consume(state.reapedCount);
    }

    @Benchmark
    public void fileChannelDirectRead(ReadThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
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
                blackhole.consume(read);
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
                blackhole.consume(read);
            }
        }

        counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
    }

    @Benchmark
    public void juringDirectWrite(WriteThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) {
        final JUring ring = state.ring;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int qd = state.qd;
        final int waitBatch = state.waitBatch;
        final boolean isSingle = state.isSingleFile;
        final long maxBlocks = state.maxBlocks;
        final int startFile = state.startFile;
        final int localNumFiles = state.localNumFiles;
        final long[] fileMaxBlocks = state.fileMaxBlocks;
        final DatasetManager.FioPrng prng = state.prng;

        int inFlight = state.inFlight;
        int reapedTotal = 0;

        while (inFlight < qd) {
            int bufIdx = ring.checkOutBuffer();
            if (bufIdx < 0) break;
            if (isSingle) {
                long offset = prng.nextOffset(maxBlocks, DatasetManager.DEFAULT_BUFFER_SIZE);
                ring.prepareWriteFixed(0, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
            } else {
                int fileIdx = startFile + (int) prng.nextBlockIndex(localNumFiles);
                long offset = prng.nextOffset(fileMaxBlocks[fileIdx], DatasetManager.DEFAULT_BUFFER_SIZE);
                ring.prepareWriteFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
            }
            inFlight++;
        }

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
                    ring.prepareWriteFixed(0, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                } else {
                    int fileIdx = startFile + (int) prng.nextBlockIndex(localNumFiles);
                    long offset = prng.nextOffset(fileMaxBlocks[fileIdx], DatasetManager.DEFAULT_BUFFER_SIZE);
                    ring.prepareWriteFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, bufIdx);
                }
                inFlight++;
            }
        }

        state.inFlight = inFlight;
        state.reapedCount = reapedTotal;

        counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        blackhole.consume(state.reapedCount);
    }

    @Benchmark
    public void fileChannelDirectWrite(WriteThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
        final FileChannel[] channels = state.channels;
        final ByteBuffer buf = state.writeBuffer;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final DatasetManager.FioPrng prng = state.prng;

        if (state.isSingleFile) {
            final FileChannel fc = channels[0];
            final long maxBlocks = state.maxBlocks;
            for (int i = 0; i < totalTasks; i++) {
                long offset = prng.nextOffset(maxBlocks, DatasetManager.DEFAULT_BUFFER_SIZE);
                buf.position(0);
                int written = fc.write(buf, offset);
                if (written != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("FileChannel direct write failed: expected "
                            + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + written);
                }
                blackhole.consume(written);
            }
        } else {
            final int startFile = state.startFile;
            final int localNumFiles = state.localNumFiles;
            final long[] fileMaxBlocks = state.fileMaxBlocks;

            for (int i = 0; i < totalTasks; i++) {
                int fileIdx = startFile + (int) prng.nextBlockIndex(localNumFiles);
                long offset = prng.nextOffset(fileMaxBlocks[fileIdx], DatasetManager.DEFAULT_BUFFER_SIZE);
                FileChannel fc = channels[fileIdx];
                buf.position(0);
                int written = fc.write(buf, offset);
                if (written != DatasetManager.DEFAULT_BUFFER_SIZE) {
                    throw new IllegalStateException("FileChannel direct write failed: expected "
                            + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + written);
                }
                blackhole.consume(written);
            }
        }

        counters.record((long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
    }
}
