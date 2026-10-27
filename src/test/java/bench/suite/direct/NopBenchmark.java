package bench.suite.direct;

import bench.suite.common.CpuAuxCounters;
import bench.suite.common.DatasetManager;
import bench.suite.common.StorageAuxCounters;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.ResultConsumer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

/**
 * Pure {@code IORING_OP_NOP} throughput and overhead ceiling benchmark.
 * <p>
 * Exercises the exact same SQE preparation, batch submission, and CQE reaping pipeline
 * as {@link DirectReadBenchmark} without touching any filesystem or block device.
 * Isolates pure JUring + kernel {@code io_uring} framework cost per operation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DatasetManager.DEFAULT_TASK_COUNT)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED",
        "-Djuring.countEnters=true"
})
@Threads(1)
public class NopBenchmark {

    @State(Scope.Thread)
    public static class ThreadState {

        @Param({"64"})
        public int qd;

        public JUring ring;
        public int reapedCount;
        public ResultConsumer consumer;

        @Setup(Level.Trial)
        public void setup() {
            int ringEntries = Math.max(256, qd * 2);
            ring = new JUring(ringEntries,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            this.reapedCount = 0;
            this.consumer = result -> {
                if (result.result() != 0) {
                    throw new IllegalStateException("NOP failed with result: " + result.result());
                }
                this.reapedCount++;
            };
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (ring != null) {
                ring.close();
            }
        }
    }

    @Benchmark
    public void juringNop(ThreadState state, StorageAuxCounters counters, CpuAuxCounters cpu, Blackhole blackhole) {
        cpu.begin();
        final JUring ring = state.ring;
        ring.takeEnterCount();
        final int totalTasks = DatasetManager.DEFAULT_TASK_COUNT;
        final int qd = state.qd;
        final int submitBatch = (qd <= 1) ? 1 : Math.min(qd, 32);

        state.reapedCount = 0;
        int submitted = 0;
        int submitsIssued = 0;
        int waitsIssued = 0;
        int unsubmitted = 0;

        while (state.reapedCount < totalTasks) {
            while ((submitted - state.reapedCount) < qd && submitted < totalTasks) {
                ring.prepareNop();
                submitted++;
                unsubmitted++;

                if (unsubmitted >= submitBatch) {
                    ring.submit();
                    submitsIssued++;
                    unsubmitted = 0;
                    ring.peekForBatchResult(qd, state.consumer);
                }
            }

            int reaped = ring.peekForBatchResult(qd, state.consumer);
            if (reaped == 0 && state.reapedCount < totalTasks) {
                ring.submitAndWaitForBatchResult(1, qd, state.consumer);
                waitsIssued++;
                unsubmitted = 0;
            }
        }

        int countedEnters = ring.takeEnterCount();
        int totalEnters = countedEnters > 0 ? countedEnters : (submitsIssued + waitsIssued);
        counters.record(totalEnters, 0L);
        cpu.end();
        blackhole.consume(state.reapedCount);
    }
}
