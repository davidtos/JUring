package bench.random.read;

import com.davidvlijmincx.lio.api.ReadResult;
import com.davidvlijmincx.lio.api.Result;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2211)
@Fork(value = 1, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED",
})
@Threads(25)
public class ReadBufferPoolBenchmark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ReadBufferPoolBenchmark.class.getSimpleName())
                .forks(1)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void registeredFilesWithBufferPool(Blackhole blackhole, ExecutionPlanReadBufferPool plan, TaskCreator taskCreator) {
        final var jUring = plan.jUring;
        final var readTasks = taskCreator.readTasks;

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 256;

        while (processed < readTasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < readTasks.length) {
                Task task = readTasks[taskIndex];
                int fileIndex = plan.taskFileIndices[taskIndex];
                jUring.prepareReadPooled(fileIndex, task.bufferSize(), task.offset());
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
                if (result instanceof ReadResult r) {
                    blackhole.consume(r.buffer());
                    jUring.checkInReadBuffer(r.buffer().address());
                }
            }
            processed += results.size();
        }
    }
}
