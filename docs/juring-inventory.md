# JUring inventory, benchmark-suite audit, and seastar gap analysis

Evidence base for `SEASTAR-PLAN.md`. Written 2026-11-13 against JUring HEAD `4526338` ("feat(blackpanda): implement high-throughput zero-allocation O_DIRECT streaming log engine"), workspace clean (`git status` empty; branch `kafka-engine` per `bench/results/2026-11-13/manifest.txt:86-88`). Runtime JDK 26.0.2, class-file target 25 (`pom.xml:57-58`, `bench/results/2026-11-13/manifest.txt:81-84`). Host: Ryzen 9 3950X (16C/32T), 62 GiB, kernel 7.0.12-201.fc44, data disk `nvme1n1` ext4 mounted read-only at `/mnt/TestData` (`bench/results/2026-11-13/manifest.txt:6,56-58,76`).

Legend: `[V]` = verified by executing code in this workspace today; `[U]` = inference/UNVERIFIED. Every claim carries a `path:line` citation. Paths are repo-relative. `bench/results/...` is a gitignored artifact directory produced by the parallel benchmarking effort, not part of the Maven build.

### File index (short names used in citations)

Citations use a short file name for readability; this table maps each one to its repo path.

| Cited as | Repo path |
|---|---|
| `BENCHMARK.md` | `BENCHMARK.md` |
| `BlackPandaConcurrencyTest.java` | `src/test/java/com/davidvlijmincx/lio/blackpanda/BlackPandaConcurrencyTest.java` |
| `BufferedReadBenchmark.java` | `src/test/java/bench/suite/buffered/BufferedReadBenchmark.java` |
| `BufferedWriteBenchmark.java` | `src/test/java/bench/suite/buffered/BufferedWriteBenchmark.java` |
| `Cqe.java` | `src/main/java/com/davidvlijmincx/lio/api/Cqe.java` |
| `DatasetManager.java` | `src/test/java/bench/suite/common/DatasetManager.java` |
| `DirectLatencyBenchmark.java` | `src/test/java/bench/suite/direct/DirectLatencyBenchmark.java` |
| `DirectReadBenchmark.java` | `src/test/java/bench/suite/direct/DirectReadBenchmark.java` |
| `DirectWriteBenchmark.java` | `src/test/java/bench/suite/direct/DirectWriteBenchmark.java` |
| `EqualQueueDepthBenchmark.java` | `src/test/java/bench/suite/direct/EqualQueueDepthBenchmark.java` |
| `FfmInvocationBenchmark.java` | `src/test/java/bench/suite/FfmInvocationBenchmark.java` |
| `FileDescriptor.java` | `src/main/java/com/davidvlijmincx/lio/api/FileDescriptor.java` |
| `IoHelper.java` | `src/test/java/bench/suite/common/IoHelper.java` |
| `IoUringDispatcher.java` | `src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java` |
| `IoUringOptions.java` | `src/main/java/com/davidvlijmincx/lio/api/IoUringOptions.java` |
| `IovecBlockPool.java` | `src/main/java/com/davidvlijmincx/lio/api/IovecBlockPool.java` |
| `JUring.java` | `src/main/java/com/davidvlijmincx/lio/api/JUring.java` |
| `JUringBlocking.java` | `src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java` |
| `JUringTest.java` | `src/test/java/com/davidvlijmincx/lio/api/JUringTest.java` |
| `LibCDispatcher.java` | `src/main/java/com/davidvlijmincx/lio/api/LibCDispatcher.java` |
| `MultiThreadScalingBenchmark.java` | `src/test/java/bench/suite/direct/MultiThreadScalingBenchmark.java` |
| `OperationType.java` | `src/main/java/com/davidvlijmincx/lio/api/OperationType.java` |
| `PartitionTailBuffer.java` | `src/main/java/com/davidvlijmincx/lio/blackpanda/PartitionTailBuffer.java` |
| `README.md` | `README.md` |
| `ReadBufferPool.java` | `src/main/java/com/davidvlijmincx/lio/api/ReadBufferPool.java` |
| `ReadvWritevTest.java` | `src/test/java/com/davidvlijmincx/lio/api/ReadvWritevTest.java` |
| `RecordBatch.java` | `src/main/java/com/davidvlijmincx/lio/blackpanda/RecordBatch.java` |
| `Result.java` | `src/main/java/com/davidvlijmincx/lio/api/Result.java` |
| `ResultConsumer.java` | `src/main/java/com/davidvlijmincx/lio/api/ResultConsumer.java` |
| `Sqe.java` | `src/main/java/com/davidvlijmincx/lio/api/Sqe.java` |
| `SqeOptions.java` | `src/main/java/com/davidvlijmincx/lio/api/SqeOptions.java` |
| `UringHistoricReader.java` | `src/main/java/com/davidvlijmincx/lio/blackpanda/UringHistoricReader.java` |
| `UringLogSegment.java` | `src/main/java/com/davidvlijmincx/lio/blackpanda/UringLogSegment.java` |
| `UringPartitionLog.java` | `src/main/java/com/davidvlijmincx/lio/blackpanda/UringPartitionLog.java` |
| `UringPartitionManager.java` | `src/main/java/com/davidvlijmincx/lio/blackpanda/UringPartitionManager.java` |
| `UringSparseIndex.java` | `src/main/java/com/davidvlijmincx/lio/blackpanda/UringSparseIndex.java` |
| `WakePollerStoreLoadTest.java` | `src/test/java/com/davidvlijmincx/lio/api/WakePollerStoreLoadTest.java` |
| `ZeroAllocationHotPathTest.java` | `src/test/java/com/davidvlijmincx/lio/api/ZeroAllocationHotPathTest.java` |
| `module-info.java` | `src/main/java/module-info.java` |
| `pom.xml` | `pom.xml` |

## Corrections to prior assumptions (read first)

