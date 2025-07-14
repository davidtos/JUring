package bench.random.write;

import bench.ExecutionPlanBlocking;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2211)
@Fork(value = 3, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(20)
public class RandomWriteBenchmark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RandomWriteBenchmark.class.getSimpleName())
                .forks(1)
                // .addProfiler(AsyncProfiler.class, "event=cpu;simple=true;output=flamegraph;dir=./profiler-results")
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void juringBlockingWithVirtualThreads(Blackhole blackhole, ExecutionPlanBlocking plan){

    }

    @Benchmark
    public void registeredFiles(Blackhole blackhole){

    }

    @Benchmark
    public void preOpenedFileChannels(Blackhole blackhole){

    }

    @Benchmark
    public void juringOpenWriteClose(Blackhole blackhole){

    }

    @Benchmark
    public void fileChannelOpenWriteClose(Blackhole blackhole) {

    }

    @Benchmark
    public void fileChannelOpenWriteCloseOnVirtualThreads(Blackhole blackhole) {

    }

}
