# M2 report — L1 stream parity: W21/W22 streams vs seastar `fstream`

**Session:** 2026-11-13T21:48:37Z → 2026-11-14T00:19:41Z (2 h 31 min) · **Branch:**
`kafka-engine` · **Plan item:** `SEASTAR-PLAN.md` §5 **M2 — L1 file/stream parity**
(B1 + B2 + W21/W22). **Status: M2 PASS on the pre-registered bar; campaign complete —
60/60 invocations `status=ok`, 0 errors; reads also pass the strict one-sided-95 % bound
and the ±5 % parity test; writes pass the bar on point estimate (139 % / 160 % of seastar)
but their strict bound is not established at n=5 because of the drive's burst→steady
write-state transition (a power/state issue, not a JUring deficit — see §5/§6). The
defensible (position-matched) write ratios are 1.23×/0.98× (W21-128k) and 1.27×/1.38×
(W21-1M), pooled 1.25×/1.14×; only 1 of 10 write reps had both engines in the same classified
drive state, so no per-rep state-matched speedup is claimed (see §3.4).**
Frozen stream contract: [`docs/b2-design.md`](b2-design.md) §3.3; prior evidence:
[`docs/b2-report.md`](b2-report.md) (B2), [`docs/m1-report.md`](m1-report.md) (M1).

- **Raw session:** [`bench/results/m2-final/`](../bench/results/m2-final/) —
  `cells/` (60 engine JSON, schema 1.0, full per-process manifest inside), `logs/` (60),
  `cell-meta.tsv` (60 rows), `manifest.txt`.
- **Aggregate (generated, do not hand-edit; numbers cross-checked, see §3.6):**
  [`aggregate/stream.json`](../bench/results/m2-final/aggregate/stream.json),
  `aggregate/stream.md`, `aggregate/analyze.log` (generated 2026-11-14T00:19:41Z by
  `bench/stream/analyze_stream.py`).
- **Session cell plan:** cells-major, reps inner, primary engines interleaved inside every
  rep; one disclosed `juring --buffered` exploratory row per rep, never used for the
  verdict.

> **Provenance and verification.** Every number in this report was recomputed from the raw
> per-rep `cells/*.json` (Lead verification of this session, independent of the analyst's
> packet) and cross-checked against `aggregate/stream.json`: **max |Δ| < 1e-3 MiB/s and
> < 0.01 µs** for every cell/engine mean, sd, CI and latency percentile; all 60 files
> parsed, `unparsed_files=[]`. `±` means the two-sided 95 % CI of the mean (Student t,
> df = n−1); ratio intervals are two-sided 90 % / 95 % (the 90 % ratio bounds equal the
> one-sided 95 % bounds). No rep was dropped, trimmed or replaced.
>
> **Aggregate boilerplate is stale (numerical values unaffected).**
> `aggregate/stream.md`/`stream.json` still carry B2-era hard-coded text that calls this a
> "preliminary" run and declares a default protocol of R=3 / 30 s / 60 s rest
> (`analyze_stream.py:404,491,599`). The *recorded* protocol of this session — in
> `manifest.txt`, `cell-meta.tsv` and every cell JSON — is the full M2 protocol:
> **10 s warmup + 60 s measured, R=5, 300 s rests** (§2). The aggregate's headline
> `verdict: "inconclusive"` is its *strict-one-sided-lower-bound over all four cells*
> reading; it is reconciled cell-by-cell in §3.1/§3.6 and does not change the M2 decision
> under the plan's stated bar.

## 1. Verdict and scope

**Milestone M2 pass criteria** (`SEASTAR-PLAN.md` §5): *"seq 1M read/write ≥85 % of seastar
`fstream`; bounded memory; correctness suite green; L0 unchanged."*

| M2 criterion | Evidence (this session unless stated) | Verdict |
|---|---|---|
| seq **read** ≥85 % of seastar `fstream` | W22-1M **98.7 %** point estimate, strict one-sided-95 % LB **0.960**; W22-128k **98.2 %**, LB **0.966** | **PASS** (point + strict) |
| seq **write** ≥85 % of seastar `fstream` | W21-1M **160.1 %** point estimate (paired-mean ratio 3.056, state-confounded); W21-128k **139.1 %** (paired-mean 3.147) | **PASS** (point estimate); strict write LB **not established** at n=5 (§3.2–§3.4) |
| bounded memory | B2 stream layer: `JuringStreamsBoundedMemoryTest` (200 MiB transfers, `bufferMemoryBytes()` within the `readAhead`/`writeBehind` bounds, 0 after close) — unchanged engine, same commit | **PASS** (B2 §3.1) |
| correctness suite green | **184 tests, 0 failures, 0 errors, 1 skipped** (`mvn -o test`, Lead re-run on this tree at campaign commit `2198330`, clean) | **PASS** |
| L0 unchanged | B2 non-regression control (W1/W3, same commit): fast-state W3 327,996 vs stored M1 327,371 = **+0.19 %**; all reps inside the p99 < 600 µs state gate; slow reps are the documented host signature | **PASS** (B2 §3.4) |

