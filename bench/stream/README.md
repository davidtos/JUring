# B2 W21/W22 stream benchmark (`bench/stream/`)

Preliminary W21/W22 comparison of the JUring B2 stream layer
(`JuringInputStream` / `JuringOutputStream`) against seastar `fstream`
(`file_input_stream` / `file_output_stream`), plus the JUring-side stream
runner and the driver/aggregator that produce the plan-M2 readiness verdict.

Frozen contract: [`docs/b2-design.md`](../../docs/b2-design.md) §3.1 (runner
CLI + JSON), §3.2 (seastar runner) and §3.3 (W21/W22 protocol, driver,
analyzer). This directory owns only the JUring runner invocation, the matrix
driver and the aggregator; it does not modify the engines.

Related files (owned by other workstreams):

| file | role |
|---|---|
| `bench/juring/JUringStreamBench.java` | JUring stream runner (this workstream) |
| `bench/juring/run-juring-stream.sh` | compile/run wrapper for the runner |
| `bench/seastar/seastar_stream_bench.cc`, `bench/seastar/build-stream-bench.sh` | seastar fstream runner |
| `docs/b2-design.md` | frozen contract |
| `docs/b2-report.md` | B2 report (Lead) |

## 1. Cells and protocol

| cell | workload | stream buffer | concurrency (`qd`) | dataset |
|---|---|---|---|---|
| `W21-128k` | `stream_write` | 128 KiB | 8 | `nvme-data/write_files/single_file_bench_w.bin` (10 GiB, writable) |
| `W22-128k` | `stream_read` | 128 KiB | 8 | `/mnt/TestData/read_files/single_file_bench.bin` (10 GiB, read-only) |
| `W21-1M` | `stream_write` | 1 MiB | 8 | same write file |
| `W22-1M` | `stream_read` | 1 MiB | 8 | same read file |

Primary rows (used for the verdict):

* **juring** — `bench/juring/JUringStreamBench` with O_DIRECT
  (`--buffered` off), registered file (`JuringFile.register()`), ring
  `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` with `entries = max(256, 2*qd)`,
  stream options `bufferSize=bs`, `readAhead=qd-1` (input) / `writeBehind=qd`
  (output), `alignment=4096`, dynamic adjustments disabled.
* **seastar** — `bench/seastar/seastar_stream_bench` (fstream over
  `open_file_dma`, DMA/O_DIRECT on this filesystem), run with
  `--smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring`.

Disclosed exploratory row per cell (never used for the verdict): **juring
buffered** (`--buffered`, no O_DIRECT).

Default protocol (preliminary, per §3.3):

* warmup **10 s** + measured **30 s** per process invocation;
* **R = 3** reps per cell, **one fresh process per rep**, everything pinned
  with `taskset -c 2`;
* primary engines **interleaved inside every rep** (rep odd: juring then
  seastar; rep even: seastar then juring) so host drift is common-mode; the
  exploratory buffered row runs last;
