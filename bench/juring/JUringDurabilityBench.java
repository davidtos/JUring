/*
 * JUringDurabilityBench - group-commit durability micro-benchmark.
 *
 * Purpose
 * -------
 * Dedicated JUring-only runner that (a) proves the GroupCommit contract on the device
 * ("one fsync per committed batch" and "data visible after a fresh process re-reads it")
 * and (b) measures the value of batching: N writes per one fdatasync/fsync, batch sizes
 * 1/8/32/128, durability fdatasync|fsync|writeback.
 *
 * The bench calls the PUBLIC GroupCommit API only (GroupCommitOptions/GroupCommit/
 * GroupCommitStats); no test hook or internal seam is used.
 *
 * Workload (one group = one batch)
 * --------------------------------
 *   The file is preallocated (setLength; sparse) to --file-size bytes, `--bs`-aligned
 *   (default 1 GiB when both the file and --file-size are absent). For each group the
 *   bench fills `batch` distinct aligned buffers with a deterministic per-offset pattern
 *   (xorshift64 seeded by --seed and the 4096-aligned position index), calls
 *   GroupCommit.write(offset, buffer) once per buffer at consecutive aligned offsets,
 *   then one GroupCommit.commit(). Offsets advance sequentially and wrap modulo the
 *   preallocated capacity (disclosed in JSON as wrap_arounds); the pattern depends only
 *   on the position index, so a wrapped re-write is byte-identical and the verified
 *   prefix [0, min(committed_bytes, file_size)) is still deterministic.
 *
 *   Warmup: --warmup-sec of the same loop (stats discarded; offsets restart at 0).
 *   Measured: --seconds of the loop (default 15) or exactly --records writes (mutually
 *   exclusive; records mode is deterministic and used for identity checks).
 *   One fresh process per invocation is the driver's job; --runs >1 repeats the
 *   warmup+measured pair inside one process for convenience only.
 *
 * Fresh-process restart visibility (--verify-only)
 * ------------------------------------------------
 *   After every measured run the parent JVM spawns a *fresh* child JVM of this same class
 *   with --verify-only --file F --bs BS --seed S --verify-bytes N; the child re-reads
 *   [0, min(N, file_size)) and compares every block against the deterministic pattern.
 *   JSON restart_verify{ok,verified_bytes,method,power_cut_tested:false,...} records the
 *   outcome. This is a process-restart test, NOT a power-loss test.
 *
 * Counters / JSON
 * ---------------
 *   schema_version "1.0" + "schema_extensions": ["durability-1"] (an additive durability
 *   marker, the stream schema is not touched). Per-run: batch_size, groups_total, committed_writes,
 *   committed_bytes, max_observed_batch, writes_per_group{min,mean,max}, syncs_issued,
 *   syncs_completed, syncs_failed, fsyncs_per_group (= syncs_completed / groups_total,
 *   exactly 1.0 for a successful FDATASYNC/FSYNC run), bytes_synced, syncs_per_sec,
 *   mib_per_sync, sync_latency_us{samples,avg,p50,p90,p99,max}, sync_wait_share,
 *   group_latency_us{...}, p99_group_latency_us, mibs, writes_per_sec, wrap_arounds,
 *   restart_verify{...}. `sync_latency_us` is the commit() call duration and is therefore
 *   an UPPER BOUND on the sync-only cost (it includes waiting for the batch writes; the
 *   public API exposes no sync-only hook) - recorded as config.sync_latency_method.
 *
 * CLI
 * ---
 *   --file PATH          target file (created/preallocated if absent)      [required]
 *   --batch B            writes per group (1..4096; benchmark cells 1/8/32/128)
 *   --records N          measured phase = exactly N writes (deterministic identity run)
 *   --seconds S          measured phase duration (default 15; ignored if --records > 0)
 *   --bs SIZE            record size (default 4096; multiple of 512, <= 1 MiB)
 *   --durability MODE    fdatasync|fsync|writeback (default fdatasync)
 *   --warmup-sec S       warmup duration (default 5)
 *   --runs N             repetitions inside one process (default 1; driver uses 1)
 *   --file-size SIZE     preallocate/require this file size (default: existing size,
 *                        else records*bs, else 1 GiB)
 *   --seed N             deterministic pattern seed (default 20261113)
 *   --json PATH          also write the JSON object to PATH
 *   --label TEXT         case label for reports
 *   --verify-only        fresh-process verification mode (used by the parent bench)
 *   --verify-bytes N     --verify-only: verify [0, N) (default: whole file)
 *   --no-verify          skip the fresh-process restart verification (driver default off)
 *   --help
 *
 * Build/run (must not run Maven in the repo root; use the Lead's target/classes or a
 * scratch tree):
 *   javac -cp target/classes -d bench/juring/out --release 25 \
 *       bench/juring/JUringDurabilityBench.java
 *   taskset -c 2 java --enable-native-access=ALL-UNNAMED \
 *       -cp target/classes:bench/juring/out bench.juring.JUringDurabilityBench ...
 */

package bench.juring;

import com.davidvlijmincx.lio.api.DurabilityMode;
import com.davidvlijmincx.lio.api.GroupCommit;
import com.davidvlijmincx.lio.api.GroupCommitOptions;
import com.davidvlijmincx.lio.api.GroupCommitStats;
import com.davidvlijmincx.lio.api.IoUringOptions;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.JuringException;
import com.davidvlijmincx.lio.api.JuringFile;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
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
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;

public final class JUringDurabilityBench {

    /** Stream/file schema is frozen at 1.0; the durability bench marks itself additively. */
    static final String SCHEMA_VERSION = "1.0";
    static final String SCHEMA_EXTENSION = "durability-1";
    static final String ENGINE = "juring";
    static final String ENGINE_DETAIL = "JUringDurabilityBench";
    static final String ENGINE_DETAIL_VERIFY = "JUringDurabilityBench-verify";

