# bench/write/ — M1 write-path matrix harness (W5–W8, W12–W14)

Status: **harness complete for Step A (session 11), campaign not yet run.**
This directory is Step 2 of `docs/HANDOVER.md` §7 (write matrix) and implements
the normative controls D1–D8 of `docs/benchmark-methodology.md` §5 for cells
W5–W8 / W12–W14 (`docs/benchmark-methodology.md` §2). `run-write-campaign.sh`
is the one-command Step-A driver (three blocks, one session dir).

The read matrix uses the same NVMe (`/dev/nvme1n1p1`). Until the Lead says go,
**no script here may be run for real**: only `--dry-run`, `--help`, and
`bash -n` are safe while the read matrix owns the device. If in doubt, run the
equivalent command with `--dry-run` first and confirm it prints a plan instead
of touching the device.

## 1. Hard safety rules

1. **Never write `/mnt/TestData` or the read file**
   (`/mnt/TestData/read_files/single_file_bench.bin`). Writes go only to the
   dedicated write file on the writable bind mount
   `nvme-data/` → `/dev/nvme1n1p1` ext4 (the same physical NVMe, different file).
   The scripts refuse to start if the target resolves to the read dataset, is
   below `/mnt/TestData/read_files`, or is outside `<repo>/nvme-data`.
2. **Never pool read and write cells in the same session** (methodology §2
   phasing: writes are never interleaved with read cells).
3. **Never `blkdiscard`/TRIM** the shared partition (D4). The `discard` mount
   option is checked and reported; the harness never issues discard.
4. **Do not delete the write file while another engine is using it.** Only
   `create-write-file.sh --force` recreates it, and only after all checks pass.
5. **One writer at a time.** `run-write-cells.sh` takes an `flock` on
   `<session-dir>/.run-write-cells.lock`; `run-write-campaign.sh` additionally
   holds `<session-dir>/.run-write-campaign.lock` for all three blocks + rests.
   Both refuse to start if another `fio`/`seastar_file_bench`/`JUringFileBench`
   process is running (override: `--allow-concurrent`; not recommended).
6. The write file is non-sparse and preallocated; cells run **in place** on it.
   It is never truncated by the harness (no `--size` is passed that would extend
   it).

## 2. Files

| file | purpose |
|---|---|
| `create-write-file.sh` | create/verify the non-sparse 10 GiB O_DIRECT write file (`fallocate` → `dd oflag=direct` → fsync → stat/filefrag verify); idempotent, `--force` to recreate, `--dry-run` |
| `precondition.sh` | D1 preconditioning burst: 4K randwrite QD64 in **4 GiB chunks** until `bytes_written >= 3× file size` (`--condition target`, default; 32,212,254,720 B for the 10 GiB file), then fsync; `--condition and` reproduces the old target AND ≥120 s behaviour; records write generation, condition/stop_reason + pre/post device state; `--dry-run` |
| `run-write-cells.sh` | W5–W8 + W12–W14 matrix for juring/fio (+ seastar opt-in): warmup/measured per methodology, default R=3, `--reps-by-cell`, `--resume` (run-granular), D1/D2/D3/D6/RESULT + CELL_SUMMARY lines; `--dry-run` |
| `run-write-campaign.sh` | **Step-A driver**: blocks B1 `W5,W6` reps 3 → B2 `W7,W8` reps-by-cell W7=5/W8=5 → B3 `W12,W13,W14` reps 3, one session dir, verified 24/70/36 runs, enforced+recorded 300 s rest between blocks, preflight, `--resume`, `--dry-run` |
| `sample-device-iops.sh` | D2 1 s-resolution `/sys/block/nvme1n1/stat` time series to TSV; backgrounded by the matrix driver, can also run standalone; `--dry-run` |
| `README.md` | this file |

All five scripts: `bash -n` clean, `--help`, `--dry-run`, no `$HOME`
assumptions, `set -euo pipefail`, no root required for the harness itself.

## 3. Environmental assumptions (verified in this sandbox)