1. **JMH harness claims in `BENCHMARK.md` are not encoded in source.** Every direct/buffered benchmark class specifies only `@Fork(value = 1)` and no `@Warmup`/`@Measurement` (`DirectReadBenchmark.java:23-29`, `DirectWriteBenchmark.java:22-28`, `EqualQueueDepthBenchmark.java:20-26`, `MultiThreadScalingBenchmark.java:22-28`, `BufferedReadBenchmark.java:23-29`, `BufferedWriteBenchmark.java:23-29`), so JMH defaults apply: 1 fork, 5 warmup x 10 s, 5 measurement x 10 s. `BENCHMARK.md:19,38,49,63,78,90` claims "5 forks x 3 iterations x 3 s" / "3 forks x ..."; no committed runner script or JMH option file exists (at HEAD the repo has only `LICENSE`, `README.md`, `BENCHMARK.md`, `pom.xml` and `src/`; untracked tooling under `bench/` and `docs/` appeared during this planning effort). Only `FfmInvocationBenchmark.java:16-21` pins 3 x 1 s. The published config therefore cannot be reproduced from the repository; treat `BENCHMARK.md` numbers as "config modified at run time" `[U]`.
2. **The data mounts are read-only.** `/mnt/TestData` (dataset disk `nvme1n1`) and `/mnt/data` are mounted `ro` (`bench/results/2026-11-13/manifest.txt:56-58,76`). `DirectWriteBenchmark`, `BufferedWriteBenchmark` and the write half of `DirectLatencyBenchmark.FioComparisonBenchmark` (which opens a `WRITE_DIRECT` handle on the 10 GB write file, `DirectLatencyBenchmark.java:240-241`) cannot run against the default dataset here. `DatasetManager.resolveWriteDir` falls back to the base dir (`DatasetManager.java:62-70`), i.e. the read-only mount (`DatasetManager.java:106`). Write benchmarks require `-Djuring.data.dir=...` on a writable device; the only writable place is the btrfs home filesystem, unusable for I/O numbers (`bench/results/2026-11-13/manifest.txt:106-107`).
3. **seastar is checked out/built but cannot run in this sandbox** (hugepages/io_uring privileges; not attempted: `bench/results/2026-11-13/manifest.txt:108`). Any "JUring vs seastar" number must come from another host or stay source-level.
4. **No socket ops remain.** `AcceptResult`/`ConnectResult`/`RecvResult` were deleted by the rewrite (`git show --stat 2c288da`: `AcceptResult.java`, `ConnectResult.java` removed) after being added in `612670b` ("Socket support"). The current `OperationType` enum (`OperationType.java:5-16`) and dispatcher opcode set (`IoUringDispatcher.java:17-27`) contain no accept/connect/send/recv/poll/timeout/splice/statx ops. Do not assume network support for a seastar comparison.
5. **The 10 GB `SINGLE_FILE` read dataset already exists** (`/mnt/TestData/read_files/single_file_bench.bin`, 10,737,418,240 bytes) and is usable for reads; `DatasetManager.ensureSingleBenchFile` would otherwise create it (buffered writes + `force`, `DatasetManager.java:156-206`).
6. **SQPOLL works today; IOPOLL does not on this filesystem; DEFER_TASKRUN requires SINGLE_ISSUER.** Verified probes: `new JUring(64, SQPOLL)` + 4 KiB `READ_FIXED` from `/mnt/TestData/...` returns 4096; `IOPOLL` setup succeeds but the same read returns `-95` (`-EOPNOTSUPP`; ext4 does not support IOPOLL); `DEFER_TASKRUN` without `SINGLE_ISSUER` fails setup with EINVAL `[V]`. See appendix.

## A. Layers and architecture

### A.1 Module, build, and JDK requirements

- One Maven module, `com.davidvlijmincx.lio:JUring:0.1-SNAPSHOT` (`pom.xml:7-9`), exporting two packages: `com.davidvlijmincx.lio.api` and `com.davidvlijmincx.lio.blackpanda` (`module-info.java:1-6`). `requires static jdk.management` only (`module-info.java:5`).
- Java 25 class files (`pom.xml:57-58`); FFM (`java.lang.foreign`) final API, no JNI and no liburing (`README.md:3`). Syscalls are reached through `syscall(2)` downcalls: `io_uring_setup` = 425, `io_uring_enter` = 426, `io_uring_register` = 427 (`LibCDispatcher.java:113-136`).
- Test-only deps: JMH 1.37, JUnit 5.12.1, AssertJ 3.27.2, jcstress 0.16, HdrHistogram 2.2.2 (`pom.xml:12-53`); surefire runs with `--enable-native-access=ALL-UNNAMED` (`pom.xml:86-93`).
- Build: `mvn -o -q -DskipTests compile` `[V]`; tests: `mvn -o test`; jcstress only via the manual `-Pjcstress` profile (`pom.xml:99-126`).
- Benchmarks are not surefire tests; each has its own `main()` (e.g. `DirectReadBenchmark.java:300-306`), so they are run manually from an IDE or `java -cp target/test-classes:<deps> ...`.

### A.2 `lio.api` (the binding)

- A thin, allocation-conscious 1:1 wrapper over the rings: SQE builders (`JUring.prepare*`, `JUring.java:51-746`), submission (`JUring.java:760-780`), completion reaping either as `Result` objects (`JUring.java:838-844`) or streamed into a reusable `Result` through a callback (`ResultConsumer.java:6-9`; dispatcher reaps in place at `IoUringDispatcher.java:947-976`).
- Claims: zero heap allocation on the hot path after setup (`README.md:8`), focus on random direct I/O with registered files and fixed buffers (`README.md:10-13`), "single-threaded by design" (`README.md:81`), "still very much in development" (`README.md:15`).
- Startup probes the 10 opcodes it requires and fails fast if any is missing (`IoUringDispatcher.java:144-203`); setup failures are translated to `IllegalArgumentException` with errno detail (`IoUringDispatcher.java:206-231`).

### A.3 `lio.blackpanda` (the log engine)

- Append-only, segment-rolling O_DIRECT log engine built on `JUring`. Class Javadoc claims "zero-pollution ... high-throughput group commit ... sub-millisecond P99 ... non-pinning virtual-thread wait states ... total isolation (lagging consumer shield)" (`UringPartitionLog.java:29-42`).
- Components: `UringPartitionManager` (segment map, roll/find, `UringPartitionManager.java:13-144`); `UringLogSegment` (1 GiB default segment file + sparse index, `UringLogSegment.java:16-143`); `UringSparseIndex` (`.index`/`.timeindex` off-heap arrays, `UringSparseIndex.java:18-36`); `PartitionTailBuffer` (in-memory 4 KiB-slot tail ring, `PartitionTailBuffer.java:12-38`); `UringHistoricReader` (QD 8-32 `READ_FIXED` O_DIRECT reader, `UringHistoricReader.java:16-30`); `RecordBatch` framing (`RecordBatch.java:15-52`).
- Write path: producers reserve slab space with an atomic offset, write the header+payload, set a per-slot status, then park until the flusher marks the covering batch flushed (`UringPartitionLog.java:179-232`). The flusher thread batches writes up to 512 KiB, pipelines up to 16 NVMe writes, and links a `DATASYNC` fsync every `FSYNC_THRESHOLD_BYTES` = 512 MiB or at segment roll (`UringPartitionLog.java:35-40,291-405,376-390`).
- **Important:** blackpanda is not used anywhere in the benchmark suite and is absent from `README.md` and `BENCHMARK.md` (grep: zero hits). It is exercised only by `src/test/java/com/davidvlijmincx/lio/blackpanda/*` and the manual head-to-head harness. It is a log engine, not a general file/stream abstraction.

### A.4 Ownership model: where enforced vs where not

- Intended model: one ring per issuing thread, created and closed on that thread.
- *Documented* only in the `close()` Javadoc (`JUring.java:916-923`) and a README example comment (`README.md:81`).
- *Partially enforced*: `SINGLE_ISSUER` registers the ring fd into the creating task's file table (`IoUringDispatcher.java:717-741,747-767`); `close()` from another thread silently skips `IORING_UNREGISTER_RING_FDS` (`IoUringDispatcher.java:769-806`); FFM segments are thread-confinement checked (`JUring.validateNativeSegment`, `JUring.java:567-580`).
- *Not enforced*: `prepare*`, `submit()`, `waitForResult()` have no owner-thread check (`JUring.java:748-767`, `IoUringDispatcher.java:408-556`), and internal state (`opSeq`, `freeBufferStack`, SQ/CQ indices) is plain unsynchronized fields (`JUring.java:26-35`, `IoUringDispatcher.java:88-114`). Concurrent issue from two threads can corrupt SQ bookkeeping or produce kernel EINVAL `[U]`.
- `JUringBlocking` is the exception: it is designed for concurrent callers and uses per-worker MPSC queues/slot state machines (`JUringBlocking.java:84-233,535-613`).

