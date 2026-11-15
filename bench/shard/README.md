# `bench/shard/` — W17-W20 shard-scaling bench + B5 overhead A/B

**M3 status: SIGNED OFF PASS (2026-11-15).** The official M3 campaign is
`run-m3-campaign.sh` + `analyze_m3.py` (frozen bars/protocol in `docs/m3-design.md`,
deliverable `docs/m3-report.md`, session `bench/results/m3-2026-11-15/` — 60/60
invocations `status=ok`). It adds the seastar `--smp N` multi-shard runner, R=5,
300 s rest per invocation, state gating, resumable chunks, the restated same-N-vs-`fio`
bars (a)–(e), and per-ring queue-depth + runtime-completion-p99 tables from B5 JSON 1.1.
The older B4 preliminary cells below are the historical R=3, one-session,
single-shared-file subset from `docs/b4-design.md` §13 — kept for provenance, not a
sign-off. B5 (docs/b5-design.md §7/§7.1) added the benchmark schema-1.1 per-ring
metrics and the official observability overhead A/B session (`run-b5-overhead.sh` +
`analyze_b5_overhead.py`); its analyzer emits the per-ring queue-depth/p99 tables that
were the M3-report data source.

This directory contains:

| file | purpose |
|---|---|
| `run-shard-scaling.sh` | B4 preliminary scaling driver: N ∈ {1,2,4,8} × {`juring-group`, `juring-manual`, `fio`}, CPUs 1..N, 10 s warmup + 30 s measured, R=3, 60 s rests, fresh process/JVM per rep |
| `analyze_shard.py` | analyzer: total IOPS, ratio vs fio, SE(N), per-shard min/mean, p99(N)/p99(1), preliminary acceptance bars, all reps reported (never trimmed); **B5 schema 1.1**: per-ring queue depth + library dispatch p99 + external p99, mean/max in-flight, sq_queued/cq_ready, failed/cancelled/timed_out (old sessions analyze with every B5 column `n/a`) |
| `run-m3-campaign.sh` | **M3 official campaign driver** (`docs/m3-design.md`): `juring-group` + `fio` + `seastar --smp N` at N=1,2,4,8, 4K randread QD64/worker, 10 s warmup + 30 s measured, **R=5, 300 s rest per invocation**, engines interleaved with order alternating per rep, fresh process/JVM, every rep kept; N=8 hctx placement; resumable (`--resume`) with per-invocation `cell-meta.tsv` + raw JSON and `--max-blocks K` chunking; the 300 s rest is made up across chunk boundaries |
| `analyze_m3.py` | **M3 frozen-bar analyzer**: one-sided 95 % t CIs (df=R−1) for bars (a)–(e), per-rep tables, state-gate disclosure (never trims), per-ring queue-depth + runtime-completion p99, PASS/FAIL/INCONCLUSIVE + M3-signing flag; writes `aggregate/m3.{md,json}` |
| `run-b5-overhead.sh` | **B5 overhead A/B driver** (§7.1): baseline arm = commit `6cc1a75` built in a `git archive` scratch tree (offline maven + its own javac, workspace untouched), B5 arm = frozen tree metrics on; cells shard N=1, shard N=8, threads N=1, 4K randread QD64/worker, CPUs 1..N, 10 s + 30 s, R=5, 30 s rests, interleaved arms with alternating order, fresh JVM per invocation, all reps kept; F14 gate + manifest + `--dry-run`/`--prep-baseline`/`--resume` |
| `analyze_b5_overhead.py` | **B5 overhead analyzer**: paired per-rep ratio B5/B4, mean/stdev/one-sided 95 % t CI (df=R-1), state-gated reps disclosed (fast p99 < 600 µs + IOPS anomaly + F14), PASS = mean >= 0.99 every cell and no non-gated rep < 0.95; writes `aggregate/overhead.{json,md}` |
| `README.md` | this file |

The shard-runtime mode of the JUring harness lives in
`bench/juring/JUringFileBench.java` (`--runtime=shard`); its default
(`--runtime=threads`) is the historical harness and its JSON is field-compatible
with pre-B4 output.

