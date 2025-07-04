package bench.random;

import bench.random.read.RandomReadBenchMark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class BenchRunner {


    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RandomReadBenchMark.class.getSimpleName())
                .forks(1)
                .shouldDoGC(false)
                .build();

        new Runner(opt).run();
    }
}
