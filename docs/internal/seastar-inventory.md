# Seastar file-I/O & benchmark-tooling inventory

**Provenance.** Static source analysis of the seastar checkout (originally `/home/david/projects/seastar`, now the in-repo `SeaStarRef/` copy) @
`04b1c04dcd056d9d9b276ea5c42c9687bc834493` (`git describe` =
`seastar-25.05.0-1392-g04b1c04d`, commit date 2026-11-11) plus in-repo docs. No
build/run was attempted: no cmake/ninja/liburing/boost/fmt here and the tree is a
read-only mount. All `path:line` cites are relative to that seastar root unless
prefixed `JUring:` (relative to `/home/david/projects/JUring`). Anything not
verifiable from source here is marked **UNVERIFIED**.
Audience: the Lead's `SEASTAR-PLAN.md`, and whoever writes `bench/seastar/` configs.

## 0. Corrections to prior assumptions, headline findings

1. **Default backend is io_uring when detected**: `available()` order is io_uring,
   asymmetric_io_uring, linux-aio, epoll (`src/core/reactor_backend.cc:2650-2666`);
   CLI default is first non-asymmetric (`src/core/reactor.cc:4039-4048`). The comment
   "Default: linux-aio" in `include/seastar/core/reactor_config.hh:168-177` is stale.
2. **No `--max-io-requests` option exists in this commit** (tree-wide grep: none).
   Equivalent caps: 16 MiB max request (`include/seastar/core/io_queue.hh:405`),
   ring length 200 (`src/core/reactor_backend.hh:467`), 1024 IOCBs for linux-aio
   (`src/core/reactor_backend.hh:78`).
3. **`max_aio_per_queue = 128` is not aio queue depth.** It sizes
   `reactor::max_aio = 128*8 = 1024` (`include/seastar/core/reactor.hh:235-237`) used
   for `aio-max-nr` budgeting (`src/core/reactor.cc:4390`); the aio backend's real
   cap is its 1024-IOCB pool per shard.
4. **Normal io_uring ring has no special flags.** `params` is zero before
   `io_uring_queue_init_params` (`src/core/reactor_backend.cc:1259-1262`); required
   features are only `SUBMIT_STABLE|NODROP` (`:1236-1240`). No
   SINGLE_ISSUER/COOP_TASKRUN/DEFER_TASKRUN/SQPOLL/IOPOLL, and the only
   `io_uring_register()` in the tree is `iowq_aff` for the asymmetric backend
   (`:2172`) — no registered files/buffers.
5. **`file` has no public plain `read()`/`write()`**: only `dma_*` plus metadata ops
   (`include/seastar/core/file.hh:359-624`); byte-stream read/write is fstream
   (`include/seastar/core/fstream.hh:74-133`).
6. **io_tester passes `io_intent` only in rps mode** (`apps/io_tester/io_tester.cc:372-406`);
   default QD jobs pass `nullptr` (`:358-370,724-727`), so QD sweeps do not exercise
   cancellation.
7. **io_tester stdout is not pure YAML**: progress lines print before the document
   (`io_tester.cc:1361,1371,1386,1390`); parse from the first `---`.
8. **No disk-parameter auto-detection in the reactor.** Without
   `--io-properties(-file)` rates default to `uint64_t::max()`
   (`include/seastar/core/disk_params.hh:40-48`) → effectively unthrottled
   (`src/core/disk_params.cc:178-191`). Auto-tuning is the separate `iotune` tool.
9. **"seqwrite" does not exist**: the write types are `overwrite` (sequential from 0,
   wraps), `randwrite`, `append` (`io_tester.cc:77,422-432`). Map seqwrite→overwrite.

## A. Identity, build requirements, build commands

* Version `seastar-25.05.0-1392-g04b1c04d`; commit above; CMake project version is a
  placeholder `1.0` (`CMakeLists.txt:46-48`).
* API level: CMake default 9 (`CMakeLists.txt:72-77`), but `configure.py` passes
  `--api-level` default 10 (`configure.py:161`), so a configure.py build is level 10;
  header fallback 9 (`include/seastar/core/internal/api-level.hh:25-33`).
* C++23 or C++26, auto-selected (`BUILD.md:140-149`).
* Deps: `sudo ./install-dependencies.sh` (`BUILD.md:12-16`); Fedora list includes
  cmake, ninja-build, gcc-c++, boost-devel, fmt-devel, liburing-devel,
  yaml-cpp-devel, numactl-devel, hwloc-devel, gnutls/openssl/protobuf/lz4/sctp dev
  (`install-dependencies.sh:125-149`).
