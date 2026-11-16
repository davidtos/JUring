# B5 report — observability (ring metrics, latency histogram, JMX, benchmark JSON)

**Status:** DELIVERED + INDEPENDENTLY VERIFIED (PASS), session 7, 2026-11-14 — **M3 is NOT
signed off by this report**
(B5 observability only; the full R=5 W17-W20 campaign incl. seastar `--smp N` and the
restated M3 bars remain, `docs/b4-report.md` §8).
**Contract:** [`docs/b5-design.md`](b5-design.md) (frozen; includes amendments A1/A2/A3).
**Plan:** `SEASTAR-PLAN.md` §4 B5 / §5 M3. **Baseline tree:** `kafka-engine` at `6cc1a75`
(B4 delivered in `159d60f`; 309 tests green).

## 0. TL;DR

**B5 is delivered and independently verified (PASS); M3 is NOT signed off.** JUring now has
the observability layer the L2/M3 comparison needed:

- **Per-ring metrics** on every `JUring` (`RingMetrics`: 18 components — prepared /
  submitted / reaped / completed / failed / cancelled / timed-out / in-flight, SQ+CQ
  occupancy, enter/batch/retry/SQ-full counts, CQ-overflow events), readable from any
  thread and frozen after close.
- **A dependency-free latency histogram** (`LatencyHistogram`/`LatencySnapshot`, 512
  fixed log-linear buckets, allocation-free record, bucket-max percentiles) and
  **per-shard runtime-completion latency** in the B4 shard runtime
  (`ShardOptions.withLatencySampleEvery(N)`, disabled by default, deterministic sampling).
- **Shard/group snapshots** (`ShardMetrics`, `GroupMetrics`, `IoShard.metrics()`,
  `RingGroup.metrics()`/`aggregateMetrics()`).
- **JMX binding** (JDK-only, no new runtime dependency): per-shard and per-group MXBeans,
  pull-based attributes, deterministic ObjectNames, rollback, idempotent handles.
- **Benchmark integration** (JSON schema 1.1): per-ring `ring_metrics`, measured-phase 1 Hz
  `ring_series` (queue depth + p99), library `dispatch_latency_us`, aggregate queue-depth
  and p99 — for both runtimes; the analyzer emits per-ring queue-depth/p99 tables.
- **Overhead A/B vs the B4 baseline build: PASS** — mean paired throughput ratios
  `1.0190 / 1.0110 / 1.0070` (shard N=1 / shard N=8 / threads N=1), i.e. **no measurable
  cost** (35-40 % of the < 1 % budget in point estimate terms; CIs are wider, see §6).
  Final config: metrics on, `--metrics-interval 1.0 s`, `withLatencySampleEvery(1)`.
- **Tests:** `mvn -o test` → **349 tests, 0 failures, 0 errors, 1 skipped** (309 B4
  baseline unchanged + 40 new), BUILD SUCCESS 16.698 s on the frozen tree.
- **Independent adversarial verification: PASS** (60/60 modes, 2 355 assertions, own
  classpath, fresh JVMs; see §5 and `scratch/b5-verify/VERDICT.md`).

M3 needs the campaign, and the plan-M3 scaling bars (`SE(8)>=0.75`, `p99(8)<=1.15*p99(1)`)
still need restating (`docs/b4-report.md` §6/§8); B5 provides the per-ring queue depth and
p99 data those bars will be reported from.

## 1. What was delivered

| item | status | evidence |
|---|---|---|
| Frozen contract `docs/b5-design.md` (API/semantics/benchmark-overhead protocol, written before implementation) | DONE | design + A1/A2 amendments |
| `RingMetrics` per-ring counters/occupancy + `JUring.ringMetrics()` | DONE | `B5RingMetricsTest` (14) |
| `LatencyHistogram`/`LatencySnapshot` (A1.2 layout, bucket-max percentiles) | DONE | `B5LatencyHistogramTest` (11) |
| Shard runtime-completion latency sampling (`ShardOptions.withLatencySampleEvery`) | DONE | `B5ShardMetricsTest` (10) |
| `ShardMetrics`/`GroupMetrics`/`IoShard.metrics()`/`RingGroup.metrics()`/`aggregateMetrics()` | DONE | `B5ShardMetricsTest` |
| JMX binding (`IoShardMetricsMXBean`, `RingGroupMetricsMXBean`, `MetricsJmx`) | DONE | `B5JmxTest` (5) |
| Benchmark JSON schema 1.1 (metrics + series + dispatch latency, both runtimes) | DONE | `bench/juring/JUringFileBench.java` + smoke |
| Analyzer per-ring queue depth + p99 (tolerant of old sessions) | DONE | `bench/shard/analyze_shard.py` |
| Overhead A/B driver + analyzer, official session | DONE — PASS | `bench/shard/run-b5-overhead.sh`, `bench/results/b5-overhead-2026-11-14/` |
| Required JUnit matrix (design §11) | DONE — 40 new tests | §4 |
| Independent adversarial verification | PASS (60/60 modes) | §5, `scratch/b5-verify/VERDICT.md` |
| M3 campaign | NOT IN B5 SCOPE | `docs/b4-report.md` §8 |