---

## 1. What is measured

| engine | command | what it is |
|---|---|---|
| `juring-group-N` | `JUringFileBench --runtime=shard --shards N` | one `IoShard`/one ring per worker in one `RingGroup`, CPUs `1..N` (best-effort `sched_setaffinity` per shard), registered file + registered fixed 4K buffers, QD 64 per shard. The pipeline is driven by `ctx.onCompletion` handlers dispatched by the shard's batched poll loop (the real B4 runtime path). |
| `juring-manual-N` | `JUringFileBench --runtime=threads --threads N` | the historical harness: one plain platform thread + one ring per worker; each thread runs its own self-driving fill/reap loop. No affinity beyond the taskset mask. |
| `fio-N` | `fio` io_uring, `numjobs=N`, matched registration | reference engine: `--registerfiles=1 --fixedbufs=1 --iodepth=64 --direct=1 --rw=randread`, pinned with `taskset -c 1..N`. |

Cell: **4K randread**, QD **64 per worker**, total QD = 64·N, dataset =
`/mnt/TestData/read_files/single_file_bench.bin` (aged 10 GiB, read-only),
O_DIRECT, 10 s warmup + 30 s measured, R=3, 60 s rest between rep blocks,
30 s settle between N values, one fresh process/JVM per invocation.

Only **read** cells are run (`--readonly` for fio, read-only O_DIRECT fds for
JUring). No write cells, no seastar (out of scope this session).

## 2. Frozen harness CLI (`bench/juring/JUringFileBench.java`)

Default behaviour is unchanged (plain threads, byte-compatible JSON fields/units):

```
bench/juring/run-juring.sh --workload randread --file <10GiB> \
    --bs 4k --qd 64 --threads N --seconds 30 --warmup-sec 10 \
    --runs 1 --seed 20261114 --readonly --label <id> --json <out.json>
```

Shard-runtime mode (new):

```
bench/juring/run-juring.sh --workload randread --file <10GiB> \
    --bs 4k --qd 64 --runtime=shard --shards N --first-cpu 1 \
    --seconds 30 --warmup-sec 10 --runs 1 --seed 20261114 \
    --readonly --label <id> --json <out.json>
```

New flags (frozen for this session):

| flag | meaning |
|---|---|
| `--runtime threads\|shard` | worker runtime; default `threads` (= historical behaviour, JSON unchanged) |
| `--shards N` | shard count for `--runtime=shard`; one `IoShard`/ring per shard; defaults to `--threads`; rejected with `--runtime=threads` |
| `--first-cpu N` | base CPU for shard affinity: shard `i` → `first-cpu+i` (default 1, `-1` = no affinity); `--runtime=shard` only |
| `--metrics on\|off` | B5 schema-1.1 metrics (default **on**): per-worker `ring_metrics`, measured-phase `ring_series`; shard mode also enables `ShardOptions.withLatencySampleEvery(1)` and emits per-worker `dispatch_latency_us`. `off` removes every B5 field and disables the shard latency sampling |
| `--metrics-interval SEC` | ring-series sampling interval during the measured phase (default `1.0`; `0` disables the series; shard mode) |

`--threads` is ignored/rejected as a conflict in shard mode: the worker count is
`--shards`. In both modes the JSON `config.threads` is the total worker count, so
"total QD = 64 × threads" stays directly comparable to fio `numjobs=N`.

**JSON additions (shard mode only; nothing is removed or renamed):**

- `config.runtime` (`"shard"`), `config.shards`, `config.first_cpu`;
- `runs[].shards[]`: per-shard `shard`, `elapsed_sec`, `ops`, `bytes`, `errors`,
  `seq_overflows`, `iops`, `mibs`, `latency_us`, plus `shard_stats` — the full
  `ShardStats` snapshot (`shard_id`, `thread_name`, `requested_cpu`,
  `current_cpu`, `affinity_applied`, `completions_registered/dispatched/
  unattributed/failures`, `in_flight_requests`, `ring_submits`, `wakeup_armed`,
  `cancelled_at_close`, `abandoned_at_close`, `close_timed_out`, …);