* Build on a normal host:
  ```
  sudo ./install-dependencies.sh
  ./configure.py --mode=release        # build/release (RelWithDebInfo, -O3)
  ninja -C build/release
  ```
  (`BUILD.md:12-31`, `HACKING.md:60-68`). Pass `--mode=release`: `--mode` defaults to
  `all` = debug+release+dev+sanitize+fuzz (`configure.py:151,275`). release is ~2x
  dev (`BUILD.md:45-60`). io_uring is on by default, silently off if liburing is
  absent (`CMakeLists.txt:316-318,1200-1206`); force `--enable-io_uring`
  (`configure.py:217-222`).
* `ninja -C build/release apps` builds all apps (`apps/CMakeLists.txt:23`), per-app
  CMake target `app_<name>` (`:25-44`). Expected artifacts (CMake-derived,
  **UNVERIFIED** unbuilt): `build/release/apps/io_tester/io_tester`,
  `.../ioinfo/ioinfo`, `.../iotune/iotune`,
  `build/release/tests/perf/fstream_perf`,
  `build/release/tests/perf/io_queue_latency_perf`
  (`apps/io_tester/CMakeLists.txt:23-31`, `apps/iotune/CMakeLists.txt:23`,
  `tests/perf/CMakeLists.txt:26-62,64,83-84`).
* Tests: `ninja test_unit` / `ninja test` in the build dir (`HACKING.md:70-96`).
  io_tester is an app, not driven by `test.py` (no reference there).

## B. Public file-I/O API a benchmark can exercise

**Open.** `open_file_dma(name, flags[, file_open_options])`
(`include/seastar/core/seastar.hh:214,229`; impl `src/core/reactor.cc:1873-1949`).
`open_flags`: rw/ro/wo/create/truncate/exclusive/dsync
(`include/seastar/core/file-types.hh:41-49`). O_DIRECT is added by `fcntl` post-open
unless tmpfs/hugetlbfs or `--kernel-page-cache`/`--relaxed-dma`
(`src/core/reactor.cc:1879-1916`); open itself runs on the thread pool (`:1876-1878`).
`file_open_options` (`include/seastar/core/file.hh:91-105`):
`extent_allocation_size_hint` 1 MiB default, applied via XFS
`XFS_IOC_FSSETXATTR` and aligned to 128 KiB (`src/core/reactor.cc:1917-1936`,
`file.hh:99-104`); `sloppy_size`/`sloppy_size_hint`, `create_permissions`,
`append_is_unlikely`, `durable` (false or `--unsafe-bypass-fsync` skips O_DSYNC and
flush, `src/core/reactor.cc:1897-1900`, `src/core/file.cc:218-223`).
Helpers: `open_directory`, `make_directory`, `check_direct_io_support`,
`remove_file`, `rename_file` (`seastar.hh:245-324`).
Alignment/limits: `disk_{read,write,overwrite}_dma_alignment()`,
`memory_dma_alignment()`, `disk_{read,write}_max_length()`
(`file.hh:310-357`; defaults 4096 / 1 GiB, `file.hh:214-219`, clamped by io_queue on
open `src/core/file.cc:175-176`). Alignment is discovered per FS/device (XFS
`XFS_IOC_DIOINFO`, statx `DIOALIGN`, `BLKSSZGET`/`BLKPBSZGET`)
(`src/core/file.cc:1250-1380`).

**Data ops** (`include/seastar/core/file.hh`):
`dma_read(pos, buf, len)` aligned (`:359-377`); allocating `dma_read(pos,len)`
returns `temporary_buffer`, may be short at EOF (`:379-399`); `dma_read_exactly`
throws `eof_error` on short (`:401-423`, `src/core/file.cc:1616-1624`); iovec
`dma_read` (`:425-439`); `dma_write` + iovec, short write possible (`:441-470`);
`dma_read_bulk` aligned bulk read (`:632-652`, `src/core/file.cc:650-745`).
Metadata: `flush` = fdatasync unless non-durable/O_DSYNC (`:472-476`,
`src/core/file.cc:218-223`); `stat`/`statat` (`:478-485`); `truncate`
(`:487-488`); `allocate` (`:490-502`); `discard` (`:504-508`); `mmap` (tmpfs/shm
only, warned against, `:114-192,510-525`); `ioctl`/`fcntl` (+`_short`)
(`:527-583`); lifetime hints (`:585-609`); `size`/`close` (`:611-624`);
`dup()`/`file_handle` for cross-shard read-only access (`:654-662,740-766`).

**Semantics.** Errors surface as `std::system_error`/exceptions; short reads at EOF
return fewer bytes; short writes possible; no automatic retry at the file layer
(bulk reads internally loop and tolerate EINVAL at EOF,
`src/core/file.cc:650-745`). Cancellation exception is `cancelled_error`
(`file.hh:770-776`). **There is no per-operation timeout in the public API** — only
cancellation via `io_intent`; use app-level timers.

