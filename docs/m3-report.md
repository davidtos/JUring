# M3 report — W17–W20 multi-shard scaling campaign (JUring vs `fio` vs seastar)

**Verdict: M3 PASS — signed off.** All five frozen bars (a)–(e) of
[`docs/m3-design.md`](m3-design.md) PASS on the official R=5 campaign, with every rep kept
and no trimming. The B5 observability `<1 %` overhead bar was already PASS and is not re-run.
Branch `kafka-engine`; code under test is the B5 frozen tree (`0952deb`) plus the benchmark
runner additions committed before the campaign. This is the L2 scaling milestone
(`SEASTAR-PLAN.md` §5 M3): `juring-group` at N=8 reaches **473,126 IOPS = 103.2 % of
`fio numjobs=8`** (459,125 IOPS) and **101.5 % of seastar io_uring `--smp 8`**
(466,460 IOPS), with per-shard min/mean **0.963** (95 % LB 0.947).

**Session:** `bench/results/m3-2026-11-15/` (gitignored; 60/60 invocations `status=ok`).
**Report stub for the raw tables:** that session's `aggregate/m3.md` (all 60 rep rows and the
full 75-row per-ring table), `aggregate/m3.json`, `cell-meta.tsv`, `manifest.txt`.
**Campaign wall:** 2026-11-14T22:57:00Z → 2026-11-15T04:33:17Z = **5 h 36 m**, 60 invocations,
300 s rest between invocations throughout (59 gaps; verified in `cell-meta.tsv` `end_utc`/`start_utc`).
Pilot + diagnostics: `bench/results/m3-pilot-2026-11-15/`, `scratch/m3-lead/` (gitignored).

## 0. TL;DR

| bar | frozen rule | result |
|---|---|---|
| (a) throughput vs `fio` at same N | one-sided 95 % LB of `IOPS(juring-group,N)/IOPS(fio,N)` ≥ 0.85, every N∈{1,2,4,8} | **PASS** (LB 0.959 / 0.859 / 0.961 / 0.987) |
| (b) SE(N) + p99(N) vs `fio` at same N, no `N·IOPS(1)` cross-engine denominator | reporting comparison with one-sided 95 % CIs, complete per rep at every N (design §1, Amendment A1) | **PASS** (all complete; tables §3.2–3.3) |
| (c) per-shard balance, juring-group N=8 | one-sided 95 % LB of per-rep `min/mean` ≥ 0.85 | **PASS** (mean 0.963, 95 % LB 0.947) |
| (d) seastar io_uring total IOPS at N=8 | brief-literal: one-sided 95 % LB of `seastar/JUring` ≥ 0.90 | **PASS** (mean 0.986, LB 0.959) |
| (e) per-ring queue depth + runtime-completion p99 | present + consistent for every juring-group cell, from B5 JSON 1.1 | **PASS** (5/5 reps × N; 75 ring rows; 0 failed/cancelled/timedout) |

B5 metrics-overhead bar: **PASS**, carried over from
`bench/results/b5-overhead-2026-11-14/` (mean paired B5/B4 ratios 1.019/1.011/1.007).

## 1. Frozen identity, protocol and placement

**Frozen before any campaign invocation.** Design `docs/m3-design.md`; **Amendment A1**
(pre-campaign) sha1 `d391cad3d9a4df50d3c8e0d365e8ba177393b9eb`, commit `1f1e548`; v1 sha1
`334bb55a…`, commit `b8b7832`. A1 restores the brief-literal wording of bars (b) and (d)
(the v1 file over-specified numeric thresholds for (b) and read (d) in the plan direction);
A1 was written at 23:55 local **before the campaign session directory existed**, with only
2 s pilot/smoke data (never part of the campaign). No bar changed after campaign data existed.