- `runs[].threads[]` is still populated in shard mode (same per-shard numbers,
  historical shape), so `bench/m1`/`bench/stream` style consumers keep working.

**B5 schema 1.1 additions (additive; both runtimes).** With `--metrics on`
(default) every worker entry (`runs[].threads[]`, and `runs[].shards[]` in shard
mode) carries `ring_metrics` (all frozen `RingMetrics` fields, snake_case),
`ring_series` (measured-phase samples `{t_sec, in_flight, sq_queued, cq_ready}`;
`dispatch_p99_us` in shard mode) and, shard mode only, `dispatch_latency_us`
(`{samples, mean, min, max, p50, p90, p99, p999}` in µs from the library
`LatencySnapshot`). `dispatch_latency_us` is the **runtime completion latency
(registration→dispatch)**: it starts at `ShardContext.onCompletion` registration
(before the SQE is submitted), so it includes kernel/device completion time plus
the runtime's post-completion dispatch; it excludes SQE preparation and the
handler body. It is the M3 per-ring p99, NOT a pure dispatch-overhead metric, and
NOT comparable to fio `clat` or the external `latency_us`. `runs[]` adds `metrics {enabled, interval_sec,
ring_series_points}`; `aggregate` adds summed `ring_metrics` (mean over runs),
`ring_queue_depth {mean_in_flight, max_in_flight, mean_sq_queued, mean_cq_ready}`
and (shard mode) `dispatch_latency_us` (mean of per-run percentiles). The series
is sampled by ONE benchmark-owned thread at `--metrics-interval` from the
cross-thread snapshot API (`JUring.ringMetrics()` / `IoShard.metrics()`), starting
at measured-phase start and stopping at its end. The library dispatch latency is
**not** comparable to the external `latency_us` reservoir and is never conflated
with it. `--metrics off` emits no B5 field; the historical 1.0 fields are
unchanged in every case (schema 1.1 is additive).

Latency convention (unchanged, all JUring modes): `System.nanoTime()` before
prepare/submit → completion handler; per-worker uniform Algorithm-R reservoir
(1,000,000 samples/worker), merged by the historical `mergeLatency`; report
p50/p90/p95/p99/p999/max in µs. fio reports `clat` (a different definition) —
fio tail values are not compared to JUring tail values; only the within-engine
p99(N)/p99(1) ratio is gated.

## 3. Driver

```bash
# plan only: preflight report + every exact command, no files, no I/O
bench/shard/run-shard-scaling.sh --dry-run

# the preliminary session (~40 min)
bench/shard/run-shard-scaling.sh

# trim to N={1,8} (only if the session is at risk -- say so in the notes)
bench/shard/run-shard-scaling.sh --n-list 1,8
```

Options: `--session NAME` (default `b4-<YYYY-MM-DD>`), `--n-list`, `--reps`,
`--seconds`, `--warmup`, `--rest`, `--settle`, `--force`, `--dry-run`.
Environment: `READ_FILE`, `JURING_RUN`, `FIO_BIN`,
`JVM_OPTS` (default `-Xms8g -Xmx8g -XX:+AlwaysPreTouch`), `FIO_GROUP_REPORTING`
(frozen template value 1), `ALLOW_RESIDUAL_LOAD=1`.

Protocol details:

- Engine order **alternates per rep** (`group,manual,fio` / `fio,manual,group`)
  so slow drift is common-mode (methodology D6); reps are never reordered after
  seeing results.
- Every invocation is a fresh process (fresh JVM for JUring) under
  `taskset -c 1..N`; fio `numjobs=N` on the same CPU set (F3/F4/F5). CPUs 0 and
  16–31 (SMT siblings) stay idle.
