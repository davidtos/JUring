package bench.random;

import bench.random.read.RandomReadBenchMark;
import bench.random.write.RandomWriteBenchmark;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class BenchRunner {


    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RandomReadBenchMark.class.getSimpleName())
                .include(RandomWriteBenchmark.class.getSimpleName())
                .addProfiler(AsyncProfiler.class, "event=cpu;simple=true;output=flamegraph;dir=./profiler-results")
                .forks(1)
                .build();

        new Runner(opt).run();
    }
}
