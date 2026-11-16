# B6 report — buffered I/O parity + durability (fadvise, durability modes, group commit)

**Status:** DELIVERED + INDEPENDENTLY VERIFIED (PASS), session 9, 2026-11-15 — **B6 does
not change M1/M2/M3 sign-off status** (M2 remains the signed buffered comparison; B6 is the
durability follow-up).
**Contract:** [`docs/b6-design.md`](b6-design.md) (frozen; sha1 `7490aa9bb7629f5705b880ef7f870dcb11d27152`,
incl. amendments A1/A2).
**Plan:** `SEASTAR-PLAN.md` §4 B6 / gap G12. **Baseline tree:** `kafka-engine` at `d0d549a`
(M3 signed; B5 delivered in `309335b`; 349 tests / 0 / 0 / 1).

## 0. TL;DR

B6 is delivered, tested, independently verified (PASS) and documented, with no change to the
M1/M2/M3 sign-off status. Headline evidence: the group-commit helper issues **exactly one
`fdatasync` per batch** (`fsyncs_per_group == 1.000000` at batch sizes 1/8/32/128) with
fresh-process restart verification PASS, the durability-mode sync table is proven with two
independent observables, and the full suite is **401/0/0/1** green (349 baseline byte-identical
+ 52 new).

B6 completes the plan's buffered-durability item additively on the B1 `JuringFile` and B2
stream layer:

- **`posix_fadvise` hints** — new `FadviseAdvice` enum, `JuringFile.fadvise(...)` (blocking,
  advisory, not a ring request), applied once at stream open via
  `InputStreamOptions.withFadvise` / `OutputStreamOptions.withFadvise`.
- **Durability modes** — new `DurabilityMode` `{WRITEBACK, FDATASYNC, FSYNC, DSYNC}`;
  `JuringFile.durability()` reports the mode implied by the open flags; the buffered output
  stream's `flush()`/`close()` are durability points (one `fdatasync`/`fsync` per point;
  `DSYNC` = kernel durability, no explicit sync).
- **Group-commit helper** — new `GroupCommit`/`GroupCommitOptions`/`GroupCommitStats`:
  queue N writes, `commit()` awaits them and issues **exactly one** `fdatasync` (or `fsync`),
  bounded memory, caller-controlled batches, typed/latched errors, proof counters visible on
  both the helper and `JuringFile` (`syncOperations()`/`syncCompletions()`).
- **Tests** — 5 new B6 JUnit classes; full suite **401 tests, 0 failures, 0 errors,
  1 skipped** on the frozen tree (349 baseline byte-identical + 52 new).
- **Independent verification: PASS** — `scratch/b6-verify/VERDICT.md` (STATUS FINAL): 55/55
  adversarial modes, 1,680 PASS assertions, 0 failures, 0 skips; independent private-copy
  suite 401/0/0/1; 4,916 bench-artifact assertions PASS; frozen identity re-derived
  before/after (unchanged); no library defect (2 contract-wording/report-footnote
  observations F1/F2, no amendment).
- **Benchmark evidence** — W24 durability A/B (JUring vs seastar `fstream`, explicit matched
  durability; 19/19 invocations ok, 0 errors) and W25 group-commit micro-benchmark (12/12
  invocations ok; **`fsyncs_per_group == 1.000000`** and fresh-process visibility for every
  batch size 1/8/32/128); M2's signed W21/W22 buffered comparison is cited, with a newly
  disclosed durability asymmetry in the M2 write arms (§6). W24's throughput is
  state-confounded by the drive's write bimodality (bracket control drift 148.9 % > 10 %), so
  it is reported as a raw per-rep table with **no ranked durability-cost number** (§7).

**B6 does not change M1/M2/M3 sign-off status.** M2 remains the signed buffered comparison;
B6 is the durability follow-up. **Acceptance evidence:** group commit proves
`fsyncs_per_group == 1.000000` at batch sizes 1/8/32/128 with fresh-process restart
verification PASS in every cell (W25, 12/12 invocations ok), and durability modes are tested
across the full §3.1 table with two independent observables; W24 (19/19 ok) confirms matched
durability accounting but is write-state-confounded (no ranked cost claim). Independent
verification: **PASS** (`scratch/b6-verify/VERDICT.md`, 55/55 modes).

## 1. What was delivered

| item | status | evidence |
|---|---|---|
| Frozen contract `docs/b6-design.md` (API/semantics/protocol, before implementation; A1/A2) | DONE | design sha1 `7490aa9b…` |
| `FadviseAdvice` + `JuringFile.fadvise(...)` (+ whole-file) | DONE | `B6FadviseTest` (12) |
| `DurabilityMode` + `JuringFile.durability()` + sync counters | DONE | `B6DurabilityModesTest` (8) |
| Stream fadvise options + application at open | DONE | `B6StreamDurabilityTest` (14, incl. all advices on output + three input overloads) |
| Stream durability points (`OutputStreamOptions.withDurabilityMode`, `JuringOutputStream.syncs()`) | DONE | `B6StreamDurabilityTest` |
| `GroupCommit` + `GroupCommitOptions` + `GroupCommitStats` | DONE | `B6GroupCommitTest` (17), `B6GroupCommitCrashTest` (1) |
| W24 durability A/B driver + analyzer + official session | DONE — 19/19 invocations ok, supporting evidence | `bench/results/b6-durability-2026-11-15/` (§7) |
| W25 group-commit micro-bench + driver + analyzer + official session | DONE — 12/12 invocations ok, identities PASS | `bench/results/b6-group-commit-2026-11-15/` (§8) |
| Independent verification | **PASS** — 55/55 modes, 1,680 assertions, own suite 401/0/0/1 | `scratch/b6-verify/VERDICT.md` (§5) |
| HANDOVER §0.0, SEASTAR-PLAN v1.6, b1/b2 pointer addenda | DONE | the docs commit that adds this report (session 9) |

