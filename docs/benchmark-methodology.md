# Benchmark methodology: JUring, fio and Seastar on one NVMe

Comparing JUring to Seastar looks like one number and is really four questions. Seastar is a
runtime, an async file API and an I/O scheduler. JUring is a thin binding over `io_uring`. Put a
JMH ring loop next to a Seastar shard and you measure the kernel engine, the API, the scheduler and
the JVM at once, then argue about which part moved. So I split the comparison into four layers and
never mix them in one table.

I froze this design on 2026-11-13, against JUring `4526338` (branch `kafka-engine`) and Seastar
`04b1c04d` (`seastar-25.05.0-1392-g04b1c04d`, read-only clone at
`/home/david/projects/seastar`).

## The machine and the data

One host does the measuring: AMD Ryzen 9 3950X, 16 cores / 32 threads, 62 GiB, Fedora 44, kernel
7.0.12-201, and a GIGABYTE GP-ASM2NE6100TTTD 1 TB NVMe (`nvme1n1`, ext4) at `/mnt/TestData`. The
read file is 10 GiB, fully allocated (10,737,418,240 bytes, mtime 2026-11-03); writes use a
separate 10 GiB file. The multi-file corpus is 2,211 files of about 4.7 MB.

On my bench host `/mnt/TestData` is a read-only mount, so write runs go to a writable bind mount of the same NVMe partition (`nvme-data/`). `perf` works and
syscall tracepoints count; `drop_caches`, governor changes and `nvme smart-log` need root; Seastar
needs `cmake`, `ninja` and `liburing` to build. `taskset`, `fio 3.40`, `jq`, `python3`, `cpupower`
and `turbostat` are present; `numactl` and `sensors` are not, so I pin with `taskset`. `io_uring`
is enabled (`kernel.io_uring_disabled=0`).

## Four layers, not one number

| Layer | The question | Can I compare it? |
|---|---|---|
| Raw ring | Same syscalls, fd flags and registration: does JUring reach fio's `io_uring` throughput? | Against fio, yes. Seastar only approximately: no taskrun flags, no fixed files or buffers, `QUEUE_LEN=200`. |
| File API | Async file calls, streams, queue-depth sweeps and latency | Partly. `io_tester` has percentiles; JUring has latency at QD1 and telemetry at QD64; `fstream_perf` has no latency. |
| Runtime | Shard scaling, fairness, backpressure | No. JUring has no shard runtime, fair queue or QoS; Seastar has io_queue, scheduling groups and AIO caps. Only throughput scaling is measurable. |
| Application | A log engine against a Seastar log app | No, and out of scope. |

I don't quote a number without its layer and matched batching, and I don't call a JUring JMH loop
against a one-shard reactor "Seastar". Seastar's ring asymmetry is permanent until it exposes the
flags and registration calls, so I report it as a bracket, not a JUring win. The claims I will
defend are parity against fio at identical batching and registration, file API non-regression at
equal per-thread queue depth, and scaling efficiency at equal core budget.

## What I run

