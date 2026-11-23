# Interleaved write bracket — fallback protocol (fixed before the run, 2026-11-23)

## Why this exists

The primary state-controlled 3-way campaign (`bench/write/run-write-3way.sh`, protocol in
[write-state-3way-protocol.md](write-state-3way-protocol.md)) requires one freshly preconditioned
generation in which JUring and both `fio` references are single-state and within `max/min <= 1.25`
per cell. On 2026-11-23 that gate failed in both completed generations:

| generation | W7 (4 KiB randwrite QD 64) | W8 (QD 128) | frozen result |
|---|---|---|---|
| g1 | 171k JUring, 198k fio-matched, 302k fio-matchedbatch, 132k Seastar (`max/min` 1.77) | 130k / 180k / 69k / 71k (`max/min` 2.61) | `PILOT_PASS 0` |
| g2 | 104k / 141k / 178k / 132k (`max/min` 1.71) | 38k Seastar, then stopped | `PILOT_PASS 0` |

The drive was in a low, drifting steady state (roughly 70k–300k IOPS with multi-second GC cliffs),
so no generation was homogeneous. Rather than spend the remaining budget on more generations that
the gate was likely to reject, the campaign was stopped after two attempts and this fallback was
specified **before it ran**. It is a descriptive bracket, not a resurrection of the frozen gate.

## Design: measure all engines over the same drifting state

If the device state moves, the fix is to make every engine sample the same state distribution
instead of pretending one generation is a single state. This bracket alternates the three engines
at short, fixed windows:

| Parameter | Value |
|---|---|
| Cells | W7 = 4 KiB random write QD 64; W8 = 4 KiB random write QD 128 |
| Engines | JUring (registered file + fixed buffers, taskrun flags), `fio` io_uring `matched` (`--registerfiles=1 --fixedbufs=1`), Seastar `seastar_file_bench` (stock 200-ring, no registration; bracket) |
| Cycles (repetitions) | 5 per cell; one run per engine per cycle |
| Windows | 15 s warmup + 30 s measured, fresh process per run, `--seed 20261113` |
| Order | engine order rotated every cycle by the harness (cycle 1 J/F/S, cycle 2 S/J/F, cycle 3 F/S/J, repeat), so position bias is shared across engines |
| Preconditioning | **none**. The device is already in a sustained post-burst state from the primary campaign (~1 TB written in the preceding 30 min). Resetting it would just restart the burst-to-steady transient this bracket is designed to average over |
| Rest | none; continuous |
| CPU | all engines pinned to CPU 2 |
| File | `nvme-data/write_files/single_file_bench_w.bin` (10 GiB, ext4, `/dev/nvme1n1p1`); the read dataset is untouched |

Command:

```bash
bash bench/write/run-write-interleaved.sh
# which runs:
bash bench/write/run-write-cells.sh \
  --cells W7,W8 --engines "juring fio seastar" --allow-seastar-write \
  --fio-variants matched --no-w7-sweep --reps 5 \
  --warmup-sec 15 --measured-sec 30 \
  --precondition none --rest-sec 0 --rest-scope runs \
  --seed 20261113 --cpu 2 \
  --session-dir "$S" --loadavg-max 1.6 --loadavg-action abort
```

Expected wall time: 30 runs x ~47 s = ~24 min.

## Analysis and claim rules (fixed before the run)

- Report every run; never trim. Per cell/engine: `n`, mean ± two-sided 95% Student-t CI, CV%, min,
  median, max, p99, and the number of runs whose 1 s device series shows a sustained cliff.
- Paired ratios are computed per cycle (the engines in a cycle ran on the same device state):
  JUring/fio-matched, JUring/Seastar, fio-matched/Seastar. `n = 5` pairs.
- Parity at ±5%: the two-sided **90%** CI of the ratio lies inside `[0.95, 1.05]`. "Faster by >=5%"
  requires the one-sided 95% lower bound `> 1.05`; "slower by >=5%" requires the 90% CI upper bound
  `< 0.95`; otherwise "no measurable difference at this power".
- If an arm's CV > 20% or its 95% CI half-width > 15%, the table is still published but labelled
  *descriptive only*: the device moves too much to rank that cell.
- This bracket is **not** pooled with the primary campaign or with the 2026-11-13/16 write sessions,
  and it is not a frozen-gate PASS/FAIL. It exists so the README can show measured write numbers
  for all three engines, with the state caveat attached.

## Analysis commands

```bash
python3 bench/write/analyze_write_interleaved.py --session "$S" --out "$S/analysis"
```
