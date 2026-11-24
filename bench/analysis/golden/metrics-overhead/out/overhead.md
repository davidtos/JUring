# Observability overhead A/B — analysis (`b5-overhead-2026-11-14`)

> **Protocol (frozen before the session).** Paired per-rep ratio `throughput(metrics-on)/throughput(baseline)` for the same cell/rep/position; arms interleaved with the arm order alternating per rep; baseline = commit `6cc1a75` (no metrics) built from a `git archive` scratch tree; metrics-on = frozen tree with metrics **on** (`ring_metrics`, 1 Hz `ring_series`, shard `withLatencySampleEvery(1)`). PASS = mean paired ratio >= 0.99 in every cell and no individual rep below 0.95 unless state-gated and disclosed. Every rep is reported; nothing is trimmed.

- results: `<SESSIONS>/b5-overhead-2026-11-14`
- cells: `shard1` (shard N=1), `shard8` (shard N=8), `threads1` (threads N=1) — 4K randread QD 64 per worker, CPUs 1..N, 10 s warmup + 30 s measured, R=5, 30 s rests, fresh JVM/process per invocation.
- frozen-tree identity at session: HEAD=`6cc1a751f1c652ead1bfa05f7941f75af8fb5ecf` src=`04f12dbc85d723a10dd952b7ac3c51bb6253ed46` test=`666e0644dc063c0b15c5bb52571002a79b5ec657`

## 0. Verdict

**PASS**

The primary verdict uses the Lead-approved **cell-adjusted** fast-state gate (p99 < 600 us × total workers in the cell: 600 us at N=1, 4800 us at N=8); under the literal methodology flat 600 us gate the verdict is **PASS**. The PRIMARY mean bar (mean paired ratio >= 0.99 in every cell) is identical under both labellings because every rep enters every mean — the labelling only decides which sub-0.95 individual reps may be disclosed as state-gated. No rep is trimmed under either labelling.

Metric: mean paired ratio per cell (lower is worse for metrics-on); `overhead` = `(1 - ratio) * 100 %`.

| cell | workers | pairs (R) | baseline mean IOPS | metrics-on mean IOPS | mean paired ratio | stdev | one-sided 95 % CI lower | min rep ratio | overhead % | state-gated reps (adjusted) | verdict (adjusted) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| shard1 | 1 | 5 | 315086 | 320896 | 1.0190 | 0.0300 | 0.9910 | 0.9910 | -1.95 | 2 | PASS |
| shard8 | 8 | 5 | 454835 | 458900 | 1.0110 | 0.0400 | 0.9730 | 0.9570 | -1.10 | 5 | PASS |
| threads1 | 1 | 5 | 323237 | 325307 | 1.0070 | 0.0370 | 0.9720 | 0.9540 | -0.70 | - | PASS |

Pass conditions: every cell mean ratio >= 0.99; no non-state-gated rep ratio < 0.95. The one-sided 95 % CI uses Student's t with df = R-1 (t=2.1318 at R=5); with R=5 the lower bound is the mean minus 2.13 x (stdev/sqrt(5)).

## 1. All reps (paired, raw, never trimmed)