    /** io_uring setup flags (same as the other JUring benches). */
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

    static final int ALIGNMENT = 4096;
    static final long DEFAULT_FILE_SIZE = 1L << 30;      // 1 GiB (the default file size)
    static final int GROUP_LATENCY_SAMPLES = 100_000;
    static final int SYNC_LATENCY_SAMPLES = 100_000;
    static final long VERIFY_CHILD_TIMEOUT_SEC = 300;

    public static void main(String[] args) {
        Config cfg = null;
        Manifest manifest = null;
        Storage storage = null;
        RunStat[] runs = new RunStat[0];
        VerifyOutcome verifyOnly = null;
        String error = null;
        int exitCode = 0;

        try {
            cfg = Config.parse(args);
            if (cfg.help) {
                System.out.println(usage());
                return;
            }
            manifest = Manifest.collect(args);
            if (cfg.verifyOnly) {
                verifyOnly = verify(cfg);
                if (!verifyOnly.ok) {
                    exitCode = 1;
                    error = verifyOnly.failureMessage();
                }
            } else {
                storage = prepareStorage(cfg);
                printPreamble(cfg, storage);
                List<RunStat> done = new ArrayList<>();
                for (int r = 0; r < cfg.runs; r++) {
                    done.add(runOnce(cfg, storage, r));
                }
                runs = done.toArray(new RunStat[0]);
            }
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
        String json = (cfg != null && cfg.verifyOnly)
                ? Report.renderVerify(cfg, manifest, verifyOnly, error, status)
                : Report.render(cfg, storage, manifest, runs, error, status);
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

    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    static final class BenchException extends Exception {
        BenchException(String message) {
            super(message);
        }
    }

    static final class Config {
        boolean help = false;
        String[] argv = new String[0];
        String file = null;
        long bs = 4096;
        int batch = 32;
        long records = 0;                 // 0 => timed mode (--seconds)
        double seconds = 15.0;
        double warmupSec = 5.0;
        int runs = 1;
        DurabilityMode durability = DurabilityMode.FDATASYNC;
        long fileSize = 0;                // 0 => derive from existing file/records/default
        long seed = 20261113L;
        String jsonPath = null;
        String label = null;
        boolean verifyOnly = false;
        boolean noVerify = false;
        long verifyBytes = 0;             // --verify-only range (0 => whole file)

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
                    case "--file":
                        c.file = value(argv, i, key, inline);
                        if (inline == null) i++;
                        break;
                    case "--batch":
                        c.batch = intArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--records":
                        c.records = sizeArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--bs":
                        c.bs = sizeArg(value(argv, i, key, inline));
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
                    case "--durability":
                        c.durability = durabilityArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--file-size":
                        c.fileSize = sizeArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--seed":
                        c.seed = longArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--json":
                        c.jsonPath = value(argv, i, key, inline);
                        if (inline == null) i++;
                        break;
                    case "--label":
                        c.label = value(argv, i, key, inline);
                        if (inline == null) i++;
                        break;
                    case "--verify-only":
                        if (inline != null) throw new UsageException("--verify-only takes no value");
                        c.verifyOnly = true;
                        break;
                    case "--verify-bytes":
                        c.verifyBytes = sizeArg(value(argv, i, key, inline));
                        if (inline == null) i++;
                        break;
                    case "--no-verify":
                        if (inline != null) throw new UsageException("--no-verify takes no value");
                        c.noVerify = true;
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
            try {
                return Long.parseLong(s.trim());
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

        /** Parses byte sizes with binary suffixes: 4096, 128k, 1M, 1GiB, ... */
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
            try {
                return Math.multiplyExact(Long.parseLong(t.trim()), mult);
            } catch (ArithmeticException | NumberFormatException e) {
                throw new UsageException("not a byte size: '" + s + "'");
            }
        }

        static DurabilityMode durabilityArg(String s) throws UsageException {
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "fdatasync":
                    return DurabilityMode.FDATASYNC;
                case "fsync":
                    return DurabilityMode.FSYNC;
                case "writeback":
                    return DurabilityMode.WRITEBACK;
                case "dsync":
                    throw new UsageException("--durability dsync is not part of this bench: "
                            + "DSYNC is the stream arm's contract-assertion mode; "
                            + "use fdatasync|fsync|writeback here");
                default:
                    throw new UsageException("unknown --durability '" + s
                            + "' (expected fdatasync|fsync|writeback)");
            }
        }

        void validate() throws UsageException {
            if (file == null || file.isBlank()) {
                throw new UsageException("--file is required");
            }
            if (bs < 512 || (bs & 511) != 0) {
                throw new UsageException("--bs must be a multiple of 512, got " + bs);
            }
            if (bs > (1 << 20)) {
                throw new UsageException("--bs too large (max 1 MiB)");
            }
            if (batch < 1 || batch > 4096) {
                throw new UsageException("--batch must be in [1, 4096] (benchmark cells 1/8/32/128)");
            }
            if (records > 0 && records < batch) {
                throw new UsageException("--records must be >= --batch (one full group minimum)");
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
            if (fileSize < 0) {
                throw new UsageException("--file-size must be > 0 when given");
            }
            if (fileSize > 0 && fileSize < bs) {
                throw new UsageException("--file-size must be >= --bs");
            }
            if (verifyOnly && noVerify) {
                throw new UsageException("--verify-only cannot be combined with --no-verify");
            }
            if (verifyOnly && batch < 1) {
                throw new UsageException("--batch must be >= 1");
            }
            if (label == null || label.isBlank()) {
                label = defaultLabel();
            }
        }

        String defaultLabel() {
            if (verifyOnly) {
                return "verify_" + humanSize(verifyBytes);
            }
            return "groupcommit-4k-batch" + batch + "_" + durabilityModeName();
        }

        String durabilityModeName() {
            return durability.name().toLowerCase(Locale.ROOT);
        }

        /** Expected fsyncs per committed group: 1.0 for FDATASYNC/FSYNC, 0 for WRITEBACK. */
        double fsyncsPerGroupExpected() {
            return durability == DurabilityMode.WRITEBACK ? 0.0 : 1.0;
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
                "JUringDurabilityBench - group-commit durability micro-benchmark",
                "",
                "Usage:",
                "  java --enable-native-access=ALL-UNNAMED -cp target/classes:bench/juring/out \\",
                "       bench.juring.JUringDurabilityBench [flags]",
                "",
                "Flags:",
                "  --file PATH          target file (created/preallocated if absent)   [required]",
                "  --batch B            writes per group (1..4096; benchmark cells 1/8/32/128; default 32)",
                "  --records N          measured phase = exactly N writes (deterministic identity run)",
                "  --seconds S          measured duration when --records is absent (default 15)",
                "  --bs SIZE            record size (default 4096; multiple of 512, <= 1 MiB)",
                "  --durability MODE    fdatasync|fsync|writeback (default fdatasync)",
                "  --warmup-sec S        warmup duration (default 5)",
                "  --runs N              repetitions inside one process (default 1; driver uses 1)",
                "  --file-size SIZE      preallocate/require file size (default existing size,",
                "                        else records*bs, else 1 GiB)",
                "  --seed N              deterministic pattern seed (default 20261113)",
                "  --json PATH           also write the JSON object to PATH",
                "  --label TEXT          case label for reports",
                "  --verify-only         fresh-process verification mode (spawned by the parent)",
                "  --verify-bytes N      --verify-only: verify [0, N) (default: whole file)",
                "  --no-verify           skip the fresh-process restart verification",
                "  --help                this text",
                "",
                "Workload: per group exactly --batch GroupCommit.write() calls at consecutive",
                "aligned offsets, then one commit() (fdatasync/fsync; writeback issues none).",
                "Identity: fsyncs_per_group == 1.0 for fdatasync/fsync; groups*batch = committed",
                "writes; the written pattern is re-read by a fresh process (restart_verify).",
                "Output: one JSON object on stdout; diagnostics on stderr.",
                "");
    }

    // ------------------------------------------------------------------
    // File preallocation
    // ------------------------------------------------------------------

    static final class Storage {
        final Path file;
        final long fileSize;          // preallocated capacity in bytes (multiple of bs)
        final long capacityRecords;   // fileSize / bs
        final boolean created;
        final boolean preallocated;

        Storage(Path file, long fileSize, long capacityRecords, boolean created, boolean preallocated) {
            this.file = file;
            this.fileSize = fileSize;
            this.capacityRecords = capacityRecords;
            this.created = created;
            this.preallocated = preallocated;
        }
    }

    static Storage prepareStorage(Config cfg) throws UsageException, IOException {
        Path p = Path.of(cfg.file).toAbsolutePath().normalize();
        boolean created = false;
        boolean preallocated = false;
        long size;
        if (Files.exists(p)) {
            if (!Files.isRegularFile(p)) {
                throw new UsageException("not a regular file: " + p);
            }
            size = Files.size(p);
            if (cfg.fileSize > 0 && cfg.fileSize > size) {
                setLength(p, cfg.fileSize);
                size = cfg.fileSize;
                preallocated = true;
            }
        } else {
            size = cfg.fileSize > 0 ? cfg.fileSize
                    : cfg.records > 0 ? cfg.records * cfg.bs
                    : DEFAULT_FILE_SIZE;
            if (size < cfg.bs) {
                throw new UsageException("--file-size " + size + " is smaller than --bs " + cfg.bs);
            }
            size = alignUp(size, cfg.bs);
            Path parent = p.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            setLength(p, size);
            created = true;
            preallocated = true;
        }
        if (size < cfg.bs) {
            throw new UsageException("file is smaller than --bs: " + p);
        }
        if (size % cfg.bs != 0) {
            throw new UsageException("file size " + size + " is not a multiple of --bs " + cfg.bs
                    + "; delete the file or pass --file-size with a --bs multiple");
        }
        if (cfg.records > 0 && cfg.records * cfg.bs > size) {
            throw new UsageException("--records " + cfg.records + " * --bs " + cfg.bs
                    + " exceeds the file capacity " + size + "; grow the file with --file-size"
                    + " or use --seconds");
        }
        return new Storage(p, size, size / cfg.bs, created, preallocated);
    }

    static void setLength(Path p, long size) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(size);
        }
    }