**Code identity:** `src/main/java` content sha1 `04f12dbc85d723a10dd952b7ac3c51bb6253ed46`,
`src/test/java` content sha1 `666e0644dc063c0b15c5bb52571002a79b5ec657`; benchmark-tree HEAD
`1f1e5488c7d5d8fb1f69bf608332e5bcdfd2a087`. Harness sha256: driver
`7448907c…`, analyzer `ca12e798…`, seastar runner source `76039187…`, seastar binary
`ce9006d3…`; all recorded in `manifest.txt`.

**Protocol (design §2, executed exactly):** 4K randread, QD 64 per worker/shard, 10 s warmup +
30 s measured, **R=5**, **300 s rest between invocations**, fresh process/JVM per invocation,
engines interleaved inside each rep block with order alternating per rep (odd `(rep+N)`:
`juring-group, fio, seastar`; even: reversed), seed 20261114, every rep kept. Cells:
`juring-group` + `fio` + `seastar --smp N` at N=1,2,4,8 = 12 cells / 60 invocations.
The optional `juring-manual` N=8 reference **was** run after the campaign (we finished 2 h 27 m
ahead of the schedule gate; 5 invocations, same 4K/QD64/10 s+30 s/R=5/300 s-rest protocol,
own session `bench/results/m3-manual-2026-11-15/`) — disclosure only, **never a bar** (design
§2), reported in §3.7.

**Dataset (DS-S):** aged shared 10 GiB `/mnt/TestData/read_files/single_file_bench.bin`
(10,737,418,240 B, read-only, O_DIRECT, whole file). seastar and `fio` read the same single
shared file (one fd per reactor; `fio` one file, N jobs); the W18–W20 DS-M per-shard-file
layout is **not** used, so extent/page-cache sharing is common to all engines and no
per-shard-file advantage is claimed (design §3).

**Placement (design §3):** N=1..4 CPUs `1..N` for all engines (`juring-group --first-cpu 1`;
`fio`/seastar pinned to the same set). N=8: CPU set `1,2,4,6,8,10,12,14` = one CPU per data
NVMe hw queue (`nvme1n1` has 8 queues: `mq/0=0,1,16,17 … mq/7=14,15,30,31`; recorded in
`manifest.txt`). `fio` and seastar are confined/pinned to that exact set; **`juring-group`
runs unpinned** (`--first-cpu -1`) inside a `taskset` mask of the same set — the B4 naive
`1..8` pinning is deliberately not reused (it produced the 0.802 spread artifact; B4 §6).
Disclosed asymmetry: seastar pins one reactor per CPU, `fio` jobs float over the set,
`juring-group` shards float over the set (for N=8 no `sched_setaffinity` is applied and every
`shard_stats` reports `affinity_applied=false`). This is the frozen choice; a non-campaign 30 s diagnostic
(`scratch/m3-lead/diag/`) got 440.5k IOPS / spread 0.958 for this placement and 477.0k / 0.985
for fully unpinned (all 32 CPUs) — the frozen placement was kept, not tuned, and it passes.

## 2. Device/machine state

- Data NVMe `nvme1n1` (GIGABYTE GP-ASM2NE6100TTTD), ext4, `/mnt/TestData` read-only,
  `/home/david/projects/JUring/nvme-data` the writable mount of the same partition.
- Governor `performance` on all CPUs; `kernel.io_uring_disabled=0`; 32 logical CPUs
  (16C/32T); CPUs 0 and 16–31 idle during cells.
- Start (23:57): loadavg 0.20, NVMe hwmon 29.9/37.9/45.9 °C, no dmesg NVMe/error lines;
  dataset mtime 2026-11-03 (aged), unchanged. End (05:33): loadavg 0.12, 29.9/42.9/43.9 °C,
  no dmesg errors, dataset mtime unchanged. `scratch/m3-lead/device-state-{start,end}.txt`.
- The machine ran only campaign invocations during cells (no Maven/heavy jobs); a foreign
  process >20 % CPU was never observed. Start-of-chunk `ALLOW_RESIDUAL_LOAD=1` only relaxes
  our own loadavg residual from the previous chunk; the foreign-process gate stayed strict
  (disclosed).