| item | value |
|---|---|
| writable data path | `<repo>/nvme-data` → `/dev/nvme1n1p1` ext4 (`rw,nosuid,nodev,relatime,seclabel`), ~151 GiB free |
| write file | `<repo>/nvme-data/write_files/single_file_bench_w.bin`, 10 GiB = 10,737,418,240 B, non-sparse |
| read file (never touched) | `/mnt/TestData/read_files/single_file_bench.bin` (10 GiB, `ro`) |
| device stat | `/sys/block/nvme1n1/stat` (17 fields, readable) |
| CPU pinning | `taskset -c 2` for every engine; seastar `--cpuset 2` |
| governor | `performance` (checked and recorded; abort only if you choose) |
| memlock | `ulimit -l` = 8 MiB → JUring 1M QD8/QD32 writes fall back to arena-unregistered buffers (recorded in the cell JSON `io_buffer_mode`) |
| seastar runtime | `LD_LIBRARY_PATH=<repo>/.sysroot/root/usr/lib64`, mandatory `--smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring` |
| thermal | no readable hwmon node → `nvme smart-log` needs root (D5); recorded as `unavailable(no-root)` otherwise |
| discard | not present in mount options (D4 recorded) |

## 4. Exact command sequence

Run from the repo root. **Step 0 (dry-run review)** does no I/O and is safe at
any time. Steps 1–4 require the Lead's go-ahead and an idle NVMe.

```bash
cd /home/david/projects/JUring

# ---- Step 0: static + dry-run validation (no I/O) ------------------------
bash -n bench/write/create-write-file.sh
bash -n bench/write/precondition.sh
bash -n bench/write/run-write-cells.sh
bash -n bench/write/run-write-campaign.sh
bash -n bench/write/sample-device-iops.sh
bench/write/create-write-file.sh --dry-run
bench/write/precondition.sh --dry-run
bench/write/run-write-cells.sh --dry-run
bench/write/run-write-campaign.sh --dry-run \
    --session-dir scratch/stepa-harness/dryrun-session
bench/write/sample-device-iops.sh --out /tmp/x.tsv --dry-run

# ---- Step 1: create/verify the 10 GiB write file (once) ------------------
bench/write/create-write-file.sh
# expect: CREATE_WRITE_FILE_RESULT action=created ... size_bytes=10737418240
#         allocated_bytes=10737418240 non_sparse=1 extents="..." fstype=ext4
# idempotent re-run prints action=skipped; --force recreates:
# bench/write/create-write-file.sh --force

# ---- Step 2: one D1 preconditioning burst (standalone check) -------------
# (~1.5-2.5 min with the target-driven 4 GiB chunks: 8 chunks = 32 GiB)
bench/write/precondition.sh
# expect: PRECONDITION_RESULT ... condition=target stop_reason=target-reached
#         chunks=8 bytes_written>=32212254720 fsync=ok
#         device_write_sectors_delta=... (capped=0)
# the burst stops as soon as the target is reached; --min-sec=120 only caps the
# burst (rc=1, stop_reason=min-sec-cap) if a slow device cannot reach it.
# after the burst the device must idle 600 s (D3) before the first cell;
# run-write-cells.sh does this itself when it owns the session.

# ---- Step 3: smoke subset first (one cell, one rep, real rests) ----------
bench/write/run-write-cells.sh --cells W5 --reps 1
# or a cheaper structural smoke (still real I/O, rests kept):
bench/write/run-write-cells.sh --cells W5 --reps 1 --rest-sec 60 --precond-rest-sec 300
# for a zero-I/O structural check of the same plan:
bench/write/run-write-cells.sh --cells W5 --reps 1 --dry-run

# ---- Step 4: the Step-A campaign (one command, ~9 h, 130 runs) -----------
# add seastar ONLY after its write path is validated by the Lead (done 2026-11-13):
bench/write/run-write-campaign.sh --session-dir bench/results/stepa-write-<date>
# continue an interrupted campaign (run-granular; skips present JSONs):
bench/write/run-write-campaign.sh --resume --session-dir bench/results/stepa-write-<date>
# the underlying driver remains available for custom/smoke selections:
bench/write/run-write-cells.sh --cells W5 --reps 1
bench/write/run-write-cells.sh --engines juring,fio,seastar --allow-seastar-write

# ---- Optional: standalone 1 s series during any manual run ---------------
bench/write/sample-device-iops.sh --out series/manual.tsv --label manual &
# ... run engine ...
kill -TERM $!      # sampler writes its final # end line and exits 0
```