## B. Capability matrix

### B.1 Ring setup flags (`IoUringOptions.java:7-26`, all 18 exposed)

| Flag | State in JUring | Evidence |
|---|---|---|
| `SINGLE_ISSUER` | **Used/verified.** Ring-fd registration; every benchmark/test uses it. | `IoUringDispatcher.java:717-741`; `DirectReadBenchmark.java:101-104`; `README.md:83-85`; `[V]` |
| `COOP_TASKRUN` | **Used/verified** in every benchmark/example. | `README.md:83-85`; `ZeroAllocationHotPathTest.java:44-48` |
| `DEFER_TASKRUN` | **Used**, but requires `SINGLE_ISSUER`; alone = EINVAL. Deferred-taskrun flush helper exists but is never called. | `IoUringDispatcher.java:421-423,477-479,716-717`; `[V]` |
| `SQPOLL` | **Implemented but untested/unbenchmarked.** Explicit `IORING_SQ_NEED_WAKEUP` handling and `sqThreadIdleMs` constructor. | `JUring.java:37-41`; `IoUringDispatcher.java:414-435,505-518,609-618`; `[V]` read OK |
| `SQ_AFF` | Pass-through, but `sq_thread_cpu` is never set -> binds CPU 0. | `IoUringDispatcher.java:613-618`; `[V]` setup OK |
| `CQSIZE` | **Unusable:** `params.cq_entries` is never set -> kernel EINVAL. | `IoUringDispatcher.java:613-618`; `[V]` EINVAL |
| `CLAMP`, `SUBMIT_ALL`, `TASKRUN_FLAG` | Kernel pass-through flags; no harness/test exercises them; setup+NOP OK. | `[V]` |
| `ATTACH_WQ` | Used internally by `getSharedWorkerRing`, ORed with user flags. | `IoUringDispatcher.java:244-260` |
| `R_DISABLED` | **Unusable:** no `IORING_REGISTER_ENABLE_RINGS` call anywhere; any I/O fails EBADF. | register list `IoUringDispatcher.java:35-36`; `[V]` EBADF |
| `SQE128`, `CQE32` | Ring/SQE sizing handled; no 128/32-byte payload API. | `IoUringDispatcher.java:641-643,673-677,815-816`; `Cqe.java:14-20`; `[V]` read OK |
| `NO_MMAP`, `REGISTERED_FD_ONLY` | **Unusable:** no registration-based ring-mapping path; setup fails EFAULT here. | `IoUringDispatcher.java:664-693` always mmaps; `[V]` EFAULT |
| `NO_SQARRAY` | Handled (skips SQ array init); no test. | `IoUringDispatcher.java:696-701`; `[V]` read OK |
| `IOPOLL` | Exposed; no IOPOLL-specific code; kernel rejects every op on ext4 (`-EOPNOTSUPP`). | opcode set `IoUringDispatcher.java:17-27`; `[V]` -95 |
| `HYBRID_IOPOLL` | Exposed; kernel on this host rejects setup. | `[V]` EINVAL |

What the four production flags actually mean: only `SINGLE_ISSUER` + `COOP_TASKRUN` + `DEFER_TASKRUN` are exercised (all benchmarks and README), plus `ATTACH_WQ` internally. `SQPOLL` is code-complete but has no test/benchmark; the remaining 13 flags are raw pass-through with no supporting params/registrations, and 5 of them (`CQSIZE`, `R_DISABLED`, `NO_MMAP`, `REGISTERED_FD_ONLY`, and effectively `SQ_AFF`) are unusable as exposed `[V]`.

### B.2 Operations (SQE opcodes are a closed set)

Present, with evidence and verification status:

| Op | Kernel opcode | Public API | Verified |
|---|---|---|---|
| NOP | 0 | `JUring.prepareNop` `JUring.java:165-180` | `[V]` probe + `NopBenchmark` |
| READ (to native buffer) | 22 | `JUring.java:526-536` | `[V]` `JUringTest` |
| READ (into `MemorySegment`) | 22 | `JUring.java:538-548` | `[V]` `JUringTest.java:729-764` |
| WRITE | 23 | `JUring.java:582-606` | `[V]` `JUringTest` |
| READV / WRITEV | 1 / 2 | `JUring.java:278-313,398-434` | `[V]` `ReadvWritevTest` |
| READ_FIXED / WRITE_FIXED | 4 / 5 | `JUring.java:149-276,608-696` | `[V]` benchmarks |
| READV/WRITEV with registered buffers | 1 / 2 | `JUring.java:315-396` | `[V]` `ReadvWritevTest.java:83-141` |
| OPENAT | 18 | `JUring.java:436-460` | `[V]` `JUringTest.java:357-382` |
| OPENAT direct (fixed-file install) | 18 + `file_index` | `JUring.java:462-490` | `[V]` `JUringTest.java:384-406` |
| CLOSE / CLOSE direct | 19 + `file_index` | `JUring.java:492-520` | `[V]` `JUringTest.java:408-462` |
| FSYNC (`DATASYNC`/`SYNC`) | 3 | `JUring.java:708-735` | `[V]` `JUringTest.java:842-864` |
| ASYNC_CANCEL | 14 | `JUring.java:737-746` | designed, untested |

Opcode numbering and SQE construction: `IoUringDispatcher.java:17-27,305-406`. `READ_EXT`/`WRITE_EXT` are *synthetic* `Result` types meaning "same kernel READ/WRITE, but buffer owned by the caller" (`OperationType.java:13-14`, `JUring.java:582-592`, `IoUringDispatcher.java:1227-1232`).

Absent (no enum value, constant, or API): accept/connect/send/recv/sendmsg/recvmsg, poll/poll_add/poll_remove, timeout/timeout_remove and link-timeouts, splice/tee, statx, fallocate/discard, fsync-range, openat2, read/write multishot, provided-buffer rings, `IORING_OP_URING_CMD`, waitid, pipe, memfd. Evidence: the closed enums above; register opcodes are only RING_FDS/UNREGISTER_RING_FDS/BUFFERS/FILES/FILES_UPDATE/IOWQ_MAX_WORKERS/PROBE (`IoUringDispatcher.java:34-36,49-52,144`).

### B.3 Registration and advanced features

