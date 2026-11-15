# M1 write matrix — frozen design (SEASTAR-PLAN Step A, session 11)

**Status:** **FROZEN v1.0 — 2026-11-15, before any measurement** (sha1 recorded in
`scratch/stepa-lead/FREEZE.md`; post-freeze changes are numbered amendments in §12 only).
**Scope:** the write half of milestone M1 — cells W5–W8 (4K randwrite) and W12–W14
(1M seqwrite) of `docs/benchmark-methodology.md` §2 — plus the M1 write rule-1 decision at
the saturation cells W7/W8. This design is pre-campaign: it fixes protocol, statistics and
acceptance before the first measured run, so the verdict cannot be re-defined after seeing
the data.
**Basis:** `SEASTAR-PLAN.md` §4 (M1) + §7 "Step A"; `docs/benchmark-methodology.md` §2,
§5 (D1–D8), §6 (statistics), §7 (M1 gate) and fairness rules F0/F12/F14;
`docs/m1-report.md` §13/§14 (read-path PASS; write half open, W7 rule 1 INCONCLUSIVE at
n=3); `docs/HANDOVER.md` §7 "Step A".
**Session identity:** session 11, run on the committed tree at the pre-campaign session-11
commit (HEAD at campaign start; `manifest.txt` records `juring_git_commit` and
`juring_git_dirty=false`). Host: AMD Ryzen 9 3950X (16C/32T), 62 GiB, Linux 7.0.12-201,
data NVMe `/dev/nvme1n1p1` (ext4, `nvme-data/` bind mount), governor `performance`,
benchmark CPU pin `--cpu 2`, `HugePages_Total=0`, `ulimit -l` = 8 MiB.

---

## 1. Purpose and the decision this campaign buys

The read path of M1 is **PASS** (`docs/m1-report.md` §14). The write half is not assessed:
only a trimmed W7 block ran on 2026-11-13 (12 runs, R=3), where write rule 1 was
**INCONCLUSIVE** (paired ratio 0.9644, one-sided 95 % LB 0.9490). This campaign:

1. measures the full write matrix under the frozen methodology protocol — W5/W6/W7/W8
   (4K randwrite QD1/16/64/128) and W12/W13/W14 (1M seqwrite QD1/8/32) — so the write
   half is no longer a gap;
2. makes the M1 write **rule 1** decidable at the saturation cells **W7 (QD64)** and
   **W8 (QD128)** by running R=5 there (the previous block was R=3);
3. reports seastar's write path as a **bracket** (asymmetric engine configuration — never
   a win/loss), and fio's batching sweep at W7 (methodology W7 row) so batching is
   matched rather than assumed (F12);
4. produces the write-half evidence for `docs/m1-write-report.md` and the M1 gate table.

This design does **not** change any library code. It changes measurement tooling only
(`bench/write/`, `bench/m1/` analyzer) and is committed before the campaign.

## 2. Session layout and provenance

| item | value |
|---|---|
| session dir | `bench/results/m1-write-2026-11-15/` (gitignored evidence) |
| raw per-run artifacts | `cells/<case>__<engine>__<variant>__bs<bs>__qd<qd>__t1__r<rep>.json`, `logs/`, `series/` (D2 1 s TSV) |
| ledger | `state/order.tsv`, `state/runs.tsv`, `state/rests.tsv`, `state/resumed.tsv` (resume only), `state/precondition-results.txt`, `state/write-generations.tsv`, `cell-meta.tsv` |
| invocation records | `manifest.txt` (first invocation) + `manifest-run<N>.txt` (later blocks) + `state/invocations.tsv` |
| block logs | `logs/campaign-block1..3.log` |
| aggregate | `bench/results/m1-write-2026-11-15/aggregate/m1-write.*` from `bench/m1/analyze_m1.py` |

**Dataset.** One dedicated 10 GiB non-sparse `O_DIRECT` file
`nvme-data/write_files/single_file_bench_w.bin` (size = 10,737,418,240 B, fully
allocated, on `/dev/nvme1n1p1` ext4 rw, the same physical NVMe as the read dataset).
The read dataset (`/mnt/TestData/read_files/single_file_bench.bin`) is never opened for
writing; the harness records its `stat` before/after every invocation and refuses paths
outside `nvme-data/`. Data-state disclosure: the write file was last rewritten by the B6
benchmarks on 2026-11-15 (≈ 2 h before this campaign) and is **not** the aged read file;
D1 preconditioning (§5) establishes the write steady state, and every number is comparable
only within this session's data state.

