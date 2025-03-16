package bench;

import com.davidvlijmincx.lio.api.BlockingReadResult;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.Flag;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.openjdk.jmh.annotations.Threads.MAX;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(MAX)
public class SequentialReadBenchmark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SequentialReadBenchmark.class.getSimpleName())
                .forks(1)
                .shouldDoGC(false)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void libUringBlocking(Blackhole blackhole, ExecutionPlanBlocking plan, SequentialReadTaskCreator taskCreator) {
        final var jUringBlocking = plan.jUringBlocking;
        final int bufferSize = taskCreator.getReadSize();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (var task : taskCreator.getSequentialReadTasks()) {
                FileDescriptor fd = new FileDescriptor(task.sPath(), Flag.READ, 0);
                long fileSize = task.fileSize();
                long position = 0;

                // Read file sequentially in chunks of bufferSize
                List<BlockingReadResult> results = new ArrayList<>();
                while (position < fileSize) {
                    long currentPosition = position;
                    int readSize = (int) Math.min(bufferSize, fileSize - position);

                    results.add(jUringBlocking.prepareRead(fd, readSize, currentPosition));
                    jUringBlocking.submit();

                    position += readSize;
                }

                executor.execute(() -> {
                    results.forEach(r -> {
                        blackhole.consume(r.getBuffer());
                        r.freeBuffer();
                    });

                    fd.close();
                });

            }
        }
    }

}