| Feature | State | Evidence |
|---|---|---|
| Registered files + `IOSQE_FIXED_FILE` | Present; register + update; **no unregister**; 0-based index. | `JUring.java:904-914`; `IoUringDispatcher.java:863-918`; `JUringTest.java:238-315` |
| Direct open/close into fixed-file table | Present (`file_index + 1`, liburing convention). | `JUring.java:462-520`; `IoUringDispatcher.java:317-333` |
| Registered buffers | Present; page-aligned contiguous slab; one registration per ring. | `JUring.java:846-868`; `LibCDispatcher.java:366-386`; `JUringTest.java:766-779` |
| Registered-buffer selection/pooling | Present, caller-managed; double-free detection; `-1` on exhaustion. | `JUring.java:870-900`; `JUringTest.java:558-576` |
| Provided-buffer ring + `IOSQE_BUFFER_SELECT` | **Absent:** flag is exposed but there is no `IORING_REGISTER_PBUF_RING`/buf-group API -> unusable. | `SqeOptions.java:9-16`; `IoUringDispatcher.java:49-52` |
| Multishot ops / `IORING_CQE_F_MORE` | Absent; CQE flags passed through raw, never interpreted. | `Cqe.java:37-39`; `Result.java:78-80` |
| Linked ops | Pass-through only: link/hardlink/drain/async reach the SQE via `SqeOptions`; no link semantics or ordered-completion API; no tests. Only in-repo use: blackpanda write->fsync link. | `JUring.java:748-758`; `SqeOptions.java:9-16`; `UringPartitionLog.java:382-383` |
| Timeout ops | Absent. | `OperationType.java:5-16` |
| eventfd registration | Absent from public API; `eventfd` used only internally by `JUringBlocking` as a wakeup channel. | `LibCDispatcher.java:140-143`; `JUringBlocking.java:110,195-196` |
| io-wq worker tuning | Private method, never called. | `IoUringDispatcher.java:921-934` (grep: no callers) |
| Ring-fd registration / `IORING_ENTER_REGISTERED_RING` | Present for `SINGLE_ISSUER` on platform threads only (VTs get `-EBADF`). | `IoUringDispatcher.java:747-767` |
| `ReadBufferPool` (`prepareReadPooled`) | Separate from registered buffers; mallocs when empty, so not bounded. | `JUring.java:104-147,550-565`; `ReadBufferPool.java:23-27` |
| Iovec block pool | Present, `MAX_VECS = 64`, internal. | `IovecBlockPool.java:8-10,20-38` |
| SQE `ioprio` | Struct accessor exists but is never set by a public API -> no priorities. | `Sqe.java:90-96` |
| CQE batching | `maxReap` bound + reusable `Result` callback; batch size chosen by caller. | `JUring.java:795-844`; `IoUringDispatcher.java:947-976,1099-1189` |

### B.4 SQE flags

`SqeOptions` enumerates fixed-file, drain, link, hardlink, async, buffer-select, cqe-skip-success (`SqeOptions.java:9-16`). Only `IOSQE_FIXED_FILE` is set by the library itself (`JUring.java:748-758`); the rest are caller pass-through via `SqeOptions...` varargs present on nearly every `prepare*`. No test in `src/test` passes any `SqeOptions` (grep), so these paths are effectively untested.

## C. Lifecycle, memory ownership, and safety

### C.1 Ring lifecycle

1. `new JUring(depth, flags)` -> `queueInit` writes `io_uring_params`, maps SQ ring / CQ ring / SQE array, probes opcodes, optionally registers the ring fd (`IoUringDispatcher.java:609-745`).
2. Steady state: `prepare*` fills SQEs into the mapped SQ array; `submit*` publishes `sq.tail` and enters the kernel (`IoUringDispatcher.java:408-556`); completions are reaped by advancing `cq.head` (`IoUringDispatcher.java:947-976,1191-1211`).
3. `close()` is idempotent (`IoUringDispatcher.java:1335-1340`) and runs `drainAndCancel()`: submit leftover SQEs, submit `ASYNC_CANCEL_ALL|ANY` (`rw_flags=5`) if `inFlightCount > 0`, then reap with `Result::close` until all in-flight complete (`IoUringDispatcher.java:1297-1332`).
4. `queueExit()` unmaps SQ/CQ/SQEs and closes the ring fd (`IoUringDispatcher.java:806-830`); then `iovecBlockPool.close()` and `Arena.close()` free registered buffers/iovecs (`IoUringDispatcher.java:1335-1342`).
5. Sharp edges: `close()` can block indefinitely if a submitted op never completes (no timeout; loop at `IoUringDispatcher.java:1313-1329`), and for `SINGLE_ISSUER` it must run on the owner thread or ring-fd unregistration is skipped (`JUring.java:916-923`, `IoUringDispatcher.java:769-806`).

### C.2 Result lifetime and native memory ownership

- `Result` is the ownership token. Non-fixed `prepareRead(fd, size, offset)` mallocs a native buffer freed only by `Result.freeBuffer()`/`close()` (`JUring.java:526-536`; `Result.java:152-170,186-189`); README tells users to close results (`README.md:220-221`).
- Batched consumer callbacks receive the *same reusable* `Result` instance (`IoUringDispatcher.java:947-966`), so the consumer must free a plain-READ buffer before returning; fixed-buffer reads need not free.
- `WRITE` with a `byte[]` payload is copied to native memory and freed internally on completion (`JUring.java:594-606`; `IoUringDispatcher.java:1233-1240`).
- `READ_EXT`/`WRITE_EXT` results reference caller-owned segments; `freeBuffer()` is a no-op for them (`Result.java:152-160`).
- `READ_FIXED` exposes the registered slab through `Result.buffer()` (`Result.java:86-100`); `READV` builds fresh `MemorySegment` views per iovec, sliced to actual bytes read (`IoUringDispatcher.java:1248-1272`); `freeBuffers()` frees only `READV`/plain READ (`Result.java:172-185`).
- `Result.close()` is safe twice because `freeBuffer` zeroes the address before returning (`Result.java:156-170`).
- `Result.result()` is the raw kernel `res`; `fd()` casts it for open (`Result.java:70-76,144-150`). There is no separate buffer-length field for short reads: `validBuffer()`/`toByteArray()` clamp to `res` (`Result.java:102-135`).

### C.3 FileDescriptor lifecycle

- Blocking `open(2)` in the constructor, `close(2)` in `close()` (`FileDescriptor.java:9-56`). It is not tied to any ring.
- Closing an fd registered through `registerFiles` is safe kernel-side (registration holds a reference), but it leaves a stale fixed-file slot until ring close because the API has no `IORING_UNREGISTER_FILES` (`IoUringDispatcher.java:50-52` has no such opcode).

### C.4 Short I/O, partial vectors, error propagation

- No short read/write handling and no retry loop anywhere in the binding; callers see the kernel `res`. The benchmark suite treats any short transfer as a hard failure (`DirectReadBenchmark.java:113-121`, `DirectWriteBenchmark.java:120-128`).
- The readv path only slices buffers to the returned byte count (`IoUringDispatcher.java:1248-1272`); it does not refill the remainder. A stream layer must implement complete-until-done itself.
- Op-level errors are raw negative `-errno` with `isSuccess()` = `res >= 0` (`Result.java:70-76`); no errno enum, no typed exceptions, no policy hooks. Submit/setup errors throw `RuntimeException`/`IllegalArgumentException` (`IoUringDispatcher.java:213-224,438-477`).
- The `enter`/submit loops retry `EAGAIN` and `EINTR` internally and map `EBUSY` (CQ full) to `IllegalStateException` (`IoUringDispatcher.java:415-477,491-556,1123-1180`). Transient *op-level* errors (EAGAIN on an O_DIRECT read, EINTR on fsync) are handed to the caller untried.