## 3. Results (official R=5 campaign, all reps kept)

### 3.1 Cell summary (mean over 5 reps; one-sided 95 % t CI, df=4)

| engine | N | mean IOPS | sd | one-sided 95 % CI | min | max | CV | mean p99 µs |
|---|---|---|---|---|---|---|---|---|
| juring-group | 1 | 320,760 | 7,908 | [313,221, 328,300] | 306,618 | 324,497 | 2.5 % | 493.0 |
| juring-group | 2 | 391,855 | 42,755 | [351,094, 432,617] | 320,691 | 424,661 | 10.9 % | 1,308.4 |
| juring-group | 4 | 465,936 | 10,643 | [455,790, 476,083] | 453,055 | 474,254 | 2.3 % | 1,820.1 |
| juring-group | 8 | 473,126 | 8,945 | [464,598, 481,654] | 457,125 | 477,224 | 1.9 % | 2,771.5 |
| fio | 1 | 323,284 | 6,326 | [317,252, 329,315] | 311,986 | 326,600 | 2.0 % | 492.3 |
| fio | 2 | 403,669 | 15,615 | [388,783, 418,556] | 384,613 | 420,158 | 3.9 % | 1,041.2 |
| fio | 4 | 463,263 | 17,678 | [446,410, 480,117] | 434,967 | 475,859 | 3.8 % | 2,102.1 |
| fio | 8 | 459,125 | 18,573 | [441,418, 476,832] | 439,846 | 477,640 | 4.0 % | 3,476.7 |
| seastar | 1 | 317,330 | 13,561 | [304,401, 330,259] | 296,215 | 326,537 | 4.3 % | 510.1 |
| seastar | 2 | 427,332 | 1,279 | [426,113, 428,551] | 425,864 | 428,640 | 0.3 % | 930.9 |
| seastar | 4 | 462,364 | 16,150 | [446,968, 477,761] | 433,979 | 471,578 | 3.5 % | 2,269.2 |
| seastar | 8 | 466,460 | 8,613 | [458,249, 474,671] | 451,196 | 472,251 | 1.8 % | 3,526.5 |

The device ceiling in this protocol is ~470–480 k IOPS; N=4 and N=8 are ceiling-limited for
all three engines (the N=2 spread and the low `juring-group` N=2 mean are one slow-state rep,
§5). All 60 invocations report `errors=0`.

### 3.2 Bar (a) — N-shard JUring throughput vs `fio` at the same N

Per-rep ratio `IOPS(juring-group,N) / IOPS(fio,N)` (paired by rep block; one-sided 95 % t CI):

| N | ratio mean | sd | 95 % LB | per-rep ratios (r1..r5) | result |
|---|---|---|---|---|---|
| 1 | 0.993 | 0.035 | **0.959** | 0.996, 0.996, 0.940, 1.039, 0.992 | PASS |
| 2 | 0.973 | 0.120 | **0.859** | 1.011, 1.010, 1.072, 1.008, 0.764 | PASS |
| 4 | 1.007 | 0.048 | **0.961** | 0.997, 0.957, 1.088, 0.997, 0.995 | PASS |
| 8 | 1.032 | 0.047 | **0.987** | 0.995, 0.999, 1.082, 1.085, 0.999 | PASS |

N=8 is the runtime-relevant point: **473.1k vs 459.1k = 103.2 %** of `fio numjobs=8` (B4
preliminary: 98.5 %). N=1/4 are at parity; N=2's LB is the narrowest margin in the whole
campaign (0.859 vs 0.850) and is driven entirely by the one slow-state `juring-group` rep
(`N2 r5`, 320,691 IOPS = 0.755× its cell max). Per the frozen rules that rep stays in every
mean/CI; as a **disclosure only** (not a bar, no trimming), excluding that single state-gated
rep gives N=2 mean ratio 1.025 (r1–r4) and `juring-group` N=2 mean 409.6k. The bar passes with
all reps, so no re-run was made (the design forbids trimming; gate-retries were not needed —
all 60 invocations completed as `ok`).