- First-message gate: **no Maven, no other benchmark harness, no foreign process
  >20 % CPU, governor `performance`, io_uring enabled, loadavg(1m) ≤ 0.5**
  (F14). Per-invocation the driver records `uptime`/`loadavg`/top-`ps` in
  `logs/<id>.env` and a `gate_start`/`gate_end` state in `cell-meta.tsv`; a
  foreign-process violation is recorded (`status=gate`) and disclosed, never
  silently pooled. Note: our own benchmark threads are runnable, so loadavg(1m)
  stays >0.5 for a few minutes after each cell; that residual is recorded
  verbatim as `own-residual(la1=…)` (not a violation) and disclosed by the
  analyzer — the operative per-cell interference gates are the runtime
  foreign-process/top-ps check and the session-start loadavg ≤0.5 gate.
- The driver never invokes Maven: the B4 harness is compiled `javac`-only
  (`JURING_SKIP_MVN=1 ... --help`) against `target/classes` that the Lead builds
  on the frozen tree before the session; cells then run with
  `JURING_SKIP_COMPILE=1`. The session manifest records the commit, the build
  state of `target/classes`, and harness sha256 sums.
- Guard rails: refuses to run into a non-empty `cells/` unless `--force`;
  per-cell JSON identity checks fail the cell if `juring-group` did not really
  run `runtime=shard shards=N` (or manual did not run `runtime=threads threads=N`).
- **G3 frozen-tree gate (mandatory, freeze v2):** the start preflight requires
  `git HEAD == 25c664b28956992a8f35244ee0100ad79883d5c7`,
  `find src/main/java -type f | sort | xargs cat | sha1sum ==
  93ce8a716c0f3b56e9ce76b8806db5eff99fc66f` and
  `find src/test/java -type f | sort | xargs cat | sha1sum ==
  74f9c344596a4a7c8353f915b9dcbdf0ebae2f37` (the Lead's freeze-v2 content
  hashes, after the F1 CompletionTable fix); a mismatch aborts the session. The
  manifest records the expected and live hashes; the B4 working tree is
  intentionally uncommitted (`dirty=true`), so the identity is those hashes, not
  only the commit id.

Artifacts (under the gitignored `bench/results/<session>/`):

```
cells/N<N>__<engine>__r<rep>.json     raw per-process JSON (+ .gc.log for JUring)
logs/<id>.log, logs/<id>.env          process output; pre/post uptime/loadavg/top
cell-meta.tsv                         one row per invocation (all reps, never trimmed)
manifest.txt                          kernel/JDK/CPU/NVMe identity, commit, build state,
                                      protocol, exact command templates, "only read cells"
aggregate/shard.{json,md}             analyzer output
```

## 3.1 B5 observability overhead A/B (`run-b5-overhead.sh`, §7.1)

```bash
# build the B4 baseline arm ONCE before the session: git archive 6cc1a75 into
# scratch/b5-bench/baseline + `mvn -o -q -DskipTests compile` + its own javac.
# This is the only Maven invocation; the workspace is never edited for it.
bench/shard/run-b5-overhead.sh --prep-baseline
# plan only (preflight + exact commands + estimated wall): no files, no I/O
bench/shard/run-b5-overhead.sh --dry-run
# the official session (~40 min; the machine must be otherwise idle)
FROZEN_HEAD=<sha> FROZEN_SRC_SHA1=<sha> FROZEN_TEST_SHA1=<sha>     bench/shard/run-b5-overhead.sh --session b5-overhead-2026-11-14
```

Arms: **b4** = commit `6cc1a75` (schema 1.0, no B5 metrics), **b5** = the frozen
workspace tree with metrics on (schema 1.1, `withLatencySampleEvery(1)`, 1 s
series). Cells: `shard1` (shard N=1), `shard8` (shard N=8), `threads1`
(threads N=1) — 4K randread QD 64/worker, CPUs 1..N via `taskset` (shard mode
`--first-cpu 1`), `/mnt/TestData/read_files/single_file_bench.bin`, 10 s warmup +
30 s measured, **R=5**, 30 s rest between invocations, arms interleaved and the
arm order alternating per rep, fresh process/JVM per invocation, identical JVM
flags for both arms, **every rep kept**.

