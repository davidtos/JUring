# Seastar: build and run

`bench/seastar/` is the Seastar side of the comparison: `io_tester` job configs that mirror the JUring workloads, two small runners that open an existing file, and the scripts that build them. The Seastar numbers in [BENCHMARK.md](../../BENCHMARK.md) come from a release build of Seastar 25.05 (`seastar-25.05.0-1392-g04b1c04d`, commit `04b1c04dcd056d9d9b276ea5c42c9687bc834493`).

I couldn't build Seastar inside the sandbox. There were no cmake, ninja, liburing or boost development packages, and the checkout was read-only. The commands below are for your host.

## Build

```bash
cd /path/to/seastar
sudo ./install-dependencies.sh

# io_tester links yaml-cpp; install-dependencies.sh doesn't install it
sudo dnf install -y yaml-cpp-devel

./configure.py --mode=release
ninja -C build/release
```

On Fedora, `install-dependencies.sh` uses its `fedora` branch. GCC 13 or newer is fine; the published runs used 16.1.1. The kernel needs 5.17 or newer for the `asymmetric_io_uring` backend, and the host ran 7.0.12.

`--mode=release` is the mode to measure: RelWithDebInfo `-O3` with the Seastar allocator and asserts on. `dev` is about 2x slower and `debug` about 300x, so don't compare either with a release number. If `cc1plus` gets OOM-killed, lower the parallelism: `ninja -C build/release -j4`. Seastar apps and tests default on for a top-level checkout.

Binaries after the build:

* `build/release/apps/io_tester/io_tester`, the comparison tool
* `build/release/apps/iotune/iotune`, measures the io-properties numbers
* `build/release/apps/io_tester/ioinfo`, prints the disk configuration Seastar detected
* `build/release/tests/perf/fstream_perf`, the stream write path

If `fstream_perf` is missing because tests were configured out, run `ninja -C build/release perf_tests`.

## Run io_tester

io_tester creates one file per shard under `--storage` and prefills it, so point `--storage` at a writable directory on the device under test. `--keep-files true` reuses those files between runs. Let's run one config, the queue-depth-64 random read:

```bash
IO_TESTER=/path/to/seastar/build/release/apps/io_tester/io_tester
DATA=/mnt/nvme/seastar-bench
mkdir -p "$DATA"

$IO_TESTER \
    --storage "$DATA" \
    --conf bench/seastar/conf/randread_4k_qd64.yaml \
    --duration 10 \
    --smp 1 --cpuset 2 \
    --reactor-backend io_uring \
    --keep-files true \
    > results-seastar-randread_4k_qd64.yaml
```

The configs in `bench/seastar/conf/` are `randread_4k_qd1.yaml`, `randread_4k_qd64.yaml`, `randread_4k_qd128.yaml`, `seqread_1m_qd8.yaml`, `randwrite_4k_qd64.yaml`, `seqwrite_1m_qd8.yaml`, `latency_isolated_4k_qd1.yaml` and `multi_job_mixed.yaml`. `seqwrite_1m_qd8` uses type `overwrite`, because io_tester has no sequential-write type. `--smp N` is the shard count and matches JUring `--threads N`.

Two limits of `io_tester` matter for the comparison. It always creates and prefills its own files under `--storage` (a read case writes the dataset first), so it cannot measure the aged read dataset the way `fio` and JUring do, and `--storage` must be on the device under test. It also has no warmup/ramp phase: `--duration` is measured from the first request. Use `bench/seastar/seastar_file_bench` (below) for the same-file comparison; `run-comparison.sh` uses `io_tester` and records both caveats in the manifest.

`bench/seastar/io-properties.example.yaml` is a **template**, not a config to pass as-is: its numbers are placeholders and its mountpoint is an example. Seastar stat()s the mountpoint at startup, so a missing path aborts the run, and a path that exists but was not measured silently sets the I/O scheduler model to fiction. Measure the filesystem that backs `--storage` with `build/release/apps/iotune/iotune` and pass the result with `--io-properties-file`. Leave the option out to let Seastar probe the device.

`--reactor-backend` also takes `linux-aio` and `epoll`; `asymmetric_io_uring` needs `--async-workers-cpuset` as well.

## Same-file runs

io_tester can't target a read-only dataset, so the runners here open an existing file and emit the same JSON as the JUring harness. Build and run the file runner:

```bash
bench/seastar/build-file-bench.sh

taskset -c 2 bench/seastar/seastar_file_bench \
  --file /mnt/TestData/read_files/single_file_bench.bin \
  --workload randread --bs 4k --qd 64 --seconds 10 --warmup-sec 2 \
  --readonly --seed 20261113 --label randread_4k_qd64 \
  --smp 1 --cpuset 2 --memory 1G --lock-memory 0 \
  --reactor-backend io_uring --json /tmp/seastar_rr4k_qd64.json
```

`build-file-bench.sh` links against `SeaStarRef/build/release/libseastar.a`, the in-repo Seastar checkout built the same way as above; `SEASTAR_REF`, `SYSROOT` and `CXX` override the paths. The checked-in binary was built against the repo's `.sysroot`, so rebuild it for your host. For shard scaling use `--smp N` with a matching `--cpuset`, for example `--smp 8 --cpuset 1,2,4,6,8,10,12,14`. The `--memory 1G --lock-memory 0` pair was needed in the sandbox; on a host with hugepages you can leave them out. The stream comparison has its own runner, built by `build-stream-bench.sh`.

## Run the comparison

Set `SEASTAR_BIN` and `DATA_DIR` and let the driver run the matrix. `DATA_DIR` must be a writable directory on the device under test: `io_tester` creates and prefills its own 10 GiB files there. Leaving it under the repo normally puts those files on the OS disk, not the dataset NVMe. The prebuilt `io_tester` links against the repo sysroot, so `run-comparison.sh` adds it to `LD_LIBRARY_PATH` when `SEASTAR_BIN` is set. Do not pass the placeholder io-properties template:

```bash
SEASTAR_BIN=$PWD/SeaStarRef/build/release/apps/io_tester/io_tester \
DATA_DIR=$PWD/nvme-data/iotester-storage \
./bench/run-comparison.sh
```

This is the `io_tester` bracket: fresh generated files, no warmup, same device and size. The same-file Seastar numbers in BENCHMARK.md come from `bench/seastar/seastar_file_bench`, which opens the aged dataset and emits the JUring JSON; the `bench/m1/` read matrix and `bench/write/` campaign drive it. The seastar write path through that runner is opt-in in the write harness (`--allow-seastar-write`).

`python3 bench/parse_results.py --dir bench-results --csv bench-results/results.csv` then normalizes the io_tester YAML and the runners' JSON together with the JUring and fio output.