`run-write-cells.sh` refuses to run on a missing/sparse/wrong-size write file,
<2 GiB free, a non-`nvme1n1p1` write filesystem (unless overridden), a
concurrent benchmark process, or a loadavg(1m) above `--loadavg-max 0.5`
(F14). Use `--loadavg-action warn` only when you will annotate the run.

## 5. Cells and protocol

| cell | workload | bs | QD | dataset | warmup / measured | R | engines |
|---|---|---|---|---|---|---|---|
| W5 | randwrite | 4K | 1 | write file | 60 / 60 s | 3 | juring, fio-io_uring, seastar (campaign) |
| W6 | randwrite | 4K | 16 | write file | 120 / 60 s | 3 | juring, fio-io_uring, seastar (campaign) |
| W7 | randwrite | 4K | 64 | write file | 120 / 60 s | **5** in the campaign | juring, seastar, fio-io_uring (+ registration-only `matched`, `matchedbatch`, batching sweep `batch1/8/16/32`) |
| W8 | randwrite | 4K | 128 | write file | 120 / 60 s | **5** in the campaign | juring, seastar, fio-io_uring (+ `matchedbatch`) |
| W12 | seqwrite | 1M | 1 | write file | 60 / 60 s | 3 | juring, fio-io_uring, seastar (campaign) |
| W13 | seqwrite | 1M | 8 | write file | 60 / 60 s | 3 | juring, fio-io_uring, seastar (campaign) |
| W14 | seqwrite | 1M | 32 | write file | 60 / 60 s | 3 | juring, fio-io_uring, seastar (campaign) |

The campaign raises W7/W8 to R=5 (`--reps-by-cell W7=5,W8=5`, HANDOVER §7
Step A) because the 2026-11-13 W7 block was **INCONCLUSIVE** at R=3 (paired LB
0.9490), and keeps R=3 for the other cells. The matrix table names only
fio-uring+juring for the write cells; seastar is the HANDOVER-mandated bracket
and its write cells are reported as a bracket, never as a pass/fail. fio-libaio
is available via `--fio-variants libaio` but is not in the Step-A campaign.

Warmups follow `docs/benchmark-methodology.md` §2 (the task brief summarised
"60/60"; W6–W8 specify 120 s warmup in the normative table). Override with
`--warmup-sec N` for a uniform 60/60 session if you prefer the brief's
simplification — record the deviation in `notes.md`.

Per-cell protocol (encoded by `run-write-cells.sh`):

1. **D1 preconditioning** before the cell (`--precondition=per-cell`, default):
   `precondition.sh` writes 4 GiB chunks of 4K randwrite QD64 until it reaches
   ≥3× file size (32,212,254,720 B), then fsyncs and records a write
   generation (`condition=target`, `stop_reason=target-reached`). Then a
   **600 s idle rest** (D3).
2. For every rep 1..R, run the selected engines **in rotated order** (rep 1:
   juring, fio-default, fio-matched; rep 2: fio-default, fio-matched, juring;
   …), each as a fresh process; the exact order is written to
   `state/order.tsv` (D6).
3. After every measured run a **300 s idle rest** (D3), recorded in
   `state/rests.tsv`. `--rest-scope reps` rests only between rep groups instead
   (cheaper; documented deviation).
4. Engine cells after W5 reuse the same preconditioned file; the next cell is
   preceded by a fresh preconditioning burst, so the order is monotonic
   W5→W8→W12→W14 (`--cells` may select a subset, order is preserved).

## 6. Engine invocations (exact)

All engines are pinned with `taskset -c 2`; JUring runs with
`JURING_SKIP_COMPILE=1` (the class is already built; the compile step is not part
of the measurement), one fresh JVM/ring/fd per run, `--runs 1`, fixed seed
`20261113`.

