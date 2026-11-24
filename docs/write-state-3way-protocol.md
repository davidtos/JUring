# Pre-registered write measurement protocol — 3-way state-controlled rerun (2026-11-23)

Frozen before the first measured run. Written after the fio and Seastar audits, before any
write measurement. The executed command is [`bench/write/run-write-3way.sh`](../bench/write/run-write-3way.sh);
the pre-registered analysis is [`bench/write/analyze_write_3way.py`](../bench/write/analyze_write_3way.py).
(The 2026-11-23 session itself was driven by a byte-equivalent scratch copy of the runner,
`scratch/audit/run-write-rerun-3way.sh`, whose `PROTOCOL.txt` records the hashes; only the
session paths differ.)

## Why this protocol

`BENCHMARK.md` reports the write path as inconclusive. The frozen state-controlled chain
(`bench/stepd/run-write-chain.sh`, W2 gate) fixed the SLC problem by running the engines
back-to-back inside one freshly preconditioned generation, with no rests, and requiring a
generation to be homogeneous (`max/min IOPS <= 1.25` per cell) before it counts. The W1-A
"continuous" variant passed both pilots (`max/min` 1.0483 at QD 64, 1.0584 at QD 128) and
produced two valid generations; two of five attempts later failed because a `fio` arm entered
a deep GC state during its 120 s warmup.

This rerun uses that continuous variant, with two pre-registered changes:

1. **60 s warmup / 60 s measured** instead of 120/60. `bench/write/README.md` explicitly allows
   a uniform 60/60 override ("record the deviation in notes.md"). It shortens the interval in
   which a generation can drift into a different device state and is recorded in the session
   manifest.
