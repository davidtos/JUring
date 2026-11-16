# JUring vs seastar — apples-to-apples benchmark methodology

**Status:** design frozen 2026-11-13. This document is the measurement contract for M1/M2 and for the
harness under `bench/`. **Repos:** JUring `4526338` (branch `kafka-engine`) and seastar
`04b1c04d` (`seastar-25.05.0-1392-g04b1c04d`, read-only clone at `/home/david/projects/seastar`).
**Companions:** `docs/internal/SEASTAR-PLAN.md` (milestones §5), `docs/internal/seastar-inventory.md`,
`docs/internal/juring-inventory.md`, `docs/internal/baseline-2026-11-13.md` (today's numbers).
**Host:** AMD Ryzen 9 3950X, 16C/32T, 62 GiB, Fedora 44, kernel 7.0.12-201, GIGABYTE
GP-ASM2NE6100TTTD 1 TB NVMe (`nvme1n1`, ext4) at `/mnt/TestData`, 10 GiB preallocated
`read_files/single_file_bench.bin` (10,737,418,240 B, fully allocated, mtime 2026-11-03).

**Evidence tags.** `[V]` = verified in this sandbox on 2026-11-13; `[U]` = unverified claim that
must be checked on the host before being cited; `[S]` = blocked by sandbox, must run on host.
Code claims are cited `path:line` relative to the repo root named in the sentence.

| Sandbox fact | Value | Consequence |
|---|---|---|
| `/mnt/TestData`, `/mnt/data` | mounted `ro` `[V]` | no write workloads here; writes on host only |
| `perf` | works; `perf_event_paranoid=-1`, `raw_syscalls:sys_enter` counts `[V]` | CPU/syscall instrumentation is possible |
| root-only controls | `drop_caches`, governor, `nvme smart-log` denied `[V]` | cold-cache/thermal gates on host with sudo |
| seastar build | no `cmake`/`ninja`/`liburing.pc` `[V]` | seastar numbers only on host after `configure.py && ninja` |
| tools | `taskset`, `fio 3.40`, `jq`, `python3`, `cpupower`, `turbostat` present; `numactl`, `sensors` missing `[V]` | use `taskset`; NUMA is single-node anyway |
| `io_uring` | `kernel.io_uring_disabled=0` `[V]` | uring usable |

## 1. Comparison layers — why one number is meaningless

"JUring vs seastar" has no single answer. seastar is a runtime + async file API + I/O scheduler;
JUring is a thin io_uring binding. Comparing a JMH ring loop to a full seastar shard conflates the
kernel engine, the API, the scheduler, and the JVM. Decompose into four layers and never mix them in
one table.

| Layer | Question it answers | Comparable today? | New code needed | Scope now |
|---|---|---|---|---|
| **L0** raw io_uring engine parity | Same syscalls/fd flags/registration, same workload: does JUring reach fio `io_uring` throughput? | **Yes for JUring↔fio** (both can register files/buffers). seastar only *approximately*: ring has no `SINGLE_ISSUER`/`COOP_TASKRUN`/`DEFER_TASKRUN`, no fixed files, no fixed buffers, `QUEUE_LEN=200` (`src/core/reactor_backend.hh:464-467`), submission via `io_uring_prep_read/write` on normal fds (`src/core/reactor_backend.cc:1361-1379`, `src/core/reactor_backend.hh:467`) | seastar-side flags/registration patch (not planned) — declare **asymmetric, documented** | **in scope** as L0-parity(JUring↔fio) and L0-bracket(seastar) |
| **L1** async file API | `file::dma_read/write` vs `JUring.read/write`; `file_input/output_stream` vs JUring buffered reads/writes; QD sweep, latency distributions | **Partly.** `io_tester` reports IOPS + avg/p50/p95/p99/p999/max (`apps/io_tester/io_tester.cc:688-700`, quantiles `:273`); JUring latency exists at QD=1 (`src/test/java/bench/suite/direct/DirectLatencyBenchmark.java:94-109`) and as QD=64 telemetry in `FioComparisonBenchmark` (`:189-192,264-296,225-226`) | JUring: seq + QD-aware latency harness (`bench/juring/JUringFileBench.java`, task-4); seastar `fstream_perf` has **no latency** output (`tests/perf/fstream_perf.cc:73-74`) | **in scope** read path first; stream path best-effort |
| **L2** runtime (shards, scheduling, QoS, backpressure) | Does sharding scale, is latency fair under load, backpressure | **No.** JUring has no shard runtime, no fair queue, no QoS; seastar has io_queue token buckets, scheduling groups, a task quota (`src/core/reactor.cc:4062`) and per-queue AIO caps (`include/seastar/core/reactor.hh:235-237`). Only *throughput scaling* is measurable | benchmark shim: N JUring rings in N Java threads; seastar `--smp N`; QoS/priority tests need JUring features that do not exist | **scaling only in scope; QoS/backpressure out of scope** |
| **L3** application/log engine | an application/log engine (e.g. Kafka-style) vs a seastar-based log/commitlog | **No.** different APIs, no equivalent seastar app | a real seastar log app | **out of scope now** |

**Rules that follow from the decomposition**
- Never write "JUring is 0.9 % faster than fio" without stating the layer and the matched batching
  (L0). Never compare a JUring JMH ring loop to a seastar 1-shard reactor and call it "seastar" (that is
  L0+JVM+API, not the framework).
- seastar L0 asymmetry is *permanent until seastar exposes the flags*; report it as a bracket
  (lower bound = what seastar's current engine does), not as a JUring advantage [V for absence of
  flags/registration: `src/core/reactor_backend.cc:1236-1275` builds `io_uring_params{}` with no
  flags; no `io_uring_register_files/buffers` call exists in `src/core`].
- the only defensible headline claims are: (a) L0 parity/non-regression between JUring and fio with
  identical batching+registration; (b) L1 non-regression for the file API at equal per-thread QD;
  (c) L2 scaling efficiency of N JUring threads vs N seastar shards at equal core budget.

## 2. Workload matrix (IDs fixed for all artifacts)

Notation: **DS-S** = single 10 GiB file `/mnt/TestData/read_files/single_file_bench.bin`;
**DS-W** = single preallocated 10 GiB write file `/mnt/TestData/write_files/single_file_bench_w.bin`
(host only); **DS-M** = 2,211×~4.7 MB file set (existing multi-file corpus). Durations are
`warmup / measured`; `R` = independent repetitions (new process/JVM/run dir each).

| ID | Workload | bs | QD / parallelism | Dataset | warmup/dur, R | Engines | Primary | Secondary |
|---|---|---|---|---|---|---|---|---|
| W1 | randread | 4K | 1 | DS-S | 10/30 s, 5 | fio-uring, juring, seastar-uring | IOPS | MiB/s, p50/p99/p999/max, syscalls/op |
| W2 | randread | 4K | 16 | DS-S | 10/30 s, 5 | fio-uring, juring, seastar-uring | IOPS | same + CPU/op u/k |
| W3 | randread | 4K | 64 | DS-S | 10/30 s, 5 | fio-uring(b=1,8,16,32), fio-libaio, juring, seastar-uring/linux-aio | IOPS | same + QD curve anchor |
| W4 | randread | 4K | 128 | DS-S | 10/30 s, 5 | fio-uring, juring, seastar-uring | IOPS | saturation check |
| W5 | randwrite | 4K | 1 | DS-W | 60/60 s, 3 | fio-uring, juring | IOPS | p99, write-cliff flag |
| W6 | randwrite | 4K | 16 | DS-W | 120/60 s, 3 | fio-uring, juring | IOPS | p99 |
| W7 | randwrite | 4K | 64 | DS-W | 120/60 s, 3 | fio-uring(b=1,8,16,32), fio-libaio, juring | **steady-state IOPS** | p99, temp delta, time-series |
| W8 | randwrite | 4K | 128 | DS-W | 120/60 s, 3 | fio-uring, juring | steady-state IOPS | p99 |
| W9 | seqread | 1M | 1 | DS-S | 10/30 s, 5 | fio-uring, juring, seastar-uring | MiB/s | IOPS, CPU/op |
| W10 | seqread | 1M | 8 | DS-S | 10/30 s, 5 | fio-uring, juring, seastar-uring | MiB/s | IOPS |
| W11 | seqread | 1M | 32 | DS-S | 10/30 s, 5 | fio-uring, juring, seastar-uring | MiB/s | IOPS |
| W12 | seqwrite | 1M | 1 | DS-W | 60/60 s, 3 | fio-uring, juring | MiB/s | steady-state flag |
| W13 | seqwrite | 1M | 8 | DS-W | 60/60 s, 3 | fio-uring, juring | MiB/s | steady-state flag |
| W14 | seqwrite | 1M | 32 | DS-W | 60/60 s, 3 | fio-uring, juring | MiB/s | steady-state flag |
| W15 | randread latency | 4K | 1 | DS-S | 10/120 s, 5 | fio-uring, juring, seastar-uring | p50/p99/p999/max | IOPS, CPU/op |
| W16 | randread latency | 4K | 64 | DS-S | 10/120 s, 5 | fio-uring, juring, seastar-uring | p99/p999/max | IOPS, tail-vs-QD curve |
| W17 | randread scaling | 4K | 64/shard × 1 shard | DS-S (1 file) | 10/30 s, 5 | fio-uring numjobs=1, juring 1T, seastar `--smp 1` | IOPS | CPU/op |
| W18 | randread scaling | 4K | 64 × 2 | DS-M (2 files) | 10/30 s, 5 | fio numjobs=2, juring 2T, seastar `--smp 2` | IOPS | per-shard spread |
| W19 | randread scaling | 4K | 64 × 4 | DS-M (4 files) | 10/30 s, 5 | fio numjobs=4, juring 4T, seastar `--smp 4` | IOPS | per-shard spread |
| W20 | randread scaling | 4K | 64 × 8 | DS-M (8 files) | 10/30 s, 5 | fio numjobs=8, juring 8T, seastar `--smp 8` | IOPS, scaling-eff | per-shard spread, p99 |
| W21 | buffered stream write | 128K buffer | concurrency 8 | DS-W on host | 10/60 s, 3 | fstream_perf; juring buffered **only if stream basics land** (conditional, plan-M2) | MiB/s | p99 (new code) |
| W22 | buffered stream read | 128K buffer | concurrency 8 | DS-M | 10/60 s, 3 | fstream_perf (seastar); juring buffered/(no stream API yet) | MiB/s | cache-state flag |
| W23 | mixed 70R/30W | 4K | 64 | DS-W+DS-S | 10/60 s, 3 | fio-uring (`--rw=randrw --rwmixread=70`), juring (harness) | IOPS R/W split | p99 each direction |
| W24 | nop / engine overhead | — | 64 | none: `/dev/null` / ring memory | 5/20 s, 5 | juring NopBenchmark (`src/test/java/bench/suite/direct/NopBenchmark.java:58-91`), fio-uring on `/dev/null`, seastar io_tester `/dev/null` (`apps/io_tester/io_tester.cc:645-651`) | ops/s, ns/op | syscalls/op, cycles/op |

* Multi-shard footnote: for seastar, W17-W20 use `io_tester`'s own per-shard files under
`--storage` (one file per shard, `apps/io_tester/io_tester.cc:602`) unless the run uses the raw
block device as root (`:626-643`). fio/JUring use the DS-M files. That file-count difference is a
documented L2 asymmetry (§3.5) and must appear in every multi-shard artifact.

**Phasing (machine time).** Phase A (must, read path, ≈4 h): W1-W4, W7 reference only, W9-W11,
W15, W17-W20 with `fio-uring` + `juring` + `seastar-uring` (where built). Phase B (writes, overnight):
W5-W8, W12-W14, W23; writes are never interleaved with read cells in the same session. Phase C:
W21-W22, W24, fio-libaio variants. Record every cell's actual wall time; do not pretend 30 s cells
are free.

**QD semantics.** Always state which QD is meant: *per-thread QD* (JUring in-flight per ring; fio
`--iodepth`; seastar `parallelism`) vs *total QD* (per-thread × threads/shards). L0/L1 compare at
equal **per-thread** QD; L2 scaling compares at equal per-shard QD and reports total. The JUring QD=1
vs FileChannel QD=1 comparison is only valid because FileChannel is synchronous (BENCHMARK.md:37).

## 3. Engine configurations (exact)

### 3.1 JUring (`bench/juring/JUringFileBench.java`, task-4; JMH suite as secondary)

- Ring: `IORING_SETUP_SINGLE_ISSUER | IORING_SETUP_COOP_TASKRUN | IORING_SETUP_DEFER_TASKRUN`
  (`src/main/java/com/davidvlijmincx/lio/api/IoUringOptions.java:16-21`), entries `max(256, 2×qd)`
  (`src/test/java/bench/suite/direct/DirectReadBenchmark.java:74-75`), one ring per thread, single issuer.
- Registration: `registerFiles(all fds)` + `registerBuffers(bs, N)` → all I/O uses
  `IOSQE_FIXED_FILE` + fixed-buffer index (`.../JUring.java:149-160`, `.../SqeOptions.java:8`,
  `.../DirectReadBenchmark.java:106-112`). N = `min(qd, memlock budgets)`; existing helper caps 256
  (`src/test/java/bench/suite/common/IoHelper.java:102-115`). Record `/proc/self/limits` locked memory.
- Batching policy: every pending SQE is submitted in one **fused** `io_uring_enter(submit,
  min_complete, GETEVENTS)` and up to `maxReap` CQEs are drained per call
  (`src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java:1099-1186`; API
  `.../JUring.java:781-796`). Effective batching is therefore set by *how many SQEs are prepared
  between enters*. Existing read benchmark: fill to `qd`, `waitBatch=1`
  (`src/test/java/bench/suite/direct/DirectReadBenchmark.java:199,224-229`); `NopBenchmark` fills in
  batches of `min(qd,32)` (`.../NopBenchmark.java:63-87`). **Per cell record** the fill policy
  (`fill_to_qd` default) and `min_complete`. W3/W7 must also run partial fills of **8, 16, 32** so the
  fio `iodepth_batch_submit` sweep is matched one-to-one; otherwise the comparison measures batching,
  not the engine.
- O_DIRECT: `LinuxOpenOptions.READ_DIRECT/WRITE_DIRECT` = `O_DIRECT` (`.../LinuxOpenOptions.java:18-21,42-51`).
- Random offsets: 64-bit xorshift + multiply, uniform over file blocks
  (`src/test/java/bench/suite/common/DatasetManager.java:431-452`), matching fio's per-job PRNG style.
- Buffered (non-O_DIRECT) path already exists in JMH form: `ring` with registered files but
  `LinuxOpenOptions.READ` (buffered), fixed buffers still registered, vs buffered `FileChannel`
  (`src/test/java/bench/suite/buffered/BufferedReadBenchmark.java:60-75`; write mirror
  `.../BufferedWriteBenchmark.java:60-76`). Use the same semantics in the standalone harness.
- `JUringBlocking` (VT runs): dedicated platform poller threads, default count
  `min(4, highestOneBit(procs/4))` and 1 for qd<64 (`.../JUringBlocking.java:646-660`), caller parked
  via `LockSupport`, woken by poller (`:203-215,:253`). It does **not** use fixed buffers on the VT
  path (`src/test/java/bench/suite/direct/VirtualThreadBenchmark.java:210`), so VT results are L1,
  not L0. Engine label `juring-blocking`, `-Djuring.blocking.pollers=N` recorded.
- JVM: `--enable-native-access=ALL-UNNAMED` (`DirectReadBenchmark.java:26-28`), `-Xms8g -Xmx8g
  -XX:+AlwaysPreTouch`, GC choice recorded (proposal: G1 default; ZGC reported separately, never
  pooled). JMH only for micro-overhead (W24) and as a cross-check; the L1 harness is a standalone
  `main` with N internal reps and a fresh JVM per rep.

### 3.2 fio (neutral reference)

| Cell | Command core | Purpose |
|---|---|---|
| fio-uring default | `--ioengine=io_uring --iodepth=64` (fio default `iodepth_batch_submit=1`, `/usr/share/doc/fio/HOWTO.rst:3276-3280`) | the number README.md:27 quotes as "default" |
| fio-uring batched sweep | `--iodepth_batch_submit=1,8,16,32` | removes the batching asymmetry vs JUring's fused submit |
| fio-uring fixed | `--registerfiles=1 --fixedbufs=1` (`HOWTO.rst:2408-2434`) | matches JUring's registered files/fixed buffers |
| fio-libaio | `--ioengine=libaio` (same QD/O_DIRECT) | non-uring kernel-engine reference |
| fio-sqpoll | `--ioengine=io_uring --sqthread_poll=1` (`fio --enghelp=io_uring`; not `--sqpoll`) | bracket against seastar `asymmetric_io_uring` |

Read-only run (sandbox-safe, must be the *only* fio form used against `/mnt/TestData`):

```bash
taskset -c 1 fio --name=W3-fio-uring-b1 --filename=/mnt/TestData/read_files/single_file_bench.bin \
  --readonly --direct=1 --rw=randread --bs=4k --iodepth=64 --numjobs=1 --ioengine=io_uring \
  --iodepth_batch_submit=1 --ramp_time=10 --runtime=30 --time_based=1 --group_reporting=1 \
  --percentile_list=50:99:99.9:100 --clocksource=clock_gettime \
  --output-format=json --output=bench/results/2026-11-13/W3__fio-uring__b1__qd64__r1.json
```

Write form (host, writable dataset; identical but `--rw=randwrite --filename=...write_files/...` and
no `--readonly`). Mixed: `--rw=randrw --rwmixread=70`. Multi-job scaling: `--numjobs=N
--cpus_allowed` is *not* used; use `taskset` on the whole process and `--numjobs=N`, verify fio binds
one job per CPU by checking `--showcmd` output; do not use `--cpus_allowed_policy=split` unless
recorded (`HOWTO.rst:3674-3701`).

### 3.3 seastar (runnable in this sandbox since 2026-11-13)

`SeaStarRef/` is now an in-repo, writable checkout and was built locally without root
against a dependency sysroot (`.sysroot/`, see `bench/seastar/build-and-run.md`). The
table below now has a sandbox column; the host build recipe remains the supported path.

| Tool | Config | Metric |
|---|---|---|
| `seastar_file_bench` (our runner, **sandbox-capable**) | opens the same existing file read-only; `--qd` fibers; emits JUringFileBench-schema JSON | IOPS, MiB/s, p50/p99/p999/max, full manifest |
| `io_tester --reactor-backend=io_uring` (default when the probe succeeds: `src/core/reactor.cc:4029-4050`; backend ordering in `src/core/reactor_backend.cc:2651-2663`) | per-workload YAML, `--smp N --cpuset 1-N --duration 30 --storage DIR` | IOPS, kB/s, avg/p50/p95/p99/p999/max (`apps/io_tester/io_tester.cc:688-700`) |
| `io_tester --reactor-backend=linux-aio` | same | kernel-AIO bracket; requires `fs.aio-max-nr` headroom (`src/core/reactor.cc:4097`) |
| `io_tester --reactor-backend=asymmetric_io_uring --async-workers-cpuset=...` | same | SQPOLL bracket (`src/core/reactor_backend.cc:2135,2149`; option required, `reactor.cc:4088-4092`) |
| `io_tester` (default backend, no flag) | same | proves which backend the default picks; must be logged |
| `fstream_perf --concurrency=N --buffer-size=... --total-ops=...` | `--reactor-backend=io_uring` | IOPS only (`tests/perf/fstream_perf.cc:73-74`); latency = new code |
| `io_queue_latency` (perf harness) | reactor backend builds only | io_queue latency microbench, not workload parity (`tests/perf/io_queue_latency.cc`) |

`io_tester` YAML fields used: `name, shards, type, data_size, files_count, shard_info{parallelism,
reqsize, shares, think_time, bandwidth, sched_class}` (`apps/io_tester/io_tester.cc:217-230,247-260,
1012-1149`); types `seqread|overwrite|randread|randwrite|append|cpu|unlink` (`:77`). `parallelism` is
the per-shard in-flight count (= QD). `think_time: 0`. `shares` fixed at 100 for all jobs so io_queue
fair-queue does not differentiate. **Datasets:** on a directory, io_tester creates one file per shard
`test-<job>-<shard>` (`:602`) of `align_up(file_size, 1MB)` (`:1149`); on a block device it uses the
raw device per shard (`:626-643`). So a single-shard run is single-file comparable, but multi-shard
runs are multi-file — this must be stated, not hidden (or run on the block device as root).
`io_tester` warns if the directory is not XFS (`:1291`); the warning is expected on ext4 and is
recorded, not "fixed" mid-study.

Build/run template (host):

```bash
cd /home/david/projects/seastar
./configure.py --mode=release --disable-tests --enable-apps  # UNVERIFIED in sandbox
ninja -C build/release io_tester fstream_perf
sudo mkdir -p /dev/hugepages && sudo mount -t hugetlbfs none /dev/hugepages  # if not present
build/release/apps/io_tester/io_tester --storage /mnt/TestData/bench-ss \
  --conf bench/seastar/conf/W3_randread_4k_qd64.yaml --duration 30 --smp 1 --cpuset 1 \
  --reactor-backend io_uring --hugepages /dev/hugepages --task-quota-ms 0.5 \
  --io-properties-file bench/seastar/io_properties.yaml 2>&1 | tee W3__seastar-uring__smp1.log
```

### 3.4 FileChannel (Java baseline)

`FileChannel.open(path, READ, ExtendedOpenOption.DIRECT)` (`src/test/java/bench/suite/common/
IoHelper.java:21-39`; `DatasetManager.java:311-338`), one in-flight op per thread (QD is clamped to 1
by synchrony). Use it only as the "synchronous Java" reference, never as an L0 competitor at QD>1.

### 3.5 Equivalence assumptions and where they break

| Assumption | Holds? | Where it breaks |
|---|---|---|
| Same kernel operation | YES for read/write/`*v` | seastar always uses `IORING_OP_READ`/`WRITE` on normal fds (`src/core/reactor_backend.cc:1361-1379`); JUring uses `IORING_OP_READ_FIXED`/`WRITE_FIXED` (`src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java:344-368`) with `IOSQE_FIXED_FILE` (`.../JUring.java:149-162`; `.../SqeOptions.java:8`) → JUring saves fd lookup + buffer pin per op |
| Same ring flags | NO | JUring sets `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` (`IoUringOptions.java:16-21`); seastar sets none (`reactor_backend.cc:1236-1275`) → kernel-side task_work/offload differs; the DEFER_TASKRUN advantage is real and must be reported as a bracket |
| Same ring depth | NO | seastar `QUEUE_LEN=200` (`reactor_backend.hh:467`); JUring ring ≥256, QD can exceed 200 → seastar caps effective per-shard in-flight below QD=256 cells. Keep seastar cells ≤128 or split jobs |
| Same batching | NO by default | fio default submits 1 per enter (`HOWTO.rst:3276-3280`); JUring fuses submit+wait (≈2.2 ops/enter measured, BENCHMARK.md:26 `0.4569`); seastar batches in the poller (`reactor_backend.cc:1710-1711`). The batching sweep is mandatory, not optional |
| Same async API | NO | seastar `dma_read` goes through io_queue fair-queue→io_sink (`include/seastar/core/internal/io_sink.hh:50`; `reactor_backend.cc:1361-1379`) and caps at `max_aio_per_queue=128` with `max_queues=8` (`include/seastar/core/reactor.hh:235-237`); JUring submits directly from Java. This is L2 overhead and is exactly what L1/L2 are meant to measure |
| Same file count | NO for seastar multi-shard | per-shard files (`io_tester.cc:602`) vs one file for fio/JUring. W18-W20 accept this and record it; single-file multi-shard seastar requires block-device storage (root) |
| Same alignment | YES if we force it | 4K bs/offsets for both (JUring O_DIRECT 4K, io_tester alignment 4K when reqsize ≥4096, `io_tester.cc:560`); never use bs=512 cells |

## 4. Fairness protocol (normative: `MUST` unless marked `SHOULD`)

| # | Rule |
|---|---|
| F0 | **Best configuration for every engine, fully disclosed.** Run each engine with the fastest documented configuration it supports (io_uring backend, THP/hugepages if available, pinned io-properties for seastar; registered files/buffers + SINGLE_ISSUER/COOP_TASKRUN/DEFER_TASKRUN for JUring; registerfiles/fixedbufs + batch + sqpoll for fio). Never omit a flag or capability to make a competitor look worse, and never silently equalise a capability one side lacks — report it as an engine difference. This is locked decision D3. |
| F1 | Same device (`nvme1n1`), same file(s), same offsets/PRNG class, same bs, same per-thread QD, same duration/warmup. Same file size ≥ 10 GiB so the working set exceeds any drive cache. |
| F2 | O_DIRECT for all direct cells; buffered cells only in W21-W22 and always together for both engines. Never compare an O_DIRECT number to a buffered number. |
| F3 | Pin every engine to the **same** CPU set with `taskset -c`; pin the whole process tree (fio threads, JVM, seastar `--cpuset`). Never let the scheduler migrate a benchmark thread. |
| F4 | SMT: this host's siblings are `0/16, 1/17, …, 15/31` `[V]` (`thread_siblings_list`), so physical cores are CPUs **0-15**. Use one thread per physical core and never place two shards on siblings of the same core. For N ≤ 15 use CPUs **1..N** (leaving core 0 for kernel/IRQ housekeeping); for N=16 use CPUs **0-15** and record that core 0 is shared with housekeeping. Record that CPUs 16-31 (SMT siblings) are idle in all cells. |
| F5 | Same core budget across engines: fio `numjobs=N` on CPUs 1..N; JUring N threads on 1..N (plus `JUringBlocking` pollers counted and reported); seastar `--smp N --cpuset 1-N` (use `0-15` when N=16). Report total threads used; a JUring run with hidden poller threads is not at parity with a 1-thread fio job. |
| F6 | NUMA: single node (`NUMA node0: 0-31`) `[V]` — no bind needed; record it. If run on a multi-node host later, bind memory too. |
| F7 | Governor/boost: `performance` on all CPUs and `energy_performance_preference=performance` `[V]`; verify and record immediately before each session (`cpupower frequency-info`). Do not compare sessions with different governor state. |
| F8 | JVM: identical flags for every JUring cell (`--enable-native-access=ALL-UNNAMED`, `-Xms8g -Xmx8g -XX:+AlwaysPreTouch`, one GC, GC logging on); fresh JVM per rep; JIT warmup ≥ 10 s plus ≥ its own measured steady-state (verify with `-XX:+PrintCompilation`/JFR). Never measure the first 30 s of a JVM. |
| F9 | JMH usage: only for W24/FFM micro-overhead and as a cross-check; L1 uses the standalone harness with N internal reps. If JMH: `-f 5 -wi 3 -i 5 -r 3s`, record the exact CLI. The current docs' "5 forks × 3 iterations" (BENCHMARK.md:19) is unverifiable because no command is recorded and the annotations say `@Fork(1)` (`DirectReadBenchmark.java:26-28`). |
| F10 | Hugepages: seastar runs with a hugetlbfs mount if available (its normal config; option `--hugepages`, `src/core/reactor.cc:4116`; record size), JUring cannot use hugetlbfs for Java heap without `-XX:+UseLargePages`; **do not** enable large pages for one side only. Record hugepage state (`/proc/meminfo`) and treat `HugePages_Total=0` `[V]` as the default condition. |
| F11 | Page cache: O_DIRECT cells are cache-insensitive, but file metadata/extents are not. Cold-cache runs need `sync; echo 3 > /proc/sys/vm/drop_caches` (root; `[S]` here); non-root fallback is `posix_fadvise(DONTNEED)` (`src/test/java/bench/suite/common/IoHelper.java:167-174`) and it is best-effort — record which was used. Never mix cold and warm cells. |
| F12 | Dataset layout: preallocated, non-sparse, written once, `fsync`ed, aged (file mtime 10 days old here `[V]`); same file for both engines; for writes use a dedicated 10 GiB file, never the read file. Record `stat` size/blocks, `filefrag` count (host), and ext4 mount options (`findmnt`). |
| F13 | ext4 mount: `/mnt/TestData` is `ro,nosuid,nodev,relatime,seclabel` in the sandbox `[V]`; record the host's exact mount options. Read-only cells must use `--readonly` (fio) / read-only fds (JUring) so a typo cannot modify the dataset. |
| F14 | Background-load gate: before and after each cell record `uptime`, `ps -eo pcpu,comm --sort=-pcpu | head`, and the loadavg in the artifact. Abort/redo a cell if loadavg(1m) > 0.5 or a foreign process >20 % CPU appears. The sandbox is shared with another teammate's compiles — annotate runs, do not silently pool. |
| F15 | Same clock/metric definitions: IOPS = completed ops / measured window (exclude warmup); MiB/s = bytes/2²⁰/s; latency measured from before submit to completion callback (JUring), fio `clat` (not `lat`), io_tester completion time; per-op CPU = (user+kernel cycles)/ops from `perf stat` around the whole process. |

## 5. Device-state control (writes are the hard part)

| # | Control |
|---|---|
| D1 | **Preconditioning (writes).** Before a write session: write the 10 GiB write file to steady state — 3× file size of 4K randwrite at QD64 (≈30 GiB) or 120 s minimum; then `fsync`. Do not start a measured write cell on a fresh file or after a long idle. Record "file write generation" and total bytes written since last idle. |
| D2 | **SLC cache / write cliff.** This drive's SLC policy is **UNVERIFIED** (no public spec; `nvme smart-log` blocked `[S]`). Capture an IOPS-vs-time series at 1 s resolution for every write cell; detect the cliff by a >30 % step drop and report burst and steady-state *separately*. Never pool burst and steady-state reps. |
| D3 | **Rest periods.** Uniform: 300 s idle between W5-W8 reps, 600 s after a preconditioning burst; record idle duration. The README "let the NVMe rest" claim (README.md:42) is only valid with a recorded rest protocol — currently none is documented. |
| D4 | **TRIM/discard.** The test partition has no `discard` mount option `[V]` (and automatic discard is not used). Never `blkdiscard` a shared data partition; never issue discard during a comparison. If discard studies are needed later, do it on a dedicated scratch namespace and reset state between engines identically. |
| D5 | **Thermal.** Record drive temperature and throttle counters before/after each cell (`sudo nvme smart-log /dev/nvme1n1`, host only `[S]`); ambient/CPU temp via `turbostat`/`sensors`. Abort a cell if temp rises >5 °C within the cell or a throttle counter increments; discard and redo after cool-down. |
| D6 | **Run order.** Read-only cells: interleave engines/reps (A,B,A,B…) so slow drift cancels; write cells: monotonic order is required by preconditioning, so randomize *within* a preconditioned block and always report order. Never compare a "fresh drive" run of engine A to a "worn drive" run of engine B. |
| D7 | **Write variance reporting.** Report all 3 reps, the range, and the time-series shape; a write cell with CV >10 % is reported as "unstable, no ranking", and the cause (cliff, GC, thermal) is recorded. Do not compute a "speedup" from overlapping write CIs. |
| D8 | **Do-not-compare checklist.** Do not compare: (a) different QD/thread counts; (b) O_DIRECT vs buffered; (c) fio default batching vs JUring fused submit without the batching sweep; (d) JUring-with-fixed-buffers vs seastar-without; (e) single-file vs multi-file cells; (f) cold vs warm page cache; (g) burst vs steady-state write; (h) runs from different thermal/governor/load states; (i) JMH op/s of a 2048-op invocation to fio's time-based IOPS without normalizing the window. |

## 6. Metrics & instrumentation

| Metric | Source | Exact command / formula | Sandbox |
|---|---|---|---|
| IOPS, MiB/s | engine output | `iops = ops / (t_end - t_start)` after warmup; `mib = bytes / 2^20 / s` | ok |
| Latency p50/p95/p99/p999/max | fio | `--percentile_list=50:95:99:99.9:100` (+`--clat_percentiles=1`, `--lat_percentiles=0`) | ok (read-only) |
| Latency percentiles | HdrHistogram | `org.HdrHistogram.Histogram(1,60e9,3)` then `outputPercentileDistribution` (`DirectLatencyBenchmark.java:225-226,338-374`) | ok |
| Latency percentiles | seastar io_tester | YAML `latencies.{average,p50,p95,p99,p999,max}` usec (`apps/io_tester/io_tester.cc:688-700,273`) | host |
| CPU cycles/instructions user+kernel per op | perf stat | `perf stat -e task-clock,cycles:u,cycles:k,instructions:u,instructions:k -o perf.txt taskset -c 1 fio ...`; divide by ops | **works** `[V]` |
| Syscalls per op | tracepoint | `perf stat -e raw_syscalls:sys_enter -o syscalls.txt taskset -c 1 fio ...`; `syscalls/op = count/ops` | **works** `[V]` (29 events for `true`) |
| Context switches per op | perf/proc | `perf stat -e context-switches` or read `/proc/<pid>/status` `voluntary_ctxt_switches`+`nonvoluntary_ctxt_switches` before/after | ok |
| io_uring specifics | tracepoints | `perf record -e io_uring:* -e raw_syscalls:sys_enter -- taskset -c 1 <engine>`; `perf report --stdio --sort=symbol` | host; events may differ by kernel |
| Device-side queue | sysfs | `/sys/block/nvme1n1/stat`, `/sys/block/nvme1n1/queue/nr_requests` (`IoHelper.java:219-244`) — check `read`/`write` sectors moved matches engine bytes (10 % gate) | readable `[V]` |
| Scaling efficiency | derive | `SE(N) = IOPS(N) / (N × IOPS(1))`; also per-shard min/mean/max spread for seastar shards | derive |
| Per-core efficiency | derive + perf | `ops/s / active_cpus` and `cpu_util = task-clock / (active_cpus × elapsed)`; report IOPS per core at equal core budget (required for L2 claims) | ok |
| Parse fio JSON | jq | `jq -r '.jobs[0] | {iops:.read.iops, mib:.read.bw_bytes/1048576, p50:.read.clat_ns.percentile."50.000000", p99:.read.clat_ns.percentile."99.000000", p999:.read.clat_ns.percentile."99.900000", p100:.read.clat_ns.percentile."100.000000", cpu_u:.read.cpu.user, cpu_s:.read.cpu.sys}' fio.json` | ok |
| Throughput-vs-QD / latency-vs-QD curves | re-run W1-W4/W15-W16 | plot IOPS and p99 vs QD per engine; the curve is more robust than a single point | derive |

`perf` caveat: `perf stat` around the whole process includes startup and JIT; for per-op CPU use the
steady-state window only (run perf on the already-warmed process with `-p PID` if possible), and never
compare a JVM run measured with `perf` to a fio run measured without (use the same wrapper for both).

## 7. Statistics & acceptance thresholds

**Repetitions.** Throughput cells R=5 (writes R=3 due to cost), latency cells R=5. Each rep is a new
process/JVM; within a rep, discard warmup, measure the window. Do not average across engines without
pairing: interleave A/B reps so drift is common-mode.

**Reporting.** `mean ± 95 % CI(t, df=R−1)` **and** min/max/median, plus `n`, `CV=s/mean`. Never use
"±" without saying what it is — the existing docs do not define it (BENCHMARK.md:19-29 and
README.md:27-39 mix JMH-style spreads with fio spreads; `[U]`).

**Outliers.** Keep every rep. An outlier may be *removed only with an instrumented cause*
(loadavg spike, `dmesg` NVMe reset, thermal throttle, disk full) and must be replaced by a full rerun;
at most 1 of R removed per cell. If >1, the cell is invalid and reported as such — never trimmed
silently.

**Minimum detectable difference (MDE).** With per-group CV `s` and R reps, the two-sided 95 %
difference CI is ≈ `t_{0.975,2R−2} · s · √(2/R)`. For R=5 and `s=2 %` → ≈2.9 %; for R=7 → ≈2.5 %.
Paired/interleaved runs reduce this (use SD of per-rep ratio, `s_d`). **Never claim a delta whose
confidence interval includes 0**, and never claim "parity" from two overlapping CIs — test the ratio
(or the paired difference) directly.

**Decision rule.**
- `parity` at ±5 %: 90 % CI of the ratio (JUring/comparator) lies inside `[0.95, 1.05]` (equivalence /
  TOST logic). If the CI is wider than the bounds, the result is **"inconclusive, more reps needed"**,
  not "parity".
- `faster` by ≥5 %: 95 % lower bound of the ratio > 1.05 (and the same for `slower`: upper < 0.95).
- anything between → "no measurable difference at this power", with the achieved CI half-width quoted.

**Milestone mapping (read this first).** The task brief labels *M1 = single-shard engine parity* and
*M2 = multi-shard/framework parity*; the Lead's `docs/internal/SEASTAR-PLAN.md` §5 labels *M1 = L0 parity report*,
*M2 = L1 file/stream parity*, *M3 = L2 scaling*. The two schemes are reconciled here: **task-M1 =
plan-M1** (plus the L1 latency part of the L0 report); **task-M2 = plan-M3**; **plan-M2 (stream
parity) is W21-W22 and conditional** on JUring stream basics landing (plan items B1/B2/B6). Pass/fail
uses the plan's published bars, with stricter "parity claim" bars added where a claim (not just a
report) is made.