    static void printPreamble(Config cfg, Storage st) {
        System.err.printf(Locale.ROOT,
                "[juring-durability] label=%s file=%s size=%,d bs=%d batch=%d durability=%s"
                        + " records=%d seconds=%.2f warmup=%.2f runs=%d%n",
                cfg.label, st.file, st.fileSize, cfg.bs, cfg.batch, cfg.durabilityModeName(),
                cfg.records, cfg.seconds, cfg.warmupSec, cfg.runs);
        System.err.printf(Locale.ROOT,
                "[juring-durability] ring entries=max(256,2*batch+16) flags=%s inFlightCapacity=%d"
                        + " groupCommit.maxBatchSize=%d preallocated=%s%n",
                Arrays.toString(RING_FLAG_NAMES), Math.max(64, cfg.batch + 8), cfg.batch,
                st.preallocated);
    }

    // ------------------------------------------------------------------
    // One run = fresh ring/file/group, warmup phase, measured phase, verify child
    // ------------------------------------------------------------------

    static RunStat runOnce(Config cfg, Storage st, int runIndex) throws BenchException {
        int ringEntries = Math.max(256, 2 * cfg.batch + 16);
        int inFlightCapacity = Math.max(64, cfg.batch + 8);
        try (JUring ring = new JUring(ringEntries, RING_FLAGS)) {
            JuringFile file;
            try {
                file = JuringFile.open(ring, st.file, LinuxOpenOptions.WRITE, LinuxOpenOptions.DIRECT);
            } catch (JuringException e) {
                throw new BenchException("open " + st.file + " failed: " + describe(e));
            }
            try {
                file.withInFlightCapacity(inFlightCapacity);
                GroupCommitOptions options = GroupCommitOptions.defaults()
                        .withDurabilityMode(cfg.durability)
                        .withMaxBatchSize(cfg.batch);
                GroupCommit group;
                try {
                    group = GroupCommit.open(file, options);
                } catch (JuringException e) {
                    throw new BenchException("GroupCommit.open failed: " + describe(e));
                }
                try {
                    return runGrouped(cfg, st, group, runIndex);
                } finally {
                    try {
                        group.close();     // commits a pending batch (none) and never closes the file
                    } catch (JuringException e) {
                        System.err.println("[juring-durability] warning: group.close failed: "
                                + describe(e));
                    }
                }
            } finally {
                file.close();
            }
        }
    }