### C.5 Cancellation

- `prepareCancel(long targetOpId)` actually takes the target's **user_data** value (returned by every `prepare*`), not a logical op id, and posts one `IORING_OP_ASYNC_CANCEL` with `flags=0` (any-match) (`JUring.java:737-746`; `IoUringDispatcher.java:394-403`).
- Cancelled operations still post a normal completion; there is no "wait for cancellation" helper, no timeout, and no public bulk-cancel API (bulk cancel exists only inside `close()`/`JUringBlocking.close()`, `IoUringDispatcher.java:1303-1311`, `JUringBlocking.java:389-406`). No tests exercise cancellation.

### C.6 Buffer pools and misuse failure modes

- `registerBuffers` may be called once per ring (`JUring.java:846-850`); a second call throws.
- `checkOutBuffer` returns `-1` on exhaustion (`JUring.java:870-879`); `checkInBuffer` throws on double check-in (`JUring.java:881-900`, tested `JUringTest.java:558-576`); `checkOutBuffer`/`checkInBuffer` are not thread-safe (plain stack fields, `JUring.java:26-31`).
- `ReadBufferPool` (used by `prepareReadPooled`) mallocs a fresh buffer when empty, so it can grow without bound and is not a real pool (`ReadBufferPool.java:23-27`); `checkInReadBuffer(long)` silently drops buffers when the pool is null (`JUring.java:143-147`); only idle slots are freed on close (`ReadBufferPool.java:38-44`), so in-flight pooled buffers leak if the pool is closed early `[U]`.

### C.7 Zero-allocation claim: scope and verification

- Claimed in `README.md:8`; verified by 7 tests that read `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` around 100k-op loops with a JIT-warmup gate (`ZeroAllocationHotPathTest.java:20-35,57-58,82-140`).
- Covered: NOP batching, fixed-file + fixed-buffer read/write loops, and single-result paths on `JUring`. The test re-warms until three consecutive 0-byte windows (`ZeroAllocationHotPathTest.java:96-101`), which is JIT- and machine-sensitive.
- Not covered: `JUringBlocking` hot path (no allocation test at all), non-fixed `prepareRead` (mallocs per call, `JUring.java:526-536`), `prepareWrite(byte[])` (native copy, `JUring.java:594-606`), `prepareReadv`/`prepareWritev` (malloc per iovec, `JUring.java:286-313,406-434`), and blackpanda `append(byte[])` (Arena.ofAuto per call, `UringPartitionLog.java:246-249`).

### C.8 Other known sharp edges (from code/tests)

- SQ full -> `IllegalStateException` from `getSqe` (`JUring.java:748-758`); leak-safety around it is tested for open/write/writeFixed (`JUringTest.java:781-840`).
- CQ full -> `EBUSY` -> `IllegalStateException` in submit/batch paths (`IoUringDispatcher.java:438-442,1152-1167`).
- `submit()` with SQPOLL can busy-enter in a loop waiting for the kernel to consume the SQ (`IoUringDispatcher.java:428-477`).
- `waitForBatchResult(int batchSize)` retries the wait 3 times then throws (`IoUringDispatcher.java:1061-1073`).
- `getSharedWorkerRing` rings do not inherit parent registrations; each ring needs its own `registerFiles`/`registerBuffers` (`IoUringDispatcher.java:244-260`; test only does a plain read, `JUringTest.java:38-58`).
- `isRingFdRegistered`/`isSqNeedWakeup`/`ringFd` wrappers exist but have no callers outside their own definitions (`JUring.java:892-902`).
- `registerRingFd` returns `-EBADF` for virtual-thread creators (`IoUringDispatcher.java:747-751`), silently disabling the registered-ring fast path.

## D. Concurrency

### D.1 `JUring` raw API: one issuer, no locks, no safety net

- All SQ/CQ state and Java-side bookkeeping is plain fields; no CAS/lock on the hot path (`IoUringDispatcher.java:88-114`). This deliberately matches `IORING_SETUP_SINGLE_ISSUER` (`README.md:81`).
- The API does not reject other-thread use; only `close()`'s ring-fd unregister has a thread check (`IoUringDispatcher.java:769-806`). Concurrent issue from two threads is undefined `[U]`.
- The supported multi-thread pattern is one ring per thread: the suite creates a ring per JMH thread (`DirectReadBenchmark.java:101-104`; `MultiThreadScalingBenchmark.java:109-112` is run with `-t N` externally even though the class is annotated `@Threads(1)` at `MultiThreadScalingBenchmark.java:28`).

### D.2 `JUringBlocking`: poller-thread + virtual-thread blocking API

- `new JUringBlocking(qd[, numPollers], flags)` starts 1..N platform poller threads, each with its own `JUring` ring and an internal `eventfd` (`JUringBlocking.java:84-233`).
- Callers (usually virtual threads) submit through a per-worker MPSC queue + slot state machine, then park on their own slot until the poller observes the CQE and `LockSupport.unpark`s them (`JUringBlocking.java:535-613`); slot states are idle/parked/done/abandoned (`JUringBlocking.java:24-27`).
- The poller batches SQEs, reaps raw CQEs, and only enters the kernel after a short coalescing spin; wakeups use an `inKernel`/`pollerParked` + eventfd protocol (`JUringBlocking.java:234-405,476-533`), modelled by the jcstress store/load test (`WakePollerStoreLoadTest.java:1-30`).
- Default worker count = 1 for `qd < 64`, else `min(4, highestOneBit(procs/4))` = 4 on this 32-thread host; overridable with `-Djuring.blocking.pollers` (`JUringBlocking.java:645-658`). Worker choice for an op is `workers[threadId & mask]` (`JUringBlocking.java:677-682`), so cross-thread ordering is unspecified; one VT's calls are sequential because each call blocks until completion.
- `open`/`openDirect`/`closeDirect`/`registerFiles`/`registerBuffers` are **broadcast to every worker** (per-worker rings), so `openDirect` costs N syscalls for N pollers (`JUringBlocking.java:831-868,1089-1121,1145-1169`).
- Asymmetry vs `JUring`: no readv/writev, no `fsync`, no `nop`, no `prepareCancel`, no read-buffer pool; only read/write/readFixed/writeFixed/open/close/register (`JUringBlocking.java:689-1169`).

### D.3 `getSharedWorkerRing` semantics

- Creates a second, independent ring attached to the parent ring's io-wq via `IORING_SETUP_ATTACH_WQ` + `wq_fd` (`IoUringDispatcher.java:244-260`). It does *not* share SQ/CQ, registered files, registered buffers, or issuer thread - only the async worker pool.
- The only test does a plain (non-fixed) read through it (`JUringTest.java:38-58`); the API has no documentation beyond the name (`JUring.java:51-54`).

### D.4 What is missing for a seastar-style shard-per-core model

- No reactor loop, task scheduler, timer wheel, future/continuation plumbing, cross-shard messaging (`smp::submit_to` equivalent), `sharded<T>`-like service registry, gate/semaphore primitives, per-shard I/O scheduler, or shard-aware metrics.
- Today "multi-shard" means "N threads, each with a private `JUring`" (`MultiThreadScalingBenchmark.java:286-374`). `JUringBlocking` is the only thing resembling a runtime: a fixed 1-4 poller pool with no affinity or QoS (`JUringBlocking.java:645-682`).