## 2. Public API (as frozen in design §2)

New public types in `com.davidvlijmincx.lio.api` (additive only; `pom.xml` and
`module-info.java` unchanged):

```java
public enum FadviseAdvice { NORMAL(0), RANDOM(1), SEQUENTIAL(2), WILLNEED(3), DONTNEED(4), NOREUSE(5);
    public int value(); }

public enum DurabilityMode { WRITEBACK, FDATASYNC, FSYNC, DSYNC }

// JuringFile (additive)
public DurabilityMode durability();                 // DSYNC iff opened O_DSYNC/O_SYNC; else WRITEBACK
public void fadvise(FadviseAdvice advice, long offset, long length) throws JuringException;
public void fadvise(FadviseAdvice advice) throws JuringException;   // (advice, 0, 0) = whole file
public int inFlightCapacity();                      // configured ledger capacity (default 1024)
public long syncOperations();                       // FSYNC requests prepared through this handle
public long syncCompletions();                      // FSYNC completions reaped through this handle

// InputStreamOptions (additive)
public FadviseAdvice fadvise();                     // default NORMAL (= no syscall)
public InputStreamOptions withFadvise(FadviseAdvice advice);

// OutputStreamOptions (additive; syncOnClose kept exactly as B2)
public DurabilityMode durability();                 // default WRITEBACK
public FadviseAdvice fadvise();                     // default NORMAL
public OutputStreamOptions withDurabilityMode(DurabilityMode mode);
public OutputStreamOptions withFadvise(FadviseAdvice advice);

// JuringOutputStream (additive)
public DurabilityMode durability();
public long syncs();                                // successful mode syncs issued (valid after close)

// GroupCommit / GroupCommitOptions / GroupCommitStats (new)
public final class GroupCommit implements AutoCloseable {
    public static final int DEFAULT_MAX_BATCH_SIZE = 32;
    public static GroupCommit open(JuringFile file) throws JuringException;
    public static GroupCommit open(JuringFile file, GroupCommitOptions options) throws JuringException;
    public JuringFile file();
    public DurabilityMode durabilityMode();
    public int maxBatchSize();
    public int pendingWrites();
    public long pendingBytes();
    public void write(long offset, MemorySegment source) throws JuringException;  // ISE when batch full
    public int commit() throws JuringException;         // one fdatasync/fsync for FDATASYNC/FSYNC
    public GroupCommitStats stats();
    public void close() throws JuringException;         // commits pending batch; never closes the file
}
public final class GroupCommitOptions { /* defaults: FDATASYNC, maxBatchSize 32; 1..4096 */
    public static GroupCommitOptions defaults();
    public DurabilityMode durability();
    public int maxBatchSize();
    public GroupCommitOptions withDurabilityMode(DurabilityMode mode);
    public GroupCommitOptions withMaxBatchSize(int maxBatchSize);
}
public record GroupCommitStats(long batches, long committedWrites, long committedBytes,
                               long syncs, long failedCommits, int maxObservedBatch) {}
```

Defaults are exactly today's behaviour (`WRITEBACK`, `NORMAL` fadvise), so B1/B2 callers are
unaffected. New option fields participate in `equals`/`hashCode`/`toString`.

## 3. Implementation summary

- **fadvise (file layer).** `LibCDispatcher` gains one `posix_fadvise` FFM downcall
  (`FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)`, no
  `captureCallState` — the libc wrapper returns the errno directly, no arena needed).
  `JuringFile.fadvise` validates (`ensureOpen` → null advice → negative offset/length), then
  calls the dispatcher and maps a non-zero return to the typed
  `JuringException(errno, "fadvise", ...)`. It is advisory: no ring request, no ledger slot,
  no change to any operation counter and no `checkRingOwnership`. It is the first blocking
  metadata syscall on `JuringFile` (precedent: `open`/`close`/`calloc`/`strerror`); the
  doc warns that `WILLNEED`/`DONTNEED` can do real work and must not be called from a shard
  task.
- **Durability report (file layer).** The combined open flags are stored once at `open`;
  `durability()` derives `DSYNC` from the `O_DSYNC` bit (set by both `DSYNC` and `SYNC`) and
  is readable after close. `syncOperations()` increments where `prepareFsync` tracks the
  `OperationType.FSYNC` request; `syncCompletions()` increments in the completion consumer
  when the reaped slot's kind is `FSYNC` (terminal, success or failure). No existing counter
  or behaviour changed.