### 3.3 Bar (b) — SE(N) and p99(N) vs `fio` at the same N (no `N·IOPS(1)` cross-engine denominator)

`SE(N) = IOPS(N)/(N·IOPS(1))` computed per engine from this campaign's cells; ratios paired by
rep index; one-sided 95 % t CIs (df=4). This is the brief's reporting/comparison bar
(Amendment A1): **complete at every N, PASS**.

| N | SE juring | SE fio | SE_j/SE_f mean | 95 % LB | p99_j/p99_f mean | 95 % UB | role |
|---|---|---|---|---|---|---|---|
| 1 | 1.000 | 1.000 | 1.000 | 1.000 | 1.004 | 1.088 | bracket |
| 2 | 0.612 | 0.624 | 0.982 | 0.854 | 1.289 | 2.160 | bracket |
| 4 | 0.363 | 0.358 | 1.017 | 0.939 | 0.886 | 1.029 | comparison |
| 8 | 0.184 | 0.178 | 1.041 | 0.979 | 0.832 | 1.023 | comparison |

Reading: JUring's scaling efficiency tracks `fio` at the same N (+1.7 % at N=4, +4.1 % at N=8;
the absolute SE collapse is the single-device capacity limit, exactly why the old
`SE(8)≥0.75`/`p99(8)≤1.15·p99(1)` bars were invalid — `fio` itself scores SE(8)=0.178).
p99 here is a same-N ratio of **engine-native definitions** (JUring: before prepare/submit →
completion handler; `fio`: `clat` submission→completion) and is disclosure, not an absolute
tail ranking. The N=2 p99 ratio (1.29, UB 2.16) is the same one slow rep; at N=4/N=8 JUring's
external p99 is 0.89×/0.83× `fio`'s (2.77 ms vs 3.48 ms at N=8) — under different definitions,
so no ranking is claimed. The seastar bracket's p99 (3.53 ms at N=8, pre-submit→completion,
no registered files/buffers) is higher than JUring's; that is an engine asymmetry, not a
bar (d) input.

### 3.4 Bar (c) — per-shard balance, `juring-group` N=8

Per-rep `min(shard IOPS)/mean(shard IOPS)`, frozen placement (unpinned within the hctx set):
r1 0.953, r2 0.986, r3 0.960, r4 0.972, r5 0.943 → **mean 0.963, sd 0.016, one-sided 95 % LB
0.947 ⇒ PASS** (bar ≥0.85 with 95 % LB). This closes the B4 artifact: the naive `1..8` pinning
produced min/mean 0.802 (all 3 reps); the frozen topology-aware placement gives ≥0.943 in
every rep. `fio` per-job spread is `n/a` under the frozen `--group_reporting=1`; seastar
per-shard spread at N=8 is 0.997–1.000 (disclosure; it pins one reactor per CPU).

### 3.5 Bar (d) — seastar io_uring total IOPS at N=8

`IOPS(juring-group,8)` mean **473,126**; `IOPS(seastar --smp 8)` mean **466,460**.

- Brief-literal bar: `seastar/JUring` mean **0.986**, one-sided 95 % LB **0.959 ≥ 0.90 ⇒ PASS**.
- Plan-direction disclosure: `JUring/seastar` mean **1.015**, 95 % LB 0.986.
- Other N (bracket only, not a bar): seastar/JUring 0.989 (N=1), 1.091 (N=2), 0.993 (N=4).

### 3.6 Bar (e) — per-ring queue depth + runtime-completion p99 (B5 JSON 1.1)