| cell | rep | baseline IOPS | metrics-on IOPS | ratio metrics-on/baseline | baseline p99 us | metrics-on p99 us | state | gated? (adjusted) | gated? (literal) | reason (adjusted) |
|---|---|---|---|---|---|---|---|---|---|---|
| shard1 | 1 | 324490 | 324724 | 1.0007 | 479.4 | 476.5 | fast | no | no | clean |
| shard1 | 2 | 291732 | 307929 | 1.0555 | 623.1 | 538.7 | slow(IOPS anomaly) | YES | YES | baseline arm IOPS anomaly (< 0.92 x max of its cell/arm); baseline arm slow-state p99 >= 600 us |
| shard1 | 3 | 309620 | 324695 | 1.0487 | 534.4 | 479.7 | fast | no | no | clean |
| shard1 | 4 | 325287 | 322332 | 0.9909 | 477.7 | 483.0 | fast | no | no | clean |
| shard1 | 5 | 324298 | 324804 | 1.0016 | 477.2 | 477.5 | fast | no | no | clean |
| shard8 | 1 | 476419 | 475740 | 0.9986 | 2691.9 | 2709.7 | slow(p99 literal) | no | YES | clean |
| shard8 | 2 | 456617 | 475387 | 1.0411 | 3143.3 | 2738.5 | slow(p99 literal) | no | YES | clean |
| shard8 | 3 | 475895 | 476375 | 1.0010 | 2687.8 | 2686.8 | slow(p99 literal) | no | YES | clean |
| shard8 | 4 | 476303 | 455703 | 0.9568 | 2699.9 | 3206.7 | slow(p99 literal) | no | YES | clean |
| shard8 | 5 | 388942 | 411294 | 1.0575 | 7614.7 | 7016.2 | slow(IOPS anomaly) | YES | YES | baseline arm IOPS anomaly (< 0.92 x max of its cell/arm); metrics-on arm IOPS anomaly (< 0.92 x max of its cell/arm); baseline arm slow-state p99 >= 4800 us; metrics-on arm slow-state p99 >= 4800 us |
| threads1 | 1 | 315109 | 328494 | 1.0425 | 477.8 | 484.7 | fast | no | no | clean |
| threads1 | 2 | 314167 | 327408 | 1.0421 | 534.8 | 486.5 | fast | no | no | clean |
| threads1 | 3 | 328101 | 327258 | 0.9974 | 486.6 | 487.9 | fast | no | no | clean |
| threads1 | 4 | 329203 | 314171 | 0.9543 | 483.6 | 473.0 | fast | no | no | clean |
| threads1 | 5 | 329607 | 329203 | 0.9988 | 481.5 | 482.7 | fast | no | no | clean |

### 1.1 Per-cell state and excluded pairs

| cell | workers | p99 gate literal us | p99 gate adjusted us | valid pairs | state-gated reps (adjusted) | non-gated reps < 0.95 (adjusted) | state-gated reps (literal) | non-gated reps < 0.95 (literal) | skipped/missing |
|---|---|---|---|---|---|---|---|---|---|
| shard1 | 1 | 600 | 600 | 5 | 2 | - | 2 | - | - |
| shard8 | 8 | 600 | 4800 | 5 | 5 | - | 1,2,3,4,5 | - | - |
| threads1 | 1 | 600 | 600 | 5 | - | - | - | - | - |

### 1.2 State-gate labelling variants (same reps, same primary mean bar)

| cell | p99 threshold literal | p99 threshold cell-adjusted | gated reps literal | gated reps adjusted | non-gated < 0.95 literal | non-gated < 0.95 adjusted | verdict literal | verdict adjusted |
|---|---|---|---|---|---|---|---|---|
| shard1 | 600 us | 600 us | 2 | 2 | - | - | PASS | PASS |
| shard8 | 600 us | 4800 us | 1,2,3,4,5 | 5 | - | - | PASS | PASS |
| threads1 | 600 us | 600 us | - | - | - | - | PASS | PASS |

The cell-adjusted gate is `600 us x total workers`: the methodology's 600 us calibration is for one QD64 ring, while a cell with W workers has W x 64 requests in flight, so its normal p99 scales with W (the shard-runtime report measured p99(8)/p99(1) = 5.1-5.9 for every engine, including fio, at constant QD64/worker). Both labellings keep the IOPS-anomaly rule (rep < 0.92 x the max IOPS of its own cell/arm) and the background-load gate.

## 2. Metrics-on arm configuration

The metrics-on arm must have metrics on in every invocation; this is what the measured overhead covers (per-ring counters + dispatch-latency sampling + one 1 Hz sampler thread). `dispatch_latency_us` is the **runtime completion latency (registration->dispatch)**: it includes kernel/device completion time and excludes SQE preparation + the handler body; it is the multi-shard scaling report's per-ring p99 and is NOT comparable to fio `clat` or the external `latency_us`. Sampling is every successful registration (the sampling-count rule).

| cell | metrics on (metrics-on reps) | total ring_series points per run | per-ring ring_metrics | dispatch samples (mean) |
|---|---|---|---|---|
| shard1 | 5/5 | 29.0 | 5/5 | 12441507 |
| shard8 | 5/5 | 232.0 | 5/5 | 18369938 |
| threads1 | 5/5 | 29.8 | 5/5 | 0 |

## 3. State gate, disclosures, reproducibility