Metric: paired per-rep ratio `throughput(B5)/throughput(B4)` at the same
cell/rep/position. **PASS = mean paired ratio >= 0.99 in every cell** and no
individual rep below 0.95 unless it is state-gated and disclosed. The analyzer
reports per-rep ratios, mean/stdev and the one-sided 95 % t CI (df = R−1), all
state flags, and writes `aggregate/overhead.{json,md}`. State gate (disclosed,
never trimmed): fast-state external p99 < 600 µs (4K randread QD64 protocol) +
the IOPS anomaly rule (rep < 0.92 × the max IOPS of its own cell/arm) + F14; the
"state-clean" mean over the non-gated subset is reported as a disclosure only
(the frozen bar uses all reps). Session dir:
`bench/results/b5-overhead-<date>/` (gitignored) with `cells/`, `logs/`,
`cell-meta.tsv`, `manifest.txt`, `aggregate/overhead.{json,md}`. If the bar
fails, §7.1 allows one re-run with a cheaper sampling configuration
(`latencySampleEvery 8` and/or series interval 2 s); both configurations must be
reported, and the final configuration stated.

**Executed session (G4): `bench/results/b5-overhead-2026-11-14/` (gitignored).**
PASS with no fallback re-run: mean paired ratios B5/B4 = 1.0190 (sd 0.0300,
one-sided 95 % CI lower 0.9910) for `shard1`, 1.0110 (0.0400, 0.9730) for
`shard8` and 1.0070 (0.0370, 0.9720) for `threads1`; minimum individual rep
0.9543; no gate-flagged invocations (30/30 `status=ok`). The analyzer reports two
state-gate labellings side by side in `aggregate/overhead.md` §1.2: the literal
flat 600 µs p99 gate and the Lead-approved cell-adjusted `600 µs × total
workers` gate (a cell with W workers has W × 64 requests in flight, so its normal
p99 scales with W); both are PASS and the primary mean bar is identical under
both (no rep is trimmed). The per-invocation `cell-meta.tsv` `p99_state` column
holds the literal flat gate as recorded at run time. Citable tables:
`aggregate/overhead.md` §§0-1 (verdict, per-rep), §1.2 (labelling variants),
§4 (per-ring queue-depth/p99 M3 evidence); raw data `cells/`, `logs/`,
`cell-meta.tsv`, `manifest.txt`; first-pass analysis archived as
`aggregate/overhead-first-pass.{md,json}`.

## 3.2 M3 official campaign (`run-m3-campaign.sh` / `analyze_m3.py`, 2026-11-15)

```bash
# pilot (2 s harness check; NOT campaign data)
bench/shard/run-m3-campaign.sh --session m3-pilot-2026-11-15 --force \
  --engines juring-group,juring-manual,fio,seastar --n-list 1,2,4,8 \
  --reps 1 --seconds 2 --warmup 2 --rest 30
# official campaign: chunks of <=3 rep-blocks; repeat the second line until 60/60
ALLOW_RESIDUAL_LOAD=1 bench/shard/run-m3-campaign.sh --session m3-2026-11-15 --max-blocks 3
ALLOW_RESIDUAL_LOAD=1 bench/shard/run-m3-campaign.sh --session m3-2026-11-15 --resume --max-blocks 3
python3 bench/shard/analyze_m3.py --session m3-2026-11-15
```

Frozen protocol/bars: `docs/m3-design.md` (incl. pre-campaign Amendment A1 that restored
the brief-literal bars (b)/(d)). Result: `docs/m3-report.md` — all five bars PASS;
`juring-group` N=8 = 473,126 IOPS = 103.2 % of `fio numjobs=8` and 101.5 % of seastar
io_uring; per-shard min/mean 0.963 (95 % LB 0.947); 6/60 slow-state reps disclosed and
kept. `ALLOW_RESIDUAL_LOAD=1` relaxes ONLY our own chunk-start loadavg residual;
foreign-process >20 % CPU stays a hard gate. The seastar runner is
`bench/seastar/seastar_file_bench.cc --smp N` (built by `build-file-bench.sh`; schema-1.1
JSON parity verified).