| Workload | bs | QD / threads | Data | Warmup / measured, reps | Engines | Record |
|---|---|---|---|---|---|---|
| randread | 4K | 1, 16, 128 | read file | 10/30 s, 5 | fio-uring, juring, seastar-uring | IOPS, MiB/s, p50/p99/p999/max, syscalls/op; CPU/op at QD16, saturation at QD128 |
| randread | 4K | 64 | read file | 10/30 s, 5 | fio-uring batch 1/8/16/32, fio-libaio, juring, seastar-uring/linux-aio | IOPS, depth curve |
| randwrite | 4K | 1, 16 | write file | 60/60 s (QD1), 120/60 s (QD16), 3 | fio-uring, juring | IOPS, p99, write-cliff flag |
| randwrite | 4K | 64 | write file | 120/60 s, 3 | fio-uring batch 1/8/16/32, fio-libaio, juring | steady-state IOPS, p99, temperature, 1 s series |
| randwrite | 4K | 128 | write file | 120/60 s, 3 | fio-uring, juring | steady-state IOPS, p99 |
| seqread | 1M | 1, 8, 32 | read file | 10/30 s, 5 | fio-uring, juring, seastar-uring | MiB/s, IOPS, CPU/op |
| seqwrite | 1M | 1, 8, 32 | write file | 60/60 s, 3 | fio-uring, juring | MiB/s, steady-state flag |
| randread latency | 4K | 1, 64 | read file | 10/120 s, 5 | fio-uring, juring, seastar-uring | QD1 p50/p99/p999/max, QD64 p99/p999/max, tail curve |
| thread scaling | 4K | 64/thread × 1, 2, 4, 8 | 1 file at 1 thread, 2/4/8 files above | 10/30 s, 5 | fio `numjobs=N`, juring N threads, Seastar `--smp N` | IOPS, scaling efficiency, spread, p99 |
| buffered streams | 128K | concurrency 8 | write file / multi-file set | 10/60 s, 5 | `fstream_perf`; juring buffered where the API exists | MiB/s, p99, cache state |
| mixed 70/30 read/write | 4K | 64 | read + write files | 10/60 s, 3 | fio-uring `--rw=randrw --rwmixread=70`, juring | IOPS split, p99 each way |
| nop | — | 64 | `/dev/null`, ring memory | 5/20 s, 5 | juring `NopBenchmark`, fio-uring, io_tester | ops/s, ns/op, syscalls/op, cycles/op |

The Seastar multi-thread runs use `io_tester`'s per-shard files, one per shard, unless the run uses
the raw block device as its root; fio and JUring use the multi-file set. That file-count difference
appears in every multi-thread result. The stream write path only runs the JUring side once stream
basics land; the read side has no stream API yet.

Reads come first, about four hours of machine time: randread QD 1 through 128, seqread, latency and
thread scaling. Writes run overnight in their own session, never interleaved with reads; streams,
nop and libaio variants come last. I record the wall time of every run.

**Queue depth.** Per thread (JUring in-flight per ring, fio `--iodepth`, Seastar `parallelism`) or
total (per-thread × threads), and I always say which. Engine and API comparisons use equal
per-thread depth; scaling runs use equal per-shard depth and report the total. The JUring QD1
against FileChannel QD1 comparison is only valid because FileChannel is synchronous.

## How I configure each engine

### JUring

The ring is `SINGLE_ISSUER | COOP_TASKRUN | DEFER_TASKRUN`, with `max(256, 2 × qd)` entries, one
ring per thread and a single issuer. I register all files and buffers up front
(`registerFiles`, `registerBuffers(bs, N)`), so every operation uses `IOSQE_FIXED_FILE` and a
fixed-buffer index. That drops the fd lookup and the buffer pin from the hot path, and it is why
the fio comparison is fair only with `--registerfiles=1 --fixedbufs=1`. `N` is
`min(qd, memlock budget)`, capped at 256 by the helper, and I record locked memory from
`/proc/self/limits`.

Every pending SQE goes out in one fused `io_uring_enter(submit, min_complete, GETEVENTS)`, with up
to `maxReap` completions drained per call. Effective batching is set by how many SQEs I prepare
between enters: the read harness fills to `qd` with `waitBatch=1`, `NopBenchmark` fills in batches
of `min(qd, 32)`. I record the fill policy and `min_complete`, and at QD64 I run partial fills of 8,
16 and 32 so fio's `--iodepth_batch_submit` sweep matches one to one. Without the sweep I would
measure batching, not the engine.

`--min-complete N` (default 1) and `--sqpoll` (experimental: `SINGLE_ISSUER|SQPOLL` with
`sqThreadIdleMs=0`) are the two runtime knobs, recorded in the manifest. The 0.1.0 claims use the
defaults. A higher `minComplete` trimmed p99 and lowered throughput monotonically; SQPOLL came out
70-80 % slower, so it stays parked.

Direct runs use `LinuxOpenOptions.READ_DIRECT`/`WRITE_DIRECT`. Random offsets are a 64-bit xorshift
plus multiply, uniform over the file's blocks, matching fio's per-job PRNG style. The buffered JMH
path keeps registered files and buffers but opens with `LinuxOpenOptions.READ`, against a buffered
`FileChannel`.