## E. Benchmark suite inventory (`src/test/java/bench/suite/**`)

All are JMH classes except the blackpanda harness; all use `--enable-native-access=ALL-UNNAMED`; none are run by surefire.

| File | Mode / output | Params | JMH config in source | What is measured | Dataset; writes? |
|---|---|---|---|---|---|
| `DirectReadBenchmark.java` | Throughput, ops/s | `distribution` {MULTI_FILE, SINGLE_FILE}; `qd` {1,16,64,128} | `@Fork(1)`, `@Threads(1)`, `@OperationsPerInvocation(2048)`; no warmup/measurement annotations -> JMH defaults 5x10 s + 5x10 s (`:23-29,34,54`) | 2048 4 KiB random O_DIRECT reads pipelined to QD; JUring (fixed files+buffers) vs `FileChannel` (QD=1) in the same class | 10 GB single file or up to 2211 `.bin` files; read-only |
| `DirectWriteBenchmark.java` | Throughput | `distribution`; `qd` {1,64} | `@Fork(1)`, default timing (`:22-28,52`) | Same loop for random 4 KiB O_DIRECT writes | same datasets; **writes to disk** |
| `EqualQueueDepthBenchmark.java` | Throughput | `SINGLE_FILE` only; `qd` {1,16,32,64} | `@Fork(1)`, defaults (`:20-26,31,51`) | Single-thread JUring vs FileChannel at matched QD | 10 GB single file; read |
| `MultiThreadScalingBenchmark.java` | Throughput | `distribution`; threads via `-t` | `@Fork(1)`, `@Threads(1)` (`:22-28`) | 1..N threads x QD (derived from safe buffer count, `:83-85`) read/write scaling | both datasets; both directions |
| `NopBenchmark.java` | Throughput | `qd=64` | `@Fork(1)`, defaults (`:14-20,26`) | Ring/framework overhead (batched NOP + submit + peek) | none |
| `VirtualThreadBenchmark.java` | Throughput | `virtualThreads` {50..1000}; `ioMode` {DIRECT, BUFFERED_COLD}; `distribution` | `@Fork(1)`, defaults; `@OperationsPerInvocation(10000)` (`:26-32,35,41-44`) | `JUringBlocking` vs FileChannel with many VTs; BUFFERED_COLD drops page cache per iteration (`:60-66`) and reads device sectors from sysfs (`:174-241`) | both datasets; read |
| `DirectLatencyBenchmark.java` | `SampleTime` (us/op) + nested `FioComparisonBenchmark` (Throughput + HDR histograms) | latency QD 1; fio part `qd=64`, `telemetry` {true,false} | `@Fork(1)`, `@OperationsPerInvocation(1)` (`:34-40`); nested OPI 2048 (`:149`) | QD=1 `submit()`+`waitForResult()` and fused `submitAndWait(1)` latency (`:94-126`); nested fio-parity loop with optional HDR submit->completion histograms written to `results/<date>/*.percentiles/.hlog` (`:377-536`) | both datasets; nested write half needs writable disk |
| `BufferedReadBenchmark.java` / `BufferedWriteBenchmark.java` | Throughput | none (MULTI_FILE only) | `@Fork(1)`, defaults (`:23-29`) | Page-cache 4 KiB fixed-buffer IO, 512-entry ring vs FileChannel, batch 64 | up to 2211 files; write variant writes |
| `FfmInvocationBenchmark.java` | AverageTime ns/op | none | `@Warmup(3x1 s)`, `@Measurement(3x1 s)`, `@Fork(1)` (`:16-21`) | FFM downcall/`invokeExact`/proxy/record dispatch costs on `abs()` | none |
| `blackpanda/BlackPandaHeadToHeadBenchmark.java` | Not JMH: `@Test` + `main()`; text summary + HdrHistogram p50/p90/p99/p99.9 | BlackPanda vs "Kafka model" (FileChannel+force) vs "Redpanda model" (per-thread direct IO) | n/a | Workload A: 1M x 1 KiB append saturation; Workload B: producer stability while a lagging consumer drains 1-10 GB (`:32-38,105-108,353-356`) | needs writable dir `/mnt/data/blackpanda_bench` or `-Dblackpanda.bench.dir` (`:39-57`); not the read-only mounts |

### E.1 Dataset manager behavior

- Base-dir resolution order: `-Djuring.data.dir`, `JURING_DATA_DIR`, `./bench-data`, `/home/david/testData`, `/mnt/TestData`, then `./bench-data` (`DatasetManager.java:21-44`). Read/write subdir heuristics: `text_files`/`read_files`/`read`/`multi_file`/`write_files`/`write` (`DatasetManager.java:46-70`).
- `SINGLE_FILE` = one `single_file_bench.bin`, default 10 GiB, overridable with `-Djuring.bench.singleFileSize` (`DatasetManager.java:110-111`); if missing it is *created with buffered `FileChannel` writes and `force(true)`* (`DatasetManager.java:156-206`) - this both writes to disk and pollutes page cache before a benchmark.
- `MULTI_FILE` scans up to 2211 files of >=4 KiB, one directory deep (`DatasetManager.java:113,208-223`).
- Multi-file task pools use a fixed seed `42424242 + threadIndex*31` (`DatasetManager.java:228`), but the SINGLE_FILE offset stream is seeded with `System.nanoTime()` in each benchmark (`DirectReadBenchmark.java:82,164`; `DirectWriteBenchmark.java:74,169`), so single-file runs are **not reproducible run-to-run**.
- The random model is 4 KiB-aligned uniform blocks over the whole file (`DatasetManager.FioPrng`, `DatasetManager.java:431-452`), which approximates fio's `norandommap`/`randrepeat=0` but has no fio-style per-thread random offset generator equivalence proof.

### E.2 fio comparison

- Only `.fio` profiles are committed: `src/test/resources/fio/fio_randread_qd64.fio` and `fio_randwrite_qd64.fio` (ioengine io_uring, direct=1, bs=4k, size=10G, iodepth=64, fixedbufs, registerfiles, runtime 30 s, ramp 5 s, norandommap, randrepeat=0, lat_percentiles=1; batch variants only in comments). There is no committed script that runs fio and JMH together or compares them; the comparison lives only as hand-written tables in `BENCHMARK.md:23-40`.
- A parallel effort's gitignored fio harness now exists at `bench/results/2026-11-13/` (3 reps, `taskset` pinning, JSON+TSV, environment manifest); at time of writing it contains fio read results only (cases a-randread, b-libaio, c-seqread, 3 reps each; `bench/results/2026-11-13/fio-results.tsv:1-8` is the header plus first cases) and its `juring/` output dir is still empty.

### E.3 Limitations of the suite

