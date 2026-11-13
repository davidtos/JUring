# bench/ — JUring vs seastar comparison harness

A self-contained harness to compare **JUring**'s io_uring file I/O against
**seastar**'s `io_tester` (`file::dma_read`/`dma_write`), with **fio** as a neutral
reference. It is the runnable counterpart to the methodology docs: same
workloads, same block sizes, same queue depths, same metrics (IOPS, MiB/s,
p50/p99/p99.9/max latency), one CSV.

| path | what it is |
|---|---|
| `juring/JUringFileBench.java` | standalone benchmark (no JMH, no deps), compiles against `target/classes` |
| `juring/run-juring.sh` | compile + run wrapper (`mvn -o compile`, `javac`, `java --enable-native-access=ALL-UNNAMED`) |
| `seastar/seastar_file_bench.cc` | seastar runner on an **existing** file (same JSON schema as JUringFileBench) |
| `seastar/build-file-bench.sh` | build the runner against `SeaStarRef/build/release` |
| `seastar/conf/*.yaml` | seastar `io_tester` job configs mirroring the JUring workloads |
| `seastar/build-and-run.md` | build + run instructions (host and sandbox), custom runner, sandbox sysroot recipe |
| `seastar/io-properties.example.yaml` | template for seastar's `--io-properties-file` (disk model) |
| `run-comparison.sh` | driver: runs JUring + fio (+ seastar if `SEASTAR_BIN`), writes raw results, calls the parser |
| `parse_results.py` | normalizes JUring JSON / fio JSON / io_tester YAML into a table + `results.csv` |
| `results/` | owned by the baseline task; **not** written by this harness (driver defaults to `./bench-results`) |
| `m1/` | M1 read-path session harness: `lib.sh` + `run-read-session.sh` and its phase scripts (W1-W4, W9-W11, W15/W16, fio brackets, perf cells, io_tester cross-check) + `analyze_m1.py` |
| `write/` | Step-2 write-path harness: write-file creation, D1 preconditioning, W5-W8/W12-W14 driver, 1 s device time-series sampler |

## What runs where

* **Here (this sandbox)**: JUring, fio **and seastar** run against `/mnt/TestData`
  (read-only mount, 10 GiB file). SeaStarRef was built locally from the in-repo
  `SeaStarRef/` checkout using a no-root dependency sysroot (`.sysroot/`, `.tools/`);
  see [`seastar/build-and-run.md`](seastar/build-and-run.md). For L0/L1 comparisons use
  `seastar/seastar_file_bench` on the same file (io_tester creates its own files and
  cannot target a read-only dataset). The driver still skips seastar unless
  `SEASTAR_BIN` is set.
* **On the user's host**: everything, including seastar; see
  [`seastar/build-and-run.md`](seastar/build-and-run.md).
* **Writes**: impossible on `/mnt/TestData`. Write cases only run when
  `JURING_WRITE_FILE` (JUring/fio) or `DATA_DIR` (seastar) point at writable
  storage.

## M1 session harness (`bench/m1/`)

The M1 read-path comparison is a frozen, scripted session rather than ad-hoc
commands. It now supersedes the earlier per-result scripts under
`bench/results/2026-11-13/scripts/` for anything that must be citable.