* **60 s rest between successive reps of a cell** (baseline rest; the full M2
  campaign uses the methodology's 60 s cells and 300 s rests — see §5);
* O_DIRECT on both primary sides; no cache drop (no root), no dataset
  rewriting/preconditioning.

Each process emits one JSON object (`schema_version 1.0`); the driver stores it
under `cells/` and one row per invocation in `cell-meta.tsv`.

## 2. Running

One-time prerequisites (owned elsewhere): the stream API compiled into
`target/classes` (Lead's build), `bench/seastar/seastar_stream_bench` built via
`bench/seastar/build-stream-bench.sh` (seastar workstream), the two 10 GiB
datasets present.

Plan only (side-effect free, prints preflight + every command line):

```bash
bench/stream/run-stream-matrix.sh --dry-run
```

Short validation run (small R, same protocol):

```bash
bench/stream/run-stream-matrix.sh --dry-run --reps 1 --seconds 3 --warmup 1 --rest 0 \
    --cells "W21-128k W22-128k"
```

Full preliminary W21/W22 session (≈35–40 min wall):

```bash
bench/stream/run-stream-matrix.sh
# -> bench/results/b2-2026-11-13/{cells,logs,aggregate}/, cell-meta.tsv, manifest.txt
```

Re-runs never overwrite a populated session; start a new one explicitly:

```bash
bench/stream/run-stream-matrix.sh --session b2-2026-11-13-r2
```

Then aggregate (the driver runs this automatically at the end; it can also be
run standalone):

```bash
python3 bench/stream/analyze_stream.py --session b2-2026-11-13
# -> bench/results/b2-2026-11-13/aggregate/stream.json + stream.md
```

Driver overrides: `--reps N`, `--seconds S`, `--warmup S`, `--rest S`,
`--cells "W21-128k,..."`, `--session NAME`, `--force` (append to a populated
session), `--dry-run`. Environment: `CPU` (default 2), `READ_FILE`,
`WRITE_FILE`, `SEASTAR_BIN`, `JURING_RUN`, `ALLOW_RESIDUAL_LOAD=1` (relax the
1-min loadavg gate for chained sessions). Results live under
`bench/results/` (gitignored); the report keeps the tables.

## 3. Preflight (fails the run before any benchmark)

`run-stream-matrix.sh` refuses to start unless:

* both dataset files exist; the read file is readable, the write file
  writable; both are ≥ 10 GiB and a multiple of 1 MiB;
* the write filesystem has ≥ 2 GiB free;
* `kernel.io_uring_disabled=0`;
* the CPU governor is `performance` on every CPU;
* pinned CPU 2 is online;
* 1-min loadavg < 1.0 (unless `ALLOW_RESIDUAL_LOAD=1`);
* no foreign process is using > 20 % CPU;
* the JUring runner script, the seastar stream binary and the analyzer exist.

`--dry-run` reports the same checks as PASS/FAIL/NOTE but never exits on
failure and never creates files. The session `manifest.txt` records host,
kernel, CPU, governor, mounts/device, dataset stats, JDK/g++/seastar versions,
git commit/dirty state, engine-binary/harness SHA256, the exact protocol and the
disclosed engine asymmetries.

## 4. Aggregator (`analyze_stream.py`)

Reads `cells/<cell>__<engine>__<variant>__r<rep>.json` (stdlib only) and writes:

* `aggregate/stream.json` — machine-readable: per cell `config`, per engine row
  `mibs {mean, sd, ci95, cv_pct, min, max, values_by_rep}`, `iops`, chunk
  latency `p50`/`p99` (per-rep means), `errors_total`, `direct_io`,
  `registered_files`, `excluded`/`missing` lists, and the `paired` block
  (`ratio_mean`, `ratio_sd`, `ratio_ci95`, `ratio_one_sided_95_lower`,
  `point_estimate_pass`, `lower_bound_pass`, `verdict`, `dropped_reps`), plus
  `overall`.
* `aggregate/stream.md` — the same as report tables: verdict table, MiB/s
  detail, chunk-latency detail, and a data-quality section.

Statistics: sample sd (`n-1`), two-sided 95 % CI via Student t, paired per-rep
ratio `juring_mibs / seastar_mibs` (same rep index, primary O_DIRECT rows only),
one-sided 95 % lower confidence bound of the ratio. `CV%` is reported per
engine row. Failed/malformed/unpaired reps are listed explicitly; nothing is
silently dropped and the exploratory buffered row is never used for the
verdict.

Verdict (plan M2): `juring MiB/s >= 0.85 * seastar MiB/s`. Per cell:

* `below-bar` — mean ratio < 0.85;
* `pass` — mean ratio ≥ 0.85 **and** the one-sided 95 % lower bound ≥ 0.85;
* `inconclusive` — point estimate clears the bar but the one-sided lower bound
  does not (small-R noise);
* `insufficient-n` — fewer than 2 paired reps.

The overall verdict requires every primary cell listed in `stream.json`
(`W21-128k`, `W22-128k`, `W21-1M`, `W22-1M`) to clear the pairwise bar (point
estimate), with the stricter lower-bound reading reported alongside. The 128K
cells are the methodology's W21/W22 wording; the 1 MiB cells address the plan's
"seq 1M" wording.

## 5. Deviations from the frozen protocol (disclosed)

These are preliminary-measurement deviations; the full plan-M2 campaign is a
separate run owned by the Lead.

1. **Durations / rests are preliminary.** 10 s warmup + 30 s measured + R=3
   with 60 s rests between cell reps (design §3.1/§3.3 defaults), not the full
   M2 methodology (60 s cells, 300 s rests, R=5). The driver records the exact
   values used in `manifest.txt` and the analyzer repeats the observed warmup/
   measured values in `stream.json`/`stream.md`.
2. **Datasets are the existing 10 GiB single files** (`single_file_bench.bin`,
   `single_file_bench_w.bin`) — the writable one is overwritten in place by the
   write cells (`0..dataset`, exactly `dataset` bytes per pass; file size is not
   changed). DS-M multi-file W22 layouts are out of scope for B2.
3. **Buffer-size asymmetry is reported, not hidden.** Cells run at 128 KiB
   (methodology W21/W22) and 1 MiB (plan-M2 "seq 1M" wording); both are reported
   separately rather than collapsed.
4. **JUring buffered row is exploratory only.** It exists to quantify the
   O_DIRECT asymmetry (seastar fstream is DMA/O_DIRECT here) and is never
   counted toward the ≥ 85 % verdict.
5. **No cache drop / no root.** Like the M1 session: files are not dropped from
   page cache; O_DIRECT primary rows make this mostly moot. The 10 GiB datasets
   are far larger than the page cache-friendly working set of a 30 s cell.
6. **Latency is per stream chunk** (`readChunk()` / `write()` complete-to-done),
   i.e. per 128 KiB / 1 MiB buffer, not per underlying 4 KiB I/O. This matches
   the frozen §3.1/§3.2 latency definition on both engines.
7. **The runner never extends or truncates the shared dataset.** `--size` is
   capped at the file size; a write dataset must be a multiple of
   `alignUp(bs, 4096)` so the stream's flush-time alignment truncate cannot
   change the file's size. (The stock `JuringOutputStream` truncates to its
   logical length when the final buffer was alignment-padded.)

## 6. Disclosed engine asymmetries (F0)

| aspect | JUring stream row (primary) | seastar fstream row |
|---|---|---|
| io_uring ring | `entries=max(256,2*qd)`; `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` | stock seastar io_uring reactor backend, ~200-entry ring, no equivalent setup flags |
| files | registered via `JuringFile.register()` (runtime fixed-file table) | not registered (seastar has no file registration) |
| buffers | stream-allocated 4096-aligned native arenas, `fixed_buffers=false` | seastar aligned buffers, unregistered |
| concurrency | blocking `JuringFile.await()` on stream-owned in-flight ops, one owner thread | io_queue + futures/fibers, one reactor shard |
| stream options | `bufferSize=bs`, `readAhead=qd-1` / `writeBehind=qd`, `alignment=4096`, dynamic adjustments off | `buffer_size=bs`, `read_ahead=qd-1` / `write_behind=qd` (seastar defaults otherwise) |
| pinning | `taskset -c 2`, one thread | `taskset -c 2 --smp 1 --cpuset 2`, one shard |
| O_DIRECT | primary row yes (`--buffered` off); buffered row exploratory | always DMA/O_DIRECT on this filesystem |
| latency | per chunk from before `readChunk()`/`write()` to completion | per chunk from before `get()`/`put()` to completion |

Both rows use the same file, same O_DIRECT, same `bs`/`qd`/dataset, same
warmup/measured windows and the same JSON schema (`schema_version 1.0`).

## 7. Manual single-run smoke commands

The runner is usable standalone (short cells; the Lead owns the full campaign):

```bash
# write, O_DIRECT, registered file
env JURING_SKIP_MVN=1 bash bench/juring/run-juring-stream.sh \
    --workload stream_write --file nvme-data/write_files/single_file_bench_w.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 \
    --label w21_smoke --json /tmp/w21_smoke.json

# read, O_DIRECT, registered file
env JURING_SKIP_MVN=1 bash bench/juring/run-juring-stream.sh \
    --workload stream_read --file /mnt/TestData/read_files/single_file_bench.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 --readonly \
    --label w22_smoke --json /tmp/w22_smoke.json

# the seastar side standalone (same CLI, reactor flags, taskset)
LD_LIBRARY_PATH="$PWD/.sysroot/root/usr/lib64" taskset -c 2 \
    bench/seastar/seastar_stream_bench \
    --workload stream_read --file /mnt/TestData/read_files/single_file_bench.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 --readonly \
    --label w22_seastar_smoke --json /tmp/w22_seastar_smoke.json \
    --smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring
```

`--json -` is not supported: the runner always writes one JSON object to
stdout and, with `--json PATH`, also to `PATH`.

## 8. Reproduce

```bash
# 0. prerequisites (Lead/other workstreams)
mvn -o -q -DskipTests compile                 # produces the B2 stream API classes
bench/seastar/build-stream-bench.sh           # seastar stream runner binary

# 1. validate the plan (no side effects)
bench/stream/run-stream-matrix.sh --dry-run

# 2. run the preliminary W21/W22 session
bench/stream/run-stream-matrix.sh             # default session b2-2026-11-13

# 3. aggregate + verdict
python3 bench/stream/analyze_stream.py --session b2-2026-11-13

# 4. cite
#    bench/results/b2-2026-11-13/manifest.txt
#    bench/results/b2-2026-11-13/cell-meta.tsv
#    bench/results/b2-2026-11-13/aggregate/stream.{json,md}
```

The datasets are read-only for the read cells and overwritten in place (never
resized) by the write cells; results are gitignored under `bench/results/`.