## 2. Public API (as frozen in design §2, incl. A1/A2)

New public types in `com.davidvlijmincx.lio.api`: `RingMetrics`, `LatencySnapshot`,
`LatencyHistogram`, `ShardMetrics`, `GroupMetrics`, `IoShardMetricsMXBean`,
`RingGroupMetricsMXBean`, `MetricsJmx` (with nested `Handle`).
Additive methods/settings: `JUring.ringMetrics()`, `IoShard.metrics()`,
`RingGroup.metrics()`, `RingGroup.aggregateMetrics()`,
`ShardOptions.DEFAULT_LATENCY_SAMPLE_EVERY` / `latencySampleEvery()` /
`withLatencySampleEvery(int)`. Run-time dependency added: none (JMX is `java.management`,
a JDK module; `module-info` gains `requires java.management;`). `ShardStats` is unchanged
(B4 compatibility).

### 2.1 `RingMetrics` (18-component record)

`sqesPrepared, submitted, reaped, completed, failed, cancelled, timedOut, inFlight, enters,
submitBatches, submitErrors, submitRetries, sqFullEvents, sqQueued, sqEntries, cqReady,
cqEntries, cqOverflowEvents`. Frozen invariants: all counters monotone; `reaped ==
completed + failed`; `cancelled <= failed`; `timedOut <= failed`; `inFlight ==
max(0, submitted - reaped)`; after close counters are frozen, `sqQueued == cqReady == 0`,
effective ring depths retained. Ring counters count every SQE/CQE on the ring including
runtime-internal ops (eventfd wakeup read, shutdown cancel); user-level shard counters stay
in `ShardStats`.

### 2.2 `LatencyHistogram` / `LatencySnapshot`

512 slots: 0..15 ns exact, 16..63 unused padding, 64..511 log-linear (16 sub-buckets per
octave, values >= 16 ns), range `[0, 2^32-1]` ns; negatives clamp to 0, larger values clamp
to the max. `count/total/min/max` exact; `p50/p90/p99/p999` are the **inclusive bucket
maximum** of the quantile bucket, clamped to the exact recorded `maxNanos` (A3; exact for
0..15 ns and bucket maxima; relative over-estimate < 1/16 for values >= 16 ns when not
clamped). `record()` allocates nothing; `snapshot()` may
allocate (off the hot path); `merge()` is bucket-exact.

### 2.3 Shard/group metrics and JMX

`ShardMetrics(stats, ring, dispatchLatency)`; `dispatchLatency` = **runtime completion
latency (registration→dispatch)**: measured on the shard thread from a successful
`ShardContext.onCompletion` registration to immediately before the handler runs, sampled
deterministically every Nth registration (`0` = disabled by default; `1` = all), empty for
body-hosted (`JUringBlocking`) shards. `GroupMetrics` sums the ring counters and merges the
shard histograms bucket-wise. `MetricsJmx.register(IoShard|RingGroup)` registers
pull-based MXBeans (`type=IoShard,instance=<N>,id=<i>` / `type=IoShard,group=<N>,id=<i>` /
`type=RingGroup,instance=<N>`), duplicate registration rejects, group registration rolls
back on failure, handles are idempotent, attribute reads never throw (latency in
microseconds).

## 3. Implementation summary

- **Engine counters** (`IoUringDispatcher`): a 12-slot `RingCounters` with owner-thread
  opaque (atomic, non-tearing) writes and acquire snapshot reads; SQ/skhead progress for
  `submitted` (no double count on EAGAIN/EINTR retries, retries counted separately); CQE
  classification in **every** consumption path (`reapAvailable`, `reapRawCqes`, list
  `peekForBatchResult`, `waitForResult`/`cqAdvance`, bounded-close drain); live occupancy
  from the shared ring (`sq. khead`, `cq. ktail/khead`, `cq.overflow`); all 8
  `io_uring_enter` sites counted; snapshot and freeze serialised by a metrics-only lock so
  the after-close freeze can never race `munmap` (hot path stays lock-free).
