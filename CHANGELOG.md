# Changelog

All notable changes to JUring are documented here. The project follows
[Semantic Versioning](https://semver.org/) with the pre-1.0 adaptations described in
[docs/api-stability.md](docs/api-stability.md).

## [0.1.0] — 2026-11-16

First release. JUring drives Linux `io_uring` file I/O directly from Java through the
Foreign Function & Memory API: no JNI, no C library, no `liburing`. The artifact is
`io.github.davidtos:juring` (module name `JUring`, package `com.davidvlijmincx.lio.api`).

### Added

- Raw single-owner ring API (`JUring`) with registered files, fixed buffers, vectored I/O,
  open/close, fsync/fdatasync, fallocate, ftruncate, statx, timeouts/linked timeouts,
  cancellation, and bounded close.
- `JuringFile` (offset reads/writes, truncate/allocate/discard, fsync, typed
  `JuringException`, bounded close, cancellation statuses), `JuringInputStream` and
  `JuringOutputStream` (bounded read-ahead/write-behind), `GroupCommit` for durable batching
  and `DurabilityMode`/`FadviseAdvice`.
- Blocking / virtual-thread facade (`JUringBlocking`) with a park/unpark design that does not
  pin carrier threads; exact-arity no-option overloads are allocation-free.
- Multi-ring runtime (`IoShard`, `RingGroup`, `ShardOptions`) with affinity, backpressure,
  completion/handoff accounting, and cross-thread-safe stats.
- Observability: `RingMetrics`, `ShardMetrics`, `GroupMetrics`, latency histograms, JMX
  MXBeans, and machine-readable benchmark JSON.
- `JUring.isSupported()`: cached `io_uring_setup` probe for `java.nio` fallback decisions.
- `docs/platforms.md`: OS/arch/JDK/kernel floors, native-access flags, container/seccomp and
  `kernel.io_uring_disabled` caveats, O_DIRECT/memlock guidance.
- Reproducible benchmark programme under `bench/` (fio-matched and Seastar brackets, raw
  per-rep JSON, CI reports) and `BENCHMARK.md`.
- GitHub Actions CI (JDK 25/26) and a manual, credential-gated Central release workflow.

### Fixed

- **CQ-overflow completion loss / hang**: completions parked in the kernel CQ-overflow list are
  now drained with a non-blocking `enter(GETEVENTS)`; `waitForBatchResult(1)` no longer hangs at
  deep in-flight counts. Waiting for more completions than the CQ can hold now throws
  `IllegalArgumentException` instead of blocking forever.
- **Attached rings lost optional-opcode support**: `getSharedWorkerRing` now probes the new ring,
  so timeouts/statx/fallocate/ftruncate work there instead of throwing
  `UnsupportedOperationException`.
- **Buffer-pool ownership**: double/foreign check-ins are rejected for `ReadBufferPool` and
  `JUringBlocking`'s registered-buffer pool; an exhausted read pool throws instead of silently
  allocating outside its capacity (a double free was possible before).
- **Registered-buffer lifetime in `JUringBlocking`**: the shared buffer memory is freed only
  after every shard terminated, removing a use-after-free during multi-worker shutdown.
- **Owner-thread contract is enforced**: data-plane/registration calls from a foreign thread
  throw `WrongThreadException`; `closed` is volatile.
- **Setup-flag validation**: unwired flags (`SQ_AFF`, `CQSIZE`, `NO_MMAP`,
  `REGISTERED_FD_ONLY`, `HYBRID_IOPOLL`, `IOPOLL`) and invalid combinations
  (`DEFER_TASKRUN` without `SINGLE_ISSUER`, `SQPOLL` with `ATTACH_WQ`) fail with actionable
  messages before setup.
- Optional libc symbols (`sched_getcpu`, `posix_fadvise`) are resolved lazily, so a libc without
  them no longer prevents JUring from loading.

### Changed

- Maven coordinates: `io.github.davidtos:juring` (previously an unpublished
  `com.davidvlijmincx.lio:JUring` snapshot). Java package and module name are unchanged.
- License: Apache-2.0 (previously Unlicense).
- The "zero allocation" claim is now precise: the raw-ring path and `JUringBlocking`'s
  no-option overloads are allocation-free; passing `SqeOptions` explicitly allocates the
  varargs array.
- Internal process/audit documents live under `docs/internal/`; user documentation is
  README, BENCHMARK, `docs/api-stability.md`, `docs/UPGRADING.md`, `docs/examples.md`,
  `docs/benchmark-methodology.md`, `docs/m4-decision.md` and `docs/platforms.md`.