**fstream.** Input `file_input_stream_options{buffer_size=8192, read_ahead=0,
dynamic_adjustments={}}` (`fstream.hh:59-63`), factories `:74-99`. Readahead issues
up to `read_ahead` extra `dma_read_bulk`s with a 4 MiB sliding window and self-tuned
buffer size, target <25% discarded data (`src/core/fstream.cc:106-235`,
`fstream.hh:46`). Output `file_output_stream_options{buffer_size=65536,
preallocation_size=0, write_behind=1}` (`fstream.hh:101-112`), factories
`:118-133`. `write_behind` = concurrent background writes via a semaphore; `flush()`
waits then `file::flush()`, `close()` waits then closes
(`src/core/fstream.cc:370-512`); unaligned tail is zero-padded + `truncate()`
(`:448-486`).

**`io_intent` (cancellation).** Requests are pinned to the intent; `cancel()`
resolves attached futures with `cancelled_error` (`include/seastar/core/io_intent.hh:30-105`).
Registry per (queue,scheduling-group) (`io_intent.hh:40-51,86-104`), attached in
`io_queue::queue_one_request` (`src/core/io_queue.cc:1032-1050`), dequeued on
dispatch (`:419-431`). Old `io_priority_class` is gone; only
`io_priority_class_id = unsigned` remains (`include/seastar/core/io_priority_class.hh:31-33`).
Per-I/O-class priority is now the scheduling group's `shares`.

## C. Engine internals: fairness and per-op overhead

**Selection/setup.** `available()` = io_uring, asymmetric_io_uring, linux-aio,
epoll (`reactor_backend.cc:2650-2666`); default first non-asymmetric
(`reactor.cc:4039-4048`); startup logs `Reactor backend: <name>` (`reactor.cc:4763`).
Probes require `SUBMIT_STABLE|NODROP` (+`SQPOLL_NONFIXED|FAST_POLL` for asymmetric),
opcode support, and (kernel <5.12) 8 MiB mlock; pre-5.17 with md/RAID refused
(`reactor_backend.cc:1236-1240,1312-1347,2062-2066`). On kernel 7.0.12 detection
should succeed but seastar is unbuilt here — **UNVERIFIED**.

**Normal uring ring**: `io_uring_queue_init_params(200, &ring, &params)` with
zeroed params; `io_uring_ring_dontfork` (`reactor_backend.cc:1236-1290,1259-1262`);
`QUEUE_LEN=200` "more or less arbitrary" (`reactor_backend.hh:464-468`).
**Asymmetric ring**: base `SQPOLL|SQ_AFF`, `sq_thread_cpu`, `sq_thread_idle=5ms`,
iowq affinity (`reactor_backend.cc:2143-2180`); proxies
`ATTACH_WQ|SQPOLL` (`:2132-2139`); `--async-workers-cpuset` mandatory
(`:2398-2404`, `doc/reactor_backend_asymmetric_uring.md:84-108`).
**linux-aio**: `aio_storage_context`, `io_setup(1024)`, 1024-entry IOCB pool per
shard, `io_submit`/`io_getevents` (`reactor_backend.hh:76-109`,
`reactor_backend.cc:147-160,289-338,397-417`); EAGAIN retried in a coroutine,
counted in `aio_retries` (`:405-417`, `reactor.hh:190-211`).

**File-I/O path.** `posix_file_impl::do_read/write_dma` builds an
`internal::io_request` → `io_queue::submit_io_read/write` → `queue_request`
(`src/core/file.cc:599-623`, `io_queue.cc:1103-1115`). Oversized requests are split
(`io_queue.cc:1052-1110`, max = 16 MiB `io_queue.hh:405`); each part allocates a
`queued_io_request`+`io_desc_read_write` and enqueues a fair-queue entry
(`io_queue.cc:1032-1050`). Reactor `flush_pending_aio()` runs `poll_io_queue()` for
all queues (`reactor.cc:1822-1831`); dispatch pops entries as token-bucket capacity
allows and pushes `io_request`s into reactor `_io_sink`
(`io_queue.cc:1147-1197`). Backend `kernel_submit_work()` drains the sink:
uring→`prepare_sqe` READ/WRITE/READV/WRITEV/FDATASYNC then one
`io_uring_submit` per poll (`reactor_backend.cc:1651-1700,1358-1440`); completions
via `io_uring_peek_batch_cqe(...,QUEUE_LEN)` + `complete_with`
(`:1709-1723`); aio→drain to iocb pool while capacity, `io_submit`,
`io_getevents` (`:289-338,397-417`). Completion resolves a promise and the
continuation runs later as a reactor task (not inline). Poller order reap → io-queue
dispatch → submit → reap (`reactor.cc:3349-3376`); the io_queue poller arms a
high-res timer when token bucket says "wait" (`reactor.cc:2950-2990`).