**M1 — single-shard engine parity (task-M1 = plan-M1).** Pass iff, for all read throughput cells
(W1-W4, W9-W11) and steady-state write cells (W5-W8, W12-W14), against fio-uring with **matched
batching and registration**:

> **0.1.0 disclosure / correction.** The signed M1 read-path report used fio with registration
> (`--registerfiles=1 --fixedbufs=1`) but **default submit batching**, because fio cannot set
> JUring's `SINGLE_ISSUER`/`COOP_TASKRUN`/`DEFER_TASKRUN` flags or fuse submits the same way.
> The read cells are therefore a **best-config capability comparison**, not the equal-config gate
> written above. The device-state gate and fast-state subset were calibrated after the first
> session (report §4), not pre-registered. The 0.1.0 claims rerun
> (`bench/m1/run-claims-0.1.sh`) adds fio `matchedbatch` (`--registerfiles=1 --fixedbufs=1
> --iodepth_batch_submit=qd`) and rotated engine order for W3/W4. Until that control run is
> published, read comparisons against fio are **best-config**, not equal-config; run the script
> on an idle benchmark host and publish the rows together with the historical reference. The
> write cells follow the original gate and remain `INCONCLUSIVE`.
1. **Plan gate:** at the saturation QDs (W3/W4, W7/W8) `IOPS_JUring ≥ IOPS_fio − 5 %` (plan-M1
   criterion), equivalently a one-sided 95 % lower bound of the ratio ≥ 0.95;
