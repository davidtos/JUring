package bench.suite;

import bench.suite.buffered.BufferedReadBenchmark;
import bench.suite.buffered.BufferedWriteBenchmark;
import bench.suite.common.PreflightCheck;
import bench.suite.direct.DirectLatencyBenchmark;
import bench.suite.direct.DirectReadBenchmark;
import bench.suite.direct.DirectWriteBenchmark;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Universal CLI Benchmark Runner wrapping JMH OptionsBuilder with preflight verification,
 * quick/full execution presets, category filters, and profiler attachments.
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        System.out.println("Starting JUring Benchmark Suite...");

        // Run preflight checks
        PreflightCheck.Report preflightReport = PreflightCheck.runChecks();
        preflightReport.printReport();

        if (!preflightReport.isSuccess()) {
            System.err.println("Aborting benchmark execution due to preflight check failures.");
            System.exit(1);
        }

        boolean quick = false;
        boolean full = false;
        boolean directOnly = false;
        boolean bufferedOnly = false;
        boolean latencyOnly = false;
        boolean attachGc = false;
        boolean attachAsync = false;
        Integer customThreads = null;

        // Ensure java.class.path contains full test classpath when invoked via Maven exec:java
        String currentCp = System.getProperty("java.class.path", "");
        if (!currentCp.contains("jmh-core")) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl instanceof java.net.URLClassLoader ucl) {
                StringBuilder sb = new StringBuilder();
                for (java.net.URL url : ucl.getURLs()) {
                    try {
                        if (!sb.isEmpty()) sb.append(java.io.File.pathSeparator);
                        sb.append(java.nio.file.Path.of(url.toURI()).toAbsolutePath());
                    } catch (Exception ignored) {}
                }
                if (!sb.isEmpty()) {
                    System.setProperty("java.class.path", sb.toString());
                }
            }
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();
            switch (arg) {
                case "--quick" -> quick = true;
                case "--full" -> full = true;
                case "--direct" -> directOnly = true;
                case "--buffered" -> bufferedOnly = true;
                case "--latency" -> latencyOnly = true;
                case "--gc" -> attachGc = true;
                case "--async" -> attachAsync = true;
                case "--threads" -> {
                    if (i + 1 < args.length) {
                        customThreads = Integer.parseInt(args[++i]);
                    }
                }
                case "--help" -> {
                    printHelp();
                    return;
                }
                default -> System.out.println("Unknown argument: " + arg + " (ignored)");
            }
        }

        OptionsBuilder builder = new OptionsBuilder();
        builder.jvmArgsPrepend("--enable-native-access=ALL-UNNAMED");

        // Execution mode
        if (quick) {
            System.out.println("Configuring QUICK smoke test (1 warmup of 2s, 1 measurement of 3s)...");
            builder.warmupIterations(1)
                    .warmupTime(TimeValue.seconds(2))
                    .measurementIterations(1)
                    .measurementTime(TimeValue.seconds(3))
                    .forks(1);
        } else if (full) {
            System.out.println("Configuring FULL publication benchmark (3 warmups of 5s, 5 measurements of 5s)...");
            builder.warmupIterations(3)
                    .warmupTime(TimeValue.seconds(5))
                    .measurementIterations(5)
                    .measurementTime(TimeValue.seconds(5))
                    .forks(1);
        } else {
            // Default balanced mode
            builder.warmupIterations(2)
                    .warmupTime(TimeValue.seconds(3))
                    .measurementIterations(3)
                    .measurementTime(TimeValue.seconds(3))
                    .forks(1);
        }

        // Benchmark selection
        List<String> includes = new ArrayList<>();
        if (directOnly) {
            includes.add(DirectReadBenchmark.class.getSimpleName());
            includes.add(DirectWriteBenchmark.class.getSimpleName());
        } else if (bufferedOnly) {
            includes.add(BufferedReadBenchmark.class.getSimpleName());
            includes.add(BufferedWriteBenchmark.class.getSimpleName());
        } else if (latencyOnly) {
            includes.add(DirectLatencyBenchmark.class.getSimpleName());
        } else {
            includes.add(DirectReadBenchmark.class.getSimpleName());
            includes.add(DirectWriteBenchmark.class.getSimpleName());
            includes.add(BufferedReadBenchmark.class.getSimpleName());
            includes.add(BufferedWriteBenchmark.class.getSimpleName());
        }

        for (String inc : includes) {
            builder.include(inc);
        }

        // Profilers
        if (attachGc) {
            System.out.println("Attaching JMH GCProfiler (-prof gc) for zero-allocation verification...");
            builder.addProfiler(GCProfiler.class);
        }

        if (attachAsync) {
            System.out.println("Attaching JMH AsyncProfiler (-prof async) for CPU flame graphs...");
            builder.addProfiler(AsyncProfiler.class, "event=cpu;simple=true;output=flamegraph;dir=./profiler-results");
        }

        // Concurrency
        if (customThreads != null && customThreads > 0) {
            System.out.println("Setting thread concurrency to: " + customThreads);
            builder.threads(customThreads);
        }

        Options opt = builder.build();
        new Runner(opt).run();
    }

    private static void printHelp() {
        System.out.println("JUring Benchmark Suite CLI Options:");
        System.out.println("  --quick        Run fast smoke test (1 warmup 2s, 1 measurement 3s, 1 fork)");
        System.out.println("  --full         Run full publication benchmark (3 warmups 5s, 5 measurements 5s)");
        System.out.println("  --direct       Run only NVMe Direct I/O (O_DIRECT) benchmarks");
        System.out.println("  --buffered     Run only Buffered / Page Cache benchmarks");
        System.out.println("  --latency      Run unbatched tail latency distribution benchmark (Mode.SampleTime)");
        System.out.println("  --gc           Attach GCProfiler to measure bytes allocated / op");
        System.out.println("  --async        Attach AsyncProfiler for CPU profiling flame graphs");
        System.out.println("  --threads <N>  Set thread concurrency (1, 4, 8, 16)");
        System.out.println("  --help         Display this help message");
    }
}
