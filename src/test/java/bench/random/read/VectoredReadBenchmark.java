package bench.random.read;

import com.davidvlijmincx.lio.api.ReadvResult;
import com.davidvlijmincx.lio.api.Result;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2211)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(25)
public class VectoredReadBenchmark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(VectoredReadBenchmark.class.getSimpleName())
                .forks(1)
                .build();
        new Runner(opt).run();
    }

    @Benchmark
    public void juringReadv(Blackhole bh, ExecutionPlanPreOpenedJUringReadv plan, VectoredTaskCreator taskCreator) {
        final var jUring = plan.jUring;
        final Task[] tasks = taskCreator.readTasks;

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 256;

        while (processed < tasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < tasks.length) {
                Task task = tasks[taskIndex];
                jUring.prepareReadv(plan.taskFds[taskIndex], VectoredTaskCreator.VECTOR_SIZES, task.offset());
                submitted++;
                taskIndex++;
                if (submitted % 64 == 0) {
                    jUring.submit();
                }
            }

            if (submitted > processed) {
                jUring.submit();
            }

            List<Result> results = jUring.peekForBatchResult(64);
            for (Result result : results) {
                if (result instanceof ReadvResult r) {
                    bh.consume(r.buffers());
                 //   System.out.println(r.result() + new String(r.buffers()[0].asSlice(0,100).getString(0)));
                    r.freeBuffers();
                }
            }
            processed += results.size();
        }
    }

  //  @Benchmark
    public void fileChannelScatter(Blackhole bh, ExecutionPlanPreOpenedFCVectored plan, VectoredTaskCreator taskCreator) throws IOException {
        for (Task task : taskCreator.readTasks) {
            ByteBuffer[] bufs = {
                ByteBuffer.allocate(VectoredTaskCreator.VECTOR_SIZE),
                ByteBuffer.allocate(VectoredTaskCreator.VECTOR_SIZE),
                ByteBuffer.allocate(VectoredTaskCreator.VECTOR_SIZE),
                ByteBuffer.allocate(VectoredTaskCreator.VECTOR_SIZE)
            };
            FileChannel fc = plan.openFileChannels.get(task.pathAsString());
            fc.position(task.offset());
            fc.read(bufs);
            for (ByteBuffer buf : bufs) {
                buf.flip();
                bh.consume(buf);
            }
        }
    }

   // @Benchmark
    public void fileChannelSingle16KB(Blackhole bh, ExecutionPlanPreOpenedFCVectored plan, VectoredTaskCreator taskCreator) throws IOException {
        for (Task task : taskCreator.readTasks) {
            ByteBuffer data = ByteBuffer.allocate(VectoredTaskCreator.TOTAL_SIZE);
            FileChannel fc = plan.openFileChannels.get(task.pathAsString());
            fc.read(data, task.offset());
            data.flip();
            bh.consume(data);
        }
    }
}
