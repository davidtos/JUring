# Stream benchmarks (bench/stream/)

A ring is not much use if reading a file through it is slower than a buffered `InputStream`. So I built `JuringInputStream` and `JuringOutputStream` and compared them against Seastar's `fstream`. It also has two write-side protocols: a durability A/B with matched sync settings on both engines, and a JUring-only group-commit benchmark.

The results are in [`BENCHMARK.md`](../../BENCHMARK.md); the protocol and statistics are in [`docs/benchmark-methodology.md`](../../docs/benchmark-methodology.md); the stream design is in [`docs/design-notes.md`](../../docs/design-notes.md).

### Identifiers

Cells are named by what they do (`stream-write-128k`, `stream-read-1m`, `durability-write-128k`,
`groupcommit-4k-batch32`, ...); the mapping and its single source of truth are
[`bench/analysis/cells.py`](../analysis/cells.py) and [`bench/cells.sh`](../cells.sh). Runs,
manifests and tables use these canonical names.

## The stream comparison

The workloads are sequential stream read and write at two buffer sizes, 128 KiB and 1 MiB, with concurrency 8. Reads use `/mnt/TestData/read_files/single_file_bench.bin`, a 10 GiB read-only file. Writes use `nvme-data/write_files/single_file_bench_w.bin`, a 10 GiB file that is overwritten in place and never resized.

The primary rows:

- JUring: `O_DIRECT`, registered file (`JuringFile.register()`), ring `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` with `entries = max(256, 2*qd)`, stream options `bufferSize=bs`, `readAhead=qd-1` for input and `writeBehind=qd` for output, `alignment=4096`, dynamic adjustments off, one owner thread.
- Seastar: `bench/seastar/seastar_stream_bench`, fstream over `open_file_dma` (DMA/O_DIRECT on this filesystem), with `--smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring`.

There is a disclosed exploratory JUring buffered row (`--buffered`, no `O_DIRECT`) that never counts toward the result.

The defaults are 10 s warmup and 30 s measured, three repetitions, one fresh process per repetition, `taskset -c 2`, engines interleaved with the order alternating, and 60 s rest between repetitions. The final session used 10 s warmup and 60 s measured, five repetitions and 300 s rests, and `analyze_stream.py --session <stream-session>` rebuilds it from the raw JSON.

| Workload | Buffer | Prefetch / write-behind | JUring MiB/s ± 95% CI | Seastar `fstream` ± 95% CI | Paired ratio |
|---|---|---|---|---|---|
| Sequential read | 128 KiB | `readAhead = 8` | 2,284.6 ± 76.2 | 2,327.5 ± 72.3 | 0.9816 |
| Sequential read | 1 MiB | `readAhead = 4` | 4,662.8 ± 287.6 | 4,724.3 ± 131.3 | 0.9865 |
| Sequential write | 128 KiB | `writeBehind = 8` | 610.0 ± 402.8 | 438.6 ± 359.8 | 1.3908 point; 1.232 fast / 0.984 slow |
| Sequential write | 1 MiB | `writeBehind = 4` | 569.5 ± 386.7 | 355.7 ± 300.1 | 1.6011 point; 1.269 / 1.384 |

I measured reads at parity: `0.9816` and `0.9865` of Seastar, with strict lower bounds of `0.966` and `0.960` against the `0.85` bar. Writes look like a point-estimate win, but the first engine after a 300 s rest ran in the fast drive state and the second in the slow state, so I quote position-matched strata. Durability wasn't matched either: Seastar ran `durable=true` with two `fdatasync` per pass, JUring ran `syncOnClose=false` with none. Treat the write rows as point estimates, not a ranking.

```bash
# prerequisites: compile the stream API and build the Seastar binary
mvn -o -q -DskipTests compile
bench/seastar/build-stream-bench.sh

# plan only: preflight plus every command line, no files, no I/O
bench/stream/run-stream-matrix.sh --dry-run

# short validation with tiny windows
bench/stream/run-stream-matrix.sh --dry-run --reps 1 --seconds 3 --warmup 1 --rest 0 \
    --cells "stream-write-128k stream-read-128k"

# full preliminary session, roughly 35-40 minutes
bench/stream/run-stream-matrix.sh
# -> bench/results/stream-matrix/{cells,logs,aggregate}/, cell-meta.tsv, manifest.txt

# re-runs never overwrite a populated session; start a new one
bench/stream/run-stream-matrix.sh --session stream-matrix-r2

# aggregate (the driver also runs this at the end)
python3 bench/stream/analyze_stream.py --session stream-matrix
```