    static RunStat runGrouped(Config cfg, Storage st, GroupCommit group, int runIndex)
            throws BenchException {
        Arena arena = Arena.ofConfined();
        try {
            long stride = Math.multiplyExact(cfg.bs, (long) cfg.batch);
            MemorySegment buffers = arena.allocate(stride, ALIGNMENT);

            Phase warmup = null;
            Phase measured;
            GroupCommitStats base;
            GroupCommitStats end;
            try {
                if (cfg.warmupSec > 0) {
                    warmup = runPhase(cfg, st, group, buffers, false, cfg.warmupSec, 0L);
                    System.err.printf(Locale.ROOT,
                            "[juring-durability] warmup: %.2f s, %,d groups, %,d writes, %,d bytes%n",
                            warmup.elapsedSec, warmup.groups, warmup.writes, warmup.bytes);
                }
                base = group.stats();
                measured = runPhase(cfg, st, group, buffers, true, cfg.seconds, cfg.records);
                end = group.stats();
            } catch (JuringException e) {
                throw new BenchException("run " + (runIndex + 1) + " group-commit I/O failed: "
                        + describe(e));
            }
            if (measured.elapsedSec <= 0 || measured.groups == 0) {
                throw new BenchException("measured phase completed no groups");
            }

            long syncsIssued = end.syncs() - base.syncs();
            long syncsCompleted = syncsIssued;   // stats.syncs() counts successful syncs
            long syncsFailed = end.failedCommits() - base.failedCommits();
            long committedBytes = end.committedBytes() - base.committedBytes();
            long committedWrites = end.committedWrites() - base.committedWrites();
            long groupsTotal = end.batches() - base.batches();
            long bytesSynced = cfg.durability == DurabilityMode.WRITEBACK ? 0L : committedBytes;

            double mibs = (measured.bytes / (1024.0 * 1024.0)) / measured.elapsedSec;
            double writesPerSec = measured.writes / measured.elapsedSec;
            double syncsPerSec = syncsCompleted / measured.elapsedSec;
            double mibPerSync = syncsCompleted > 0
                    ? (bytesSynced / (1024.0 * 1024.0)) / syncsCompleted : 0.0;
            double fsyncsPerGroup = groupsTotal > 0 ? (double) syncsCompleted / groupsTotal : 0.0;
            double syncWaitShare = measured.elapsedNs > 0
                    ? measured.syncWaitNs / (double) measured.elapsedNs : 0.0;

            // Fresh-process restart visibility for [0, min(committed_bytes, file_size)).
            long verifyBytes = Math.min(measured.bytes, st.fileSize);
            VerifyOutcome verify = cfg.noVerify
                    ? VerifyOutcome.skipped()
                    : spawnVerify(cfg, st, verifyBytes);

            RunStat st2 = new RunStat(runIndex, measured, cfg, st, groupsTotal, committedWrites,
                    committedBytes, syncsIssued, syncsCompleted, syncsFailed, bytesSynced,
                    fsyncsPerGroup, syncsPerSec, mibPerSync, syncWaitShare, mibs, writesPerSec,
                    verify);
            System.err.printf(Locale.ROOT,
                    "[juring-durability] run %d/%d: %.2f s, %,d groups x batch %d, %,d writes,"
                            + " %.1f MiB/s, %.1f writes/s, syncs=%d (%.3f/group),"
                            + " sync p50=%.1f us p99=%.1f us, share=%.3f,"
                            + " restart_verify=%s (%s)%n",
                    runIndex + 1, cfg.runs, measured.elapsedSec, groupsTotal, cfg.batch,
                    committedWrites, mibs, writesPerSec, syncsCompleted, fsyncsPerGroup,
                    measured.syncLat.p50, measured.syncLat.p99, syncWaitShare,
                    verify.ok ? "ok" : "FAILED", verify.method);
            return st2;
        } catch (ArithmeticException e) {
            throw new BenchException("buffer size overflow: " + describe(e));
        } finally {
            arena.close();
        }
    }

    // ------------------------------------------------------------------
    // Phase = groups until deadline / record count
    // ------------------------------------------------------------------

    static final class Phase {
        long groups;
        long writes;
        long bytes;
        double elapsedSec;
        long elapsedNs;
        long syncWaitNs;
        long wrapArounds;
        long minGroup = Long.MAX_VALUE;
        long maxGroup;
        LatencyStats groupLat = LatencyStats.empty();
        LatencyStats syncLat = LatencyStats.empty();
    }

