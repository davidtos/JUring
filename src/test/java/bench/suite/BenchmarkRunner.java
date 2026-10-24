package bench.suite;

import bench.suite.buffered.BufferedReadBenchmark;
import bench.suite.buffered.BufferedWriteBenchmark;
import bench.suite.common.PreflightCheck;
import bench.suite.direct.DirectLatencyBenchmark;
import bench.suite.direct.DirectReadBenchmark;
import bench.suite.direct.DirectWriteBenchmark;
import bench.suite.direct.MultiThreadScalingBenchmark;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.*;

/**
 * Universal CLI Benchmark Runner wrapping JMH OptionsBuilder with preflight verification,
 * quick/full execution presets, category filters, multi-thread scaling sweeps, and profiler attachments.
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
        boolean threadsScaling = false;
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

        boolean attachPerfnorm = false;
        boolean decision = false;
        List<String> customIncludes = new ArrayList<>();
        Map<String, List<String>> customParams = new LinkedHashMap<>();
        Integer customForks = null;
        Integer customWi = null;
        Integer customI = null;
        Integer customR = null;
        Integer customW = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();
            switch (arg) {
                case "--quick" -> quick = true;
                case "--full" -> full = true;
                case "--decision" -> decision = true;
                case "--direct" -> directOnly = true;
                case "--buffered" -> bufferedOnly = true;
                case "--latency" -> latencyOnly = true;
                case "--threads-scaling" -> threadsScaling = true;
                case "--gc" -> attachGc = true;
                case "--async" -> attachAsync = true;
                case "--perfnorm" -> attachPerfnorm = true;
                case "--include" -> {
                    if (i + 1 < args.length) {
                        customIncludes.add(args[++i].trim());
                    }
                }
                case "--param" -> {
                    if (i + 1 < args.length) {
                        String[] parts = args[++i].trim().split("=", 2);
                        if (parts.length == 2) {
                            customParams.computeIfAbsent(parts[0].trim(), k -> new ArrayList<>()).add(parts[1].trim());
                        }
                    }
                }
                case "--f" -> {
                    if (i + 1 < args.length) customForks = Integer.parseInt(args[++i]);
                }
                case "--wi" -> {
                    if (i + 1 < args.length) customWi = Integer.parseInt(args[++i]);
                }
                case "--i" -> {
                    if (i + 1 < args.length) customI = Integer.parseInt(args[++i]);
                }
                case "--r" -> {
                    if (i + 1 < args.length) customR = Integer.parseInt(args[++i]);
                }
                case "--w" -> {
                    if (i + 1 < args.length) customW = Integer.parseInt(args[++i]);
                }
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

        if (threadsScaling) {
            runScalingSuite(quick, full, decision, attachGc, attachAsync, customThreads, customIncludes, customParams);
            return;
        }

        OptionsBuilder builder = new OptionsBuilder();
        builder.jvmArgsPrepend("--enable-native-access=ALL-UNNAMED", "-Djuring.countEnters=true");

        // Execution mode
        if (customWi != null || customI != null || customForks != null || customR != null || customW != null) {
            int forks = customForks != null ? customForks : 1;
            int wi = customWi != null ? customWi : 3;
            int it = customI != null ? customI : 3;
            int rSec = customR != null ? customR : 3;
            int wSec = customW != null ? customW : 3;
            System.out.printf("Configuring CUSTOM benchmark (-f %d -wi %d -i %d -r %ds -w %ds)...%n", forks, wi, it, rSec, wSec);
            builder.warmupIterations(wi)
                    .warmupTime(TimeValue.seconds(wSec))
                    .measurementIterations(it)
                    .measurementTime(TimeValue.seconds(rSec))
                    .forks(forks);
        } else if (decision) {
            System.out.println("Configuring DECISION benchmark (-f 3 -wi 3 -i 5 -w 3s -r 5s)...");
            builder.warmupIterations(3)
                    .warmupTime(TimeValue.seconds(3))
                    .measurementIterations(5)
                    .measurementTime(TimeValue.seconds(5))
                    .forks(3);
        } else if (quick) {
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
        if (!customIncludes.isEmpty()) {
            for (String inc : customIncludes) {
                builder.include(inc);
            }
        } else {
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
        }

        for (Map.Entry<String, List<String>> entry : customParams.entrySet()) {
            builder.param(entry.getKey(), entry.getValue().toArray(new String[0]));
        }

        // Profilers
        if (attachGc) {
            System.out.println("Attaching JMH GCProfiler (-prof gc) for zero-allocation verification...");
            builder.addProfiler(GCProfiler.class);
        }

        if (attachPerfnorm) {
            verifyRealPerfBinary();
            System.out.println("Attaching JMH LinuxPerfNormProfiler (-prof perfnorm)...");
            builder.addProfiler("perfnorm");
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
        Collection<RunResult> results = new Runner(opt).run();
        printDerivedMetricsTable(results);
    }

    private static void printDerivedMetricsTable(Collection<RunResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        System.out.println("\n==========================================================================================================");
        System.out.println("                              DERIVED PER-OP METRICS (USER + KERNEL + SYSCALLS)                           ");
        System.out.println("==========================================================================================================");
        System.out.printf("%-42s | %18s | %11s | %11s | %10s%n",
                "Benchmark (params)", "Throughput (ops/s)", "user ns/op", "sys ns/op", "enters/op");
        System.out.println("-".repeat(106));
        for (RunResult rr : results) {
            String fullName = rr.getParams().getBenchmark();
            String shortName = fullName.substring(fullName.lastIndexOf('.', fullName.lastIndexOf('.') - 1) + 1);
            StringBuilder paramStr = new StringBuilder();
            for (String k : rr.getParams().getParamsKeys()) {
                if (!paramStr.isEmpty()) paramStr.append(",");
                paramStr.append(k).append("=").append(rr.getParams().getParam(k));
            }
            String label = paramStr.isEmpty() ? shortName : shortName + " (" + paramStr + ")";
            double score = rr.getPrimaryResult().getScore();
            double err = rr.getPrimaryResult().getScoreError();
            var sec = rr.getSecondaryResults();
            double userNsRate = sec.containsKey("userNs") ? sec.get("userNs").getScore() : Double.NaN;
            double sysNsRate = sec.containsKey("sysNs") ? sec.get("sysNs").getScore() : Double.NaN;
            double syscallsRate = sec.containsKey("syscalls") ? sec.get("syscalls").getScore() : Double.NaN;
            double bytesRate = sec.containsKey("bytesProcessed") ? sec.get("bytesProcessed").getScore() : Double.NaN;
            double denomOps = (!Double.isNaN(bytesRate) && bytesRate > 0) ? (bytesRate / 4096.0) : score;
            double userNsPerOp = (!Double.isNaN(userNsRate) && denomOps > 0) ? userNsRate / denomOps : Double.NaN;
            double sysNsPerOp = (!Double.isNaN(sysNsRate) && denomOps > 0) ? sysNsRate / denomOps : Double.NaN;
            double entersPerOp = (!Double.isNaN(syscallsRate) && denomOps > 0) ? syscallsRate / denomOps : Double.NaN;
            String scoreCell = Double.isNaN(err) || Double.isInfinite(err)
                    ? String.format("%,18.0f", score)
                    : String.format("%,10.0f ± %,6.0f", score, err);
            System.out.printf("%-42s | %18s | %11.1f | %11.1f | %10.4f%n",
                    label, scoreCell, userNsPerOp, sysNsPerOp, entersPerOp);
        }
        System.out.println("==========================================================================================================\n");
    }

    private static void runScalingSuite(boolean quick, boolean full, boolean decision, boolean attachGc, boolean attachAsync,
                                        Integer customThreads, List<String> customIncludes, Map<String, List<String>> customParams) throws RunnerException {
        System.out.println("================================================================================");
        System.out.println("          Launching Multi-Thread Scaling Suite (1, 2, 4, 8, 16 Threads)         ");
        System.out.println("================================================================================");

        int[] threadLevels = (customThreads != null && customThreads > 0)
                ? new int[]{customThreads}
                : new int[]{1, 2, 4, 8, 16};

        Map<String, Map<Integer, Double>> matrix = new LinkedHashMap<>();

        for (int t : threadLevels) {
            System.out.println("\n>>> Running MultiThreadScalingBenchmark at Concurrency: " + t + " Thread(s) <<<");

            OptionsBuilder builder = new OptionsBuilder();
            builder.jvmArgsPrepend("--enable-native-access=ALL-UNNAMED", "-Djuring.countEnters=true");
            if (customIncludes != null && !customIncludes.isEmpty()) {
                for (String inc : customIncludes) {
                    builder.include(inc);
                }
            } else {
                builder.include(MultiThreadScalingBenchmark.class.getSimpleName());
            }
            if (customParams != null) {
                for (Map.Entry<String, List<String>> entry : customParams.entrySet()) {
                    builder.param(entry.getKey(), entry.getValue().toArray(new String[0]));
                }
            }
            builder.threads(t);

            if (decision) {
                builder.warmupIterations(3)
                        .warmupTime(TimeValue.seconds(3))
                        .measurementIterations(5)
                        .measurementTime(TimeValue.seconds(5))
                        .forks(3);
            } else if (quick) {
                builder.warmupIterations(1)
                        .warmupTime(TimeValue.seconds(2))
                        .measurementIterations(1)
                        .measurementTime(TimeValue.seconds(3))
                        .forks(1);
            } else if (full) {
                builder.warmupIterations(3)
                        .warmupTime(TimeValue.seconds(5))
                        .measurementIterations(5)
                        .measurementTime(TimeValue.seconds(5))
                        .forks(1);
            } else {
                builder.warmupIterations(2)
                        .warmupTime(TimeValue.seconds(3))
                        .measurementIterations(3)
                        .measurementTime(TimeValue.seconds(3))
                        .forks(1);
            }

            if (attachGc) {
                builder.addProfiler(GCProfiler.class);
            }
            if (attachAsync) {
                builder.addProfiler(AsyncProfiler.class, "event=cpu;simple=true;output=flamegraph;dir=./profiler-results-threads-" + t);
            }

            Collection<RunResult> results = new Runner(builder.build()).run();
            printDerivedMetricsTable(results);
            for (RunResult rr : results) {
                String fullName = rr.getParams().getBenchmark();
                String shortName = fullName.substring(fullName.lastIndexOf('.') + 1);
                double score = rr.getPrimaryResult().getScore();
                matrix.computeIfAbsent(shortName, k -> new TreeMap<>()).put(t, score);
            }
        }

        printScalingMatrix(matrix, threadLevels);
    }

    private static void printScalingMatrix(Map<String, Map<Integer, Double>> matrix, int[] threadLevels) {
        System.out.println("\n====================================================================================================");
        System.out.println("                        MULTI-THREAD SCALING BENCHMARK COMPARISON MATRIX                            ");
        System.out.println("====================================================================================================");

        StringBuilder header = new StringBuilder(String.format("%-26s", "Benchmark Engine"));
        for (int t : threadLevels) {
            header.append(String.format(" | %2d Thread%-2s          ", t, (t == 1 ? "" : "s")));
        }
        System.out.println(header);
        System.out.println("-".repeat(header.length()));

        for (Map.Entry<String, Map<Integer, Double>> entry : matrix.entrySet()) {
            StringBuilder row = new StringBuilder(String.format("%-26s", entry.getKey()));
            for (int t : threadLevels) {
                Double score = entry.getValue().get(t);
                if (score != null) {
                    double mbSec = (score * 4096.0) / (1024.0 * 1024.0);
                    row.append(String.format(" | %,10.0f op/s (%4.0f MB/s)", score, mbSec));
                } else {
                    row.append(" |            N/A              ");
                }
            }
            System.out.println(row);
        }
        System.out.println("====================================================================================================");

        // Print Key Architectural Findings if full scaling sweep was performed
        if (threadLevels.length >= 4) {
            Map<Integer, Double> fcRead = matrix.get("fileChannelDirectRead");
            Map<Integer, Double> juRead = matrix.get("juringDirectRead");
            Map<Integer, Double> fcWrite = matrix.get("fileChannelDirectWrite");
            Map<Integer, Double> juWrite = matrix.get("juringDirectWrite");

            System.out.println("\nKey Concurrency & Saturation Insights:");
            if (juRead != null && fcRead != null) {
                Double juRead1 = juRead.get(1);
                Double fcRead16 = fcRead.get(16);
                if (juRead1 != null && fcRead16 != null && fcRead16 > 0) {
                    System.out.printf("  * Read Concurrency: 1-Thread JUring (%,.0f ops/s) vs 16-Thread FileChannel (%,.0f ops/s) -> %.2fx faster!%n",
                            juRead1, fcRead16, juRead1 / fcRead16);
                }
                Double juRead16 = juRead.get(16);
                if (juRead16 != null) {
                    System.out.printf("  * Peak NVMe Flash Read Saturation: %,.0f ops/s (%,.1f MB/s) at 16 threads.%n",
                            juRead16, (juRead16 * 4096.0) / (1024.0 * 1024.0));
                }
            }

            if (juWrite != null && fcWrite != null) {
                Double juWrite1 = juWrite.get(1);
                Double fcWrite16 = fcWrite.get(16);
                if (juWrite1 != null && fcWrite16 != null && fcWrite16 > 0) {
                    System.out.printf("  * Write Concurrency: 1-Thread JUring (%,.0f ops/s) vs 16-Thread FileChannel (%,.0f ops/s) -> %.2fx faster!%n",
                            juWrite1, fcWrite16, juWrite1 / fcWrite16);
                }
                Double juWrite16 = juWrite.get(16);
                if (juWrite16 != null) {
                    System.out.printf("  * Peak NVMe Flash Write Saturation: %,.0f ops/s (%,.1f MB/s) at 16 threads.%n",
                            juWrite16, (juWrite16 * 4096.0) / (1024.0 * 1024.0));
                }
            }
            System.out.println("====================================================================================================\n");
        }
    }

    /**
     * Guards against a non-genuine {@code perf} executable being picked up by JMH's perfnorm profiler.
     * <p>
     * JMH 1.37 runs the binary named by the {@code JMH_PERF} environment variable, or {@code perf}
     * from {@code PATH}. A script wrapper (for example a user-space-only shim) silently produces
     * counters that exclude kernel time, which makes per-op numbers for io_uring meaningless because
     * most of the cost is in the kernel. This check requires the resolved binary to be an ELF executable.
     */
    private static void verifyRealPerfBinary() {
        String configured = System.getenv("JMH_PERF");
        String name = (configured != null && !configured.isBlank()) ? configured : "perf";
        java.nio.file.Path resolved = null;
        if (name.contains("/")) {
            resolved = java.nio.file.Path.of(name);
        } else {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    java.nio.file.Path candidate = java.nio.file.Path.of(dir, name);
                    if (java.nio.file.Files.isExecutable(candidate)) {
                        resolved = candidate;
                        break;
                    }
                }
            }
        }
        if (resolved == null) {
            throw new IllegalStateException("perfnorm requested but no '" + name + "' executable was found. "
                    + "Install perf (dnf install perf) or set JMH_PERF=/usr/bin/perf.");
        }
        byte[] magic = new byte[4];
        try (var in = java.nio.file.Files.newInputStream(resolved)) {
            if (in.readNBytes(magic, 0, 4) != 4) {
                throw new IllegalStateException("Unable to read perf binary header: " + resolved);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to read perf binary: " + resolved, e);
        }
        boolean elf = magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        if (!elf) {
            throw new IllegalStateException("perfnorm would use '" + resolved + "' (real path: "
                    + safeRealPath(resolved) + "), which is not a genuine perf ELF binary (likely a script shim "
                    + "that excludes kernel time). Re-run with JMH_PERF=/usr/bin/perf, or use ./bench.sh.");
        }
        System.out.println("perfnorm will use perf binary: " + safeRealPath(resolved));
    }

    private static String safeRealPath(java.nio.file.Path p) {
        try {
            return p.toRealPath().toString();
        } catch (java.io.IOException e) {
            return p.toString();
        }
    }

    private static void printHelp() {
        System.out.println("JUring Benchmark Suite CLI Options:");
        System.out.println("  --quick             Run fast smoke test (1 warmup 2s, 1 measurement 3s, 1 fork)");
        System.out.println("  --full              Run full publication benchmark (3 warmups 5s, 5 measurements 5s)");
        System.out.println("  --direct            Run only NVMe Direct I/O (O_DIRECT) benchmarks");
        System.out.println("  --buffered          Run only Buffered / Page Cache benchmarks");
        System.out.println("  --latency           Run unbatched tail latency distribution benchmark (Mode.SampleTime)");
        System.out.println("  --threads-scaling   Run multi-thread saturation suite (1, 4, 8, 16 threads)");
        System.out.println("  --gc                Attach GCProfiler to measure bytes allocated / op");
        System.out.println("  --async             Attach AsyncProfiler for CPU profiling flame graphs");
        System.out.println("  --threads <N>       Set thread concurrency (1, 4, 8, 16)");
        System.out.println("  --help              Display this help message");
    }
}