**XFS/ext4/btrfs append-challenged files.** Size-changing writes (beyond committed
size, truncate, allocate, exclusive flush) are serialized one at a time unless
`append_is_unlikely`; speculative `ftruncate` allows some concurrency
(`src/core/file.cc:824-970`; FS flags `:1396-1440`; XFS concurrency 1 on modern
kernels `:1211-1220`). io_tester sets `append_is_unlikely` except for `append` jobs
(`io_tester.cc:613`), so randwrite/overwrite are not serialized, but `append` is.

**Queue caps / splitting / cost model.** Effective max size is computed from token
capacity, so pinned low `--io-properties` can shrink it (`io_queue.cc:752-800`,
`io_queue.hh:405`, `io_queue.cc:1024-1029`); splits are counted in
`total_split_ops`/`aio_outsizes` (`io_queue.cc:1064-1080`, `io_tester.cc:684`).
Per-op token cost `= weight/req_count_rate + size/block_count_rate`, reads weighted
by `read_request_base_count=128` (`io_queue.hh:160-169`, `io_queue.cc:991-1010`,
`doc/io-scheduler.md:1-40`); with no io-properties both rates are max() → cost ~0.

**Overhead vs a raw liburing loop (JUring):** (a) two heap allocations + promise/
future per request (`io_queue.cc:1032-1050`); (b) fair-queue + double-precision
token-bucket accounting (`io_queue.cc:991-1023,1147-1190`,
`fair_queue.hh:150-230`); (c) `io_sink` chunked-FIFO staging per batch
(`core/internal/io_sink.hh:50-74`); (d) reactor poll batching: completion is a task
scheduled after the poll cycle, task quota 0.5 ms (`reactor.cc:4062`); (e)
`io_uring_submit` once per poll, SQEs batched (`reactor_backend.cc:1651-1700,1752-1757`);
(f) one ring per shard shared with networking, depth 200 (`reactor_backend.hh:467`)
— >200 in-flight file I/Os/shard makes `get_sqe()` reap completions inline (sync
fallback, `reactor_backend.cc:1640-1670`); (g) per-class scheduling-group dispatch
and metrics per op (`io_queue.cc:820-877`).

## D. Benchmark tooling

### D.1 io_tester CLI (`apps/io_tester/io_tester.cc:1263-1274`)

| Flag | Type | Default | Meaning |
|---|---|---|---|
| `--storage` | string | `.` | dir, block device, or `/dev/null` (`:1269`) |
| `--duration` | uint sec | `10` | run time after file setup (`:1270`) |
| `--conf` | string | `./conf.yaml` | workload YAML (`:1271`) |
| `--keep-files` | bool | `false` | reuse test files next run (`:1272`); needs an explicit value, `--keep-files=true` (not a switch) |
| `--sched-groups` | string | — | optional scheduling-group YAML (`:1273`) |

Plus all seastar options. Storage type is sniffed; non-XFS directory is warned
(`:1288-1293`); block devices are partitioned per shard; `/dev/null` completes every
I/O with 0 bytes, throughput uses requested size (`:585-652`, `doc/io-tester.md:16-22`).

### D.2 io_tester YAML schema (exact keys/types/defaults)

Top level: sequence of classes → `std::vector<job_config>`
(`io_tester.cc:1138-1172,1298-1299`).

| Key | Type | Req | Default | Source |
|---|---|---|---|---|
| `name` | string | yes | — | `:1141` |
| `type` | enum (D.3) | yes | — | `:1142,1024-1043` |
| `shards` | `"all"` or list<uint> | yes | — | `:1143,1010-1022` |
| `data_size` | byte size | no | 1 GiB (0 for append) | `:1147-1154` |
| `files_count` | uint | only unlink | — | `:1159-1161,822-824` |
| `shard_info` | map | no | struct default | `:1163-1165,217-232` |
| `options` | map | no | struct default | `:1166-1168,234-243` |

`shard_info` (`:1045-1092`; defaults `:217-232`):

| Key | Type | Default | Notes |
|---|---|---|---|
| `parallelism` | uint | **0** | fibers per class per shard = in-flight per shard |
| `rps` | uint | 0 | if set: rate mode (uses io_intent), else QD mode |
| `batch` | uint | 1 | requests per rate-mode wakeup |
| `limit` | uint | UINT_MAX | total requests per job |
| `shares` | uint | 10 | sched-group weight; ignored if `class` present |
| `class` | string | `""` | named sched group; mutually exclusive with `shares` (`:1061-1065`) |
| `bandwidth` | byte size | 0 | per-class bytes/s cap, applied on shard 0 (`:435-440`) |
| `reqsize` | byte size | 4 KiB | min 512 (`:224,962-979`) |
| `think_time` | duration | 0 | pause after each request |
| `think_after` | duration | 0 | alternate active/thinking phases |
| `execution_time` | duration | 1 ms | cpu type only |
| `vectorized` | bool | false | readv/writev |
| `iov_count` | uint | 1 | must divide reqsize; segments ≥4 KiB aligned (`:746-764`) |