    static Phase runPhase(Config cfg, Storage st, GroupCommit group, MemorySegment buffers,
                          boolean timed, double seconds, long records) throws JuringException {
        Phase p = new Phase();
        Reservoir groupLat = timed ? new Reservoir(GROUP_LATENCY_SAMPLES) : null;
        Reservoir syncLat = timed ? new Reservoir(SYNC_LATENCY_SAMPLES) : null;
        long start = System.nanoTime();
        long deadline = start + (long) (seconds * 1_000_000_000L);
        long targetWrites = records > 0 ? records : Long.MAX_VALUE;
        long written = 0;
        long recordIndex = 0;
        while (written < targetWrites) {
            long now = System.nanoTime();
            if (records <= 0 && now >= deadline) {
                break;
            }
            int n = (int) Math.min(cfg.batch, targetWrites - written);
            long t0 = System.nanoTime();
            for (int k = 0; k < n; k++) {
                long position = (recordIndex + k) % st.capacityRecords;
                MemorySegment buf = buffers.asSlice((long) k * cfg.bs, cfg.bs);
                fillPattern(buf, cfg.seed, position);
                group.write(position * cfg.bs, buf);
            }
            long c0 = System.nanoTime();
            int committed = group.commit();
            long c1 = System.nanoTime();
            if (committed != n) {
                throw new JuringException(0, "commit", "commit() returned " + committed
                        + " but " + n + " writes were queued");
            }
            p.groups++;
            p.writes += n;
            p.bytes += (long) committed * cfg.bs;
            p.minGroup = Math.min(p.minGroup, n);
            p.maxGroup = Math.max(p.maxGroup, n);
            if (recordIndex + n > st.capacityRecords) {
                p.wrapArounds++;
            }
            if (timed) {
                groupLat.record(c1 - t0);
                syncLat.record(c1 - c0);
                p.syncWaitNs += c1 - c0;
            }
            recordIndex += n;
            written += n;
        }
        long end = System.nanoTime();
        p.elapsedNs = end - start;
        p.elapsedSec = p.elapsedNs / 1_000_000_000.0;
        if (timed) {
            p.groupLat = groupLat.toStats();
            p.syncLat = syncLat.toStats();
        }
        return p;
    }

    // ------------------------------------------------------------------
    // Deterministic pattern (xorshift64; the verified prefix is position-indexed)
    // ------------------------------------------------------------------

    /** Mix the user seed with a 4096-aligned position index. */
    static long patternSeed(long seed, long position) {
        long z = seed + position * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return z == 0 ? 1 : z;
    }