**Quiet window.** Host load at session start was ~0.19. The campaign wrapper preflights
`loadavg(1m) <= 0.7` and aborts if a foreign benchmark process (`fio`,
`seastar_file_bench`, `JUringFileBench`) is running. Every run records loadavg at
start/end and the top CPU process. The benchmark runs pinned to CPU 2, one fresh engine
process per run.

## 3. Frozen run matrix (130 runs)

Warmup/measured windows are the methodology table (§2): W5/W12/W13/W14 = 60 s/60 s;
W6/W7/W8 = 120 s/60 s. One fresh process per run, `taskset -c 2`, seed 20261113.

| block | cells | workload | bs | qd | warmup/measured | reps | runs |
|---|---|---|---|---|---|---|---|
| B1 | W5 | randwrite | 4K | 1 | 60/60 s | 3 | 12 |
| B1 | W6 | randwrite | 4K | 16 | 120/60 s | 3 | 12 |
| B2 | W7 | randwrite | 4K | 64 | 120/60 s | **5** | 45 |
| B2 | W8 | randwrite | 4K | 128 | 120/60 s | **5** | 25 |
| B3 | W12 | seqwrite | 1M | 1 | 60/60 s | 3 | 12 |
| B3 | W13 | seqwrite | 1M | 8 | 60/60 s | 3 | 12 |
| B3 | W14 | seqwrite | 1M | 32 | 60/60 s | 3 | 12 |
| **total** | | | | | | | **130** |

Per-cell engine/variant specs (each rep runs every spec once; order rotated by rep):

| cells | specs | specs/cell × reps = runs |
|---|---|---|
| W5, W6, W12, W13, W14 | juring `std`; seastar `std`; fio `default`; fio `matched` | 4 × 3 = 12 |
| W7 | juring `std`; seastar `std`; fio `default`, `matched`, `matchedbatch`, `batch1`, `batch8`, `batch16`, `batch32` | 9 × 5 = 45 |
| W8 | juring `std`; seastar `std`; fio `default`, `matched`, `matchedbatch` | 5 × 5 = 25 |

**Rep choice.** Methodology §6 sets writes R=3 "due to cost"; W7/W8 use **R=5** because the
pre-existing W7 evidence is INCONCLUSIVE *only* because n=3 gives a t-based one-sided LB of
0.9490 (CI spanning 0.95) — the frozen accept criterion needs a decidable LB, and R=5 is
the smallest pre-registered increase that can decide it in either direction. W5/W6/W12–W14
keep the methodology R=3 (descriptive cells, no gate). This is deviation §11-D4.

## 4. Engine configurations (F0 — every engine's documented configuration, fully disclosed)

* **JUring** — `bench/juring/run-juring.sh` (`JURING_SKIP_COMPILE=1`, `taskset -c 2`, one
  thread, fresh JVM per run), O_DIRECT, the harness's normal fixed-file/fixed-buffer fast
  path, `--seconds 60`, `--warmup-sec` warmup, seed 20261113, JSON schema 1.0.
* **fio** — `--ioengine=io_uring --direct=1 --numjobs=1 --time_based --ramp_time=<warmup>
  --runtime=60 --randrepeat=1 --clocksource=clock_gettime --percentile_list=50:95:99:99.9`.
  Variants (all disclosed; no configuration is hidden):
  * `default` — no registration/batching flags (F0 default bracket);
  * `matched` — `--registerfiles=1 --fixedbufs=1` (read-gate comparator, `docs/m1-report.md`);
  * `matchedbatch` — `matched` + `--iodepth_batch_submit=<qd>` (batching-matched);
  * `batch1`, `batch8`, `batch16`, `batch32` — W7-only batching sweep
    (`--iodepth_batch_submit=1/8/16/32`, unregistered), methodology W7 row.
  `fio-libaio` (W7 row bracket) is **not** run — both compared engines use io_uring; this
  is pre-registered as deviation §11-D3.
* **seastar** — `bench/seastar/seastar_file_bench --smp 1 --cpuset 2 --memory 1G
  --lock-memory 0 --reactor-backend io_uring`, `LD_LIBRARY_PATH=.sysroot/root/usr/lib64`,
  pinned seastar `04b1c04d`. **Bracket only** (no ring flags, no registered files/fixed
  buffers, 200-entry ring, io_queue path — asymmetric by construction; methodology §3.5).
  Its write path is validated (second use; first validation was the 2026-11-13 smoke block).