Overrides: `--reps N`, `--seconds S`, `--warmup S`, `--rest S`, `--cells "stream-write-128k,..."`, `--session NAME`, `--force`, `--dry-run`.

`--cells` takes the canonical case names (`stream-write-128k`, `stream-read-1m`, ...).
Environment: `CPU` (default 2), `READ_FILE`, `WRITE_FILE`, `SEASTAR_BIN`, `JURING_RUN`, `ALLOW_RESIDUAL_LOAD=1`.

The driver refuses to start unless both datasets exist with the right permissions and size (at least 10 GiB and a multiple of 1 MiB), the write filesystem has at least 2 GiB free, `kernel.io_uring_disabled=0`, the governor is `performance`, the pinned CPU is online, 1-min loadavg is below 1.0 (unless `ALLOW_RESIDUAL_LOAD=1`), no foreign process is above 20% CPU, and the JUring runner, Seastar binary and analyzer exist. `--dry-run` reports the same checks without exiting on failure or creating files.

`analyze_stream.py` reads `cells/<workload>__<engine>__<variant>__r<rep>.json` and writes `aggregate/stream.json` and `aggregate/stream.md`. It reports per-workload and per-engine MiB/s mean, sd, 95% CI, CV, min and max, plus IOPS, chunk latency p50 and p99, errors, `direct_io`, `registered_files`, excluded and missing files, and a paired block with the ratio mean, sd, 95% CI, one-sided 95% lower bound and dropped repetitions. It uses sample sd, a two-sided 95% Student-t interval, and a paired per-repetition ratio of JUring to Seastar. The bar is JUring MiB/s at least `0.85 x` Seastar. A workload is below the bar when the mean ratio is under `0.85`, a pass when the mean and lower bound both clear it, inconclusive when only the point estimate clears, and insufficient-n with fewer than two paired repetitions. The overall result requires every primary workload to clear the point-estimate bar. Nothing is dropped silently.

What the comparison does not hide:

1. The defaults are preliminary: three repetitions and 60 s rests, not the five repetitions and 300 s rests in the methodology. `manifest.txt` records the values used.
2. The writable dataset is overwritten in place, exactly `dataset` bytes per pass, and its size never changes. Multi-file layouts are out of scope, and the 128 KiB and 1 MiB buffer sizes are reported separately.
3. The buffered JUring row is exploratory only. It exists to quantify the `O_DIRECT` asymmetry, because Seastar's fstream is DMA here.
4. No cache drop and no root. `O_DIRECT` on the primary rows makes that mostly moot, and the files are far larger than a 30 s working set.
5. Latency is per stream chunk, 128 KiB or 1 MiB, not per underlying 4 KiB I/O. Both engines use that definition.
6. The runner never extends or truncates the shared dataset. `--size` is capped at the file size, and a write dataset must be a multiple of `alignUp(bs, 4096)` so the stream's flush-time alignment truncate cannot change the file size.

The two engine rows also differ in buffers and concurrency: JUring uses stream-allocated 4096-aligned native arenas with `fixed_buffers=false` and one owner thread; Seastar uses unregistered aligned buffers, its io queue, futures and fibers, and one reactor shard.

Single runs are usable standalone:

```bash
env JURING_SKIP_MVN=1 bash bench/juring/run-juring-stream.sh \
    --workload stream_write --file nvme-data/write_files/single_file_bench_w.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 \
    --label stream-write-128k_smoke --json /tmp/stream-write-128k_smoke.json

env JURING_SKIP_MVN=1 bash bench/juring/run-juring-stream.sh \
    --workload stream_read --file /mnt/TestData/read_files/single_file_bench.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 --readonly \
    --label stream-read-128k_smoke --json /tmp/stream-read-128k_smoke.json

LD_LIBRARY_PATH="$PWD/.sysroot/root/usr/lib64" taskset -c 2 \
    bench/seastar/seastar_stream_bench \
    --workload stream_read --file /mnt/TestData/read_files/single_file_bench.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 --readonly \
    --label stream-read-128k_seastar_smoke --json /tmp/stream-read-128k_seastar_smoke.json \
    --smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring
```

`--json -` is not supported: the runner always writes one JSON object to stdout and, with `--json PATH`, also to `PATH`.

## Durability A/B

This is supporting evidence, not a milestone bar. One write workload: 128 KiB stream writes at concurrency 8, `--size 2g` on the existing 10 GiB write file. The file is never resized; the 2 GiB pass is a disclosed deviation from the 10 GiB dataset. The four arms all use `--sync-every 4M`: JUring writeback, JUring fdatasync, Seastar writeback and Seastar fdatasync, three repetitions.