`options` (`:1095-1136`; defaults `:234-243`): `dsync` bool=false (open O_DSYNC);
`sleep_type` lowres(default)|steady|busyloop; `pause_distribution`
uniform(default)|poisson; `extent_allocation_size_hint` byte size (default file
size, aligned 128 KiB); `sloppy_size_hint` byte size (enables sloppy_size);
`fallocate` bool=false (`:104-106`).

Parsers: byte size = raw bytes or `<n>kB|MB|GB` (lowercase `k`, uppercase `M`/`G`,
final `B`), min 512 — e.g. `512`, `4kB`, `256kB`, `10GB` (`:962-979`); duration =
`0` or `<n>ns|us|ms|s` — e.g. `1000us`, `10ms` (`:981-1008`).
`--sched-groups` YAML: sequence of `{name, shares, parent?}`; parents become
supergroups (`:1174-1184,1307-1354`; `doc/io-tester.md:95-118`).

### D.3 Workload types (`request_type`: `io_tester.cc:77,937-958`)

* `seqread` — read from 0 forward, wrap at EOF (`:531-546`, class `:716-729`).
* `overwrite` — sequential **write** from 0, wrap at EOF; io_tester's "seqwrite"
  (`:731-744`).
* `randread` — uniformly random request-aligned offset (`:527-554`).
* `randwrite` — random overwrite of existing blocks.
* `append` — always EOF; file_size default 0; append-challenged (serialized).
* `unlink` — delete `files_count` pre-created files, one per request
  (`:815-910`; requires files_count).
* `cpu` — busy-loop `execution_time` (`:913-935`).
Vectorized read/write variants use `std::vector<iovec>` (`:746-813`).

### D.4 Files, data_size, keep-files, parallelism, shards

Per class per shard one file `test-<name>-<shard>` (`:601-623`); `unlink` creates
`files_count` files `test-<name>-shard-<shard>-file-<id>` (`:888-909`). Creation:
truncate to file_size, optional `fallocate`, then for non-append types prefill with
256 KiB aligned chunks at concurrency 64 and `flush()` (`:100-126,601-623`); extent
hint defaults to file size (`:608`). `data_size` is **total** across shards:
per-shard = `align_up(data_size/smp_shards, 1MiB)` (`:1145-1154`); default 1 GiB per
shard — scale it with `--smp` to keep per-shard dataset constant. `--keep-files`
skips the setup-time unlink (`:275-279,618-622`) and skips prefill when the existing
file is already ≥ target size (`:107`). `parallelism` = fibers, each one
request-at-a-time (`:358-370`), so per-shard in-flight ≈ parallelism (×batch in rps
mode); total QD = sum over classes × shards. Placement filter instantiates a class
only on listed shards (`:1198-1207`); each class gets an implicit scheduling group
named after it with `shares` (`:1356-1374`), or shares an explicit `class`.

### D.5 Output format

One YAML document on stdout: sequence of per-shard maps `{shard: N, ...}`; each
class map followed by `statistics` (`:1246-1261,1229-1244`). I/O class
(`:688-713`):
`throughput` (kB/s = bytes>>10 / s), `IOPS`, `latencies: {average, p0.5, p0.95,
p0.99, p0.999, max}` in microseconds, and `stats: total_requests,
io_queue_total_exec_sec, io_queue_total_delay_sec, io_queue_total_operations,
io_queue_starvation_time_sec, io_queue_consumption,
io_queue_adjusted_consumption, io_queue_activations, reactor_aio_outsizes,
disk_queue_length: {p0.5,p0.9,max} (sampled every 200 ms after 500 ms warmup),
file_size_overflows`, plus `statistics: {aio_retries}` per shard.
`cpu` emits only throughput; `unlink` emits IOPS + latencies(avg,max) +
total_requests (`:931-934,849-860`). Metric meanings: total_delay = queue wait,
total_exec = time in disk, starvation = queued while nothing executing
(`src/core/io_queue.cc:843-851`); group `io_queue` (`:904,1387-1405`).
`aio_retries` only increments on linux-aio (`src/core/reactor_backend.cc:405-417`).

### D.6 Other tools

* `tests/perf/fstream_perf.cc` — output_stream write throughput into `testfile.tmp`
  in cwd (exclusive create; remove between runs). Flags `--concurrency` (default 1,
  sets write_behind), `--buffer-size` (4096), `--total-ops` (100000), `--sloppy-size`
  (false) (`:38-47`); sets preallocation 32 MiB and write_behind=concurrency
  (`:53-56`); writes total-ops buffers, flush+close, prints
  `bufsize ops iodepth IOPS` (`:73-77`). No latency. Run:
  `fstream_perf --concurrency=16 --buffer-size=65536 --total-ops=100000`.