    /** Fill a native buffer with the deterministic per-position pattern (xorshift64). */
    static void fillPattern(MemorySegment buffer, long seed, long position) {
        long n = buffer.byteSize();
        long x = patternSeed(seed, position);
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

    /** Byte-array twin of {@link #fillPattern} (little-endian 8-byte words). */
    static void fillPatternBytes(byte[] buffer, int length, long seed, long position) {
        long x = patternSeed(seed, position);
        int off = 0;
        while (off + 8 <= length) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            buffer[off] = (byte) x;
            buffer[off + 1] = (byte) (x >>> 8);
            buffer[off + 2] = (byte) (x >>> 16);
            buffer[off + 3] = (byte) (x >>> 24);
            buffer[off + 4] = (byte) (x >>> 32);
            buffer[off + 5] = (byte) (x >>> 40);
            buffer[off + 6] = (byte) (x >>> 48);
            buffer[off + 7] = (byte) (x >>> 56);
            off += 8;
        }
        while (off < length) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            buffer[off] = (byte) x;
            off++;
        }
    }

    // ------------------------------------------------------------------
    // Fresh-process restart verification
    // ------------------------------------------------------------------

    static final class VerifyOutcome {
        final boolean ok;
        final boolean skipped;
        final long verifiedBytes;
        final long rangeBytes;
        final long firstMismatchOffset;
        final String method;
        final int childExit;
        final long wallMs;
        final int wrapArounds;

        VerifyOutcome(boolean ok, boolean skipped, long verifiedBytes, long rangeBytes,
                      long firstMismatchOffset, String method, int childExit, long wallMs,
                      int wrapArounds) {
            this.ok = ok;
            this.skipped = skipped;
            this.verifiedBytes = verifiedBytes;
            this.rangeBytes = rangeBytes;
            this.firstMismatchOffset = firstMismatchOffset;
            this.method = method;
            this.childExit = childExit;
            this.wallMs = wallMs;
            this.wrapArounds = wrapArounds;
        }

        static VerifyOutcome skipped() {
            return new VerifyOutcome(false, true, 0, 0, -1,
                    "skipped (--no-verify): no fresh-process re-read was performed", -1, 0, 0);
        }

        String failureMessage() {
            if (firstMismatchOffset >= 0) {
                return "pattern mismatch at offset " + firstMismatchOffset
                        + " while verifying " + rangeBytes + " bytes";
            }
            return "fresh-process verification failed (verified " + verifiedBytes + "/"
                    + rangeBytes + " bytes, child exit " + childExit + ")";
        }
    }

    /** Spawn a fresh JVM running this class with --verify-only over [0, verifyBytes). */
    static VerifyOutcome spawnVerify(Config cfg, Storage st, long verifyBytes) {
        long t0 = System.nanoTime();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(JUringDurabilityBench.class.getName());
        cmd.add("--verify-only");
        cmd.add("--file=" + st.file);
        cmd.add("--bs=" + cfg.bs);
        cmd.add("--seed=" + cfg.seed);
        cmd.add("--verify-bytes=" + verifyBytes);
        cmd.add("--label=" + cfg.label + "_verify");
        Process proc = null;
        String out = "";
        int exit = -1;
        try {
            proc = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            byte[] bytes = proc.getInputStream().readAllBytes();
            out = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!proc.waitFor(VERIFY_CHILD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new VerifyOutcome(false, false, 0, verifyBytes, -1,
                        "fresh-process verify child timed out after " + VERIFY_CHILD_TIMEOUT_SEC
                                + " s", -1, (System.nanoTime() - t0) / 1_000_000L, 0);
            }
            exit = proc.exitValue();
        } catch (Exception e) {
            if (proc != null) {
                proc.destroyForcibly();
            }
            return new VerifyOutcome(false, false, 0, verifyBytes, -1,
                    "fresh-process verify child failed to run: " + describe(e), -1,
                    (System.nanoTime() - t0) / 1_000_000L, 0);
        }
        long wallMs = (System.nanoTime() - t0) / 1_000_000L;
        boolean ok = exit == 0 && out.contains("\"ok\":true");
        long verified = extractLong(out, "verified_bytes", -1);
        long range = extractLong(out, "range_bytes", -1);
        long firstMismatch = extractLong(out, "first_mismatch_offset", -1);
        if (range < 0) {
            range = verifyBytes;
        }
        String method = "fresh-process JVM ("
                + javaBin + ") re-read [0, " + range + ") with buffered FileChannel and compared"
                + " every " + cfg.bs + "-byte block against the deterministic xorshift64 pattern"
                + " (process-restart visibility, not a power-loss test)";
        if (!ok) {
            System.err.println("[juring-durability] restart verification FAILED (exit=" + exit
                    + "): " + out);
            if (firstMismatch < 0) {
                firstMismatch = extractLong(out, "first_mismatch_offset", -1);
            }
        }
        return new VerifyOutcome(ok, false, verified, range, firstMismatch, method, exit, wallMs, 0);
    }

    /** Extract a flat integer JSON field from the child's JSON (writer is ours; no spaces). */
    static long extractLong(String json, String name, long fallback) {
        String key = "\"" + name + "\":";
        int i = json.indexOf(key);
        if (i < 0) {
            return fallback;
        }
        int j = i + key.length();
        int end = j;
        if (end < json.length() && (json.charAt(end) == '-' || Character.isDigit(json.charAt(end)))) {
            end++;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            try {
                return Long.parseLong(json.substring(j, end));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** --verify-only body: fresh-process re-read of [0, min(verifyBytes,file_size)). */
    static VerifyOutcome verify(Config cfg) throws UsageException, IOException {
        Path p = Path.of(cfg.file).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            throw new UsageException("--verify-only: file does not exist: " + p);
        }
        long size = Files.size(p);
        long want = cfg.verifyBytes > 0 ? cfg.verifyBytes : size;
        long range = Math.min(want, size);
        range = (range / cfg.bs) * cfg.bs;      // whole blocks only
        if (range <= 0) {
            throw new UsageException("--verify-only: empty verification range (file_size=" + size
                    + ", verify_bytes=" + cfg.verifyBytes + ", bs=" + cfg.bs + ")");
        }
        long verified = 0;
        long firstMismatch = -1;
        byte[] expected = new byte[(int) cfg.bs];
        byte[] actual = new byte[(int) cfg.bs];
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            long off = 0;
            while (off < range) {
                int n = (int) Math.min(cfg.bs, range - off);
                fillPatternBytes(expected, n, cfg.seed, off / cfg.bs);
                ByteBuffer bb = ByteBuffer.wrap(actual, 0, n);
                int got = 0;
                while (got < n) {
                    int r = ch.read(bb, off + got);
                    if (r < 0) {
                        break;
                    }
                    got += r;
                }
                if (got != n) {
                    firstMismatch = off + got;
                    break;
                }
                int mm = Arrays.mismatch(expected, 0, n, actual, 0, n);
                if (mm >= 0) {
                    firstMismatch = off + mm;
                    break;
                }
                verified += n;
                off += n;
            }
        }
        boolean ok = firstMismatch < 0 && verified == range;
        String method = "fresh-process buffered read-back of [0, " + range + ") with every "
                + cfg.bs + "-byte block compared against the deterministic xorshift64 pattern"
                + " (process-restart visibility, not a power-loss test)";
        if (!ok) {
            System.err.printf(Locale.ROOT,
                    "[juring-durability-verify] FAILED: verified %,d/%,d bytes, first mismatch at %d%n",
                    verified, range, firstMismatch);
        }
        return new VerifyOutcome(ok, false, verified, range, firstMismatch, method, 0, 0, 0);
    }

    // ------------------------------------------------------------------
    // Latency reservoir / stats (same Algorithm R as the other benches)
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
            long[] copy = Arrays.copyOf(samples, count);
            Arrays.sort(copy);
            return LatencyStats.fromSorted(copy, sumNs, seen, seen > count);
        }
    }

    static final class LatencyStats {
        long recorded;
        int samples;
        boolean sampled;
        double avg, p50, p90, p99, max;

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
            s.p99 = us(quantile(sorted, 0.99));
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

    // ------------------------------------------------------------------
    // Result struct
    // ------------------------------------------------------------------

    static final class RunStat {
        final int run;
        final Phase phase;
        final Config cfg;
        final Storage storage;
        final long groupsTotal;
        final long committedWrites;
        final long committedBytes;
        final long syncsIssued;
        final long syncsCompleted;
        final long syncsFailed;
        final long bytesSynced;
        final double fsyncsPerGroup;
        final double syncsPerSec;
        final double mibPerSync;
        final double syncWaitShare;
        final double mibs;
        final double writesPerSec;
        final VerifyOutcome verify;

        RunStat(int run, Phase phase, Config cfg, Storage storage, long groupsTotal,
                long committedWrites, long committedBytes, long syncsIssued, long syncsCompleted,
                long syncsFailed, long bytesSynced, double fsyncsPerGroup, double syncsPerSec,
                double mibPerSync, double syncWaitShare, double mibs, double writesPerSec,
                VerifyOutcome verify) {
            this.run = run;
            this.phase = phase;
            this.cfg = cfg;
            this.storage = storage;
            this.groupsTotal = groupsTotal;
            this.committedWrites = committedWrites;
            this.committedBytes = committedBytes;
            this.syncsIssued = syncsIssued;
            this.syncsCompleted = syncsCompleted;
            this.syncsFailed = syncsFailed;
            this.bytesSynced = bytesSynced;
            this.fsyncsPerGroup = fsyncsPerGroup;
            this.syncsPerSec = syncsPerSec;
            this.mibPerSync = mibPerSync;
            this.syncWaitShare = syncWaitShare;
            this.mibs = mibs;
            this.writesPerSec = writesPerSec;
            this.verify = verify;
        }

        double writesPerGroupMean() {
            return groupsTotal > 0 ? (double) committedWrites / groupsTotal : 0.0;
        }

        long writesPerGroupMin() {
            return phase.minGroup == Long.MAX_VALUE ? 0 : phase.minGroup;
        }

        long writesPerGroupMax() {
            return phase.maxGroup;
        }
    }

    // ------------------------------------------------------------------
    // Manifest (same env-override conventions as the other benches)
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
    // Minimal JSON writer (same style as the other JUring benches)
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

        static String render(Config cfg, Storage st, Manifest m, RunStat[] runs,
                             String error, String status) {
            Jw w = new Jw();
            w.obj();
            renderHeader(w, cfg, m, error, status, ENGINE_DETAIL);
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

        static String renderVerify(Config cfg, Manifest m, VerifyOutcome v, String error,
                                   String status) {
            Jw w = new Jw();
            w.obj();
            renderHeader(w, cfg, m, error, status, ENGINE_DETAIL_VERIFY);
            w.name("verify").obj();
            w.name("ok").bool(v != null && v.ok);
            w.name("skipped").bool(v != null && v.skipped);
            w.name("verified_bytes").num(v != null ? v.verifiedBytes : 0);
            w.name("range_bytes").num(v != null ? v.rangeBytes : 0);
            w.name("first_mismatch_offset").num(v != null ? v.firstMismatchOffset : -1);
            w.name("method").str(v != null ? v.method : null);
            w.name("power_cut_tested").bool(false);
            w.endObj();
            w.endObj();
            return w.toString();
        }

        static void renderHeader(Jw w, Config cfg, Manifest m, String error, String status,
                                 String engineDetail) {
            w.name("schema_version").str(SCHEMA_VERSION);
            w.name("engine").str(ENGINE);
            w.name("engine_detail").str(engineDetail);
            // Additive durability marker; schema_version stays "1.0".
            w.name("schema_extensions").arr();
            w.str(SCHEMA_EXTENSION);
            w.endArr();
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
            renderConfig(w, cfg);
            w.endObj();
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

        static void renderConfig(Jw w, Config c) {
            if (c == null) {
                return;
            }
            w.name("file").str(c.file);
            w.name("bs").num(c.bs);
            w.name("batch_size").num(c.batch);
            w.name("records").num(c.records);
            w.name("seconds").num(c.seconds);
            w.name("warmup_sec").num(c.warmupSec);
            w.name("runs").num(c.runs);
            w.name("durability").str(c.durabilityModeName());
            w.name("durability_mode").str(c.durabilityModeName());
            w.name("seed").num(c.seed);
            w.name("direct_io").bool(true);
            w.name("ring_entries").num(Math.max(256, 2 * c.batch + 16));
            w.name("ring_flags").arr();
            for (String f : RING_FLAG_NAMES) {
                w.str(f);
            }
            w.endArr();
            w.name("in_flight_capacity").num(Math.max(64, c.batch + 8));
            w.name("group_commit_max_batch_size").num(c.batch);
            w.name("group_commit_default_max_batch_size").num(GroupCommit.DEFAULT_MAX_BATCH_SIZE);
            w.name("fsyncs_per_group_expected").num(c.fsyncsPerGroupExpected());
            w.name("sync_latency_method").str("commit() wall time = upper bound on the sync-only"
                    + " cost (includes waiting for the batch writes; the public GroupCommit API"
                    + " exposes no sync-only hook)");
            w.name("restart_verify_method").str("fresh JVM --verify-only read-back of"
                    + " [0, min(committed_bytes,file_size)) with a deterministic pattern test;"
                    + " process-restart visibility, not a power-loss test");
            w.name("verify_spawned").bool(!c.noVerify);
        }

        static void renderLatency(Jw w, LatencyStats s, boolean withP90) {
            w.obj();
            w.name("samples").num(s.samples);
            w.name("avg").num(s.avg);
            w.name("p50").num(s.p50);
            if (withP90) {
                w.name("p90").num(s.p90);
            }
            w.name("p99").num(s.p99);
            w.name("max").num(s.max);
            w.endObj();
        }

        static void renderRun(Jw w, RunStat r) {
            w.obj();
            w.name("run").num(r.run + 1);
            w.name("seconds").num(r.phase.elapsedSec);
            w.name("batch_size").num(r.cfg.batch);
            w.name("durability_mode").str(r.cfg.durabilityModeName());
            w.name("groups_total").num(r.groupsTotal);
            w.name("committed_writes").num(r.committedWrites);
            w.name("committed_bytes").num(r.committedBytes);
            w.name("max_observed_batch").num(r.writesPerGroupMax());
            w.name("writes_per_group").obj();
            w.name("min").num(r.writesPerGroupMin());
            w.name("mean").num(r.writesPerGroupMean());
            w.name("max").num(r.writesPerGroupMax());
            w.endObj();
            w.name("syncs_issued").num(r.syncsIssued);
            w.name("syncs_completed").num(r.syncsCompleted);
            w.name("syncs_failed").num(r.syncsFailed);
            w.name("fsyncs_per_group").num(r.fsyncsPerGroup);
            w.name("bytes_synced").num(r.bytesSynced);
            w.name("syncs_per_sec").num(r.syncsPerSec);
            w.name("mib_per_sync").num(r.mibPerSync);
            w.name("sync_wait_share").num(r.syncWaitShare);
            w.name("sync_latency_us");
            renderLatency(w, r.phase.syncLat, true);
            w.name("group_latency_us");
            renderLatency(w, r.phase.groupLat, true);
            w.name("p99_group_latency_us").num(r.phase.groupLat.p99);
            w.name("mibs").num(r.mibs);
            w.name("writes_per_sec").num(r.writesPerSec);
            w.name("wrap_arounds").num(r.phase.wrapArounds);
            w.name("restart_verify").obj();
            VerifyOutcome v = r.verify;
            w.name("ok").bool(v.ok);
            w.name("skipped").bool(v.skipped);
            w.name("verified_bytes").num(v.verifiedBytes);
            w.name("range_bytes").num(v.rangeBytes);
            w.name("first_mismatch_offset").num(v.firstMismatchOffset);
            w.name("method").str(v.method);
            w.name("power_cut_tested").bool(false);
            w.name("child_exit").num(v.childExit);
            w.name("wall_ms").num(v.wallMs);
            w.endObj();
            w.endObj();
        }

        static void renderAggregate(Jw w, RunStat[] runs) {
            int n = runs.length;
            long groupsTotal = 0, committedWrites = 0, committedBytes = 0;
            long syncsIssued = 0, syncsCompleted = 0, syncsFailed = 0, bytesSynced = 0;
            double elapsed = 0;
            double[] mibs = new double[n];
            double[] writesPerSec = new double[n];
            double[] syncsPerSec = new double[n];
            double[] mibPerSync = new double[n];
            double[] syncWaitShare = new double[n];
            double[] fsyncsPerGroup = new double[n];
            double[] syncP50 = new double[n], syncP99 = new double[n], syncMax = new double[n];
            double[] groupP50 = new double[n], groupP99 = new double[n], groupMax = new double[n];
            int syncSamples = 0;
            int groupSamples = 0;
            boolean verifyOk = true;
            boolean verifySkipped = false;
            long verifiedBytes = 0;
            for (int i = 0; i < n; i++) {
                RunStat r = runs[i];
                groupsTotal += r.groupsTotal;
                committedWrites += r.committedWrites;
                committedBytes += r.committedBytes;
                syncsIssued += r.syncsIssued;
                syncsCompleted += r.syncsCompleted;
                syncsFailed += r.syncsFailed;
                bytesSynced += r.bytesSynced;
                elapsed += r.phase.elapsedSec;
                mibs[i] = r.mibs;
                writesPerSec[i] = r.writesPerSec;
                syncsPerSec[i] = r.syncsPerSec;
                mibPerSync[i] = r.mibPerSync;
                syncWaitShare[i] = r.syncWaitShare;
                fsyncsPerGroup[i] = r.fsyncsPerGroup;
                syncP50[i] = r.phase.syncLat.p50;
                syncP99[i] = r.phase.syncLat.p99;
                syncMax[i] = r.phase.syncLat.max;
                groupP50[i] = r.phase.groupLat.p50;
                groupP99[i] = r.phase.groupLat.p99;
                groupMax[i] = r.phase.groupLat.max;
                syncSamples += r.phase.syncLat.samples;
                groupSamples += r.phase.groupLat.samples;
                verifyOk = verifyOk && r.verify.ok;
                verifySkipped = verifySkipped || r.verify.skipped;
                verifiedBytes += r.verify.verifiedBytes;
            }
            w.name("runs").num(n);
            w.name("elapsed_sec").num(elapsed);
            w.name("groups_total").num(groupsTotal);
            w.name("committed_writes").num(committedWrites);
            w.name("committed_bytes").num(committedBytes);
            double aggFsyncsPerGroup = groupsTotal > 0 ? (double) syncsCompleted / groupsTotal : 0.0;
            w.name("syncs_issued_total").num(syncsIssued);
            w.name("syncs_completed_total").num(syncsCompleted);
            w.name("syncs_failed_total").num(syncsFailed);
            w.name("fsyncs_per_group").num(aggFsyncsPerGroup);
            w.name("bytes_synced_total").num(bytesSynced);
            w.name("mibs").obj();
            w.name("mean").num(mean(mibs));
            w.name("sd").num(stdev(mibs));
            w.name("ci95").num(ci95(mibs));
            w.name("min").num(minOf(mibs));
            w.name("max").num(maxOf(mibs));
            w.endObj();
            w.name("mib_per_sync").obj();
            w.name("mean").num(mean(mibPerSync));
            w.endObj();
            w.name("syncs_per_sec").obj();
            w.name("mean").num(mean(syncsPerSec));
            w.endObj();
            w.name("sync_wait_share").obj();
            w.name("mean").num(mean(syncWaitShare));
            w.endObj();
            w.name("writes_per_sec").obj();
            w.name("mean").num(mean(writesPerSec));
            w.endObj();
            w.name("fsyncs_per_group_mean").num(mean(fsyncsPerGroup));
            w.name("sync_latency_us").obj();
            w.name("method").str("mean of per-run percentiles (max across runs for max)");
            w.name("samples").num(syncSamples);
            w.name("p50").num(mean(syncP50));
            w.name("p99").num(mean(syncP99));
            w.name("max").num(maxOf(syncMax));
            w.endObj();
            w.name("group_latency_us").obj();
            w.name("method").str("mean of per-run percentiles (max across runs for max)");
            w.name("samples").num(groupSamples);
            w.name("p50").num(mean(groupP50));
            w.name("p99").num(mean(groupP99));
            w.name("max").num(maxOf(groupMax));
            w.endObj();
            w.name("restart_verify").obj();
            w.name("ok").bool(verifyOk && !verifySkipped);
            w.name("skipped").bool(verifySkipped);
            w.name("verified_bytes_total").num(verifiedBytes);
            w.name("power_cut_tested").bool(false);
            w.endObj();
        }

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
            return v.length > 0 ? s / v.length : 0;
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

    private JUringDurabilityBench() {
    }
}