2. **CPU gate:** `cycles/op` (user+kernel) within 15 % of fio at the same QD (plan-M1 criterion);
3. **Tail gate:** W15/W16 `p99 ≤ 2 × fio p99` at the same QD (plan-M1 criterion); a **parity claim**
   needs the stricter `p99 ≤ 1.10 ×` and `p999 ≤ 1.15 ×` with a non-overlapping difference CI;
4. **seastar:** W1-W4/W15-W16 measured on `--reactor-backend=io_uring` and reported as a bracket with
   the §3.5 asymmetry table; no pass threshold is attached to seastar until its ring flags/registration
   are matched (a gap is not a JUring win, nor a seastar loss);
5. every cited number has a CI (plan-M1 criterion) and no delta claim whose CI contains 0.
Fail if 1-3 fail at the saturation cells; "inconclusive, more reps" if the achieved CI is wider than
the gate. If the device is the limit (fio multi-job reference within 5 % of single-thread), a pass may
be declared on the ceiling: `min(IOPS)/max(IOPS) ≥ 0.95` among engines.

**M2 — multi-shard/framework parity (task-M2 = plan-M3).** At N ∈ {1,2,4,8} shards on W17-W20,
compare JUring N threads × qd64 vs seastar `--smp N` × parallelism 64 vs `fio numjobs=N`, all on CPUs
1..N:
1. **Plan gate:** 8-thread throughput ≥ 85 % of `fio numjobs=8` (plan-M3 criterion);
2. **Plan gate:** scaling efficiency `SE(N) = IOPS(N)/(N × IOPS(1)) ≥ 0.75` at N=8 (plan-M3); report
   `SE(4)/SE(8)`;