## 4. Analyzer

```bash
python3 bench/shard/analyze_shard.py --session b4-2026-11-14
# fixtures/self-test: python3 bench/shard/analyze_shard.py --results-dir <dir>
```

Formulas (methodology §6 / plan §5 M3):

- `IOPS(engine, N)` = mean over reps of the process-level total IOPS
  (JUring: `runs[0].iops`; fio: sum of `jobs[].read.iops`).
- `ratio_vs_fio = IOPS(engine,N) / IOPS(fio,N)`.
- `SE(N) = IOPS(N) / (N · IOPS(1))` per engine (also `SE(4)/SE(8)`).
- per-shard spread = `min(per-shard IOPS) / mean(per-shard IOPS)`; per-shard
  IOPS come from `runs[0].shards[]` (group) or `runs[0].threads[]` (manual).
- `p99(N)/p99(1)` per engine (mean p99 ratio; same JUring latency definition).
- `group/manual` ratio per N.

Preliminary acceptance bars (design §13 / plan M3, applied to this data):
`group-8 ≥ 85 % of fio-8`; `SE_group(8) ≥ 0.75`; per-shard `min/mean ≥ 0.85`
(group-8; manual-8 reported as reference); `p99_group(8) ≤ 1.15 × p99_group(1)`.
The verdict is `PASS (preliminary bars only)` / `FAIL` / `INCONCLUSIVE`, and it
never claims M3 sign-off.

**B5 per-ring queue-depth / p99 columns (schema 1.1).** When the raw JSON carries
the B5 fields, `aggregate/shard.md` gains section 4: per-cell means and per-ring
rows with mean/max in-flight, mean `sq_queued` / `cq_ready`, the **runtime
completion p99** (`dispatch_latency_us.p99`, shard runtime only; registration→
dispatch, includes kernel/device completion — NOT fio clat and NOT the external
`latency_us` definition) next to the **external** p99 (`latency_us.p99`), and
`failed`/`cancelled`/`timed_out` ring counters; `aggregate/shard.json` carries the same under `b5` and `b5_per_ring`.
Queue depth comes from `ring_series` (measured phase only). Sessions that predate
schema 1.1 (all `b4-*` sessions) still analyze unchanged: every B5 column renders
`n/a` and `b5.available=false`.

The **overhead** analyzer (`analyze_b5_overhead.py`, §3.1) additionally reports
the two state-gate labellings side by side and the per-ring queue-depth/p99
evidence; its analysis is analysis-only (raw cells untouched) and the primary
mean bar is identical under both labellings.

Every rep is listed in the raw table; nothing is averaged away silently. At R=3
no confidence interval is claimed — means are shown with stdev/min/max/CV and
the report says so. Data-quality section flags *unstable/bimodal* cells using the
M1 anomaly rule (any rep < 0.92 × cell max IOPS) and CV, and lists failed or
gate-flagged invocations.

**fio `group_reporting=1` caveat (verified with fio 3.40).** The frozen matched
template uses `--group_reporting=1`; with `numjobs>1` fio 3.40 then emits **one
aggregated `jobs[]` entry**, so fio per-job/per-shard spread is not available.
The analyzer uses the aggregated `read.iops` for the total gate and reports fio
per-shard spread as `n/a`; the M3 per-shard min/mean bar is evaluated on JUring
shards/threads. (`FIO_GROUP_REPORTING=0` would expose per-job entries — the
driver supports it — but the frozen template value 1 is what the session uses;
output reporting only, I/O behaviour is identical.)

## 5. Limitations (honest disclosure)

1. **Preliminary**: R=3, one session. Supports "the B4 runtime scales"; does not
   satisfy M3 (needs R=5, state gating, B5 metrics, seastar `--smp N`).
2. **Single shared file** for all shards/engines (DS-S); the W18-W20 DS-M
   one-file-per-shard layout is not used. Page cache/extent mapping is shared.
3. Host is **bimodal at QD64**; R=3 cannot gate device state (no 1 s series, no
   300 s rests). Bimodal cells are flagged, not trimmed.