| file | role |
|---|---|
| `lib.sh` | shared helpers: preflight gate (dataset, io_uring, governor, loadavg, foreign-process), per-cell logging, device-sector deltas, `cell-meta.tsv` row, engine command builder |
| `run-read-session.sh` | runs the six phases below in order (30 s settle between phases) and writes `logs/session.log` |
| `run-read-matrix.sh` | W1-W4: 4K randread QD1/16/64/128, 10 s warmup + 30 s, R=5, juring/seastar/fio-matched |
| `run-fio-brackets.sh` | fio config brackets at QD64 (+QD128 default/libaio): default, batch8/16/32, sqpoll, libaio, R=3 |
| `run-latency-matrix.sh` | W15/W16: QD1/QD64, 10 s warmup + 120 s, R=5, three-way |
| `run-perf-cells.sh` | cycles/instructions/syscalls per op at QD1/QD64, R=3: `perf stat -p <process tree>` attached for 30 s inside a 40 s measured phase |
| `run-seqread-matrix.sh` | W9-W11: 1 MiB seqread QD1/8/32, 10 s warmup + 30 s, R=5, three-way |
| `run-iotester-check.sh` | io_tester cross-check on writable `nvme-data/` (creates+prefills 10 GiB shard files, so it runs last), + `/dev/null` overhead probe |
| `run-write-first-block.sh` | Step-2 kickoff: create the 10 GiB write file, validate seastar's randwrite path, run the trimmed W7 block |
| `run-locality-check.sh` | aged-vs-fresh dataset control (shows read performance depends on data state; report §11b) |
| `normalize-write-session.py` | re-reads the write raw JSON and emits `cells-normalized.csv` in us (fixes the write harness fio p99 ns/units defect) |
| `repair-w10-w11-latency.sh` | re-measures the 10 JUring cells affected by the harness latency-attribution defect (non-fixed 1 MiB reads); archives the invalid JSONs outside the session dir |
| `analyze_m1.py` | aggregation: CIs, rep-paired ratios, state gating (p99<600/1200 us + IOPS anomaly rule), M1 gate table (methodology §7), device-byte gate |
| `json_summary.py` / `perf_summary.py` | small helpers used by the scripts |

Session artifacts live in `bench/results/m1-YYYY-MM-DD/`:
`cells/<case>__<engine>__<variant>__bs<bs>__qd<qd>__t1__r<rep>.json`,
`logs/`, `perf/`, `iotester/`, `cell-meta.tsv`, `perf-cells.tsv`,
`iotester-check.tsv`, `manifest.txt`, `notes.md`, `aggregate/m1.*`.

```bash
# full read-path session (~3 h of machine time; run on an idle machine)
./bench/m1/run-read-session.sh

# aggregate + M1 gate table (writes <session>/aggregate/m1.*)
python3 bench/m1/analyze_m1.py --dir bench/results/m1-2026-11-13
```

Engine symmetries/disclosures enforced by the harness: O_DIRECT everywhere,
`taskset -c 2`, one fresh process per rep, seed 20261113, fio reference
`--registerfiles=1 --fixedbufs=1`; at 1 MiB the fio fixed-buffer path is
unavailable above QD~6 (`RLIMIT_MEMLOCK=8 MiB`) and JUring itself falls back to
unregistered arena buffers, so fio uses `regfile` and the per-cell JSON
`io_buffer_mode` records the truth. `seastar_file_bench` latency uses a uniform
reservoir (Algorithm R) so 120 s percentiles cover the whole window.

## Write-path harness (`bench/write/`)

`create-write-file.sh` (10 GiB non-sparse O_DIRECT file on `nvme-data/`),
`precondition.sh` (D1: 3x file size 4K randwrite QD64, fsync),
`run-write-cells.sh` (W5-W8/W12-W14, R=3, D1/D2/D3/D6/D7 encoded, seastar write
path opt-in via `--allow-seastar-write`), `sample-device-iops.sh` (1 s
`/sys/block/nvme1n1/stat` series). See `bench/write/README.md`.

`nvme-data/` must exist as a writable bind mount of the data NVMe
(`/dev/nvme1n1p1`); `/mnt/TestData` stays read-only and is never written.

## Prerequisites

* JDK 25+ (this machine: JDK 26.0.2), Maven, `python3` (PyYAML optional — the
  parser has a built-in fallback), `fio` optional, `taskset` (util-linux).
* JUring `target/classes` populated: `mvn -o -q -DskipTests compile` (the wrapper
  does this).
* A 10 GiB O_DIRECT-friendly benchmark file on the **same device** for both
  engines. On this host: `/mnt/TestData/read_files/single_file_bench.bin`
  (read-only mount, so read tests only).
* Idle machine: the numbers move a lot under even light background I/O. Nothing
  else (no fio matrix, no seastar, no `mvn`) should run while measuring.

## Quickstart (this machine, read path only)

