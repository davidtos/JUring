# Changelog

All notable changes to JUring are documented here. The project follows
[Semantic Versioning](https://semver.org/) with the pre-1.0 adaptations described in
[docs/api-stability.md](docs/api-stability.md).

## [Unreleased] — 0.2.0 (planned)

### Added

- `BlockingOptions`: immutable configuration for `JUringBlocking` — queue depth, poller
  count, `maxConcurrentCalls`, `OverflowPolicy` and an optional pre-park spin — with
  `defaults()`/`with*` builders, `resolvedPollers()` and `estimatedBytes()` (the heap cost of
  the pre-allocated call records).
- `OverflowPolicy { BLOCK, FAIL }`: what a blocking call does when every call record of its
  worker is in use. `BLOCK` (default) waits for a record; `FAIL` throws
  `IllegalStateException`.
- `JUringBlocking(BlockingOptions, IoUringOptions...)`: explicit configuration, alongside the
  existing `(int, IoUringOptions...)` and `(int, int, IoUringOptions...)` constructors, which
  keep the 0.1.0 defaults.
- `JUringBlocking.options()`, `maxConcurrentCalls()`, `recordsPerWorker()`, `parkedCallers()`
  and `overflowWaits()`: the effective capacity and its diagnostics.
- `JuringException.EINTR = 4`: the errno a blocking call returns when it is interrupted while
  parked.
- `JUringBlockingScaleTest`: committed scale test (10,000 virtual threads by default, larger
  via `-Djuring.scale.vt=N`) that blocks every virtual thread in one facade at once and
  exercises the admission path.
- `bench/blocking/run-scale.sh`: opt-in 1,000,000-virtual-thread runner that adds
  `-Djuring.scale.vt=1000000` and `-Xmx6g` to the forked test JVM.
- `docs/vt-scale.md`: capacity model, sizing recipe, memory math, interrupt semantics and
  the scale evidence.

### Changed

- `JUringBlocking` oversubscription no longer retries in a timed loop: a caller that finds
  every call record of its worker in use parks on the worker's admission monitor
  (`OverflowPolicy.BLOCK`) or throws immediately (`OverflowPolicy.FAIL`). Previously it
  retried with `LockSupport.parkNanos(10 µs)`. The wait set is VM-managed, so waiting costs
  no per-caller library object and does not pin a virtual thread's carrier.
- Interrupting a parked `JUringBlocking` call now cancels the request in the kernel
  (`IORING_OP_ASYNC_CANCEL`) and returns `-EINTR` once the kernel completion has been reaped,
  preserving the interrupt flag. Previously the interrupt was ignored and the call kept
  waiting. This is a documented behaviour change (MINOR); a cancelled write may already have
  taken effect.
- The zero-allocation documentation for the blocking facade is now explicit that the
  guarantee is caller-side: the hot path allocates nothing after construction, and the call
  records it uses are pre-allocated at construction, so capacity — not per-call allocation —
  bounds concurrent calls.

- The `JUringBlocking(int[, int], ...)` constructors now validate their arguments: the queue
  depth must be in `1..32768` and an explicit poller count in `1..1024` (previously the ring
  was silently clamped to at least 8 entries and any poller count was accepted).

### Fixed

- `JUringBlocking` adopting a non-power-of-two `RingGroup` now routes calls across all
  workers. Worker selection used `threadId & (size - 1)`, which stranded workers when the
  group size was not a power of two (for example size 3 only ever used workers 0 and 2).
- The build on Maven 3.10.0 (the version GitHub Actions runners ship). Maven 3.10 sets
  `project.build.outputTimestamp` by default, which makes maven-compiler-plugin patch
  `module-info.class` for reproducible builds; maven-compiler-plugin 3.13.0 did that patch
  with ASM 9.6, which rejects Java 25 class files (`Unsupported class file major version
  69`). Upgraded maven-compiler-plugin to 3.16.0 (plexus-java 1.6.0, ASM 9.10.1).

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
- `JuringInputStream.abandonedAtClose()` / `JuringOutputStream.abandonedAtClose()`: count the
  reads/writes abandoned by the hard-bounded stream close (`0` for a normal close).
- `JUring.prepareReadUnique(...)`: raw read variant whose handle stays unique even when the same
  `MemorySegment` is used by concurrent in-flight reads (the reaped `Result` carries no buffer;
  `validBuffer()` is `NULL`).
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
- **Stream close could hang forever**: `JuringInputStream.close()` and `JuringOutputStream.close()`
  no longer fall back to an unbounded wait. The in-flight drain, the final truncate and the
  durability sync share one 2 s + 250 ms grace deadline; a write that misses it is abandoned
  (its buffer is kept alive — a bounded leak, never a use-after-free), `close()` throws
  `ETIMEDOUT` and the count is reported by `abandonedAtClose()`. A truncate/sync-only timeout
  also throws `ETIMEDOUT` but releases the buffers.
- **Waits with nothing in flight blocked forever**: `waitForResult`/`waitForResult(target)` and
  `waitForBatchResult*` with `minComplete > 0` now throw `IllegalStateException` when no
  completion can arrive: nothing is prepared and unreaped, or fewer operations are submitted
  than the wait requires (prepared SQEs are only published by `submit()`).
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