**Gate comparator rule (pre-registered, no cherry-picking).** M1 write rule 1 is evaluated
at W7 and W8 against **both** registration-matched fio configurations — `matchedbatch`
(primary; batching matched to JUring's fused submit) and `matched` (sensitivity; the read
gate's comparator). **PASS requires the one-sided 95 % LB ≥ 0.95 against BOTH.** This
prevents selecting a slower-than-default fio baseline (the §2.4 credibility-audit failure
mode) and prevents a comparator change after seeing data. `default` and the batching sweep
are reported as informational brackets only.

## 5. Device-state controls (methodology §5, D1–D8; fairness F14)

* **D1 — preconditioning, per cell.** Before every measured cell: 4K randwrite QD64 on the
  write file until **3× file size = 32,212,254,720 B** are written, then `fsync`; record
  write generation, chunks, bytes, elapsed, device write-sector delta. **Chunk size 4 GiB
  and target-driven stop condition** (clarification I1 below). `--min-sec 120` is a time
  cap only when the byte target is *not* reached (capped burst = rc 1 → campaign aborts;
  the old `--condition and` reading is retained for reproducing the 2026-11-13 block).
  A **600 s idle rest** follows every preconditioning burst (D3).
  *I1 (pre-registered interpretation):* methodology D1 says "3× file size … or 120 s
  minimum". The 2026-11-13 block read that with AND semantics and wrote **128,849,018,880 B
  per burst (≈ 4× the D1 target; documented deviation toward deeper steady state)**. This
  design targets exactly the stated 3× file size: at the observed ~750 MiB/s the burst
  reaches 32 GiB in ≈ 45 s, and the 120 s clause remains a slow-drive time cap. The
  correction is required by Step A's first bullet ("fix the chunk size; target ~3× file
  size in <120 s") and is disclosed; it is the only change to D1 and it is frozen before
  measurement. The 4 GiB chunk granularity writes **34,359,738,368 B = 3.2× file size**
  (6.7 % above the 32,212,254,720 B target), a small disclosed overshoot toward deeper
  steady state; a final partial chunk is deliberately not added (it would change the fio
  invocation shape mid-burst).
* **D2 — SLC/write-cliff.** 1 s `/sys/block/nvme1n1/stat` series for every measured run
  (`sample-device-iops.sh`). Analysis: per-run sample count/mean/CV/min/max, write-cliff
  flag (>30 % step drop, or samples <70 % of the run mean), **burst vs steady-state split =
  first 10 s of the measured window vs the remainder** (definition frozen here), write
  amplification = device write-sector delta ×512 / engine measured bytes. Burst and steady
  state are never pooled in a claim. The sampler's `io_busy_pct` column is not used (known
  ~100× unit bug in older samplers; recorded as such).