- **Stream hints.** `InputStreamOptions.fadvise` is applied once per `open` (all overloads
  delegate to the 4-arg one): the declared window is hinted, `length < 0` (until EOF) and
  `length == 0` are hinted as `(offset, 0)` = "to EOF"; `NORMAL` issues no syscall.
  `OutputStreamOptions.fadvise` is applied once to the whole file at open.
- **Stream durability points.** `JuringOutputStream` keeps the frozen B2 drain/pad/truncate
  order; after that step `flush()` issues exactly one `fdatasync`/`fsync` when the mode is
  `FDATASYNC`/`FSYNC` (`syncs++` on success; failure latched B2-style). `close()` performs
  its existing single-flush bookkeeping and then exactly one mode sync (none for `DSYNC`;
  for `WRITEBACK` the B2 `syncOnClose` fdatasync shortcut is preserved and is never doubled).
  `DSYNC` is validated at open against `JuringFile.durability()` before the preallocation or
  fadvise hints run.
- **Group commit.** `GroupCommit` allocates two O(`maxBatchSize`) arrays at `open` and queues
  writes with `JuringFile.prepareWrite` (no submit/drain/sync in `write`; batch-full is an
  unlatched `IllegalStateException`). `commit()` awaits every queued write in submission order
  — the first `await` submits the whole prepared batch — then issues exactly **one**
  `JuringFile.fdatasync()`/`fsync()` (`WRITEBACK`/`DSYNC`: none) and updates its counters;
  an empty commit returns 0 and touches nothing. Write/sync failures drain the rest of the
  batch, latch the first `JuringException`, increment `failedCommits` and throw. `close()`
  commits a pending batch, is idempotent, and never closes the file. The "one sync per batch"
  is observable twice: `GroupCommitStats.syncs()` and
  `ΔJuringFile.syncOperations()`/`ΔsyncCompletions()`; ordering is observable as
  `ΔissuedOperations() == n + syncs`.
- **No new threads, rings, arenas or dependencies**; no `module-info`/`pom` change; the B1
  hot path and every B2 default are untouched.

## 4. Test evidence

**Full suite on the integrated tree (Lead G3, `mvn -o test`, 2026-11-15):**
**401 tests, 0 failures, 0 errors, 1 skipped — BUILD SUCCESS** (`scratch/b6-lead/g3-suite.log`),
against the 349/0/0/1 baseline (**+52 new tests**, all existing test files byte-identical,
`git diff -- src/test` empty).

| new class | tests | covers |
|---|---|---|
| `B6FadviseTest` | 12 | enum values; NPE/IAE/ISE taxonomy; all six advices on read-only/RDWR and buffered/O_DIRECT handles; whole-file convenience; advisory-only contract (content, length, all ring counters, in-flight unchanged); `NORMAL` explicit no-op; fadvise with foreign raw ring work in flight (no ownership check); dispatcher direct-errno convention (EBADF) |
| `B6DurabilityModesTest` | 8 | `durability()` derivation for WRITEBACK/DSYNC/SYNC combinations; readable after close; O_DSYNC write + close/reopen visibility; explicit fsync/fdatasync still counted; prepared sync counted issued/reaped; `inFlightCapacity()` default/configured/validation |
| `B6StreamDurabilityTest` | 14 | option defaults/round-trips/equality; DSYNC assertion (mismatch IAE before any write, DSYNC/SYNC file accepted); sync-count table via `syncs()` **and** `Δfile.syncOperations()`/`syncCompletions()` (+`Δissued` ordering) for WRITEBACK / writeback+syncOnClose / FDATASYNC / FSYNC / DSYNC; flush/close counts; writes after a durability flush; fadvise applied for all six advices on output and all three input overloads with unchanged content |
| `B6GroupCommitTest` | 17 | open validation (null/closed/capacity `maxBatchSize+1 <= inFlightCapacity`/DSYNC mismatch/defaults); batch-full ISE without side effects; one batch → exactly one sync/one completion, `Δissued == n+1`, stats exact; empty commit no-op; two batches; WRITEBACK/DSYNC zero explicit syncs; FSYNC mode; ordering (writes prepared but not completed before commit); use-after-close; close commits pending and does not close the file; close idempotent; failed write latches; stats invariants; 0 B/op steady state (ThreadMXBean, 3 stable warm-up rounds) |
| `B6GroupCommitCrashTest` | 1 | child JVM (fresh process) opens a file, writes 6×4 KiB, commits one FDATASYNC batch, prints `DURABLE ... syncs=1 committedWrites=6`, `Runtime.halt(137)`; parent asserts exit 137 and reopens with a **fresh `JUring`+`JuringFile`** and verifies length + every byte. Canonical run: skipped=0. (`@TempDir` is tmpfs: this proves process-restart visibility, not media durability — design §9.1.) |

Existing suites unchanged and green: `JuringStreamsConformanceTest` (37),
`JuringStreamsBoundedMemoryTest` (5), `ZeroAllocationHotPathTest` (7),
`JuringFile*` conformance/lifecycle/timeout tests and all B4/B5 classes. `mvn -o -q
-DskipTests package` clean.

## 5. Independent verification

