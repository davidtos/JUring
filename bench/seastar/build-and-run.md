# seastar side of the comparison: build & run

This directory contains **seastar `io_tester` configurations** that mirror the
JUring workloads plus the instructions to build and run seastar on a normal
host. It is *not* buildable inside this sandbox: the sandbox has no
cmake/ninja/liburing/boost development packages and the seastar checkout is
read-only. Everything below was verified against the checked-out source at

| item | value |
|---|---|
| path in this sandbox | `/home/david/projects/seastar` (read-only) |
| version | `seastar-25.05.0-1392-g04b1c04d` |
| commit | `04b1c04dcd056d9d9b276ea5c42c9687bc834493` |
| io_tester source | `apps/io_tester/io_tester.cc` (1397 lines) |
| io_tester schema doc | `doc/io-tester.md` |
| io-properties doc | `doc/io-properties-file.md` |
| build doc | `BUILD.md`, `install-dependencies.sh` |

Nothing in this file was executed in the sandbox — the build is for **your
host**, where the packages are installable.

---

## 1. Prerequisites (Fedora)

seastar's own dependency installer covers most of it:

```bash
cd /path/to/seastar
sudo ./install-dependencies.sh          # uses the `fedora` branch: dnf install ...
```

`install-dependencies.sh` does **not** install `yaml-cpp-devel`, although
`cmake/SeastarDependencies.cmake:180` requires yaml-cpp >= 0.5.1 for the apps
(io_tester links `yaml-cpp::yaml-cpp`). Install it explicitly, or cook it
locally:

```bash
# option A: system package
sudo dnf install -y yaml-cpp-devel

# option B: build yaml-cpp inside the seastar build (no root needed)
./configure.py --mode=release --cook yaml-cpp
```

Other packages that matter for this comparison:

```bash
sudo dnf install -y gcc-c++ cmake ninja-build liburing-devel boost-devel \
    fmt-devel hwloc-devel numactl-devel libaio libaio-devel \
    yaml-cpp-devel python3 python3-pyyaml
```

* `liburing-devel` is what enables the `io_uring` reactor backend
  (`FindLibUring 2.0 REQUIRED`).
* Keep `gcc` recent: this seastar supports C++23/26, Fedora 44's default GCC is
  fine. Check `g++ --version` >= 13.
* Kernel >= 5.17 for the `asymmetric_io_uring` backend; the target host runs
  7.0.12, so all four backends are candidates.

## 2. Build (release mode)

```bash
cd /path/to/seastar
./configure.py --mode=release
ninja -C build/release                  # -j$(nproc) is the default; use -j4 if RAM tight
```

Notes:

* `--mode=release` maps to `RelWithDebInfo -O3` with the seastar allocator and
  asserts on — the mode you benchmark with. **Do not benchmark `dev`/`debug`**
  (`BUILD.md`: release is ~2x dev, ~300x debug).
* If `cc1plus` gets OOM-killed, lower parallelism: `ninja -C build/release -j4`.
* `Seastar_APPS` and `Seastar_TESTING` default ON for a top-level checkout, so
  the apps and perf tests are built by default.

Binary paths after the build:

| binary | path | notes |
|---|---|---|
| `io_tester` | `build/release/apps/io_tester/io_tester` | the main comparison tool |
| `iotune` | `build/release/apps/iotune/iotune` | measures the io-properties numbers |
| `fstream_perf` | `build/release/tests/perf/fstream_perf` | buffered/stream write path |
| `ioinfo` | `build/release/apps/io_tester/ioinfo` | dumps the disk configuration seastar detected |

If `fstream_perf` is missing (tests were configured out), build it:

```bash
ninja -C build/release perf_tests        # all perf tests (always works)
# or just this one (target name is usually accepted unqualified):
ninja -C build/release test_perf_fstream
# ...if that target is not found, use the built file path as the target:
ninja -C build/release tests/perf/fstream_perf
```

