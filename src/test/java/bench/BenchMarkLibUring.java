package bench;

import com.davidvlijmincx.lio.api.AsyncReadResult;
import com.davidvlijmincx.lio.api.Result;
import com.davidvlijmincx.lio.api.BlockingReadResult;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.openjdk.jmh.annotations.Threads.MAX;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2300)
@Threads(MAX)
public class BenchMarkLibUring {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BenchMarkLibUring.class.getSimpleName())
                .forks(1)
                .shouldDoGC(false)
                .build();

        new Runner(opt).run();
    }



    @Benchmark()
    public void libUringVirtual(Blackhole blackhole, ExecutionPlanVirtual plan) {

        final var q = plan.q;
        final var paths = BenchmarkFiles.filesTooRead;

        try(ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < paths.length; i++) {
                BlockingReadResult r = q.prepareRead(paths[i].sPath(), paths[i].bufferSize(), paths[i].offset());
                q.submit();
                executor.execute(() -> {
                    blackhole.consume(r.getBuffer());
                    q.freeReadBuffer(r.getBuffer());
                });
            }
        }
    }

//    @Benchmark()
    public void libUring(Blackhole blackhole, ExecutionPlanJUring plan) {


        final var q = plan.q;
        final var paths = BenchmarkFiles.filesTooRead;

        try {
            for (int i = 0; i < paths.length; i++) {
                q.prepareRead(paths[i].sPath(), paths[i].bufferSize(), paths[i].offset());

                if (i % 100 == 0) {
                    q.submit();
                }

            }

            q.submit();

            for (int i = 0; i < paths.length; i++) {
                Result result = q.waitForResult();

                if (result instanceof AsyncReadResult r) {
                    blackhole.consume(r.getBuffer());
                    r.freeBuffer();
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}