**PASS — STATUS FINAL** on the frozen tree; verifier `b6-verify` independent of all authors
(`scratch/b6-verify/VERDICT.md`, 2026-11-15). Frozen identity was re-derived live and on a
byte-identical private copy before/after (all identical to the Lead G3b/D3 hashes:
src/main `5690c498…`, src/test `6796f83e…`, bench `b2eb2d80…`, design `7490aa9b…`,
`analyze_stream.py` `9a24b3fc…`); no source/bench edit occurred during verification. The
verifier built its own classpath from the frozen copy (`javap`/`javac`), never used
`target/classes`, and ran every mode in a fresh JVM with an external timeout.

| gate | result |
|---|---|
| frozen identity (live + private copy, before/after) | **PASS** — all hashes match |
| adversarial matrix — 6 harnesses / **55 modes** | **PASS** — 55/55 modes, **1,680 assertions**, 0 failures, 0 skips (B6Surface 4/259, B6Fadvise 10/213, B6Durability 13/285, B6GroupCommit 21/817, B6Crash 5/93, B6Leaks 2/13) |
| independent `posix_fadvise` syscall oracle | **PASS** — `perf record -p` attach + `syscalls:sys_enter_fadvise64` decoding (67 assertions): explicit advice values/ranges forwarded verbatim; stream `NORMAL` = no syscall; every non-`NORMAL` stream hint = exactly one call with the frozen `(offset,length)` mapping |
| §3.1 sync-count table | **PASS** via two independent observables (`JuringOutputStream.syncs()`/`GroupCommitStats` **and** `ΔJuringFile.syncOperations()`/`ΔsyncCompletions()`), with `ΔissuedOperations()` ordering, empty durability points, `syncOnClose`, `DSYNC`; read-only-fd latched-failure identity; zero-allocation [0,0,0]; no-copy buffer; 5 child-JVM `halt(137)` restart-visibility tests with fresh-parent byte verification |
| private-copy full suite | **PASS** — `mvn -o test`: 401 tests / 0 failures / 0 errors / 1 skipped, BUILD SUCCESS; all 62 pre-existing test files byte-identical; 5 new B6 classes present |
| static/scope checks | **PASS 43/43** — design hash + A1/A2, write-scope audit, `module-info`/`pom`/`OperationType`/`analyze_stream.py` byte-identity, `javap` additive-only on the 7 changed classes, `LinuxOpenOptions` javadoc-only, downcall shape / `NORMAL` guards / fdatasync-vs-fsync call sites / no threads-arenas-nanoTime in `GroupCommit` |
| bench recomputation (raw JSON, analyzer output cross-checked, not trusted) | **PASS 4,916 / 0 failures** (W24 1,060; W25 895; additive-vs-M2 2,952; M2 re-aggregation 6; fail-loud 3) — W24 `durability_points == syncs_issued == syncs_completed == floor(bytes/S)+passEnd`, writeback/dsync zero syncs, state classes/paired ratios/bracket verdict reproduce; W25 all identities, `restart_verify`, A2 rests from `cell-meta`; `analyze_stream.py` byte-identical and M2 re-aggregation reproduces; both new analyzers fail loudly without the extension marker |
| findings | **F1/F2 contract-wording/report-record only, no defect, no amendment** (see §7.3/§7.4) |
| verdict | **PASS** — B6 meets the frozen contract on this tree. M1/M2/M3 sign-off status unchanged. |

Residual risks accepted and documented in the verdict (§7): W24 is state-confounded supporting
evidence only; W25 `sync_latency` is a `commit()` upper bound; restart ≠ power loss; the perf
oracle is environment-dependent (worked here, sentinel-guarded); JIT-warm-up transients are
outside the steady-state zero-allocation claim; W24's 2 GiB dataset deviation and the M2
durability-asymmetry disclosure stand.

## 6. M2 relation and the durability asymmetry disclosure (frozen obligation)

**M2's signed W21/W22 buffered comparison is cited, not re-litigated.** From
[`docs/m2-report.md`](m2-report.md) and `bench/results/m2-final/` (R=5, 10 s warmup + 60 s
measured, 300 s rests, 60/60 invocations ok):

| cell | juring MiB/s | seastar MiB/s | juring/seastar row means | read strict 1s95 LB | write position-matched |
|---|---|---|---|---|---|
| W21-128k (write) | 610.0 | 438.6 | 1.391 | — | 1.232 fast / 0.984 slow |
| W22-128k (read) | 2284.6 | 2327.5 | 0.982 | **0.966** | — |
| W21-1M (write) | 569.5 | 355.7 | 1.601 | — | 1.269 fast / 1.384 slow |
| W22-1M (read) | 4662.8 | 4724.3 | 0.987 | **0.960** | — |

**Disclosure (not stated in M2): the W21 write comparison was durability-asymmetric.**
The M2 seastar arm ran with seastar's default `file_open_options.durable = true`, and its
write pass calls both `s.flush()` and `s.close()`, each of which reaches
`file::flush()` → `fdatasync` — **2 `fdatasync` per pass** (`seastar_stream_bench.cc:368,375`
→ `SeaStarRef/src/core/fstream.cc:499-508`, `iostream-impl.hh:571-586`,
`SeaStarRef/src/core/file.cc:217-227`). The M2 JUring arm ran with the B2 default
`syncOnClose = false` and no `O_DSYNC` — **0 syncs per pass** (`JUringStreamBench.java`
options at the M2 revision; `JuringOutputStream` only synced on close when `syncOnClose`).
Consequences, and how B6 handles them:

1. M2's write ratios compare *seastar-with-fdatasync* against *JUring-without*, so they
   **cannot** be used as evidence that "durability is free" for either engine; B6 quotes no
   such claim.
2. B6's W24 arms (§7) **remove** the asymmetry: both engines run explicit, matched durability
   modes (writeback vs fdatasync per 4 MiB durability point) plus a writeback-vs-writeback
   control pair. The B6 report makes **no new cross-engine write win/loss claim** (methodology
   D7 discipline); the M2 signed numbers stand as the buffered comparison.
3. The M2 read comparison is not affected (no durability dimension).

## 7. W24 durability A/B evidence

**Supporting evidence only — not a milestone bar, no new cross-engine write claim.**
Session `bench/results/b6-durability-2026-11-15/` (gitignored; 19 invocations,
**19/19 `status=ok`, 0 errors**, 07:29:59Z → 07:55:26Z ≈ 25 m 27 s incl. rests and analysis).
Protocol (frozen §6.3 + A2): one write cell `W24-128k` (stream_write, bs 128 KiB, qd 8,
`--size 2g` on the existing 10 GiB write file — never resized, deviation disclosed), R=3,
**300 s idle rest before each rep window**, then the 4 Tier-1 arms **back-to-back** in an
order alternating per rep (rep1 `Jw,Jd,Sw,Sd`; rep2 `Sd,Sw,Jd,Jw`; rep3 `Jw,Jd,Sw,Sd`),
per arm **5 s warmup + 15 s measured**, fresh process, `taskset -c 2`, O_DIRECT, pinned
seastar `--aio-fsync 1`, `--sync-every 4M` on all Tier-1 arms; Tier-2 `dsync` arms
(`juring`, `seastar`) R=2 with 120 s rests + a JUring-only `fsync` arm; the JUring-writeback
arm runs first overall and again as the **last-overall bracket control**. Raw:
`cells/` (19 JSON), `cell-meta.tsv`, `manifest.txt`, `aggregate/durability.{md,json}`.

### 7.1 Matched-durability accounting (valid regardless of device state)

| engine | arm | n | MiB/s mean (CV%) | syncs/s | **MiB/sync** | sync_wait_share | sync p50/p99 µs | write p50 µs | state classes f/m/s |
|---|---|---|---|---|---|---|---|---|---|
| juring | writeback | 3 | 2721.3 (82.1) | 0 | n/a | 0.241 | 2308.9 / 8925.6 | 274.0 | 2/0/1 |
| juring | fdatasync | 3 | 1562.7 (80.6) | 391.4 | **3.993** | 0.460 | 717.4 / 6570.5 | 20.7 | 1/0/2 |
| seastar | writeback | 3 | 2749.9 (75.6) | 0 | n/a | 0.274 | 281.9 / 3600.9 | 3.1 | 2/0/1 |
| seastar | fdatasync | 3 | 1277.4 (75.1) | 320.6 | **3.983** | 0.533 | 1646.1 / 10499.1 | 3.3 | 1/0/2 |
| juring | dsync (T2) | 2 | 321.5 (32.0) | 0 | n/a | 0.247 | 2427.3 / 12839.1 | — | exploratory |
| juring | fsync (T2, JUring-only) | 2 | 483.5 (0.2) | 121.1 | — | 0.681 | 5452.1 / 7765.5 | — | JUring-only |
| seastar | dsync (T2) | 2 | 385.5 (3.0) | 0 | n/a | 0.279 | 2730.4 / 7118.2 | — | exploratory |