```bash
# single smoke run (3 s measured + 1 s warmup)
./bench/juring/run-juring.sh \
    --workload randread --file /mnt/TestData/read_files/single_file_bench.bin \
    --bs 4k --qd 64 --threads 1 --seconds 3 --warmup-sec 1 --readonly \
    --label randread_4k_qd64

# fixed-buffer fast path depends on RLIMIT_MEMLOCK (8 MiB here); large block
# sizes automatically fall back and say so:
#   [juring] note: RLIMIT_MEMLOCK=8388608 B ... using unregistered arena buffers

# full driver (JUring + fio; seastar only if SEASTAR_BIN is set)
./bench/run-comparison.sh
RESULTS_DIR=/tmp/bench-run MATRIX=default ./bench/run-comparison.sh

# seastar on the user's host
SEASTAR_BIN=/opt/seastar/build/release/apps/io_tester/io_tester \
DATA_DIR=/mnt/nvme/seastar-bench \
./bench/run-comparison.sh
```

`run-comparison.sh` writes `$RESULTS_DIR/{manifest.txt,results.csv,raw/,logs/}`.
`MATRIX` is `default`/`read` (read cases + nop), `all` (adds write cases), or a
space/comma list of case names.

## Matrix

| case | JUring | fio | seastar `io_tester` |
|---|---|---|---|
| `randread_4k_qd1` | `randread` bs 4k qd 1 | `--rw=randread --bs=4k --iodepth=1` | `conf/randread_4k_qd1.yaml` |
| `randread_4k_qd64` | `randread` bs 4k qd 64 | `--rw=randread --bs=4k --iodepth=64` | `conf/randread_4k_qd64.yaml` |
| `randread_4k_qd128` | `randread` bs 4k qd 128 | `--rw=randread --bs=4k --iodepth=128` | `conf/randread_4k_qd128.yaml` |
| `seqread_1m_qd8` | `seqread` bs 1M qd 8 | `--rw=read --bs=1m --iodepth=8` | `conf/seqread_1m_qd8.yaml` |
| `randwrite_4k_qd64` | `randwrite` bs 4k qd 64 | `--rw=randwrite --bs=4k --iodepth=64` | `conf/randwrite_4k_qd64.yaml` |
| `seqwrite_1m_qd8` | `seqwrite` bs 1M qd 8 | `--rw=write --bs=1m --iodepth=8` | `conf/seqwrite_1m_qd8.yaml` (`overwrite`) |
| `nop_qd64` | `nop` qd 64 (overhead) | — | — (`--storage /dev/null` is the closest) |
| latency probe | `randread` bs 4k qd 1 | `--rw=randread --bs=4k --iodepth=1` | `conf/latency_isolated_4k_qd1.yaml` |
| mixed | two processes | — | `conf/multi_job_mixed.yaml` |

Threads: JUring `--threads N` ↔ seastar `--smp N` ↔ fio `--numjobs N`.
Queue depth: JUring `--qd N` ↔ `shard_info.parallelism: N` ↔ fio `--iodepth N`.
Block size: `--bs` ↔ `shard_info.reqsize` ↔ `--bs`.
Sequential semantics: both wrap at EOF (`seq_overflows` / `file_size_overflows`).

Caveats when reading the table:

* seastar's read cases create their **own** files under `--storage` (one per shard,
  `data_size/smp` each) — they cannot use the read-only `/mnt/TestData` file.
* fio uses its own PRNG (`--randrepeat=1`, deterministic) and its own default
  offset distribution; JUring uses the fio-style XorShift from `DatasetManager`.
  Distributions match in shape (uniform over whole blocks), not in bit sequence.
* fio reports `clat` (completion latency); JUring/per-op latency includes SQ
  preparation + submission; seastar measures issue→completion per request.
  Compare like for like at the workload level, not sub-microsecond.
* Write-test numbers on consumer NVMe are unstable (SLC cache, GC). Use rests
  between runs and report variance.

## JUringFileBench CLI

```
--workload randread|randwrite|seqread|seqwrite|nop
--file PATH        existing file (O_DIRECT). Required for I/O workloads.
--size SIZE        dataset bytes to use (default whole file; reads must be <=
                   file size; writes extend the file with truncate() if larger).
                   Suffixes: 4096, 4k, 1m, 10g, 1t; 10GB == 10 GiB here.
--bs SIZE          block size, multiple of 512 (default 4096)
--qd N             per-thread queue depth / io_tester parallelism (default 64)
--threads N        worker threads, each with its own ring (default 1)
--seconds S        measured duration per run (default 10)
--warmup-sec S     warmup before each measured phase (default 1)
--runs N           repetitions (default 1); each run gets fresh rings/fds
--readonly         require read-only operation (error with write workloads)
--json PATH        also write the JSON report to PATH
--label TEXT       case label (default e.g. randread_4k_qd64)
--latency-samples N  per-thread latency reservoir (default 1,000,000)
--seed N           base PRNG seed (hex ok)
--engine juring    accepted for symmetry; JUring only
--verbose          per-thread setup diagnostics on stderr
--help
```

