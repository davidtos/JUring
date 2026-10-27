package bench.suite.direct;

import bench.suite.common.CpuAuxCounters;
import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import bench.suite.common.StorageAuxCounters;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUringBlocking;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;

/**
 * Project Loom Virtual Threads NVMe Direct Read Benchmark.
 *
 * Compares Java standard FileChannel (which executes synchronous blocking pread64 system calls
 * that monopolize and exhaust OS carrier threads in ForkJoinPool)
 * against JUringBlocking (which unmounts virtual threads cleanly via LockSupport.park()
 * during asynchronous I/O and stripes virtual threads across non-blocking single-issuer poller rings)
 * across virtual thread concurrency levels (50, 100, 250, 500, 1,000 VTs).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED",
        "-Djuring.countEnters=true"
})
@Threads(1)
public class VirtualThreadBenchmark {

    public static final int SCALING_TASK_POOL_SIZE = 65536;

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        @Param({"MULTI_FILE", "SINGLE_FILE"})
        public String distribution = "MULTI_FILE";

        public DatasetManager readDataset;

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
        }
    }

    @State(Scope.Thread)
    public static class ThreadState {

        @Param({"50", "100", "250", "500", "1000"})
        public int virtualThreads = 100;

        public int threadOffset;
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public JUringBlocking ring;
        public FileDescriptor[] fileDescriptors;
        public FileChannel[] channels;
        public ByteBuffer[] alignedBuffers;
        public MemorySegment[] segments;
        public ExecutorService executor;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState) {
            DatasetManager.TaskPool pool = benchState.readDataset.generateThreadTasks(
                    0, 1, SCALING_TASK_POOL_SIZE);
            this.taskFileIndices = pool.fileIndices();
            this.taskOffsets = pool.offsets();
            this.threadOffset = 0;

            int qd = Math.max(256, Math.min(1024, virtualThreads));
            this.ring = new JUringBlocking(qd,
                    IORING_SETUP_SINGLE_ISSUER,
                    com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN,
                    com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN);

            this.fileDescriptors = benchState.readDataset.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUringBlocking: " + regRet);
            }

            this.channels = benchState.readDataset.openFileChannels(true);
            this.executor = Executors.newVirtualThreadPerTaskExecutor();

            this.alignedBuffers = new ByteBuffer[virtualThreads];
            this.segments = new MemorySegment[virtualThreads];
            for (int i = 0; i < virtualThreads; i++) {
                alignedBuffers[i] = IoHelper.allocateAligned(DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);
                segments[i] = MemorySegment.ofBuffer(alignedBuffers[i]);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (executor != null) {
                executor.close();
            }
            DatasetManager.closeChannels(channels);
            DatasetManager.closeFileDescriptors(fileDescriptors);
            if (ring != null) {
                ring.close();
            }
        }
    }

    @Benchmark
    public void juringVirtualThreadRead(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) throws Exception {
        cpu.begin();
        final JUringBlocking ring = state.ring;
        ring.takeEnterCount();
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int localOffset = state.threadOffset;
        state.threadOffset = (localOffset + totalTasks) & taskMask;

        final int vtCount = state.virtualThreads;
        final MemorySegment[] segments = state.segments;
        final ExecutorService executor = state.executor;
        final CountDownLatch latch = new CountDownLatch(vtCount);
        final AtomicReference<Throwable> failureRef = new AtomicReference<>();
        final int tasksPerVt = totalTasks / vtCount;
        final int remainder = totalTasks % vtCount;
        int offset = 0;

        for (int t = 0; t < vtCount; t++) {
            final int start = offset;
            final int count = tasksPerVt + (t < remainder ? 1 : 0);
            offset += count;
            final int vtIndex = t;

            executor.execute(() -> {
                try {
                    MemorySegment buf = segments[vtIndex];
                    for (int i = 0; i < count; i++) {
                        int idx = (localOffset + start + i) & taskMask;
                        int fileIdx = taskFileIndices[idx];
                        long fileOffset = taskOffsets[idx];

                        long read = ring.read(fileIdx, buf, fileOffset);
                        if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
                            throw new IllegalStateException("JUringBlocking VT read returned unexpected bytes: " + read);
                        }
                        if (blackhole != null) {
                            blackhole.consume(read);
                        }
                    }
                } catch (Throwable t1) {
                    failureRef.compareAndSet(null, t1);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        Throwable failure = failureRef.get();
        if (failure != null) {
            if (failure instanceof Exception e) throw e;
            throw new RuntimeException("JUringBlocking VT read failed", failure);
        }

        int countedEnters = ring.takeEnterCount();
        int totalEnters = countedEnters > 0 ? countedEnters : 1;
        counters.record(totalEnters, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        cpu.end();
    }

    @Benchmark
    public void fileChannelVirtualThreadRead(ThreadState state, BenchmarkState benchState, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) throws Exception {
        cpu.begin();
        final FileChannel[] channels = state.channels;
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int taskMask = taskFileIndices.length - 1;
        final int localOffset = state.threadOffset;
        state.threadOffset = (localOffset + totalTasks) & taskMask;

        final int vtCount = state.virtualThreads;
        final ByteBuffer[] byteBuffers = state.alignedBuffers;
        final ExecutorService executor = state.executor;
        final CountDownLatch latch = new CountDownLatch(vtCount);
        final AtomicReference<Throwable> failureRef = new AtomicReference<>();
        final int tasksPerVt = totalTasks / vtCount;
        final int remainder = totalTasks % vtCount;
        int offset = 0;

        for (int t = 0; t < vtCount; t++) {
            final int start = offset;
            final int count = tasksPerVt + (t < remainder ? 1 : 0);
            offset += count;
            final int vtIndex = t;

            executor.execute(() -> {
                try {
                    ByteBuffer buf = byteBuffers[vtIndex];
                    for (int i = 0; i < count; i++) {
                        int idx = (localOffset + start + i) & taskMask;
                        int fileIdx = taskFileIndices[idx];
                        long fileOffset = taskOffsets[idx];

                        buf.position(0);
                        int read = channels[fileIdx].read(buf, fileOffset);
                        if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
                            throw new IllegalStateException("FileChannel VT read returned unexpected bytes: " + read);
                        }
                        if (blackhole != null) {
                            blackhole.consume(read);
                        }
                    }
                } catch (Throwable t2) {
                    failureRef.compareAndSet(null, t2);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        Throwable failure = failureRef.get();
        if (failure != null) {
            if (failure instanceof Exception e) throw e;
            throw new RuntimeException("FileChannel VT read failed", failure);
        }

        counters.record(totalTasks, (long) totalTasks * DatasetManager.DEFAULT_BUFFER_SIZE);
        cpu.end();
    }
}