All 20 `juring-group` invocations carry `ring_metrics`, 1 Hz measured-phase `ring_series` and
`dispatch_latency_us`; coverage **5/5 reps at every N** (75 ring-rows in `aggregate/m3.md` §5).
`dispatch_latency_us.p99` is the runtime completion latency (registration→dispatch; includes
kernel/device completion; **not** `fio clat` and **not** the external `latency_us`).

| N | rings | mean in-flight (range, avg) | max in-flight | mean sq_queued | mean cq_ready | runtime p99 µs (range, mean) | external p99 µs (range, mean) | failed/cancelled/timedout |
|---|---|---|---|---|---|---|---|---|
| 1 | 5 | 62.1–63.3, 62.8 | 65 | 0.07 | 2.08 | 492–557, 518 | 478–549, 493 | 0/0/0 |
| 2 | 10 | 62.7–64.0, 63.3 | 65 | 0.65 | 0.80 | 918–2621, 1278 | 873–2830, 1309 | 0/0/0 |
| 4 | 20 | 63.8–64.9, 64.3 | 65 | 0.59 | 0.24 | 1901–2228, 2019 | 1723–1945, 1821 | 0/0/0 |
| 8 | 40 | 60.8–64.9, 64.2 | 65 | 0.65 | 0.26 | 2753–3146, 2962 | 2603–3219, 2771 | 0/0/0 |