Implementation details that matter for interpreting results:

* Each worker owns one ring: `IORING_SETUP_SINGLE_ISSUER | COOP_TASKRUN |
  DEFER_TASKRUN`, entries `max(256, 2*qd)`, the file registered
  (`registerFiles`) and `qd` fixed buffers registered (`registerBuffers`) where
  possible.
* Pipeline: fill to `qd`, then `submitAndWaitForBatchResult(1, qd, consumer)`;
  reap and top up. Single completion callback, zero per-op allocation (the
  `Result` object is reused by JUring; latency timestamp slots are primitive
  arrays).
* Offsets: random = XorShift block index over `[0, size-bs]`; sequential = `0, bs,
  2bs, …`, wrap at the end of the (per-thread) region. With `--threads N > 1`
  sequential workloads partition the dataset into N contiguous regions, mirroring
  io_tester's one-file-per-shard layout; random workloads use the whole region
  from every thread.
* Latency: `System.nanoTime()` immediately before SQE preparation and at
  completion; samples go into a bounded reservoir (uniform replacement beyond the
  cap), percentiles by sorting the reservoir; `avg` is exact. Completions after the
  stop instant are excluded from ops/latency, as io_tester does.
* **Latency bookkeeping must not be keyed on `user_data & slotMask`.** For fixed
  buffers the library encodes a monotonic op id, but for non-fixed `READ_EXT` it
  encodes the buffer address; arena buffers are 4096-aligned, so low bits collide.
  The harness indexes by buffer and keeps `udOfBuf[qd]` (see the 2026-11-13 review
  fix). It also warns when the measured mean latency violates Little's law
  (qd/IOPS) by >25 %, which is the signature of mis-attributed timestamps.
* `nop` uses `IORING_OP_NOP` and reports IOPS/latency only (`MiB/s` = 0).

## JSON output schema (`schema_version` 1.0)

Exactly one JSON object on stdout (all diagnostics on stderr), e.g.:

```json
{
  "schema_version": "1.0",
  "engine": "juring",
  "status": "ok",
  "error": null,
  "label": "randread_4k_qd64",
  "timestamp_utc": "2026-11-13T11:09:00Z",
  "argv": ["--workload", "randread", "..."],
  "manifest": {
    "juring_git_commit": "4526338e…", "juring_git_dirty": "true",
    "juring_git_version": "…", "juring_classes": "/home/…/target/classes",
    "java_version": "26.0.2", "java_vm_version": "…", "java_home": "…",
    "os_name": "Linux", "os_arch": "amd64", "os_version": "…",
    "kernel": "7.0.12-201.fc44.x86_64", "hostname": "…",
    "cpu_model": "AMD Ryzen 9 3950X 16-Core Processor",
    "logical_cpus": 32, "cpus_allowed": "0-31", "max_heap_bytes": 16835936256
  },
  "config": {
    "workload": "randread", "file": "/mnt/TestData/read_files/single_file_bench.bin",
    "file_size_bytes": 10737418240, "dataset_bytes": 10737418240,
    "bs": 4096, "qd": 64, "threads": 1, "seconds": 10.0, "warmup_sec": 1.0,
    "runs": 1, "readonly": true, "direct_io": true,
    "ring_entries": 256, "ring_flags": ["IORING_SETUP_SINGLE_ISSUER", "…"],
    "io_buffer_mode": "registered-fixed",
    "registered_files": 1, "registered_buffers": 64,
    "latency_samples_cap": 1000000, "seed": 0
  },
  "runs": [
    {
      "run": 0, "elapsed_sec": 3.0009, "ops": 975076, "bytes": 3993911296,
      "iops": 324977.9, "mibs": 1269.4, "errors": 0, "seq_overflows": 0,
      "latency_us": {"samples": 975076, "recorded": 975076, "sampled": false,
                     "min": 2.3, "avg": 171.9, "p50": 171.9, "p90": 320.0,
                     "p95": 380.0, "p99": 475.9, "p999": 624.0, "max": 2412.1},
      "threads": [ { "thread": 0, "…": "same shape per worker" } ]
    }
  ],
  "aggregate": {
    "runs": 1, "elapsed_sec": 3.0009, "ops_total": 975076,
    "bytes_total": 3993911296, "errors_total": 0, "seq_overflows_total": 0,
    "iops": {"mean": 324977.9, "stdev": 0.0, "min": …, "max": …},
    "mibs": {"mean": 1269.4, "stdev": 0.0, "min": …, "max": …},
    "latency_us": {"method": "mean of per-run percentiles (max/min across runs)",
                   "avg": …, "p50": …, "p99": …, "p999": …, "min": …, "max": …}
  }
}
```