* **D3 — rests.** 300 s idle between **rep groups** (methodology D3 wording: "300 s idle
  between W5–W8 reps"), 600 s after every preconditioning burst, and 300 s between campaign
  blocks. Every rest is recorded in `state/rests.tsv` with requested/actual seconds and
  UTC start/end. No rests inside a rep group between engines (same preconditioned
  generation/block — deviation §11-D5).
* **D4 — no discard/TRIM.** Mount options checked; the harness never issues
  `blkdiscard`/`TRIM`.
* **D5 — thermal.** hwmon temperature before/after every run (nvme `smart-log` needs root,
  unavailable); recorded in `state/runs.tsv`.
* **D6 — run order.** Cells monotonic W5→W6→W7→W8→W12→W13→W14; engine/variant order within
  a rep is rotated by rep by the driver; exact order written to `state/order.tsv`.
* **D7 — variance.** All reps are kept and reported; a cell with CV > 10 % (or the
  analyzer's mixed-state/spread flags) is reported "unstable, no ranking" and the existing
  gate policy (all reps primary; fast-state subset only if unstable, with excluded rep ids
  disclosed) applies. No rep is ever dropped silently.
* **F14 — host load.** Pre-run gate `loadavg(1m) > 1.6` aborts. Threshold disclosure: with
  rest-scope=reps the driver's own previous run leaves up to ≈ 1.0 of 1-min load, so the
  methodology default 0.5 is not usable; 1.6 ≈ one busy core + 0.6 margin for foreign
  load. `loadavg_start`/`loadavg_end`, top process and governor are recorded per run;
  analysis flags any run with `loadavg_start > 1.6` (deviation §11-D6).

## 6. Statistics and acceptance (fixed before measurement)

Statistics follow `docs/benchmark-methodology.md` §6: mean ± two-sided 95 % CI of the mean
(Student t, df = n−1); paired-by-rep ratios; 90 % two-sided CI = one-sided 95 % bounds;
CV%; every cited number has n ≥ 2 and a CI (rule 5).

**Write rule 1 (the only pass/fail gate in this campaign).** At W7 and W8, for the paired
per-rep ratio JUring/fio (matched with the same seed/CPU/process-per-run protocol):

| observed | verdict |
|---|---|
| one-sided 95 % LB ≥ 0.95 **against both** `matched` and `matchedbatch` | **PASS** |
| 95 % upper bound < 0.95 against **either** comparator (parity excluded) | **FAIL** |
| otherwise (CI spans 0.95 against at least one comparator) | **INCONCLUSIVE** |

Reported for every cell: n, mean, SD, CV, mean ± 95 % CI, ratio 90 %/95 % CI, one-sided LB,
half-width, slow/unstable flags, and both comparator rows. No "speedup" is claimed from
overlapping CIs. Cells with CV > 10 % are labelled "unstable, no ranking".

**Non-gated cells.** W5/W6/W12–W14 are descriptive (mean ± 95 % CI, CV, p99), no pass/fail;
the 1M cells are reported in MiB/s and IOPS. **Seastar** is a bracket everywhere (no
threshold), per methodology §3.5. **M1 overall verdict** for the write half is
`PASS` only if both W7 and W8 are PASS; `FAIL` if either FAILs; `INCONCLUSIVE` otherwise —
the same combine rule as the read path.

## 7. Analysis and deliverables

* Analyzer: `bench/m1/analyze_m1.py` (committed; stdlib only) extended for write sessions:
  mixed reps, both gate comparators, D2 series/cliff/write-amplification, W7 batching sweep,
  seastar bracket, µs-correct latencies (`cell_meta_p99_units=us` for the new session; the
  legacy 2026-11-13 block keeps its documented ns→µs normalization).
* Aggregate: `bench/results/m1-write-2026-11-15/aggregate/m1-write.*`; every table in
  `docs/m1-write-report.md` is regenerated from it (no hand-typed numbers; the report cites
  the generated artifact and the raw files).
* Deliverables: `docs/m1-write-report.md` (write-half report + gate verdict + deviations +
  threats to validity + reproduce commands); cross-links in `docs/m1-report.md` §13/§14;
  `SEASTAR-PLAN.md` v1.8; `docs/HANDOVER.md` session-11 section; `bench/write/README.md`
  status/results.
* Independent verification: a fresh verifier recomputes the gate from raw JSON, checks
  protocol adherence (counts/reps/rests/preconditioning/order/read-file stat), checks report
  fidelity, and writes `scratch/stepa-verify/VERDICT.md` (PASS / PASS-WITH-CONCERNS / FAIL)
  + an addendum after any corrections.

## 8. Campaign execution

Three blocks, sequentially, one session dir (resume-safe; a block failure can be re-run
with `--resume`, completed cells are skipped and every skip is recorded):

| block | driver invocation | expected runs |
|---|---|---|
| B1 | `--cells W5,W6 --reps 3` | 24 |
| B2 | `--cells W7,W8 --reps-by-cell W7=5,W8=5` | 45+25 = 70 |
| B3 | `--cells W12,W13,W14 --reps 3` | 36 |

Common flags: `--engines juring,fio,seastar --allow-seastar-write --rest-scope reps
--rest-sec 300 --precond-rest-sec 600 --precondition per-cell --loadavg-max 1.6
--loadavg-action abort --sampler --cpu 2 --seed 20261113`.

Command (wrapper enforces and records the 300 s between-block rests):
```bash
bench/write/run-write-campaign.sh --session-dir bench/results/m1-write-2026-11-15
```
Expected wall time (reviewed dry-run, `scratch/stepa-harness/campaign-dryrun.txt`):
**32,940 s ≈ 9 h 09 min**, including the two 300 s between-block rests and the conservative
per-cell precondition estimate. The real per-cell precondition burst is expected to be
~60 s (8 × 4 GiB at the measured ~198k IOPS QD64) rather than the dry-run's 150 s
estimate, while per-process startup adds a few seconds per run; plan for an **≈ 9–9.5 h**
uninterrupted run in the quiet window. A short pilot smoke in a separate throwaway session
dir validates the end-to-end path before the campaign; its data is never cited.

## 9. Out of scope

Read-path re-measurement; M2 stream/`fstream` cells; W21–W23; multi-shard/`--smp N` (M3 is
signed); fio-libaio; io_tester; host-root representativeness runs (Step D); publishing
README/BENCHMARK claim changes (Step E); CPU/`perf` gate for the write half (not part of
Step A acceptance; M1 CPU gate was the read QD64 cell).

## 10. Threats to validity (pre-registered)

1. Single machine/drive/kernel; no generalization.
2. Device state dominates writes (SLC/GC, burst→steady transitions); mitigated by D1/D2/D3/
   D7 and stated wherever a claim is made; absolute numbers comparable only within this
   session's data state.
3. fio configuration dominates fio numbers; both registration-matched comparators are
   required for PASS, default/sweep disclosed; no slower-baseline selection.
4. seastar is asymmetric by construction (no flags/registration) — bracket only.
5. Latency definitions differ (JUring/seastar before-submit→completion, fio `clat`); write
   latency is descriptive here (no write tail gate in Step A).
6. JVM effects (fresh JVM per rep, warmup); QD1 JVM tails are a known read-path artifact.
7. No hugepages (`HugePages_Total=0`) — THP path only, F0-disclosed.
8. The write file is 2 days old and was rewritten by B6 itself (not an aged dataset);
   preconditioning addresses steady state but this is disclosed with every absolute number.
9. `loadavg` F14 threshold adjusted for rest-scope; run-level loadavg is recorded so a
   foreign-load run can be identified and disclosed.
10. Multi-block execution: block boundaries have a recorded 300 s rest; a resumed block
    re-preconditions any partially-run cell (recorded generation) and skips complete cells.

## 11. Pre-registered deviations (all disclosed, none data-driven)

| # | deviation | why |
|---|---|---|
| D1 | preconditioning per cell (not "before a write session") + interpretation I1 (4 GiB chunks, target-driven stop) | driver default is per-cell (stricter); Step A bullet requires the chunk fix |
| D2 | `fio-libaio` bracket at W7 not run | both compared engines are io_uring; L0 comparison scope |
| D3 | gate comparators = matchedbatch **and** matched (both required for PASS) | prevents slower-baseline selection; read gate used matched |
| D4 | W7/W8 R=5 while methodology writes floor is R=3 | the open W7 LB cannot be decided at n=3 |
| D5 | rest scope = rep groups; no rest between engines within a rep | literal D3 wording; cost-bounded |
| D6 | F14 loadavg threshold 1.6 instead of 0.5 | own-run load decay under rest-scope=reps |
| D7 | seastar write path is a bracket; second validated use | asymmetric engine, methodology §3.5 |
| D8 | 60 s measured windows, 120 s warmup at W6/W7/W8 | methodology table §2 |
| D9 | no CPU/`perf` capture for writes | Step A acceptance is rule 1 only |
| D10 | write file not aged (2-day-old, B6-rewritten) | only dataset available; D1 mitigates; disclosed |
| D11 | campaign split into 3 resumable blocks with recorded between-block rests | practical session length; protocol identical per cell |
| D12 | no hugepages | F0-disclosed configuration |
| D13 | a *resumed* block can omit one 300 s rep-group rest when the resume lands on the last planned run of a group | rests are attached to planned run positions; a fully-present cell skips all its runs and rests; the normal uninterrupted campaign has no partial groups (disclosed if used) |

## 12. Freeze record and amendments

* **v1.0 frozen 2026-11-15 (UTC) before the pilot/campaign.** Files covered by the freeze:
  this document, `bench/write/precondition.sh`, `bench/write/run-write-cells.sh`,
  `bench/write/run-write-campaign.sh`, `bench/write/README.md`,
  `bench/m1/analyze_m1.py`, `bench/m1/normalize-write-session.py`. sha1s and the pre-campaign
  git commit are recorded in `scratch/stepa-lead/FREEZE.md`.
* **Amendment rules:** measurement data never triggers a protocol change. Post-freeze
  amendments are numbered (`A1`, `A2`, …), dated, reasoned, applied **before** the affected
  measurement when they change execution, and listed in the eventual report. Analysis-only
  clarifications that change no measured quantity are recorded as amendments too.
* **Execution parameters frozen above:** expected wall time **32,940 s ≈ 9.2 h**
  (reviewed dry-run, including the two 300 s between-block rests; real run expected
  ≈ 9–9.5 h), cells/reps/order, rests, preconditioning, comparators, and the rule-1
  verdict table.