The two engines' `fdatasync` arms both report **MiB/sync ≈ 3.99 MiB**, i.e. the durability
settings are matched (one explicit `fdatasync` per 4 MiB durability point, with pass-end
syncs < 1 % of the points) and the sync accounting is correct on both sides. `writeback` and
`dsync` arms report 0 explicit syncs (as specified), and the `fsync` arm is JUring-only
(seastar's `fstream` has no `fsync(2)` path — disclosed; not compared across engines).

### 7.2 Run state and why no cost number is quoted

The two writeback controls disagree by **148.9 %** (start control 142.6 MiB/s "slow" vs
end bracket 973.7 MiB/s "fast"; the frozen §6.3 rule requires them within 10 %). The
official session is therefore **state-confounded**: the report quotes the raw per-rep table
with state classes and makes **no ranked durability-cost claim** (this is the frozen
disclosure outcome, not a defect). Within-rep paired detail (all reps kept, state classes
attached):

| engine | rep | writeback MiB/s | fdatasync MiB/s | ratio | cost % | classes | state-matched |
|---|---|---|---|---|---|---|---|
| juring | 1 | 142.6 | 269.5 | 1.890 | −89.0 | slow/slow | yes |
| juring | 2 | 4007.5 | 1634.1 | 0.408 | 59.2 | fast/slow | no |
| juring | 3 | 4013.9 | 2784.4 | 0.694 | 30.6 | fast/fast | yes |
| seastar | 1 | 352.2 | 603.7 | 1.714 | −71.4 | slow/slow | yes |
| seastar | 2 | 3825.1 | 852.5 | 0.223 | 77.7 | fast/slow | no |
| seastar | 3 | 4072.3 | 2375.9 | 0.583 | 41.7 | fast/fast | yes |

Descriptive only (one same-state fast pair per engine, n=1): in the fast-state pair,
durability points cost ≈ 30.6 % (juring) and ≈ 41.7 % (seastar) of writeback throughput.
The point estimate is consistent with the expected cost of one `fdatasync` per 4 MiB at
multi-GiB/s write rates; it is **not** a ranked claim (CV > 10 %, bracket control failed;
methodology D7).

### 7.3 What the session does establish

- All durability modes behave as specified under sustained stream writes: `fdatasync`/`fsync`
  issue the expected number of syncs, `writeback`/`dsync` issue none, `sync_wait_us` is
  populated, and **0 errors / 0 failed syncs** across 19 fresh-process invocations.
- Matched durability accounting across engines (`MiB/sync` parity, §7.1).
- The W24 write-path state sensitivity is severe on this drive even for the 2 GiB
  overwrite workload (throughput spans 142–4072 MiB/s across arms/reps within one session);
  the M2 disclosure (burst→steady write states) is confirmed and quantified.
- **Disclosures:** durability points include a write-behind drain on both engines (B2
  `flush()` and seastar `fstream::flush()` both drain) so `sync_wait_share` is an upper bound
  on pure sync cost; seastar's pass-end path issues 2 `fdatasync` per pass vs JUring's 1
  (< 1 % of the points at `--sync-every 4M`, reported through `syncs/s`/`MiB/sync`); no cache
  drop and no power-loss test; W24 dataset is 2 GiB/pass; state classes are derived from
  M1 §4 + M2 §6 and fixed in the analyzer before the campaign.
- **F1 (report-record, verifier finding):** the seastar W24 cells do not carry a
  `config.fadvise` field — the seastar stream bench exposes no `--fadvise` flag (design §6.2),
  so `NORMAL` is implicit there, while the JUring arm records `"fadvise":"normal"`. No W24
  verdict or number depends on fadvise; design §3.4's "recorded in the JSON" wording is
  satisfied for the JUring arms and implicit for seastar (reported here, no amendment needed).

## 8. W25 group-commit evidence

Session `bench/results/b6-group-commit-2026-11-15/` (gitignored; **12/12 invocations
`status=ok`, 0 errors**, 07:55:31Z → 08:06:24Z ≈ 10 m 53 s incl. rests and analysis).
Protocol (frozen §6.4 + A2): cells `W25-4k-b1 / b8 / b32 / b128` (4 KiB writes,
`durability=fdatasync`), R=3, 5 s warmup + 15 s measured, 5 s pause between reps, **120 s
idle rest between cells**, one fresh process per invocation, `taskset -c 2`, and a
**fresh-JVM `--verify-only` re-read** of the deterministic pattern after every measured run;
the bench calls only the public `GroupCommit` API. Raw: `cells/` (12 JSON), `manifest.txt`,
`cell-meta.tsv`, `aggregate/group-commit.{md,json}`.

### 8.1 Contract identities (machine-checkable acceptance)

| cell | batch | n | `fsyncs_per_group` mean | == 1.0 | `groups × batch == committed_writes` | syncs_failed | restart_verify.ok |
|---|---|---|---|---|---|---|---|
| W25-4k-b1 | 1 | 3 | 1.000000 | **PASS** | **PASS** | 0 | **PASS** |
| W25-4k-b8 | 8 | 3 | 1.000000 | **PASS** | **PASS** | 0 | **PASS** |
| W25-4k-b32 | 32 | 3 | 1.000000 | **PASS** | **PASS** | 0 | **PASS** |
| W25-4k-b128 | 128 | 3 | 1.000000 | **PASS** | **PASS** | 0 | **PASS** |

This is the plan's acceptance clause: **one fsync for a batch** (`fsyncs_per_group == 1.0`
for every batch size) and **correct visibility after restart** (fresh-process re-read
verified the deterministic position-indexed pattern in every cell; wrap-arounds are
reported). It is proven by the counters/identity, not by a syscall trace; "restart" =
process restart (no power-loss test, §9.1).

### 8.2 Cost / latency per cell (all reps kept)

| cell | batch | n | groups/s | writes/s | MiB/s mean (CV%) | group p50/p99 ms | sync p50/p99 µs | syncs/s | MiB/sync | sync_wait_share |
|---|---|---|---|---|---|---|---|---|---|---|
| W25-4k-b1 | 1 | 3 | 1521.5 | 1522 | 5.9 (29.7) | 0.581 / 2.195 | 580.1 / 2189.8 | 1521.5 | 0.004 | 0.997 |
| W25-4k-b8 | 8 | 3 | 2011.1 | 16089 | 62.8 (32.4) | 0.496 / 1.146 | 489.4 / 1134.4 | 2011.1 | 0.031 | 0.987 |
| W25-4k-b32 | 32 | 3 | 2350.0 | 75201 | 293.8 (2.2) | 0.397 / 0.686 | 373.1 / 658.0 | 2350.0 | 0.125 | 0.943 |
| W25-4k-b128 | 128 | 3 | 1441.4 | 184497 | 720.7 (1.1) | 0.669 / 0.976 | 574.4 / 876.4 | 1441.4 | 0.500 | 0.861 |