* `tests/perf/io_queue_latency.cc` — pure scheduler test (no disk): fake sink
  completes immediately; background 128 KiB class vs foreground 4 KiB class
  (`:44-56,100-152`). Flags `--iterations 7 --nr-queues 4 --iops 10000
  --bandwidth-mb 100 --concurrency 4 --fg-concurrency 1 --poll-interval-us 250`
  (`:82-98`); logs only Average FG delay / BG IOPS (`:151-152`).
* `ioinfo` prints io_queue config and per-size request token capacities
  (`apps/io_tester/ioinfo.cc:37-39,54-90`) — check whether your pinned
  io-properties make 4 KiB requests expensive.
* `iotune` is seastar's disk auto-tuner, emitting `io-properties.yaml`
  (`apps/iotune/iotune.cc:39,800-840,868-893`):
  `iotune --evaluation-directory <writable-dir> --properties-file io_properties.yaml --duration 120`.

## E. Copy-paste recipes (normal host; **cannot run in this sandbox**)

Build once: `sudo ./install-dependencies.sh`; `./configure.py --mode=release`;
`ninja -C build/release apps` (or full `ninja -C build/release`); binaries as in A.
Every run: `--storage` must be a **writable** dir on an O_DIRECT-capable FS (XFS
ideal, `doc/io-tester.md:17`; non-XFS only warns, `io_tester.cc:1288-1293`) or block
device; `/dev/null` = framework-overhead only. On this host `/mnt/TestData` is
read-only and seastar is unbuilt, so these target a host with writable NVMe dir
(e.g. `/mnt/TestData/seastar-bench`). For single-reactor parity with a JUring single
thread pin `--smp=1 --cpuset=0` (`--smp` defaults to one shard per CPU,
`reactor.cc:4112`). Confirm `Reactor backend: io_uring` in the log.

**(1) 4K randread QD64, 1 shard** — `randread_qd64_4k.yaml`:
```yaml
- name: randread
  type: randread
  shards: all
  data_size: 10GB
  shard_info: {parallelism: 64, reqsize: 4kB, think_time: 0}
  options: {fallocate: true}
```
```
./build/release/apps/io_tester/io_tester \
  --storage /mnt/TestData/seastar-bench --conf randread_qd64_4k.yaml \
  --duration 30 --smp 1 --cpuset 0 --reactor-backend io_uring --keep-files=true
```

**(2) 1M seqread QD8** — same file with `type: seqread`,
`shard_info: {parallelism: 8, reqsize: 1MB, think_time: 0}`; drop `options`.
**(3) 4K randwrite QD64** — `type: randwrite`, `parallelism: 64`, `reqsize: 4kB`;
use a scratch writable dir, never the read-only dataset mount.
**(4) Sequential write (harness "seqwrite")** — `type: overwrite`, e.g.
`parallelism: 8`, `reqsize: 1MB`.
**(5) Multi-shard scaling** — keep per-shard dataset constant by scaling
`data_size` with shards: `--smp 2 --cpuset 0,1`, `--smp 4 --cpuset 0-3`,
`--smp 8 --cpuset 0-7`. Total QD = parallelism × smp. SMT: on the 3950X CPUs 0-15
and 16-31 are siblings; be consistent.
**(6) Backend A/B** (same config):
`--reactor-backend=io_uring`; `--reactor-backend=linux-aio`;
`--reactor-backend=asymmetric_io_uring --async-workers-cpuset=16 --smp 1 --cpuset 0`
(worker cpuset mandatory; `doc/reactor_backend_asymmetric_uring.md:84-108`).
**(7) Pinned QoS** — `io_properties.yaml` (all four rates required,
`src/core/disk_params.cc:49-52`; format `doc/io-properties-file.md:10-45`):
```yaml
disks:
  - mountpoint: /mnt/TestData/seastar-bench
    read_iops: 500000
    read_bandwidth: 3G
    write_iops: 250000
    write_bandwidth: 2G
```
then `--io-properties-file io_properties.yaml` (never together with
`--io-properties`, `src/core/disk_params.cc:97-99`).
**(8) Framework overhead only** — `--storage /dev/null` with a randread config; no
files created (`io_tester.cc:644-652`), closest analogue to a `nop` ring benchmark.
**(9) Buffered streams** — `fstream_perf --concurrency=16 --buffer-size=65536
--total-ops=100000` from a writable cwd; `io_queue_latency_perf` for scheduler
latency.

## F. Fairness knobs, defaults, silent throughput caps