- **State gate (disclosed, never trimmed):** fast-state p99 thresholds as in §1.2; IOPS anomaly rule = a rep below 0.92 x the maximum IOPS of its own (cell, arm); background-load gate from cell-meta.tsv (foreign process > 20 % CPU; own decaying loadavg residual is recorded as `own-residual` and is not foreign interference). A pair is state-gated when either arm is slow-state or gate-flagged; it stays in every mean.
- Baseline arm identity: commit `6cc1a75`, built by `git archive` into a scratch tree + `mvn -o -q -DskipTests compile` + its own `javac` build (workspace never edited for the baseline). Schema 1.0, no metrics.
- Metrics-on arm identity: frozen workspace tree (see manifest.txt for the expected/live hashes), schema 1.1, metrics on.
- Arms are interleaved with the order alternating each rep (rep odd: baseline->metrics-on; rep even: metrics-on->baseline); every invocation is a fresh process/JVM under `taskset -c`; identical JVM flags for both arms; 30 s rest between invocations; every rep kept.
- This session proves the metrics-overhead bar only; **the multi-shard scaling campaign is not signed off** by it.

## 4. Per-ring queue depth / p99 evidence (metrics-on arm, schema 1.1; multi-shard scaling report data source)

### 4.1 `shard1` per-ring values (mean over the 5 metrics-on reps; queue depth from the 1 Hz `ring_series`, dispatch p99 from `dispatch_latency_us.p99`)

| ring | mean in-flight | max in-flight | mean sq_queued | mean cq_ready | runtime completion p99 us | mean external p99 us | failed | cancelled | series points |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 62.26 | 65.00 | 0.04 | 2.35 | 537.4 | 491.1 | 0 | 0 | 29 |

### 4.2 `shard8` per-ring values (mean over the 5 metrics-on reps; queue depth from the 1 Hz `ring_series`, dispatch p99 from `dispatch_latency_us.p99`)

| ring | mean in-flight | max in-flight | mean sq_queued | mean cq_ready | runtime completion p99 us | mean external p99 us | failed | cancelled | series points |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 64.24 | 65.00 | 0.60 | 0.19 | 2831.2 | 2505.3 | 0 | 0 | 29 |
| 1 | 64.77 | 65.00 | 0.20 | 0.04 | 4115.7 | 3939.5 | 0 | 0 | 29 |
| 2 | 64.77 | 65.00 | 0.18 | 0.06 | 4063.2 | 3916.8 | 0 | 0 | 29 |
| 3 | 64.83 | 65.00 | 0.14 | 0.07 | 4089.4 | 3943.2 | 0 | 0 | 29 |
| 4 | 64.81 | 65.00 | 0.17 | 0.02 | 4089.4 | 3903.7 | 0 | 0 | 29 |
| 5 | 64.88 | 65.00 | 0.09 | 0.06 | 4063.2 | 3926.8 | 0 | 0 | 29 |
| 6 | 64.86 | 65.00 | 0.12 | 0.01 | 4063.2 | 3930.9 | 0 | 0 | 29 |
| 7 | 64.09 | 65.00 | 0.80 | 0.21 | 2831.2 | 2514.5 | 0 | 0 | 29 |

### 4.3 `threads1` per-ring values (mean over the 5 metrics-on reps; queue depth from the 1 Hz `ring_series`, dispatch p99 from `dispatch_latency_us.p99`)

| ring | mean in-flight | max in-flight | mean sq_queued | mean cq_ready | runtime completion p99 us | mean external p99 us | failed | cancelled | series points |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 59.44 | 63.00 | 0.00 | 0.27 | n/a | 483.0 | 0 | 0 | 30 |

This is the schema-1.1 evidence for the multi-shard scaling report requirement "per-ring queue depth and p99": queue depth comes from the measured-phase `ring_series`; the p99 is the runtime completion latency (registration->dispatch, includes kernel/device completion; NOT fio clat / NOT the external `latency_us`). Counters/occupancy are best-effort snapshots (ring counters include runtime-internal wakeup/cancel ops).

## 5. Method

For each cell and rep `i`, `ratio_i = IOPS_metrics-on,i / IOPS_baseline,i` (paired at the same position). Reported: mean, sample stdev, one-sided 95 % lower confidence bound `mean - t_{0.95,n-1} * stdev/sqrt(n)`, min/max. PASS = mean ratio >= 0.99 in every cell AND no non-state-gated ratio < 0.95. `INCONCLUSIVE` means missing pairs/cells, not a pass. Raw data: `cells/`, `cell-meta.tsv`, `manifest.txt`; raw aggregates: `aggregate/overhead.json`.

