/*
 * JUringFileBench - standalone file-I/O benchmark for the JUring io_uring binding.
 *
 * Purpose
 * -------
 * Measure JUring file I/O with workload semantics that mirror seastar's
 * apps/io_tester (same workload types, same offset generation, same pipeline
 * model, per-op latency percentiles), so the two engines can be compared on
 * identical workloads, request sizes and queue depths.
 *
 * This class deliberately has NO external dependencies (no JMH, no YAML, no
 * JSON library). It compiles against target/classes after:
 *
 *   mvn -o -q -DskipTests compile
 *   javac -cp target/classes -d bench/juring/out bench/juring/JUringFileBench.java
 *
 * Run with (FFM restricted-method access):
 *
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp target/classes:bench/juring/out bench.juring.JUringFileBench <flags>
 *
 * The wrapper bench/juring/run-juring.sh does both steps for you.
 *
 * Workload semantics (mirroring seastar apps/io_tester/io_tester.cc):
 *   randread / randwrite : XorShift PRNG offset over [0, size-bs], block aligned
 *                          (fio-style per-thread PRNG, same algorithm as
 *                          bench.suite.common.DatasetManager.FioPrng; io_tester's
 *                          std::uniform_int_distribution is a different PRNG, the
 *                          distribution is the same).
 *   seqread / seqwrite   : start at 0, advance by bs, wrap to 0 after the last
 *                          whole block (io_tester "seqread" / "overwrite").
 *                          With --threads N > 1 the dataset is partitioned into
 *                          N contiguous per-thread regions (mirrors io_tester's
 *                          one-file-per-shard layout).
 *   nop                  : IORING_OP_NOP only, measures submission/completion
 *                          overhead (no file, no buffers).
 *
 * Pipelining: each worker thread owns its own io_uring ring; the pipeline is
 * filled up to --qd outstanding requests, then completions are reaped with
 * submitAndWaitForBatchResult(1, qd, consumer) and the pipeline is topped up.
 * That is exactly the "parallelism" model of io_tester shard_info.parallelism.
 *
 * Two worker runtimes (--runtime, default = threads = historical behaviour):
 *   threads : every worker is a plain platform thread that owns its own JUring
 *             ring and runs the self-driving fill/reap loop described above.
 *             This is exactly the pre-B4 harness; its 1.0 JSON fields are unchanged
 *             (schema 1.1 adds the B5 metrics fields additively; --metrics off omits them).
 *   shard   : the workers are the IoShards of one RingGroup (one ring per
 *             shard, best-effort CPU pinning to CPUs first-cpu..first-cpu+N-1).
 *             Each shard's QD-64 pipeline is driven by ctx.onCompletion
 *             handlers that the shard's batched poll loop dispatches, i.e. the
 *             real B4 runtime path: tasks/handlers run on the shard thread,
 *             the loop batches submits and reaps completions. Per-shard
 *             IOPS/latency plus the IoShard/ShardStats counters are added to
 *             the JSON (extra fields only; the historical 1.0 schema is kept and
 *             schema 1.1 adds the B5 metrics fields).
 *             A shard never opens a second ring and never runs its own
 *             submit/reap loop (that would both bypass the runtime and race
 *             the shard's internal wakeup read).
 *
 * B5 observability (json schema 1.1, docs/b5-design.md §7): --metrics on|off
 * (default on) adds, for BOTH runtimes, a per-worker "ring_metrics" object (every
 * RingMetrics field, snake_case), a per-worker "ring_series" sampled by ONE
 * sampler thread at --metrics-interval (default 1.0 s; 0 disables) only during the
 * measured phase via the cross-thread snapshot API (JUring.ringMetrics() or
 * IoShard.metrics()), and (shard runtime only) per-worker "dispatch_latency_us"
 * from the library LatencySnapshot -- labelled in the reports as the runtime
 * completion latency (registration->dispatch): it includes kernel/device
 * completion, excludes SQE prep + handler body, and is NOT comparable to fio
 * clat or the external latency_us. Shard mode runs with
 * ShardOptions.withLatencySampleEvery(1) (design A2.1: every successful
 * registration; drained R => floor(R/N) samples for N).
 * runs[] gains a "metrics" object; the aggregate gains summed ring_metrics (mean
 * over runs) + ring_queue_depth {mean/max_in_flight, mean_sq_queued,
 * mean_cq_ready} + dispatch_latency_us (mean of per-run percentiles). --metrics
 * off emits no B5 field and disables the shard latency sampling. Every schema 1.0
 * field is unchanged; stdout stays exactly one JSON object.
 *
 * File I/O always uses O_DIRECT and a *registered* file + registered
 * (fixed) buffers, i.e. IORING_OP_READ_FIXED / IORING_OP_WRITE_FIXED with
 * IOSQE_FIXED_FILE, the fastest JUring path.
 *
 * Latency: System.nanoTime() is sampled immediately before each request is
 * prepared and again when its completion is consumed; per-op latency therefore
 * includes SQ preparation, submission and completion handling (io_tester
 * measures from before dma_read() to completion, a comparable window). Each
 * sample is stored in a bounded reservoir (--latency-samples, default
 * 1,000,000 per thread); percentiles are computed by sorting the reservoir.
 * The average is exact (running sum over all recorded ops).
 *
 * Output: exactly ONE JSON object on stdout (diagnostics go to stderr). See
 * bench/README.md for the schema and bench/parse_results.py for the consumer.
 */

package bench.juring;

import com.davidvlijmincx.lio.api.CompletionHandler;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.IoShard;
import com.davidvlijmincx.lio.api.IoUringOptions;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LatencySnapshot;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import com.davidvlijmincx.lio.api.Result;
import com.davidvlijmincx.lio.api.ResultConsumer;
import com.davidvlijmincx.lio.api.RingGroup;
import com.davidvlijmincx.lio.api.RingMetrics;
import com.davidvlijmincx.lio.api.ShardContext;
import com.davidvlijmincx.lio.api.ShardMetrics;
import com.davidvlijmincx.lio.api.ShardOptions;
import com.davidvlijmincx.lio.api.ShardStats;
import com.davidvlijmincx.lio.api.ShardTask;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SQPOLL;

public final class JUringFileBench {

    /** Bumped only on incompatible JSON changes (1.1 = additive B5 metrics fields). */
    static final String SCHEMA_VERSION = "1.1";
    static final String ENGINE = "juring";

    /** io_uring setup flags used for every ring (same as the JUring JMH suite). */
    static final IoUringOptions[] RING_FLAGS = {
            IORING_SETUP_SINGLE_ISSUER,
            IORING_SETUP_COOP_TASKRUN,
            IORING_SETUP_DEFER_TASKRUN
    };
    static final String[] RING_FLAG_NAMES = {
            "IORING_SETUP_SINGLE_ISSUER",
            "IORING_SETUP_COOP_TASKRUN",
            "IORING_SETUP_DEFER_TASKRUN"
    };
    /** SQPOLL submission variant: a kernel thread consumes the SQ ring. */
    static final IoUringOptions[] RING_FLAGS_SQPOLL = {
            IORING_SETUP_SINGLE_ISSUER,
            IORING_SETUP_SQPOLL
    };
    static final String[] RING_FLAG_NAMES_SQPOLL = {
            "IORING_SETUP_SINGLE_ISSUER",
            "IORING_SETUP_SQPOLL"
    };

    static IoUringOptions[] ringFlags(Config cfg) {
        return cfg.sqpoll ? RING_FLAGS_SQPOLL : RING_FLAGS;
    }

    static String[] ringFlagNames(Config cfg) {
        return cfg.sqpoll ? RING_FLAG_NAMES_SQPOLL : RING_FLAG_NAMES;
    }

    public static void main(String[] args) {
        Config cfg = null;
        Manifest manifest = null;
        int exitCode = 0;
        RunStat[] runs = new RunStat[0];
        String error = null;
        Dataset ds = null;

        try {
            cfg = Config.parse(args);
            if (cfg.help) {
                System.out.println(usage());
                return;
            }
            manifest = Manifest.collect(args);
            ds = Dataset.prepare(cfg);
            cfg.fixedBuffers = Buffers.decideFixed(cfg);
            printPreamble(cfg, ds);

            List<RunStat> done = new ArrayList<>();
            for (int r = 0; r < cfg.runs; r++) {
                done.add(cfg.runtime == WorkerRuntime.SHARD ? runOnceShard(cfg, ds, r) : runOnce(cfg, ds, r));
            }
            runs = done.toArray(new RunStat[0]);
        } catch (UsageException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.print(usage());
            System.exit(2);
            return;
        } catch (Throwable t) {
            error = describe(t);
            exitCode = 3;
            System.err.println("error: " + error);
        }

        String status = error == null ? "ok" : "error";
        String json = Report.render(cfg, ds, manifest, runs, error, status);
        System.out.println(json);
        if (cfg != null && cfg.jsonPath != null) {
            try {
                Path p = Path.of(cfg.jsonPath).toAbsolutePath();
                Path parent = p.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(p, json + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("warning: failed to write --json file: " + e.getMessage());
                exitCode = exitCode == 0 ? 4 : exitCode;
            }
        }
        System.exit(exitCode);
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = t.getClass().getName();
        }
        return msg;
    }

    // ------------------------------------------------------------------
    // CLI
    // ------------------------------------------------------------------

    enum Mode {
        RANDREAD("randread", true),
        RANDWRITE("randwrite", false),
        SEQREAD("seqread", true),
        SEQWRITE("seqwrite", false),
        NOP("nop", true);

        final String cliName;
        final boolean read;

        Mode(String cliName, boolean read) {
            this.cliName = cliName;
            this.read = read;
        }

        boolean isWrite() {
            return this == RANDWRITE || this == SEQWRITE;
        }

        boolean isRandom() {
            return this == RANDREAD || this == RANDWRITE;
        }

        boolean isNop() {
            return this == NOP;
        }

        static Mode from(String s) throws UsageException {
            for (Mode m : values()) {
                if (m.cliName.equalsIgnoreCase(s)) {
                    return m;
                }
            }
            throw new UsageException("unknown --workload '" + s + "' (expected randread|randwrite|seqread|seqwrite|nop)");
        }
    }

    /**
     * Worker runtime: plain platform threads (historical behaviour) or the B4
     * shard runtime (one ring per IoShard of a RingGroup).
     */
    enum WorkerRuntime {
        THREADS("threads"),
        SHARD("shard");

        final String cliName;

        WorkerRuntime(String cliName) {
            this.cliName = cliName;
        }

        static WorkerRuntime from(String s) throws UsageException {
            for (WorkerRuntime r : values()) {
                if (r.cliName.equalsIgnoreCase(s)) {
                    return r;
                }
            }
            throw new UsageException("unknown --runtime '" + s + "' (expected threads|shard)");
        }
    }

    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    static final class Config {
        boolean help = false;
        String[] argv = new String[0];
        Mode mode = Mode.RANDREAD;
        String file = null;
        long size = -1;                 // 0/unset => whole file
        int bs = 4096;
        int qd = 64;
        int threads = 1;
        boolean threadsExplicit = false;
        WorkerRuntime runtime = WorkerRuntime.THREADS;
        int shards = 0;                 // 0/unset => --threads (only with --runtime=shard)
        int firstCpu = 1;               // RingGroup base CPU; -1 = no affinity
        double seconds = 10.0;
        double warmupSec = 1.0;
        int runs = 1;
        boolean readonly = false;
        String jsonPath = null;
        String label = null;
        int latencySamples = 1_000_000;
        long seed = 0L;                 // 0 => derive from System.nanoTime()
        boolean verbose = false;
        // Performance knobs for the raw-ring runtime (threads mode only).
        int minComplete = 1;            // --min-complete N: completions per submit+wait
        boolean sqpoll = false;         // --sqpoll: IORING_SETUP_SQPOLL submission

        // B5 schema 1.1 metrics (docs/b5-design.md §7): ring_metrics for both runtimes,
        // a 1 Hz ring_series sampled during the measured phase, and (shard runtime only)
        // the library dispatch-latency snapshot with ShardOptions.withLatencySampleEvery(1).
        boolean metrics = true;         // --metrics on|off (default on)
        double metricsInterval = 1.0;   // --metrics-interval SEC (default 1.0; 0 = no series)

        // Resolved at runtime (memlock-aware buffer strategy, mirrored in the JSON).
        boolean fixedBuffers = true;
        String resolvedBufferMode = null;   // registered-fixed | arena-unregistered | none
        int resolvedRegisteredBuffers = 0;
        int resolvedRegisteredFiles = 0;

        static Config parse(String[] argv) throws UsageException {
            Config c = new Config();
            c.argv = argv.clone();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                String key = arg;
                String inline = null;
                if (arg.startsWith("--")) {
                    int eq = arg.indexOf('=');
                    if (eq > 0) {
                        key = arg.substring(0, eq);
                        inline = arg.substring(eq + 1);
                    }
                }
                switch (key) {
                    case "--help":
                    case "-h":
                        c.help = true;
                        return c;
                    case "--workload":
                        c.mode = Mode.from(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--file":
                        c.file = value(argv, i, key, inline);
                        if (inline == null) i++;
                        break;
                    case "--size":
                        c.size = sizeArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--bs":
                        c.bs = (int) sizeArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--qd":
                        c.qd = intArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--threads":
                        c.threads = intArg(value(argv, i, key, inline));
                        c.threadsExplicit = true;
                        if (inline == null) i++;
                        break;
                    case "--runtime":
                        c.runtime = WorkerRuntime.from(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--shards":
                        c.shards = intArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--first-cpu":
                        c.firstCpu = intArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--seconds":
                    case "--duration":
                    case "--runtime-sec":
                        c.seconds = doubleArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--warmup-sec":
                        c.warmupSec = doubleArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--runs":
                        c.runs = intArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--readonly":
                        if (inline != null) throw new UsageException("--readonly takes no value");
                        c.readonly = true;
                        break;
                    case "--json":
                        c.jsonPath = value(argv, i, key, inline);
                        if (inline == null) i++;
                        break;
                    case "--label":
                        c.label = value(argv, i, key, inline);
                        if (inline == null) i++;
                        break;
                    case "--latency-samples":
                        c.latencySamples = intArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--seed":
                        c.seed = longArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--metrics":
                        c.metrics = onOffArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--metrics-interval":
                        c.metricsInterval = doubleArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--engine":
                        String engine = value(argv, i, key, inline);
                        if (!"juring".equalsIgnoreCase(engine)) {
                            throw new UsageException("--engine " + engine + " not supported (this harness is JUring-only)");
                        }
                        if (inline == null) i++;
                        break;
                    case "--verbose":
                        if (inline != null) throw new UsageException("--verbose takes no value");
                        c.verbose = true;
                        break;
                    case "--min-complete":
                        c.minComplete = intArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--sqpoll":
                        if (inline != null) throw new UsageException("--sqpoll takes no value");
                        c.sqpoll = true;
                        break;
                    default:
                        throw new UsageException("unknown argument '" + arg + "' (try --help)");
                }
            }
            c.validate();
            return c;
        }

        static String value(String[] argv, int i, String key, String inline) throws UsageException {
            if (inline != null) {
                return inline;
            }
            if (i + 1 >= argv.length) {
                throw new UsageException("missing value for " + key);
            }
            return argv[i + 1];
        }

        static int intArg(String s) throws UsageException {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new UsageException("not an integer: '" + s + "'");
            }
        }

        static long longArg(String s) throws UsageException {
            String t = s.trim().toLowerCase(Locale.ROOT);
            try {
                if (t.startsWith("0x")) {
                    return Long.parseUnsignedLong(t.substring(2), 16);
                }
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new UsageException("not an integer: '" + s + "'");
            }
        }

        static double doubleArg(String s) throws UsageException {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new UsageException("not a number: '" + s + "'");
            }
        }