3. total IOPS ratio (JUring/seastar-uring) 90 % CI lower bound ≥ 0.90;
4. per-shard spread `min_shard_IOPS / mean_shard_IOPS ≥ 0.85` (seastar shards and JUring threads);
5. p99 at N=8 ≤ 1.15 × the 1-shard p99 for both engines;
6. plan-M3 also requires metrics `<1 %` overhead — **not measurable yet** (no JUring metrics, plan B6);
   mark absent, do not pass it silently.
M2 passes if rules 1-5 hold at both N=4 and N=8; the run is declared "device-limited" if the fio
reference shows saturation first. `fio numjobs=N` is the neutral referee for this milestone.

**Plan-M2 (L1 file/stream parity) — conditional.** Only after JUring exposes stream basics: W21/W22
seq 1M read/write throughput ≥ 85 % of seastar `fstream` (plan-M2 criterion), bounded in-flight memory
reported, correctness suite green, and L0 numbers unchanged. Until then W21/W22 are marked "not
comparable" and excluded from every milestone table.

## 8. Output schema, artifacts, reproducibility manifest

All engines normalize to one JSON object per cell (one file per engine/cell/rep; aggregate at the end):

```json
{
  "schema_version": "1.0",
  "run_id": "2026-11-13/W3__juring__io_uring__bs4k__qd64__t1__r1",
  "layer": "L0|L1|L2",
  "engine": "juring|juring-blocking|fio-io_uring|fio-libaio|seastar-io_tester|seastar-fstream_perf|filechannel",
  "engine_version": "JUring git 4526338 | seastar 04b1c04d | fio 3.40",
  "backend": "io_uring|asymmetric_io_uring|linux-aio|epoll",
  "workload": "randread|randwrite|seqread|seqwrite|mixed|nop|stream_read|stream_write",
  "bs": 4096, "qd_per_domain": 64, "domains": 1, "total_qd": 64,
  "file": "/mnt/TestData/read_files/single_file_bench.bin",
  "size": 10737418240,
  "duration_s": 30, "warmup_s": 10, "reps": 1,
  "results": [{
    "rep": 1, "iops": 0, "mib_s": 0, "bytes": 0, "ops": 0, "errors": 0,
    "lat_us": {"p50": 0, "p95": 0, "p99": 0, "p999": 0, "max": 0},
    "cpu": {"cycles_u_per_op": 0, "cycles_k_per_op": 0, "instr_u_per_op": 0, "instr_k_per_op": 0},
    "syscalls_per_op": 0, "ctxt_switches_per_op": 0,
    "timeseries_1s": {"iops": [], "mib_s": [], "lat_p99_us": []},
    "flags": {"direct": true, "registered_files": true, "fixed_buffers": true,
              "iodepth_batch_submit": null, "ring_flags": "SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN"}
  }],
  "aggregate": {"n": 5, "iops_mean": 0, "iops_sd": 0, "iops_ci95": [0, 0],
                "iops_median": 0, "cv_pct": 0, "p99_mean": 0},
  "validity": {"status": "valid|invalid|unstable", "reason": "", "outliers_removed": 0},
  "manifest": {
    "kernel": "7.0.12-201.fc44.x86_64", "cpu": "AMD Ryzen 9 3950X 16C/32T",
    "device": "nvme1n1 GIGABYTE GP-ASM2NE6100TTTD sn SN200908901967",
    "mount": "/mnt/TestData ext4 ro,nosuid,nodev,relatime,seclabel",
    "juring_git": "4526338", "seastar_git": "04b1c04d", "jdk": "OpenJDK 25.0.3",
    "fio_version": "fio-3.40", "perf_version": "7.2.11-200.fc44",
    "governor": "performance", "hugepages_total": 0, "loadavg_1m": 0.3,
    "taskset": "1..N (N shards, cores 1..N)", "command_line": "...",
    "session_started_utc": "...", "order_in_session": 7
  }
}
```