- **Shard latency** (`CompletionTable` + `IoShard`): a parallel `long[]` timestamp carried
  through insertion, backward-shift deletion, full-table (F1) failure and clear — no extra
  table, no allocation, failed `put` leaves nothing behind. Sampling is a per-shard
  1-based registration counter; only sampled registrations call `System.nanoTime()`; the
  histogram is allocated only when sampling is enabled and recorded immediately before the
  handler invocation. The final ring snapshot is cached before the ring reference is
  dropped, so `IoShard.metrics()` is total (never throws) before/during/after close.
- **JMX**: `ManagementFactory.getPlatformMBeanServer()`, pull-based standard MXBeans
  (package-private adapters), per-JVM monotonic instance ids, duplicate-registration
  `IllegalStateException`, group rollback, idempotent `Handle.close()`; no exporter thread,
  no background cost.
- **Benchmark**: schema 1.1 adds the fields additively; one sampler thread per process
  reads the cross-thread snapshot API only during the measured phase; `--metrics off` and
  `--metrics-interval 0` supported; old schema-1.0 consumers keep working.

## 4. Test evidence

**Frozen-tree full suite (Lead):** `mvn -o test` → **Tests run: 349, Failures: 0,
Errors: 0, Skipped: 1**, BUILD SUCCESS **16.698 s**
(`scratch/b5-lead/lead-freeze-full-suite.log`). Baseline before B5: 309/0/0/1. B5 adds
**40 tests** in 4 new classes; no existing test was modified or weakened
(`git status src/test` = new files only; byte identity checked by the independent verifier).

| class | tests | coverage (design §11) |
|---|---|---|
| `B5RingMetricsTest` | 14 | exact counters for NOP/read/mixed workloads; invalid-fd failed; cancelled (-125); kernel timeout (-62, A1.1); classification rule; occupancy (prepared/submitted/reapable); SQ-full/batch/enter accounting; before/after close + frozen; cross-thread monotone/no-throw; close-with-abandonment consistency; foreign sampler racing close(0); zero-allocation hot path |
| `B5LatencyHistogramTest` | 11 | empty; exact count/sum/min/max/mean; reset; bucket-max percentile correctness + monotonicity over many values; bucket-max exactness; negative/large clamps; merge equals recording all values; immutable snapshots; zero allocation per record |
| `B5ShardMetricsTest` | 10 | lifecycle shape + per-shard isolation; disabled => empty; sampling every=1 (samples==dispatched); every=4 exactly floor(dispatched/4) (A2.1); failed registration consumes no sample/timestamp; group aggregation identity; any-thread sampling during a burst; body-hosted empty latency + real ring metrics; handler-path 0 B/call; CompletionTable timestamp carry/F1 |
| `B5JmxTest` | 5 | standalone bean vs snapshots/lifecycle; group names/order/aggregates/distinct instances; live attribute reads during load + disabled latency = 0.0; rollback of a partially registered group; register/unregister cycles leak no threads/fds |

**Zero allocation (metrics on):** engine hot loop (`prepareNop` +
`submitAndWaitForBatchResult`) **0 B/op over 50 000 ops**; `LatencyHistogram.record()`
**0 B over 500 000 observations**; shard registration path 0 B over 2 000 ops (all
rounds), shard dispatch path minimum over repeated rounds 0 B (a sporadic single-round JVM
one-off observed once is documented in `scratch/b5-runtime/STATUS.md` §3.5); standalone
B4-vs-B5 allocation/throughput probe identical (0.0007 B/op both arms; NOP median 334.5 vs
333.1 ns/op — parity, informational only).

**JMX/latency evidence:** MXBean attributes equal the frozen snapshots exactly on closed
shards (counters/booleans/latency µs); group ObjectName order/instance ids distinct; live
attribute sampling during a burst never throws; disabled sampling => 0.0; sampling
every=1 → samples==dispatched (64/64), every=4 → 16, duplicate registration consumes no
index; body-hosted shards report empty latency + real ring metrics; 90 register/unregister
cycles keep thread/fd counts stable.

## 5. Independent verification