Units: `iops` = ops/s, `mibs` = MiB/s (2^20 B/s), all latencies **microseconds**.
The manifest additionally records `loadavg_at_start` / `loadavg_at_end` and
`cpu_governor`, so a contaminated run can be spotted afterwards.
`io_buffer_mode`: `registered-fixed` (fast path), `arena-unregistered` (memlock
fallback, still O_DIRECT + 4096-aligned buffers) or `none` (nop).
`status: "error"` documents a failed run (`error` field, `aggregate` may be null).

## parse_results.py

```bash
python3 bench/parse_results.py                       # scans ./bench-results
python3 bench/parse_results.py --dir /path/to/run --csv /path/to/run/results.csv
python3 bench/parse_results.py raw/juring/*.json raw/fio/*.json raw/seastar/*.yaml
```

Auto-detects JUring JSON, fio JSON and io_tester YAML (skips io_tester's
preamble lines), aggregates seastar jobs across shards (IOPS/throughput summed,
latency = max across shards, `threads` = shard count), and prints a table +
writes CSV with columns:

```
case, engine, workload, bs, qd, threads, iops, mibs, p50_us, p99_us, p999_us,
max_us, ops, errors, buffer_mode, source
```

Rows are sorted by case then engine (`juring`, `fio`, `seastar`). PyYAML is used
if installed; otherwise a built-in parser handles io_tester's output. One corrupt
file produces a warning, not a crash.

No CPU-utilization column is produced: none of the three tools reports it. Use
`pidstat`/`perf stat` around the run if you need CPU per IOPS.

## Adding a workload

1. Add a mode to `JUringFileBench.java` (`Mode` enum + `fill()` case + position
   logic) and the `--workload` validator.
2. Add a seastar conf under `seastar/conf/` using the exact schema keys from
   `apps/io_tester/io_tester.cc` (`name`, `type`, `shards`, `data_size`,
   `shard_info{parallelism,reqsize,shares,think_time,...}`, `options{...}`); see
   the existing files for the mapping comments.
3. Add a case name to `case_meta()` and the matrix lists in `run-comparison.sh`
   (`workload bs qd threads seastar-conf fio-rw`).
4. Document the mapping in the matrix table above.

## Known limitations

* seastar is not built or runnable in this sandbox — the seastar half is
  configs + instructions, not measured numbers. Do not present seastar numbers
  produced here.
* Only one workload per JUring process; run two processes for mixed load.
* Latency sampling is a reservoir (`--latency-samples`, 8 MB/thread at the
  default), and `nanoTime()` overhead is part of each op's measurement
  (negligible at 4K+ I/O, visible for `nop`).
* `RLIMIT_MEMLOCK` limits registered fixed buffers; the harness detects this and
  falls back to unregistered O_DIRECT buffers, recording the mode in JSON. For
  the fixed-buffer fast path raise the limit (`ulimit -l unlimited` in a root
  shell/systemd unit).
* Write tests cannot touch `/mnt/TestData` (read-only); none of the committed
  smoke numbers include writes on the NVMe under test.
* `--threads N > 1`: one file is shared by N rings; seastar uses N files (one per
  shard). Throughput comparisons at N>1 are approximate.
* Results reflect machine state; always record `manifest.txt` and run on an idle
  machine.
