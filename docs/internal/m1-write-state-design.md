# M1 write half — state-controlled write protocol (FROZEN 2026-11-15, v1.0)

**Status: FROZEN before measurement** (session 12, `scratch/stepd-write/FREEZE.md`).
Amendments recorded after the campaign only, with reason/timestamp. This protocol is the
pre-registered follow-up to Step A (`docs/m1-write-design.md`), whose rule 1 was
`INCONCLUSIVE` because 36/130 runs contained a device write-state cliff (burst→steady)
and 21/34 cells were unstable (D7) — adding reps did not remove the burst/steady mixing.

## 1. Question and hypothesis

Can the write rule-1 comparison be decided on this drive by **measuring only
single-state, same-state generations**?

Hypothesis to test: after one D1 preconditioning burst (32,212,254,720 B = 3× file size,
4 KiB randwrite QD64), a generation is **either** entirely in the SLC-burst state **or**
entirely in the post-cliff steady state; if all engines are run back-to-back inside one
such generation, their ratios are comparable. Step A's evidence (cliffs inside 60 s
windows despite 600 s rests) suggests the rest itself may re-create burst capacity, so the
pilot tests that directly.

## 2. Protocol

Shared for every generation (one `bench/write/run-write-cells.sh` invocation):

| item | frozen value |
|---|---|
| write dataset | `nvme-data/write_files/single_file_bench_w.bin`, 10 GiB, non-sparse (read dataset never opened for writing) |
| preconditioning (D1) | `--precondition session-once` → one burst before the first cell, `--precond-rest-sec` per variant |
| cells / arms | W7 (4K randwrite QD64) and W8 (QD128); arms = juring std, fio `matched` (`--registerfiles=1 --fixedbufs=1`), fio `matchedbatch` (+`--iodepth_batch_submit=qd`) |
| reps | 1 per cell per generation (the generation **is** the repetition; gate reps = generations) |
| windows | 120 s warmup + 60 s measured per run, one fresh process per run |
| sampler | D2 1 s `/sys/block/nvme1n1/stat` series for every run (`sample-device-iops.sh`) |
| CPU/user | `taskset -c 2`, user `david`, JDK 26.0.2 explicit |
| F14 | abort a run if loadavg(1m) ≥ 1.6 |
| seastar | **not** run (rule 1 consumes only the two fio comparators; seastar writes are unvalidated) |

### Generation variants (pre-registered order)

| variant | preconditioning rest | inter-run idle | purpose |
|---|---|---|---|
| **W0 control** | 600 s after the burst | 0 s | Step-A-like rest; tests whether rest re-creates burst state |
| **W1-A continuous** | 0 s | 0 s | never idle inside a generation |
| **W1-B brief-idle** | 0 s | 120 s between runs | tests whether brief idle re-creates burst state |

## 3. Frozen classifier (`bench/stepd/write_state_analysis.py` (sha256 `4d507bc46a813f2e5428eb46d508e3eb65a8067b17ea9a045fb29ab0fa6d0fec`))

For each run, from its D2 1 s **write_iops** series:

- measured window = the **last min(60, n)** samples (measured_sec = 60; warmup precedes);
- **sustained cliff** = exists a split inside the measured window where the median of the
  following 10 samples < **0.70 ×** the median of the preceding 10 samples;
- **single-state** = no sustained cliff and ≥30 measured samples (CV/in-band are reported
  as disclosure, not gates: 1 s device IOPS is noisy even in one state).

A **generation is valid** iff:
1. all six planned runs are `ok` and single-state, and
2. for each of W7 and W8, the three arms' measured IOPS satisfy **max/min ≤ 1.25**
   (no burst-vs-steady mixing inside the generation).

Validation: on the executed Step A campaign the classifier marks 108/130 runs
single-state and 22/130 sustained-cliff (the report's cliff detector flagged 36/130; the
difference is the different detector and is disclosed).

## 4. Adaptivity (pre-registered, no post-hoc choices)

1. Run **W0**. If valid → gate campaign with the **control** generation definition.
2. Else run **W1-A**. If valid → gate campaign with **continuous**.
3. Else run **W1-B**. If valid → gate campaign with **brief-idle**.
4. Else the chain stops: verdict **UNRESOLVED** — the drive does not admit valid
   state-controlled generations under any pre-registered variant; write rule 1 stays
   `INCONCLUSIVE` for v1 *with a documented cause*.

No parameter may be tuned after seeing a generation's IOPS; only the fixed sequence above
is followed.

## 5. W2 gate rule (frozen)

5 generations with the first passing variant.

- **Valid generation** as in §3 (invalid generations are excluded, count disclosed).
- Per valid generation, ratios: `JUring/fio-matched` and `JUring/fio-matchedbatch` at
  W7 and at W8.
- One-sided 95 % lower bound (Student t, df=n−1) of the mean ratio per (cell, comparator).
- **PASS** iff all four LBs ≥ **0.95**; **FAIL** iff any two-sided 95 % upper bound < 0.95;
  otherwise **INCONCLUSIVE**.
- Gate is evaluated only when **n_valid ≥ 4**; with fewer, verdict is INCONCLUSIVE
  ("insufficient valid generations"), never a partial gate.

This mirrors the Step-A both-comparator rule exactly, with the population changed from
all reps to valid generations.

## 6. Time budget and guards (unattended)

- Step D: ~1.2 h (separate frozen design `docs/stepd-design.md`).
- W0 ≈ 30 min; W1-A ≈ 20 min; W1-B ≈ 30 min; W2 ≈ 2.5 h (5 × ~30 min).
- Worst case ≈ 5.0 h inside a 6 h chain window; the orchestrator skips W2 if
  `remaining < 5×(1080+PR+5·RS)+300 s`, stops W2 before a generation if <15 min remain,
  and caps each generation at 60 min and the chain at 5 h (timeout).
- The chain never runs two writers concurrently (harness flock + preflight).

## 7. Deliverables and disclosure

- `bench/results/unattended-<date>/write-state/` — generations, raw JSON/series,
  `WRITE-STATE-VERDICT.md`, `analysis/write-state-summary.md` + `.csv` + `.json`.
- Post-session: `docs/m1-write-report.md` addendum (or a new report) stating the
  variant chosen, valid/invalid generations (all disclosed), gate verdict, and the
  Step-A comparison; plan/handover update; independent verification.
- **No claim beyond the gate.** A PASS licenses "write rule 1 PASS on
  state-controlled valid generations"; INCONCLUSIVE/FAIL are reported as such. The
  Step-A INCONCLUSIVE stays on the record unless the gate PASSes, and even then the
  difference in protocol is disclosed.

## 8. Freeze identity

- design sha1: recorded in `scratch/stepd-write/FREEZE.md`
- `bench/stepd/run-write-chain.sh` (sha256 `146cf8727453203f9f7d8d20d5c5094ff62b17801ca3f725331ac94e4d746f5c`), `bench/stepd/write_state_analysis.py` (sha256 `4d507bc46a813f2e5428eb46d508e3eb65a8067b17ea9a045fb29ab0fa6d0fec`),
  `bench/stepd/run-unattended.sh` (sha256 `704955f6a0b993a240bee93ccce476716a3fd09ec24c12d5fffd70afa6820047`): sha256 in the freeze record.