`JUringBlocking` uses dedicated platform pollers, `min(4, highestOneBit(procs/4))` by default and 1
below qd 64; the caller parks in `LockSupport` and a poller wakes it. The virtual-thread path has no
fixed buffers, so those are file-API results, not raw-engine results. Engine label
`juring-blocking`, `-Djuring.blocking.pollers=N` recorded.

JVM runs use `--enable-native-access=ALL-UNNAMED`, `-Xms8g -Xmx8g -XX:+AlwaysPreTouch`, one GC and
a fresh JVM per rep. I give the JIT at least 10 s plus its own measured steady state, check with
`-XX:+PrintCompilation` or JFR, and never measure the first 30 s. G1 is the default; ZGC is
reported separately and never pooled. JMH is only for the nop micro-overhead and as a cross-check;
the file-API harness is a standalone `main` with N internal reps and a fresh JVM per rep.

### fio

fio is the neutral reference. The configurations I use are:

| Configuration | Command | Purpose |
|---|---|---|
| default | `--ioengine=io_uring --iodepth=64` (`iodepth_batch_submit=1` is fio's default) | fio's stock io_uring configuration (one submit per enter) |
| batch sweep | `--ioengine=io_uring --iodepth_batch_submit=1,8,16,32` | removes the batching asymmetry against JUring's fused submit |
| fixed | `--registerfiles=1 --fixedbufs=1` | matches JUring's registered files and buffers |
| libaio | `--ioengine=libaio`, same depth and `O_DIRECT` | non-uring kernel reference |
| sqpoll | `--ioengine=io_uring --sqthread_poll=1` (not `--sqpoll`) | Seastar SQPOLL bracket |

This is the only fio form I run against `/mnt/TestData`, because the mount is read-only:

```bash
taskset -c 1 fio --name=fio-uring-qd64-b1 --filename=/mnt/TestData/read_files/single_file_bench.bin \
  --readonly --direct=1 --rw=randread --bs=4k --iodepth=64 --numjobs=1 --ioengine=io_uring \
  --iodepth_batch_submit=1 --ramp_time=10 --runtime=30 --time_based=1 --group_reporting=1 \
  --percentile_list=50:99:99.9:100 --clocksource=clock_gettime \
  --output-format=json --output=bench/results/2026-11-13/fio-uring-b1-qd64-r1.json
```

Writes use the same command with `--rw=randwrite --filename=.../write_files/...` and without
`--readonly`; mixed runs use `--rw=randrw --rwmixread=70`. For multi-job scaling I put `taskset`
on the whole process and use `--numjobs=N`, then check with `--showcmd` that fio binds one job per
CPU; `--cpus_allowed_policy=split` only if the run records it.

### Seastar

`SeaStarRef` is an in-repo writable checkout, built locally without root against a dependency
sysroot (`.sysroot/`, see `bench/seastar/build-and-run.md`); the host build recipe is the supported
path. The tools I use are:

| Tool | Configuration | Metrics |
|---|---|---|
| `seastar_file_bench` | same file, read-only, `--qd` fibers, `JUringFileBench` schema | IOPS, MiB/s, percentiles, manifest |
| `io_tester --reactor-backend=io_uring` (default when the probe succeeds) | per-workload YAML, `--smp N --cpuset 1-N --duration 30 --storage DIR` | IOPS, kB/s, percentiles |
| `io_tester` linux-aio / asymmetric_io_uring | same; the latter needs `--async-workers-cpuset` | kernel-AIO and SQPOLL brackets |
| `io_tester`, no backend flag | same | proves which backend the default picks; log it |
| `fstream_perf --concurrency=N --buffer-size=... --total-ops=...` | `--reactor-backend=io_uring` | IOPS only; latency is new code |

The `io_tester` YAML fields I use are `name`, `shards`, `type`, `data_size`, `files_count` and
`shard_info{parallelism, reqsize, shares, think_time, bandwidth, sched_class}`. Types are
`seqread|overwrite|randread|randwrite|append|cpu|unlink`. `parallelism` is the per-shard in-flight
count, which is the queue depth; `think_time` is 0; `shares` is fixed at 100 so the fair queue does
not differentiate jobs. On a directory `io_tester` creates one file per shard, `test-<job>-<shard>`,
of `align_up(file_size, 1MB)`; on a block device it uses the raw device per shard. It warns if the
directory is not XFS; on ext4 that warning is expected and recorded, not "fixed" mid-study.

Build and run template for the host:

```bash
cd /home/david/projects/seastar
./configure.py --mode=release --disable-tests --enable-apps  # not verified on a clean host
ninja -C build/release io_tester fstream_perf
sudo mkdir -p /dev/hugepages && sudo mount -t hugetlbfs none /dev/hugepages  # if not present
build/release/apps/io_tester/io_tester --storage /mnt/TestData/bench-ss \
  --conf bench/seastar/conf/randread_4k_qd64.yaml --duration 30 --smp 1 --cpuset 1 \
  --reactor-backend io_uring --hugepages /dev/hugepages --task-quota-ms 0.5 \
  --io-properties-file bench/seastar/io_properties.yaml 2>&1 | tee seastar-uring-smp1.log
```

### FileChannel

`FileChannel.open(path, READ, ExtendedOpenOption.DIRECT)` is my synchronous Java reference. One
operation is in flight per thread because a synchronous channel clamps the queue depth to 1 by
construction. I use it only as that reference, never as a raw-engine competitor above QD1.

### Where the engines are not equivalent

| Assumption | Holds? | Where it breaks |
|---|---|---|
| Same kernel operation | Yes | Seastar always uses `IORING_OP_READ`/`WRITE` on normal fds; JUring uses `READ_FIXED`/`WRITE_FIXED` with `IOSQE_FIXED_FILE`, saving an fd lookup and a buffer pin per operation. |
| Same ring flags | No | JUring sets `SINGLE_ISSUER`, `COOP_TASKRUN` and `DEFER_TASKRUN`; Seastar sets none. The `DEFER_TASKRUN` advantage is real; report it as a bracket. |
| Same ring depth | No | Seastar has `QUEUE_LEN=200`; the JUring ring is at least 256. Keep Seastar at 128 or less, or split the jobs. |
| Same batching | No, by default | fio submits one per enter; JUring fuses submit and wait (roughly 3 operations per enter at QD 64, about 2.2 in the single-thread JMH run); Seastar batches in its poller. The sweep is mandatory. |
| Same async API | No | Seastar `dma_read` goes through io_queue and io_sink and caps at `max_aio_per_queue=128` with `max_queues=8`; JUring submits directly from Java. That is runtime overhead, which is what these layers measure. |

## Fairness rules I don't break

Every engine runs its fastest documented configuration, and I disclose it: io_uring backend,
hugepages where available, pinned io-properties for Seastar; registered files and buffers plus the
three taskrun flags for JUring; `registerfiles`, `fixedbufs`, the batch sweep and sqpoll for fio. I
never omit a flag to flatter a competitor, and I never silently equalise a capability one side
lacks. That difference is a result.

Same device, files, offsets and PRNG class, block size, per-thread queue depth, warmup and
duration. The file is at least 10 GiB, so the working set exceeds any drive cache. Direct runs use
`O_DIRECT`, buffered runs only in the stream workloads and always for both engines. Both use 4K
block sizes and offsets; never 512-byte runs.

Pin the whole process tree to the same CPU set with `taskset -c`: fio threads, the JVM, Seastar's
`--cpuset`. A migrating benchmark thread is how you get a 5 % difference from nowhere. The SMT
siblings here are 0/16, 1/17, ... 15/31, so physical cores are 0-15; one thread per physical core,
never two shards on siblings. For N up to 15 I use CPUs 1..N and leave core 0 for kernel and IRQ
housekeeping; N=16 uses 0-15 with core 0 shared and recorded. CPUs 16-31 stay idle.

Same core budget: fio `numjobs=N` on 1..N; JUring N threads on 1..N with `JUringBlocking` pollers
counted and reported; Seastar `--smp N --cpuset 1-N`. Hidden poller threads break parity with a
one-thread fio job. This is a single NUMA node (`NUMA node0: 0-31`), so no bind is needed and I
record it; on a multi-node host I would bind memory too.

Governor and `energy_performance_preference` are both `performance`, verified with
`cpupower frequency-info` before each session; I don't compare sessions with different governor
state. JMH is only for the
nop micro-overhead and as a cross-check: `-f 5 -wi 3 -i 5 -r 3s`, exact command line recorded. The
annotations alone say `@Fork(1)`, which is why the command line ends up in the results file.

Hugepages: Seastar runs with a hugetlbfs mount if the host has one, its normal configuration, and
the page size is recorded. JUring can't use hugetlbfs for the Java heap without
`-XX:+UseLargePages`, so I don't enable large pages for one side only; `/proc/meminfo` records the
state and `HugePages_Total=0` is the default.

Page cache: `O_DIRECT` is insensitive to it, but metadata and extents are not. Cold runs need
`sync; echo 3 > /proc/sys/vm/drop_caches` as root; the non-root fallback is
`posix_fadvise(DONTNEED)`, best-effort. I record which and never mix cold and warm.

Dataset: preallocated, non-sparse, written once, fsynced and aged; the read file is 10 days old.
Both engines use the same file, and writes use the dedicated 10 GiB write file. I record `stat`
size and blocks, extent count and ext4 mount options; the bench mount is
`ro,nosuid,nodev,relatime,seclabel`, and read-only runs use read-only fds so a typo can't modify
data.

Background load: I record `uptime`, the top CPU processes and the load average before and after a
run, and abort and redo if loadavg(1m) is above 0.5 or a foreign process shows more than 20 % CPU.
Shared-host conditions get annotated, not silently pooled.

Metric definitions are fixed: IOPS over the measured window with warmup excluded; MiB/s is
bytes/2²⁰/s; latency is submit to completion for JUring, `clat` (not `lat`) for fio, completion
time for `io_tester`; per-op CPU is user plus kernel cycles over operations from `perf stat`.

## Writes are the hard part

Before a write session I precondition the file: 4K random writes at QD64 until at least 3× the file
size has been written — a 30 GiB target for the 10 GiB write file — then `fsync`. The bursts run in
4 GiB chunks, so they stop on a chunk boundary at 32 GiB (34.36 GB), which is the figure in
[BENCHMARK.md](../BENCHMARK.md). The 120 seconds in the original protocol is a time cap, not a
floor: a burst that has not reached the target by then is recorded as capped, and on this host the
target is reached in 40–67 s. I never start a measured write run on a fresh file or after a long
idle, and I record the write generation and bytes since the last idle.

The first write block on 2026-11-13 predates the target rule and stopped only when the target *and*
the 120 s minimum were both met, which wrote 128.8 GiB (12 chunks) instead of 32 GiB. That block is
kept in the record and is not pooled with later ones; the target rule replaced it.

This drive's SLC policy is unverified: no public spec, and `nvme smart-log` needs root on the
host. I capture an IOPS-versus-time series at 1-second resolution for every write run, detect the
cliff by a step drop of more than 30 %, and report burst and steady state separately. I never pool
them.

Rest is part of the protocol: 300 seconds idle between write reps, 600 seconds after a
preconditioning burst, and the idle duration is recorded.

The state-controlled write attempt in [BENCHMARK.md](../BENCHMARK.md) is a deliberate deviation
from that rest rule, not an oversight: it drops the 600 s post-preconditioning rest and the 300 s
between-run rest so that all three engines in a generation see the same device state. A
partially-recovered SLC burst exhausts mid-generation and systematically penalises whichever engine
runs last, which is exactly what the first pilot measured (`max/min = 2.0921` at 4 KiB QD 64). Both
protocols record their rest decisions, and results from one are never pooled with the other.

The test partition has no `discard` mount option and automatic discard is not used. I never
`blkdiscard` a shared data partition and never issue discard during a comparison; discard studies
belong on a dedicated scratch namespace with identical state resets between engines.

Thermal state is checked: drive temperature and throttle counters before and after each run with
`sudo nvme smart-log /dev/nvme1n1` on the host, ambient and CPU temperature with `turbostat` or
`sensors`. A run is aborted if temperature rises more than 5 °C within it or a throttle counter
increments, then redone after cooldown.

Read-only runs interleave engines A, B, A, B so slow drift cancels. Write runs need a monotonic
order for preconditioning, so I randomise within a preconditioned block and always report the
order. I never compare a fresh-drive run of engine A with a worn-drive run of engine B. Write
variance is reported in full: all 3 reps, the range and the time-series shape; CV above 10 % is
"unstable, no ranking" with the cause recorded, and I don't compute a speedup from overlapping
write intervals.

I don't compare: different queue depths or thread counts; `O_DIRECT` against buffered; fio's default
batching against JUring's fused submit without the sweep; JUring with fixed buffers against Seastar
without; single-file against multi-file; cold against warm page cache; burst against steady-state
writes; runs from different thermal, governor or load states; or JMH ops per invocation against
fio's time-based IOPS without normalising the window.

## What I measure

| Metric | How |
|---|---|
| IOPS, MiB/s | `iops = ops / (t_end − t_start)` after warmup; `mib = bytes / 2^20 / s` |
| Latency percentiles | fio `--percentile_list=50:95:99:99.9:100` with `--clat_percentiles=1 --lat_percentiles=0`; HdrHistogram `Histogram(1, 60e9, 3)`; io_tester YAML `latencies.{average,p50,p95,p99,p999,max}` in microseconds |
| CPU cycles and instructions per operation, user plus kernel | `perf stat -e task-clock,cycles:u,cycles:k,instructions:u,instructions:k -o perf.txt taskset -c 1 <engine>`, divided by ops |
| Syscalls and context switches per operation | `perf stat -e raw_syscalls:sys_enter` divided by ops; `context-switches` or `/proc/<pid>/status` before and after |
| io_uring specifics | `perf record -e io_uring:* -e raw_syscalls:sys_enter -- taskset -c 1 <engine>`, then `perf report --stdio --sort=symbol` |
| Device-side queue | `/sys/block/nvme1n1/stat` and `queue/nr_requests`; sectors moved must match engine bytes within 10 % |
| Scaling and per-core efficiency | `SE(N) = IOPS(N) / (N × IOPS(1))` with per-shard min/mean/max; `ops/s / active_cpus` and `cpu_util = task-clock / (active_cpus × elapsed)` |

`perf stat` around a whole process includes startup and JIT. For per-op CPU I use the steady-state
window only, attaching with `perf stat -p PID` where possible, and both engines get the same
wrapper. I parse fio JSON with `jq` into IOPS, MiB/s, percentiles and user/sys CPU, and plot
throughput and p99 against queue depth; a curve tells you more than one point.

## Statistics, and what I call a win

**Repetitions.** Throughput runs get 5 reps, writes 3 because they cost time, latency 5. Each rep
is a new process or JVM; I discard warmup and measure the window. I interleave A/B reps so drift is
common-mode; averaging the engines separately hides it.

**Reporting.** Mean ± 95 % CI (t, df = R−1), plus min, max, median, `n` and CV = s/mean. Never
write ± without saying what it is, and never mix JMH spreads with fio spreads.

**Outliers.** I keep every rep. A rep can be removed only with an instrumented cause: a load
average spike, an NVMe reset in `dmesg`, a thermal throttle, or a full disk. Then I rerun the whole
thing. At most 1 of R is removed per run; more than that makes the run invalid, and it is reported
as invalid, never trimmed silently.

**Minimum detectable difference.** With per-group CV `s` and `R` reps, the two-sided 95 %
difference interval is about `t_{0.975, 2R−2} · s · √(2/R)`. For R=5 and s=2 % that is about
2.9 %; for R=7 about 2.5 %. Paired and interleaved runs reduce it because I can use the SD of the
per-rep ratio, `s_d`. Never claim a delta whose interval includes zero, and never claim parity from
two overlapping intervals. Test the ratio or the paired difference directly.

**When I call it parity.** Parity at ±5 % means the 90 % interval of the ratio (JUring/comparator) lies
inside `[0.95, 1.05]`. If the interval is wider than the bounds, the result is "inconclusive, more
reps needed", not parity. Faster by 5 % or more means the 95 % lower bound of the ratio is above
1.05; slower means the upper bound is below 0.95. Anything between is "no measurable difference at
this power", with the achieved interval half-width quoted.

**The limits I set before the runs.** Read IOPS at the saturation depths must be at least fio minus 5 %, a
one-sided 95 % lower bound of 0.95, with cycles per operation within 15 % of fio. p99 must be at
most 2× fio; a parity claim needs p99 at most 1.10× and p999 at most 1.15× with a non-overlapping
difference interval. Scaling to 8 threads needs 8-thread throughput at least 85 % of `fio
numjobs=8`, SE(8) at least 0.75 with SE(4)/SE(8) reported, a 90 % lower bound of 0.90 on the IOPS
ratio against seastar-uring, per-shard min over mean at least 0.85, and 8-thread p99 at most 1.15×
the 1-thread p99. If fio's multi-job reference is within 5 % of single-thread, the device is the
limit and I may pass on the ceiling: min over max IOPS at least 0.95. Every cited number has an
interval. The sub-1 % metrics-overhead bar is not measurable yet because the harness had no JUring
metrics at the time, so I mark it absent instead of passing it silently. Seastar gets no bar until
its flags and registration are matched; a gap there is an asymmetry, reported as a bracket.

One caveat. The 0.1.0 read-path report used fio with registration
(`--registerfiles=1 --fixedbufs=1`) but default submit batching, because fio cannot set JUring's
taskrun flags or fuse submits the same way. Those read runs are a best-config capability
comparison, not an equal-config one. I reran the read matrix on 2026-11-17 with rotated engine
order and matched and matchedbatch fio references (`--iodepth_batch_submit=qd`), using
`bench/m1/run-claims-0.1.sh`; the 0.1.0 verification run reproduced the published JUring numbers within about
1 %, and the paired JUring/fio ratios were 1.0655–1.0693 at QD 64 and 1.2183–1.2306 at QD 128
(matched and batching-matched references; the same numbers as BENCHMARK.md). The write
runs follow the original protocol and remain inconclusive.

## Results files and reproducibility

Every engine normalises to one JSON object per run per rep: schema version 1.0, run ID, layer,
engine and version, backend, workload, block size, per-thread and total queue depth, file and size,
warmup, duration and reps. The `results` array holds per-rep IOPS, MiB/s, bytes, operations, errors,
latency percentiles, user and kernel cycles and instructions per operation, syscalls and context
switches per operation, the 1-second time series, and the flags that matter. The aggregate is `n`,
mean, SD, 95 % interval, median and CV; a validity block records valid, invalid or unstable with the
reason and the outlier count. The manifest records kernel, CPU, device, mount options, git commits,
JDK, fio and perf versions, governor, hugepage total, load average, CPU pinning, the exact command
line, session start and the run's order in the session.

Raw outputs live under `bench/results/YYYY-MM-DD/`, named with workload, engine, backend, block
size, queue depth, thread count and rep, next to `manifest.txt`, a `notes.md` for anomalies and
order, an `aggregate/` directory and a frozen hash of this document. `bench/parse_results.py`
normalises fio JSON, `io_tester` YAML and JUring JSON into that schema.

A run is only citable if its results file has all of that; missing anything and it is marked
unverified and excluded. That sounds strict, and it is: a number without its command, `±` definition and CPU pinning
isn't citable.

## What could still be wrong

All of this is one machine, one drive, one kernel. There is no generalisation to other NVMe
controllers or CPU families, and I say so under every table.

Consumer-drive SLC and garbage collection dominate writes. Burst and steady state are logically
different devices; only steady state is rankable, and only with a time series.

The JVM adds JIT and GC noise. JUring has about 2-5 % per-operation overhead against native in the
nop benchmark (11.6 M ops/s), but the JIT can also optimise the loop in ways a real application
won't. That is why I enforce steady state, one GC, a fresh JVM per rep and GC logs. fio and JMH
also use different estimators: fio reports time-based IOPS over a window, JMH operations per
invocation normalised by iteration time. Those are different estimators, so I don't convert one into the other.

The host is shared with other work, so CPU interference
is real; the load average check is mandatory, and free space and fragmentation are captured on
the host with `df -h` and `filefrag`.

Seastar's build configuration changes framework overhead. I pin release mode, compiler and LTO;
only release numbers are comparable to fio and the JVM. Kernel version matters too: `DEFER_TASKRUN`
and task_work behaviour changed across kernels, so results are tied to 7.0.12-201 and the read-path
comparison is rerun on any kernel change.

## Before you trust any number

Run these on the host, from `/home/david/projects/JUring`:

```bash
# 1. Identity + kernel + CPU topology (SMT pairs, NUMA)
uname -r; lscpu | grep -E 'Model name|Thread\(s\)|Core\(s\)|Socket\(s\)|NUMA'; for c in 0 1 2 3; do echo "cpu$c: $(cat /sys/devices/system/cpu/cpu$c/topology/thread_siblings_list)"; done

# 2. CPU power state must be performance/performance before any run
for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do cat "$f"; done | sort | uniq -c; cpupower frequency-info | grep -E 'governor|policy|boost'

# 3. Device identity, mount options, and that the data device is the one we think
findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS /mnt/TestData; lsblk -o NAME,MODEL,SERIAL,SIZE,MOUNTPOINTS; df -h /mnt/TestData

# 4. Dataset integrity: exact size, fully allocated (no sparse extents), 4K-alignable
stat -c '%n size=%s blocks=%b blksize=%B' /mnt/TestData/read_files/single_file_bench.bin; filefrag -v /mnt/TestData/read_files/single_file_bench.bin | head -5

# 5. io_uring is enabled and fio/JDK are the expected versions
cat /proc/sys/kernel/io_uring_disabled; fio --version; java -version 2>&1 | head -3; mvn -v 2>&1 | head -2

# 6. perf works for both counters and syscall tracepoints (abort the study if this fails)
perf stat -e cycles,instructions,raw_syscalls:sys_enter true; echo "paranoid=$(cat /proc/sys/kernel/perf_event_paranoid)"

# 7. 5 s fio io_uring 4K randread QD64 sanity check on the real dataset (read-only!)
taskset -c 1 fio --name=probe --filename=/mnt/TestData/read_files/single_file_bench.bin --readonly --direct=1 --rw=randread --bs=4k --iodepth=64 --ioengine=io_uring --runtime=5 --time_based=1 --group_reporting=1

# 8. Machine is idle: no foreign benchmark/compile, loadavg low (re-check before every session)
uptime; ps -eo pid,pcpu,comm --sort=-pcpu | head -8

# 9. Cold-cache control is available (root) and device health/thermal readable (root)
sudo sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches' && echo "drop_caches ok"; sudo nvme smart-log /dev/nvme1n1 | grep -Ei 'temperature|percentage_used|media_errors|throttle'

# 10. Seastar build prerequisites on the host (skip only if Seastar numbers are out of scope)
cmake --version | head -1; ninja --version; pkg-config --modversion liburing; g++ --version | head -1; ls /dev/hugepages >/dev/null 2>&1 && echo "hugetlbfs present" || echo "hugetlbfs MISSING"
```

Commands 2, 5, 6, 7 and 8 must all succeed. Command 9 must succeed for write and cold-cache
studies, and 10 is required for any Seastar comparison. If 5 fails because io_uring is disabled, or
6 fails because perf is blocked, stop. The numbers will not be comparable to this methodology.