**Verdict: PASS** — independent of the authors, on the frozen tree
(`scratch/b5-verify/VERDICT.md`): the verifier re-derived the four frozen hashes, copied
`src/` byte-identically, compiled its own classpath (never `target/classes`), and ran a
**60-mode adversarial matrix** in fresh JVMs under external timeouts, plus its own full
`mvn -o test` (349/0/0/1) and an independent bench-JSON/analyzer/A-B recomputation.
Details in `scratch/b5-verify/VERDICT.md` (modes × assertions, defects found before
delivery, residual risks). **Verdict: PASS** on the G3-frozen tree, independent of the authors. The verifier
re-derived the content hashes before/after every phase (unchanged), copied `src/`
byte-identically, compiled its own classpath (class-dir sha1 `5dcfe696…`; never
`target/classes`), and ran **60/60 adversarial modes in fresh JVMs under external timeouts**
(6 harnesses, **2 355 PASS assertions, 0 failures, 0 fatal markers, 0 timeout kills**; two
consecutive clean full runs): engine counter/occupancy exactness (NOP/read/mixed/invalid-fd/
cancel/-62-timeout/close-with-inflight), cross-thread no-tear/no-throw snapshots, histogram
oracle (brute-force + HdrHistogram; A1.2/A3 boundaries incl. 16/31/32→33 and clamps),
shard/group metrics + A2.1 sampling determinism (every=1/2/3/4/5/7), JMX
registration/duplicate/rollback/leak semantics + attribute reads during load and after
close, frozen races/close/leak lifecycle. It independently re-ran `mvn -o test` in a
private copy (**349/0/0/1**, 58 pre-existing test files byte-identical to HEAD) and
re-derived the bench-artifact checks from the raw JSON (**21 365 assertions, 0 FAIL**):
paired ratios/CI independently recomputed and equal to the analyzer
(`1.0190 / 1.0110 / 1.0070`), both state-gate labellings PASS, §4 per-ring queue-depth/p99
recomputed for all 10 rings, archived first-pass analysis metric-identical. Zero-allocation
steady state 0 B on all documented metrics-on recording paths, identical to an
independently built B4 baseline. **Findings:** F1 = contract wording only (histogram
percentile clamping: `{32}`→32, `{32,32,5000}`→33) resolved by amendment A3, no source
change; F2 = analyzer aggregate `frozen_tree` identity nulls, a disclosure-only
bench-analysis gap since fixed by regenerating the aggregate with the frozen identity
(identity is also enforced in `manifest.txt`). **No open library defect / release blocker.**
Methodology provenance is recorded B4-style: the first matrix pass had 26 failing modes, all
25 non-contract failures were verifier-harness/assumption defects (11 root causes) and were
fixed before two clean full runs. Residual risks R1–R11: A3 was added docs-only during
verification; analysis-only post-session edit + identity regeneration; cross-thread snapshots
best effort by contract; zero-alloc steady-state methodology premise; body-hosted idle rings
show 0 counters (active ring real); `/proc` leak checks account for JVM-internal threads;
single host/kernel/JDK/flags; `cqOverflowEvents` best effort; dispatch latency is
runtime-boundary completion latency; **M3 explicitly not signed off**. Evidence:
`scratch/b5-verify/VERDICT.md` (STATUS FINAL), `logs/matrix/` (60 logs),
`logs/mvn-test.log`, `logs/bench-check-final.log`, `logs/provenance-after.txt`.

## 6. Overhead A/B evidence (< 1 % throughput cost)

**Protocol (design §7.1).** Two arms: `B4` = build of commit `6cc1a75` (no B5 metrics) and
`B5` = the frozen tree with metrics **on** (per-ring counters, 1 Hz `ring_series`,
`withLatencySampleEvery(1)`). Both built from the same host/JDK/flags; fresh JVM/process per
invocation; identical ring flags/QD/CPU placement/dataset/seed; arms interleaved with the
order alternating per rep; 4K randread, QD64/worker, CPUs 1..N, aged 10 GiB read-only file,
10 s warmup + 30 s measured, **R=5**, 30 s rests, every rep kept, state gate disclosed.
Session: `bench/results/b5-overhead-2026-11-14/` (gitignored; 30/30 invocations
`status=ok`, frozen hashes enforced at preflight, machine alone at loadavg 0.36 start).

