package bench.suite.direct;

import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import bench.suite.common.StorageAuxCounters;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUringBlocking;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
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
@OperationsPerInvocation(VirtualThreadBenchmark.OPERATIONS_PER_INVOCATION)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED"
})
@Threads(1)
public class VirtualThreadBenchmark {

    public static final int OPERATIONS_PER_INVOCATION = 10000;
    public static final int SCALING_TASK_POOL_SIZE = 65536;

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        @Param({"MULTI_FILE", "SINGLE_FILE"})
        public String distribution = "MULTI_FILE";

        @Param({"DIRECT", "BUFFERED_COLD"})
        public String ioMode = "DIRECT";

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

        @Setup(Level.Iteration)
        public void setupIteration() {
            if ("BUFFERED_COLD".equalsIgnoreCase(ioMode)) {
                IoHelper.dropPageCache(readDataset.getUniquePaths());
            }
        }
    }

    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class DeviceCounters {
        public long deviceReadBytes;
    }

    @State(Scope.Thread)
    public static class JuringVirtualThreadState {

        @Param({"50", "100", "250", "500", "1000"})
        public int virtualThreads = 100;

        public int threadOffset;
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public JUringBlocking ring;
        public FileDescriptor[] fileDescriptors;
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
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            boolean direct = !"BUFFERED_COLD".equalsIgnoreCase(benchState.ioMode);
            this.fileDescriptors = benchState.readDataset.openFileDescriptors(
                    direct ? LinuxOpenOptions.READ_DIRECT : LinuxOpenOptions.READ);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUringBlocking: " + regRet);
            }

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
            DatasetManager.closeFileDescriptors(fileDescriptors);
            if (ring != null) {
                ring.close();
            }
        }
    }

    @State(Scope.Thread)
    public static class FileChannelVirtualThreadState {

        @Param({"50", "100", "250", "500", "1000"})
        public int virtualThreads = 100;

        public int threadOffset;
        public int[] taskFileIndices;
        public long[] taskOffsets;
        public FileChannel[] channels;
        public ByteBuffer[] alignedBuffers;
        public ExecutorService executor;

        @Setup(Level.Trial)
        public void setup(BenchmarkState benchState) {
            DatasetManager.TaskPool pool = benchState.readDataset.generateThreadTasks(
                    0, 1, SCALING_TASK_POOL_SIZE);
            this.taskFileIndices = pool.fileIndices();
            this.taskOffsets = pool.offsets();
            this.threadOffset = 0;

            boolean direct = !"BUFFERED_COLD".equalsIgnoreCase(benchState.ioMode);
            this.channels = benchState.readDataset.openFileChannels(direct);
            this.executor = Executors.newVirtualThreadPerTaskExecutor();

            this.alignedBuffers = new ByteBuffer[virtualThreads];
            for (int i = 0; i < virtualThreads; i++) {
                alignedBuffers[i] = IoHelper.allocateAligned(DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (executor != null) {
                executor.close();
            }
            DatasetManager.closeChannels(channels);
        }
    }

    @Benchmark
    public void juringVirtualThreadRead(JuringVirtualThreadState state, BenchmarkState benchState, StorageAuxCounters counters, DeviceCounters deviceCounters, Blackhole blackhole) throws Exception {
        final long sectorsStart = IoHelper.readDeviceSectors();
        final JUringBlocking ring = state.ring;
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = OPERATIONS_PER_INVOCATION;
        final int taskMask = taskFileIndices.length - 1;
        final int localOffset = state.threadOffset;
        state.threadOffset = (localOffset + totalTasks) & taskMask;

        final int vtCount = state.virtualThreads;
        final MemorySegment[] segments = state.segments;
        final ExecutorService executor = state.executor;
        final CountDownLatch latch = new CountDownLatch(vtCount);
        final AtomicReference<Throwable> failureRef = new AtomicReference<>();
        final LongAdder bytesAdder = new LongAdder();
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
                    long vtBytes = 0;
                    for (int i = 0; i < count; i++) {
                        int idx = (localOffset + start + i) & taskMask;
                        int fileIdx = taskFileIndices[idx];
                        long fileOffset = taskOffsets[idx];

                        long read = ring.read(fileIdx, buf, fileOffset);
                        if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
                            throw new IllegalStateException("JUringBlocking VT read returned unexpected bytes: " + read);
                        }
                        vtBytes += read;
                    }
                    bytesAdder.add(vtBytes);
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

        long totalBytes = bytesAdder.sum();
        if (blackhole != null) {
            blackhole.consume(totalBytes);
        }
        counters.record(totalBytes);
        final long sectorsEnd = IoHelper.readDeviceSectors();
        if (deviceCounters != null) {
            deviceCounters.deviceReadBytes += Math.max(0, (sectorsEnd - sectorsStart) * 512);
        }
    }

    @Benchmark
    public void fileChannelVirtualThreadRead(FileChannelVirtualThreadState state, BenchmarkState benchState, StorageAuxCounters counters, DeviceCounters deviceCounters, Blackhole blackhole) throws Exception {
        final long sectorsStart = IoHelper.readDeviceSectors();
        final FileChannel[] channels = state.channels;
        final int[] taskFileIndices = state.taskFileIndices;
        final long[] taskOffsets = state.taskOffsets;
        final int totalTasks = OPERATIONS_PER_INVOCATION;
        final int taskMask = taskFileIndices.length - 1;
        final int localOffset = state.threadOffset;
        state.threadOffset = (localOffset + totalTasks) & taskMask;

        final int vtCount = state.virtualThreads;
        final ByteBuffer[] byteBuffers = state.alignedBuffers;
        final ExecutorService executor = state.executor;
        final CountDownLatch latch = new CountDownLatch(vtCount);
        final AtomicReference<Throwable> failureRef = new AtomicReference<>();
        final LongAdder bytesAdder = new LongAdder();
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
                    long vtBytes = 0;
                    for (int i = 0; i < count; i++) {
                        int idx = (localOffset + start + i) & taskMask;
                        int fileIdx = taskFileIndices[idx];
                        long fileOffset = taskOffsets[idx];

                        buf.position(0);
                        int read = channels[fileIdx].read(buf, fileOffset);
                        if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
                            throw new IllegalStateException("FileChannel VT read returned unexpected bytes: " + read);
                        }
                        vtBytes += read;
                    }
                    bytesAdder.add(vtBytes);
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

        long totalBytes = bytesAdder.sum();
        if (blackhole != null) {
            blackhole.consume(totalBytes);
        }
        counters.record(totalBytes);
        final long sectorsEnd = IoHelper.readDeviceSectors();
        if (deviceCounters != null) {
            deviceCounters.deviceReadBytes += Math.max(0, (sectorsEnd - sectorsStart) * 512);
        }
    }
}
