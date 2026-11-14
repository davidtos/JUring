# M3 design — W17–W20 multi-shard scaling campaign (FROZEN before measurement)

**Session:** `bench/results/m3-2026-11-15/` (gitignored). **Branch** `kafka-engine`.
**Frozen identity:** code under test is the B5 frozen tree at commit `0952deb`:
`src/main/java` content sha1 `04f12dbc85d723a10dd952b7ac3c51bb6253ed46`, `src/test/java`
content sha1 `666e0644dc063c0b15c5bb52571002a79b5ec657` (both re-verified 2026-11-14 23:27).
The campaign driver records HEAD, both content hashes, this design file's sha1, the harness
sha256s and the full manifest; a mismatch aborts. This file is frozen before the first measured
run; only a disclosed, separately-versioned amendment may change it (an amended campaign is not
M3-signing).

## 1. Restated bars (replace plan-M3 `SE(8)≥0.75` and `p99(8)≤1.15·p99(1)`, which are invalid for
this single-device, constant-QD-per-shard protocol — `fio` itself fails both; B4 §6/§8). **M3 is
signed only if (a)–(e) all PASS. Any bar INCONCLUSIVE ⇒ M3 INCONCLUSIVE; any FAIL ⇒ M3 FAIL.**

- **(a) N-shard throughput vs `fio` at the same N.** For N=1,2,4,8: mean over R=5 of
  `IOPS(juring-group,N) / IOPS(fio numjobs=N)`. PASS iff the one-sided 95 % t lower bound
  (df=R−1) is ≥ 0.85 at **every** N; INCONCLUSIVE iff the mean is ≥ 0.85 but a lower bound is
  < 0.85; FAIL iff the mean is < 0.85. (B4's 98.5 % at N=8 is the starting point, not a sign-off.)
- **(b) Scaling and tail vs `fio` at the same N — no `N·IOPS(1)` cross-engine denominator.**
  `SE(N)=IOPS(N)/(N·IOPS(1))` computed per engine from **this campaign's** cells; report
  `SE_juring(N)/SE_fio(N)` and `p99_juring(N)/p99_fio(N)` at every N with one-sided 95 % t CIs
  (df=R−1). PASS iff at N=4 **and** N=8 the 95 % LB of the SE ratio is ≥ 0.85 **and** the 95 % UB
  of the p99 ratio is ≤ 1.15; INCONCLUSIVE iff the mean meets the bound but the corresponding CI
  bound does not; FAIL otherwise. N=1,2 are disclosure. (Same numerical intent as the old bars,
  re-anchored to the correct comparator; latency definitions stay engine-native and are
  disclosed — see §5.)
- **(c) Per-shard balance under the chosen N=8 placement.** For `juring-group`: per-rep
  `min(shard IOPS)/mean(shard IOPS)`; cell statistic = mean of the R=5 per-rep values. PASS iff
  the one-sided 95 % LB ≥ 0.85; INCONCLUSIVE iff the mean is ≥ 0.85 but the LB < 0.85; FAIL iff
  the mean is < 0.85. Every rep is reported. (`fio` per-job spread is `n/a` under the frozen
  `group_reporting=1`; seastar per-shard spread is disclosure only.)
- **(d) seastar io_uring total IOPS at N=8.** Plan §5 M3 row: "total IOPS vs seastar uring 90 % CI
  LB ≥ 0.90" (JUring subject). PASS iff the one-sided 95 % LB of
  `IOPS(juring-group,8)/IOPS(seastar io_uring,8)` ≥ 0.90; INCONCLUSIVE iff the mean is ≥ 0.90 but
  the LB < 0.90; FAIL otherwise. The reverse ratio is reported for transparency. Other N are
  bracket disclosure, not a bar.
- **(e) Per-ring queue depth + p99 from B5 JSON schema 1.1.** For every `juring-group` cell
  (N=1,2,4,8) report per-ring mean/max in-flight, mean `sq_queued`/`cq_ready`, external p99, and
  the **runtime completion p99** = `dispatch_latency_us.p99` (registration→dispatch; includes
  kernel/device completion; **not** `fio clat`, **not** external `latency_us`), plus
  failed/cancelled/timed-out counters. PASS iff present and consistent for all four cells
  (max in-flight ≈ 64 per ring under QD 64; if a counter is absent that is INCONCLUSIVE).
  The B5 <1 % overhead bar is already PASS (`bench/results/b5-overhead-2026-11-14/`, mean paired
  ratios 1.019/1.011/1.007) and is not re-run.

## 2. Frozen protocol

Cells: **`juring-group` + `fio` at N=1,2,4,8 (8 cells, 40 invocations) and `seastar --smp N` at
N=1,2,4,8 (4 cells, 20 invocations)** = 12 cells / 60 invocations. Optional `juring-manual` N=8
(5 invocations) only if ahead of schedule; it is reference disclosure, never a bar. Per invocation:
**10 s warmup + 30 s measured, fresh process/JVM**, 4K randread, QD 64 per worker/shard, seed
20261114. **R=5.** **300 s rest between invocations** (measured from process exit to next start);
no rest may be shortened, no rep added/removed after data, **every rep kept and reported**. Within
each rep block the engines are interleaved and the order alternates per rep: odd `(rep+N)` =
`juring-group, fio, seastar`; even = `seastar, fio, juring-group`. All invocations use the same
dataset/CPU placement/measurement window. Only read cells; dataset opened read-only / `fio --readonly`.
Never run Maven or another harness during cells.

## 3. Dataset and placement

**Dataset:** DS-S = the aged shared 10 GiB `/mnt/TestData/read_files/single_file_bench.bin`
(10,737,418,240 B, read-only, O_DIRECT) — same file as B4/M1/M2 for comparability.
**Disclosed difference:** seastar and `fio` here read the same single shared file (one fd per
seastar reactor; `fio` one file, N jobs); the W18–W20 DS-M one-file-per-shard layout is **not**
used tonight (no DS-M), so extent/page-cache sharing is common to all engines and the seastar
per-shard-file advantage/penalty is out of scope.
**Placement.** N=1..4: CPUs `1..N` for every engine (`juring-group --first-cpu 1`; `fio`/seastar
`taskset -c 1..N` / `--cpuset 1..N`). **N=8: one shard per NVMe hctx, CPU set
`1,2,4,6,8,10,12,14`** (data device `nvme1n1`: 8 hw queues, `mq/0=0,1,16,17 … mq/7=14,15,30,31`):
`fio` and seastar are confined/pinned to that exact set; `juring-group` runs **unpinned**
(`--first-cpu -1`) inside a `taskset` mask of the same set — the B4 naive `1..8` pinning is **not**
kept (it produced the 0.802 spread artifact); unpinned is the pre-declared choice here because
shard i→first-cpu+i cannot express the hctx set without changing the frozen B4 API, and B4
measured unpinned spread 0.965–0.983 at equal total IOPS. Same CPU set for `fio`/seastar, CPUs 0
and 16–31 idle. `mq/*/cpu_list` and every engine's actual placement are recorded in the manifest.

## 4. Analysis rule (fixed now)

Per cell: mean, sample stdev, min/max, CV, and one-sided 95 % t CI (df=R−1), all R=5 reps listed.
State gate (disclosed, **never trims**): IOPS anomaly = rep < 0.92 × max IOPS of its own
cell/engine; cell-adjusted slow-state flag = external p99 ≥ 600 µs × total workers of the cell;
F14 load/foreign-process gate. Gated reps stay in every mean/CI and are labelled. No silent
trimming; a re-run of a failed invocation is allowed only as a gate-retry (≤1 extra invocation per
engine), recorded and disclosed. Every table is generated from raw JSON by the analyzer; verdicts
come only from the frozen rules in §1.

## 5. Engine configurations (best documented config, fully disclosed)

`juring-group`: `JUringFileBench --runtime=shard --shards N`, one `IoShard`/ring per shard,
handler-driven QD-64 pipeline, registered file + fixed buffers, metrics on (schema 1.1),
`-Xms8g -Xmx8g -XX:+AlwaysPreTouch`, fresh JVM. `fio 3.40`: io_uring, matched
`--registerfiles=1 --fixedbufs=1 --iodepth=64 --direct=1 --rw=randread --time_based
--runtime=30 --ramp_time=10 --group_reporting=1 --clocksource=clock_gettime
--percentile_list=50:95:99:99.9 --clat_percentiles=1`. `seastar`: `--smp N --cpuset <set>
--memory 1G --lock-memory 0 --reactor-backend io_uring`, one reactor/shard, 64 in-flight
fibers/shard, JSON schema-1.1 parity (no registered files/fixed buffers; 200-entry ring —
engine asymmetries, reported not equalised). Latency definitions: JUring external = before
prepare/submit → completion handler; fio = `clat` (submission→completion); seastar = before
submit → completion; the B5 runtime-completion p99 is a separate per-ring metric. Cross-engine
p99 values are compared only as the frozen same-N ratio and never as an absolute ranking.

## 6. Execution, resumability, gates

Driver `bench/shard/run-m3-campaign.sh` (resumable `--resume`, per-invocation `cell-meta.tsv` +
raw JSON, chunks of ≤2 cells); analyzer `bench/shard/analyze_m3.py`. Pilot first: 2 s cells for
all engines at N=1,2,4,8 + device/dataset state check (pilot data never enters the campaign).
Campaign runs alone on the machine. Session gates (local time, hard stop **10:00**): design
frozen 00:30; seastar `--smp N` + JSON parity 02:15; pilot green 02:45; campaign 02:45–08:30;
analysis + `docs/m3-report.md` + HANDOVER/SEASTAR-PLAN updates 08:30–09:30; `mvn -o test` green on
the frozen tree + commits 09:30–10:00; 30–60 min unallocated buffer. Fallback ladder: (1) drop
`juring-manual`; (2) if seastar `--smp N` is not solid by 02:15, run the 40-invocation
`juring-group`+`fio` campaign (~3.8 h), commit the seastar work for follow-up, and report M3
**INCONCLUSIVE** on bar (d); (3) never shorten rests, reduce R, or trim slow-state reps. If the
session is interrupted: same session dir, `--resume`, keep every complete cell; any protocol
variant must be frozen and disclosed **before** it is run and makes the campaign non-signing.