        static boolean onOffArg(String s) throws UsageException {
            String t = s.trim();
            if (t.equalsIgnoreCase("on") || t.equalsIgnoreCase("true") || t.equals("1")) {
                return true;
            }
            if (t.equalsIgnoreCase("off") || t.equalsIgnoreCase("false") || t.equals("0")) {
                return false;
            }
            throw new UsageException("expected on|off, got '" + s + "'");
        }

        static long sizeArg(String s) throws UsageException {
            String t = s.trim().toLowerCase(Locale.ROOT);
            long mult = 1L;
            if (t.endsWith("ib")) {
                t = t.substring(0, t.length() - 2);
            } else if (t.endsWith("b")) {
                t = t.substring(0, t.length() - 1);
            }
            if (t.endsWith("k")) {
                mult = 1L << 10;
                t = t.substring(0, t.length() - 1);
            } else if (t.endsWith("m")) {
                mult = 1L << 20;
                t = t.substring(0, t.length() - 1);
            } else if (t.endsWith("g")) {
                mult = 1L << 30;
                t = t.substring(0, t.length() - 1);
            } else if (t.endsWith("t")) {
                mult = 1L << 40;
                t = t.substring(0, t.length() - 1);
            }
            String num = t.trim();
            try {
                long n = Long.parseLong(num);
                return Math.multiplyExact(n, mult);
            } catch (ArithmeticException | NumberFormatException e) {
                throw new UsageException("not a byte size: '" + s + "'");
            }
        }

        void validate() throws UsageException {
            if (bs < 512 || (bs & 511) != 0) {
                throw new UsageException("--bs must be a multiple of 512 (O_DIRECT alignment), got " + bs);
            }
            if (bs > (1 << 30)) {
                throw new UsageException("--bs too large");
            }
            if (qd < 1 || qd > 8192) {
                throw new UsageException("--qd must be in [1, 8192]");
            }
            if (threads < 1 || threads > 256) {
                throw new UsageException("--threads must be in [1, 256]");
            }
            if (!(seconds > 0)) {
                throw new UsageException("--seconds must be > 0");
            }
            if (warmupSec < 0) {
                throw new UsageException("--warmup-sec must be >= 0");
            }
            if (runs < 1) {
                throw new UsageException("--runs must be >= 1");
            }
            if (latencySamples < 100) {
                throw new UsageException("--latency-samples must be >= 100");
            }
            if (!Double.isFinite(metricsInterval) || metricsInterval < 0) {
                throw new UsageException("--metrics-interval must be >= 0 seconds (0 disables the ring series)");
            }
            if (metricsInterval > 0 && metricsInterval < 0.01) {
                throw new UsageException("--metrics-interval must be >= 0.01 seconds (or 0 to disable the series)");
            }
            if (metricsInterval > 0 && seconds / metricsInterval > 100_000) {
                throw new UsageException("--metrics-interval too small for --seconds (more than 100000 series points)");
            }
            if (mode.isWrite() && readonly) {
                throw new UsageException("--readonly cannot be combined with a write workload (" + mode.cliName + ")");
            }
            if (!mode.isNop() && (file == null || file.isBlank())) {
                throw new UsageException("--file is required for workload " + mode.cliName);
            }
            if (size > 0 && size < bs) {
                throw new UsageException("--size must be >= --bs");
            }
            if (runtime == WorkerRuntime.SHARD) {
                if (mode.isNop()) {
                    throw new UsageException("--runtime=shard does not support --workload nop (no file/buffer path)");
                }
                if (shards == 0) {
                    shards = threads; // convenient default: one shard per requested thread
                }
                if (shards < 1 || shards > 64) {
                    throw new UsageException("--shards must be in [1, 64] for --runtime=shard");
                }
                if (threadsExplicit && threads != shards) {
                    throw new UsageException("--runtime=shard runs --shards N workers; "
                            + "do not combine it with a conflicting --threads " + threads);
                }
                threads = shards; // JSON "threads" = total workers (total-QD parity with fio numjobs=N)
                if (firstCpu < -1) {
                    throw new UsageException("--first-cpu must be >= -1 (-1 = no affinity), got " + firstCpu);
                }
            } else {
                if (shards != 0) {
                    throw new UsageException("--shards requires --runtime=shard");
                }
                if (firstCpu != 1) {
                    throw new UsageException("--first-cpu requires --runtime=shard");
                }
            }
            if (minComplete < 1 || minComplete > qd) {
                throw new UsageException("--min-complete must be in [1, qd=" + qd + "], got " + minComplete);
            }
            if (sqpoll && runtime == WorkerRuntime.SHARD) {
                throw new UsageException("--sqpoll is only supported with --runtime=threads");
            }
            if (label == null || label.isBlank()) {
                label = defaultLabel();
            }
        }