| cell | workers | B4 mean IOPS | B5 mean IOPS | mean paired ratio | stdev | one-sided 95 % CI lower | min rep ratio | point overhead | verdict |
|---|---|---|---|---|---|---|---|---|---|
| shard1 | 1 | 315 086 | 320 896 | **1.0190** | 0.0300 | 0.9910 | 0.9910 | −1.95 % | **PASS** |
| shard8 | 8 | 454 835 | 458 900 | **1.0110** | 0.0400 | 0.9730 | 0.9570 | −1.10 % | **PASS** |
| threads1 | 1 | 323 237 | 325 307 | **1.0070** | 0.0370 | 0.9720 | 0.9543 | −0.70 % | **PASS** |

Per-rep paired ratios (never trimmed): shard1 `1.0007, 1.0555, 1.0487, 0.9909, 1.0016`;
shard8 `0.9986, 1.0411, 1.0010, 0.9568, 1.0575`; threads1 `1.0425, 1.0421, 0.9974, 0.9543,
0.9988`. No individual rep below 0.95 (min 0.9543), so there is no rep-floor violation under
either state-gate labelling (literal 600 µs vs the Lead-approved cell-adjusted
600 µs × workers; both PASS; the primary mean bar is identical under both because every rep
enters every mean — the labelling only decides which sub-0.95 reps are disclosed as
state-gated). Point estimates slightly above 1 are within the paired-rep stdev: the honest
reading is **no measurable throughput cost at this power**, not a speedup claim. Host
bimodality is disclosed: one shard1 B4 rep and both shard8 r5 arms were slow-state; they stay
in every mean. **Final configuration:** metrics on, `--metrics-interval 1.0`, sampling
every=1; no cheaper-sampling fallback re-run was needed.

## 7. Benchmark integration + per-ring queue depth/p99 (M3 data source)

`JUringFileBench` schema 1.1 (additive; old 1.0 fields unchanged) emits, for both runtimes:
per-worker `ring_metrics` (all 18 components, snake_case), a measured-phase 1 Hz
`ring_series` (`t_sec, in_flight, sq_queued, cq_ready[, dispatch_p99_us]`) sampled by one
sampler thread through the cross-thread snapshot API, and (shard runtime) per-worker
`dispatch_latency_us`; run-level `metrics`; aggregate `ring_metrics` (mean over runs,
sum over shards), `ring_queue_depth` (`mean/max_in_flight`, `mean_sq_queued`,
`mean_cq_ready`) and `dispatch_latency_us`. `analyze_shard.py` consumes the new fields
tolerantly (old sessions still analyze, B5 columns `n/a`) and adds per-ring queue-depth/p99
tables. **This is the M3 "per-ring queue depth and p99" evidence.**

Demonstration from the G4 session B5 arm (`aggregate/overhead.md` §4):

| cell/ring group | mean in-flight | max in-flight | mean sq_queued | mean cq_ready | runtime-completion p99 | external p99 | failed/cancelled |
|---|---|---|---|---|---|---|---|
| shard1 ring 0 | 62.26 | 65 | 0.04 | 2.35 | 537.4 µs | 491.1 µs | 0 / 0 |
| shard8 per ring (0..7) | 64.09–64.88 | 65 | 0.09–0.80 | 0.01–0.21 | 2831–4116 µs | 2505–3943 µs | 0 / 0 |
| threads1 ring 0 | 59.44 | 63 | 0.00 | 0.27 | n/a (no library histogram in raw runtime) | 483.0 µs | 0 / 0 |

The shard8 p99 split (rings on the dedicated hctx CPUs lower at 2831 µs; the shared-pair
CPUs at ~4063–4116 µs) reproduces the known B4 CPU/NVMe-hctx topology diagnostic
(`docs/b4-report.md` §6), not a runtime defect. **The `dispatch_latency_us` p99 is a
runtime completion latency (registration→dispatch; includes kernel/device completion,
excludes SQE prep and handler body) — it is the M3 per-ring p99 and is NOT fio `clat` and
NOT the external `latency_us`** (design A2.2).

## 8. Known limitations / residual risks

Full disclosure: design §10 + A1/A2. Summary:

1. Ring counters include runtime-internal wakeup/cancel ops; ring-level and shard-level
   counters are labelled and must not be summed as if both were user ops.
2. Snapshots are best effort, not linearizable; `ShardMetrics` is three separate snapshots;
   counters can lag the owner/kernel by one loop pass.
3. JMX is pull-based (per-read snapshot cost; operator-chosen polling cadence), local
   platform `MBeanServer` only — no remote connector/auth, no exporter thread.
4. Histogram percentiles are inclusive bucket maxima (strictly < 1/16 relative
   over-estimate for values >= 16 ns); do not use them for single-request SLO enforcement.
