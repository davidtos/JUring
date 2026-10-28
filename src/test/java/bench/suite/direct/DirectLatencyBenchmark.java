package bench.suite.direct;

import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import com.davidvlijmincx.lio.api.Result;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * NVMe Direct I/O (O_DIRECT) Unbatched Tail Latency Benchmark.
 *
 * Runs in JMH Mode.SampleTime with OperationsPerInvocation(1) to isolate
 * per-request completion percentiles (P50, P90, P99, P99.9, Max) and eliminate
 * batch-smoothing distortion.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@OperationsPerInvocation(1)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED"
})
@Threads(1)
public class DirectLatencyBenchmark {

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
        public ByteBuffer alignedBuffer;
        public Result reusableResult;

        @Setup(Level.Iteration)
        public void setup(BenchmarkState benchState) {
            ring = new JUring(64,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = benchState.dataset.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUring: " + regRet);
            }

            ring.registerBuffers(DatasetManager.DEFAULT_BUFFER_SIZE, 8);

            channels = benchState.dataset.openFileChannels(true);
            alignedBuffer = IoHelper.allocateAligned(DatasetManager.DEFAULT_BUFFER_SIZE, IoHelper.DEFAULT_SECTOR_SIZE);
            reusableResult = new Result();
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
    public void juringDirectReadLatency(ThreadState state, BenchmarkState benchState, Blackhole blackhole) {
        int idx = benchState.dataset.nextTaskIndex();
        int fileIdx = benchState.dataset.getTaskFileIndices()[idx];
        long offset = benchState.dataset.getTaskOffsets()[idx];

        state.ring.prepareReadFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, 0);
        state.ring.submit();
        Result r = state.ring.waitForResult(state.reusableResult);
        long res = r.result();
        if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
            throw new IllegalStateException("Direct read latency failed: expected "
                    + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + res);
        }
        blackhole.consume(res);
    }

    @Benchmark
    public void juringDirectReadLatencyFused(ThreadState state, BenchmarkState benchState, Blackhole blackhole) {
        int idx = benchState.dataset.nextTaskIndex();
        int fileIdx = benchState.dataset.getTaskFileIndices()[idx];
        long offset = benchState.dataset.getTaskOffsets()[idx];

        state.ring.prepareReadFixed(fileIdx, DatasetManager.DEFAULT_BUFFER_SIZE, offset, 0);
        state.ring.submitAndWait(1);
        Result r = state.ring.waitForResult(state.reusableResult);
        long res = r.result();
        if (res != DatasetManager.DEFAULT_BUFFER_SIZE) {
            throw new IllegalStateException("Direct read latency fused failed: expected "
                    + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + res);
        }
        blackhole.consume(res);
    }

    @Benchmark
    public void fileChannelDirectReadLatency(ThreadState state, BenchmarkState benchState, Blackhole blackhole) throws IOException {
        int idx = benchState.dataset.nextTaskIndex();
        int fileIdx = benchState.dataset.getTaskFileIndices()[idx];
        long offset = benchState.dataset.getTaskOffsets()[idx];

        FileChannel fc = state.channels[fileIdx];
        state.alignedBuffer.clear();
        int read = fc.read(state.alignedBuffer, offset);
        if (read != DatasetManager.DEFAULT_BUFFER_SIZE) {
            throw new IllegalStateException("FileChannel direct read latency failed: expected "
                    + DatasetManager.DEFAULT_BUFFER_SIZE + " bytes, got " + read);
        }
        blackhole.consume(read);
    }
}