Protocol: a 300 s idle rest before each repetition window, then the four arms back-to-back inside that post-rest window, with the order alternating per repetition. Five seconds warmup and 15 s measured per arm, one fresh process per invocation, `taskset -c 2`. The JUring writeback arm runs first overall in repetition 1 and last overall as an extra `writeback-bracket` control after repetition 3; those two appearances are the write-state control pair.

Both runners gained durability flags. JUring accepts `--durability writeback|fdatasync|fsync|dsync` (default writeback), `--sync-every BYTES` (write only, a multiple of `--bs` and 4096, default 0 meaning pass end only) and `--fadvise normal|random|sequential|willneed|dontneed|noreuse`. Seastar accepts `--durability writeback|fdatasync|dsync` (default fdatasync), `--durable 0|1`, `--dsync 0|1` and `--sync-every BYTES`; `--durability fsync` is rejected because Seastar has no `fsync(2)` path. `writeback` drains only at a durability point, `fdatasync` and `fsync` drain and issue one matching sync, and `dsync` uses an `O_DSYNC` file with no explicit sync call. `--fadvise` is informational.

Tier 2 is exploratory: `juring-dsync` and `seastar-dsync` at two repetitions and 120 s rests, plus an optional `juring-fsync` arm that `--skip-fsync` disables, because Seastar cannot fsync. The timed block costs about 26-30 minutes.

```bash
# plan only: preflight report, every command line, wall estimate
bench/stream/run-durability-ab.sh --dry-run

# short validation with tiny windows
ALLOW_RESIDUAL_LOAD=1 bench/stream/run-durability-ab.sh \
    --reps 1 --warmup 1 --seconds 2 --rest 5 --tier2-reps 1 --tier2-rest 5 \
    --size 64m --session durability-ab-smoke

# full session on a quiet machine (three repetitions, 5 s warmup, 15 s measured, 300 s rests)
bench/stream/run-durability-ab.sh

python3 bench/stream/analyze_durability.py --session durability-ab

# resume an interrupted session
bench/stream/run-durability-ab.sh --resume
```

The session lives in `bench/results/durability-ab/` with `cells/`, `logs/`, `aggregate/`, `manifest.txt` and `cell-meta.tsv`. Overrides: `--reps`, `--warmup`, `--seconds`, `--rest`, `--tier2-reps`, `--tier2-rest`, `--size`, `--skip-tier2`, `--skip-fsync`, `--no-analyze`, `--resume`, `--session`, `--force`, `--dry-run`. Environment: `CPU` (2), `WRITE_FILE`, `SEASTAR_BIN`, `JURING_RUN`, `ALLOW_RESIDUAL_LOAD=1`. The driver refuses to overwrite a populated session, preflights the dataset, space, io_uring, governor, CPU, loadavg and foreign CPU, and records everything in `manifest.txt`, including the pinned Seastar `--aio-fsync 1`.

`analyze_durability.py` requires the `durability-1` extension in the session JSON and exits 3 on a mixed or old session. It writes `aggregate/durability.{json,md}` with per-arm MiB/s mean, sd, 95% CI and CV, `syncs_issued`/s, `MiB/sync = bytes_synced/syncs_completed`, `sync_wait_share`, sync latency p50 and p99, and the per-repetition state class. It also reports the within-repetition paired durability-cost ratio per engine (durable MiB/s over writeback MiB/s), with a 95% CI over repetitions plus the state-matched subset.

The state-class rule is fixed in the analyzer: a repetition is `fast` iff MiB/s is at least 550 and write p50 is at most 250 µs, `slow` iff MiB/s is below 300 or p50 is above 600 µs, and `mixed` otherwise; the `0.92` same-arm rule also marks a repetition slow. Classes are computed within the arm, because a durable arm is slower by design. A pair is ranked only if both sides have at least two repetitions in the same class. An arm with CV above 10% gets no ranking, and the two writeback controls must agree within 10% or only the raw table is reported.

The executed session was state-confounded: the two writeback controls differed by 148.9%, so only the raw per-repetition table is reported. Matched durability accounting did verify at `MiB/sync` of `3.993` for JUring and `3.983` for Seastar.

The disclosures, also in `aggregate/durability.md` and `manifest.txt`: no cross-engine write win or loss claim; the stream comparison's write arms issued two `fdatasync` per pass on Seastar against zero on JUring, and this A/B matches durability instead; durability points include a write-behind drain, so `sync_wait_share` is an upper bound on pure sync cost; Seastar's pass end issues two `fdatasync`; `juring-fsync` is JUring-only; the dataset is 2 GiB; there is no cache drop and no power-loss test.

## Group commit