**JUring** (`bench/juring/run-juring.sh`; same harness as the read cells):

```bash
JURING_SKIP_COMPILE=1 taskset -c 2 bench/juring/run-juring.sh \
  --workload randwrite --file nvme-data/write_files/single_file_bench_w.bin \
  --bs 4k --qd 64 --threads 1 --seconds 60 --warmup-sec 120 \
  --runs 1 --seed 20261113 \
  --label W7__juring__std__bs4k__qd64__t1__r1 \
  --json bench/results/m1-2026-11-13-write/cells/W7__juring__std__bs4k__qd64__t1__r1.json
# 1M: --workload seqwrite --bs 1m
```

**fio** (neutral reference; io_uring, O_DIRECT, time-based):

```bash
taskset -c 2 fio --name=<stem> --filename=nvme-data/write_files/single_file_bench_w.bin \
  --ioengine=io_uring --direct=1 --rw=randwrite --bs=4k --iodepth=64 --numjobs=1 \
  --runtime=60 --ramp_time=120 --time_based --group_reporting=1 --randrepeat=1 \
  --clocksource=clock_gettime --percentile_list=50:95:99:99.9 \
  --clat_percentiles=1 --lat_percentiles=0 \
  --output-format=json \
  --output=bench/results/m1-2026-11-13-write/cells/<stem>.json
# 1M sequential: --rw=write --bs=1m --iodepth=1|8|32
```

fio variants:

| variant | extra flags | purpose |
|---|---|---|
| `default` | (none; fio's implicit `iodepth_batch_submit=1`) | the README/"default" configuration |
| `matched` | `--registerfiles=1 --fixedbufs=1` | matched registration, default batching = the read session's `matched` variant |
| `matchedbatch` | `--registerfiles=1 --fixedbufs=1 --iodepth_batch_submit=<qd>` | matched registration + fused submit = the M1-gate comparator; auto-added for W7/W8 |
| `batch1` | `--iodepth_batch_submit=1` | explicit one-SQE-per-enter point; auto-added for W7 so the sweep is `batch1, batch8, batch16, batch32, matchedbatch` |
| `batch8`, `batch16`, `batch32` | `--iodepth_batch_submit=8/16/32` | W7 batching sweep (F0/§3.2 mandatory, auto-added for W7) |
| `sqpoll` | `--sqthread_poll=1` | seastar asymmetry bracket (optional, not in default plan) |
| `libaio` | `--ioengine=libaio` | kernel-AIO reference (optional, W7 in the methodology) |

**seastar** (`bench/seastar/seastar_file_bench`; write path untested — opt in
with `--allow-seastar-write`):

```bash
LD_LIBRARY_PATH=$PWD/.sysroot/root/usr/lib64 taskset -c 2 bench/seastar/seastar_file_bench \
  --file nvme-data/write_files/single_file_bench_w.bin \
  --workload randwrite --bs 4k --qd 64 --seconds 60 --warmup-sec 120 \
  --seed 20261113 --label <stem> \
  --smp 1 --cpuset 2 --memory 1G --lock-memory 0 \
  --reactor-backend io_uring \
  --json bench/results/m1-2026-11-13-write/cells/<stem>.json
# 1M: --workload seqwrite --bs 1m
```

The runner opens the existing file `rw|create`, uses `file::dma_write` (no fixed
buffers/files — the documented seastar asymmetry), one fiber per outstanding
I/O, and emits the same schema as JUring. Validate it once manually
(`--seconds 3 --warmup-sec 1` on the write file) before enabling it in the
matrix; the 1M/QD32 cell is expected to be the slowest to validate.

## 7. Device-state controls (D1–D8) → implementation

| control | implementation |
|---|---|
| D1 preconditioning | `precondition.sh` before every cell: **4 GiB chunks** of 4K randwrite QD64 until ≥3× file size (32,212,254,720 B), then fsync (`--condition target`, default; `condition=`/`stop_reason=target-reached` in the result); `--min-sec` only caps a burst that cannot reach the target (rc=1, `stop_reason=min-sec-cap`); generation counter `state/write-generation.counter`, rows in `state/write-generations.tsv`, `PRECONDITION_RESULT` line with `condition=`/`stop_reason=` |
| D2 SLC cliff / 1 s series | `sample-device-iops.sh` per run → `series/<stem>.tsv`: read/write IOPS, MiB/s, in-flight, `io_busy_pct`, loadavg at 1 s; warmup+measured window (approximate ± process startup) |
| D3 rests | 300 s between measured runs, 600 s after every preconditioning burst; actual durations in `state/rests.tsv` |
| D4 TRIM/discard | mount options recorded in `manifest.txt`; warns if `discard` present; no discard command anywhere |
| D5 thermal | `thermal_state()` before/after every run: hwmon °C if readable, else `nvme smart-log` (root), else `unavailable(no-root)`; host-root step below |
| D6 order | monotonic cells W5→W8→W12→W14; engines rotated per rep; full order in `state/order.tsv`; no A/B interleaving across preconditioning boundaries |
| D7 variance | every rep kept; per-run `RESULT` lines and per-cell `CELL_SUMMARY` (n, mean, sd, CV%, `unstable=1` when CV>10%); no ranking of unstable cells |
| D8 do-not-compare | fixed bs/QD/direct/pinning per cell; burst vs steady-state separated by the D2 series; fio default vs matched never mixed in one judgement; cells marked with their variant |

Additional gates recorded per run: loadavg before/after, top CPU process,
governor, thermal, `/sys/block/nvme1n1/stat` deltas (device write sectors must
move; a >10% mismatch against the engine-reported bytes warns per methodology
§6).

## 8. Artifacts and naming

Session root: `bench/results/m1-2026-11-13-write/` (override `--session-dir`).

```text
cells/<case>__<engine>__<variant>__bs<bs>__qd<qd>__t1__r<rep>.json
logs/<same stem>.log
series/<same stem>.tsv
cell-meta.tsv                      # 17-column meta, same format as the read session;
                                   # p99_us is MICROSECONDS for every engine
state/order.tsv, runs.tsv, rests.tsv, cell-summaries.txt,
state/resumed.tsv                  # --resume skip/reprecondition log (utc, cell, stem, rep, action)
state/invocations.tsv              # one row per invocation (utc, manifest, argv, run counts)
state/write-file.tsv, write-generations.tsv, precondition-results.txt
manifest.txt                       # first invocation
manifest-run<N>.txt                # invocation N>1 (manifest.txt is never truncated)
logs/campaign-block<N>.log         # campaign wrapper block logs
```

A campaign session mixes all three blocks in one dir; `state/order.tsv` seq
numbers stay monotonic across blocks **and** across later `--resume`
invocations (the seq offset is the max seq already present).

Examples (same convention as the read session, e.g.
`W3__juring__std__bs4k__qd64__t1__r2.json`):

```text
cells/W5__juring__std__bs4k__qd1__t1__r1.json
cells/W5__fio-io_uring__default__bs4k__qd1__t1__r1.json
cells/W5__fio-io_uring__matched__bs4k__qd1__t1__r1.json
cells/W7__fio-io_uring__batch32__bs4k__qd64__t1__r3.json
cells/W13__juring__std__bs1m__qd8__t1__r2.json
cells/W13__seastar__std__bs1m__qd8__t1__r2.json          # only if enabled
```

`<case>` is the methodology ID (`W5`…`W14`), `<engine>` is `juring`,
`fio-io_uring`, `fio-libaio` or `seastar`, `<variant>` is `std` (JUring,
seastar), `default`/`matched`/`b8`/`b16`/`b32`/`sqpoll` (fio).

Cell JSON is the engine-native JSON (JUring/seastar schema 1.0, or fio JSON) —
the same data `bench/parse_results.py` and the read-session analyzer consume.
The external `series/*.tsv` is the only 1 s time series; engines do not emit one.

`RESULT` line per measured run (stdout + log):

```text
RESULT seq=.. cell=W5 engine=juring variant=std bs=4k qd=1 rep=1 status=ok exit=0
  iops=.. mibs=.. p99_us=.. bytes=.. errors=.. buffer_mode=registered-fixed
  dev_write_mib=.. elapsed_s=.. json=.. log=.. series=..
```

`CELL_SUMMARY` line per (cell, engine, variant):

```text
CELL_SUMMARY case=W5 engine=fio-io_uring variant=matched bs=4k qd=1 n=3 ok=3
  iops_mean=.. iops_sd=.. cv_pct=.. mibs_mean=.. p99_mean_us=.. unstable=0
```

Final line: `SESSION_RESULT session_dir=.. runs_planned=.. runs_ok=..
runs_failed=.. elapsed_s=..` (exit 1 if any run failed).

## 9. Decisions and deviations (review checklist)

0. **Step-A precondition condition (2026-11-15).** `precondition.sh` now
   defaults to `--condition target`: 4 GiB chunks until `bytes_written >= 3×
   file size`, stopping immediately (≈8 chunks / 32–34 GiB) instead of the old
   `target AND >=120 s` rule that wrote **128.8 GiB** in the 2026-11-13 block.
   `--condition and` reproduces the old rule (add `--chunk-bytes 10g` to
   reproduce that block exactly). This is a deliberate deviation from the
   literal "3× file size or 120 s minimum" wording of methodology D1; the
   overshoot was the documented Step-A problem, the HANDOVER fix is
   target-driven chunks. `--min-sec` now only caps a burst that cannot reach
   the target (rc=1, `stop_reason=min-sec-cap`).
0b. **W7/W8 R=5, batch1, seastar writes.** The campaign raises W7/W8 to 5 reps
   (`--reps-by-cell W7=5,W8=5`) to decide the 2026-11-13 INCONCLUSIVE W7 result;
   the W7 fio batching sweep now starts at `batch1` (explicit) as well as
   `batch8/16/32/matchedbatch`; seastar write cells are included for all blocks
   per HANDOVER §7 Step A (bracket, not a gate). fio-libaio is not in the
   campaign (optional `--fio-variants libaio`).
0c. **p99 units defect fixed (2026-11-13 write-session defect).** fio
   `clat_ns` percentiles are divided by 1000 before writing `RESULT` /
   `runs.tsv` / `cell-meta.tsv`, so `p99_us` is microseconds for **all**
   engines; the manifest records `cell_meta_p99_units=us`. Sessions written
   before this fix keep their ns values and remain non-citable for fio p99.
0d. **Multi-invocation sessions.** Seq numbers are offset by the max seq in
   `state/order.tsv`/`state/runs.tsv`; `manifest.txt` is only written by the
   first invocation, later ones write `manifest-run<N>.txt`; every invocation
   appends a row to `state/invocations.tsv`.

1. **Warmups**: methodology says 120 s warmup for W6–W8, 60 s otherwise; the
   task brief said "60/60". Default follows the methodology; `--warmup-sec`
   gives a uniform override.
2. **Rest scope**: default 300 s after *every* measured run (strict D3 reading,
   ~10 h session). `--rest-scope reps` keeps the rests only between rep groups
   (~5 h) — cheaper but the engines inside a rep then run back-to-back.
3. **Preconditioning granularity**: default per cell (`--precondition=per-cell`,
   7 bursts). `session-once` costs less but a long session drifts away from the
   pre-write state; `none` is for smoke runs only.
4. **fio batching**: `matched` = registration only (read-session-compatible);
   `matchedbatch` = registration + `iodepth_batch_submit=<qd>` (JUring fills to
   QD and submits all pending SQEs in one fused enter, so batch=qd is the
   closest match) — this is the M1-gate comparator and is auto-added for the
   saturation cells W7/W8. The W7 `batch8/batch16/batch32` sweep is also
   included by default so the batching assumption is measured, not assumed.
5. **fio sequential write** is `--rw=write` (fio's name for sequential write);
   `overwrite` semantics are equivalent here because the 10 GiB file already
   exists and fio wraps with `--time_based`.
6. **fio flags match the read session** (`bench/m1/lib.sh`):
   `--randrepeat=1`, `--clocksource=clock_gettime`,
   `--percentile_list=50:95:99:99.9`, `--clat_percentiles=1`,
   `--lat_percentiles=0`, `--group_reporting=1`. Variant names are the same as
   the read session (`std`, `matched`, `default`, `batch8/16/32`, `sqpoll`), so
   `matched` here is registration-only; `matchedbatch` adds
   `--iodepth_batch_submit=<qd>` and is auto-added for W7/W8.
7. **Latency is not cross-engine comparable**: JUring timestamps before SQE
   preparation, seastar before `dma_write`, fio reports `clat`. Compare
   throughput; report tails per engine (same convention as the read baseline).
8. **seastar write path is unvalidated** (`docs/HANDOVER.md` §6.5); it must be
   smoke-tested by the Lead on the write file before `--allow-seastar-write`.
9. **JUring JVM flags** are the same as the read session (none beyond
   `--enable-native-access=ALL-UNNAMED`, no `-Xms/-Xmx`): the write cells hold
   the engine constant with the read matrix. Methodology F8 suggests
   `-Xms8g -Xmx8g -XX:+AlwaysPreTouch`; changing JVM flags for writes only would
   break comparability with the read cells. Record if this is revisited.
10. **Sampler alignment** is approximate: the series covers the whole engine
    process (JVM/fio startup + warmup + measured), because neither engine emits
    a time series and its warmup→measured transition is not externally visible.
    The measured window is the last `measured_sec` before the process exits;
    cliff/steady-state classification uses the shape, not exact alignment.
11. **Free-space / 10% gate**: the write run does not grow the file, so a
    ≥2 GiB free margin is just an ENOSPC guard; the device-sector-vs-engine
    bytes check is a warning (it includes warmup, so it is not an equality).

## 10. Wall-time estimate

Step-A campaign plan (juring + fio + seastar, W7 sweep incl. `batch1`):

| block | cells / reps | runs | estimate |
|---|---|---|---|
| B1 | `W5,W6` reps 3 (`--rest-scope reps`) | 24 | ≈ 6,552 s ≈ 1.8 h |
| B2 | `W7,W8` reps-by-cell 5/5 | 70 | ≈ 16,890 s ≈ 4.7 h |
| B3 | `W12–W14` reps 3 | 36 | ≈ 8,898 s ≈ 2.5 h |
| between-block rests | 2 × 300 s enforced + recorded | — | ≈ 600 s |
| **campaign total** | juring + fio + seastar, 7 precondition bursts | **130** | **≈ 32,940 s ≈ 9.2 h** |
| smoke `--cells W5 --reps 1` (juring+fio) | 3 runs, 1 precondition, 2 run rests + precond rest | 3 | ≈ 28 min |

For reference, the previous `run-write-cells.sh` default plan (78 runs, R=3,
`default,matched` only, no seastar) was estimated at ≈11 h; the campaign is
longer because W7/W8 now have R=5, `batch1` and seastar.

The estimate uses run = warmup + measured + 3 s startup, a **90 s
target-driven precondition + 600 s rest** per burst, and 300 s between rep
groups. Use `--dry-run` on either script to print the exact estimate for any
selection; the campaign wrapper prints per-block estimates and the total.

## 11. What still needs host root

| item | why | status here |
|---|---|---|
| `nvme smart-log /dev/nvme1n1` (D5) | drive temperature + throttle counters | attempted per run; recorded `unavailable(no-root)` without sudo. Run `sudo nvme smart-log /dev/nvme1n1` before/after cells on the host if thermal gating matters |
| `drop_caches` (F11) | cold page-cache / metadata state | **not needed**: all write cells are O_DIRECT and the file is preallocated/written once; if a cold start is required, the host must `sudo sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches'` before the session |
| governor (F7) | `performance` for all CPUs | already `performance`; changing it needs root |
| raising `RLIMIT_MEMLOCK` (F0) | JUring fixed buffers for 1M QD8/QD32 writes | 8 MiB here → JUring falls back to unregistered arena buffers (recorded in JSON). Host/root can raise it, but then the write cells would not match the read cells' buffer mode — flag the deviation |
| hugepages (F10) | seastar best config | no hugetlbfs in the sandbox; mandatory `--lock-memory 0`/THP path is recorded. Not available without host root |
| writable bind mount | `nvme-data` is a host-side bind of `/mnt/TestData/juring-bench` | already set up; if it is missing or ro, the host must redo `docs/HANDOVER.md` §7 Step 0 |
| discard | D4 | **no action**: mount has no `discard`; do not enable it |

## 12. Acceptance procedure

A write session is citable only if all of these hold (methodology §7–§8):

1. **Static**: `bash -n` clean for all five scripts; every `--dry-run` prints a
   plan and does not touch the device, and the campaign dry-run plans exactly
   24/70/36 runs (130 total).
2. **Dataset**: `CREATE_WRITE_FILE_RESULT` (or `action=skipped`) with
   `size_bytes=10737418240`, `allocated_bytes>=size*0.99`, `fstype=ext4`,
   source `/dev/nvme1n1p1`; read file stat unchanged across the session
   (`read_file_unchanged=true` in `manifest.txt`).
3. **Preconditioning**: each `PRECONDITION_RESULT` has
   `condition=target`, `stop_reason=target-reached`,
   `bytes_written >= 32212254720`, `fsync=ok`,
   `device_write_sectors_delta > 0`; the generation number is recorded.
   A `stop_reason=min-sec-cap`/`max-sec-cap` result is a D1 shortfall: abort
   (the driver does unless `--allow-capped-precondition` is given).
4. **Per run**: `RESULT ... status=ok`, `errors=0`, engine bytes > 0, device
   write sectors moved, `series/<stem>.tsv` has ≈ warmup+measured rows at 1 s.
5. **Per cell**: `CELL_SUMMARY n=3`, all `ok=3`, mean/sd/CV reported. A cell
   with `CV > 10 %` is **unstable, no ranking** (D7) and needs a cause note
   (cliff via the series, thermal via D5, loadavg via the run log).
6. **Session**: `SESSION_RESULT runs_failed=0`; the manifest of the invocation
   (`manifest.txt`, then `manifest-run<N>.txt`) is complete (governor, loadavg,
   device deltas, exact argv); every JSON has its cell-named file and every log
   its exact command line; `cell_meta_p99_units=us` is present and the
   `cell-meta.tsv` p99 values are microseconds (no fio ns values).
   For a campaign: `CAMPAIGN_RESULT status=ok` with `runs_planned=130`,
   `blocks_ok=3`, and two `phase=between-blocks` rows in `state/rests.tsv`
   (`after_run_seq` = last seq of the finished block, requested_s=300).
7. **Analysis**: use the same statistics as the read path (`docs/benchmark-
   methodology.md` §7: mean ± 95% CI, min/max/median, CV, paired ratios against
   fio-matched; no delta claim whose CI contains 0; parity only inside
   [0.95, 1.05]). Hand the session directory to the M1 analyzer; the write
   version of the report must keep W5–W8 and W12–W14 separate from the read
   cells and must state the burst/steady-state split from the D2 series.

## 13. Cleanup / re-runs

* Re-running cells appends new `RESULT`/`CELL_SUMMARY` lines; cell JSONs are
  overwritten per stem, so **copy a completed session before re-running it**
  (or use a new `--session-dir`, e.g.
  `--session-dir bench/results/m1-2026-11-13-write-r2`).
* To continue an interrupted session use `--resume` (run-granular): a planned
  run whose `cells/<stem>.json` parses with `iops>0` is skipped, a cell with
  missing runs is preconditioned once more, a fully present cell skips
  cell+preconditioning; skips land in `state/resumed.tsv`, and the manifest is
  preserved as `manifest-run<N>.txt`. The campaign wrapper forwards `--resume`
  to all three blocks.
* To recreate the write file from scratch:
  `bench/write/create-write-file.sh --force` (10 GiB write, ~20 s; run only
  when the device is idle).
* To free the space: `rm nvme-data/write_files/single_file_bench_w.bin` (only
  after a session is archived; this is a plain unlink, no TRIM).
* The `flock` file `<session-dir>/.run-write-cells.lock` is harmless and can
  stay.