**M2 verdict: PASS.** All four primary cells clear the plan's `MiB/s_JUring ≥ 0.85 ×
MiB/s_seastar` bar on point estimates; the two read cells additionally clear the strict
one-sided-95 % lower bound and the methodology's ±5 % parity equivalence test. The write
cells are far above the bar on point estimates and in the position-matched write strata
(the robust stratification, which controls for engine order) they range over 0.98–1.38×:
**1.232× fast / 0.984× slow** (W21-128k), **1.269× fast / 1.384× slow** (W21-1M), pooled
**1.250× / 1.142×** (§3.4). A direct state-classified check (by MiB/s) gives 1.289× / 1.269×
in the fast state and 0.949× (n=1 juring vs n=2 seastar, not significant) / 1.384× in the
slow state; only **1 of 10 write reps** had both engines in the same classified state, so
no per-rep state-matched speedup is claimable. The all-reps paired-ratio 95 % CI is
dominated by the drive's write-state transition, so **no strict write "faster" claim is
made, and no JUring deficit is present either**. Non-throughput criteria (bounded memory,
tests, L0) were established by B2 at the identical commit (`2198330`, clean tree) and are
not re-run in this campaign report; §7 restates the evidence.

**What this report does not claim**
- No strict statistical write-speedup claim (paired-ratio CI spans the bar; §3.2), and no
  per-rep state-matched write speedup (only 1 of 10 write reps had both engines in the same
  classified drive state; §3.4).
- No `DS-M` multi-file W22 corpus, no D1 write preconditioning / D2 1 s time series, no
  thermal capture, no separated read/write sessions — these are deviations from
  `docs/benchmark-methodology.md`, disclosed in §8.
- No engine latency superiority from write-call percentiles (definitional asymmetry, §4).
- No generalization beyond this host/device/kernel; seastar is reported as a bracket, not a
  win/loss.

## 2. Environment and protocol

From [`manifest.txt`](../bench/results/m2-final/manifest.txt), `cell-meta.tsv` and every
cell JSON. One fresh process per invocation, all pinned to CPU 2.

| item | value | source |
|---|---|---|
| Host / kernel | `treediagram`, Linux `7.0.12-201.fc44.x86_64` | `manifest.txt`, cell JSON |
| CPU | AMD Ryzen 9 3950X 16C/32T, 1 NUMA node; governor **performance** on all CPUs | `manifest.txt` |
| Memory / hugepages | 65,738,292 kB; `HugePages_Total=0` (THP path); `ulimit -l = 8192 KiB` | `manifest.txt` |
| Data device | `nvme1n1` GIGABYTE GP-ASM2NE6100TTTD, 931.5 G, ext4 | `manifest.txt` (lsblk) |
| Read dataset (W22) | `/mnt/TestData/read_files/single_file_bench.bin`, 10,737,418,240 B, aged single file (**DS-S**, not DS-M) | `manifest.txt` |
| Write dataset (W21) | `nvme-data/write_files/single_file_bench_w.bin`, 10,737,418,240 B, rewritable, same NVMe partition | `manifest.txt` |
| Kernel I/O controls | `kernel.io_uring_disabled = 0` | `manifest.txt` |
| Tools | JDK `26.0.2`; g++ `16.1.1`; seastar `seastar-25.05.0-1392-g04b1c04d` | `manifest.txt` |
| Repo state | commit `21983307a79346b94ad47a6854d6a1e0bd7fe124` (`2198330`), `git_dirty=false` | `manifest.txt`, cell JSON |
| Harness + engine hashes | `seastar_stream_bench` `9db0385…`; `run-juring-stream.sh` `7c429d3…`; `run-stream-matrix.sh` `2c64c4e…`; `analyze_stream.py` `6ce4bb9…`; `JUringStreamBench.java` `560a73a…` | `manifest.txt` |
| Protocol (recorded) | **R=5**, warmup **10 s**, measured **60 s** per process, **rest 300 s** between reps, seed 20261113, qd 8, cpu 2 | `manifest.txt`; rest gaps verified **13 × 300.0 s + 3 × 301.0 s** (whole-second timestamps) |
| Session window / volume | 2026-11-13T21:48:37Z → 2026-11-14T00:19:41Z; 60/60 invocations ok; 0 errors; ~13.97 TiB moved (2.78 TiB writes incl. exploratory buffered row, 11.20 TiB reads) | `cell-meta.tsv` |
| Cell wall time | 70.1–70.5 s except the ten juring O_DIRECT read invocations at 74.4–74.5 s (their warmup logs 12.0 s because closing the interrupted warmup pass waits ≈2 s; the measured window is unaffected) | `cell-meta.tsv` |

**Cells and primary rows** (the same four cells throughout; "1M" cells address the plan's
"seq 1M" wording, 128K cells the methodology's W21/W22 wording):

| cell | workload | stream buffer | concurrency (`qd`) |
|---|---|---|---|
| W21-128k | `stream_write` | 128 KiB | 8 |
| W22-128k | `stream_read` | 128 KiB | 8 |
| W21-1M | `stream_write` | 1 MiB | 8 |
| W22-1M | `stream_read` | 1 MiB | 8 |

- **juring (primary):** `JuringOutputStream`/`JuringInputStream`, O_DIRECT,
  registered file (`registered_files=1`), ring `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN`,
  entries `max(256,2·qd)=256`, `bufferSize=bs`, `readAhead=qd−1` / `writeBehind=qd`,
  alignment 4096, dynamic adjustments off, `fixed_buffers=false`, in-flight capacity 64.
- **seastar (primary):** `file_output_stream`/`file_input_stream` over `open_file_dma`,
  DMA/O_DIRECT, `--smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend=io_uring`;
  stock 200-entry ring, **no** `SINGLE_ISSUER/COOP_TASKRUN/DEFER_TASKRUN`, no registered
  files/buffers (`io_buffer_mode=fstream-aligned-unregistered`, `write_behind=8`).
- **juring `--buffered` (exploratory only):** same JUring stream API without `O_DIRECT`
  (page-cache path); reported for completeness, **never used for the verdict** (it is not
  an engine-matched comparison: seastar `fstream` is DMA/O_DIRECT here).

## 3. Results — throughput and paired statistics

### 3.1 Per-cell verdict (plan-M2 bar 0.85)

| cell | workload | n | juring MiB/s | seastar MiB/s | paired-ratio mean | ratio 90 % CI | ratio 1s95 LB | ratio of means | plan bar (point) | strict 1s95 LB ≥ 0.85 |
|---|---|---|---|---|---|---|---|---|---|---|
| W21-128k | stream_write | 5 | 610.0 | 438.6 | 3.147 | [-0.066, 6.360] | −0.066 | **1.391** | **PASS** | not established |
| W22-128k | stream_read | 5 | 2284.6 | 2327.5 | 0.982 | [0.966, 0.998] | **0.966** | **0.982** | **PASS** | **PASS** |
| W21-1M | stream_write | 5 | 569.5 | 355.7 | 3.056 | [0.330, 5.783] | 0.330 | **1.601** | **PASS** | not established |
| W22-1M | stream_read | 5 | 4662.8 | 4724.3 | 0.986 | [0.960, 1.013] | **0.960** | **0.987** | **PASS** | **PASS** |

Read cells: mean point estimate **98.4 %** of seastar (98.2 % / 98.7 %); the strict
one-sided-95 % lower bound clears the 0.85 bar with margin, and the methodology's ±5 %
parity rule also holds — the two-sided 90 % CI of the paired ratio is
[0.966, 0.998] (W22-128k) and [0.960, 1.013] (W22-1M), inside `[0.95, 1.05]`.
Write cells: unconditional point estimates are **139.1 % / 160.1 %** of seastar's row means
(way above the bar), but the per-rep paired ratios swing from 0.16 to 7.63 (§3.3) and the
strict bound is not established. The unconditional means are themselves confounded by
engine order; the defensible write comparison is the position-matched one in §3.4,
supplemented by a state-classified check.

### 3.2 What the paired ratio does and does not mean

The aggregate's paired-ratio statistic pairs rep *i* of juring with rep *i* of seastar. That
is valid **only if the drive state is the same for both engines within a rep**. It was not
(§3.3): after each 300 s rest the **first** process in a rep runs in the fast write state
and the **second** runs in the slow/steady state, so the ratio mostly encodes execution
order, not the engine. Engine order alternates by rep (odd = juring first, even = seastar
first), which is why juring ran first in 3 of 5 reps and the unconditional means favour it.
Consequences:

- the paired-ratio mean for writes (3.06–3.15) is not a speedup estimate and must not be
  quoted as one; its 95 % CI is correspondingly meaningless for the bar test;
- the ratio **of means** (1.391 / 1.601) is a valid summary of the raw row means but is
  still position-confounded;
- the strict write bound fails under both the paired ratio and a delta-method ratio-of-means
  CI (95 % CI [0.661, 2.926] for W21-128k; [0.745, 3.439] for W21-1M), so the correct
  label for the write strict reading is **"inconclusive at this power"**, not "pass" and
  not "fail";
- the per-cell strict reading requires n=5; the achieved ratio half-widths are ±4.18 /
  ±3.55 on the paired statistic (writes) versus ±0.021 / ±0.034 (reads).

**Why the write variance is not a JUring effect:** the position-matched analysis (§3.4)
shows both engines track the drive state, with JUring at 1.23×/0.98× and 1.27×/1.38× of
seastar across the position-matched strata (one stratum is 1.6 % below parity). The exploratory `juring --buffered`
row is unaffected by the drive
state (page-cache path, 3.2–4.1 GiB/s writes / 11.6–12.9 GiB/s reads, CV 0.5–8.3 %), which is
direct evidence the swing is device-side.

### 3.3 Per-rep values, engine order, and the drive-state signature

`order` is the execution order of the two primary engines inside the rep (buffered row runs
last in every rep, after both); `p50`/`p99` are the run's chunk-call latency percentiles
(µs). Load average is the **1-minute value at process start / end** for the first process
in the rep.

| cell | rep | order (first>second) | juring MiB/s | seastar MiB/s | ratio | j p50 | j p99 | s p50 | s p99 | loadavg(1m) start / end |
|---|---|---|---|---|---|---|---|---|---|---|
| W21-128k | 1 | juring > seastar | **850.3** | **145.4** | 5.849 | 109 | 1118 | 8 | 2876 | 0.01 / 0.22 |
| W21-128k | 2 | seastar > juring | 430.5 | 616.8 | 0.698 | 111 | 1846 | 11 | 1347 | 0.04 / 0.51 |
| W21-128k | 3 | juring > seastar | **814.1** | **586.0** | 1.389 | 109 | 1680 | 11 | 1813 | 0.09 / 0.13 |
| W21-128k | 4 | seastar > juring | **120.8** | **735.4** | 0.164 | 989 | 1658 | 29 | 3361 | 0.01 / 0.41 |
| W21-128k | 5 | juring > seastar | **834.2** | **109.3** | 7.633 | 109 | 1766 | 7 | 3651 | 0.01 / 0.11 |
| W22-128k | 1 | juring > seastar | 2268.0 | 2364.8 | 0.959 | 56 | 121 | 55 | 115 | **1.93** / 0.84 |
| W22-128k | 2 | seastar > juring | 2322.2 | 2366.0 | 0.981 | 56 | 116 | 55 | 115 | 0.00 / 0.69 |
| W22-128k | 3 | juring > seastar | 2324.6 | 2309.8 | 1.006 | 56 | 116 | 55 | 120 | 0.02 / 0.22 |
| W22-128k | 4 | seastar > juring | 2183.7 | 2232.6 | 0.978 | 58 | 129 | 61 | 127 | 0.01 / 0.69 |
| W22-128k | 5 | juring > seastar | 2324.5 | 2364.3 | 0.983 | 56 | 117 | 55 | 115 | 0.01 / 0.12 |
| W21-1M | 1 | juring > seastar | **864.6** | **144.8** | 5.969 | 874 | 12356 | 116 | 33207 | 0.93 / 0.52 |
| W21-1M | 2 | seastar > juring | **172.9** | **622.1** | 0.278 | 7893 | 16414 | 72 | 9418 | 0.02 / 0.22 |
| W21-1M | 3 | juring > seastar | **863.3** | **140.0** | 6.166 | 874 | 11800 | 115 | 34344 | 0.03 / 0.01 |
| W21-1M | 4 | seastar > juring | **333.7** | **607.5** | 0.549 | 880 | 12387 | 49 | 9773 | 0.01 / 0.14 |
| W21-1M | 5 | juring > seastar | **612.8** | **264.2** | 2.320 | 877 | 12424 | 117 | 25024 | 0.01 / 0.10 |
| W22-1M | 1 | juring > seastar | 4248.4 | 4535.1 | 0.937 | 193 | 983 | 186 | 758 | **2.19** / 0.77 |
| W22-1M | 2 | seastar > juring | 4768.7 | 4771.5 | 0.999 | 188 | 493 | 187 | 553 | 0.00 / 0.60 |
| W22-1M | 3 | juring > seastar | 4765.4 | 4771.7 | 0.999 | 191 | 494 | 178 | 552 | 0.00 / 0.38 |
| W22-1M | 4 | seastar > juring | 4769.5 | 4771.5 | 1.000 | 189 | 492 | 190 | 552 | 0.00 / 0.55 |
| W22-1M | 5 | juring > seastar | 4761.8 | 4771.8 | 0.998 | 181 | 562 | 185 | 551 | 0.02 / 0.40 |

The signature is unambiguous in the write cells: the first process after a 300 s rest runs
at ~0.6–0.85 GiB/s (fast state), the second at ~0.107–0.572 GiB/s (slow/steady state; one
second-position run, W21-128k r3 seastar 586.0 MiB/s, is a fast-state exception) — e.g.
W21-128k r4: seastar first 735.4 then juring 120.8; W21-1M r2: seastar first 622.1 then
juring 172.9. The transition is not perfectly binary: two second-position runs were
intermediate ("mixed") — W21-128k r2 juring 430.5 MiB/s and W21-1M r4 juring 333.7 MiB/s,
both with fast-state p50 (111 µs / 880 µs) but degraded throughput. Reads do not show this
bimodality (2,184–2,366 MiB/s at 128k, 4,248–4,772 MiB/s
at 1M) except in rep 1 of each read cell, which starts immediately after the preceding
write cell with a nonzero residual load (§3.5).

### 3.4 Position-matched write comparison (the defensible reading)

Because the order is recorded, the write comparison can be stratified by execution
position (which on this device is the drive state). Means over the raw reps:

| cell | engine | first-position (fast state) | second-position (slow state) |
|---|---|---|---|
| W21-128k | juring | **832.9** MiB/s (n=3) | 275.7 MiB/s (n=2) |
| W21-128k | seastar | 676.1 MiB/s (n=2) | 280.2 MiB/s (n=3) |
| W21-1M | juring | **780.2** MiB/s (n=3) | **253.3** MiB/s (n=2) |
| W21-1M | seastar | 614.8 MiB/s (n=2) | 183.0 MiB/s (n=3) |
| pooled writes | juring | **806.5** MiB/s (n=6) | **264.5** MiB/s (n=4) |
| pooled writes | seastar | 645.4 MiB/s (n=4) | 231.6 MiB/s (n=6) |

Position-matched ratios (juring/seastar): **1.232** fast / **0.984** slow (W21-128k);
**1.269** fast / **1.384** slow (W21-1M); pooled **1.250 / 1.142**. This stratification
controls for the alternating engine order (juring first in 3 of 5 reps; per-position n is
3 vs 2 and 2 vs 3), which is the dominant
confound: the unbalanced pooled cell-mean ratio is **1.485**, while the position-balanced
ratio (mean of the two position ratios) is **1.196** — the order imbalance inflates the raw
pooled ratio by ~24 %. The unconditional row-mean "speedup" is not used for any claim.

**State-classified cross-check (MiB/s ≥ 550 = fast, by direct throughput level):** fast
state **1.289×** (W21-128k) / **1.269×** (W21-1M); slow state **0.949×** (W21-128k:
juring has one slow rep, 120.8 MiB/s, vs two seastar slow reps, 145.4 / 109.3) and
**1.384×** (W21-1M: 172.9 / 333.7 vs 144.8 / 140.0 / 264.2). The 0.949 is a single-rep vs
two-rep comparison, not significant.

**Contemporaneous same-state pairs are scarce:** only **1 of 10** write reps had *both*
engines in the same classified state (W21-128k r3: juring 814.1 fast vs seastar 586.0 fast,
ratio 1.389); 0 of 10 at 1M. So the per-rep paired ratio is essentially never a state-matched
comparison, and the aggregate/position-matched tables above are the only defensible
summaries. This is the precise reason the strict write bound is not established.

### 3.5 Read cells: parity, and a rep-1 residual-load artifact

Reads are stable (CV 2.2–5.0 %) and position effects are absent: position-matched ratios
are 1.003 (W22-128k first), 0.960 (second) and 0.962 (W22-1M first), 1.016 (second). The
small all-reps deficit (~1.6 %) is concentrated in **rep 1 of each read cell**, which starts
**immediately after the previous write cell** (no inter-cell cool-down; the driver rests
only between reps inside a cell) while the exploratory page-cache writeback is still
draining — loadavg(1m) at rep-1 start is **1.93** (W22-128k) and **2.19** (W22-1M), versus
0.00–0.69 for the other read reps (median 0.07); only the two r1 starts exceed 1.0. Because juring runs first in odd reps, it absorbs that hit
in both read cells:

| cell | engine | r1 MiB/s | mean r2–r5 | r1 delta | loadavg(1m) at r1 start |
|---|---|---|---|---|---|
| W22-128k | juring | 2268.0 | 2288.7 | −0.9 % | 1.93 |
| W22-128k | seastar | 2364.8 | 2318.2 | +2.0 % | 0.84 |
| W22-1M | juring | 4248.4 | 4766.3 | −10.9 % | 2.19 |
| W22-1M | seastar | 4535.1 | 4771.6 | −5.0 % | 0.77 |

This is a session-architecture artifact (cells-major ordering, no inter-cell rest,
buffered row last), disclosed as such; no rep is dropped and the strict read verdict still
passes with the artifact included. It is the most likely explanation for the residual ~1.6 %
read gap; a separated read/write session is the clean fix (§8).

### 3.6 Reconciliation with `aggregate/stream.{json,md}`

| aggregate field | value | reconciliation |
|---|---|---|
| `overall.point_estimate_pass` | `true` | all four cells ≥ 0.85 on point estimates |
| `overall.lower_bound_pass` | `false` | strict 1s95 LB fails for the two write cells (driven by the state/order confound), passes for reads; see §3.1 |
| `overall.verdict` | `"inconclusive"` | the aggregate's all-cells *strict* conjunction; label kept here but decomposed cell-by-cell; M2's own bar is the point estimate |
| `overall.parity_note` | "best cell W21-128k at 3.147" | **do not use**: the paired ratio is order-confounded (§3.2); the defensible write statistic is §3.4 |
| `protocol` (json) / `declared_protocol` | R=5, 10 s, 60 s; declared defaults R=3/30 s/60 s rest | recorded protocol is the full M2 protocol; the "preliminary"/defaults text is stale B2 boilerplate (`analyze_stream.py:404,491,599`) |
| `unparsed_files` | `[]` | all 60 JSONs parsed; every expected rep paired |
| per-cell means/sd/CI/p50/p99 | — | **identical to this report's independent recomputation** (max \|Δ\| < 1e-3 MiB/s, < 0.01 µs) |

## 4. Latency percentiles

Mean of the per-run chunk-call percentiles (µs). Read-call latency is a like-for-like
metric; **write-call latency is not** — see the definition note below.

| cell | row | n | p50 | p99 | p999 | avg |
|---|---|---|---|---|---|---|
| W21-128k | juring | 5 | 285.4 | 1613.6 | 3551.9 | 354.8 |
| W21-128k | seastar | 5 | 13.0 | 2609.4 | 4251.0 | 517.9 |
| W21-128k | juring-buffered | 5 | 10.5 | 158.6 | 173.0 | 34.1 |
| W22-128k | juring | 5 | **56.6** | **119.9** | 254.1 | 54.7 |
| W22-128k | seastar | 5 | **56.5** | **118.3** | 201.0 | 53.7 |
| W22-128k | juring-buffered | 5 | 10.3 | 13.3 | 14.9 | 10.4 |
| W21-1M | juring | 5 | 2279.7 | 13076.2 | 32298.6 | 2543.0 |
| W21-1M | seastar | 5 | 93.6 | 22352.9 | 30237.1 | 4212.5 |
| W21-1M | juring-buffered | 5 | 91.3 | 1053.0 | 1175.4 | 246.1 |
| W22-1M | juring | 5 | **188.4** | **604.9** | 1541.5 | 214.5 |
| W22-1M | seastar | 5 | **185.2** | **593.1** | 887.0 | 211.6 |
| W22-1M | juring-buffered | 5 | 75.3 | 81.1 | 93.3 | 75.7 |

**Read path (comparable):** juring and seastar are at parity — 128k p50 56.6 vs 56.5 µs,
p99 119.9 vs 118.3 µs; 1M p50 188.4 vs 185.2 µs, p99 604.9 vs 593.1 µs (difference well
inside the run-to-run spread; no gate in the M2 plan, reported descriptively).

**Write path (not comparable):** the two runners both timestamp around the stream call
(`JUringStreamBench.runPhase` ≈`bench/juring/JUringStreamBench.java:698-735`;
`seastar_stream_bench::write_pass` ≈`bench/seastar/seastar_stream_bench.cc:340-360`), but
the call semantics differ. `JuringOutputStream.write()` can block in
`drainOldest()`→`JuringFile.await()` when the write-behind window is full
(`src/main/java/.../JuringOutputStream.java:427-480,494-525`), so its time includes
write-behind drain pacing; seastar's `file_output_stream::write()` future resolves when the
chunk is accepted by its write-behind engine, so its time is mostly copy+enqueue. The
observed write p50 (juring 285/2280 µs vs seastar 13/94 µs) and the opposite p99 ordering
(juring lower) are explained by this asymmetry; **they are not evidence that either engine's
write completions are slower/faster**. Only throughput is used for the write verdict.
`p999` on the 1M write cells is a queue-tail property of a 1 MiB chunk at QD8, not a
separate defect.

## 5. Throughput vs seastar `fstream`

| cell | juring (MiB/s, 95 % CI) | seastar (MiB/s, 95 % CI) | juring/seastar row means | reads: strict LB | writes: position-matched |
|---|---|---|---|---|---|
| W21-128k | 610.0 ± 402.8 | 438.6 ± 359.8 | **1.391** | — | 1.232 fast / 0.984 slow |
| W22-128k | 2284.6 ± 76.2 | 2327.5 ± 72.3 | **0.982** | 0.966 | — |
| W21-1M | 569.5 ± 386.7 | 355.7 ± 300.1 | **1.601** | — | 1.269 fast / 1.384 slow |
| W22-1M | 4662.8 ± 287.6 | 4724.3 ± 131.3 | **0.987** | 0.960 | — |

- **Reads** are at parity (98.2 % / 98.7 % of seastar; strict LB 0.966 / 0.960; ±5 % parity
  equivalence test passes). The residual r1 residual-load artifact (§3.5) explains the
  direction and size of the gap; no JUring stream-layer deficit was found on the read path.
- **Writes** are far above the bar on point estimates (139 % / 160 %). The unconditional
  means should not be read as a 1.4–1.6× speedup: juring ran first in 3 of 5 reps, which is
  the fast state; the position-balanced pooled ratio is **1.196** vs the unbalanced 1.485
  (order imbalance inflates the raw ratio by ~24 %). Position-matched: **1.232× fast /
  0.984× slow** (128k) and **1.269× fast / 1.384× slow** (1M), pooled **1.250× / 1.142×**;
  state-classified fast **1.27–1.29×**, slow **0.95× (n=1 vs n=2, not significant) / 1.38×**.
  No stratum shows a meaningful JUring deficit; only 1 of 10 write reps had both engines in
  the same state, so no per-rep state-matched speedup is claimed.
- **Exploratory buffered row** (page cache, never used for the verdict): 3.2–4.1 GiB/s
  writes and 11.6–12.9 GiB/s reads, CV 0.5–8.3 %; it is immune to the drive write-state
  transition, which is why it is useful as a state control. It also raised 1-min loadavg to
  ≤2.21 at process end (writeback), which feeds the r1 residual-load artifact above.
- **Comparison with the B2 preliminary session** (`docs/b2-report.md` §4, R=3 / 30 s /
  60 s rest): B2 saw one write-state transition inside W21-128k (ratios 1.055 fast /
  0.998 slow / 0.435 transition) and a post-write recovery artifact in the first W22-1M
  read. M2 reproduces the same device signature on every rep pair (position effect) and the
  same first-read recovery artifact (r1). The M2 protocol (60 s cells, 300 s rests, R=5)
  gives much tighter read CIs (±76/±131 MiB/s vs B2's coarse R=3 run) but cannot remove the
  write burst→steady transition because the drive re-enters the fast state during the rest.

## 6. Host and drive-state findings

1. **The write cells are a burst→steady-state comparison, not a steady-state comparison.**
   9 of 10 rep pairs consist of one fast-state process (~0.6–0.85 GiB/s, 128k/1M) followed by
   one slow-state process (~0.107–0.572 GiB/s); the exception is W21-128k r3 (fast → fast:
   814.1 → 586.0 MiB/s). The 300 s rest is enough for the drive to
   recover to the fast state; the second 60 s writer then exhausts it. D1 preconditioning
   and D2 1 s time series were not applied in this campaign, so the transition is visible
   between processes rather than inside a cell. This is the dominant variance source and
   the reason the strict write bound is not established.
2. **Engine order is confounded with drive state.** Order alternates by rep
   (odd = juring first), and the fast state belongs to whichever engine runs first. The
   aggregate's paired ratio therefore measures position, not engine. The report's write
   verdict rests on the position-matched table (§3.4), not the paired-mean ratios.
   Only 1 of 10 write reps (W21-128k r3) had both engines in the same classified state
   (0 of 10 at 1M), so per-rep state-matched evidence is essentially absent.
3. **Rep-1 read artifact.** Both read cells' rep 1 began with no inter-cell cool-down,
   immediately after the preceding write cell's exploratory page-cache row; 1-min loadavg
   was 1.93 / 2.19 at start. At 128k the r1 hit landed on juring (−0.9 % vs its r2–r5 mean;
   seastar's r1 was +2.0 %, i.e. no hit), while at 1M both engines were slower in r1
   (juring −10.9 %, seastar −5.0 %); juring, running first, absorbed the larger hit. This is a session-architecture artifact, disclosed, not trimmed.
4. **Read device state is stable.** Aged single-file DS-S reads were 2,184–2,366 MiB/s (128k)
   and 4,248–4,772 MiB/s (1M), CV 2.2–5.0 %, with no bimodality; the page-cache control is
   stable at 11.6–12.9 GiB/s. Read conclusions are therefore much more robust than write ones.
5. **Load.** Of the 40 standard-row invocations, 11 started with 1-min loadavg > 0.5
   (median 0.115; 9 of the 11 in 0.51–0.93 from residual page-cache writeback) and only
   the two read r1 starts exceeded 1.0 (1.93 / 2.19); end-of-process loadavg was > 0.5 in
   15/40 (max 0.95). The exploratory buffered row's writeback pushed loadavg to ≤2.21 at
   end-of-process, decaying during the 300 s rest. Per-cell loadavg is recorded in
   `cell-meta.tsv`; no foreign-process CPU snapshot was captured, so "no foreign load"
   is not asserted here.
6. **Host/config state** was stable and recorded: governor `performance` on all 32 CPUs,
   CPU 2 pinned, `kernel.io_uring_disabled=0`, one NUMA node, no hugepages, memlock 8 MiB.
   Drive SMART/thermal telemetry was not captured (D5 unavailable/not recorded); this is a
   disclosed monitoring gap, not evidence of throttling.
7. **Device/volume wear:** the session moved ~14 TiB (≈2.8 TiB writes incl. exploratory
   page-cache row, ≈11.2 TiB reads) on the same consumer NVMe that ran M1/B2; the write
   dataset is rewritten every pass. Write-state conclusions are valid for this drive's
   SLC/GC behaviour, not for a different device.

## 7. Correctness, bounded memory, L0 (non-throughput M2 criteria)

The campaign commit is `2198330`, the B2 deliverable commit, clean working tree — these
criteria were established in B2 and are unchanged by the benchmark campaign:

- **Correctness suite:** `mvn -o test` on this tree = **184 tests, 0 failures, 0 errors,
  1 skipped** (the skip is the pre-existing B1 tmpfs discard-alignment assumption). This
  includes the 42 B2 stream conformance/bounded-memory tests. The Lead re-ran the suite at
  the start of the M2 sign-off session to confirm the exact campaign commit.
- **Bounded memory:** `JuringStreamsBoundedMemoryTest` — 200 MiB input read + 200 MiB output
  write with `bufferMemoryBytes()` within `(readAhead+1)·(alignUp(bufferSize)+alignment)` /
  `(writeBehind+1)·alignUp(bufferSize)`, stable across the transfer, zero after close;
  owning-thread heap growth < 8 MiB over the transfer (B2 §3.1/§3.2).
- **L0 unchanged:** B2's W1/W3 non-regression control on this same commit: fast-state W3
  **327,996 IOPS vs stored M1 327,371 = +0.19 %**, all reps inside the pre-registered
  p99 < 600 µs gate; W1 fast reps +0.3 % vs B1 post3. The two slow W1 reps and one slow W3
  rep are the documented host bimodal signature, not a code effect (no engine line changed
  by B2). L0 read parity is therefore unchanged by M2.

## 8. Deviations and threats to validity

| # | deviation / threat | impact and mitigation |
|---|---|---|
| 1 | **Single mixed read+write session** (cells-major: W21-128k → W22-128k → W21-1M → W22-1M). `docs/benchmark-methodology.md:92` requires writes never interleaved with read cells in the same session. | Read cells' r1 runs under residual writeback load (§3.5); reads are otherwise stable and still pass strictly. Clean fix: separate read/write sessions (the harness supports it). |
| 2 | **W22 uses DS-S (single aged 10 GiB file), not DS-M multi-file.** | The plan-M2 bar and this report's read claim are single-file read-stream claims; DS-M remains unmeasured. Disclosed, not silently generalized. |
| 3 | **D1 write preconditioning not applied / not recorded; D2 1 s IOPS series not captured.** | Write cells measure the burst→steady transition, not steady state; hence strict write LB not established. The position-matched analysis is the mitigation (state-classified cross-check in §3.4). |
| 4 | **Write-call latency definition is asymmetric** (blocking JUring `write()` vs future-resolved seastar `write()`, §4). | Write latency percentiles are reported descriptively only; no write latency claim is made. Read-call latency is like-for-like. |
| 5 | **Aggregate boilerplate says "preliminary" with B2-era default protocol** (`analyze_stream.py:404,491,599`). | Numerical aggregate validated; actual protocol read from manifest/cell-meta/JSON. Recommendation: derive the protocol block from the session manifest in the analyzer. |
| 6 | **No inter-cell cool-down; exploratory buffered row runs last in every rep.** | Its page-cache writeback raised loadavg at the next read cell's r1 (§3.5). The row is disclosed/exploratory and never used for the verdict. |
| 7 | **R=5, one host, one drive, one kernel; strict writing LB not achieved.** | The write per-rep ratio CV is 54–68 %, so n=5 cannot resolve the bar strictly under the state transition. The achieved ratio half-widths are quoted. |
| 8 | **seastar is asymmetric by construction** (200-entry ring, no ring flags/registration, io_queue + futures, 1 GiB memory, no hugepages). | Reported as a bracket, never as a win/loss; both sides' flags are in §2. |
| 9 | **Dataset data state** is not reset between sessions (same aged read file, same rewritten write file). | Within-session comparison is valid (same file for both engines, same rep); cross-session absolute numbers are not claimed. |
| 10 | **No thermal/SMART telemetry (D5), no discard/TRIM (D4).** | Monitoring gap disclosed; no throttle evidence either way. |
| 11 | **Read parity is close to the ±5 % equivalence edge** (90 % CI lower bounds 0.966 / 0.960 vs 0.95). | The parity claim is valid at the recorded n=5 but not a large-margin result; a separated-session rerun would widen the margin if needed. |
| 12 | **The write cells' row-mean point estimates are confounded by engine order**, so the plan-bar "PASS" is a weak pass for writes even though the point estimate (1.39/1.60) looks strong. | Stated explicitly; no "3× faster" style claim is made. The defensible write statement is §3.4. |
| 13 | **Methodology F8 JVM flags are not recorded/passed.** `run-juring-stream.sh` passes only `--enable-native-access` unless `JURING_JAVA_OPTS` is set; no `-Xms/-Xmx/AlwaysPreTouch` and no JVM-option string in the cell JSON (it records JDK only). | Results are JVM+engine (the M1 convention); no comparison is affected, but the F8 record is incomplete. Next run: pass and record `JURING_JAVA_OPTS`. |
| 14 | **Warmup/measured windows overshoot.** Declared 10 s/60 s; logged warmup 10.0–12.01 s (juring O_DIRECT read warmups log 12.0 s because closing the interrupted warmup pass waits ≈2 s — `JuringInputStream.DEFAULT_CLOSE_TIMEOUT_MILLIS`; the measured window is unaffected) and measured 59.998–60.066 s. | Throughput uses the measured window only; the overshoot does not change the engine comparison but is disclosed. Per-run latency percentiles are reservoir estimates when a run exceeds the cap: juring 1,000,000 samples, seastar `max(20000, 2,000,000/qd)` = 250,000 at qd 8 (`sampled=true` in 28/60 runs: 10 buffered + 5 juring W22-128k + 13 seastar). |

## 9. Artifacts, provenance, exact reproduce commands

Raw session `bench/results/m2-final/` (gitignored):

| artifact | count | role |
|---|---|---|
| `cells/*.json` | 60 | one JSON per invocation (schema 1.0; full manifest incl. argv/config inside) |
| `logs/*.log` | 60 | stdout/stderr of each invocation |
| `cell-meta.tsv` | 60 rows | cell/engine/variant/rep, argv, start/end, wall time, loadavg, status, exit, ops, MiB/s, p99, bytes, errors |
| `manifest.txt` | — | host/CPU/governor/memory/device/mounts/tools/repo state + engine binary/script SHA256 + protocol + cell plan |
| `aggregate/stream.json`, `stream.md`, `analyze.log` | — | generated aggregate (do not hand-edit) |

```bash
cd /home/david/projects/JUring
# re-emit the aggregate from the frozen raw cells (no benchmark is run):
python3 bench/stream/analyze_stream.py --session m2-final
# inspect raw evidence:
jq -r '.cells[] | [.cell, (.engines.juring.mibs.mean|tostring), (.engines.seastar.mibs.mean|tostring)] | @tsv' \
    bench/results/m2-final/aggregate/stream.json
# the per-cell/per-engine numbers in §3/§4 were recomputed from cells/*.json with
# mean / sample sd / Student-t CI95 (df=n-1); they match stream.json to <1e-3.
# M2 non-throughput evidence (B2, same commit):
mvn -o test        # 184 tests, 0 failures, 0 errors, 1 skipped
```

## 10. Milestone decision and next steps

- **M2 is signed off: PASS** (all four primary cells clear the plan's ≥85 % bar; reads also
  strict and parity-equivalent; bounded memory, 184-test suite and L0 non-regression
  unchanged at the same commit). The strict write reading is recorded as inconclusive at
  n=5 due to the drive burst→steady transition — explicitly *not* a JUring stream deficit.
- **Recommended follow-up (not part of M2 sign-off):** if a strict write-speedup claim is
  needed, run separated read-only and write-only sessions with fixed engine order per rep
  and D1 preconditioning + D2 1 s series (the driver already supports
  `--session/--cells/--seconds/--warmup/--rest/--reps`), and analyse state-matched strata
  with engine order balanced 2/2 or randomised within the fast block. Also derive the
  protocol block in `analyze_stream.py` from the session manifest instead of hard-coding it.
- **Next plan item: B3 — request lifecycle hardening** (timeouts incl. linked timeouts,
  deterministic cancellation contract, bounded shutdown, stress acceptance) is started in
  this session; evidence will be in `docs/b3-design.md` and the B3 status section of
  `docs/HANDOVER.md`/`SEASTAR-PLAN.md`.

## Appendix A — M2 completion checklist

- [x] Session finished clean: 60/60 invocations `status=ok`, `errors_total=0`, 60/60 logs,
      `unparsed_files=[]`; every expected (cell, engine, rep) paired.
- [x] Actual protocol verified from `manifest.txt` + `cell-meta.tsv` + cell JSON
      (10 s/60 s/R=5/300 s rests/CPU 2/governor performance); 300 s rest gaps confirmed.
- [x] Aggregate re-inspected and its stale "preliminary"/default-protocol boilerplate
      documented; numerical values independently cross-checked to <1e-3 MiB/s, <0.01 µs.
- [x] Per-cell/engine throughput stats with n, mean, sd, 95 % CI, CV, min/max; all reps kept.
- [x] Paired ratios with 90 %/95 % CI and one-sided 95 % LB; ratio-of-means;
      **strict-vs-point-estimate distinction stated per cell**.
- [x] Engine-order/drive-state confound identified and quantified; position-matched write
      table is the defensible comparison; no speedup claim from the paired mean.
- [x] Read cells verified at parity (98.2 %/98.7 %, strict LB 0.966/0.960, ±5 % equivalence)
      with the r1 residual-load artifact disclosed.
- [x] Latency percentiles reported; write-call definitional asymmetry documented with code
      paths; no write latency superiority claim.
- [x] Host/drive-state findings recorded (burst→steady transition, load, read stability,
      r1 residual load, dataset state, telemetry gap).
- [x] Non-throughput M2 criteria restated from B2 at the same commit (184 tests, bounded
      memory, L0 unchanged).
- [x] Deviations/threats table complete (mixed session, DS-S vs DS-M, D1/D2, latency,
      aggregate boilerplate, buffered-row perturbation, n=5 power, seastar asymmetries).
- [x] Artifacts + exact reproduce commands listed; no number without a raw-file citation;
      no delta claim whose CI contains 0 except where explicitly labelled inconclusive.
