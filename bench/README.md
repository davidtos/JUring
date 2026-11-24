# Running the benchmarks

The harness tables in [BENCHMARK.md](../BENCHMARK.md) come from this directory. The harness drives JUring, `fio` and Seastar through the same workloads on the same file, records the raw output of every repetition, and the analyzers rebuild those tables from it. If you want to check a claim, this is where you start.

The measuring rules, the device-state caveats and the statistics are in [BENCHMARK.md](../BENCHMARK.md); the analyzers in this directory implement them. The decisions about what I chose not to build are summarized in [Limitations](../README.md#limitations). Raw sessions are not committed: every session writes a manifest with the host, command line and hashes, and the tables can be rebuilt from a session directory.

## Identifiers

Cells are named by what they do — `randread-4k-qd64`, `randwrite-4k-qd128`, `seqread-1m-qd8`,
`shards-8`, `stream-write-1m`, `groupcommit-4k-batch32` — and a full pass of the state-controlled
write protocol is a **pass** (`pass1`..`pass8`). One source of truth holds the mapping:
[`bench/analysis/cells.py`](analysis/cells.py), which every analyzer imports and the bash harness
loads through [`bench/cells.sh`](cells.sh). Runs, manifests, tables and figures use these canonical
names throughout.

## What you need

* Linux with `io_uring` enabled (`kernel.io_uring_disabled=0`). A container whose seccomp profile blocks the ring won't run this.
* JDK 25 or newer (the published runs used OpenJDK 26.0.2), Maven, `python3`, and `taskset` from util-linux. `fio` is optional. PyYAML is optional too; the parser has a fallback.
* A 10 GiB file opened with `O_DIRECT`, on the device under test. On this host that is `/mnt/TestData/read_files/single_file_bench.bin`, read-only. Writes need a writable file on the same NVMe; the write harness uses `nvme-data/`.
* An idle machine with the CPU governor on `performance`. Background I/O moves these numbers more than any flag in the harness.
* Seastar, if you want that comparison. It has to be built on your host: see [bench/seastar/build-and-run.md](seastar/build-and-run.md).

## Quick start

The JUring wrapper compiles the harness on first use. Let's start with a smoke run:

```bash
# 3 s measured, 1 s warmup, fixed buffers where RLIMIT_MEMLOCK allows
./bench/juring/run-juring.sh \
    --workload randread --file /mnt/TestData/read_files/single_file_bench.bin \
    --bs 4k --qd 64 --threads 1 --seconds 3 --warmup-sec 1 --readonly \
    --label randread_4k_qd64
```

Then let's run the whole matrix through one driver:

```bash
# JUring + fio; Seastar is skipped unless SEASTAR_BIN is set
./bench/run-comparison.sh

# the read matrix into a directory of your choice
RESULTS_DIR=/tmp/bench-run MATRIX=default ./bench/run-comparison.sh

# the same comparison with Seastar on the host
SEASTAR_BIN=/opt/seastar/build/release/apps/io_tester/io_tester \
DATA_DIR=/mnt/nvme/seastar-bench \
./bench/run-comparison.sh
```

`MATRIX` is `default`, `read` or `all`, or a space- or comma-separated list of case names. The driver writes `manifest.txt`, `results.csv`, `raw/{juring,fio,seastar}/` and `logs/` under `RESULTS_DIR` (default `./bench-results`). The variables I change most are `JURING_FILE`, `JURING_WRITE_FILE`, `RESULTS_DIR`, `DATA_DIR`, `SEASTAR_BIN` and `MATRIX`; the script header lists the rest.

Write cases run only when `MATRIX` includes them and `JURING_WRITE_FILE` points at a writable file. The driver reports skips as skips, not failures.

If `RLIMIT_MEMLOCK` is small, the fixed-buffer path is unavailable and the JUring harness falls back to unregistered arena buffers. It prints a note on stderr and records the mode in the JSON: `registered-fixed`, `arena-unregistered` or `none`. Raise it with `ulimit -l unlimited` in a root shell if you want the fast path at large block sizes.

## What gets compared

### JUring

`bench/juring/run-juring.sh` builds and runs `bench/juring/JUringFileBench.java`. The benchmark has no dependencies beyond `target/classes` and the JDK, and it takes `--workload randread|randwrite|seqread|seqwrite|nop`, `--bs`, `--qd`, `--threads`, `--seconds`, `--warmup-sec`, `--runs`, `--seed`, `--readonly` and a few reporting flags. Each worker has its own ring with `SINGLE_ISSUER | COOP_TASKRUN | DEFER_TASKRUN`, `max(256, 2*qd)` entries, a registered file and fixed buffers where they fit. One JSON object per invocation comes out, with the config, the host manifest, per-run numbers and an aggregate.

### fio

`fio` is the neutral reference. The harness runs it through its `io_uring` engine with the same block size and queue depth as the JUring case, and with registration matched:

```bash
--ioengine=io_uring --direct=1 --registerfiles=1 --fixedbufs=1
```

fio can't set JUring's three ring flags or fuse submit and wait, so this is a capability comparison, not an equal-config race. BENCHMARK.md also reports a batching sweep (`--iodepth_batch_submit=1,8,16,32`), a libaio reference and a `--sqthread_poll=1` bracket, so you can see how sensitive the reference is to its configuration. The exact commands are in the runner scripts under [`bench/read-write/`](read-write/) and in [BENCHMARK.md](../BENCHMARK.md#how-to-reproduce).

### Seastar

There are two Seastar paths. `bench/seastar/seastar_file_bench`, a small app that opens an existing file and emits the same JSON as the JUring harness, is how the published read and write comparisons run: same file, same offsets, same warmup. The second is `io_tester` with the YAML configs under `bench/seastar/conf/`, which `run-comparison.sh` uses. `io_tester` creates and prefills its own files under `--storage`, so it needs a writable directory **on the device under test** (the repo checkout is normally on the OS disk), it has no warmup phase, and it cannot open the aged read dataset. Treat that path as a same-device bracket, not the same-file comparison. The prebuilt `io_tester` links against the repo sysroot, and `run-comparison.sh` puts `.sysroot/root/usr/lib64` on `LD_LIBRARY_PATH` for it.

Do not pass `bench/seastar/io-properties.example.yaml` as-is: it is a template with placeholder numbers and an example mountpoint, and Seastar aborts if that mountpoint does not exist (or silently uses a fictional scheduler model if it does). `run-comparison.sh` leaves `SEASTAR_IO_PROPS` empty by default so Seastar probes the device; pass a file measured with `iotune` on this host when you want the model.

Seastar's ring has no taskrun flags, no registered files and no fixed buffers. That asymmetry is real, and it stays until Seastar exposes those calls, so I report Seastar as a bracket rather than an equal opponent.

The cases the driver runs:

| case | JUring | fio | Seastar `io_tester` |
|---|---|---|---|
| `randread_4k_qd1` | `randread` bs 4k qd 1 | `--rw=randread --bs=4k --iodepth=1` | `conf/randread_4k_qd1.yaml` |
| `randread_4k_qd64` | `randread` bs 4k qd 64 | `--rw=randread --bs=4k --iodepth=64` | `conf/randread_4k_qd64.yaml` |
| `randread_4k_qd128` | `randread` bs 4k qd 128 | `--rw=randread --bs=4k --iodepth=128` | `conf/randread_4k_qd128.yaml` |
| `seqread_1m_qd8` | `seqread` bs 1m qd 8 | `--rw=read --bs=1m --iodepth=8` | `conf/seqread_1m_qd8.yaml` |
| `randwrite_4k_qd64` | `randwrite` bs 4k qd 64 | `--rw=randwrite --bs=4k --iodepth=64` | `conf/randwrite_4k_qd64.yaml` |
| `seqwrite_1m_qd8` | `seqwrite` bs 1m qd 8 | `--rw=write --bs=1m --iodepth=8` | `conf/seqwrite_1m_qd8.yaml` (`overwrite`) |
| `nop_qd64` | `nop` qd 64 (overhead only) | — | — (`--storage /dev/null` is the closest) |

`conf/latency_isolated_4k_qd1.yaml` and `conf/multi_job_mixed.yaml` are for the latency probe and the mixed-job run; neither is in the default driver matrix. Threads map as JUring `--threads N` to Seastar `--smp N` to fio `--numjobs N`, and queue depth maps as `--qd` to `shard_info.parallelism` to `--iodepth`.

## Where results go

`bench/results/` is gitignored. It holds the session directories behind the published tables: per-repetition JSON and YAML, per-run metadata, manifests, device series, logs and aggregates. The directory is not committed because the files are large and specific to this host. Each session's manifest records the host, command line and hashes; [BENCHMARK.md](../BENCHMARK.md) explains what is committed and what the numbers cover.

`run-comparison.sh` defaults to `./bench-results`; set `RESULTS_DIR=bench/results/<name>` to keep a run next to the existing sessions. The per-harness drivers write under `bench/results/<session>/`; `bench/write/` takes the session directory as an argument instead.

`bench/parse_results.py` reads all three formats and writes a table plus CSV:

```bash
python3 bench/parse_results.py                       # scans ./bench-results
python3 bench/parse_results.py --dir /path/to/run --csv /path/to/run/results.csv
```

The CSV columns are `case, engine, workload, bs, qd, threads, iops, mibs, p50_us, p99_us, p999_us, max_us, ops, errors, buffer_mode, source`. One corrupt file produces a warning, not a crash.

## Analyzers

The analyzers are thin CLIs: each one owns its campaign's policy — which cells, which bar, which verdict — and writes the tables BENCHMARK.md cites. What more than one needs lives in `bench/analysis/`: `cells.py` (the cell/pass identifier map), `stats.py` (Student-t intervals, paired ratios, gate verdicts), `jsonio.py` (tolerant cell-JSON and manifest loading), `md.py` (the number and markdown formats) and `cli.py` (session resolution and output writing). The package docstring explains the two statistics conventions it keeps side by side on purpose, because the published tables were produced with each. The write campaign's state-controlled 3-way rerun has its own runner, `bench/write/run-write-3way.sh`, and analyzer, `bench/write/analyze_write_3way.py`, with the frozen options and gates in the runner and analyzer; the interleaved fallback used when a pass will not hold homogeneous is `bench/write/run-write-interleaved.sh` with `bench/write/analyze_write_interleaved.py`. [bench/write/README.md](write/README.md) documents both campaigns, and [BENCHMARK.md](../BENCHMARK.md#the-2026-11-23-write-rerun-still-no-ranking) reports the results. The figures embedded in README.md and BENCHMARK.md are generated from the same sessions by `bench/write/plot_write_modes.py` and `bench/read-write/plot_read_claims.py`, using the shared SVG primitives in `bench/analysis/svg.py`.

`selftest.py` is the regression harness for that package and the analyzers:
```bash
python3 bench/analysis/selftest.py            # unit tests (the default)
python3 bench/analysis/selftest.py --unit     # the same, named explicitly
```

`--unit` checks the shared helpers — Student-t tables and intervals, descriptive statistics, paired ratios, gate verdicts, markdown and JSON plumbing, CLI session resolution and the cell/pass identifiers — against hand-computed values and cross-checks. The analyzers themselves rebuild a campaign's tables from a session directory; the session data is gitignored. The Python is linted with `uvx ruff check bench`; the rules live in `ruff.toml` at the repo root.

## The per-harness docs

| document | what it covers |
|---|---|
| [bench/shard/README.md](shard/README.md) | Scaling from one ring to eight, per-shard balance, and what the per-ring metrics cost. |
| [bench/stream/README.md](stream/README.md) | `JuringInputStream`/`JuringOutputStream` against Seastar's stream API, plus the durability and group-commit runs. |
| [bench/write/README.md](write/README.md) | The write matrix on the writable NVMe file. Long sessions, exclusive use of the device. |
| [bench/host-setup/README.md](host-setup/README.md) | Host checks: hugepages against THP, memlock, and the state-controlled write chain. |
| [bench/seastar/build-and-run.md](seastar/build-and-run.md) | Building Seastar, running `io_tester`, and running the same-file runners. |

BENCHMARK.md's "How to reproduce" section lists the analyzer command for each published table. The runners themselves are in `bench/juring/`; `bench/blocking/run-scale.sh` is the opt-in million-thread scale run, and it checks correctness and bounded resources rather than throughput.

## Known limits

* Seastar has to be built on your host. The checked-in runners were built in this workspace against its `.sysroot`, so rebuild them before you trust their numbers elsewhere.
* One workload per JUring process. For mixed load you run two processes, not one process with two workloads.
* Fixed buffers need `RLIMIT_MEMLOCK` headroom. Without it the read path still works, but it isn't the registered fast path.
* Writes need the writable `nvme-data/` mount of the data NVMe. `/mnt/TestData` is never written. Write numbers on this consumer NVMe move with SLC burst and folding, which is why BENCHMARK.md publishes no write ranking.
* The numbers are evidence for this repository's claims, not a promise for your hardware. Run them on your machine before you quote them, and compare within one session, not across days.