**Artifact naming** (as required by the Lead's plan): `bench/results/YYYY-MM-DD/` —
raw engine outputs `W<id>__<engine>__<backend>__bs<bs>__qd<qd>__t<domains>__r<rep>.<json|log|txt>`,
`manifest.txt` (session manifest, see existing `bench/results/2026-11-13/manifest.txt`),
`notes.md` (anomalies/order), `aggregate/` (one normalized JSON/CSV per table row), and a frozen
`METHODOLOGY.sha256`. Parsers: `bench/parse_results.py` (task-4) must emit the schema above for
fio JSON (`jobs[].read/write.iops`, `.clat_ns.percentile`, `.user/sys` CPU), io_tester YAML
(`IOPS`, `throughput` kB/s, `latencies`, `metrics`), and JUring JSON.

**Reproducibility gate.** A cell is only citable if all of these are in its artifact: exact command
line, engine commit/version, backend, ring flags/batching/registration, file+size+alignment, CPU set
and SMT policy, governor, JVM flags, warmup/duration/reps, loadavg before/after, and raw per-rep
values. Cells missing any of these are marked `[U]` and excluded from M1/M2.

## 9. Threats to validity, and an evidence-based critique of the existing claims

### 9.1 Claim audit

**Locate the claims correctly before quoting them.** The fio-vs-JUring table is **not** in
`BENCHMARK.md`; it is in `README.md:24-42`. `BENCHMARK.md` contains the scaling + virtual-thread
tables (`BENCHMARK.md:40-132`) and the harness description (`:19`). The brief's "BENCHMARK.md claims"
(322,908 ± 13,644 etc.) are README.md:28-30,36-39; both files are treated below. Neither file records
the engine commands, the `±` definition, or the CPU pinning, so none of these numbers currently meets
the reproducibility gate in §8.

| Claim (location) | Verdict | Evidence |
|---|---|---|
| "fio best batching 322,908 ± 13,644 vs JUring 325,676 ± 9,495 → +0.9 %" (README.md:28,30) | **Not established.** The spreads are larger than the 2,768 op/s delta; the 95 % intervals overlap almost entirely. Also the "±" convention is undefined. | README.md:28,30; no command recorded; choose the interval convention before comparing |
| "fio default 310,610 ± 12,689" (README.md:27) | **Not reproducible.** No fio command/version/queue-depth/batching is recorded anywhere in the repo; "default settings" hides `iodepth_batch_submit=1` (`HOWTO.rst:3276-3280`), which is the main reason JUring looks better. | README.md:27; `/usr/share/doc/fio/HOWTO.rst:3276-3280` |
| write: "fio best batching 205,278 ± 64,687 = baseline; JUring −5.6 %" (README.md:36-39) | **Contradictory.** fio "default" (214,941 ± 33,471) is *higher* than the chosen baseline; the −5.6 % is computed against a slower-than-default fio configuration, and the CIs (±31-66 k) dwarf both deltas. Also the README says "best batching B=16" but default is faster — the sweep selection is inconsistent between read and write tables. | README.md:36-39 |
| "fio and JUring achieve identical ~190 k write / ~325 k read, both at flash limit" (README.md:42) | **Unsupported for writes** (single-run, no CI, no steady-state protocol) and only weakly for reads (overlapping CIs). | README.md:42; BENCHMARK.md:74 (author admits write variance) |
| "Letting the NVMe rest, JUring ~450 k burst write vs fio ~320 k, due to DEFER_TASKRUN" (README.md:42) | **Unverified.** No table, no CI, no rest protocol, no fio command, no same-config-without-DEFER_TASKRUN control in either doc. The mechanism is plausible (kernel task_work) but the measurement is missing. Mark `[U]`. | README.md:42; `IoUringOptions.java:16-21` (flag is set) |
| "4 threads+ JUring saturates NVMe at ~478,000 ops/s" (BENCHMARK.md:59) | **Weak.** 4T 474,169 ± 9,109; 8T 436,041 ± 66,223; 16T 477,680 ± 1,338 — non-monotonic, and no fio multi-job reference is provided, so "saturation" cannot be attributed to the device. | BENCHMARK.md:55-57,59 |
| same 1T/QD64/4K/single-file cell appears as three different means: 330,196 ± 361 (line 26), 330,089 ± 748 (line 45), 319,847 ± 40,984 (line 53) | **Internal inconsistency** (3 % spread in means, 100× spread in spreads) across three benchmarks in one document. Any one cannot be quoted as "the" baseline. | BENCHMARK.md:26,45,53 |
| write scaling "2.6×, 1.6×, 1.0×, 1.0×, 0.9×" (BENCHMARK.md:67-71) | **Not supported.** e.g. 2T 209,755 ± 162,797 (78 % spread); 0.9× at 16T with JUring ±147,764. These are overlapping-noise ratios, not measurements. | BENCHMARK.md:67-71,74 |
| VT: "1-2 threads = 34.91× vs 4 threads = 10.50×" (historical README/BENCHMARK) | **Over-precise / resolved in 0.1.0.** JUringBlocking at 1-2T = 399,109 ± 89,552 vs 4T = 452,193 ± 1,263; the means overlap within 1 CI. The 0.1.0 docs label the VT table as historical JMH, not a ranking, and state the defensible result (parity at Loom defaults; thread efficiency under a fixed budget). | README.md §Virtual Thread Performance; BENCHMARK.md Part II §3 |
| no seastar baseline anywhere | **Confirmed gap.** Zero occurrences of "seastar" in `*.md`. | `grep -in seastar *.md` → none |
| "L0 measured; JUring at or above fio parity" (`docs/internal/SEASTAR-PLAN.md` §0, from `docs/internal/baseline-2026-11-13.md`) | **Provisional, must be re-stated under this protocol.** The baseline is 3×10 s runs reported as mean ± stdev, not the matched-batching/registration comparison with CIs required by §7; cite it as "provisional" until W3/W4 pass rules 1-3. | `docs/internal/SEASTAR-PLAN.md` §0; §7 here |
| JMH harness = fio workload | **Partly false.** JUring JMH uses registered files + fixed buffers (`DirectReadBenchmark.java:106-112`); fio "default" does not. That is exactly the L0 asymmetry being measured, so the comparison conflates engine and buffer registration. | README.md:27-30 vs `DirectReadBenchmark.java:106-112` |
| "5 forks × 3 iterations × 3 s" (BENCHMARK.md:19,49,63,78) | **Unverifiable.** Annotations say `@Fork(1)` (`DirectReadBenchmark.java:26-28`); CLI overrides are not recorded. | BENCHMARK.md:19; Source file |

### 9.2 Structural threats

- **Single machine, single drive, single kernel.** All results are one GIGABYTE NVMe on one Ryzen
  host; no generalization to other NVMe/controllers/CPU families. State it in every table caption.
- **Consumer-drive SLC/GC state** dominates writes: burst vs steady-state are different devices
  logically; only steady-state is rankable, and only with a time series.
- **JVM JIT/GC**: JUring has ~2-5 % per-op overhead vs native (`NopBenchmark` 11.6 M ops/s,
  `BENCHMARK.md:23`), but JIT can also optimize the loop unrealistically; enforce steady state, one
  GC, fresh JVM per rep, and report GC logs.
- **fio vs JMH scheduling**: fio time-based IOPS over a window vs JMH ops-per-invocation normalized
  by iteration time are different estimators; never mix them. The `.9 %` claim does exactly this.
- **Sandbox sharing**: workspace load (compiles) and the main workspace on btrfs (nvme0n1) mean CPU
  interference is real; the loadavg gate (F14) is mandatory, not decorative.
- **`/mnt/TestData` being 80 % full** (BENCHMARK.md:6) is `[U]`: in this sandbox only the 10 GiB file
  is visible; free-space/fragmentation state must be captured on the host (`df -h`, `filefrag`).
- **seastar build config** (debug/release, compiler, LTO, `--enable-tests`) changes framework
  overhead; pin and record it. Only `--mode=release` numbers are comparable to fio/JVM.
- **Kernel version drift**: DEFER_TASKRUN/task_work behavior changed across kernels; results are tied
  to 7.0.12-201. Re-run M1 on any kernel change.

## 10. First 10 commands to validate the host before trusting any number

Run on the user's host (not the sandbox), from `/home/david/projects/JUring`:

```bash
# 1. Identity + kernel + CPU topology (SMT pairs, NuMA)
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

# 10. seastar build prerequisites on the host (skip only if seastar numbers are out of scope)
cmake --version | head -1; ninja --version; pkg-config --modversion liburing; g++ --version | head -1; ls /dev/hugepages >/dev/null 2>&1 && echo "hugetlbfs present" || echo "hugetlbfs MISSING"
```

**Go/no-go:** commands 2, 5, 6, 7, 8 must all succeed; 9 must succeed for write/cold-cache studies;
10 is required for any seastar comparison. If 5 (io_uring disabled) or 6 (perf blocked) fails, stop —
the numbers will not be comparable to this methodology.