        String defaultLabel() {
            StringBuilder sb = new StringBuilder();
            sb.append(mode.cliName).append('_').append(humanSize(bs)).append("_qd").append(qd);
            if (runtime == WorkerRuntime.SHARD) {
                sb.append("_group").append(shards);
            } else if (threads > 1) {
                sb.append("_t").append(threads);
            }
            return sb.toString();
        }
    }

    static String humanSize(long bytes) {
        if (bytes >= (1L << 30) && bytes % (1L << 30) == 0) {
            return (bytes >> 30) + "g";
        }
        if (bytes >= (1L << 20) && bytes % (1L << 20) == 0) {
            return (bytes >> 20) + "m";
        }
        if (bytes >= (1L << 10) && bytes % (1L << 10) == 0) {
            return (bytes >> 10) + "k";
        }
        return Long.toString(bytes);
    }

    static String usage() {
        return String.join("\n",
                "JUringFileBench - JUring file-I/O benchmark (io_tester-compatible workloads)",
                "",
                "Usage:",
                "  java --enable-native-access=ALL-UNNAMED -cp target/classes:bench/juring/out \\",
                "       bench.juring.JUringFileBench [flags]",
                "",
                "Flags:",
                "  --workload randread|randwrite|seqread|seqwrite|nop   (default randread)",
                "  --file PATH             existing file for I/O workloads (O_DIRECT)",
                "  --size SIZE             dataset bytes to use; default = whole file.",
                "                          Reads: must be <= file size.",
                "                          Writes: extends the file with truncate() if larger.",
                "  --bs SIZE               block size, multiple of 512 (default 4096); 4k/1M ok",
                "  --qd N                  per-thread/per-shard queue depth (default 64)",
                "  --threads N             worker threads, each with its own ring (default 1)",
                "  --runtime threads|shard worker runtime (default threads = historical behaviour);",
                "                          shard = one B4 IoShard/ring per worker via RingGroup",
                "  --shards N              shard count for --runtime=shard, one ring per IoShard",
                "                          (default: --threads; invalid with --runtime=threads)",
                "  --first-cpu N           base CPU for shard affinity: shard i -> first-cpu+i",
                "                          (default 1; -1 = no affinity; --runtime=shard only)",
                "  --seconds S             measured duration per run (default 10)",
                "  --warmup-sec S          warmup before each measured phase (default 1)",
                "  --runs N                repetitions (default 1)",
                "  --readonly              assert/require read-only operation (reads only)",
                "  --json PATH             also write the JSON report to PATH",
                "  --label TEXT            case label for reports (default derived)",
                "  --latency-samples N     per-thread latency reservoir size (default 1000000)",
                "  --seed N                base PRNG seed (default: time based)",
                "  --metrics on|off        per-ring metrics + dispatch latency in the JSON (default on);",
                "                          off removes every B5 metrics field and disables shard latency sampling",
                "  --metrics-interval SEC  ring_series sampling interval during the measured phase",
                "                          (default 1.0; 0 disables the series; shard mode)",
                "  --min-complete N        completions to request per submit+wait (default 1;",
                "                          threads runtime only; higher N trades throughput for tail)",
                "  --sqpoll                IORING_SETUP_SQPOLL submission ring (threads runtime only;",
                "                          experimental: measured far slower than the default flags)",
                "  --engine juring         accepted for symmetry; JUring only",
                "  --verbose               extra setup diagnostics on stderr",
                "  --help                  this text",
                "",
                "Output: one JSON object on stdout; diagnostics on stderr.",
                "Example:",
                "  bench/juring/run-juring.sh --workload randread --file /mnt/TestData/read_files/single_file_bench.bin \\",
                "      --bs 4k --qd 64 --threads 1 --seconds 5 --warmup-sec 1 --readonly --label randread_4k_qd64",
                "  bench/juring/run-juring.sh --workload randread --file /mnt/TestData/read_files/single_file_bench.bin \\",
                "      --bs 4k --qd 64 --runtime=shard --shards 8 --first-cpu 1 --seconds 30 --warmup-sec 10 \\",
                "      --readonly --label randread_4k_qd64_group8",
                "");
    }

    // ------------------------------------------------------------------
    // Dataset preparation
    // ------------------------------------------------------------------

    static final class Dataset {
        final Path file;
        final long actualFileSize;   // size on disk before/after optional extension
        final long ioSize;           // region used for I/O (multiple of bs)
        final boolean writable;

        Dataset(Path file, long actualFileSize, long ioSize, boolean writable) {
            this.file = file;
            this.actualFileSize = actualFileSize;
            this.ioSize = ioSize;
            this.writable = writable;
        }

        static Dataset nop() {
            return new Dataset(null, 0, 0, false);
        }

        static Dataset prepare(Config cfg) throws UsageException, IOException {
            if (cfg.mode.isNop()) {
                return nop();
            }
            Path p = Path.of(cfg.file).toAbsolutePath().normalize();
            if (!Files.exists(p)) {
                throw new UsageException("file does not exist: " + p);
            }
            if (!Files.isRegularFile(p)) {
                throw new UsageException("not a regular file: " + p);
            }
            long actual = Files.size(p);
            long size = cfg.size > 0 ? cfg.size : actual;
            boolean writable = cfg.mode.isWrite();
            if (!writable) {
                if (size > actual) {
                    throw new UsageException("--size " + size + " exceeds file size " + actual + " (reads may not extend)");
                }
            } else {
                if (!Files.isWritable(p)) {
                    throw new UsageException("write workload but file is not writable: " + p);
                }
                if (size > actual) {
                    System.err.printf("[juring] extending %s from %,d to %,d bytes (truncate, sparse)%n", p, actual, size);
                    try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                        ch.truncate(size);
                    }
                    actual = Files.size(p);
                }
            }
            long ioSize = (size / cfg.bs) * cfg.bs;
            if (ioSize < cfg.bs) {
                throw new UsageException("usable dataset (" + ioSize + " B) is smaller than --bs (" + cfg.bs + " B)");
            }
            if (cfg.mode == Mode.SEQREAD || cfg.mode == Mode.SEQWRITE) {
                long perThread = ioSize / cfg.threads;
                if (perThread < cfg.bs) {
                    throw new UsageException("dataset too small for " + cfg.threads
                            + " sequential threads: " + perThread + " B/thread < bs");
                }
            }
            return new Dataset(p, actual, ioSize, writable);
        }
    }

    /**
     * Picks the I/O buffer strategy.
     *
     * The fast path registers the request buffers with the ring (fixed buffers,
     * IORING_OP_*_FIXED).  On Linux, registered buffers are accounted against
     * RLIMIT_MEMLOCK; this sandbox has a hard limit of 8 MiB, so large block
     * sizes (e.g. 1 MiB x QD 8) cannot be registered at all.  When the estimated
     * need approaches the memlock limit we fall back to plain 4096-aligned FFM
     * (arena) buffers with normal IORING_OP_READ/WRITE + file descriptors.  The
     * JSON records which mode was used, so this is never silently confused.
     */
    static final class Buffers {
        static long memlockSoftLimit() {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/self/limits"), StandardCharsets.UTF_8)) {
                    if (line.startsWith("Max locked memory")) {
                        String[] tok = line.substring("Max locked memory".length()).trim().split("\\s+");
                        if (tok.length > 0 && tok[0].equalsIgnoreCase("unlimited")) {
                            return Long.MAX_VALUE;
                        }
                        if (tok.length > 0) {
                            return Long.parseLong(tok[0]);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return -1L;
        }

        static boolean decideFixed(Config cfg) {
            if (cfg.mode.isNop()) {
                return false;
            }
            long limit = memlockSoftLimit();
            if (limit == Long.MAX_VALUE) {
                return true;
            }
            if (limit < 0) {
                return true; // unknown: try the fast path, per-thread fallback applies
            }
            long needed = (long) cfg.threads * cfg.qd * cfg.bs + (long) cfg.threads * (128L << 10);
            boolean fits = needed * 4 <= limit * 3; // <= 75% of the soft limit
            if (!fits) {
                System.err.printf(Locale.ROOT,
                        "[juring] note: RLIMIT_MEMLOCK=%d B, estimated registered-buffer need=%d B for %d thread(s) x qd %d x %d B; using unregistered 4096-aligned arena buffers%n",
                        limit, needed, cfg.threads, cfg.qd, cfg.bs);
            }
            return fits;
        }
    }

    static void printPreamble(Config cfg, Dataset ds) {
        System.err.printf("[juring] label=%s workload=%s", cfg.label, cfg.mode.cliName);
        if (ds.file != null) {
            System.err.printf(" file=%s fileSize=%,d ioSize=%,d%s", ds.file, ds.actualFileSize, ds.ioSize,
                    cfg.mode.isWrite() ? " (writable)" : " (read-only)");
        }
        System.err.printf(" bs=%d qd=%d threads=%d runs=%d seconds=%.2f warmup=%.2f%n",
                cfg.bs, cfg.qd, cfg.threads, cfg.runs, cfg.seconds, cfg.warmupSec);
        if (cfg.runtime == WorkerRuntime.SHARD) {
            System.err.printf("[juring] runtime=shard shards=%d firstCpu=%d "
                            + "(one ring per IoShard of one RingGroup; QD %d read-fixed pipeline per shard, "
                            + "handler-driven through the shard poll loop)%n",
                    cfg.shards, cfg.firstCpu, cfg.qd);
        }
        if (cfg.mode.isNop()) {
            System.err.printf("[juring] ring flags=%s entries=max(256,2*qd) buffers=none (nop)%n",
                    Arrays.toString(ringFlagNames(cfg)));
        } else {
            System.err.printf("[juring] ring flags=%s entries=max(256,2*qd) buffers=%s (%d x %d B)%n",
                    Arrays.toString(ringFlagNames(cfg)),
                    cfg.fixedBuffers ? "registered-fixed" : "arena-unregistered", cfg.qd, cfg.bs);
        }
        System.err.printf("[juring] metrics=%s interval=%.3fs%s (schema %s)%n",
                cfg.metrics ? "on" : "off", cfg.metricsInterval,
                cfg.metrics && cfg.metricsInterval > 0 ? " ring_series=measured-phase" : " ring_series=off",
                SCHEMA_VERSION);
    }

    // ------------------------------------------------------------------
    // One run = fresh rings, warmup, measured phase
    // ------------------------------------------------------------------

    static final class RunSync {
        final CountDownLatch ready;
        final CountDownLatch start;
        final CountDownLatch warmupDone;
        final CountDownLatch measureStart;

        RunSync(int threads) {
            ready = new CountDownLatch(threads);
            start = new CountDownLatch(1);
            warmupDone = new CountDownLatch(threads);
            measureStart = new CountDownLatch(1);
        }
    }

    static RunStat runOnce(Config cfg, Dataset ds, int runIndex) throws BenchException {
        int n = cfg.threads;
        RunSync sync = new RunSync(n);
        Job[] jobs = new Job[n];
        long seedBase = cfg.seed != 0L ? cfg.seed : (System.nanoTime() ^ ((long) (runIndex + 1) * 0x9E3779B97F4A7C15L));
        for (int i = 0; i < n; i++) {
            jobs[i] = new Job(cfg, ds, i, n, runIndex, seedBase + i * 7919L, sync);
        }
        for (Job j : jobs) {
            j.start();
        }
        SeriesSampler sampler = null;
        try {
            sync.ready.await();
            sync.start.countDown();
            sync.warmupDone.await();
            long measuredStartNanos = System.nanoTime();
            sync.measureStart.countDown();
            // Schema 1.1: one sampler thread, measured phase only, cross-thread snapshot API.
            if (cfg.metrics && cfg.metricsInterval > 0) {
                sampler = newSamplerThreads(cfg, jobs, measuredStartNanos);
                sampler.start();
            }
            for (Job j : jobs) {
                j.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (Job j : jobs) {
                j.interrupt();
            }
            throw new BenchException("interrupted while waiting for workers");
        } finally {
            if (sampler != null) {
                sampler.stopAndJoin();
            }
        }
        for (Job j : jobs) {
            if (j.failure != null) {
                throw new BenchException("worker " + j.index + " failed: " + describe(j.failure));
            }
        }

        ThreadStat[] tstats = new ThreadStat[n];
        RingMetrics[] workerMetrics = new RingMetrics[n];
        @SuppressWarnings("unchecked")
        List<SeriesPoint>[] workerSeries = new List[n];
        double maxElapsed = 0;
        long ops = 0, bytes = 0, errors = 0, overflows = 0;
        long sumLatNs = 0;
        long recordedLat = 0;
        for (int i = 0; i < n; i++) {
            Job j = jobs[i];
            RingMetrics rm = cfg.metrics ? j.ringMetricsSnapshot() : null;
            List<SeriesPoint> series = sampler == null ? null : sampler.seriesFor(i);
            ThreadStat t = j.toStat(rm, series);
            workerMetrics[i] = rm;
            workerSeries[i] = series;
            tstats[i] = t;
            maxElapsed = Math.max(maxElapsed, t.elapsedSec);
            ops += t.ops;
            bytes += t.bytes;
            errors += t.errors;
            overflows += t.seqOverflows;
            sumLatNs += t.sumLatNs;
            recordedLat += t.recordedLat;
        }
        if (maxElapsed <= 0) {
            throw new BenchException("no elapsed time recorded (workers did not run)");
        }
        LatencyStats lat = mergeLatency(jobs, cfg.latencySamples, sumLatNs, recordedLat);
        double iops = ops / maxElapsed;
        double mibs = (bytes / (1024.0 * 1024.0)) / maxElapsed;
        RunStat st = new RunStat(runIndex, maxElapsed, ops, bytes, errors, overflows, iops, mibs, lat,
                tstats, null,
                cfg.metrics ? RingMetricsAgg.sum(workerMetrics) : null,
                sampler == null ? null : RingQueueDepth.of(workerSeries),
                null,
                metricsInfo(cfg, workerSeries));
        System.err.printf(Locale.ROOT,
                "[juring] run %d/%d: %.2f s, %,d ops, %.1f MiB/s, %.1f IOPS, p50=%.1f us p99=%.1f us p999=%.1f us max=%.1f us, errors=%d, seqOverflows=%d%n",
                runIndex + 1, cfg.runs, maxElapsed, ops, mibs, iops,
                lat.p50, lat.p99, lat.p999, lat.max, errors, overflows);
        if (errors > 0) {
            System.err.println("[juring] WARNING: " + errors + " I/O errors/short completions recorded; results are not clean");
        }
        return st;
    }

    static LatencyStats mergeLatency(LatencySource[] sources, int cap, long sumLatNs, long recordedLat) {
        long totalSamples = 0;
        for (LatencySource j : sources) {
            totalSamples += j.latCount();
        }
        if (totalSamples == 0) {
            return LatencyStats.empty();
        }
        int take = (int) Math.min(cap, totalSamples);
        long[] merged = new long[take + sources.length];
        int pos = 0;
        boolean sampled = false;
        for (LatencySource j : sources) {
            int ni = j.latCount();
            if (ni == 0) {
                continue;
            }
            long[] src = j.latBuf();
            int slot = (int) Math.max(1L, Math.round((double) take * ni / totalSamples));
            int stride = Math.max(1, ni / slot);
            if (stride > 1) {
                sampled = true;
            }
            for (int k = 0; k < ni && pos < take; k += stride) {
                merged[pos++] = src[k];
            }
        }
        // Trim to the number of samples actually copied (guards against rounding and
        // the spare slots reserved above for stride sampling).
        merged = Arrays.copyOf(merged, pos);
        Arrays.sort(merged);
        return LatencyStats.fromSorted(merged, sumLatNs, recordedLat, sampled);
    }

    /**
     * A source of uniform-reservoir latency samples, so the threads-mode Job and
     * the shard-mode ShardRunner are merged with exactly the same algorithm.
     */
    interface LatencySource {
        int latCount();

        long[] latBuf();
    }

    static final class BenchException extends Exception {
        BenchException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // B5 metrics (schema 1.1; docs/b5-design.md §7/§7.1)
    // ------------------------------------------------------------------

    /** One sample of a worker's ring snapshot during the measured phase. */
    static final class SeriesPoint {
        final double tSec;
        final long inFlight;
        final long sqQueued;
        final long cqReady;
        final boolean hasDispatch;
        final long dispatchP99Nanos;

        SeriesPoint(double tSec, long inFlight, long sqQueued, long cqReady,
                    boolean hasDispatch, long dispatchP99Nanos) {
            this.tSec = tSec;
            this.inFlight = inFlight;
            this.sqQueued = sqQueued;
            this.cqReady = cqReady;
            this.hasDispatch = hasDispatch;
            this.dispatchP99Nanos = dispatchP99Nanos;
        }
    }

    /** {@code runs[].metrics}: what the B5 metrics plumbing did in this run. */
    static final class MetricsInfo {
        final boolean enabled;
        final double intervalSec;
        final int ringSeriesPoints;

        MetricsInfo(boolean enabled, double intervalSec, int ringSeriesPoints) {
            this.enabled = enabled;
            this.intervalSec = intervalSec;
            this.ringSeriesPoints = ringSeriesPoints;
        }

        static MetricsInfo off(double intervalSec) {
            return new MetricsInfo(false, intervalSec, 0);
        }
    }

    /** One worker's cross-thread snapshot pair (ring + optional library dispatch latency). */
    static final class WorkerSample {
        final RingMetrics ring;
        final LatencySnapshot dispatch;

        WorkerSample(RingMetrics ring, LatencySnapshot dispatch) {
            this.ring = ring;
            this.dispatch = dispatch;
        }
    }

    /** Cross-thread metric source for the sampler: one per worker. */
    interface WorkerMetricSource {
        /** Best-effort snapshot; may be null when the runtime is unavailable. */
        WorkerSample sample();
    }

    /**
     * The single sampler thread required by design §7: it reads the cross-thread snapshot
     * API of every worker once per interval, only between measured-phase start and end.
     * Sampling is 1 Hz by default and never touches the workers' threads.
     */
    static final class SeriesSampler implements Runnable {
        final Config cfg;
        final WorkerMetricSource[] sources;
        final List<SeriesPoint>[] series;
        final long intervalNanos;
        final long measuredStartNanos;
        volatile boolean stop;
        Thread thread;

        @SuppressWarnings("unchecked")
        SeriesSampler(Config cfg, WorkerMetricSource[] sources, long measuredStartNanos) {
            this.cfg = cfg;
            this.sources = sources;
            this.measuredStartNanos = measuredStartNanos;
            this.intervalNanos = (long) (cfg.metricsInterval * 1_000_000_000L);
            this.series = new List[sources.length];
            int maxPoints = (int) Math.min(1_000_000L,
                    (long) Math.ceil(cfg.seconds / cfg.metricsInterval) + 3);
            for (int i = 0; i < sources.length; i++) {
                series[i] = new ArrayList<>(maxPoints);
            }
        }

        void start() {
            thread = new Thread(this, "juring-metrics-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stopAndJoin() {
            stop = true;
            Thread t = thread;
            if (t == null) {
                return;
            }
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            long sleepMs = Math.max(1L, intervalNanos / 1_000_000L);
            int sleepNs = (int) Math.max(0L, intervalNanos % 1_000_000L);
            while (!stop) {
                try {
                    Thread.sleep(sleepMs, sleepNs);
                } catch (InterruptedException e) {
                    return;
                }
                if (stop) {
                    return;
                }
                long now = System.nanoTime();
                int n = sources.length;
                WorkerSample[] samples = new WorkerSample[n];
                for (int i = 0; i < n; i++) {
                    try {
                        samples[i] = sources[i].sample();
                    } catch (Throwable t) {
                        // Best-effort telemetry: a failed snapshot never fails a benchmark run.
                    }
                }
                double tSec = Math.round((now - measuredStartNanos) / 1_000_000_000.0 * 1000.0) / 1000.0;
                for (int i = 0; i < n; i++) {
                    WorkerSample sample = samples[i];
                    if (sample == null || sample.ring == null) {
                        continue;
                    }
                    RingMetrics r = sample.ring;
                    LatencySnapshot d = sample.dispatch;
                    List<SeriesPoint> points = series[i];
                    if (points.size() < 1_000_000) {
                        points.add(new SeriesPoint(tSec, r.inFlight(), r.sqQueued(), r.cqReady(),
                                d != null, d == null ? 0L : d.p99Nanos()));
                    }
                }
            }
        }

        List<SeriesPoint> seriesFor(int worker) {
            return series[worker];
        }
    }

    /** Sampler sources for the historical threads runtime: JUring.ringMetrics() per worker. */
    static SeriesSampler newSamplerThreads(Config cfg, Job[] jobs, long measuredStartNanos) {
        WorkerMetricSource[] sources = new WorkerMetricSource[jobs.length];
        for (int i = 0; i < jobs.length; i++) {
            final Job job = jobs[i];
            sources[i] = () -> new WorkerSample(job.ringMetricsSnapshot(), null);
        }
        return new SeriesSampler(cfg, sources, measuredStartNanos);
    }

    /** Sampler sources for the shard runtime: IoShard.metrics() per shard (latest snapshot). */
    static SeriesSampler newSamplerShards(Config cfg, RingGroup group, int shards, long measuredStartNanos) {
        WorkerMetricSource[] sources = new WorkerMetricSource[shards];
        for (int i = 0; i < shards; i++) {
            final IoShard shard = group.shard(i);
            sources[i] = () -> {
                ShardMetrics m = shard.metrics();
                if (m == null) {
                    return null;
                }
                return new WorkerSample(m.ring(), m.dispatchLatency());
            };
        }
        return new SeriesSampler(cfg, sources, measuredStartNanos);
    }

    static MetricsInfo metricsInfo(Config cfg, List<SeriesPoint>[] workerSeries) {
        if (!cfg.metrics) {
            return MetricsInfo.off(cfg.metricsInterval);
        }
        int points = 0;
        if (workerSeries != null) {
            for (List<SeriesPoint> list : workerSeries) {
                if (list != null && list.size() > points) {
                    points = list.size();
                }
            }
        }
        return new MetricsInfo(true, cfg.metricsInterval, points);
    }

    /** Sum of every {@link RingMetrics} counter/occupancy field over a run's workers. */
    static final class RingMetricsAgg {
        final long sqesPrepared, submitted, reaped, completed, failed, cancelled, timedOut, inFlight;
        final long enters, submitBatches, submitErrors, submitRetries, sqFullEvents;
        final long sqQueued, sqEntries, cqReady, cqEntries, cqOverflowEvents;
        final int workers;

        RingMetricsAgg(long sqesPrepared, long submitted, long reaped, long completed, long failed,
                       long cancelled, long timedOut, long inFlight, long enters, long submitBatches,
                       long submitErrors, long submitRetries, long sqFullEvents, long sqQueued,
                       long sqEntries, long cqReady, long cqEntries, long cqOverflowEvents,
                       int workers) {
            this.sqesPrepared = sqesPrepared;
            this.submitted = submitted;
            this.reaped = reaped;
            this.completed = completed;
            this.failed = failed;
            this.cancelled = cancelled;
            this.timedOut = timedOut;
            this.inFlight = inFlight;
            this.enters = enters;
            this.submitBatches = submitBatches;
            this.submitErrors = submitErrors;
            this.submitRetries = submitRetries;
            this.sqFullEvents = sqFullEvents;
            this.sqQueued = sqQueued;
            this.sqEntries = sqEntries;
            this.cqReady = cqReady;
            this.cqEntries = cqEntries;
            this.cqOverflowEvents = cqOverflowEvents;
            this.workers = workers;
        }

        static RingMetricsAgg sum(RingMetrics[] metrics) {
            int workers = 0;
            long sqesPrepared = 0, submitted = 0, reaped = 0, completed = 0, failed = 0, cancelled = 0;
            long timedOut = 0, inFlight = 0, enters = 0, submitBatches = 0, submitErrors = 0;
            long submitRetries = 0, sqFullEvents = 0, sqQueued = 0, sqEntries = 0, cqReady = 0;
            long cqEntries = 0, cqOverflowEvents = 0;
            if (metrics != null) {
                for (RingMetrics m : metrics) {
                    if (m == null) {
                        continue;
                    }
                    workers++;
                    sqesPrepared += m.sqesPrepared();
                    submitted += m.submitted();
                    reaped += m.reaped();
                    completed += m.completed();
                    failed += m.failed();
                    cancelled += m.cancelled();
                    timedOut += m.timedOut();
                    inFlight += m.inFlight();
                    enters += m.enters();
                    submitBatches += m.submitBatches();
                    submitErrors += m.submitErrors();
                    submitRetries += m.submitRetries();
                    sqFullEvents += m.sqFullEvents();
                    sqQueued += m.sqQueued();
                    sqEntries += m.sqEntries();
                    cqReady += m.cqReady();
                    cqEntries += m.cqEntries();
                    cqOverflowEvents += m.cqOverflowEvents();
                }
            }
            if (workers == 0) {
                return null;
            }
            return new RingMetricsAgg(sqesPrepared, submitted, reaped, completed, failed, cancelled,
                    timedOut, inFlight, enters, submitBatches, submitErrors, submitRetries,
                    sqFullEvents, sqQueued, sqEntries, cqReady, cqEntries, cqOverflowEvents, workers);
        }
    }

    /** Aggregate ring queue depth over one run's ring_series (all workers, measured phase). */
    static final class RingQueueDepth {
        final double meanInFlight;
        final long maxInFlight;
        final double meanSqQueued;
        final double meanCqReady;

        RingQueueDepth(double meanInFlight, long maxInFlight, double meanSqQueued, double meanCqReady) {
            this.meanInFlight = meanInFlight;
            this.maxInFlight = maxInFlight;
            this.meanSqQueued = meanSqQueued;
            this.meanCqReady = meanCqReady;
        }

        static RingQueueDepth of(List<SeriesPoint>[] series) {
            long count = 0, sumInFlight = 0, sumSqQueued = 0, sumCqReady = 0, maxInFlight = 0;
            if (series == null) {
                return null;
            }
            for (List<SeriesPoint> points : series) {
                if (points == null) {
                    continue;
                }
                for (SeriesPoint p : points) {
                    count++;
                    sumInFlight += p.inFlight;
                    if (p.inFlight > maxInFlight) {
                        maxInFlight = p.inFlight;
                    }
                    sumSqQueued += p.sqQueued;
                    sumCqReady += p.cqReady;
                }
            }
            if (count == 0) {
                return null;
            }
            return new RingQueueDepth(sumInFlight / (double) count, maxInFlight,
                    sumSqQueued / (double) count, sumCqReady / (double) count);
        }
    }

    /**
     * Run-level aggregation of the shard runtime's library dispatch/completion latency
     * (design A2.2: runtime completion latency, registration -> handler dispatch; includes
     * kernel/device completion; not fio clat / external latency_us). Per-worker snapshots
     * cannot be merged exactly (only the buckets are mergeable), so a run's percentiles are
     * the mean of its workers' percentile values and its mean is total/samples — the same
     * "mean of percentiles" convention as {@code latency_us}.
     */
    static final class DispatchLatencyAgg {
        final long samples;
        final long totalNanos;
        final long minNanos;
        final long maxNanos;
        final double meanNanos;
        final double p50Nanos, p90Nanos, p99Nanos, p999Nanos;

        DispatchLatencyAgg(long samples, long totalNanos, long minNanos, long maxNanos,
                           double meanNanos, double p50Nanos, double p90Nanos,
                           double p99Nanos, double p999Nanos) {
            this.samples = samples;
            this.totalNanos = totalNanos;
            this.minNanos = minNanos;
            this.maxNanos = maxNanos;
            this.meanNanos = meanNanos;
            this.p50Nanos = p50Nanos;
            this.p90Nanos = p90Nanos;
            this.p99Nanos = p99Nanos;
            this.p999Nanos = p999Nanos;
        }

        static DispatchLatencyAgg of(LatencySnapshot s) {
            if (s == null || s.samples() <= 0) {
                return null;
            }
            return new DispatchLatencyAgg(s.samples(), s.totalNanos(), s.minNanos(), s.maxNanos(),
                    s.meanNanos(), s.p50Nanos(), s.p90Nanos(), s.p99Nanos(), s.p999Nanos());
        }

        /** Aggregate per-worker snapshots into one run-level (or worker-level) view. */
        static DispatchLatencyAgg merge(LatencySnapshot[] snapshots) {
            if (snapshots == null) {
                return null;
            }
            long samples = 0, total = 0, min = Long.MAX_VALUE, max = 0;
            double p50 = 0, p90 = 0, p99 = 0, p999 = 0;
            int n = 0;
            for (LatencySnapshot s : snapshots) {
                if (s == null || s.samples() <= 0) {
                    continue;
                }
                n++;
                samples += s.samples();
                total += s.totalNanos();
                min = Math.min(min, s.minNanos());
                max = Math.max(max, s.maxNanos());
                p50 += s.p50Nanos();
                p90 += s.p90Nanos();
                p99 += s.p99Nanos();
                p999 += s.p999Nanos();
            }
            if (n == 0) {
                return null;
            }
            return new DispatchLatencyAgg(samples, total, min, max,
                    samples == 0 ? 0.0 : (double) total / samples,
                    p50 / n, p90 / n, p99 / n, p999 / n);
        }

        // ---- rendering helpers (all JSON latencies are microseconds) ----

        double meanUs() {
            return us(meanNanos);
        }

        double minUs() {
            return us(minNanos);
        }

        double maxUs() {
            return us(maxNanos);
        }

        double p50Us() {
            return us(p50Nanos);
        }

        double p90Us() {
            return us(p90Nanos);
        }

        double p99Us() {
            return us(p99Nanos);
        }

        double p999Us() {
            return us(p999Nanos);
        }

        static double us(double nanos) {
            return Math.round(nanos / 1000.0 * 1000.0) / 1000.0;
        }
    }

    // render helpers shared by the JSON writer
    static double meanUs(double nanos) {
        return DispatchLatencyAgg.us(nanos);
    }

    // ------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------

    static final class Job extends Thread implements LatencySource {
        final Config cfg;
        final Dataset ds;
        final int index;
        final int threadCount;
        final int runIndex;
        final long seed;
        final RunSync sync;
        final Mode mode;

        // setup
        volatile JUring ring;   // volatile: read cross-thread by the metrics sampler
        volatile RingMetrics finalRingMetrics;   // frozen snapshot captured at teardown
        FileDescriptor fd;
        int slotCount;
        int slotMask;
        boolean fixedOps;      // registered file + fixed buffers (fast path)
        boolean bufferOps;     // read/write workloads use buffers at all
        long expectedResult;
        long[] submitNanos;
        // user_data low bits are NOT always a unique in-flight slot: for fixed
        // buffers the library encodes a monotonic op id, but for non-fixed READ_EXT
        // it encodes the buffer ADDRESS (constant per buffer). All arena buffers are
        // 4096-aligned slices, so `user_data & slotMask` collides for every one of
        // them. Keep the user_data last submitted for each buffer and attribute
        // completions by scanning this qd-sized array; latency is indexed by buffer.
        long[] udOfBuf;
        MemorySegment[] arenaBuffers;
        int[] freeBufs;
        int freeTop;
        java.lang.foreign.Arena arena;

        // per-phase state (single writer/reader = this thread)
        volatile boolean collect;
        volatile long deadlineNanos;
        long timedOps;
        long timedBytes;
        long timedErrors;
        long seqOverflows;
        long sumLatNs;
        long recordedLat;

        // geometry
        long regionStart;
        long regionEnd;
        long randBlocks;
        long seqPos;

        // latency reservoir
        final long[] samples;
        int sampleCount;
        long sampleSeen;
        long rng;

        final ResultConsumer consumer = this::onResult;
        Throwable failure;

        Job(Config cfg, Dataset ds, int index, int threadCount, int runIndex, long seed, RunSync sync) {
            super("juring-bench-" + index);
            this.cfg = cfg;
            this.ds = ds;
            this.index = index;
            this.threadCount = threadCount;
            this.runIndex = runIndex;
            this.seed = seed;
            this.sync = sync;
            this.mode = cfg.mode;
            setDaemon(true);
            this.samples = new long[cfg.latencySamples];
            this.rng = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
        }

        @Override
        public void run() {
            boolean setupOk = false;
            boolean warmupCounted = false;
            boolean measureCounted = false;
            try {
                try {
                    setup();
                    setupOk = true;
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    sync.ready.countDown();
                }
                if (setupOk) {
                    sync.start.await();
                    try {
                        if (cfg.warmupSec > 0) {
                            runPhase(false, (long) (cfg.warmupSec * 1_000_000_000L));
                        }
                    } finally {
                        sync.warmupDone.countDown();
                        warmupCounted = true;
                    }
                    sync.measureStart.await();
                    long measuredStart = System.nanoTime();
                    runPhase(true, (long) (cfg.seconds * 1_000_000_000L));
                    measuredElapsedSec = (System.nanoTime() - measuredStart) / 1_000_000_000.0;
                }
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                }
            } finally {
                if (!warmupCounted) {
                    sync.warmupDone.countDown();
                }
                if (!measureCounted) {
                    sync.measureStart.countDown();
                }
                teardown();
            }
        }

        void setup() throws IOException {
            int ringEntries = Math.max(256, cfg.qd * 2);
            slotCount = Integer.highestOneBit(Math.max(64, cfg.qd * 4) - 1) << 1;
            if (slotCount < 64) {
                slotCount = 64;
            }
            slotMask = slotCount - 1;
            submitNanos = new long[slotCount];

            ring = cfg.sqpoll
                    ? new JUring(ringEntries, 0, ringFlags(cfg))  // no SQPOLL idle sleep
                    : new JUring(ringEntries, ringFlags(cfg));
            if (mode.isNop()) {
                fixedOps = false;
                bufferOps = false;
                expectedResult = 0;
            } else {
                bufferOps = true;
                expectedResult = cfg.bs;
                LinuxOpenOptions flags = mode.isWrite() ? LinuxOpenOptions.WRITE_DIRECT : LinuxOpenOptions.READ_DIRECT;
                fd = new FileDescriptor(ds.file.toString(), flags, 0);
                fixedOps = cfg.fixedBuffers;
                if (fixedOps) {
                    try {
                        int regRet = ring.registerFiles(fd);
                        if (regRet != 0) {
                            throw new IOException("registerFiles failed: " + regRet);
                        }
                        MemorySegment[] buffers = ring.registerBuffers(cfg.bs, cfg.qd);
                        if (mode.isWrite()) {
                            for (MemorySegment buffer : buffers) {
                                buffer.fill((byte) 0x5A);
                            }
                        }
                    } catch (RuntimeException | IOException e) {
                        // Per-thread fallback: e.g. RLIMIT_MEMLOCK too small for this bs*qd.
                        System.err.println("[juring] WARNING: fixed-buffer registration failed ("
                                + e.getMessage() + "); thread " + index
                                + " falls back to unregistered 4096-aligned arena buffers");
                        fixedOps = false;
                    }
                }
                if (!fixedOps) {
                    arena = java.lang.foreign.Arena.ofConfined();
                    MemorySegment slab = arena.allocate((long) cfg.qd * cfg.bs, 4096);
                    arenaBuffers = new MemorySegment[cfg.qd];
                    for (int i = 0; i < cfg.qd; i++) {
                        arenaBuffers[i] = slab.asSlice((long) i * cfg.bs, cfg.bs);
                        if (mode.isWrite()) {
                            arenaBuffers[i].fill((byte) 0x5A);
                        }
                    }
                    freeBufs = new int[cfg.qd];
                    for (int i = 0; i < cfg.qd; i++) {
                        freeBufs[i] = i;
                    }
                    freeTop = cfg.qd;
                    udOfBuf = new long[cfg.qd];
                }
            }
            synchronized (cfg) {
                if (cfg.resolvedBufferMode == null) {
                    cfg.resolvedBufferMode = mode.isNop() ? "none" : (fixedOps ? "registered-fixed" : "arena-unregistered");
                } else if (!mode.isNop() && !fixedOps) {
                    cfg.resolvedBufferMode = "arena-unregistered";
                }
                cfg.resolvedRegisteredBuffers = (!mode.isNop() && fixedOps) ? cfg.qd : 0;
                cfg.resolvedRegisteredFiles = (!mode.isNop() && fixedOps) ? 1 : 0;
            }

            long ioSize = ds.ioSize;
            if (mode.isRandom()) {
                regionStart = 0;
                regionEnd = ioSize;
                randBlocks = ioSize / cfg.bs;
            } else if (mode == Mode.SEQREAD || mode == Mode.SEQWRITE) {
                long chunk = (ioSize / threadCount / cfg.bs) * cfg.bs;
                regionStart = (long) index * chunk;
                regionEnd = (index == threadCount - 1) ? ioSize : regionStart + chunk;
                seqPos = regionStart;
            } else {
                regionStart = 0;
                regionEnd = 0;
            }
            if (cfg.verbose) {
                System.err.printf("[juring] thread %d: seed=0x%016x region=[%,d,%,d)%n",
                        index, seed, regionStart, regionEnd);
            }
        }

        void teardown() {
            try {
                JUring r = ring;
                if (r != null) {
                    r.close();
                    // Design §2.1: after close the snapshot is frozen; capture the final
                    // counters (shutdown cancels included) before dropping the ring reference.
                    if (cfg.metrics) {
                        try {
                            finalRingMetrics = r.ringMetrics();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                }
            } finally {
                ring = null;
                try {
                    if (arena != null) {
                        arena.close();
                    }
                } catch (Throwable ignored) {
                } finally {
                    arena = null;
                    try {
                        if (fd != null) {
                            fd.close();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        fd = null;
                    }
                }
            }
        }

        long nextPosition() {
            if (mode.isRandom()) {
                long x = rng;
                x ^= x >>> 12;
                x ^= x << 25;
                x ^= x >>> 27;
                rng = x;
                long val = (x * 0x2545F4914F6CDD1DL) >>> 1;
                return (val % randBlocks) * (long) cfg.bs;
            }
            long pos = seqPos;
            seqPos += cfg.bs;
            if (seqPos + cfg.bs > regionEnd) {
                seqOverflows++;
                seqPos = regionStart;
            }
            return pos;
        }

        void runPhase(boolean timed, long durationNanos) {
            this.collect = timed;
            this.deadlineNanos = System.nanoTime() + durationNanos;
            int inFlight = 0;
            inFlight = fill(inFlight);
            long deadline = this.deadlineNanos;
            while (true) {
                int reaped = ring.submitAndWaitForBatchResult(cfg.minComplete, cfg.qd, consumer);
                inFlight -= reaped;
                if (System.nanoTime() >= deadline) {
                    break;
                }
                inFlight = fill(inFlight);
            }
            while (inFlight > 0) {
                int reaped = ring.submitAndWaitForBatchResult(
                        Math.min(cfg.minComplete, inFlight), cfg.qd, consumer);
                inFlight -= reaped;
            }
        }

        int fill(int inFlight) {
            switch (mode) {
                case RANDREAD:
                case SEQREAD: {
                    while (inFlight < cfg.qd) {
                        long t0 = System.nanoTime();
                        long pos = nextPosition();
                        long ud;
                        int latIdx;
                        if (fixedOps) {
                            int buf = ring.checkOutBuffer();
                            ud = ring.prepareReadFixed(0, cfg.bs, pos, buf);
                            latIdx = (int) (ud & slotMask);   // monotonic op id
                        } else {
                            int buf = freeBufs[--freeTop];
                            ud = ring.prepareRead(fd, arenaBuffers[buf], cfg.bs, pos);
                            udOfBuf[buf] = ud;                // address payload
                            latIdx = buf;
                        }
                        submitNanos[latIdx] = t0;
                        inFlight++;
                    }
                    return inFlight;
                }
                case RANDWRITE:
                case SEQWRITE: {
                    while (inFlight < cfg.qd) {
                        long t0 = System.nanoTime();
                        long pos = nextPosition();
                        long ud;
                        int latIdx;
                        if (fixedOps) {
                            int buf = ring.checkOutBuffer();
                            ud = ring.prepareWriteFixed(0, cfg.bs, pos, buf);
                            latIdx = (int) (ud & slotMask);   // monotonic op id
                        } else {
                            int buf = freeBufs[--freeTop];
                            ud = ring.prepareWrite(fd, arenaBuffers[buf], pos);
                            udOfBuf[buf] = ud;                // monotonic op id (WRITE_EXT)
                            latIdx = buf;
                        }
                        submitNanos[latIdx] = t0;
                        inFlight++;
                    }
                    return inFlight;
                }
                case NOP: {
                    while (inFlight < cfg.qd) {
                        long t0 = System.nanoTime();
                        long ud = ring.prepareNop();
                        submitNanos[(int) (ud & slotMask)] = t0;
                        inFlight++;
                    }
                    return inFlight;
                }
                default:
                    throw new IllegalStateException("unhandled mode " + mode);
            }
        }

        void onResult(Result result) {
            long now = System.nanoTime();
            long res = result.result();
            long id = result.id();
            int latIdx;
            if (fixedOps) {
                ring.checkInBuffer(result.bufferIdx());
                latIdx = (int) (id & slotMask);           // monotonic op id
            } else if (bufferOps) {
                int buf = -1;
                for (int i = 0; i < udOfBuf.length; i++) {
                    if (udOfBuf[i] == id) {
                        buf = i;
                        break;
                    }
                }
                if (buf < 0) {
                    // Cannot attribute the completion; count it but do not hand a
                    // possibly in-flight buffer back to the free list.
                    timedErrors++;
                    return;
                }
                freeBufs[freeTop++] = buf;
                latIdx = buf;
            } else {
                latIdx = (int) (id & slotMask);           // NOP: monotonic op id
            }
            if (res == expectedResult) {
                if (collect && now < deadlineNanos) {
                    timedOps++;
                    timedBytes += res;
                    recordLatency(now - submitNanos[latIdx]);
                }
            } else {
                timedErrors++;
            }
        }

        void recordLatency(long ns) {
            sumLatNs += ns;
            recordedLat++;
            if (sampleCount < samples.length) {
                samples[sampleCount++] = ns;
            } else {
                long r = rng;
                r ^= r >>> 12;
                r ^= r << 25;
                r ^= r >>> 27;
                rng = r;
                long idx = Long.remainderUnsigned(r, sampleSeen + 1);
                if (idx < sampleCount) {
                    samples[(int) idx] = ns;
                }
            }
            sampleSeen++;
        }

        ThreadStat toStat(RingMetrics ringMetrics, List<SeriesPoint> ringSeries) {
            LatencyStats lat;
            if (sampleCount == 0) {
                lat = LatencyStats.empty();
            } else {
                long[] copy = Arrays.copyOf(samples, sampleCount);
                Arrays.sort(copy);
                lat = LatencyStats.fromSorted(copy, sumLatNs, recordedLat, sampleSeen > sampleCount);
            }
            double elapsed = 0;
            // measured phase duration for this thread: measured by wall clock around runPhase(true)
            elapsed = measuredElapsedSec;
            double iops = elapsed > 0 ? timedOps / elapsed : 0;
            double mibs = elapsed > 0 ? (timedBytes / (1024.0 * 1024.0)) / elapsed : 0;
            // Little's-law consistency check: with `cfg.qd` requests in flight, the
            // measured mean latency must equal qd / IOPS. A >25% mismatch is the
            // signature of mis-attributed per-op timestamps (e.g. a latency-slot
            // collision); warn loudly instead of silently emitting a wrong tail.
            if (collect && recordedLat > 0 && iops > 0) {
                double meanLatUs = sumLatNs / (double) recordedLat / 1000.0;
                double littleUs = cfg.qd / iops * 1e6;
                if (littleUs > 0 && meanLatUs > 0
                        && Math.abs(meanLatUs - littleUs) / littleUs > 0.25) {
                    System.err.println("[juring] WARNING: Little's law mismatch on thread "
                            + index + ": measured mean latency " + String.format("%.1f", meanLatUs)
                            + " us vs qd/IOPS " + String.format("%.1f", littleUs)
                            + " us (ratio " + String.format("%.3f", meanLatUs / littleUs)
                            + "); check timestamp attribution");
                }
            }
            return new ThreadStat(index, timedOps, timedBytes, timedErrors, seqOverflows, elapsed, iops, mibs,
                    sumLatNs, recordedLat, lat, ringMetrics, null, ringSeries);
        }

        @Override
        public int latCount() {
            return sampleCount;
        }

        @Override
        public long[] latBuf() {
            return samples;
        }

        /** Cross-thread snapshot used by the sampler and the final JSON (never throws). */
        RingMetrics ringMetricsSnapshot() {
            RingMetrics m = finalRingMetrics;
            if (m != null) {
                return m;
            }
            JUring r = ring;
            if (r == null) {
                return null;
            }
            try {
                return r.ringMetrics();
            } catch (Throwable t) {
                return null;
            }
        }

        double measuredElapsedSec;
    }

    // ------------------------------------------------------------------
    // Shard runtime mode (--runtime=shard): one IoShard / one ring per worker
    // ------------------------------------------------------------------

    /** Latches for one shard-mode run; every phase transition is event-driven. */
    static final class ShardRunSync {
        final CountDownLatch ready;
        final CountDownLatch warmupDone;
        final CountDownLatch measuredDone;
        final CountDownLatch cleanupDone;

        ShardRunSync(int shards) {
            ready = new CountDownLatch(shards);
            warmupDone = new CountDownLatch(shards);
            measuredDone = new CountDownLatch(shards);
            cleanupDone = new CountDownLatch(shards);
        }
    }

    /** Per-shard JSON payload: the threads-mode stat plus the IoShard/ShardStats snapshot. */
    static final class ShardInfo {
        final ThreadStat stat;
        final ShardStats stats;

        ShardInfo(ThreadStat stat, ShardStats stats) {
            this.stat = stat;
            this.stats = stats;
        }
    }

    /**
     * One self-driving QD-64 read/write pipeline hosted by one IoShard.
     *
     * The initial ShardTask opens the file and registers it plus the fixed buffers on
     * the shard's own ring (ctx.ring()), then the pipeline is driven purely by
     * {@link CompletionHandler}s dispatched by the shard's batched poll loop: each
     * completion records its latency and, before the phase deadline, prepares and
     * registers exactly one replacement request. This is the frozen B4 completion
     * path (design §4-§5): tasks/handlers run on the shard thread and the shard loop
     * batches the submits and reaps the completions. The shard's internal wakeup
     * READ is a sentinel consumed by IoShard itself, so it never reaches this
     * handler; a completion that reaches us with no attribution is a harness bug
     * (runOnceShard fails the cell on a non-zero completionsUnattributed).
     */
    static final class ShardRunner implements ShardTask, LatencySource {
        final Config cfg;
        final Dataset ds;
        final IoShard shard;
        final int index;
        final int shardCount;
        final int runIndex;
        final long seed;
        final ShardRunSync sync;
        final Mode mode;

        // setup (shard-thread confined)
        ShardContext ctx;
        JUring ring;
        FileDescriptor fd;
        int slotCount;
        int slotMask;
        boolean fixedOps;      // registered file + fixed buffers (fast path)
        long expectedResult;
        long[] submitNanos;
        long[] udOfBuf;
        MemorySegment[] arenaBuffers;
        int[] freeBufs;
        int freeTop;
        java.lang.foreign.Arena arena;

        // per-phase state (single writer/reader = this shard thread)
        volatile boolean collecting;   // measured phase active: ops/latency are recorded
        long deadlineNanos;
        long phaseStartNanos;
        boolean phaseEnded;
        int inFlight;
        long timedOps;
        long timedBytes;
        long timedErrors;
        long seqOverflows;
        long sumLatNs;
        long recordedLat;

        // geometry
        long regionStart;
        long regionEnd;
        long randBlocks;
        long seqPos;

        // latency reservoir (same uniform Algorithm-R sampling as Job)
        final long[] samples;
        int sampleCount;
        long sampleSeen;
        long rng;

        final CompletionHandler handler = this::onCompletion;
        Throwable failure;
        ShardStats statsSnapshot;
        double measuredElapsedSec;
        boolean warmupSignalled;
        boolean measuredSignalled;

        ShardRunner(Config cfg, Dataset ds, IoShard shard, int index, int shardCount, int runIndex,
                    long seed, ShardRunSync sync) {
            this.cfg = cfg;
            this.ds = ds;
            this.shard = shard;
            this.index = index;
            this.shardCount = shardCount;
            this.runIndex = runIndex;
            this.seed = seed;
            this.sync = sync;
            this.mode = cfg.mode;
            this.samples = new long[cfg.latencySamples];
            this.rng = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
        }

        @Override
        public void run(ShardContext c) {
            this.ctx = c;
            try {
                this.ring = c.ring();
                setup();
            } catch (Throwable t) {
                fail(t);
            } finally {
                sync.ready.countDown();
            }
        }

        /** Executed on the shard thread once every shard has set up. */
        void beginWarmup(ShardContext ctxUnused) {
            if (failure != null) {
                return;
            }
            try {
                beginPhase(false, (long) (cfg.warmupSec * 1_000_000_000L));
                fill(cfg.qd);
            } catch (Throwable t) {
                fail(t);
            }
        }

        /** Executed on the shard thread after every shard drained its warmup. */
        void startMeasured(ShardContext ctxUnused) {
            if (failure != null) {
                return;
            }
            try {
                beginPhase(true, (long) (cfg.seconds * 1_000_000_000L));
                fill(cfg.qd);
            } catch (Throwable t) {
                fail(t);
            }
        }

        /** Executed on the shard thread before the group is closed. */
        void teardown(ShardContext ctxUnused) {
            try {
                if (arena != null) {
                    arena.close();
                }
            } catch (Throwable ignored) {
            } finally {
                arena = null;
                arenaBuffers = null;
                freeBufs = null;
                udOfBuf = null;
                try {
                    if (fd != null) {
                        fd.close();
                    }
                } catch (Throwable ignored) {
                } finally {
                    fd = null;
                    sync.cleanupDone.countDown();
                }
            }
        }

        void beginPhase(boolean measured, long durationNanos) {
            this.collecting = measured;
            this.phaseEnded = false;
            this.phaseStartNanos = System.nanoTime();
            this.deadlineNanos = phaseStartNanos + durationNanos;
        }

        void fill(int target) {
            while (inFlight < target) {
                fillOne();
            }
        }

        void fillOne() {
            long t0 = System.nanoTime();
            long pos = nextPosition();
            long ud;
            int latIdx;
            if (fixedOps) {
                int buf = ring.checkOutBuffer();
                ud = mode.isWrite() ? ring.prepareWriteFixed(0, cfg.bs, pos, buf)
                        : ring.prepareReadFixed(0, cfg.bs, pos, buf);
                latIdx = (int) (ud & slotMask);   // monotonic op id
            } else {
                int buf = freeBufs[--freeTop];
                ud = mode.isWrite() ? ring.prepareWrite(fd, arenaBuffers[buf], pos)
                        : ring.prepareRead(fd, arenaBuffers[buf], cfg.bs, pos);
                udOfBuf[buf] = ud;                // address payload
                latIdx = buf;
            }
            submitNanos[latIdx] = t0;
            ctx.onCompletion(ud, handler);        // register BEFORE the loop's submit()
            inFlight++;
        }

        void onCompletion(Result result) {
            try {
                long now = System.nanoTime();
                long res = result.result();
                long id = result.id();
                int latIdx = -1;
                if (fixedOps) {
                    ring.checkInBuffer(result.bufferIdx());
                    latIdx = (int) (id & slotMask);           // monotonic op id
                } else if (udOfBuf != null) {
                    int buf = -1;
                    for (int i = 0; i < udOfBuf.length; i++) {
                        if (udOfBuf[i] == id) {
                            buf = i;
                            break;
                        }
                    }
                    if (buf >= 0) {
                        freeBufs[freeTop++] = buf;
                        latIdx = buf;
                    }
                } else {
                    latIdx = (int) (id & slotMask);
                }
                if (latIdx < 0) {
                    // Cannot attribute the completion; count it but do not hand a
                    // possibly in-flight buffer back to the free list.
                    timedErrors++;
                } else if (res == expectedResult) {
                    if (collecting && now < deadlineNanos) {
                        timedOps++;
                        timedBytes += res;
                        recordLatency(now - submitNanos[latIdx]);
                    }
                } else {
                    timedErrors++;
                }
                inFlight--;
                if (inFlight < 0) {
                    inFlight = 0;
                }
                if (now >= deadlineNanos) {
                    phaseEnded = true;
                }
                if (phaseEnded) {
                    if (inFlight == 0) {
                        phaseComplete(now);
                    }
                } else {
                    fillOne();
                }
            } catch (Throwable t) {
                fail(t);
            }
        }

        void phaseComplete(long now) {
            if (!collecting) {
                if (!warmupSignalled) {
                    warmupSignalled = true;
                    sync.warmupDone.countDown();
                }
            } else {
                measuredElapsedSec = (now - phaseStartNanos) / 1_000_000_000.0;
                if (!measuredSignalled) {
                    measuredSignalled = true;
                    try {
                        statsSnapshot = shard.stats();
                    } catch (Throwable ignored) {
                    }
                    sync.measuredDone.countDown();
                }
            }
        }

        void fail(Throwable t) {
            if (failure == null) {
                failure = t;
            }
            phaseEnded = true;
            // Never leave the runOnceShard controller blocked on a latch.
            if (!warmupSignalled) {
                warmupSignalled = true;
                sync.warmupDone.countDown();
            }
            if (inFlight == 0 && !measuredSignalled) {
                measuredSignalled = true;
                sync.measuredDone.countDown();
            }
        }

        void recordLatency(long ns) {
            sumLatNs += ns;
            recordedLat++;
            if (sampleCount < samples.length) {
                samples[sampleCount++] = ns;
            } else {
                long r = rng;
                r ^= r >>> 12;
                r ^= r << 25;
                r ^= r >>> 27;
                rng = r;
                long idx = Long.remainderUnsigned(r, sampleSeen + 1);
                if (idx < sampleCount) {
                    samples[(int) idx] = ns;
                }
            }
            sampleSeen++;
        }

        long nextPosition() {
            if (mode.isRandom()) {
                long x = rng;
                x ^= x >>> 12;
                x ^= x << 25;
                x ^= x >>> 27;
                rng = x;
                long val = (x * 0x2545F4914F6CDD1DL) >>> 1;
                return (val % randBlocks) * (long) cfg.bs;
            }
            long pos = seqPos;
            seqPos += cfg.bs;
            if (seqPos + cfg.bs > regionEnd) {
                seqOverflows++;
                seqPos = regionStart;
            }
            return pos;
        }

        void setup() throws IOException {
            slotCount = Integer.highestOneBit(Math.max(64, cfg.qd * 4) - 1) << 1;
            if (slotCount < 64) {
                slotCount = 64;
            }
            slotMask = slotCount - 1;
            submitNanos = new long[slotCount];

            fixedOps = cfg.fixedBuffers;
            expectedResult = cfg.bs;
            LinuxOpenOptions flags = mode.isWrite() ? LinuxOpenOptions.WRITE_DIRECT : LinuxOpenOptions.READ_DIRECT;
            fd = new FileDescriptor(ds.file.toString(), flags, 0);
            if (fixedOps) {
                try {
                    int regRet = ring.registerFiles(fd);
                    if (regRet != 0) {
                        throw new IOException("registerFiles failed: " + regRet);
                    }
                    MemorySegment[] buffers = ring.registerBuffers(cfg.bs, cfg.qd);
                    if (mode.isWrite()) {
                        for (MemorySegment buffer : buffers) {
                            buffer.fill((byte) 0x5A);
                        }
                    }
                } catch (RuntimeException | IOException e) {
                    // Per-shard fallback: e.g. RLIMIT_MEMLOCK too small for shards*qd*bs.
                    System.err.println("[juring] WARNING: fixed-buffer registration failed ("
                            + e.getMessage() + "); shard " + index
                            + " falls back to unregistered 4096-aligned arena buffers");
                    fixedOps = false;
                }
            }
            if (!fixedOps) {
                arena = java.lang.foreign.Arena.ofConfined();
                MemorySegment slab = arena.allocate((long) cfg.qd * cfg.bs, 4096);
                arenaBuffers = new MemorySegment[cfg.qd];
                for (int i = 0; i < cfg.qd; i++) {
                    arenaBuffers[i] = slab.asSlice((long) i * cfg.bs, cfg.bs);
                    if (mode.isWrite()) {
                        arenaBuffers[i].fill((byte) 0x5A);
                    }
                }
                freeBufs = new int[cfg.qd];
                for (int i = 0; i < cfg.qd; i++) {
                    freeBufs[i] = i;
                }
                freeTop = cfg.qd;
                udOfBuf = new long[cfg.qd];
            }
            synchronized (cfg) {
                if (cfg.resolvedBufferMode == null) {
                    cfg.resolvedBufferMode = fixedOps ? "registered-fixed" : "arena-unregistered";
                } else if (!fixedOps) {
                    cfg.resolvedBufferMode = "arena-unregistered";
                }
                cfg.resolvedRegisteredBuffers = fixedOps ? cfg.qd : 0;
                cfg.resolvedRegisteredFiles = fixedOps ? 1 : 0;
            }

            long ioSize = ds.ioSize;
            if (mode.isRandom()) {
                regionStart = 0;
                regionEnd = ioSize;
                randBlocks = ioSize / cfg.bs;
            } else {
                long chunk = (ioSize / shardCount / cfg.bs) * cfg.bs;
                regionStart = (long) index * chunk;
                regionEnd = (index == shardCount - 1) ? ioSize : regionStart + chunk;
                seqPos = regionStart;
            }
            if (cfg.verbose) {
                System.err.printf("[juring] shard %d: ringEntries=%d fixed=%s seed=0x%016x region=[%,d,%,d)%n",
                        index, Math.max(256, cfg.qd * 2), fixedOps, seed, regionStart, regionEnd);
            }
        }

        ThreadStat toStat(RingMetrics ringMetrics, DispatchLatencyAgg dispatchLat,
                          List<SeriesPoint> ringSeries) {
            LatencyStats lat;
            if (sampleCount == 0) {
                lat = LatencyStats.empty();
            } else {
                long[] copy = Arrays.copyOf(samples, sampleCount);
                Arrays.sort(copy);
                lat = LatencyStats.fromSorted(copy, sumLatNs, recordedLat, sampleSeen > sampleCount);
            }
            double iops = measuredElapsedSec > 0 ? timedOps / measuredElapsedSec : 0;
            double mibs = measuredElapsedSec > 0
                    ? (timedBytes / (1024.0 * 1024.0)) / measuredElapsedSec : 0;
            // Same Little's-law consistency check as the threads mode: a >25%
            // mismatch means the per-op timestamps are mis-attributed.
            if (collecting && recordedLat > 0 && iops > 0) {
                double meanLatUs = sumLatNs / (double) recordedLat / 1000.0;
                double littleUs = cfg.qd / iops * 1e6;
                if (littleUs > 0 && meanLatUs > 0
                        && Math.abs(meanLatUs - littleUs) / littleUs > 0.25) {
                    System.err.println("[juring] WARNING: Little's law mismatch on shard "
                            + index + ": measured mean latency "
                            + String.format(Locale.ROOT, "%.1f", meanLatUs)
                            + " us vs qd/IOPS " + String.format(Locale.ROOT, "%.1f", littleUs)
                            + " us (ratio " + String.format(Locale.ROOT, "%.3f", meanLatUs / littleUs)
                            + "); check timestamp attribution");
                }
            }
            return new ThreadStat(index, timedOps, timedBytes, timedErrors, seqOverflows,
                    measuredElapsedSec, iops, mibs, sumLatNs, recordedLat, lat,
                    ringMetrics, dispatchLat, ringSeries);
        }

        @Override
        public int latCount() {
            return sampleCount;
        }

        @Override
        public long[] latBuf() {
            return samples;
        }
    }

    /**
     * One shard-mode run: build a RingGroup of N IoShards (CPUs firstCpu..firstCpu+N-1),
     * run the same warmup/measured protocol as the threads mode, and aggregate the
     * per-shard stats with the same formulas (ops/maxElapsed, merged reservoir).
     */
    static RunStat runOnceShard(Config cfg, Dataset ds, int runIndex) throws BenchException {
        int n = cfg.shards;
        ShardRunSync sync = new ShardRunSync(n);
        ShardOptions options = ShardOptions.defaults()
                .withName("bench")
                .withRingOptions(RING_FLAGS)
                .withRingEntries(Math.max(256, cfg.qd * 2))
                // Design §7: shard runtime enables library dispatch-latency sampling with
                // metrics on (every registration). Metrics off keeps the B4 default (0).
                .withLatencySampleEvery(cfg.metrics ? 1 : 0);
        long seedBase = cfg.seed != 0L ? cfg.seed
                : (System.nanoTime() ^ ((long) (runIndex + 1) * 0x9E3779B97F4A7C15L));
        ShardRunner[] runners = new ShardRunner[n];
        RingGroup group = null;
        SeriesSampler sampler = null;
        try {
            group = RingGroup.create(n, options, cfg.firstCpu);
            group.start();
            for (int i = 0; i < n; i++) {
                runners[i] = new ShardRunner(cfg, ds, group.shard(i), i, n, runIndex,
                        seedBase + i * 7919L, sync);
            }
            for (int i = 0; i < n; i++) {
                group.shard(i).execute(runners[i]);
            }
            if (!sync.ready.await(120, TimeUnit.SECONDS)) {
                throw new BenchException("timed out waiting for " + n + " shard(s) to set up");
            }
            throwIfShardFailed(runners, "setup");

            for (int i = 0; i < n; i++) {
                group.shard(i).execute(runners[i]::beginWarmup);
            }
            long warmupWaitSec = (long) Math.max(60L, Math.ceil(cfg.warmupSec) + 60L);
            if (!sync.warmupDone.await(warmupWaitSec, TimeUnit.SECONDS)) {
                throw new BenchException("timed out waiting for the warmup phase (" + warmupWaitSec + " s)");
            }
            throwIfShardFailed(runners, "warmup");

            for (int i = 0; i < n; i++) {
                group.shard(i).execute(runners[i]::startMeasured);
            }
            long measuredWaitSec = (long) Math.max(60L, Math.ceil(cfg.seconds) + 60L);
            long measuredStartNanos = System.nanoTime();
            if (cfg.metrics && cfg.metricsInterval > 0) {
                sampler = newSamplerShards(cfg, group, n, measuredStartNanos);
                sampler.start();
            }
            try {
                if (!sync.measuredDone.await(measuredWaitSec, TimeUnit.SECONDS)) {
                    throw new BenchException("timed out waiting for the measured phase ("
                            + measuredWaitSec + " s)");
                }
            } finally {
                if (sampler != null) {
                    sampler.stopAndJoin();
                }
            }
            throwIfShardFailed(runners, "measured phase");

            // User-owned resources (fd + arena) must be closed on the shard thread
            // before the group (and its rings) is closed.
            for (int i = 0; i < n; i++) {
                try {
                    group.shard(i).execute(runners[i]::teardown);
                } catch (RuntimeException ignored) {
                    // best effort; process exit / group close still releases the ring
                }
            }
            if (!sync.cleanupDone.await(10, TimeUnit.SECONDS)) {
                System.err.println("[juring] warning: shard cleanup did not complete within 10 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BenchException("interrupted while waiting for the shard runtime");
        } finally {
            if (group != null) {
                try {
                    group.requestStop();
                } catch (Throwable ignored) {
                }
                group.close();
            }
        }

        // Design §2.5/§2.6: after close the snapshots are frozen (shutdown cancels included).
        // RingGroup.metrics() is total (never throws) and includes closed/unstarted shards.
        java.util.List<ShardMetrics> finalMetrics = null;
        if (group != null) {
            try {
                finalMetrics = group.metrics();
            } catch (Throwable ignored) {
            }
        }

        ThreadStat[] tstats = new ThreadStat[n];
        ShardInfo[] shardInfos = new ShardInfo[n];
        RingMetrics[] workerMetrics = new RingMetrics[n];
        LatencySnapshot[] workerDispatch = new LatencySnapshot[n];
        @SuppressWarnings("unchecked")
        List<SeriesPoint>[] workerSeries = new List[n];
        double maxElapsed = 0;
        long ops = 0, bytes = 0, errors = 0, overflows = 0;
        long sumLatNs = 0;
        long recordedLat = 0;
        for (int i = 0; i < n; i++) {
            RingMetrics rm = null;
            LatencySnapshot dispatchSnapshot = null;
            if (cfg.metrics && finalMetrics != null && i < finalMetrics.size()) {
                ShardMetrics sm = finalMetrics.get(i);
                if (sm != null) {
                    rm = sm.ring();
                    dispatchSnapshot = sm.dispatchLatency();
                }
            }
            List<SeriesPoint> series = sampler == null ? null : sampler.seriesFor(i);
            ThreadStat t = runners[i].toStat(rm, DispatchLatencyAgg.of(dispatchSnapshot), series);
            workerMetrics[i] = rm;
            workerDispatch[i] = dispatchSnapshot;
            workerSeries[i] = series;
            tstats[i] = t;
            shardInfos[i] = new ShardInfo(t, runners[i].statsSnapshot);
            maxElapsed = Math.max(maxElapsed, t.elapsedSec);
            ops += t.ops;
            bytes += t.bytes;
            errors += t.errors;
            overflows += t.seqOverflows;
            sumLatNs += t.sumLatNs;
            recordedLat += t.recordedLat;
            ShardStats ss = runners[i].statsSnapshot;
            if (ss != null && ss.completionsUnattributed() > 0) {
                throw new BenchException("harness bug: shard " + i + " saw "
                        + ss.completionsUnattributed() + " unattributed completion(s); the "
                        + "shard-internal wakeup READ must never reach the benchmark pipeline");
            }
        }
        if (maxElapsed <= 0) {
            throw new BenchException("no elapsed time recorded (shards did not run)");
        }
        LatencyStats lat = mergeLatency(runners, cfg.latencySamples, sumLatNs, recordedLat);
        double iops = ops / maxElapsed;
        double mibs = (bytes / (1024.0 * 1024.0)) / maxElapsed;
        RunStat st = new RunStat(runIndex, maxElapsed, ops, bytes, errors, overflows, iops, mibs,
                lat, tstats, shardInfos,
                cfg.metrics ? RingMetricsAgg.sum(workerMetrics) : null,
                sampler == null ? null : RingQueueDepth.of(workerSeries),
                cfg.metrics ? DispatchLatencyAgg.merge(workerDispatch) : null,
                metricsInfo(cfg, workerSeries));
        System.err.printf(Locale.ROOT,
                "[juring] run %d/%d (runtime=shard shards=%d): %.2f s, %,d ops, %.1f MiB/s, %.1f IOPS, "
                        + "p50=%.1f us p99=%.1f us p999=%.1f us max=%.1f us, errors=%d, seqOverflows=%d%n",
                runIndex + 1, cfg.runs, n, maxElapsed, ops, mibs, iops,
                lat.p50, lat.p99, lat.p999, lat.max, errors, overflows);
        if (errors > 0) {
            System.err.println("[juring] WARNING: " + errors
                    + " I/O errors/short completions recorded; results are not clean");
        }
        if (cfg.verbose) {
            for (int i = 0; i < n; i++) {
                ShardStats s = runners[i].statsSnapshot;
                if (s != null) {
                    System.err.printf(Locale.ROOT,
                            "[juring] shard %d: cpu=%d (requested %d, applied=%s) completions registered=%d "
                                    + "dispatched=%d unattributed=%d failures=%d inFlight=%d%n",
                            s.shardId(), s.currentCpu(), s.requestedCpu(), s.affinityApplied(),
                            s.completionsRegistered(), s.completionsDispatched(),
                            s.completionsUnattributed(), s.completionFailures(), s.inFlightRequests());
                }
            }
        }
        return st;
    }

    static void throwIfShardFailed(ShardRunner[] runners, String phase) throws BenchException {
        for (ShardRunner r : runners) {
            if (r.failure != null) {
                throw new BenchException("shard " + r.index + " failed during " + phase + ": "
                        + describe(r.failure));
            }
        }
    }

    // ------------------------------------------------------------------
    // Result structs
    // ------------------------------------------------------------------

    static final class LatencyStats {
        long recorded;
        int samples;
        boolean sampled;
        double min, avg, p50, p90, p95, p99, p999, max;

        static LatencyStats empty() {
            return new LatencyStats();
        }

        static LatencyStats fromSorted(long[] sorted, long sumNs, long recorded, boolean sampled) {
            LatencyStats s = new LatencyStats();
            s.recorded = recorded;
            s.samples = sorted.length;
            s.sampled = sampled;
            if (sorted.length == 0) {
                return s;
            }
            s.min = us(sorted[0]);
            s.max = us(sorted[sorted.length - 1]);
            s.avg = recorded > 0 ? us(sumNs / (double) recorded) : s.min;
            s.p50 = us(quantile(sorted, 0.50));
            s.p90 = us(quantile(sorted, 0.90));
            s.p95 = us(quantile(sorted, 0.95));
            s.p99 = us(quantile(sorted, 0.99));
            s.p999 = us(quantile(sorted, 0.999));
            return s;
        }

        static long quantile(long[] sorted, double q) {
            int n = sorted.length;
            int idx = (int) Math.ceil(q * n) - 1;
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= n) {
                idx = n - 1;
            }
            return sorted[idx];
        }

        static double us(double ns) {
            return Math.round(ns / 1000.0 * 1000.0) / 1000.0;
        }
    }

    static final class ThreadStat {
        final int thread;
        final long ops;
        final long bytes;
        final long errors;
        final long seqOverflows;
        final double elapsedSec;
        final double iops;
        final double mibs;
        final long sumLatNs;
        final long recordedLat;
        final LatencyStats lat;
        // B5 schema 1.1 (null when --metrics off / unavailable)
        final RingMetrics ringMetrics;
        final DispatchLatencyAgg dispatchLat;   // shard runtime only
        final List<SeriesPoint> ringSeries;     // measured phase only

        ThreadStat(int thread, long ops, long bytes, long errors, long seqOverflows, double elapsedSec,
                   double iops, double mibs, long sumLatNs, long recordedLat, LatencyStats lat,
                   RingMetrics ringMetrics, DispatchLatencyAgg dispatchLat, List<SeriesPoint> ringSeries) {
            this.thread = thread;
            this.ops = ops;
            this.bytes = bytes;
            this.errors = errors;
            this.seqOverflows = seqOverflows;
            this.elapsedSec = elapsedSec;
            this.iops = iops;
            this.mibs = mibs;
            this.sumLatNs = sumLatNs;
            this.recordedLat = recordedLat;
            this.lat = lat;
            this.ringMetrics = ringMetrics;
            this.dispatchLat = dispatchLat;
            this.ringSeries = ringSeries;
        }
    }

    static final class RunStat {
        final int run;
        final double elapsedSec;
        final long ops;
        final long bytes;
        final long errors;
        final long seqOverflows;
        final double iops;
        final double mibs;
        final LatencyStats lat;
        final ThreadStat[] threads;
        final ShardInfo[] shards;   // non-null only for --runtime=shard
        // B5 schema 1.1 (null/off when --metrics off or no series)
        final RingMetricsAgg ringMetrics;   // summed per-worker counters for this run
        final RingQueueDepth ringQueueDepth; // from ring_series, all workers
        final DispatchLatencyAgg dispatchLat; // shard runtime only
        final MetricsInfo metrics;

        RunStat(int run, double elapsedSec, long ops, long bytes, long errors, long seqOverflows,
                double iops, double mibs, LatencyStats lat, ThreadStat[] threads, ShardInfo[] shards,
                RingMetricsAgg ringMetrics, RingQueueDepth ringQueueDepth,
                DispatchLatencyAgg dispatchLat, MetricsInfo metrics) {
            this.run = run;
            this.elapsedSec = elapsedSec;
            this.ops = ops;
            this.bytes = bytes;
            this.errors = errors;
            this.seqOverflows = seqOverflows;
            this.iops = iops;
            this.mibs = mibs;
            this.lat = lat;
            this.threads = threads;
            this.shards = shards;
            this.ringMetrics = ringMetrics;
            this.ringQueueDepth = ringQueueDepth;
            this.dispatchLat = dispatchLat;
            this.metrics = metrics;
        }
    }

    // ------------------------------------------------------------------
    // Manifest
    // ------------------------------------------------------------------

    static final class Manifest {
        String timestampUtc = Instant.now().toString();
        String juringGitCommit = env("JURING_GIT_COMMIT");
        String juringGitDirty = env("JURING_GIT_DIRTY");
        String juringGitVersion = env("JURING_GIT_VERSION");
        String juringClasses = null;
        String javaVersion = System.getProperty("java.version");
        String javaVmVersion = System.getProperty("java.vm.version");
        String javaHome = System.getProperty("java.home");
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        String osVersion = System.getProperty("os.version");
        String kernel = readTrimmed(Path.of("/proc/sys/kernel/osrelease"));
        String hostname = readTrimmed(Path.of("/proc/sys/kernel/hostname"));
        String cpuModel = null;
        int logicalCpus = Runtime.getRuntime().availableProcessors();
        String cpusAllowed = procStatus("Cpus_allowed_list");
        String loadavgAtStart = readTrimmed(Path.of("/proc/loadavg"));
        String cpuGovernor = readTrimmed(Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"));
        long maxHeapBytes = Runtime.getRuntime().maxMemory();

        static Manifest collect(String[] argv) {
            Manifest m = new Manifest();
            try {
                var src = JUring.class.getProtectionDomain().getCodeSource();
                if (src != null && src.getLocation() != null) {
                    m.juringClasses = Path.of(src.getLocation().toURI()).toString();
                }
            } catch (Exception ignored) {
            }
            if (m.juringGitCommit == null) {
                m.juringGitCommit = firstLine(runCommand("git", "rev-parse", "HEAD"));
            }
            if (m.juringGitDirty == null) {
                String out = runCommand("git", "status", "--porcelain");
                if (out != null) {
                    m.juringGitDirty = out.isBlank() ? "false" : "true";
                }
            }
            if (m.juringGitVersion == null) {
                m.juringGitVersion = firstLine(runCommand("git", "describe", "--tags", "--always", "--dirty"));
            }
            m.cpuModel = readCpuModel();
            return m;
        }

        static String env(String name) {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? null : v;
        }

        static String firstLine(String s) {
            if (s == null) {
                return null;
            }
            int nl = s.indexOf('\n');
            return (nl < 0 ? s : s.substring(0, nl)).trim();
        }

        static String readTrimmed(Path p) {
            try {
                return Files.readString(p, StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                return null;
            }
        }

        static String procStatus(String key) {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8)) {
                    if (line.startsWith(key + ":")) {
                        return line.substring(key.length() + 1).trim();
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        static String readCpuModel() {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
                    if (line.startsWith("model name")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            return line.substring(colon + 1).trim();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        static String runCommand(String... cmd) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                byte[] out = p.getInputStream().readAllBytes();
                if (!p.waitFor(2, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    p.destroyForcibly();
                    return null;
                }
                return new String(out, StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Minimal JSON writer (no dependencies)
    // ------------------------------------------------------------------

    static final class Jw {
        private static final class Frame {
            final boolean object;
            boolean first = true;
            boolean afterName = false;

            Frame(boolean object) {
                this.object = object;
            }
        }

        private final StringBuilder b = new StringBuilder(16 * 1024);
        private final ArrayDeque<Frame> stack = new ArrayDeque<>();

        private void valuePrefix() {
            Frame f = stack.peek();
            if (f == null) {
                return;
            }
            if (f.afterName) {
                f.afterName = false;
                return;
            }
            if (!f.first) {
                b.append(',');
            }
            f.first = false;
        }

        Jw obj() {
            valuePrefix();
            b.append('{');
            stack.push(new Frame(true));
            return this;
        }

        Jw endObj() {
            b.append('}');
            stack.pop();
            return this;
        }

        Jw arr() {
            valuePrefix();
            b.append('[');
            stack.push(new Frame(false));
            return this;
        }

        Jw endArr() {
            b.append(']');
            stack.pop();
            return this;
        }

        Jw name(String n) {
            Frame f = stack.peek();
            if (!f.first) {
                b.append(',');
            }
            f.first = false;
            b.append(quote(n)).append(':');
            f.afterName = true;
            return this;
        }

        Jw str(String v) {
            valuePrefix();
            b.append(v == null ? "null" : quote(v));
            return this;
        }

        Jw num(long v) {
            valuePrefix();
            b.append(v);
            return this;
        }

        Jw num(double v) {
            valuePrefix();
            b.append(dnumber(v));
            return this;
        }

        Jw bool(boolean v) {
            valuePrefix();
            b.append(v ? "true" : "false");
            return this;
        }

        Jw nul() {
            valuePrefix();
            b.append("null");
            return this;
        }

        static String dnumber(double v) {
            if (!Double.isFinite(v)) {
                return "null";
            }
            return Double.toString(v);
        }

        static String quote(String s) {
            StringBuilder r = new StringBuilder(s.length() + 2);
            r.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        r.append("\\\"");
                        break;
                    case '\\':
                        r.append("\\\\");
                        break;
                    case '\n':
                        r.append("\\n");
                        break;
                    case '\r':
                        r.append("\\r");
                        break;
                    case '\t':
                        r.append("\\t");
                        break;
                    case '\b':
                        r.append("\\b");
                        break;
                    case '\f':
                        r.append("\\f");
                        break;
                    default:
                        if (c < 0x20 || c > 0x7E) {
                            r.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            r.append(c);
                        }
                }
            }
            r.append('"');
            return r.toString();
        }

        @Override
        public String toString() {
            return b.toString();
        }
    }

    // ------------------------------------------------------------------
    // JSON report
    // ------------------------------------------------------------------

    static final class Report {

        static String render(Config cfg, Dataset ds, Manifest m, RunStat[] runs, String error, String status) {
            Jw w = new Jw();
            w.obj();
            w.name("schema_version").str(SCHEMA_VERSION);
            w.name("engine").str(ENGINE);
            w.name("status").str(status);
            w.name("error").str(error);
            w.name("label").str(cfg != null ? cfg.label : null);
            w.name("timestamp_utc").str(m != null ? m.timestampUtc : Instant.now().toString());
            w.name("argv").arr();
            if (cfg != null) {
                for (String a : cfg.argv) {
                    w.str(a);
                }
            }
            w.endArr();
            w.name("manifest").obj();
            renderManifest(w, m);
            w.endObj();
            w.name("config").obj();
            renderConfig(w, cfg, ds);
            w.endObj();
            w.name("runs").arr();
            for (RunStat r : runs) {
                renderRun(w, r);
            }
            w.endArr();
            if (runs.length > 0) {
                w.name("aggregate").obj();
                renderAggregate(w, runs);
                w.endObj();
            } else {
                w.name("aggregate").nul();
            }
            w.endObj();
            return w.toString();
        }

        static void renderManifest(Jw w, Manifest m) {
            if (m == null) {
                return;
            }
            w.name("juring_git_commit").str(m.juringGitCommit);
            w.name("juring_git_dirty").str(m.juringGitDirty);
            w.name("juring_git_version").str(m.juringGitVersion);
            w.name("juring_classes").str(m.juringClasses);
            w.name("java_version").str(m.javaVersion);
            w.name("java_vm_version").str(m.javaVmVersion);
            w.name("java_home").str(m.javaHome);
            w.name("os_name").str(m.osName);
            w.name("os_arch").str(m.osArch);
            w.name("os_version").str(m.osVersion);
            w.name("kernel").str(m.kernel);
            w.name("hostname").str(m.hostname);
            w.name("cpu_model").str(m.cpuModel);
            w.name("logical_cpus").num(m.logicalCpus);
            w.name("cpus_allowed").str(m.cpusAllowed);
            w.name("loadavg_at_start").str(m.loadavgAtStart);
            w.name("loadavg_at_end").str(Manifest.readTrimmed(Path.of("/proc/loadavg")));
            w.name("cpu_governor").str(m.cpuGovernor);
            w.name("max_heap_bytes").num(m.maxHeapBytes);
        }

        static void renderConfig(Jw w, Config c, Dataset ds) {
            if (c == null) {
                return;
            }
            w.name("workload").str(c.mode.cliName);
            w.name("file").str(ds != null && ds.file != null ? ds.file.toString() : null);
            w.name("file_size_bytes").num(ds != null ? ds.actualFileSize : 0);
            w.name("dataset_bytes").num(ds != null ? ds.ioSize : 0);
            w.name("bs").num(c.bs);
            w.name("qd").num(c.qd);
            w.name("threads").num(c.threads);
            if (c.runtime == WorkerRuntime.SHARD) {
                w.name("runtime").str(c.runtime.cliName);
                w.name("shards").num(c.shards);
                w.name("first_cpu").num(c.firstCpu);
            }
            w.name("seconds").num(c.seconds);
            w.name("warmup_sec").num(c.warmupSec);
            w.name("runs").num(c.runs);
            w.name("readonly").bool(!c.mode.isWrite());
            w.name("direct_io").bool(!c.mode.isNop());
            w.name("ring_entries").num(Math.max(256, c.qd * 2));
            w.name("ring_flags").arr();
            for (String f : ringFlagNames(c)) {
                w.str(f);
            }
            w.endArr();
            w.name("min_complete").num(c.minComplete);
            w.name("io_buffer_mode").str(c.resolvedBufferMode != null ? c.resolvedBufferMode
                    : (c.mode.isNop() ? "none" : (c.fixedBuffers ? "registered-fixed" : "arena-unregistered")));
            w.name("registered_files").num(c.resolvedRegisteredFiles);
            w.name("registered_buffers").num(c.resolvedRegisteredBuffers);
            w.name("latency_samples_cap").num(c.latencySamples);
            w.name("seed").num(c.seed);
            w.name("metrics_enabled").bool(c.metrics);
            w.name("metrics_interval_sec").num(c.metricsInterval);
            w.name("label_source").str("cli-or-derived");
        }

        static void renderLatency(Jw w, LatencyStats s) {
            w.obj();
            w.name("samples").num(s.samples);
            w.name("recorded").num(s.recorded);
            w.name("sampled").bool(s.sampled);
            w.name("min").num(s.min);
            w.name("avg").num(s.avg);
            w.name("p50").num(s.p50);
            w.name("p90").num(s.p90);
            w.name("p95").num(s.p95);
            w.name("p99").num(s.p99);
            w.name("p999").num(s.p999);
            w.name("max").num(s.max);
            w.endObj();
        }

        static void renderMetricsInfo(Jw w, MetricsInfo m) {
            if (m == null) {
                w.nul();
                return;
            }
            w.obj();
            w.name("enabled").bool(m.enabled);
            w.name("interval_sec").num(m.intervalSec);
            w.name("ring_series_points").num(m.ringSeriesPoints);
            w.endObj();
        }

        static void renderRun(Jw w, RunStat r) {
            w.obj();
            w.name("run").num(r.run);
            w.name("elapsed_sec").num(r.elapsedSec);
            w.name("ops").num(r.ops);
            w.name("bytes").num(r.bytes);
            w.name("iops").num(r.iops);
            w.name("mibs").num(r.mibs);
            w.name("errors").num(r.errors);
            w.name("seq_overflows").num(r.seqOverflows);
            w.name("metrics");
            renderMetricsInfo(w, r.metrics);
            w.name("latency_us");
            renderLatency(w, r.lat);
            if (r.ringMetrics != null) {
                w.name("ring_metrics");
                renderRingMetricsSum(w, r.ringMetrics);
            }
            if (r.ringQueueDepth != null) {
                w.name("ring_queue_depth");
                renderRingQueueDepth(w, r.ringQueueDepth);
            }
            if (r.dispatchLat != null) {
                w.name("dispatch_latency_us");
                renderDispatchLatency(w, r.dispatchLat);
            }
            w.name("threads").arr();
            for (ThreadStat t : r.threads) {
                renderThread(w, t);
            }
            w.endArr();
            if (r.shards != null) {
                w.name("shards").arr();
                for (ShardInfo s : r.shards) {
                    renderShard(w, s);
                }
                w.endArr();
            }
            w.endObj();
        }

        /** threads[] entry: 1.0 shape + B5 per-worker ring metrics/series/dispatch latency. */
        static void renderThread(Jw w, ThreadStat t) {
            w.obj();
            w.name("thread").num(t.thread);
            w.name("elapsed_sec").num(t.elapsedSec);
            w.name("ops").num(t.ops);
            w.name("bytes").num(t.bytes);
            w.name("errors").num(t.errors);
            w.name("seq_overflows").num(t.seqOverflows);
            w.name("iops").num(t.iops);
            w.name("mibs").num(t.mibs);
            w.name("latency_us");
            renderLatency(w, t.lat);
            if (t.ringMetrics != null) {
                w.name("ring_metrics");
                renderRingMetrics(w, t.ringMetrics);
            }
            if (t.ringSeries != null) {
                w.name("ring_series");
                renderRingSeries(w, t.ringSeries);
            }
            if (t.dispatchLat != null) {
                w.name("dispatch_latency_us");
                renderDispatchLatency(w, t.dispatchLat);
            }
            w.endObj();
        }

        static void renderShard(Jw w, ShardInfo s) {
            ThreadStat t = s.stat;
            w.obj();
            w.name("shard").num(t.thread);
            w.name("elapsed_sec").num(t.elapsedSec);
            w.name("ops").num(t.ops);
            w.name("bytes").num(t.bytes);
            w.name("errors").num(t.errors);
            w.name("seq_overflows").num(t.seqOverflows);
            w.name("iops").num(t.iops);
            w.name("mibs").num(t.mibs);
            w.name("latency_us");
            renderLatency(w, t.lat);
            if (t.ringMetrics != null) {
                w.name("ring_metrics");
                renderRingMetrics(w, t.ringMetrics);
            }
            if (t.ringSeries != null) {
                w.name("ring_series");
                renderRingSeries(w, t.ringSeries);
            }
            if (t.dispatchLat != null) {
                w.name("dispatch_latency_us");
                renderDispatchLatency(w, t.dispatchLat);
            }
            w.name("shard_stats");
            renderShardStats(w, s.stats);
            w.endObj();
        }

        /** All frozen RingMetrics fields, snake_case (docs/b5-design.md §7). */
        static void renderRingMetrics(Jw w, RingMetrics m) {
            if (m == null) {
                w.nul();
                return;
            }
            w.obj();
            w.name("sqes_prepared").num(m.sqesPrepared());
            w.name("submitted").num(m.submitted());
            w.name("reaped").num(m.reaped());
            w.name("completed").num(m.completed());
            w.name("failed").num(m.failed());
            w.name("cancelled").num(m.cancelled());
            w.name("timed_out").num(m.timedOut());
            w.name("in_flight").num(m.inFlight());
            w.name("enters").num(m.enters());
            w.name("submit_batches").num(m.submitBatches());
            w.name("submit_errors").num(m.submitErrors());
            w.name("submit_retries").num(m.submitRetries());
            w.name("sq_full_events").num(m.sqFullEvents());
            w.name("sq_queued").num(m.sqQueued());
            w.name("sq_entries").num(m.sqEntries());
            w.name("cq_ready").num(m.cqReady());
            w.name("cq_entries").num(m.cqEntries());
            w.name("cq_overflow_events").num(m.cqOverflowEvents());
            w.endObj();
        }

        /** Run-level sum of every field over the run's workers (same snake_case names). */
        static void renderRingMetricsSum(Jw w, RingMetricsAgg m) {
            w.obj();
            w.name("sqes_prepared").num(m.sqesPrepared);
            w.name("submitted").num(m.submitted);
            w.name("reaped").num(m.reaped);
            w.name("completed").num(m.completed);
            w.name("failed").num(m.failed);
            w.name("cancelled").num(m.cancelled);
            w.name("timed_out").num(m.timedOut);
            w.name("in_flight").num(m.inFlight);
            w.name("enters").num(m.enters);
            w.name("submit_batches").num(m.submitBatches);
            w.name("submit_errors").num(m.submitErrors);
            w.name("submit_retries").num(m.submitRetries);
            w.name("sq_full_events").num(m.sqFullEvents);
            w.name("sq_queued").num(m.sqQueued);
            w.name("sq_entries").num(m.sqEntries);
            w.name("cq_ready").num(m.cqReady);
            w.name("cq_entries").num(m.cqEntries);
            w.name("cq_overflow_events").num(m.cqOverflowEvents);
            w.name("workers").num(m.workers);
            w.endObj();
        }

        /** measured-phase 1 Hz series: {t_sec,in_flight,sq_queued,cq_ready[,dispatch_p99_us]}. */
        static void renderRingSeries(Jw w, List<SeriesPoint> points) {
            w.arr();
            for (SeriesPoint p : points) {
                w.obj();
                w.name("t_sec").num(p.tSec);
                w.name("in_flight").num(p.inFlight);
                w.name("sq_queued").num(p.sqQueued);
                w.name("cq_ready").num(p.cqReady);
                if (p.hasDispatch) {
                    w.name("dispatch_p99_us").num(DispatchLatencyAgg.us(p.dispatchP99Nanos));
                }
                w.endObj();
            }
            w.endArr();
        }

        /** Library dispatch/completion-latency snapshot in microseconds.
         *  Shard runtime only; runtime completion latency (registration->dispatch,
         *  includes kernel/device completion) -- distinct from external latency_us. */
        static void renderDispatchLatency(Jw w, DispatchLatencyAgg d) {
            w.obj();
            w.name("samples").num(d.samples);
            w.name("mean").num(d.meanUs());
            w.name("min").num(d.minUs());
            w.name("max").num(d.maxUs());
            w.name("p50").num(d.p50Us());
            w.name("p90").num(d.p90Us());
            w.name("p99").num(d.p99Us());
            w.name("p999").num(d.p999Us());
            w.endObj();
        }

        static void renderRingQueueDepth(Jw w, RingQueueDepth q) {
            w.obj();
            w.name("mean_in_flight").num(q.meanInFlight);
            w.name("max_in_flight").num(q.maxInFlight);
            w.name("mean_sq_queued").num(q.meanSqQueued);
            w.name("mean_cq_ready").num(q.meanCqReady);
            w.endObj();
        }

        static void renderShardStats(Jw w, ShardStats s) {
            if (s == null) {
                w.nul();
                return;
            }
            w.obj();
            w.name("shard_id").num(s.shardId());
            w.name("thread_name").str(s.threadName());
            w.name("requested_cpu").num(s.requestedCpu());
            w.name("current_cpu").num(s.currentCpu());
            w.name("affinity_applied").bool(s.affinityApplied());
            w.name("started").bool(s.started());
            w.name("stopping").bool(s.stopping());
            w.name("closed").bool(s.closed());
            w.name("tasks_submitted").num(s.tasksSubmitted());
            w.name("tasks_executed").num(s.tasksExecuted());
            w.name("task_failures").num(s.taskFailures());
            w.name("tasks_rejected").num(s.tasksRejected());
            w.name("completions_registered").num(s.completionsRegistered());
            w.name("completions_dispatched").num(s.completionsDispatched());
            w.name("completions_unattributed").num(s.completionsUnattributed());
            w.name("completion_failures").num(s.completionFailures());
            w.name("handoffs_sent").num(s.handoffsSent());
            w.name("handoffs_inline_fallback").num(s.handoffsInlineFallback());
            w.name("eventfd_writes").num(s.eventfdWrites());
            w.name("ring_submits").num(s.ringSubmits());
            w.name("in_flight_requests").num(s.inFlightRequests());
            w.name("wakeup_armed").bool(s.wakeupArmed());
            w.name("cancelled_at_close").num(s.cancelledAtClose());
            w.name("abandoned_at_close").num(s.abandonedAtClose());
            w.name("close_timed_out").bool(s.closeTimedOut());
            w.endObj();
        }

        static void renderAggregate(Jw w, RunStat[] runs) {
            int n = runs.length;
            long opsTotal = 0, bytesTotal = 0, errorsTotal = 0, overflowsTotal = 0;
            double elapsedMax = 0;
            double[] iops = new double[n];
            double[] mibs = new double[n];
            double[] p50 = new double[n], p90 = new double[n], p95 = new double[n];
            double[] p99 = new double[n], p999 = new double[n], avg = new double[n], max = new double[n], min = new double[n];
            for (int i = 0; i < n; i++) {
                RunStat r = runs[i];
                opsTotal += r.ops;
                bytesTotal += r.bytes;
                errorsTotal += r.errors;
                overflowsTotal += r.seqOverflows;
                elapsedMax = Math.max(elapsedMax, r.elapsedSec);
                iops[i] = r.iops;
                mibs[i] = r.mibs;
                p50[i] = r.lat.p50;
                p90[i] = r.lat.p90;
                p95[i] = r.lat.p95;
                p99[i] = r.lat.p99;
                p999[i] = r.lat.p999;
                avg[i] = r.lat.avg;
                max[i] = r.lat.max;
                min[i] = r.lat.min;
            }
            w.name("runs").num(n);
            w.name("elapsed_sec").num(elapsedMax);
            w.name("ops_total").num(opsTotal);
            w.name("bytes_total").num(bytesTotal);
            w.name("errors_total").num(errorsTotal);
            w.name("seq_overflows_total").num(overflowsTotal);
            w.name("iops").obj();
            w.name("mean").num(mean(iops));
            w.name("stdev").num(stdev(iops));
            w.name("min").num(minOf(iops));
            w.name("max").num(maxOf(iops));
            w.endObj();
            w.name("mibs").obj();
            w.name("mean").num(mean(mibs));
            w.name("stdev").num(stdev(mibs));
            w.name("min").num(minOf(mibs));
            w.name("max").num(maxOf(mibs));
            w.endObj();
            w.name("latency_us").obj();
            w.name("method").str("mean of per-run percentiles (max/min across runs)");
            w.name("avg").num(mean(avg));
            w.name("p50").num(mean(p50));
            w.name("p90").num(mean(p90));
            w.name("p95").num(mean(p95));
            w.name("p99").num(mean(p99));
            w.name("p999").num(mean(p999));
            w.name("min").num(minOf(min));
            w.name("max").num(maxOf(max));
            w.endObj();

            // ---- B5 schema 1.1 aggregate metrics (docs/b5-design.md §7) ----
            // "summed ring counters per run mean": each field is the mean over runs of the
            // per-run sum over workers.
            boolean anyRing = false;
            for (RunStat r : runs) {
                if (r.ringMetrics != null) {
                    anyRing = true;
                    break;
                }
            }
            if (anyRing) {
                w.name("ring_metrics").obj();
                w.name("method").str("mean over runs of the per-run sum over workers");
                renderRingFieldMean(w, runs, "sqes_prepared", a -> a.sqesPrepared);
                renderRingFieldMean(w, runs, "submitted", a -> a.submitted);
                renderRingFieldMean(w, runs, "reaped", a -> a.reaped);
                renderRingFieldMean(w, runs, "completed", a -> a.completed);
                renderRingFieldMean(w, runs, "failed", a -> a.failed);
                renderRingFieldMean(w, runs, "cancelled", a -> a.cancelled);
                renderRingFieldMean(w, runs, "timed_out", a -> a.timedOut);
                renderRingFieldMean(w, runs, "in_flight", a -> a.inFlight);
                renderRingFieldMean(w, runs, "enters", a -> a.enters);
                renderRingFieldMean(w, runs, "submit_batches", a -> a.submitBatches);
                renderRingFieldMean(w, runs, "submit_errors", a -> a.submitErrors);
                renderRingFieldMean(w, runs, "submit_retries", a -> a.submitRetries);
                renderRingFieldMean(w, runs, "sq_full_events", a -> a.sqFullEvents);
                renderRingFieldMean(w, runs, "sq_queued", a -> a.sqQueued);
                renderRingFieldMean(w, runs, "sq_entries", a -> a.sqEntries);
                renderRingFieldMean(w, runs, "cq_ready", a -> a.cqReady);
                renderRingFieldMean(w, runs, "cq_entries", a -> a.cqEntries);
                renderRingFieldMean(w, runs, "cq_overflow_events", a -> a.cqOverflowEvents);
                w.endObj();
            }
            int queueRuns = 0;
            double qMeanInFlight = 0, qMeanSq = 0, qMeanCq = 0;
            long qMaxInFlight = 0;
            for (RunStat r : runs) {
                if (r.ringQueueDepth == null) {
                    continue;
                }
                queueRuns++;
                qMeanInFlight += r.ringQueueDepth.meanInFlight;
                qMeanSq += r.ringQueueDepth.meanSqQueued;
                qMeanCq += r.ringQueueDepth.meanCqReady;
                qMaxInFlight = Math.max(qMaxInFlight, r.ringQueueDepth.maxInFlight);
            }
            if (queueRuns > 0) {
                w.name("ring_queue_depth").obj();
                w.name("method").str("series means over workers; max over all series points");
                w.name("mean_in_flight").num(qMeanInFlight / queueRuns);
                w.name("max_in_flight").num(qMaxInFlight);
                w.name("mean_sq_queued").num(qMeanSq / queueRuns);
                w.name("mean_cq_ready").num(qMeanCq / queueRuns);
                w.endObj();
            }
            int dispatchRuns = 0;
            double dSamples = 0, dAvg = 0, dP50 = 0, dP90 = 0, dP99 = 0, dP999 = 0;
            double dMin = Double.POSITIVE_INFINITY, dMax = 0;
            for (RunStat r : runs) {
                if (r.dispatchLat == null) {
                    continue;
                }
                dispatchRuns++;
                dSamples += r.dispatchLat.samples;
                dAvg += r.dispatchLat.meanUs();
                dP50 += r.dispatchLat.p50Us();
                dP90 += r.dispatchLat.p90Us();
                dP99 += r.dispatchLat.p99Us();
                dP999 += r.dispatchLat.p999Us();
                dMin = Math.min(dMin, r.dispatchLat.minUs());
                dMax = Math.max(dMax, r.dispatchLat.maxUs());
            }
            if (dispatchRuns > 0) {
                w.name("dispatch_latency_us").obj();
                w.name("method").str("mean of per-run percentiles (max/min across runs)");
                w.name("samples").num(dSamples / dispatchRuns);
                w.name("avg").num(dAvg / dispatchRuns);
                w.name("p50").num(dP50 / dispatchRuns);
                w.name("p90").num(dP90 / dispatchRuns);
                w.name("p99").num(dP99 / dispatchRuns);
                w.name("p999").num(dP999 / dispatchRuns);
                w.name("min").num(dMin);
                w.name("max").num(dMax);
                w.endObj();
            }
        }

        static void renderRingFieldMean(Jw w, RunStat[] runs, String name,
                                        java.util.function.ToLongFunction<RingMetricsAgg> field) {
            int n = 0;
            double sum = 0;
            for (RunStat r : runs) {
                if (r.ringMetrics == null) {
                    continue;
                }
                sum += field.applyAsLong(r.ringMetrics);
                n++;
            }
            w.name(name).num(n == 0 ? 0.0 : sum / n);
        }

        static double mean(double[] v) {
            double s = 0;
            for (double x : v) {
                s += x;
            }
            return s / v.length;
        }

        static double stdev(double[] v) {
            if (v.length < 2) {
                return 0;
            }
            double m = mean(v);
            double s = 0;
            for (double x : v) {
                double d = x - m;
                s += d * d;
            }
            return Math.sqrt(s / (v.length - 1));
        }

        static double minOf(double[] v) {
            double m = v[0];
            for (double x : v) {
                m = Math.min(m, x);
            }
            return m;
        }

        static double maxOf(double[] v) {
            double m = v[0];
            for (double x : v) {
                m = Math.max(m, x);
            }
            return m;
        }
    }

    private JUringFileBench() {
    }
}