5. Shard completion latency covers the runtime boundary only; not end-to-end application
   latency; not comparable to fio `clat`/external `latency_us`.
6. Body-hosted (`JUringBlocking`) shards have no dispatch histogram (B4 body seam
   unchanged); ring metrics are real.
7. `cqOverflowEvents` is a best-effort kernel counter (u32, NODROP behaviour); not
   force-triggered.
8. Overhead is established for the §7.1 protocol (4K randread QD64, N=1/8, R=5); CPU-only
   microbenchmarks are a different relative-cost regime (the NOP sanity probe showed parity
   but is informational only, not the acceptance bar).
9. Micrometer is **not** shipped as a dependency (deliberate; see §9); a snapshot-based
   consumer bridge is documented.

## 9. Micrometer decision (design §9)

B5 ships **JMX only** as the built-in binding, plus the immutable snapshot API as the
binding seam. Rationale: JUring is library-first with zero runtime dependencies; adding
Micrometer (and transitives) for every user is a product/API decision for B9/the flagship
framework, not a benchmark necessity. M3's per-ring queue depth and p99 are available via
the snapshot API, JMX and the JSON. A consumer-side Micrometer adapter is ~2 lines per
gauge:
`Gauge.builder("juring.shard.in_flight", group, g -> g.aggregateMetrics().inFlight()).register(registry);`
An optional `juring-micrometer` module remains out of scope; the frozen snapshots make it
additive later.

## 10. Next steps (M3 complete path)

- **M3 campaign** on top of B5: full W17-W20 R=5 / state-gated / 300 s rests, seastar
  `--smp N` runner (not yet implemented), DS-M/per-shard-file decision, seastar 90 % CI LB;
  report per-ring queue depth + p99 from this JSON schema (and the restated bars: compare
  SE/latency against `fio` at the same N or hold total QD fixed — B4 §6/§8).
- Topology-aware placement for the campaign: one shard per NVMe hctx or unpinned; record
  `mq/*/cpu_list` (B4 §6).
- Optional follow-ups: VT-throughput re-benchmark of `JUringBlocking` on shards; B3 §11.9/F1
  class of limitations unchanged; B6 (buffered durability) still open; M1 write matrix
  still open from earlier sessions.

## 11. Provenance

- Branch `kafka-engine`; base commit `6cc1a751f1c652ead1bfa05f7941f75af8fb5ecf` (B4
  delivered in `159d60f`). B5 design frozen at `docs/b5-design.md` sha1
  `ff60e870d0d4d08acbbd6bc9931b0a9d457f0698` (final, incl. A1/A2/A3).
- Frozen source identity at verification: `src/main` content sha1
  `04f12dbc85d723a10dd952b7ac3c51bb6253ed46`, `src/test` content sha1
  `666e0644dc063c0b15c5bb52571002a79b5ec657`, tracked `git diff src/main/java` sha1
  `2933a9064465f4e85968052fba2b2a8eabb20790` (identical before/after the G4 session and
  after the G5 verification — see `scratch/b5-lead/FREEZE.md`,
  `scratch/b5-verify/VERDICT.md`).
- Frozen-tree full suite: 349/0/0/1, BUILD SUCCESS, 16.698 s
  (`scratch/b5-lead/lead-freeze-full-suite.log`); baseline 309/0/0/1
  (`mvn -o test` on untouched `6cc1a75`).
- Overhead session: `bench/results/b5-overhead-2026-11-14/` (gitignored) —
  `aggregate/overhead.md/.json`, 30/30 invocations ok; baseline arm from `git archive
  6cc1a75`; B5 arm = frozen workspace tree with metrics on.
- B5 commits: **feature commit `309335b`** (`feat(api): add ring metrics, latency
  histogram, JMX and bench JSON (B5)` — code, tests, bench, `docs/b5-design.md`); this
  report + HANDOVER §0.0 + SEASTAR-PLAN v1.4 follow in the docs commit.
- Machine: AMD Ryzen 9 3950X (16C/32T), 62 GiB, Fedora 44, kernel 7.0.12, JDK 26.0.2
  (Maven runtime JDK 25.0.3), data NVMe GIGABYTE GP-ASM2NE6100TTTD (`nvme1n1`), aged 10 GiB
  read-only dataset `/mnt/TestData/read_files/single_file_bench.bin` (read-only, never
  written). B5 adds no runtime dependency; JMX uses the JDK `java.management` module.