2. **Seastar is a measured arm.** The Seastar write path (`bench/seastar/seastar_file_bench`)
   was marked UNTESTED in `bench/write/run-write-cells.sh`; the pre-run audit smoke-tested it
   (`status=ok`, `errors=0`, in-place write on the 10 GiB file, no truncate/fsync). The user
   needs a JUring/fio/**Seastar** write comparison for README.md, so the arm is added with
   `--allow-seastar-write`.

Everything else is the frozen W2 protocol. Nothing is trimmed, retried or tuned after seeing a
number.

## Fixed parameters

| Parameter | Value |
|---|---|
| Write file | `nvme-data/write_files/single_file_bench_w.bin` (10 GiB, non-sparse, ext4 on `/dev/nvme1n1p1`) |
| Read file | never touched by this session |
| Cells | W7 = 4 KiB random write QD 64; W8 = 4 KiB random write QD 128 |
| Arms | JUring (registered file + fixed buffers, taskrun flags), `fio` io_uring `matched` (`--registerfiles=1 --fixedbufs=1`), `fio` io_uring `matchedbatch` (`+ --iodepth_batch_submit=$qd`), Seastar `seastar_file_bench` (stock 200-entry ring, no registration — bracket) |
| Repetitions | 1 run per arm per cell per generation; up to 5 generations |
| Windows | 60 s warmup + 60 s measured, fresh process per run, `--seed 20261113` |
| CPU | all engines pinned to CPU 2 (`taskset -c 2`, Seastar `--cpuset 2`) |
| Preconditioning | one D1 burst per generation: 4 KiB random write QD 64 until >= 3x file size, fsync (`--precondition session-once`) |
| Rest | **none** after preconditioning (`--precond-rest-sec 0`), **none** between runs (`--rest-sec 0`), continuous; this is the frozen W1-A deviation |
| Engine order | rotated 3-cycle across generations: `juring fio seastar`, `seastar juring fio`, `fio seastar juring`, then repeat. A fixed order can bias the first/last arm when the drive drifts |
| Preconditioning per generation | yes — `session-once` applies one burst at the start of each generation's harness invocation, so every generation starts from the same rule |
| Stop rule | stop as soon as 4 generations pass the frozen gate; otherwise stop at 5 generations or the wall budget |
| Wall budget | 5600 s for generation starts, 1500 s per-generation cap; abort a generation on harness/loadavg failure |

Exact generation command:

```bash
timeout -k 30 1500 bash bench/write/run-write-cells.sh \
  --cells W7,W8 --engines "$ORDER" --allow-seastar-write \
  --fio-variants matched,matchedbatch --no-w7-sweep \
  --reps-by-cell W7=1,W8=1 \
  --precondition session-once --precond-rest-sec 0 \
  --rest-sec 0 --rest-scope runs \
  --warmup-sec 60 --measured-sec 60 \
  --seed 20261113 --cpu 2 \
  --session-dir "$S/g$g" \
  --loadavg-max 1.6 --loadavg-action abort
```

## Pre-registered validity gate

**G1 — frozen generation gate (unchanged).** `bench/stepd/analyze_write_state.py --gen-dir`
must print `PILOT_PASS 1` for the generation. That means: all six PLANNED runs
(W7/W8 x juring, fio `matched`, fio `matchedbatch`) are `status=ok`, each has a single-state
60 s measured window (no sustained >30 % step between 10 s medians), and within each cell the
three arms satisfy `max/min IOPS <= 1.25`. Extra Seastar runs are not part of this gate (the
frozen analyzer ignores them).

**G2 — Seastar/4-way state gate for the 3-way table.** A generation enters the 3-way table
only if G1 passes **and** the Seastar run is `status=ok` with `aggregate.errors_total == 0`
**and** all four arms are single-state by the same classifier **and** the four-arm within-cell
`max/min IOPS <= 1.25`. If a generation fails G2 but passes G1, the Seastar arm is disclosed as
state-mixed and that generation is left out of the 3-way table only; the frozen JUring/fio gate
still counts it.

**Minimum n.** Frozen verdicts need `n_valid >= 4` (pre-registered in the frozen analyzer;
fewer than 4 returns INCONCLUSIVE). The 3-way table needs at least 3 G2-valid generations to
quote means and paired ratios; with 1–2 it reports the per-run numbers and calls the write path
inconclusive rather than ranking it.

## Pre-registered claims

- Report every run. Means are `mean ± two-sided 95 % Student-t CI` over valid generations, with
  `n`, `CV%`, min and max.
- Paired ratios are computed within generation (the same device state), one ratio per generation.
  Parity at ±5 % means the two-sided **90 %** CI of JUring/comparator lies inside `[0.95, 1.05]`.
  "Faster by >=5 %" requires the one-sided 95 % lower bound `> 1.05`; "slower by >=5 %" requires
  the 90 % CI upper bound `< 0.95`; otherwise "no measurable difference at this power".
- No write ranking is claimed from a generation set with `n < 3` or with `CV > 10 %` on any arm.
- Seastar is a bracket, never an equal opponent: stock 200-entry ring, no
  `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN`, no registered file, no fixed buffers. The table
  must say so, and the fio `matched`/`matchedbatch` rows exist to show the reference is not
  artificially weak.
- The measurement is a steady-state, no-rest, post-SLC-burst comparison on one consumer NVMe.
  It is not a claim about fresh-drive or burst-mode write performance, and it is not a
  durability benchmark: all engines issue plain `O_DIRECT` writes without `fsync`.

## Analysis commands (run after the session)

```bash
python3 bench/stepd/analyze_write_state.py --session "$S" --gens "$GENS" --out "$S/analysis"
python3 bench/write/analyze_write_3way.py --session "$S" --gens "$GENS" --out "$S/analysis-3way"
```

## Expected duration

One generation: preconditioning burst (~40–70 s write + fsync + harness overhead) plus
8 measured runs x (60 s warmup + 60 s measured + ~8 s process/JSON overhead) ≈ 18–19 min.
Four valid generations ≈ 75 min. Five attempts if one is invalid ≈ 95 min.

## Run 2: second dedicated drive (nvme2n1, near-empty) — 2026-11-23

The first run above was on `nvme1n1p1`, which is 81% full (130 GiB free). The drive could not hold
a generation homogeneous, and the measured steady state was low and erratic. The host has a second
drive of the **same model and firmware** (`GIGABYTE GP-ASM2NE6100TTTD`, `/dev/nvme2n1`, ext4,
mounted at `/mnt/data`) that is 5% full (825 GiB free). That is the right device for this test for
two reasons: it is the same hardware, so the comparison is directly relevant, and its large free
area gives the SLC/GC logic room to work instead of thrashing.

Run 2 keeps the run-1 protocol and gate unchanged, with one pre-registered device-preparation step:

1. **One-time steady-state burn-in.** On a near-empty 1 TB TLC drive the dynamic SLC cache is
   large, so the 32 GiB generation-precondition burst is nowhere near enough to leave burst mode:
   the first runs of a generation would measure the cache, then cross the cliff mid-generation —
   the failure mode from run 1. Before generation 1 I write **400 GiB** (40x the 10 GiB file,
   4 GiB chunks, 4K random write QD 64, CPU 2, fsync at the end) using
   `bench/write/precondition.sh --target-mult 40 --min-sec 800 --max-sec 1200`. That is a
   device-state preparation step, not a measured cell; it is recorded in the session's
   `PROTOCOL.txt` and in the SMART log. Generations then use the frozen rule
   (`--precondition session-once`, 32 GiB, no rest, no inter-run rest, 60 s + 60 s).
2. Everything else is identical: cells W7/W8, arms JUring + `fio` matched/matchedbatch + Seastar,
   five attempts, stop at 4 valid, frozen `analyze_write_state.py` gate, per-generation order
   rotation, CPU 2, seed 20261113.
3. No read data lives on nvme2n1 and the read session is not re-run. The write rows will be
   labelled with their device in BENCHMARK.md.
4. SMART (`nvme smart-log /dev/nvme2n1`) is captured before the burn-in and after the campaign on
   the host; the harness records hwmon temperature around each run.

Commands on the host (root for the mount, then user `david` builds/runs):

```bash
# one-time setup, root
sudo mkdir -p /mnt/data/juring-write && sudo chown david:david /mnt/data/juring-write
mkdir -p /home/david/projects/JUring/nvme-write
sudo mount --bind /mnt/data/juring-write /home/david/projects/JUring/nvme-write
sudo chown david:david /home/david/projects/JUring/nvme-write
sudo nvme smart-log /dev/nvme2n1 | tee ~/smart-nvme2-before.txt

# as david, from the repo root
BENCH_WRITE_FILE=$PWD/nvme-write/write_files/single_file_bench_w.bin
bench/write/create-write-file.sh --file "$BENCH_WRITE_FILE" \
    --session-dir bench/results/write3way-nvme2 \
    --allow-any-path --allow-other-device
bench/write/precondition.sh --file "$BENCH_WRITE_FILE" \
    --device-stat /sys/block/nvme2n1/stat --target-mult 40 --min-sec 800 --max-sec 1200 \
    --session-dir bench/results/write3way-nvme2 --allow-any-path
ALLOW_ANY_PATH=1 \
WRITE_FILE=$BENCH_WRITE_FILE DEVICE_STAT=/sys/block/nvme2n1/stat \
    bash bench/write/run-write-3way.sh
sudo nvme smart-log /dev/nvme2n1 | tee ~/smart-nvme2-after.txt
```

Analysis is the same as run 1:

```bash
python3 bench/stepd/analyze_write_state.py --session "$S" --gens "$GENS" --out "$S/analysis"
python3 bench/write/analyze_write_3way.py --session "$S" --gens "$GENS" --out "$S/analysis-3way"
```

## Run 2 analysis contingency: position-controlled comparison (fixed while run 2 is executing)

The frozen gate assumes that, inside a homogeneous generation, the three JUring/`fio` arms fall
within `max/min <= 1.25`. That assumption is about **device state**; it does not allow for a real
engine difference larger than 25%. On the near-empty second drive the engines appear to separate by
more than that (generation 1, both cells: JUring about 468k, Seastar about 368k, `fio` about
321k IOPS), while each engine's value repeats across cells. If further generations support that,
the frozen gate will reject every generation for the wrong reason — the spread would be the
engines, not the device.

So, fixed now, before the run-2 campaign finishes:

1. **Primary result is still the frozen gate.** If it returns PASS/FAIL it is the claim; this
   contingency is not used.
2. If it returns INCONCLUSIVE **only because generations fail `max/min <= 1.25`**, the following
   position-controlled analysis may be reported as a **secondary, descriptive ranking**, with the
   gate failure disclosed next to it:
   - per cell and engine: mean ± two-sided 95% Student-t CI, CV, min, median, max across the five
     generations;
   - a position-stratified table: each engine's value when it ran first, middle and last in the
     generation (the 3-cycle order rotation makes that possible);
   - paired per-generation ratios JUring/`fio`-matched, JUring/`fio`-matchedbatch and
     JUring/Seastar, `n = 5`, with 90%/95% CIs.
3. The secondary ranking is allowed only if **all** of the following hold, checked before writing
   any table:
   - every engine's across-generation CV is below 10% in both cells;
   - each engine's position-stratified mean varies by less than 5% between first/middle/last
     (i.e. the spread follows the engine, not the slot);
   - the paired 90% CI excludes parity in the same direction for the comparator(s) named in the
     claim;
   - at least four of the five generations fail the frozen homogeneity bound (systematic, not a
     one-off slow arm).
   If any check fails, the write path stays unranked and only the per-run numbers are published.
4. Seastar remains a bracket. The `fio` matched/matchedbatch rows are reported so the reader can
   see the reference is not weakened.

This contingency is recorded in the session `PROTOCOL.txt` hash chain; the state-controlled gate
itself is unchanged. (Later, the controlled probe showed the "engine difference larger than 25%"
premise was wrong: the spread was the drive's fast/slow mode. The contingency was therefore not
applied; the steady-state comparison in `bench/write/analyze_write_steady.py` is the result.)

## Run 2 continuation: steady-state generations (fixed after generation 4, before g5 finished)

The run-2 campaign was capped at five generations for time. The drive was cache-assisted for
generations 1–3 (JUring 467k/194k, Seastar 368k/195k, fio 321k/198k depending on generation) and
only reached its steady state around generation 4 (`W7`: JUring 195.1k, fio 198.2k, Seastar 194.8k;
`max/min` 1.017). Generation 4 still failed the frozen gate because one `fio` matchedbatch run in W8
caught a cache-assisted burst (323.5k against 198.4k for `fio` matched; later shown by the mode
probe to be a sampling artefact of the drive's fast/slow cycle). The cap therefore leaves
fewer valid generations than the gate requires, for a reason unrelated to the engines.

So, fixed now: after the five-generation campaign completes, run **steady-state continuation
generations** with exactly the same cells, arms, windows, preconditioning, rests, order rotation and
seed, as `<session>/g6`, `g7`, `g8`. The target is four generations that pass the frozen gate over
the full attempt list (g1–g8); the gate and its threshold are unchanged. Stop early once four valid
generations exist. The continuation runs are included in the frozen gate analysis and in the 3-way
table; generations that fail the gate stay disclosed in the per-generation table and are never
trimmed from the record. Generations 1–3 are the cache-assisted transient and are expected to fail
the gate; they are kept as evidence of why the ranking only uses steady-state generations.

## Outcome (2026-11-23)

- Run 1 (main data NVMe, 81% full): two generations completed, both failed the frozen gate
  (within-cell `max/min` 1.77 and 2.61), the campaign was stopped, and the interleaved fallback was
  run as a descriptive bracket. No ranking.
- Run 2 (second NVMe of the same model, 6% used, 400 GiB burn-in): five generations, all 40 runs
  `status=ok`, **0 of 5 generations passed the frozen gate**. JUring and Seastar settled at
  ~192–195k IOPS; `fio` intermittently reported ~320k cache-assisted windows, which broke the
  within-generation `max/min <= 1.25` bound even when the other three arms were steady. The
  continuation generations (g6–g8) were not run because the gate stood at 0/5 — even three more
  homogeneous generations could not reach the pre-registered four, and the mode flips made the
  outcome a matter of cache-burst luck.
- The steady-state windows from run 2 are analysed descriptively by
  [`bench/write/analyze_write_steady.py`](../bench/write/analyze_write_steady.py) with the rule
  stated in its docstring (single-state and below 250k IOPS; the modes are <=199k and >=257k).
  Result: JUring 193.8k/192.3k, batch-1 `fio` 198.2k/198.6k, Seastar 195.1k/195.0k IOPS at
  QD 64/128, within ~3% of each other; the sustained state is parity at about 192k–199k IOPS. An
  early reading of the batching-matched `fio` QD 128 windows as a configuration advantage (323.5k
  over five generations) was **retracted**: a controlled probe with the same `fio` command and
  `--iodepth_batch_submit` from 1 to 128 flipped between ~220k and ~320k independently of batch
  size, fixed buffers and batch-complete, so the fast windows are the drive's write-cache/GC mode,
  not a configuration advantage. The frozen gate remains INCONCLUSIVE (0/5); the
  steady-state table is a descriptive result, not a gate verdict.