| Option | Default | Effect / source |
|---|---|---|
| `--reactor-backend` | first available (io_uring) | `reactor.cc:4039-4048` |
| `--smp` | one shard per CPU | shards/rings/io_queues (`smp_options.hh:45-46`, `reactor.cc:4112`) |
| `--cpuset` | all CPUs | CPU placement (`reactor.cc:4113`) |
| `--async-workers-cpuset` | empty | mandatory for asymmetric (`reactor.cc:4089-4091`) |
| `--io-properties-file` / `--io-properties` | unset | disk token-bucket rates; unset = unlimited (`reactor.cc:4124-4125`, `disk_params.hh:40-48`) |
| `--num-io-groups` | #NUMA nodes | splits capacity across IO groups (`smp_options.hh:62-67`) |
| `--task-quota-ms` | 0.5 | max time between polls (`reactor.cc:4062`) |
| `--io-latency-goal-ms` | 1.5×task-quota = 0.75 | token-bucket limit / dispatch batching (`reactor.cc:4063`, `disk_params.cc:83-87`) |
| `--io-flow-rate-threshold` | 1.1 | backpressure on dispatch/completion ratio (`reactor.cc:4064`, `io_queue.hh:184-186`) |
| `--poll-mode`,`--idle-poll-time-us`,`--poll-aio` | off / calc / true | disk busy-polling (`reactor.cc:4057-4061`) |
| `--overprovisioned` | off | = `--idle-poll-time-us 0 --thread-affinity 0 --poll-aio 0` → no I/O busy-poll (`reactor.cc:4077-4078`) |
| `--max-task-backlog` | 1000 | above this, reactor stops polling I/O (`reactor.cc:4066`) |
| `--blocked-reactor-notify-ms` | 25 | stall detector warnings only (`reactor.cc:4067-4069`) |
| `--io-completion-notify-ms` | off/∞ | slow-completion logging (`reactor.cc:4065`, `disk_params.cc:89`) |
| `--kernel-page-cache` | false | disables O_DIRECT, never for storage bench (`reactor.cc:4074-4076`) |
| `--relaxed-dma` | off | allows buffered I/O (`reactor.cc:4070`) |
| `--unsafe-bypass-fsync` | false | flush() no-op, no fsync latency (`reactor.cc:4073`) |
| `--aio-fsync` | true on kernel ≥4.18 | fdatasync via ring/libaio (`reactor.cc:4093-4094`, `file.cc:229-274`) |
| `--hugepages`,`--lock-memory`,`--mbind`,`--memory`,`--reserve-memory` | unset/true/all | allocator/TLB/jitter, not caps (`smp_options.hh:49-57`, `reactor.cc:4114-4117,4126`) |
| `--max-networking-io-control-blocks` | 10000 | sockets only; storage aio pool fixed 1024/shard (`reactor.cc:4095-4097`, `reactor_backend.hh:78`) |

**Defaults that silently clamp/alter a QD64 comparison:**

1. `parallelism` defaults to **0** (`io_tester.cc:218`): a config without it issues
   no requests and reports 0 IOPS.
2. `--smp` defaults to all 32 CPUs (`reactor.cc:4112`): "QD64" becomes 32 rings ×
   64 = 2048 in-flight and 32 token-bucket contenders — not comparable to one
   JUring thread. Use `--smp 1 --cpuset 0`.
3. `data_size` is divided per shard (`io_tester.cc:1147-1149`), so 10GB on 8 shards
   = 1.25GB/shard; scale with `--smp`.
4. Ring depth 200/shard (`reactor_backend.hh:467`): QD64 in one class is fine, but
   4+ classes × 64 or QD≥200 exceed SQ depth and `get_sqe()` silently reaps
   completions inline (`reactor_backend.cc:1640-1670`) mid-run. Keep in-flight
   <200/shard.
5. No `--io-properties`: rates max() → unthrottled (`disk_params.hh:40-48`,
   `disk_params.cc:178-191`) — good raw parity but not representative QoS; pinned
   low rates cap throughput and can shrink max request length so larger ops get
   split (`io_queue.cc:752-800,1052-1110`, watch `total_split_ops`/`aio_outsizes`).
6. `--task-quota-ms 0.5` + 0.75 ms latency goal batch dispatch and add a scheduling
   floor to per-op latency (`reactor.cc:4062-4063`, `doc/io-scheduler.md:36-40`).
7. `--overprovisioned` disables I/O busy-polling (`reactor.cc:4077-4078`) and makes
   seastar look worse at low QD — never mix with plain runs.
8. `--max-task-backlog 1000` (`reactor.cc:4066`): large fan-out silently stops I/O
   polling until backlog falls.
9. linux-aio retries EAGAIN in a coroutine (`reactor_backend.cc:405-417`); nonzero
   `aio_retries` means the device/queue was saturated.
10. `append` type is serialized (`file.cc:854-870,1211-1220`) — not a QD benchmark.
11. File prefill runs before `--duration` starts (`io_tester.cc:100-126,1386-1390`)
    but heats SLC/NAND and perturbs the first run; use `--keep-files` for repeats.