This one is JUring-only. Seastar has no group-commit primitive; its nearest equivalent is the Seastar fdatasync arm above, which pays a write-behind drain the JUring helper does not, so it is an upper bound. The bench calls the public `GroupCommit` API (`GroupCommitOptions`, `GroupCommit`, `GroupCommitStats`) and uses no test hook.

The runner is `bench/juring/JUringDurabilityBench.java`: `--file`, `--batch B`, `--records N` or `--seconds S`, `--bs 4096`, `--durability fdatasync|fsync|writeback` (default fdatasync), `--warmup-sec`, `--runs`, `--file-size`, `--json`, `--label`, `--verify-only`, `--verify-bytes`, `--no-verify`.

The batch sizes are `1`, `8`, `32` and `128`, equal to `GroupCommit.maxBatchSize`. The workload preallocates the file (`setLength`, `--file-size 1g` by default). For each group it fills `B` distinct 4096-aligned buffers with a deterministic position-indexed xorshift64 pattern, issues `B` `GroupCommit.write()` calls at consecutive aligned offsets, wrapping modulo the file capacity, then one `commit()`. The measured window is 15 s after a 5 s warmup, one fresh process per invocation, `taskset -c 2`, three repetitions. There is a 5 s pause between repetitions inside a batch-size block and a 120 s idle rest between blocks, for about 8-12 minutes including the read-back.

After every measured run the bench spawns a fresh JVM with `--verify-only` that re-reads `[0, min(committed_bytes, file_size))` and compares every 4096-byte block with the deterministic pattern. `restart_verify{ok,verified_bytes,method,power_cut_tested:false,...}` is in the run JSON. This is process-restart visibility, not a power-loss test.

The identities it checks are `fsyncs_per_group == 1.0` (exactly one `fdatasync` per committed batch), `groups_total x batch_size == committed_writes` (a short last group is allowed only in `--records` mode with `committed_writes == records`), `syncs_completed == syncs_issued == groups_total`, `syncs_failed == 0`, and `restart_verify.ok == true`.

```bash
# plan only
bench/stream/run-group-commit-bench.sh --dry-run

# short validation with tiny windows and a scratch file
ALLOW_RESIDUAL_LOAD=1 bench/stream/run-group-commit-bench.sh \
    --reps 1 --warmup 1 --seconds 2 --rest 0 --rep-pause 0 --batches "1 8 32 128" \
    --file scratch/group-commit/smoke/groupcommit-driver.bin --file-size 16m --session group-commit-smoke

# full session on a quiet machine
bench/stream/run-group-commit-bench.sh

python3 bench/stream/analyze_group_commit.py --session group-commit

# resume an interrupted session
bench/stream/run-group-commit-bench.sh --resume
```

The session lives in `bench/results/group-commit/` with `cells/`, `logs/`, `aggregate/`, `manifest.txt` and `cell-meta.tsv`. Overrides: `--reps`, `--warmup`, `--seconds`, `--rest`, `--rest-between`, `--rep-pause`, `--batches`, `--file`, `--file-size`, `--skip-compile`, `--no-analyze`, `--resume`, `--session`, `--force`, `--dry-run`. Environment: `CPU`, `GC_FILE`, `GC_FILE_SIZE`, `JURING_CLASSES` (default `target/classes`), `JURING_OUT` (default `bench/juring/out`), `ALLOW_RESIDUAL_LOAD=1`. The driver compiles the runner with `javac` against the existing classes; it never runs Maven. It preflights the API presence, file space, io_uring, governor, CPU, loadavg and foreign CPU.

`analyze_group_commit.py` requires `durability-1` and writes `aggregate/group-commit.{json,md}`: per batch size MiB/s (mean, sd, 95% CI, CV), writes/s, groups/s, group-latency p50 and p99, sync latency p50 and p99, syncs/s, `MiB/sync`, `sync_wait_share`, `fsyncs_per_group`, wrap-arounds, identity rows and the per-repetition table. It exits 3 and lists the offending files if any identity, restart check or schema check fails. `sync_latency_us` is the `commit()` duration and an upper bound on the sync-only cost, because it includes waiting for the batch writes.

I measured batch 1 at `5.9 MiB/s` and batch 128 at `720.7 MiB/s`, and `fsyncs_per_group` stayed at exactly `1.000000` all the way. Every configuration passed the fresh-process restart check. [`BENCHMARK.md`](../../BENCHMARK.md#group-commit) has the table.

Smoke sessions and their raw JSON and logs live under `scratch/group-commit/smoke/`, never in `bench/results/`, which is reserved for real sessions. The two heavy drivers refuse to start if the preflight checks fail.