## 3. Prepare a writable data directory

`io_tester --storage` accepts a **directory**, a **block device**, or
`/dev/null`.

* A directory must be writable. seastar's `open_file_dma()` uses O_DIRECT; on
  ext4/btrfs that works, but io_tester prints a warning because it is tuned for
  XFS. For publication-quality numbers put `$DATA` on a dedicated XFS
  filesystem/partition of the NVMe and note the filesystem in the report.
* The host's 10 GiB JUring file lives on the read-only `/mnt/TestData` mount, so
  writes must go to a new writable directory (NVMe-backed, not tmpfs — tmpfs
  does not support O_DIRECT).
* Disk use = for **each** job in the conf, `data_size` bytes total across
  shards (each job creates one file per shard of `data_size/smp`, aligned up to
  1 MiB). `10GB` in io_tester means 10 GiB = 10737418240 B.

```bash
export DATA=/mnt/<nvme>/seastar-bench      # writable, same device class as JUring's file
mkdir -p "$DATA"
```

Add `--keep-files true` on repeated runs to reuse the created files instead of
re-filling them at startup (first run creates + fills them, which takes a while
at 10 GiB). Without it, files are removed after the run.

## 4. Run io_tester

General form (all options from `src/core/reactor.cc` plus io_tester's own):

```bash
SEASTAR_IO_TESTER=/path/to/seastar/build/release/apps/io_tester/io_tester

$SEASTAR_IO_TESTER \
    --storage "$DATA" \
    --conf bench/seastar/conf/randread_4k_qd64.yaml \
    --duration 10 \
    --smp 1 \
    --cpuset 2 \
    --reactor-backend io_uring \
    --overprovisioned \
    > results-seastar-randread_4k_qd64.yaml
```

io_tester options:

| option | meaning |
|---|---|
| `--storage PATH` | directory, block device, or `/dev/null` |
| `--conf FILE` | YAML job config (see `bench/seastar/conf/`) |
| `--duration SECONDS` | how long to run all jobs (after file creation) |
| `--keep-files true` | keep/reuse test files between runs |
| `--sched-groups FILE` | optional scheduling-group hierarchy YAML |

seastar core options that matter for a fair comparison:

| option | meaning |
|---|---|
| `--smp N` | number of reactor shards (threads) = JUring `--threads N` |
| `--cpuset L` | CPUs to pin shards to, e.g. `2` for `--smp 1`, `2-5` for `--smp 4` |
| `--reactor-backend BE` | `io_uring` (default when available), `linux-aio`, `epoll`, `asymmetric_io_uring` |
| `--io-properties-file FILE` | disk model YAML (template: `bench/seastar/io-properties.example.yaml`) |
| `--overprovisioned` | disable CPU pinning/polling (shared machine); *don't* combine with `--cpuset` benchmarks |
| `--task-quota-ms X` | reactor task-quota; affects latency tails |
| `--async-workers-cpuset L` | required by `asymmetric_io_uring` |
| `--hugepages PATH` | hugetlbfs mount, e.g. `/dev/hugepages` |
| `--lock-memory true` | mlock all memory |
| `--kernel-page-cache true` | allow buffered I/O instead of O_DIRECT (seastar >= 25.05; useful for buffered comparisons) |

Check the exact backend list and defaults with:

```bash
$SEASTAR_IO_TESTER --help | grep -A2 reactor-backend
```

### Backend variants

```bash
# kernel io_uring (seastar's default when available; QUEUE_LEN=200, see
# src/core/reactor_backend.hh:467 - no SINGLE_ISSUER/DEFER_TASKRUN, no public
# registered-file/fixed-buffer API: that is a real asymmetry vs JUring)
--reactor-backend io_uring

# legacy Linux AIO
--reactor-backend linux-aio

# epoll + thread pool fallback (slowest, sanity floor)
--reactor-backend epoll

# asymmetric io_uring: separate async worker threads; needs the extra cpuset
--reactor-backend asymmetric_io_uring --async-workers-cpuset 6-7 --smp 4 --cpuset 2-5
```

### /dev/null overhead run

```bash
$SEASTAR_IO_TESTER --storage /dev/null --conf bench/seastar/conf/randread_4k_qd64.yaml \
    --duration 10 --smp 1 > results-seastar-overhead.yaml
```

This measures io_tester + seastar request-path overhead without I/O. The JUring
equivalent is `--workload nop`.

## 5. Copy-paste: run every config

```bash
#!/usr/bin/env bash
set -euo pipefail
REPO=/path/to/JUring                  # this repo (for bench/seastar/conf)
SEASTAR=/path/to/seastar
IO_TESTER="$SEASTAR/build/release/apps/io_tester/io_tester"
DATA=/mnt/<nvme>/seastar-bench
OUT="$REPO/bench-results/seastar"
mkdir -p "$DATA" "$OUT"
cd "$REPO"

declare -A CONF=(
  [randread_4k_qd1]=bench/seastar/conf/randread_4k_qd1.yaml
  [randread_4k_qd64]=bench/seastar/conf/randread_4k_qd64.yaml
  [randread_4k_qd128]=bench/seastar/conf/randread_4k_qd128.yaml
  [randwrite_4k_qd64]=bench/seastar/conf/randwrite_4k_qd64.yaml
  [seqread_1m_qd8]=bench/seastar/conf/seqread_1m_qd8.yaml
  [seqwrite_1m_qd8]=bench/seastar/conf/seqwrite_1m_qd8.yaml
  [latency_isolated_4k_qd1]=bench/seastar/conf/latency_isolated_4k_qd1.yaml
)

for case in "${!CONF[@]}"; do
  echo "== seastar $case =="
  "$IO_TESTER" \
      --storage "$DATA" \
      --conf "${CONF[$case]}" \
      --duration 20 \
      --smp 1 \
      --cpuset 2 \
      --reactor-backend io_uring \
      --io-properties-file bench/seastar/io-properties.example.yaml \
      --keep-files true \
      | tee "$OUT/$case.yaml"
done
```

(The mixed-job config `multi_job_mixed.yaml` is intentionally not in this loop:
run it separately with a larger `--smp`.)

Raw output is the io_tester **YAML document on stdout**; informational lines
(`Creating ... group`, `Creating initial files...`, `Starting evaluation...`)
precede it and warnings go to stderr. `bench/parse_results.py` skips the
preamble and parses from the first `- shard:` line.

## 6. Buffered / stream path: fstream_perf

`io_tester` only exercises `file::dma_read`/`dma_write` (O_DIRECT). The buffered
stream API (`make_file_output_stream`, write-behind, preallocation) is exercised
by `tests/perf/fstream_perf.cc`:

```bash
cd /mnt/<nvme>/seastar-bench          # runs in CWD, creates testfile.tmp
$SEASTAR/tests/perf/fstream_perf \
    --buffer-size 4096 --concurrency 64 --total-ops 100000 --sloppy-size false \
    --smp 1 --cpuset 2
```

Its output is plain text (`bufsize ops iodepth IOPS`), not YAML, so feed it to
the report by hand. It only writes; there is no buffered-read tool in
tests/perf — use `io_tester` for reads.

## 7. Where results appear

| artifact | location |
|---|---|
| io_tester results | stdout (YAML sequence, one map per shard) — redirect to a file |
| io_tester progress/warnings | stderr |
| exit code | non-zero on config/parse/runtime error |
| files | `$DATA/test-<job>-<shard>` (removed unless `--keep-files true`) |

Then normalize everything (JUring JSON + fio JSON + io_tester YAML) with:

```bash
python3 bench/parse_results.py --dir bench-results --csv bench-results/results.csv
```

---

## 4. The custom runner: `seastar_file_bench`

`apps/io_tester` creates and prefills its own files under `--storage`, which is
impossible on a read-only dataset and perturbs device state between engines. For L0/L1
comparisons against the JUring harness we use a tiny seastar app that opens an
**existing** file and emits the same JSON schema as `bench/juring/JUringFileBench.java`:

- `seastar_file_bench.cc` — `file::dma_read` / `dma_write`, `--qd` fibers each with one
  I/O in flight, random (xorshift) or sequential interleaved offsets, per-op latency
  (before submit → completion), 4 KiB-aligned unregistered buffers (seastar has no fixed
  buffer API), full manifest in the JSON.
- `build-file-bench.sh` — compiles it against `SeaStarRef/build/release/libseastar.a`
  and the `.sysroot` deps (sandbox), or the equivalent host libraries.

Build and run (sandbox build, no root):

```bash
# build seastar first (section 2 / sandbox recipe below), then:
bench/seastar/build-file-bench.sh

SYS=$PWD/.sysroot/root/usr
LD_LIBRARY_PATH=$SYS/lib64 taskset -c 2 bench/seastar/seastar_file_bench \
  --file /mnt/TestData/read_files/single_file_bench.bin \
  --workload randread --bs 4k --qd 64 --seconds 10 --warmup-sec 2 \
  --readonly --seed 20261113 --label randread_4k_qd64 \
  --smp 1 --cpuset 2 --memory 1G --lock-memory 0 \
  --reactor-backend io_uring --json /tmp/seastar_rr4k_qd64.json
```

Notes:

- `--smp 1` is required for now: the runner measures one shard (multi-shard mode is the
  next increment). `--cpuset` should match `taskset`.
- `--memory 1G --lock-memory 0` are needed in the sandbox (8 MiB memlock, no
  hugetlbfs). On a host with hugepages configured you may omit them (and should record
  the hugetlbfs state — F0/F10).
- The reactor backend is logged at startup; verify `Reactor backend: io_uring`.
- Reads on the read-only mount work; write workloads need a writable directory (host).

## 5. Sandbox build recipe (no root, local dependency sysroot)

The sandbox has no writable `/usr` and no sudo. SeaStarRef was built with CMake/Ninja
from `.tools/` against Fedora 44 `-devel` rpms extracted into `.sysroot/root/usr`
(gitignored). Summary:

```bash
# 1. tools (once)
.tools/cmake/bin/cmake --version     # 3.31.6
.tools/ninja/ninja --version         # 1.12.1

# 2. configure + build (env is required: deps are found via the sysroot)
cd SeaStarRef
SYS=$PWD/../.sysroot/root/usr
SYSROOT=$PWD/../.sysroot/root
export CMAKE_PREFIX_PATH=$SYS PKG_CONFIG_PATH=$SYS/lib64/pkgconfig:$SYS/share/pkgconfig
export PKG_CONFIG_SYSROOT_DIR=$SYSROOT CPATH=$SYS/include
export LIBRARY_PATH=$SYS/lib64 LD_LIBRARY_PATH=$SYS/lib64
export PATH=$SYS/bin:$PWD/../.tools/cmake/bin:$PWD/../.tools/ninja:$PATH
./configure.py --mode=release --without-tests --without-demos --disable-dpdk \
  --disable-lttng --disable-gnutls --enable-openssl \
  --compiler /usr/bin/g++ --c-compiler /usr/bin/gcc \
  "--cflags=-I$SYS/include" "--ldflags=-L$SYS/lib64 -Wl,-rpath,$SYS/lib64"
ninja -C build/release -j16

# 3. run anything with the sysroot shared libs on the loader path
LD_LIBRARY_PATH=$SYS/lib64 <binary> ...
```

This is a sandbox workaround, not a supported deployment recipe — on a normal host use
`sudo ./install-dependencies.sh` + `./configure.py --mode=release` + `ninja` (section 1).