4. `juring-group` per-shard latency includes the shard runtime's batched dispatch
   overhead; `juring-manual` measures the same before-submit → callback window
   without that dispatch. That is the intended runtime cost being measured.
5. `juring-group` workers are individually CPU-pinned by the shard runtime;
   `juring-manual` threads and fio jobs only inherit the taskset mask (F0
   disclosure; fio has no equivalent per-job pinning flag in the frozen template).
6. The shard mode does **not** exercise the seastar-style multi-shard file
   layout, work stealing or QoS (B4 explicitly has none), and a `JuringFile` per
   shard is not used: the benchmark opens one fd per shard on the same file with
   `readFixed`/registered buffers.
7. No CPU/op, syscalls/op, IOPriority, write cells or seastar comparison here.
8. Sample count: the latency reservoir is per worker (1M), merged for the total
   latency; per-shard p99 in the JSON is each shard's own reservoir.
9. If the session must be trimmed, only `--n-list 1,8` (N={1,8}, R=3) is the
   documented fallback — and the notes must say so.
10. **Per-shard fairness vs the host NVMe queue topology.** The data device's
    blk-mq contexts each cover 4 CPUs (`/sys/block/nvme1n1/mq/*/cpu_list`, e.g.
    `mq/0: 0,1,16,17 mq/1: 2,3,18,19 ...`). Pinning shards to CPUs `1..N` (the
    design default) therefore gives CPU1/CPU8 a dedicated hardware queue while
    CPUs {2,3},{4,5},{6,7} share queues; measured per-shard IOPS ratios become
    ~2:1 at N=8 (min/mean 0.802) although total IOPS is at fio parity. Unpinned
    group mode (`--first-cpu -1`) is balanced (0.965-0.983) and matches the
    manual reference. This is a host topology artifact, not a runtime
    distribution defect — see `scratch/b4-bench/notes.md` §7. The full M3
    campaign should pin one shard per hctx (first CPUs of `mq/0..7`) or run
    unpinned, and record `mq/*/cpu_list` in the manifest.
11. **SE(N) and p99(N)/p99(1) are not meaningful for this single-device,
    constant-QD-per-shard protocol.** One shard at QD64 already reaches ~68 % of
    the measured device ceiling (~476 k IOPS), so SE(8) is capped at ~0.18 for
    every engine — the fio reference itself gets 0.181 and manual 0.184, and
    fio's p99(8)/p99(1) is 5.90 (manual 5.46). These bars measure the saturated
    SSD queue, not the runtime; restate them (SE relative to fio at the same N,
    or fixed total QD) before the full campaign. The analyzer prints this
    framing next to its raw bar table (`bar_interpretation`).

12. **B5 metric definitions matter.** `ring_metrics` counters include
    runtime-internal ring ops (the shard wakeup READ and the shutdown cancel);
    snapshots are best-effort and may lag one loop pass (so
    `reaped == completed + failed` holds per snapshot but combined views are not
    atomic). The library `dispatch_latency_us` percentiles are bucket upper
    bounds (<= 6.25 % over-estimate for >= 16 ns) and measure the shard runtime
    completion latency (registration→dispatch, includes kernel/device completion,
    excludes SQE prep and the handler body) — it is not a pure dispatch-overhead
    metric, not end-to-end application latency, and never compared to fio `clat`
    or the external `latency_us` reservoir.

## 6. Verification log for the harness changes

- `javac`-only type check against the frozen B4 API stubs
  (`scratch/b4-bench/stubs/`) before the core existed: clean.
- `javac` against the real `target/classes` after the B4 core landed: clean.
- A/B field-compatibility check: an old `HEAD` build of `JUringFileBench` and the
  new build are run with the same threads-mode arguments; the JSONs are compared
  with volatile fields (timestamps, loadavg, duration-dependent numbers) masked;
  identical field sets/units are required. Result: see
  `scratch/b4-bench/STATUS.md`.