Queue depth is consistent with QD 64 per ring (mean 62–65 in flight, max 65); zero failed,
cancelled or timed-out completions in the whole campaign. The N=2 range includes the slow rep;
the N=8 runtime p99 spread (2753–3146 µs) is the expected per-ring distribution at the device
ceiling (cf. B5's shard8 demonstration), not a defect.

### 3.7 Optional reference: `juring-manual` N=8 (post-campaign disclosure; never a bar)

The legacy `--runtime=threads` harness (one plain Java thread + one `JUring` per worker, the
B4 `juring-manual` reference) was run **after** the official campaign and the frozen analysis,
with the same frozen protocol (4K randread, QD 64/worker, 10 s warmup + 30 s measured, R=5,
300 s rests) and the same N=8 CPU set/mask (5/5 invocations `status=ok`, errors 0):
mean **460,790 IOPS** (sd 13,712; min 440,472 / max 477,139; CV 3.0 %), per-thread min/mean
mean 0.958, external p99 mean 3,317 µs. `juring-group` at N=8 is **1.027×** the manual
thread pattern (473,126 vs 460,790), i.e. the B4 shard runtime adds no throughput regression
at this power; this is disclosure only and did not influence any bar or verdict.


## 4. State gate (disclosed; never trims)

Rules (design §4): IOPS anomaly = rep < 0.92 × max IOPS of its own cell/engine; slow-state p99
= external p99 ≥ 600 µs × total workers; plus the F14 load/foreign-process gate. **6 of 60
reps** are labelled (10 %), all `status=ok` and kept in every mean/CI above:

| cell | engine | N | rep | IOPS | external p99 µs | flags |
|---|---|---|---|---|---|---|
| N1 | seastar | 1 | r4 | 296,215 | 591.3 | iops-anomaly |
| N2 | fio | 2 | r4 | 384,613 | 1,220.6 | iops-anomaly, slow-state-p99 |
| N2 | juring-group | 2 | r4 | 387,616 | 1,046.8 | iops-anomaly |
| N2 | juring-group | 2 | r5 | 320,691 | 2,813.3 | iops-anomaly, slow-state-p99 |
| N4 | fio | 4 | r3 | 434,967 | 2,703.4 | iops-anomaly, slow-state-p99 |
| N4 | seastar | 4 | r3 | 433,979 | 2,950.4 | slow-state-p99 |

This is the host's known QD64/saturation bimodality (B4 §6, B5 overhead A/B); it is disclosed,
not trimmed. Bars (a)/(b)/(c) therefore include it; the narrowest consequence is bar (a) at N=2
(95 % LB 0.859, still ≥ 0.85). No invocation failed, so no gate-retry invocation was used.

## 5. Analysis-code provenance and the first-pass disclosure

The campaign analyzer is `bench/shard/analyze_m3.py` (sha256 `ca12e798…`, committed `8bbde41`
after a fix). **First-pass disclosure:** the analyzer committed for the campaign
(`1f1e548`) had a session-loader bug — it read `cells/N<n>.json` instead of
`cells/N<n>__<engine>__r<rep>.json` — so the first automatic analysis at campaign end could not
load any raw JSON and returned **INCONCLUSIVE on bars (c) and (e)** (bars (a)/(b)/(d) fell back
to `cell-meta.tsv` and were already PASS). The fix changes only the file path; **no bar rule,
no data and no CLI changed**. The pre-fix output is archived under
`bench/results/m3-2026-11-15/aggregate/first-pass-loader-bug/`; the fixed run is deterministic
from the untouched raw JSON (`cells/`, `cell-meta.tsv` are write-once). This is disclosed
because the verdict changed from INCONCLUSIVE to PASS solely by reading the data the frozen
method always required (per-shard/ring fields), not by changing a criterion.

Similarly, **Amendment A1** is disclosed in full in the design file: it replaced v1's invented
numeric thresholds for bar (b) and v1's plan-direction (d) with the brief's literal wording,
before any campaign invocation existed.

## 6. Reproduce

```bash
cd /home/david/projects/JUring
# (already built) target/classes from the B5 frozen tree; seastar runner built by
#   bash bench/seastar/build-file-bench.sh
ALLOW_RESIDUAL_LOAD=1 bench/shard/run-m3-campaign.sh \
  --session m3-2026-11-15 --resume --max-blocks 3     # repeat until 60/60 (early chunks)
python3 bench/shard/analyze_m3.py --session m3-2026-11-15   # -> aggregate/m3.{md,json}
# pilot / diagnostics used the same driver with --seconds 2 --warmup 2 --rest 30 --reps 1
```

## 7. Residual risks and limitations

1. **Single device, single session, one date.** ~470–480 k IOPS device ceiling; N=4/N=8 are
   ceiling-limited, so "JUring scales" really means "JUring reaches the ceiling as fast as the
   neutral referees and distributes it evenly"; cross-engine differences near the ceiling are
   small and should not be over-read.
2. **DS-S only** (one shared aged 10 GiB file); no DS-M per-shard-file layout. seastar
   (`--smp N`) and `fio` read the same file as JUring; no per-shard-file effect is measured.
3. **Latency definitions differ** (JUring before-prepare→completion; `fio` `clat`;
   seastar pre-submit→completion; B5 runtime completion is a separate per-ring metric). Bar (b)
   is a same-N ratio disclosure only; no absolute tail ranking is claimed. seastar's p99 is
   higher at N=8 under its own definition/asymmetries (200-entry ring, no registered
   files/fixed buffers).
4. **Placement asymmetry (N=8):** `juring-group` is unpinned within the hctx CPU set
   (`affinity_applied=false`), while seastar pins reactors to those CPUs and `fio` floats over
   them. The frozen design chose this (the B4 naive `1..8` pinning was worse: 0.802 spread);
   `mq/*/cpu_list` is in the manifest. A fully unpinned diagnostic was faster (477.0 k,
   spread 0.985) but was not used — placement was not tuned after freeze.
5. **Host bimodality:** 6/60 state-gated reps (10 %) disclosed; frozen no-trim rule means they
   stay in all CIs. Bar (a) at N=2 is the narrowest margin (LB 0.859). A re-run of the campaign
   on a differently-loaded day could move this cell; the honest claim is "≥85 % with 95 % LB
   on this session", not "always ≥85 %".
6. **`fio --group_reporting=1`** (frozen template) gives one aggregated fio job, so fio
   per-job spread is `n/a`; bar (c) is evaluated on `juring-group` shards only.
7. **Scope:** read path only (no writes), no CPU/op or syscalls/op; the optional
   `juring-manual` reference was run only as post-campaign disclosure (§3.7, never a bar);
   no host-root/DS-M/hugetlbfs runs, no VT re-benchmark. B5 per-ring
   metrics include runtime-internal wakeup/cancel ops and snapshots are best-effort (B5 §8);
   per-ring qd/p99 here are evidence of the runtime path, not SLO numbers.
8. **Pilot note:** 2 s pilot cells under-report `juring-group` N=8 (240 k, JIT/warmup
   dominates a 2 s measured window). A non-campaign 30 s diagnostic reproduced the campaign
   behaviour (440.5 k, spread 0.958) before the campaign started; pilot data never enters the
   analysis. This is why the pilot was only a harness check and the campaign used 10 s warmup +
   30 s measured.

## 8. Verdict and sign-off

Every frozen bar PASSES: (a) PASS at all N, (b) PASS (complete reporting comparison),
(c) PASS, (d) PASS, (e) PASS; the B5 overhead bar is already PASS. **M3 is signed off: PASS**
(L2 scaling + metrics). The claims that are supported: at N=8, JUring's multi-ring runtime
reaches 103.2 % of `fio numjobs=8` and 101.5 % of seastar io_uring `--smp 8` at the same
per-shard QD, with per-shard min/mean 0.963 and zero failed/cancelled/timedout completions;
at N=1–4 it is within 1 % of `fio` except the N=2 cell mean, which is −2.7 % under one
slow-state rep (the four non-slow reps average 1.025× `fio`). The claims that are **not** made: an absolute tail-latency
ranking across engines, a per-shard-file (DS-M) result, or a throughput-per-CPU result.

## 9. Next steps

- Publish the N=8 line only with the CI/caveat shape above (HANDOVER §8 decision).
- Optional: re-run the N=2 cell on a different day to firm bar (a)'s narrowest margin;
  re-run the campaign with a fully unpinned N=8 JUring placement if a placement change is ever
  wanted (would need a new frozen design; not this campaign).
- Open from earlier sessions: M1 write matrix (Step A), B6 buffered durability, VT-throughput
  re-benchmark, per-shard-file DS-M layout, host-root representativeness (Step D).

## 10. Provenance

- Design: `docs/m3-design.md` (A1) sha1 `d391cad3d9a4df50d3c8e0d365e8ba177393b9eb`, commit
  `1f1e548`; v1 sha1 `334bb55a…` commit `b8b7832`.
- Code: B5 frozen tree `0952deb` (`src/main` `04f12dbc…`, `src/test` `666e0644…`); seastar
  runner `bench/seastar/seastar_file_bench.cc` committed `97e818d` (binary sha256 `ce9006d3…`);
  driver/analyzer committed `93fb6fe` / A1 `1f1e548` / loader fix `8bbde41`.
- Session: `bench/results/m3-2026-11-15/` — `cells/` (60 JSONs), `logs/` (60 + compile.log),
  `cell-meta.tsv` (60 rows, one per invocation), `manifest.txt`, `aggregate/m3.{md,json}`,
  `aggregate/first-pass-loader-bug/`; `bench/results/m3-pilot-2026-11-15/` (pilot, not
  analysed into M3), `bench/results/m3-manual-2026-11-15/` (optional `juring-manual` N=8
  reference, §3.7), `bench/results/m3-resumetest/` (chunk/resume harness test).
- Supporting evidence: `scratch/m3-seastar/STATUS.md` + `parity.txt` (seastar `--smp N`
  implementation and JSON parity), `scratch/m3-lead/` (frozen design hash/diagnostics/pilot/
  campaign logs/device state/STATUS), `scratch/m3-lead/diag/` (30 s placement diagnostics).
- Trees/commands: campaign run on HEAD `1f1e548` (benchmark tree); full suite on the frozen
  code tree `mvn -o test` — see `scratch/m3-lead/FINAL-SUITE.log` and the HANDOVER §0.0.
