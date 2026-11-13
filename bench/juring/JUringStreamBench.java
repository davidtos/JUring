/*
 * JUringStreamBench - standalone stream-I/O benchmark for the B2 stream layer.
 *
 * Purpose
 * -------
 * Companion of bench/juring/JUringFileBench.java for the buffered stream layer
 * (`com.davidvlijmincx.lio.api.JuringInputStream` / `JuringOutputStream`), and
 * the JUring side of the B2 W21/W22 comparison against seastar `fstream`
 * (bench/seastar/seastar_stream_bench). Contract: docs/b2-design.md §3.1
 * (frozen CLI + JSON schema). This class does NOT touch JUringFileBench.java.
 *
 * Workloads (one thread; pin externally with `taskset -c 2`):
 *   stream_read  : repeat passes until the phase deadline. Each pass opens a
 *                  fresh JuringInputStream over [0, dataset), calls readChunk()
 *                  until it returns an empty segment (EOF), then closes the
 *                  stream. Ops = chunks, bytes = chunk bytes.
 *   stream_write : repeat passes until the phase deadline. Each pass opens a
 *                  fresh JuringOutputStream, writes exactly `dataset` bytes as
 *                  `bs`-sized chunks from a reused 4096-aligned source buffer
 *                  (filled deterministically once per pass), flush + close.
 *                  Ops = write() calls, bytes = bytes accepted. Stops cleanly
 *                  mid-pass at the deadline (chunk boundary; stream is drained
 *                  by close()).
 *
 * Ring / files / buffers (mirrors B1 + design §3):
 *   - one JUring per run: entries = max(256, 2*qd),
 *     flags SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN, one owner thread;
 *   - JuringFile.withInFlightCapacity(max(64, 2*qd + 8));
 *   - open READ(+DIRECT) or WRITE|CREATE(+DIRECT) depending on --buffered;
 *   - JuringFile.register() unless --no-register-file;
 *   - input options : bufferSize=bs, readAhead=qd-1, dynamicAdjustments=false,
 *                     alignment=4096  (deterministic: adaptation off);
 *   - output options: bufferSize=bs, writeBehind=qd, alignment=4096,
 *                     preallocation=0, syncOnClose=false;
 *   - streams allocate their own aligned native buffers: fixed_buffers=false.
 *
 * Latency: System.nanoTime() is sampled around every readChunk()/write()
 * (including any wait for an in-flight operation), exactly like
 * JUringFileBench: bounded uniform reservoir (Algorithm R), percentiles from
 * the sorted reservoir, exact mean from a running sum. The default cap is
 * 1,000,000 samples per run (JSON `config.latency_samples_cap`; not a CLI flag
 * because §3.1's CLI is frozen).
 *
 * Output: exactly ONE JSON object on stdout (schema_version "1.0", engine
 * "juring", engine_detail "JUringStreamBench"); diagnostics on stderr;
 * `--json PATH` also writes the object. mibs = MiB/s over measured bytes and
 * measured elapsed; iops = ops/s over the same window.
 *
 * Build/run: bench/juring/run-juring-stream.sh (compiles against target/classes).
 */

package bench.juring;

import com.davidvlijmincx.lio.api.InputStreamOptions;
import com.davidvlijmincx.lio.api.IoUringOptions;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.JuringException;
import com.davidvlijmincx.lio.api.JuringFile;
import com.davidvlijmincx.lio.api.JuringInputStream;
import com.davidvlijmincx.lio.api.JuringOutputStream;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import com.davidvlijmincx.lio.api.OutputStreamOptions;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;

public final class JUringStreamBench {

    /** Bumped only on incompatible JSON changes (matches JUringFileBench/seastar). */
    static final String SCHEMA_VERSION = "1.0";
    static final String ENGINE = "juring";
    static final String ENGINE_DETAIL = "JUringStreamBench";

    /** io_uring setup flags used for the ring (§3.1). */
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

    /** Stream buffer/write alignment (design §1.5 / §4.4). */
    static final int ALIGNMENT = 4096;

    /** Uniform reservoir cap per run; not a CLI flag (frozen §3.1 CLI). */
    static final int LATENCY_SAMPLES = 1_000_000;