Grouping N writes per commit raises durable-write throughput by ~two orders of magnitude
(5.9 → 720.7 MiB/s; 1,522 → 184,497 4 KiB writes/s) at a constant **one `fdatasync` per
batch**, i.e. 1,521 → 1,441 syncs/s while the batch bytes per sync rise from 4 KiB to
512 KiB. Latency of `commit()` (the public durability point; an **upper bound** on the sync
only, because it includes waiting for the batch writes) stays sub-millisecond at p50 for
batch ≥ 8 and ≈ 0.67 ms at b128; p99 ≤ 2.2 ms. `sync_wait_share` (commit time / measured
elapsed) falls from 0.997 (b1, sync-dominated) to 0.861 (b128), as expected.

### 8.3 Disclosures

- **W25 is JUring-only**: seastar has no group-commit primitive. The nearest seastar
  equivalent is the W24 `seastar-fdatasync` arm (one `fstream::flush()` per batch, which also
  drains write-behind) — an upper bound, not a matched comparison.
- `sync_latency_us` is the `commit()` call duration (includes waiting for the batch writes;
  the public API exposes no sync-only hook), so it is an upper bound on sync-only cost.
- The file is a sparse 1 GiB preallocation under `nvme-data/write_files/`; offsets wrap
  modulo the file capacity and the pattern is position-indexed, so the fresh-process
  verification re-reads a deterministic prefix (wrap-arounds reported per run).
- Rests per A2 (5 s inter-rep, 120 s between cells); this is a count/latency micro-benchmark,
  not a throughput campaign.

## 9. Known limitations / residual risks

Mirrors design §9; campaign-specific disclosures are completed in §7/§8 once the sessions
are analysed.

1. **Durability is only as strong as `fdatasync`/`fsync`/`O_DSYNC` + the device cache
   policy.** No power-loss test was possible; "restart" = process restart. The B6 JUnit crash
   test runs on `@TempDir` (tmpfs) and therefore proves **process-restart visibility**, not
   media durability; the device-backed fresh-process re-read is the W25 bench `--verify-only`
   on the `nvme-data` ext4 file, and the "one sync per batch" claim is proven by the two
   independent counters (design §3.5), not by the tmpfs file.
2. **`GroupCommit` is single-owner and caller-driven** — not a daemon, WAL writer or
   concurrent group-commit implementation; cross-thread batching is out of scope (B4
   territory). A batch is capped at `maxBatchSize` writes and the caller must `commit()`
   before the cap; the memory of each source buffer must stay valid until commit (no copy).
3. **No `commit()` timeout** in v1: a hung device hangs a commit like `JuringFile.write()`.
   Callers needing deadlines use the B1 timed prepare/await API directly.
4. **`fadvise` is advisory** and mostly inert on `O_DIRECT` files; it is the first blocking
   metadata syscall on `JuringFile`. `WILLNEED`/`DONTNEED` can do real work and must not be
   called from a B4 shard task (where a shard's armed wakeup read makes `JuringFile` blocking
   helpers throw anyway). B6 makes no performance claim about fadvise.
5. **Stream `DSYNC` is an assertion, not a flag-applier**: the file must be opened with
   `LinuxOpenOptions.DSYNC`/`SYNC`; a mismatch fails fast at open (documented by design).
6. **M2 write-arm asymmetry** (§6): disclosed here for the first time; B6's W24 removes it and
   runs writeback controls; no new cross-engine write claim.