- Only 4 KiB random O_DIRECT and page-cache workloads; no sequential workload in the JUring suite. (The parallel fio matrix adds a sequential comparison externally: `bench/results/2026-11-13/scripts/fio_matrix.sh:61-64`.)
- No mixed read/write, no fsync/durability loop, no block-size sweep, no QD > 128.
- Main classes report throughput only; per-op latency distributions exist only for QD=1 (`DirectLatencyBenchmark`) and inside the nested `FioComparisonBenchmark`, and *none of the HDR percentile numbers are reported in `BENCHMARK.md`* (grep: no "p99"/"percentile" in either doc).
- `@OperationsPerInvocation(2048)` amortizes 2048 ops per JMH invocation, so JMH per-invocation overhead is small for direct I/O but not for buffered/NOP tiers; `NopBenchmark` is the only explicit overhead probe.
- `submitBatch` in `MultiThreadScalingBenchmark` is set but unused (`:63,85`; the loop calls `submitAndWaitForBatchResult` directly).
- Device/sector counters hard-code `/sys/block/nvme1n1` then "first block device" (`IoHelper.java:219-245`); buffer counts derive from `/proc/self/limits` memlock heuristics (`IoHelper.java:75-114`) - machine-specific.
- No CPU pinning, NUMA, or CPU-frequency policy in the JMH code; `perf`/syscall counters in `BENCHMARK.md:23-29` were collected externally and are not reproducible from source.
- Write results are acknowledged as noisy/drive-limited (`BENCHMARK.md:73-74`), and direct-write benchmarks overwrite the dataset (no verification/checksum of written content).
- No seastar workload exists in the source tree (grep of `src/` for "seastar": no hits); seastar cannot run in this sandbox anyway (Corrections #3).

## F. Tests inventory and gaps

| Test file | Tests | Covers |
|---|---|---|
| `JUringTest.java` | 35 | read/write at offsets, registered files + update, fixed buffers, open/openDirect/close/closeDirect, readv/writev, fsync+datasync, batch-result APIs, buffer-pool exhaustion/double use, ring-full leak-safety (open/write/writeFixed), shared worker ring, page alignment |
| `JUringBlockingTest.java` | 21 | blocking read/write/readFixed/writeFixed/open/openDirect/close, registered files+update, 4-poller concurrent read/write, 200-VT contention, closed-ring write exception |
| `ZeroAllocationHotPathTest.java` | 7 | ThreadMXBean allocation checks for NOP/fixed read/fixed write, batched and single-result |
| `SqeTest.java` | 9 | absolute SQE/CQE/params offsets, user_data encoding, reusable `Result`, zero-alloc polling |
| `ReadvWritevTest.java` | 4 | readv/writev + fixed round-trips |
| `DirectIoTest.java` | 4 | O_DIRECT write/read/zero-copy/round-trip on `@TempDir` |
| `FileDescriptorTest.java` | 2 | open / negative fd |
| blackpanda tests | 8 | 500-VT append concurrency + historic readback (30 s timeout, `BlackPandaConcurrencyTest.java:18-19,91,106-112`), append/tail zero-allocation, tail-buffer semantics, log segment, sparse index |
| `WakePollerStoreLoadTest.java` | jcstress | store/load wakeup protocol, only under `-Pjcstress` (`pom.xml:99-126`) |

### F.1 What is not covered

- Cancellation: no test (grep `prepareCancel` in `src/test`: 0 hits).
- Timeouts/SQPOLL/IOPOLL/R_DISABLED/CQ-overflow/EAGAIN/EINTR at op level/short-read/short-write: no tests.
- Multi-thread-same-ring: no test because unsupported (would corrupt state `[U]`).
- `JUringBlocking`: no zero-allocation assertion, no poller-count matrix, no close-vs-in-flight race test beyond one closed-ring test.
- Buffer-pool double-check-in is tested; pool-close-with-in-flight is not.
- blackpanda: no crash/torn-write recovery test; `recoverState` trusts the file size (`UringPartitionLog.java:153-171`); the CRC field is always written as 0 (`RecordBatch.java:58-65`) and `computeCrc` has no callers; historic reads validate only the magic (`UringHistoricReader.java:184-190`).
- `BlackPandaHeadToHeadBenchmark` has `@Test` methods but its name does not match surefire's defaults (`pom.xml:86-93` has no includes), so `mvn test` does not run it; it also needs a writable `/mnt/data`-class directory.

### F.2 CI / harness status

- No CI configuration and no committed shell runner for the JUring JMH suite (no `.github/`, `.gitlab-ci.yml`, Jenkinsfile, or tracked `run-bench` script); tests require Linux + io_uring and cannot run on generic hosted runners.
- jcstress is opt-in (`pom.xml:99-126`); benchmarks are manual `main()` invocations; there is no perf gate or stored baseline JSON for the JUring JMH suite.
- Flakiness risks: wall-clock timeout assertions (`BlackPandaConcurrencyTest.java:18-19,91`), allocation-measurement JIT heuristics (`ZeroAllocationHotPathTest.java:96-101`), and a shared-load sandbox (fio runlog records loadavg 1.4-2.0 during runs, `bench/results/2026-11-13/fio-runlog.txt`).
- `[V]` On this host, `mvn -o test -Dtest=JUringTest` and `-Dtest=JUringBlockingTest,ZeroAllocationHotPathTest,ReadvWritevTest,DirectIoTest` both pass (2026-11-13).

## G. Gap list vs seastar "basics"

Effort: S < 1 person-week, M = weeks, L = months. Class: **engine** = belongs in the io_uring binding; **framework** = must be built above it; **out-of-scope** = not a kernel-I/O concern.

| # | Gap | Evidence in JUring (missing/partial) | Why it matters | Effort | Class |
|---|---|---|---|---|---|
| G1 | High-level async file/stream abstraction: buffered reads, readahead, write-behind, `flush`/`close`, DMA alignment, complete-until-done | No stream/file class in `lio.api`; only SQE builders (`JUring.java:51-746`). blackpanda is an append-only log, not general file streams (`UringPartitionLog.java:179-289`). Short I/O is caller's problem (`Result.java:70-76`; benchmarks abort, `DirectReadBenchmark.java:113-121`). | seastar's `file`/`file_input_stream`/`file_output_stream` is what applications benchmark; without it JUring benchmarks only raw SQEs and app-level comparison is impossible. | M-L | framework |
| G2 | Multi-shard runtime / shard-per-core scheduling | None; supported pattern is one `JUring` per thread created ad hoc (`DirectReadBenchmark.java:101-104`). `JUringBlocking` is the only runtime: a fixed 1-4 poller pool with no affinity (`JUringBlocking.java:645-682`). | seastar's core value is a reactor per shard with cross-shard calls and fair local scheduling; without a shard abstraction JUring cannot express the same workload or scale predictably with cores. | L | framework |
| G3 | QoS / fair queuing / io_intent | Absent: no public ioprio setter despite the struct accessor (`Sqe.java:90-96`); no scheduler, shares/weights, or intent cancellation; `SqeOptions` only exposes `IOSQE_ASYNC` (`SqeOptions.java:9-16`). | Fairness under mixed classes is a seastar "basic"; without it a multi-tenant comparison is unfair and no isolation can be demonstrated. | L | framework |
| G4 | Cancellation + timeout guarantees | Partial: raw `prepareCancel(userData)` only (`JUring.java:737-746`); no timeout/link-timeout ops (`OperationType.java:5-16`); no completion-after-cancel contract; no tests. | seastar cancellation/timeouts are required for error handling and bounding tail latency; benchmarks need a hard stop for stuck I/O. | M | engine |
| G5 | Observability / metrics | No counters or histograms in `lio.api`; only blackpanda prints 3 counters at close (`UringPartitionLog.java:612-613`). Benchmarks measure externally with perf and sysfs (`BENCHMARK.md:9,23-29`; `IoHelper.java:219-245`). | Cannot compare per-shard queue depth, latency percentiles, or fairness from inside the app; seastar exposes metrics/prometheus as first-class API. Needed for any credible benchmark report. | M | framework |
| G6 | Error/retry policy | Raw negative errno only (`Result.java:70-76`); no typed errors, no EAGAIN/short-I/O retry, no policy hooks; benchmarks throw on any short op (`DirectReadBenchmark.java:113-121`). | Production I/O layers need consistent error semantics and retry/backoff; without it a comparison shows only happy paths. | S-M | framework/API |
| G7 | API stability + documentation | `0.1-SNAPSHOT` (`pom.xml:9`), sparse Javadoc (no per-method docs on `JUring.prepare*`, no `package-info`), blackpanda absent from README/BENCHMARK, no API-versioning/deprecation policy. The "single-threaded" contract is a README comment (`README.md:81`) and one Javadoc block (`JUring.java:916-923`). | A benchmark plan needs a stable surface and explicit contracts (thread ownership, Result lifetime, cancellation semantics); today these are folklore/implied by code. | S | framework |
| G8 | Benchmark reproducibility + CI perf gates | Claimed harness flags not in source (Corrections #1); nanoTime seeds make single-file runs irreproducible (`DirectReadBenchmark.java:82`); no committed runner/JSON; benchmark classes never run in the build; no perf regression thresholds; no seastar-side workload (grep). | Numbers cannot be re-derived or gated; a seastar baseline will be compared against unverifiable numbers. Highest leverage for "catching up on the basics" because it makes every later claim credible. | M | process/framework |
| G9 | Provided-buffer ring + multishot | `IOSQE_BUFFER_SELECT` enum exists but no `IORING_REGISTER_PBUF_RING`/buf-group API (`IoUringDispatcher.java:49-52`); no multishot ops, no `IORING_CQE_F_MORE` handling (`Cqe.java:37-39`). | seastar read paths use registered buffer rings and multishot to cut syscalls; without them the low-syscall-per-op comparison is incomplete. | M | engine |
| G10 | Registration lifecycle | `registerBuffers` once per ring (`JUring.java:846-850`); no unregister files/buffers; no eventfd registration; io-wq tuning not exposed (`IoUringDispatcher.java:921-934` unused). | Long-running multi-shard services need to add/remove files and buffers at runtime; today a ring's registration set is static and leaks slots until close. | M | engine |
| G11 | Networking / socket ops (accept/connect/recv/send) | Absent since the rewrite (`OperationType.java:5-16`; deleted by `2c288da`, added by `612670b`). | seastar is a network framework; full parity eventually needs sockets. Currently out of the stated focus (`README.md:13`), so only needed for broad "basics" parity, not for the disk-I/O plan. | L | out-of-scope (current focus) |
| G12 | Durability/consistency abstraction | `FSYNC`/`DATASYNC` op exists (`JUring.java:708-735`) but no flush policy or group-commit API in `lio.api`; blackpanda hand-rolls fsync-on-512 MiB/link (`UringPartitionLog.java:376-390`); recovery trusts file size and CRC is dead (`RecordBatch.java:58-65`; `UringHistoricReader.java:184-190`). | Write benchmarks without durability semantics are not comparable to seastar's flush/close guarantees; blackpanda crash-consistency claims are unverified. | M | framework (engine+blackpanda) |

### G.1 Cheapest measurable milestone per gap (for plan prioritisation)

- **G8 (do first):** commit a runner that pins forks/iterations/time/seeds and emits JMH JSON plus fio JSON into one directory; gate a 3-run median within +/-10% of a stored baseline on one fixed host. Removes Correction #1 and makes every later number re-derivable.
- **G1:** add a `file_input_stream`-like readahead wrapper (128 KiB default, N buffers ahead) on fixed buffers, and a sequential-read benchmark reporting MB/s vs `FileChannel` and vs fio `seqread`; success = steady MB/s within 5% of fio seqread at QD1-8.
- **G2:** add a `Shard`/`Reactor` utility (shard = CPU, private `JUring` + task queue + cross-shard submit), extend `MultiThreadScalingBenchmark` to report per-shard ops/s; success = near-linear scaling at 1/2/4/8 shards with <5% per-shard variance.
- **G3:** expose per-request ioprio/weight (wire `Sqe.setIoprio`) and a two-class weighted scheduler; success = measured 70/30 read bandwidth split within +/-10% under contention.
- **G4:** add `waitForResult(timeoutNanos)` plus `IORING_OP_TIMEOUT`/link-timeout support; success = an intentionally-blocked read returns a timeout while a concurrently cancelled read returns `-ECANCELED`, with zero leaked native buffers/fds after close.
- **G5:** expose ring counters (submitted/completed/in-flight/SQ-full/enter count/CQ batch size) and report p50/p99 latency as JMH secondary metrics; success = one run yields ops/s + p99 with no external perf.
- **G6:** add a small `IoException`/errno enum + `retryOnEagain` policy in the stream layer; success = a test at EOF returns short-read and an EAGAIN-inducing pressure test retries to completion.
- **G7:** generate Javadoc and a `docs/api.md` listing every public type with stability/ownership annotations; success = `mvn javadoc:javadoc` in the build with zero missing-comment warnings on the public API surface.

## Appendix: probes executed for this document `[V]`

- `mvn -o -q -DskipTests compile` -> OK.
- `mvn -o test -Dtest=JUringTest` and `-Dtest=JUringBlockingTest,ZeroAllocationHotPathTest,ReadvWritevTest,DirectIoTest` -> exit 0.
- Ad-hoc `/tmp` probe of `new JUring(64, flag)`: `SINGLE_ISSUER`, `SINGLE+COOP+DEFER`, `SQPOLL`, `SQPOLL+SINGLE`, `SQ_AFF`, `IOPOLL`, `SQE128`, `CQE32`, `NO_SQARRAY`, `CLAMP`, `SUBMIT_ALL`, `TASKRUN_FLAG` set up + NOP OK; `DEFER_TASKRUN` alone EINVAL; `CQSIZE` EINVAL; `NO_MMAP`/`REGISTERED_FD_ONLY` EFAULT; `R_DISABLED` setup OK then submit EBADF; `HYBRID_IOPOLL` EINVAL.
- `READ_FIXED` 4 KiB from `/mnt/TestData/read_files/single_file_bench.bin`: OK with default / SQPOLL / SQE128 / CQE32 / NO_SQARRAY; `-95` (EOPNOTSUPP) with IOPOLL; EINVAL at setup with SQPOLL+DEFER.
- Untracked parallel harness observed at `bench/results/2026-11-13/` (fio read matrix, 3 reps, JSON/TSV/manifest); it is not part of this document's claims about the JUring JMH suite.