- Shard smoke: `--runtime=shard --shards 2 --seconds 2` on the real dataset, only
  after the Lead's G1, with all shards' `affinity_applied=true`, non-zero
  `completions_dispatched`, `completions_unattributed=0` and no Little's-law
  mismatch warning.
- Analyzer self-test on synthetic clean and deliberately-broken fixtures
  (`scratch/b4-bench/fixtures/`): verdict/bars/raw-rep/quality sections render,
  missing JSON / anomalous rep / gate flag / failed invocation are reported.
- **B5 (schema 1.1) verification, this session:**
  - `JUringFileBench.java` javac-only type check against frozen B5 API stubs
    (`scratch/b5-bench/stubs/`; RingMetrics/LatencySnapshot/ShardMetrics/
    ShardOptions.withLatencySampleEvery per `docs/b5-design.md` §2): clean.
  - `javac` against the real `target/classes` after the Lead's G3 build (the
    session-start harness build): see `scratch/b5-bench/STATUS.md`.
  - Runtime smoke (threads + shard, 2 s) on the frozen tree: schema 1.1, one JSON
    object, per-worker `ring_metrics`/`ring_series`, shard `dispatch_latency_us`,
    run-level `metrics`, aggregate ring fields; `--metrics off` emits no B5 field.
  - `analyze_shard.py` backward-compat test on the old `b4-2026-11-14` session
    (all B5 columns `n/a`, verdict unchanged) and on a synthetic schema-1.1
    fixture (per-ring queue-depth/p99 tables render); see
    `scratch/b5-bench/analyze-*.md`.
  - `analyze_b5_overhead.py` self-test on synthetic PASS/FAIL/state-gated
    fixtures (`scratch/b5-bench/overhead-fixtures/`): CI, per-rep table,
    state-clean disclosure and PASS/FAIL/INCONCLUSIVE logic verified.
  - Baseline arm built from `git archive 6cc1a75` in `scratch/b5-bench/baseline`
    (`src/main` content sha1 `93ce8a71…`, matches the B4 freeze-v2 identity);
    smoke: shard+threads 2 s, schema 1.0, no B5 fields.
- **B5 official overhead A/B session (G4), `bench/results/b5-overhead-2026-11-14/`
  (gitignored).** Frozen tree HEAD `6cc1a75…`, src `04f12dbc…`, test `666e0644…`;
  baseline arm `git archive 6cc1a75` scratch build in `scratch/b5-bench/baseline`
  (schema 1.0, no B5 metrics), B5 arm frozen tree metrics on (schema 1.1).
  Protocol: cells `shard1`/`shard8`/`threads1`, 4K randread QD 64/worker, CPUs
  1..N, 10 s warmup + 30 s measured, R=5, 30 s rests, interleaved arms with the
  order alternating per rep, fresh JVM per invocation, all reps kept; preflight
  all PASS (loadavg 0.36 at start, no Maven/foreign harness), 30/30 invocations
  `status=ok`, no gate-flagged cells. Result: mean paired ratio B5/B4 = 1.0190
  (sd 0.0300, one-sided 95 % CI lower 0.9910) shard1, 1.0110 (0.0400, 0.9730)
  shard8, 1.0070 (0.0370, 0.9720) threads1; min rep 0.9543; **PASS under both the
  cell-adjusted (600 µs × workers) and literal (600 µs) state-gate labellings**;
  <1 % overhead bar met, no fallback re-run. Citable tables:
  `aggregate/overhead.md` (§0 verdict, §1 per-rep, §1.2 labelling variants, §4
  per-ring queue-depth/p99 M3 evidence), raw `cells/` + `cell-meta.tsv` +
  `manifest.txt`; first-pass analysis archived as
  `aggregate/overhead-first-pass.{md,json}`. Commands:
  `run-b5-overhead.sh --prep-baseline` (once, before the session), then
  `FROZEN_HEAD=… FROZEN_SRC_SHA1=… FROZEN_TEST_SHA1=… bench/shard/run-b5-overhead.sh
  --session b5-overhead-2026-11-14`; analyzer:
  `python3 bench/shard/analyze_b5_overhead.py --session b5-overhead-2026-11-14`.