7. **W24 durability points include a write-behind drain** (both engines' `flush()` drains),
   so `sync_wait_share` is an upper bound on pure sync cost; the matched arms isolate the
   sync portion (writeback = drain only vs fdatasync = drain + one sync). Seastar has no
   `fsync(2)` path through `fstream`, so the `fsync` arm is JUring-only and disclosed as such.
8. **Write-state bimodality** of host NVMe (documented in M1/M2/M3) limits W24 to
   within-engine paired ratios with disclosed state classes, position-matched strata and
   bracket controls; all reps kept; CV > 10 % ⇒ "unstable, no ranking". The W24 dataset is
   2 GiB per pass on the existing 10 GiB file (never resized) — a disclosed deviation from
   the method's 10 GiB.
9. **Owner-thread counters** (`syncs()`, `GroupCommitStats`, `JuringFile.sync*`) are not JMX
   metrics (B5 scope ends at ring/shard/core metrics); read them from the owner thread.

## 10. Next steps

- **B6 follow-ups (only if needed):** a bounded `commit()` deadline; concurrent/multi-writer
  group commit; async `IORING_OP_FADVISE` (opcode 24) if a caller ever needs a ring-based
  hint; a `DSYNC` stream option that opens the file itself (currently an assertion). None is
  on the v1 critical path.
- **Plan-level:** B7 (provided-buffer ring + multishot, conditional on measurements) is the
  next library item; B8/M4 (QoS) remains conditional; B9 (API stability/docs) later.
- **Open from earlier sessions:** M1 write matrix (Step A), DS-M / host-root
  representativeness runs (Step D), publishing decisions (Step E), optional VT-throughput
  re-benchmark and an N=2 M3 re-run for the narrowest bar.
- **Publishing:** the durable W24/W25 evidence is supporting evidence, not a milestone
  sign-off; M2 remains the signed buffered comparison.

## 11. Provenance

- **Base tree:** branch `kafka-engine` at commit `d0d549a24d376765b038f0e18ab6841158b37201`
  (M3 signed; B5 delivered in `309335b`); baseline suite 349/0/0/1. B6 tree (feature commit)
  and docs updates are the session-9 commits listed below.
- **Frozen contract:** `docs/b6-design.md` sha1
  `7490aa9bb7629f5705b880ef7f870dcb11d27152` (D1 + amendments A1/A2; frozen before
  implementation and unchanged through the official campaigns and verification). Design
  inputs: `scratch/b6-lead/research-seams.md`, `scratch/b6-lead/research-bench.md`.
- **Frozen tree identity (G3b; re-derived unchanged after G4):**

| item | sha1 |
|---|---|
| `src/main/java` content | `5690c49878f87df802211a81d56d6738660a0b85` |
| `src/test/java` content | `6796f83e1ca94788a7573f8ed5b85890bf8e0e15` |
| `bench` code/scripts/docs content (excl. results) | `b2eb2d80b211bc9a5621fe24c5ae6d5f5c800af7` |
| `bench/stream/analyze_stream.py` (unchanged from HEAD) | `9a24b3fc0fa7451a3312d77f4f06b39aca0d3a32` |
| `JUringStreamBench.java` | `9cdf2aa4c42dc828f003278eb1bccac53a4d541c` |
| `seastar_stream_bench.cc` | `124c1526457d6b917d6bdf7129bc7849948e2aa4` |
| `JUringDurabilityBench.java` | `9190c4300a889b8a9e6fd45902db35cc5d159489` |
| `run-durability-ab.sh` / `analyze_durability.py` | `eb474f8e…` / `438694a0…` |
| `run-group-commit-bench.sh` / `analyze_group_commit.py` | `89bb2075…` / `fbc1c468…` |

  Full record: `scratch/b6-lead/FREEZE.md`, `scratch/b6-lead/code-hashes.txt`,
  `scratch/b6-lead/session-hashes.txt`. Scope: `git diff -- src/test` empty (all pre-existing
  tests byte-identical); `pom.xml` and `module-info.java` unchanged; only the expected B6
  source/test/bench/doc files added/modified.
- **Authoritative suite:** `scratch/b6-lead/g3b-suite.log` — `mvn -o test` in the repo root:
  401 tests / 0 failures / 0 errors / 1 skipped, BUILD SUCCESS (2026-11-15T08:25 local);
  `mvn -o -q -DskipTests package` clean; `target/classes` built from this tree and used by
  both campaign arms.
- **Official sessions (gitignored):**
  `bench/results/b6-durability-2026-11-15/` (W24: 19 invocations, 19 ok, 0 errors,
  07:29:59Z→07:55:26Z; manifest `012283f5…`, aggregate json `7e4aaea4…`) and
  `bench/results/b6-group-commit-2026-11-15/` (W25: 12 invocations, 12 ok, 0 errors,
  07:55:31Z→08:06:24Z; manifest `80455a92…`, aggregate json `2361e801…`). Exact reproduction
  commands in the session manifests and report §7/§8; the drivers are
  `bench/stream/run-durability-ab.sh` and `bench/stream/run-group-commit-bench.sh` (both
  `--dry-run` first, preflight-gated, `--resume`-capable, all reps kept).
- **Independent verification:** `scratch/b6-verify/VERDICT.md` — **PASS, STATUS FINAL**:
  55/55 adversarial modes, 1,680 PASS assertions, 0 failures, 0 skips (fresh JVM + external
  timeout each, own classpath from a byte-identical private copy, never `target/classes`);
  private-copy `mvn -o test` 401/0/0/1 BUILD SUCCESS; 4,916 bench-artifact assertions PASS
  (W24 1,060, W25 895, additive-vs-M2 2,952, M2 re-aggregation 6, fail-loud 3); static/scope
  43/43; frozen identity re-derived before/after and unchanged; no library defect (F1/F2
  contract-wording/report-record observations only, no amendment).
- **Commits (branch `kafka-engine`, no unrelated changes):** feature commit
  **`2883b342df28487808e9ed41889ec964a289a6f2`** (`feat(api): add fadvise hints, durability
  modes and group commit (B6)`; src + tests + bench + frozen design), followed by the docs
  commit that adds this report, HANDOVER §0.0, SEASTAR-PLAN v1.6 and the B1/B2 B6 addenda
  (`docs(b6): B6 report (delivered, verified); HANDOVER §0.0; SEASTAR-PLAN v1.6; B1/B2 B6
  addenda`). **Final Lead suite on the committed feature tree: 401 tests / 0 failures /
  0 errors / 1 skipped, BUILD SUCCESS** (`scratch/b6-lead/g6-final-suite.log`).
