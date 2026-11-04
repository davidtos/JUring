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
            int threadIndex = params.getThreadIndex();
            int threadCount = params.getThreadCount();
            int safeBufferCount = Math.max(qd, Math.min(64, IoHelper.getSafeBufferCount(DatasetManager.DEFAULT_BUFFER_SIZE, threadCount)));

            this.isSingleFile = "SINGLE_FILE".equals(benchState.distribution);
            long seed = System.nanoTime() ^ ((long) threadIndex * 31L);
            this.prng = new DatasetManager.FioPrng(seed);
            if (this.isSingleFile) {
                long size = benchState.dataset.getFileSizes()[0];
                this.maxBlocks = size / DatasetManager.DEFAULT_BUFFER_SIZE;
                this.startFile = 0;
                this.localNumFiles = 1;
                this.fileMaxBlocks = new long[]{this.maxBlocks};
                System.out.printf("[DirectWriteBenchmark] JUring Thread #%d initialized PRNG with seed 0x%016x over %,d blocks (%,.1f MB)%n",
                        threadIndex, seed, maxBlocks, (size / (1024.0 * 1024.0)));
            } else {
                long[] sizes = benchState.dataset.getFileSizes();
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
                System.out.printf("[DirectWriteBenchmark] JUring Thread #%d initialized MULTI_FILE PRNG over files [%d..%d) (%,d blocks)%n",
                        threadIndex, this.startFile, endFile, totalBlocks);
            }
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
        public boolean isSingleFile;
        public long maxBlocks;
        public int startFile;
        public int localNumFiles;
        public long[] fileMaxBlocks;
        public DatasetManager.FioPrng prng;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState, ThreadParams params) {
            int threadIndex = params.getThreadIndex();
            int threadCount = params.getThreadCount();
            this.isSingleFile = "SINGLE_FILE".equals(benchState.distribution);
            long seed = System.nanoTime() ^ ((long) threadIndex * 31L);
            this.prng = new DatasetManager.FioPrng(seed);
            if (this.isSingleFile) {
                long size = benchState.dataset.getFileSizes()[0];
                this.maxBlocks = size / DatasetManager.DEFAULT_BUFFER_SIZE;
                this.startFile = 0;
                this.localNumFiles = 1;
                this.fileMaxBlocks = new long[]{this.maxBlocks};
            } else {
                long[] sizes = benchState.dataset.getFileSizes();
                int numFiles = sizes.length;
                int filesPerThread = Math.max(1, numFiles / Math.max(1, threadCount));
                this.startFile = threadIndex * filesPerThread;
                int endFile = (threadIndex == threadCount - 1) ? numFiles : Math.min(numFiles, this.startFile + filesPerThread);
                this.localNumFiles = Math.max(1, endFile - this.startFile);
                this.fileMaxBlocks = new long[numFiles];
                for (int i = 0; i < numFiles; i++) {
                    this.fileMaxBlocks[i] = Math.max(1L, sizes[i] / DatasetManager.DEFAULT_BUFFER_SIZE);
                }
                this.maxBlocks = this.fileMaxBlocks[this.startFile];
            }

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
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int qd = state.qd;
        final int waitBatch = 1;
        final boolean isSingle = state.isSingleFile;
        final long maxBlocks = state.maxBlocks;
        final int startFile = state.startFile;
        final int localNumFiles = state.localNumFiles;
        final long[] fileMaxBlocks = state.fileMaxBlocks;
        final DatasetManager.FioPrng prng = state.prng;

        int inFlight = state.inFlight;

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
    public void fileChannelDirectWrite(FileChannelThreadState state, BenchmarkState benchState, StorageAuxCounters counters, Blackhole blackhole) throws IOException {
        final FileChannel[] channels = state.channels;
        final ByteBuffer buf = state.writeBuffer;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;

        if (state.isSingleFile) {
            final FileChannel fc = channels[0];
            final long maxBlocks = state.maxBlocks;
            final DatasetManager.FioPrng prng = state.prng;
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
            final DatasetManager.FioPrng prng = state.prng;

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
