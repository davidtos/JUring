# Write-path matrix (bench/write/)

The write path is the honest failure in this project. This is the harness I used: a matrix of 4 KiB random writes and 1 MiB sequential writes, with device preconditioning before every configuration and a 1 s device IOPS series during the run, so a run that crosses the SLC cliff is visible. The result is in [`BENCHMARK.md`](../../BENCHMARK.md#the-write-path-i-could-not-measure-fairly): no write-parity ranking, because the drive's state moves under the run. The follow-up state-controlled chain is in [`bench/stepd/`](../stepd/README.md) (`run-write-chain.sh` and `analyze_write_state.py`), and it also came out inconclusive.

Everything writes to `nvme-data/write_files/single_file_bench_w.bin`; `/mnt/TestData` stays read-only. The protocol is in [`docs/benchmark-methodology.md`](../../docs/benchmark-methodology.md), and the decision to stop at inconclusive is in [`docs/design-notes.md`](../../docs/design-notes.md).

A real matrix takes the NVMe for hours. Start with `--dry-run`, confirm it prints a plan instead of touching the device, and check that no other benchmark process is running; the preflight does that.

## Safety rules

1. Never write `/mnt/TestData` or the read file `/mnt/TestData/read_files/single_file_bench.bin`. Writes go only to the dedicated write file on `nvme-data/`, the writable bind mount of `/dev/nvme1n1p1` ext4 (`rw,nosuid,nodev,relatime,seclabel`), with about 151 GiB free. The scripts refuse if the target resolves to the read dataset or outside `<repo>/nvme-data`.
2. Never pool read and write runs in one session.
3. Never `blkdiscard` or TRIM the shared partition. The mount option is checked and reported; the harness issues no discard.
4. Do not delete the write file while another engine is using it. Only `create-write-file.sh --force` recreates it, after the checks.
5. One writer at a time. `run-write-cells.sh` takes `<session-dir>/.run-write-cells.lock`; `run-write-campaign.sh` holds `<session-dir>/.run-write-campaign.lock` for all blocks and rests. Both refuse if another `fio`, `seastar_file_bench` or `JUringFileBench` process runs. `--allow-concurrent` overrides that, and I don't recommend it.
6. The write file is non-sparse and preallocated. Configurations run in place, and the harness never truncates it.

## Files

| file | purpose |
|---|---|
| `run-write-3way.sh` | pre-registered state-controlled 3-way rerun (JUring + fio matched/matchedbatch + Seastar); stops at 4 valid generations |
| `analyze_write_3way.py` | 3-way table and frozen-gate companion for `run-write-3way.sh` |
| `run-write-interleaved.sh` | interleaved fallback bracket when a generation will not hold homogeneous |
| `analyze_write_interleaved.py` | descriptive per-cycle table for the interleaved bracket |
| `analyze_write_steady.py` | separates sustained vs cache-assisted windows and reports the sustained-state comparison |
| `create-write-file.sh` | create or verify the non-sparse 10 GiB `O_DIRECT` write file (`fallocate`, `dd oflag=direct`, fsync, `stat`, `filefrag`); idempotent, `--force`, `--dry-run` |
| `precondition.sh` | the preconditioning burst: 4K random write at QD 64 in 4 GiB chunks until `3 x` file size, then fsync; records the generation and device state; `--condition and` reproduces the old rule; `--dry-run` |
| `run-write-cells.sh` | the matrix for JUring and fio, Seastar opt-in: methodology windows, three repetitions by default, `--reps-by-cell`, `--resume`, `--dry-run` |
| `run-write-campaign.sh` | one-command driver for the full 130-run session in three blocks, with a recorded 300 s rest between blocks, preflight, `--resume` and `--dry-run` |
| `sample-device-iops.sh` | 1 s series from `/sys/block/nvme1n1/stat` to TSV; backgrounded by the driver or run standalone |

All five scripts pass `bash -n`, have `--help` and `--dry-run`, make no `$HOME` assumptions, use `set -euo pipefail`, and need no root for the harness itself.

## The commands

Run from the repo root. Step 0 does no I/O and is safe at any time. The rest need an idle NVMe.

```bash
cd /home/david/projects/JUring

# ---- Step 0: static and dry-run validation (no I/O) ----
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

# ---- Step 1: create or verify the 10 GiB write file (once) ----
bench/write/create-write-file.sh

# ---- Step 2: one preconditioning burst (standalone check) ----
bench/write/precondition.sh

# ---- Step 3: smoke subset first (one configuration, one repetition, real rests) ----
bench/write/run-write-cells.sh --cells W5 --reps 1
bench/write/run-write-cells.sh --cells W5 --reps 1 --rest-sec 60 --precond-rest-sec 300

# zero-I/O structural check of the same plan
bench/write/run-write-cells.sh --cells W5 --reps 1 --dry-run

# ---- Step 4: the full session (one command, about 9 h, 130 runs) ----
bench/write/run-write-campaign.sh --session-dir bench/results/stepa-write-<date>

# continue an interrupted session (run-granular; skips present JSONs)
bench/write/run-write-campaign.sh --resume --session-dir bench/results/stepa-write-<date>

# the underlying driver is still there for custom selections
bench/write/run-write-cells.sh --engines juring,fio,seastar --allow-seastar-write

# ---- optional: a standalone 1 s series during a manual run ----
bench/write/sample-device-iops.sh --out series/manual.tsv --label manual &
# ... run the engine ...
kill -TERM $!      # the sampler writes its final # end line and exits 0
```

The `W5`-style values are the harness's case IDs passed to `--cells`; the workload tables below
use plain descriptions and keep the IDs only where a command or result path needs them.

`create-write-file.sh` prints `CREATE_WRITE_FILE_RESULT action=created ... size_bytes=10737418240 allocated_bytes=10737418240 non_sparse=1 ext4`; a second run prints `action=skipped`. `precondition.sh` prints `PRECONDITION_RESULT ... condition=target stop_reason=target-reached chunks=8 bytes_written>=32212254720 fsync=ok`. The burst stops as soon as the target is reached; `--min-sec=120` only caps a burst that cannot reach it (`rc=1`, `stop_reason=min-sec-cap`). After the burst the device must idle 600 s before the first configuration; `run-write-cells.sh` handles that when it owns the session.

`run-write-cells.sh` refuses to run on a missing, sparse or wrong-size write file, under 2 GiB free, a write filesystem that is not `nvme1n1p1`, a concurrent benchmark process, or a loadavg(1m) above `--loadavg-max 0.5`. Use `--loadavg-action warn` only when you will annotate the run.

## Workloads and protocol

| Workload | Block size | QD | Warmup / measured | Repetitions | Engines |
|---|---|---|---|---|---|
| Random write | 4 KiB | 1 | 60 / 60 s | 3 | JUring, fio io_uring, Seastar (bracket) |
| Random write | 4 KiB | 16 | 120 / 60 s | 3 | JUring, fio io_uring, Seastar (bracket) |
| Random write | 4 KiB | 64 | 120 / 60 s | 5 | JUring, Seastar, fio io_uring, plus registration-only `matched`, `matchedbatch` and the `batch1/8/16/32` sweep |
| Random write | 4 KiB | 128 | 120 / 60 s | 5 | JUring, Seastar, fio io_uring, plus `matchedbatch` |
| Sequential write | 1 MiB | 1 | 60 / 60 s | 3 | JUring, fio io_uring, Seastar (bracket) |
| Sequential write | 1 MiB | 8 | 60 / 60 s | 3 | JUring, fio io_uring, Seastar (bracket) |
| Sequential write | 1 MiB | 32 | 60 / 60 s | 3 | JUring, fio io_uring, Seastar (bracket) |

Seastar is a bracket, never a pass or fail. `fio-libaio` is available with `--fio-variants libaio` but is not in the default session. Warmups follow the methodology: the QD 16 to 128 random writes use 120 s, the others 60 s. `--warmup-sec N` overrides for a uniform 60/60 session; record the deviation in `notes.md`.

The per-configuration protocol:

1. `precondition.sh` writes 4 GiB chunks of 4K random write at QD 64 until it reaches `>= 3 x` the file size (`32,212,254,720` B), fsyncs, and records a generation (`condition=target`, `stop_reason=target-reached`). Then a 600 s idle rest.
2. For every repetition the selected engines run in rotated order, each as a fresh process. The order goes to `state/order.tsv`.
3. After every measured run there is a 300 s idle rest, recorded in `state/rests.tsv`. `--rest-scope reps` rests only between repetition groups, a documented deviation.
4. Every configuration gets a fresh preconditioning burst, so the order stays monotonic. `--cells` may select a subset; the order is preserved.

Preconditioning defaults to per configuration. `--precondition session-once` costs less but a long session drifts away from the pre-write state, and `none` is for smoke runs only. `--condition and --chunk-bytes 10g` reproduces the 2026-11-13 block, where the old target-and-120 s rule wrote 128.8 GiB; the current target-driven rule stops at about 8 chunks and 32-34 GiB.

## State-controlled 3-way rerun (JUring / fio / Seastar)

`bench/write/run-write-3way.sh` is the pre-registered continuous-state protocol behind the 2026-11-23 write table in BENCHMARK.md: cells W7/W8, one preconditioned generation per attempt, no rests inside a generation, 60 s warmup + 60 s measured, JUring + `fio` matched/matchedbatch + Seastar, engine order rotated per generation, and stop as soon as four generations pass the frozen `analyze_write_state.py` gate. The protocol, the deviations and the validity/ranking rules are frozen in [docs/write-state-3way-protocol.md](../../docs/write-state-3way-protocol.md). The Seastar arm is opt-in in the generic harness, so the wrapper passes `--allow-seastar-write`; the write path is validated in [bench/seastar/build-and-run.md](../seastar/build-and-run.md).

```bash
# zero-I/O structural check first
bash bench/write/run-write-3way.sh --dry-run

# the campaign: up to 5 generations, stop at 4 valid
bash bench/write/run-write-3way.sh

# the frozen JUring/fio gate and the 3-way table
python3 bench/stepd/analyze_write_state.py --session "$S" --gens "$GENS" --out "$S/analysis"
python3 bench/write/analyze_write_3way.py --session "$S" --gens "$GENS" --out "$S/analysis-3way"
```

To run on a second dedicated NVMe, bind-mount it under the repo and pass the path and sysfs device;
`--allow-any-path` relaxes the write-file guard (the read dataset is still refused) and the thermal
probe follows `--device-stat`:

```bash
sudo mkdir -p /mnt/data/juring-write && sudo chown david:david /mnt/data/juring-write
mkdir -p nvme-write
sudo mount --bind /mnt/data/juring-write nvme-write
bench/write/create-write-file.sh --file "$PWD/nvme-write/write_files/single_file_bench_w.bin" \
    --allow-any-path --allow-other-device
# 400 GiB SLC burn-in on a near-empty 1 TB TLC drive before generation 1
bench/write/precondition.sh --file "$PWD/nvme-write/write_files/single_file_bench_w.bin" \
    --device-stat /sys/block/nvme2n1/stat --target-mult 40 --min-sec 800 --max-sec 1200 --allow-any-path
ALLOW_ANY_PATH=1 WRITE_FILE="$PWD/nvme-write/write_files/single_file_bench_w.bin" \
    DEVICE_STAT=/sys/block/nvme2n1/stat bash bench/write/run-write-3way.sh
python3 bench/write/analyze_write_steady.py --session "$S" --gens "$GENS" --out "$S/analysis-steady"

# mode probe: same fio command, --iodepth_batch_submit 1..128, two rounds
OUTPUT_DIR=bench/results/write-mode-probe bash bench/write/probe-write-modes.sh

# figures used by README.md / BENCHMARK.md
python3 bench/write/plot_write_modes.py --session "$S" --gens "$GENS" \
    --probe-dir bench/results/write-mode-probe --out-dir docs/figures
```

### Interleaved fallback bracket

When the drive cannot hold a generation homogeneous — the 2026-11-23 primary campaign failed the gate in both completed generations — `bench/write/run-write-interleaved.sh` alternates the three engines in short cycles (15 s warmup + 30 s measured, 5 cycles per cell, order rotated) so all of them sample the same drifting state. It is a descriptive bracket, not a frozen-gate PASS/FAIL, and it is documented in [docs/write-state-interleaved-protocol.md](../../docs/write-state-interleaved-protocol.md). It runs 30 measured runs in about 24 minutes.

```bash
bash bench/write/run-write-interleaved.sh --dry-run   # zero-I/O plan check
bash bench/write/run-write-interleaved.sh
python3 bench/write/analyze_write_interleaved.py --session "$S" --out "$S/analysis"
```

## Engine invocations

All engines are pinned with `taskset -c 2`. JUring runs with `JURING_SKIP_COMPILE=1`, one fresh JVM, ring and file descriptor per run, `--runs 1`, fixed seed `20261113`. Its JVM flags match the read session (`--enable-native-access=ALL-UNNAMED`, no `-Xms`/`-Xmx`), keeping the engine constant across the read and write matrices.

JUring (`bench/juring/run-juring.sh`, the same harness as the read workloads):

```bash
JURING_SKIP_COMPILE=1 taskset -c 2 bench/juring/run-juring.sh \
  --workload randwrite --file nvme-data/write_files/single_file_bench_w.bin \
  --bs 4k --qd 64 --threads 1 --seconds 60 --warmup-sec 120 \
  --runs 1 --seed 20261113 \
  --label W7__juring__std__bs4k__qd64__t1__r1 \
  --json bench/results/m1-2026-11-13-write/cells/W7__juring__std__bs4k__qd64__t1__r1.json
# 1M: --workload seqwrite --bs 1m
```

fio (neutral reference, io_uring, `O_DIRECT`, time based; the flags match the read session in `bench/m1/lib.sh`):

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
| `default` | none, so fio's implicit `iodepth_batch_submit=1` | plain reference |
| `matched` | `--registerfiles=1 --fixedbufs=1` | matched registration, default batching |
| `matchedbatch` | `--registerfiles=1 --fixedbufs=1 --iodepth_batch_submit=<qd>` | matched registration plus fused submit; auto-added at saturation |
| `batch1` | `--iodepth_batch_submit=1` | explicit one-SQE-per-enter point; added so the sweep is `batch1, batch8, batch16, batch32, matchedbatch` |
| `batch8`, `batch16`, `batch32` | `--iodepth_batch_submit=8/16/32` | the batching sweep, added for QD 64 |
| `sqpoll` | `--sqthread_poll=1` | Seastar asymmetry bracket, optional |
| `libaio` | `--ioengine=libaio` | kernel-AIO reference, optional |

Seastar (`bench/seastar/seastar_file_bench`; opt in with `--allow-seastar-write`):

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

The runner opens the existing file `rw|create`, uses `file::dma_write` (no fixed buffers or files, the documented Seastar asymmetry), one fiber per outstanding I/O, and emits the same schema as JUring. The write path was smoke-validated on this host on 2026-11-23 (`status=ok`, `errors=0`, in-place write on the 10 GiB file, size unchanged, no truncate/fsync), but it stays opt-in via `--allow-seastar-write` because the binary is host-built. Smoke-test it with `--seconds 3 --warmup-sec 1` before enabling it on another host.

## Device-state controls

- Preconditioning runs before every configuration. `--condition target` is the default, `--condition and` reproduces the old target-and-120 s rule, and `--min-sec` only caps an unreachable target. Rows land in `state/write-generations.tsv` and `state/precondition-results.txt`, with a counter in `state/write-generation.counter`.
- `sample-device-iops.sh` writes `series/<stem>.tsv` per run: read and write IOPS, MiB/s, in-flight, `io_busy_pct` and loadavg at 1 s over the window, approximately.
- 300 s between measured runs, 600 s after every preconditioning burst. Actual durations go in `state/rests.tsv`.
- Mount options go in `manifest.txt`; the harness warns if `discard` is present and never issues discard. Thermal state is recorded before and after every run: hwmon °C if readable, else `nvme smart-log` (root), else `unavailable(no-root)`.
- Every repetition is kept. Each run prints a `RESULT` line and each configuration a `CELL_SUMMARY` line with n, mean, sd, CV% and `unstable=1` above 10% CV. An unstable configuration gets no ranking.
- Loadavg, the top CPU process, the governor, the thermal state and `/sys/block/nvme1n1/stat` deltas are recorded. Device write sectors must move; a mismatch above 10% against the engine bytes warns.
- Each workload keeps its block size, QD, `O_DIRECT` and pinning fixed, and fio `default` and `matched` are never mixed in one judgement.
- Latency is not cross-engine comparable: JUring timestamps before SQE preparation, Seastar before `dma_write`, and fio reports `clat`. Compare throughput, and report tails per engine.
- fio sequential write is `--rw=write`; overwrite semantics are equivalent here because the 10 GiB file already exists and fio wraps with `--time_based`.

## Outputs and naming

The session root is `bench/results/m1-2026-11-13-write/` unless you pass `--session-dir`. The executed matrix is in `bench/results/m1-write-2026-11-15/`.

```text
cells/<case>__<engine>__<variant>__bs<bs>__qd<qd>__t1__r<rep>.json
logs/<same stem>.log
series/<same stem>.tsv
cell-meta.tsv                      # one row per run; p99_us is microseconds for every engine
state/order.tsv, runs.tsv, rests.tsv, cell-summaries.txt, resumed.tsv, invocations.tsv
state/write-file.tsv, write-generations.tsv, precondition-results.txt
manifest.txt                       # first invocation
manifest-run<N>.txt                # later invocations; manifest.txt is never truncated
logs/campaign-block<N>.log
```

`<case>` is the workload id the driver selects with `--cells`; `<engine>` is `juring`, `fio-io_uring`, `fio-libaio` or `seastar`; `<variant>` is `std` for JUring and Seastar, and `default`, `matched`, `matchedbatch`, `batch1/8/16/32` or `sqpoll` for fio. Sequence numbers stay monotonic across blocks and later `--resume` invocations.

fio `clat_ns` percentiles are divided by 1000 before they go into `RESULT`, `state/runs.tsv` and `cell-meta.tsv`, so `p99_us` is microseconds for every engine and the manifest records `cell_meta_p99_units=us`. Sessions written before that fix keep their nanosecond values and are not citable for fio p99.

Each measured run prints:

```text
RESULT seq=.. cell=<case> engine=juring variant=std bs=4k qd=1 rep=1 status=ok exit=0
  iops=.. mibs=.. p99_us=.. bytes=.. errors=.. buffer_mode=registered-fixed
  dev_write_mib=.. elapsed_s=.. json=.. log=.. series=..
```

Each configuration summarises:

```text
CELL_SUMMARY case=<case> engine=fio-io_uring variant=matched bs=4k qd=1 n=3 ok=3
  iops_mean=.. iops_sd=.. cv_pct=.. mibs_mean=.. p99_mean_us=.. unstable=0
```

The last line is `SESSION_RESULT session_dir=.. runs_planned=.. runs_ok=.. runs_failed=.. elapsed_s=..`, and it exits 1 if any run failed.

## What the session costs

| Part | Workloads / repetitions | Runs | Estimate |
|---|---|---|---|
| Random writes, QD 1 and QD 16 | 3 repetitions | 24 | about 1.8 h |
| Random writes, QD 64 and QD 128 | 5 repetitions | 70 | about 4.7 h |
| Sequential writes, QD 1, 8 and 32 | 3 repetitions | 36 | about 2.5 h |
| Rests between blocks | 2 x 300 s | — | about 600 s |
| Full session | 7 preconditioning bursts, JUring, fio and Seastar | 130 | about 9.2 h |
| Smoke: QD 1 random write, one repetition, JUring and fio | 3 runs | 3 | about 28 min |

The estimate uses `run = warmup + measured + 3 s`, a 90 s target-driven precondition plus 600 s rest per burst, and 300 s between repetition groups.

## What I got

The full matrix ran 130 of 130 planned runs over 8 h 47 m. Six failed-init 1 MiB `fio-matched` attempts were re-measured as `regfile`. The result is inconclusive: 36 of the 130 runs crossed the SLC burst-to-steady-state cliff during the run, with within-run burst/steady ratios from `0.14x` to `3.85x`, and 21 of the 34 configurations were unstable, 17 of them with CV above 10%. An unstable configuration gets no ranking.

At 4 KiB random write QD 64 with five repetitions, JUring was stable at `188,704 ± 1,983` IOPS (`CV = 0.8%`), while `fio/matched` (`158,570 ± 50,427`, `CV = 25.6%`), `fio/matchedbatch` (`170,543 ± 42,826`, `CV = 20.2%`) and Seastar (`167,073 ± 43,672`, `CV = 21.1%`) sampled post-cliff states. The state-controlled chain got two valid generations of the five it needed, so it is also inconclusive. [`BENCHMARK.md`](../../BENCHMARK.md#the-write-path-i-could-not-measure-fairly) has the numbers and the reasoning.

## Re-runs, cleanup, and root

- Re-running a configuration appends new `RESULT` and `CELL_SUMMARY` lines, but the run JSON is overwritten per stem. Copy a completed session before re-running it, or use a new `--session-dir`.
- `--resume` is run-granular: a JSON that parses with `iops>0` is skipped, a configuration with missing runs gets one more preconditioning burst, a fully present one skips both, skips land in `state/resumed.tsv`, and the manifest is preserved as `manifest-run<N>.txt`.
- To recreate the write file, `bench/write/create-write-file.sh --force`. To free the space, unlink `nvme-data/write_files/single_file_bench_w.bin` after archiving; it is a plain unlink, no TRIM. The `flock` file is harmless and can stay.

Some checks still need host root. `nvme smart-log /dev/nvme1n1` is attempted per run and recorded as `unavailable(no-root)`; run it before and after on the host if thermal gating matters. `drop_caches` is not needed, since every write is `O_DIRECT` on a preallocated file. The governor is already `performance`. `ulimit -l` is 8 MiB, so JUring falls back to unregistered arena buffers for the 1 MiB QD 8 and QD 32 writes, recorded in the JSON. There is no hugetlbfs, so the mandatory `--lock-memory 0` THP path is recorded. `nvme-data/` is a host-side bind of `/mnt/TestData/juring-bench`; if it is missing or read-only, redo that step. Do not enable `discard`.

## What a citable session looks like

1. `bash -n` is clean for all five scripts, every `--dry-run` prints a plan without touching the device, and the full-session dry run plans exactly 24, 70 and 36 runs, 130 total.
2. `CREATE_WRITE_FILE_RESULT` (or `action=skipped`) reports `size_bytes=10737418240`, `allocated_bytes>=size*0.99`, `fstype=ext4` and source `/dev/nvme1n1p1`; the read file is unchanged (`read_file_unchanged=true`).
3. Every `PRECONDITION_RESULT` has `condition=target`, `stop_reason=target-reached`, `bytes_written >= 32212254720`, `fsync=ok` and `device_write_sectors_delta > 0`, with a recorded generation. A capped burst aborts unless `--allow-capped-precondition` is given.
4. Every run has `RESULT ... status=ok`, `errors=0`, engine bytes above 0, moved device sectors and a `series/<stem>.tsv`. Every configuration has `CELL_SUMMARY n=3`, all `ok=3`, plus mean, sd and CV; a CV above 10% is unstable and needs a cause note.
5. `SESSION_RESULT runs_failed=0`, the manifest is complete, every JSON has its named file, every log has its command line, and `cell_meta_p99_units=us` is present. A full session prints `CAMPAIGN_RESULT status=ok` with `runs_planned=130`, `blocks_ok=3` and two `phase=between-blocks` rows in `state/rests.tsv`.
6. Analysis uses the read-path statistics: mean ± 95% CI, min, max, median, CV and paired ratios against `fio-matched`; no delta whose interval contains zero, and parity only inside `[0.95, 1.05]`. Keep writes separate from reads and state the burst versus steady-state split from the series.