    public static void main(String[] args) {
        Config cfg = null;
        Manifest manifest = null;
        Dataset ds = null;
        int exitCode = 0;
        RunStat[] runs = new RunStat[0];
        String error = null;

        try {
            cfg = Config.parse(args);
            if (cfg.help) {
                System.out.println(usage());
                return;
            }
            manifest = Manifest.collect(args);
            ds = Dataset.prepare(cfg);
            printPreamble(cfg, ds);

            List<RunStat> done = new ArrayList<>();
            for (int r = 0; r < cfg.runs; r++) {
                done.add(runOnce(cfg, ds, r));
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
    // CLI (frozen §3.1)
    // ------------------------------------------------------------------

    enum Workload {
        STREAM_READ("stream_read", true),
        STREAM_WRITE("stream_write", false);

        final String cliName;
        final boolean read;

        Workload(String cliName, boolean read) {
            this.cliName = cliName;
            this.read = read;
        }

        boolean isRead() {
            return read;
        }

        boolean isWrite() {
            return !read;
        }

        static Workload from(String s) throws UsageException {
            for (Workload w : values()) {
                if (w.cliName.equalsIgnoreCase(s)) {
                    return w;
                }
            }
            throw new UsageException("unknown --workload '" + s + "' (expected stream_read|stream_write)");
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
        Workload workload = null;          // required
        String file = null;                // required
        long size = -1;                    // <=0/unset => whole file
        int bs = 128 * 1024;               // stream buffer size
        int qd = 8;                        // stream concurrency = in-flight depth
        double seconds = 10.0;
        double warmupSec = 2.0;
        int runs = 1;
        boolean readonly = false;
        boolean buffered = false;          // default: O_DIRECT
        boolean noRegisterFile = false;
        String jsonPath = null;
        String label = null;
        long seed = 20261113L;             // deterministic dataset fill by default

        // Resolved at runtime, mirrored in the JSON.
        int resolvedRegisteredFiles = 0;
        boolean resolvedRegisterAttempted = false;
        String resolvedRegisterError = null;

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
                        c.workload = Workload.from(value(argv, i, key, inline));
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
                    case "--seconds":
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
                    case "--buffered":
                        if (inline != null) throw new UsageException("--buffered takes no value");
                        c.buffered = true;
                        break;
                    case "--no-register-file":
                        if (inline != null) throw new UsageException("--no-register-file takes no value");
                        c.noRegisterFile = true;
                        break;
                    case "--json":
                        c.jsonPath = value(argv, i, key, inline);
                        if (inline == null) i++;
                        break;
                    case "--label":
                        c.label = value(argv, i, key, inline);
                        if (inline == null) i++;
                        break;
                    case "--seed":
                        c.seed = longArg(value(argv, i, key, inline));
                        if (inline == null) i++;
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

        /** Parses byte sizes with binary suffixes: 128k, 1M, 1MiB, 10g, plain bytes. */
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
            if (workload == null) {
                throw new UsageException("--workload is required (stream_read|stream_write)");
            }
            if (file == null || file.isBlank()) {
                throw new UsageException("--file is required");
            }
            if (bs < 512 || (bs & 511) != 0) {
                throw new UsageException("--bs must be a multiple of 512 (direct-I/O sector size), got " + bs);
            }
            if (bs > (64 << 20)) {
                throw new UsageException("--bs too large (max 64 MiB)");
            }
            if (qd < 1 || qd > 1024) {
                throw new UsageException("--qd must be in [1, 1024] (readAhead=qd-1, writeBehind=qd)");
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
            if (workload.isWrite() && readonly) {
                throw new UsageException("--readonly cannot be combined with stream_write");
            }
            if (size > 0 && size < bs) {
                throw new UsageException("--size must be >= --bs");
            }
            if (label == null || label.isBlank()) {
                label = defaultLabel();
            }
        }

        String defaultLabel() {
            String s = workload.cliName + "_" + humanSize(bs) + "_qd" + qd;
            if (buffered) s += "_buffered";
            if (noRegisterFile) s += "_noreg";
            return s;
        }
    }

    static long alignUp(long value, long alignment) {
        long mask = alignment - 1;
        return (value + mask) & ~mask;
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
                "JUringStreamBench - JUring B2 stream benchmark (JuringInputStream/JuringOutputStream)",
                "",
                "Usage:",
                "  java --enable-native-access=ALL-UNNAMED -cp target/classes:bench/juring/out \\",
                "       bench.juring.JUringStreamBench [flags]",
                "",
                "Flags:",
                "  --workload stream_read|stream_write   required",
                "  --file PATH             existing file (O_DIRECT by default; never extended)",
                "  --bs SIZE               stream buffer size (default 128k; multiple of 512)",
                "  --qd N                  stream concurrency = in-flight depth (default 8)",
                "  --size SIZE             dataset bytes per pass (default: file size; must be",
                "                          <= file size; write: multiple of alignUp(bs,4096))",
                "  --seconds S             measured duration per run (default 10)",
                "  --warmup-sec S          warmup before each measured phase (default 2)",
                "  --runs N                repetitions (default 1)",
                "  --readonly              assert read-only (stream_read only)",
                "  --buffered              open without O_DIRECT (default: O_DIRECT)",
                "  --no-register-file      do not ring-register the file (default: register)",
                "  --json PATH             also write the JSON report to PATH",
                "  --label TEXT            case label for reports (default derived)",
                "  --seed N                deterministic fill/PRNG seed (default 20261113)",
                "  --help                  this text",
                "",
                "Ring: entries=max(256,2*qd); flags SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN.",
                "Streams: buffer_size=bs, readAhead=qd-1 (input) / writeBehind=qd (output),",
                "         alignment=4096, dynamic adjustments disabled (deterministic).",
                "Output: one JSON object on stdout; diagnostics on stderr.",
                "Example:",
                "  bench/juring/run-juring-stream.sh --workload stream_write \\",
                "      --file nvme-data/write_files/single_file_bench_w.bin --bs 128k --qd 8 \\",
                "      --size 1g --seconds 3 --warmup-sec 1 --runs 1 --label w21_smoke",
                "");
    }

    // ------------------------------------------------------------------
    // Dataset
    // ------------------------------------------------------------------

    static final class Dataset {
        final Path file;
        final long fileSize;      // size on disk (never changed by this runner)
        final long dataset;       // bytes per pass
        final boolean writable;

        Dataset(Path file, long fileSize, long dataset, boolean writable) {
            this.file = file;
            this.fileSize = fileSize;
            this.dataset = dataset;
            this.writable = writable;
        }

        static Dataset prepare(Config cfg) throws UsageException, IOException {
            Path p = Path.of(cfg.file).toAbsolutePath().normalize();
            if (!Files.exists(p)) {
                throw new UsageException("file does not exist: " + p);
            }
            if (!Files.isRegularFile(p)) {
                throw new UsageException("not a regular file: " + p);
            }
            long actual = Files.size(p);
            long dataset = cfg.size > 0 ? cfg.size : actual;
            if (dataset > actual) {
                throw new UsageException("--size " + dataset + " exceeds file size " + actual
                        + "; this runner never extends or preconditions the shared dataset");
            }
            if (dataset < cfg.bs) {
                throw new UsageException("dataset (" + dataset + " B) is smaller than --bs (" + cfg.bs + " B)");
            }
            if (cfg.workload.isWrite()) {
                if (!Files.isWritable(p)) {
                    throw new UsageException("write workload but file is not writable: " + p);
                }
                long a = alignUp(cfg.bs, ALIGNMENT);
                if (dataset % a != 0) {
                    throw new UsageException("write dataset (" + dataset + " B) must be a multiple of alignUp(bs,"
                            + ALIGNMENT + ")=" + a + " B: the output stream truncates to its logical length"
                            + " at flush, so a partial tail would change the shared dataset's size");
                }
            }
            return new Dataset(p, actual, dataset, cfg.workload.isWrite());
        }
    }

    static void printPreamble(Config cfg, Dataset ds) {
        System.err.printf("[juring-stream] label=%s workload=%s file=%s fileSize=%,d dataset=%,d%s%n",
                cfg.label, cfg.workload.cliName, ds.file, ds.fileSize, ds.dataset,
                ds.writable ? " (writable)" : " (read-only)");
        System.err.printf("[juring-stream] bs=%d qd=%d runs=%d seconds=%.2f warmup=%.2f buffered=%s registerFile=%s%n",
                cfg.bs, cfg.qd, cfg.runs, cfg.seconds, cfg.warmupSec, cfg.buffered, !cfg.noRegisterFile);
        System.err.printf("[juring-stream] ring flags=%s entries=max(256,2*qd) inFlightCapacity=max(64,2*qd+8)"
                        + " stream: bufferSize=%d %s=%d alignment=%d dynamicAdjustments=false%n",
                java.util.Arrays.toString(RING_FLAG_NAMES), cfg.bs,
                cfg.workload.isRead() ? "readAhead" : "writeBehind",
                cfg.workload.isRead() ? cfg.qd - 1 : cfg.qd, ALIGNMENT);
    }

    // ------------------------------------------------------------------
    // One run = fresh ring/file, warmup phase, measured phase
    // ------------------------------------------------------------------

    static RunStat runOnce(Config cfg, Dataset ds, int runIndex) throws BenchException {
        long seed = cfg.seed != 0L ? cfg.seed
                : (System.nanoTime() ^ ((long) (runIndex + 1) * 0x9E3779B97F4A7C15L));
        int ringEntries = Math.max(256, cfg.qd * 2);
        int inFlightCapacity = Math.max(64, 2 * cfg.qd + 8);

        InputStreamOptions inOptions = null;
        OutputStreamOptions outOptions = null;
        if (cfg.workload.isRead()) {
            inOptions = InputStreamOptions.defaults()
                    .withBufferSize(cfg.bs)
                    .withReadAhead(cfg.qd - 1)
                    .withAlignment(ALIGNMENT)
                    .withDynamicAdjustments(false);
        } else {
            outOptions = OutputStreamOptions.defaults()
                    .withBufferSize(cfg.bs)
                    .withWriteBehind(cfg.qd)
                    .withAlignment(ALIGNMENT)
                    .withPreallocationSize(0L)
                    .withSyncOnClose(false);
        }

        try (JUring ring = new JUring(ringEntries, RING_FLAGS)) {
            JuringFile file;
            try {
                file = openFile(ring, cfg, ds);
            } catch (JuringException e) {
                throw new BenchException("open " + ds.file + " failed: " + describe(e));
            }
            try {
                file.withInFlightCapacity(inFlightCapacity);
                if (!cfg.noRegisterFile) {
                    cfg.resolvedRegisterAttempted = true;
                    try {
                        int slot = file.register();
                        cfg.resolvedRegisteredFiles = slot >= 0 ? 1 : 0;
                    } catch (RuntimeException e) {
                        // Registering is a disclosure/optimization, not a correctness
                        // requirement. Record the fallback rather than aborting.
                        cfg.resolvedRegisterError = describe(e);
                        System.err.println("[juring-stream] WARNING: file registration failed ("
                                + cfg.resolvedRegisterError + "); continuing with plain file descriptors");
                    }
                }

                Arena sourceArena = null;
                MemorySegment source = null;
                try {
                    if (cfg.workload.isWrite()) {
                        sourceArena = Arena.ofConfined();
                        source = sourceArena.allocate(cfg.bs, ALIGNMENT);
                    }

                    Phase warmup = null;
                    if (cfg.warmupSec > 0) {
                        warmup = runPhase(cfg, file, inOptions, outOptions, source, ds.dataset,
                                seed, runIndex, false, cfg.warmupSec);
                        System.err.printf(Locale.ROOT,
                                "[juring-stream] warmup: %.2f s, %d passes, %,d chunks%n",
                                warmup.elapsedSec, warmup.passes, warmup.opsTotal + warmup.unmeasuredOps);
                    }
                    Phase measured = runPhase(cfg, file, inOptions, outOptions, source, ds.dataset,
                            seed, runIndex, true, cfg.seconds);
                    if (measured.elapsedSec <= 0 || measured.opsTotal == 0) {
                        throw new BenchException("measured phase completed no chunks");
                    }
                    double iops = measured.opsTotal / measured.elapsedSec;
                    double mibs = (measured.bytesTotal / (1024.0 * 1024.0)) / measured.elapsedSec;
                    RunStat st = new RunStat(runIndex, measured.elapsedSec, measured.opsTotal,
                            measured.bytesTotal, measured.errorsTotal, measured.passes, iops, mibs,
                            measured.lat);
                    System.err.printf(Locale.ROOT,
                            "[juring-stream] run %d/%d: %.2f s, %,d chunks, %,d passes, %.1f MiB/s, "
                                    + "%.1f chunks/s, p50=%.1f us p99=%.1f us p999=%.1f us max=%.1f us, errors=%d%n",
                            runIndex + 1, cfg.runs, st.seconds, st.opsTotal, st.passes, st.mibs, st.iops,
                            st.lat.p50, st.lat.p99, st.lat.p999, st.lat.max, st.errorsTotal);
                    return st;
                } catch (JuringException e) {
                    throw new BenchException("run " + (runIndex + 1) + " I/O failed: " + describe(e));
                } finally {
                    if (sourceArena != null) {
                        sourceArena.close();
                    }
                }
            } finally {
                file.close();
            }
        }
    }

    static JuringFile openFile(JUring ring, Config cfg, Dataset ds) throws JuringException {
        if (cfg.workload.isWrite()) {
            if (cfg.buffered) {
                return JuringFile.open(ring, ds.file, LinuxOpenOptions.WRITE, LinuxOpenOptions.CREATE);
            }
            return JuringFile.open(ring, ds.file, LinuxOpenOptions.WRITE, LinuxOpenOptions.CREATE,
                    LinuxOpenOptions.DIRECT);
        }
        if (cfg.buffered) {
            return JuringFile.open(ring, ds.file, LinuxOpenOptions.READ);
        }
        return JuringFile.open(ring, ds.file, LinuxOpenOptions.READ, LinuxOpenOptions.DIRECT);
    }

    static final class BenchException extends Exception {
        BenchException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Phase = passes until deadline
    // ------------------------------------------------------------------

    static final class Phase {
        long opsTotal;
        long bytesTotal;
        long errorsTotal;
        long passes;
        long unmeasuredOps;          // warmup: chunks executed (diagnostics only)
        double elapsedSec;
        LatencyStats lat = LatencyStats.empty();
    }

    static Phase runPhase(Config cfg, JuringFile file, InputStreamOptions inOptions,
                          OutputStreamOptions outOptions, MemorySegment source, long dataset,
                          long seed, int runIndex, boolean timed, double seconds) throws JuringException {
        Phase p = new Phase();
        if (!(seconds > 0)) {
            return p;
        }
        Reservoir reservoir = timed ? new Reservoir(LATENCY_SAMPLES) : null;
        long start = System.nanoTime();
        long deadline = start + (long) (seconds * 1_000_000_000L);
        long lastCounted = start;
        long passNo = 0;

        phaseLoop:
        while (true) {
            long now = System.nanoTime();
            if (now >= deadline) {
                break;
            }
            passNo++;
            if (cfg.workload.isRead()) {
                JuringInputStream in = JuringInputStream.open(file, 0L, dataset, inOptions);
                try {
                    while (true) {
                        long t0 = System.nanoTime();
                        MemorySegment chunk = in.readChunk();
                        long t1 = System.nanoTime();
                        int n = (int) chunk.byteSize();
                        if (n == 0) {
                            break;                     // EOF: pass complete
                        }
                        if (timed) {
                            if (t1 <= deadline) {
                                p.opsTotal++;
                                p.bytesTotal += n;
                                reservoir.record(t1 - t0);
                                lastCounted = t1;
                            }
                        } else {
                            p.unmeasuredOps++;
                        }
                        if (t1 >= deadline) {
                            break phaseLoop;           // stop mid-pass; finally drains/closes
                        }
                    }
                } finally {
                    in.close();
                }
            } else {
                JuringOutputStream out = JuringOutputStream.open(file, outOptions);
                try {
                    fillPattern(source, seed ^ (passNo * 0x9E3779B97F4A7C15L));
                    long remaining = dataset;
                    while (remaining > 0) {
                        int len = (int) Math.min(cfg.bs, remaining);
                        long t0 = System.nanoTime();
                        out.write(source.asSlice(0, len));
                        long t1 = System.nanoTime();
                        if (timed) {
                            if (t1 <= deadline) {
                                p.opsTotal++;
                                p.bytesTotal += len;
                                reservoir.record(t1 - t0);
                                lastCounted = t1;
                            }
                        } else {
                            p.unmeasuredOps++;
                        }
                        remaining -= len;
                        if (t1 >= deadline) {
                            break phaseLoop;           // stop mid-pass; close() flushes+drains
                        }
                    }
                } finally {
                    out.close();
                }
            }
        }

        p.passes = passNo;
        if (timed) {
            p.elapsedSec = (lastCounted - start) / 1_000_000_000.0;
            p.lat = reservoir.toStats();
        } else {
            p.elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0;
        }
        return p;
    }

    /** Deterministic per-pass source pattern (xorshift64), written into the aligned buffer. */
    static void fillPattern(MemorySegment buffer, long seed) {
        long n = buffer.byteSize();
        long x = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
        long off = 0;
        while (off + 8 <= n) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            buffer.set(ValueLayout.JAVA_LONG, off, x);
            off += 8;
        }
        while (off < n) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            buffer.set(ValueLayout.JAVA_BYTE, off, (byte) x);
            off++;
        }
    }

    // ------------------------------------------------------------------
    // Latency reservoir (uniform Algorithm R, as JUringFileBench/seastar)
    // ------------------------------------------------------------------

    static final class Reservoir {
        final long[] samples;
        int count;
        long seen;
        long sumNs;
        long rng;

        Reservoir(int cap) {
            this.samples = new long[Math.max(1, cap)];
            this.rng = 0x9E3779B97F4A7C15L;
        }

        void record(long ns) {
            sumNs += ns;
            if (count < samples.length) {
                samples[count++] = ns;
            } else {
                long r = rng;
                r ^= r >>> 12;
                r ^= r << 25;
                r ^= r >>> 27;
                rng = r;
                long idx = Long.remainderUnsigned(r, seen + 1);
                if (idx < count) {
                    samples[(int) idx] = ns;
                }
            }
            seen++;
        }

        LatencyStats toStats() {
            if (count == 0) {
                return LatencyStats.empty();
            }
            long[] copy = java.util.Arrays.copyOf(samples, count);
            java.util.Arrays.sort(copy);
            return LatencyStats.fromSorted(copy, sumNs, seen, seen > count);
        }
    }

    // ------------------------------------------------------------------
    // Result structs
    // ------------------------------------------------------------------

    static final class LatencyStats {
        long recorded;
        int samples;
        boolean sampled;
        double avg, p50, p90, p95, p99, p999, max;

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
            s.max = us(sorted[sorted.length - 1]);
            s.avg = recorded > 0 ? us(sumNs / (double) recorded) : us(sorted[0]);
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

    static final class RunStat {
        final int run;
        final double seconds;
        final long opsTotal;
        final long bytesTotal;
        final long errorsTotal;
        final long passes;
        final double iops;
        final double mibs;
        final LatencyStats lat;

        RunStat(int run, double seconds, long opsTotal, long bytesTotal, long errorsTotal,
                long passes, double iops, double mibs, LatencyStats lat) {
            this.run = run;
            this.seconds = seconds;
            this.opsTotal = opsTotal;
            this.bytesTotal = bytesTotal;
            this.errorsTotal = errorsTotal;
            this.passes = passes;
            this.iops = iops;
            this.mibs = mibs;
            this.lat = lat;
        }
    }

    // ------------------------------------------------------------------
    // Manifest
    // ------------------------------------------------------------------

    static final class Manifest {
        String timestampUtc = Instant.now().toString();
        String gitCommit = env("JURING_GIT_COMMIT");
        String gitDirty = env("JURING_GIT_DIRTY");
        String gitVersion = env("JURING_GIT_VERSION");
        String jdk = System.getProperty("java.version") + " (" + System.getProperty("java.vm.version") + ")";
        String javaHome = System.getProperty("java.home");
        String kernel = readTrimmed(Path.of("/proc/sys/kernel/osrelease"));
        String hostname = readTrimmed(Path.of("/proc/sys/kernel/hostname"));
        String cpuModel = readCpuModel();
        int logicalCpus = Runtime.getRuntime().availableProcessors();
        String cpusAllowed = procStatus("Cpus_allowed_list");
        String loadavgAtStart = readTrimmed(Path.of("/proc/loadavg"));
        String cpuGovernor = readTrimmed(Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"));
        String commandLine = "";

        static Manifest collect(String[] argv) {
            Manifest m = new Manifest();
            m.commandLine = String.join(" ", argv);
            if (m.gitCommit == null) {
                m.gitCommit = firstLine(runCommand("git", "rev-parse", "HEAD"));
            }
            if (m.gitDirty == null) {
                String out = runCommand("git", "status", "--porcelain");
                if (out != null) {
                    m.gitDirty = out.isBlank() ? "false" : "true";
                }
            }
            if (m.gitVersion == null) {
                m.gitVersion = firstLine(runCommand("git", "describe", "--tags", "--always", "--dirty"));
            }
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
    // Minimal JSON writer (no dependencies; same style as JUringFileBench)
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
    // JSON report (frozen §3.1 shape)
    // ------------------------------------------------------------------

    static final class Report {

        static String render(Config cfg, Dataset ds, Manifest m, RunStat[] runs, String error, String status) {
            Jw w = new Jw();
            w.obj();
            w.name("schema_version").str(SCHEMA_VERSION);
            w.name("engine").str(ENGINE);
            w.name("engine_detail").str(ENGINE_DETAIL);
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
            w.name("kernel").str(m.kernel);
            w.name("cpu_model").str(m.cpuModel);
            w.name("hostname").str(m.hostname);
            w.name("git_commit").str(m.gitCommit);
            w.name("git_dirty").str(m.gitDirty);
            w.name("git_version").str(m.gitVersion);
            w.name("jdk").str(m.jdk);
            w.name("java_home").str(m.javaHome);
            w.name("command_line").str(m.commandLine);
            w.name("timestamp_utc").str(m.timestampUtc);
            w.name("logical_cpus").num(m.logicalCpus);
            w.name("cpus_allowed").str(m.cpusAllowed);
            w.name("loadavg_at_start").str(m.loadavgAtStart);
            w.name("loadavg_at_end").str(Manifest.readTrimmed(Path.of("/proc/loadavg")));
            w.name("cpu_governor").str(m.cpuGovernor);
        }

        static void renderConfig(Jw w, Config c, Dataset ds) {
            if (c == null) {
                return;
            }
            w.name("workload").str(c.workload != null ? c.workload.cliName : null);
            w.name("file").str(ds != null ? ds.file.toString() : null);
            w.name("file_size_bytes").num(ds != null ? ds.fileSize : 0);
            w.name("dataset_bytes").num(ds != null ? ds.dataset : 0);
            w.name("bs").num(c.bs);
            w.name("qd").num(c.qd);
            w.name("buffer_size").num(c.bs);
            w.name("concurrency").num(c.qd);
            w.name("seconds").num(c.seconds);
            w.name("warmup_sec").num(c.warmupSec);
            w.name("runs").num(c.runs);
            w.name("seed").num(c.seed);
            w.name("readonly").bool(c.workload != null && c.workload.isRead());
            w.name("direct_io").bool(!c.buffered);
            w.name("ring_entries").num(Math.max(256, c.qd * 2));
            w.name("ring_flags").arr();
            for (String f : RING_FLAG_NAMES) {
                w.str(f);
            }
            w.endArr();
            w.name("registered_files").num(c.resolvedRegisteredFiles);
            w.name("fixed_buffers").bool(false);
            w.name("stream_buffer_size").num(c.bs);
            w.name("stream_concurrency").num(c.qd);
            w.name("stream_alignment").num(ALIGNMENT);
            w.name("stream_dynamic_adjustments").bool(false);
            w.name("file_register_attempted").bool(c.resolvedRegisterAttempted);
            w.name("file_register_error").str(c.resolvedRegisterError);
            w.name("latency_samples_cap").num(LATENCY_SAMPLES);
            w.name("in_flight_capacity").num(Math.max(64, 2 * c.qd + 8));
        }

        static void renderLatency(Jw w, LatencyStats s) {
            w.obj();
            w.name("samples").num(s.samples);
            w.name("sampled").bool(s.sampled);
            w.name("avg").num(s.avg);
            w.name("p50").num(s.p50);
            w.name("p90").num(s.p90);
            w.name("p95").num(s.p95);
            w.name("p99").num(s.p99);
            w.name("p999").num(s.p999);
            w.name("max").num(s.max);
            w.endObj();
        }

        static void renderRun(Jw w, RunStat r) {
            w.obj();
            w.name("run").num(r.run + 1);
            w.name("seconds").num(r.seconds);
            w.name("ops_total").num(r.opsTotal);
            w.name("bytes_total").num(r.bytesTotal);
            w.name("errors_total").num(r.errorsTotal);
            w.name("passes").num(r.passes);
            w.name("iops").num(r.iops);
            w.name("mibs").num(r.mibs);
            w.name("latency_us");
            renderLatency(w, r.lat);
            w.endObj();
        }

        static void renderAggregate(Jw w, RunStat[] runs) {
            int n = runs.length;
            long opsTotal = 0, bytesTotal = 0, errorsTotal = 0, passesTotal = 0;
            double elapsedMax = 0;
            double[] iops = new double[n];
            double[] mibs = new double[n];
            double[] p50 = new double[n], p90 = new double[n], p95 = new double[n];
            double[] p99 = new double[n], p999 = new double[n], avg = new double[n], max = new double[n];
            int samples = 0;
            boolean sampled = false;
            for (int i = 0; i < n; i++) {
                RunStat r = runs[i];
                opsTotal += r.opsTotal;
                bytesTotal += r.bytesTotal;
                errorsTotal += r.errorsTotal;
                passesTotal += r.passes;
                elapsedMax = Math.max(elapsedMax, r.seconds);
                iops[i] = r.iops;
                mibs[i] = r.mibs;
                p50[i] = r.lat.p50;
                p90[i] = r.lat.p90;
                p95[i] = r.lat.p95;
                p99[i] = r.lat.p99;
                p999[i] = r.lat.p999;
                avg[i] = r.lat.avg;
                max[i] = r.lat.max;
                samples += r.lat.samples;
                sampled = sampled || r.lat.sampled;
            }
            w.name("runs").num(n);
            w.name("elapsed_sec").num(elapsedMax);
            w.name("ops_total").num(opsTotal);
            w.name("bytes_total").num(bytesTotal);
            w.name("errors_total").num(errorsTotal);
            w.name("passes_total").num(passesTotal);
            w.name("iops").obj();
            w.name("mean").num(mean(iops));
            w.name("stdev").num(stdev(iops));
            w.name("min").num(minOf(iops));
            w.name("max").num(maxOf(iops));
            w.endObj();
            w.name("mibs").obj();
            w.name("mean").num(mean(mibs));
            w.name("sd").num(stdev(mibs));
            w.name("ci95").num(ci95(mibs));
            w.name("min").num(minOf(mibs));
            w.name("max").num(maxOf(mibs));
            w.endObj();
            w.name("latency_us").obj();
            w.name("method").str("mean of per-run percentiles (max across runs for max)");
            w.name("samples").num(samples);
            w.name("sampled").bool(sampled);
            w.name("avg").num(mean(avg));
            w.name("p50").num(mean(p50));
            w.name("p90").num(mean(p90));
            w.name("p95").num(mean(p95));
            w.name("p99").num(mean(p99));
            w.name("p999").num(mean(p999));
            w.name("max").num(maxOf(max));
            w.endObj();
        }

        /** Two-sided 95% half-width (Student t). 0 for a single run. */
        static double ci95(double[] v) {
            int n = v.length;
            if (n < 2) {
                return 0.0;
            }
            double sd = stdev(v);
            return tCritical975(n - 1) * sd / Math.sqrt(n);
        }

        static double tCritical975(int df) {
            double[] t = {
                    12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
                    2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
                    2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042
            };
            if (df < 1) {
                return Double.NaN;
            }
            if (df <= t.length) {
                return t[df - 1];
            }
            return 1.96;
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

    private JUringStreamBench() {
    }
}