12. `dsync: true` waits for media per write (`file.cc:218-223`); default O_DIRECT
    writes are not fsynced per op, so "randwrite" is media-write, not durability.

## G. Asymmetry: JUring vs seastar (file-I/O relevant)

| JUring exposes | JUring evidence | Seastar counterpart |
|---|---|---|
| All setup flags: IOPOLL, SQPOLL, SQ_AFF, CQSIZE, CLAMP, ATTACH_WQ, R_DISABLED, SUBMIT_ALL, COOP_TASKRUN, TASKRUN_FLAG, SQE128, CQE32, SINGLE_ISSUER, DEFER_TASKRUN, NO_MMAP, REGISTERED_FD_ONLY, NO_SQARRAY, HYBRID_IOPOLL | `JUring:src/main/java/com/davidvlijmincx/lio/api/IoUringOptions.java:8-25` | only `SQPOLL|SQ_AFF`/`ATTACH_WQ|SQPOLL` in asymmetric backend; normal ring has no flags (`reactor_backend.cc:1259-1262,2132-2180`) |
| Per-op SQE flags: FIXED_FILE, IO_DRAIN, IO_LINK, IO_HARDLINK, IO_ASYNC, BUFFER_SELECT, CQE_SKIP_SUCCESS | `JUring:.../api/SqeOptions.java:8-14` | none generated (`grep IOSQE` → no matches in `src/`, `include/`) |
| Registered buffers + READ_FIXED/WRITE_FIXED + read-buffer pool | `JUring:.../api/IoUringDispatcher.java:832-916`, `JUring.java:104-268`, opcodes `IoUringDispatcher.java:21-22,344-361` | no registration API (only iowq_aff, `reactor_backend.cc:2172`); no fixed-buffer API |
| Registered files (`REGISTER_FILES`/`_UPDATE`), direct open/close ops | `JUring:.../IoUringDispatcher.java:50-51,863-916`, `JUringBlocking.java:1033-1051,1055-1145` | none; raw fds only |
| Registered ring fd (`IORING_ENTER_REGISTERED_RING`) | `JUring:.../IoUringDispatcher.java:747-767` | none |
| Opcode probe exposed to caller; SQPOLL idle parameter; user-driven `ATTACH_WQ` ring sharing | `JUring:.../IoUringDispatcher.java:145-185`, `JUring.java:37-54`, `IoUringDispatcher.java:240-255` | probe internal; idle hardcoded 5 ms; sharing topology-driven by hwloc (`doc/reactor_backend_asymmetric_uring.md:64-71`) |

| Seastar exposes | Seastar evidence | JUring status |
|---|---|---|
| fstream: write-behind, readahead + dynamic buffer sizing, preallocation, extent hints | `fstream.hh:59-63,101-112`; `fstream.cc:106-235,370-512` | no equivalent; caller manages raw buffers |
| QoS: scheduling groups + shares, per-class fair queue/token bucket, bandwidth caps, supergroups | `io_queue.hh:65-241`; `io_queue.cc:991-1190`; `doc/io-scheduler.md` | no scheduler; all requests equal |
| `io_intent` cancellation of all pinned futures | `io_intent.hh:30-105`; `file.hh:770-776` | no cancellation API found in public methods (UNVERIFIED) |
| Metrics: per-class ops/bytes/queue/disk-queue/delay/exec/starvation/consumption/activations; reactor aio_retries/outsizes | `io_queue.cc:820-877,1387-1405`; `reactor.hh:190-211`; `io_tester.cc:676-685` | none built in |
| Multi-shard runtime (`--smp`, `--cpuset`), one io_queue+ring per shard | `reactor.hh:230-245`; `reactor.cc:4700-4765` | threads/pollers exist, no sharded runtime |
| Per-FS alignment discovery, sloppy_size, append-challenged semantics, durable/no-durable open | `file.hh:91-105`; `file.cc:824-970,1250-1380,1396-1440` | caller handles opening/alignment |
| Benchmark harness: io_tester + iotune + ioinfo + perf tests | `apps/io_tester/`, `apps/iotune/`, `tests/perf/` | separate Java bench suite |

## H. Not verified in this environment

* Exact binary output paths are CMake-derived (`OUTPUT_NAME`/per-dir defaults),
  **UNVERIFIED** until a real build exists (`apps/CMakeLists.txt:25-44`,
  `tests/perf/CMakeLists.txt:26-62`).
* Whether the io_uring backend is available on this exact host is inferred from the
  probe code (`reactor_backend.cc:1312-1347`); seastar cannot run here.
* No runtime numbers: this file is a code-path/capability inventory only.
* JUring "no cancellation/metrics" claims are from grepping public methods of
  `JUring.java`/`JUringBlocking.java`/`IoUringDispatcher.java`; the JUring task owns
  the full audit.
