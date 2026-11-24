# Benchmark harness sanity check — 2026-11-23

Before re-running the comparison I audited how `fio` and Seastar are invoked, because a reference
that is configured wrong turns a benchmark into a press release. The audit was done on the host
(AMD Ryzen 9 3950X, Fedora 44, kernel 7.0.12, `fio 3.40`, `RLIMIT_MEMLOCK` 8 MiB) against the
checked-in harness. Full evidence (commands, JSON snippets, syscall counts) is in the session
notes; this file records what was checked and what changed.

## fio

- Every flag used by the harness is valid for `fio 3.40` (`--ioengine=io_uring --direct=1
  --registerfiles=1 --fixedbufs=1 --readonly --time_based --runtime --ramp_time --iodepth
  --numjobs --group_reporting --randrepeat --clocksource --percentile_list`).
- `--registerfiles` and `--fixedbufs` are not silently ignored: `perf stat -e
  syscalls:sys_enter_io_uring_register` counts exactly one extra registration for each flag
  (none: 1, registerfiles: 2, fixedbufs: 2, both: 3).
- `--ramp_time` excludes the ramp from the reported statistics and matches JUring's
  `--warmup-sec`; JSON percentiles are in `clat_ns` and both parsers convert ns to µs exactly
  once; write runs overwrite the existing file in place (size and allocated blocks unchanged).
- `bench/read-write/lib.sh` and `bench/write/run-write-cells.sh` build correct, matched `fio` commands.
  The one-command driver `bench/run-comparison.sh` did not: its `run_fio()` passed
  `--fixedbufs=1` unconditionally, so `seqread_1m_qd8` (1 MiB x QD 8 of pinned buffers against an
  8 MiB memlock limit) made `fio` exit 1 with JSON `error=12` and no message; it also omitted
  `--ramp_time`, `--clocksource`, `--randrepeat` and pinning, and gated write cases on `-r`
  instead of `-w`. Fixed there: fixed buffers are now conditional on the same 75 % memlock rule
  JUring uses (registering the file instead when they do not fit), and the ramp/clock/seed/
  affinity/write-gate issues are resolved.

## Seastar

There are two paths and they were audited separately.

- `bench/seastar/seastar_file_bench` (the same-file runner behind the published read and write
  comparisons) is correct: `open_file_dma` gives `O_DIRECT`, the same 10 GiB file and full region,
  `--qd` fibers per shard, `--smp` shards with matching `--cpuset`, warmup excluded from and
  repeated before every measured run, write path `rw|create` without truncate and without `fsync`.
  Its write path was previously marked untested; it is now smoke-validated (`status=ok`, zero
  errors, file size unchanged) and `bench/write/run-write-cells.sh` still keeps it opt-in. The
  stock 200-entry ring with no taskrun flags, no registered file and no fixed buffers is a real
  asymmetry against JUring — it stays reported as a bracket, never as an equal opponent.
- `bench/run-comparison.sh` used `io_tester` with three broken defaults: the placeholder
  `io-properties.example.yaml` has an example mountpoint (`/mnt/testdata-writable`) that does not
  exist, so Seastar aborted at startup on every case; `DATA_DIR` defaulted under the repository,
  which is btrfs on the OS disk rather than the NVMe under test; and the prebuilt binary's
  sysroot libraries were not on `LD_LIBRARY_PATH`. `io_tester` also creates and prefills its own
  files and has no warmup, so it is not a same-file comparison. The driver now defaults
  `DATA_DIR` to `nvme-data/iotester-storage`, leaves `SEASTAR_IO_PROPS` empty (Seastar probes the
  device; the shipped file is a template), adds the sysroot to `LD_LIBRARY_PATH`, warns when the
  storage is on a different device from the dataset, and records storage fstype/device in the
  manifest. `bench/seastar/build-and-run.md` and `bench/README.md` now state the io_tester caveats
  and point at the same-file runner for published numbers.

## Net effect

The read comparison as published (same file, `bench/read-write/lib.sh` with `seastar_file_bench`) was
already using correct invocations; the rerun in BENCHMARK.md used that path and all 30 cells
returned `status=ok`. The one-command driver is now usable on this host instead of failing its
Seastar half and the 1 MiB `fio` case. The write experiment's problem is not invocation
correctness: it is the consumer NVMe's SLC/TLC write-state movement, reported in BENCHMARK.md.
