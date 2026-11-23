# Upgrading JUring

An upgrade should be deliberate. This file records every user-visible API change per release, so
you can see what moved and what to do about it. The rules are in
[docs/api-stability.md](api-stability.md).

## Status

| item | value |
|---|---|
| project version | `0.1.0` |
| release status | **0.1.0** is the first public release; prepared, not yet tagged or published to Maven Central |
| deprecations added by 0.1.0 | none |
| pre-existing deprecations (pre-policy) | one, see [§3](#3-deprecations) |
| compatibility policy | in effect from 0.1.0; see [api-stability.md](api-stability.md) |

0.1.0 is the first public release. There is no earlier released version to migrate from; the
entries below are the upgrade history future releases build on.

## 1. Versioning rules at a glance

The full policy is in [docs/api-stability.md](api-stability.md); this table is the short version.

| change | pre-1.0 (`0.y.z`) | post-1.0 | migration note required? |
|---|---|---|---|
| `PATCH` | compatible fixes/documentation only; no API or documented-semantics change | bug fixes only | no |
| `MINOR` | additions; breaking changes only if unavoidable, with a deprecation cycle where feasible | new backward-compatible functionality | yes for breaking changes/deprecations |
| `MAJOR` | reserved for `1.0.0` (API freeze) | breaking changes only here | yes |

The supported public API is `com.davidvlijmincx.lio.api` (all public and protected types and
members); package-private internals are not part of it. The pre-release prototype package
`com.davidvlijmincx.lio.blackpanda` was removed on 2026-11-16 to keep JUring library-first.

## 2. Capability history

The 0.1 line grew additively: each step added surface, and none changed a signature
incompatibly. The only removal is the pre-release prototype package
`com.davidvlijmincx.lio.blackpanda` (2026-11-16), which had no compatibility guarantee.

| capability added | notable public API | compatibility |
|---|---|---|
| async file handle abstraction | `JuringFile` (offset I/O, length, truncate, allocate, discard, fsync, bounded close), typed `JuringException` errno mapping, short-I/O/EAGAIN policy via `IoPolicy`, runtime file registration (`register`/`unregister`) | additive |
| buffered stream layer | `JuringInputStream` (read-ahead, `readChunk`, complete-until-done reads), `JuringOutputStream` (write-behind, `flush`), `InputStreamOptions`, `OutputStreamOptions` | additive |
| request-lifecycle hardening | cancellation contract (`JuringFile.CancelStatus`, `JuringFile.cancel`/`cancelAll`), timeouts and linked timeouts (`prepareTimeout`, `prepareLinkTimeout`, timed `JuringFile` overloads), bounded shutdown | additive |
| multi-ring / shard runtime | `IoShard`, `RingGroup`, `ShardOptions`, `ShardTask`, `ShardContext`, `CompletionHandler`, `ShardStats`; `JUringBlocking` can adopt a `RingGroup` | additive |
| observability | `JUring.ringMetrics()` + `RingMetrics`, `LatencyHistogram`/`LatencySnapshot`, `IoShard.metrics()`/`RingGroup.metrics()` + `ShardMetrics`/`GroupMetrics`, JMX (`MetricsJmx`, `IoShardMetricsMXBean`, `RingGroupMetricsMXBean`), benchmark JSON 1.1 | additive |
| buffered I/O parity + durability | `DurabilityMode`, `FadviseAdvice` (+ `JuringFile.fadvise`), durability-aware stream `flush`/`close`, `GroupCommit`/`GroupCommitOptions`/`GroupCommitStats` | additive |
| library-first scope cleanup | removed the pre-release prototype package `com.davidvlijmincx.lio.blackpanda` (8 public types; no compatibility guarantee) | **pre-release removal**: no supported `lio.api` API changed |

## 2a. 0.1.0 changes

Part of 0.1.0, the first public release; they're the baseline for later entries. A later
minor release was planned around the capacity-model and interrupt-semantics work below; it
was never published, so that work ships in 0.1.0.

**Additive API**

| addition | notes |
|---|---|
| 16 exact-arity `JUringBlocking` read/write/`*Fixed` overloads | no-option variants that avoid the varargs `SqeOptions[]` allocation; the varargs forms remain for explicit flags |
| `JUring.isSupported()` | cached `io_uring_setup` probe, intended for `java.nio` fallback decisions in libraries |
| `RingMetrics.cqFlushEnters()` | count of non-blocking `enter(GETEVENTS)` flushes that drain the kernel CQ-overflow list or run deferred task work. A new record component changes `RingMetrics`'s canonical constructor. Users shouldn't construct `RingMetrics` directly (the library creates these snapshots), so this is additive for normal use but not source or binary compatible for direct constructor calls. |
| `JuringInputStream.abandonedAtClose()` / `JuringOutputStream.abandonedAtClose()` | number of reads/writes abandoned by the hard-bounded `close()` (`0` for a normal close); the buffers are intentionally kept alive |
| `JUring.prepareReadUnique(FileDescriptor, MemorySegment[, int], long)` | raw read variant that returns a unique operation handle even when the same segment has other in-flight reads. The completion carries no buffer (`Result.validBuffer()` is `NULL`); the address-keyed `prepareRead` overloads keep the address payload and still require distinct buffers per concurrent read. |
| shared-ring completion multiplexing | `JuringFile`, `JuringInputStream`, `JuringOutputStream` and `GroupCommit` handles can share one `JUring` on its owner thread; completions are routed to the issuing handle. Raw un-reaped `JUring.prepare*` work still makes the blocking helpers throw. |
| `IoPriority` block-layer hint | `JUring.defaultIoprio()`, `setDefaultIoprio(short)`, `setLastSqeIoprio(short)`, `JuringFile.withIoPriority(short)` and `ioPriority()`; read/write opcodes only; scheduler-dependent, not QoS. |
| `BlockingOptions` | immutable configuration for `JUringBlocking`: queue depth, poller count, `maxConcurrentCalls`, `OverflowPolicy` and an optional pre-park spin, with `defaults()`, `with*` builders, `resolvedPollers()` and `estimatedBytes()` |
| `OverflowPolicy { BLOCK, FAIL }` | what a call does when every call record of its worker is in use: `BLOCK` (default) waits for one, `FAIL` throws `IllegalStateException` |
| `JUringBlocking(BlockingOptions, IoUringOptions...)` | explicit configuration; the two older constructors keep the compatibility defaults |
| `JUringBlocking.options()`, `maxConcurrentCalls()`, `recordsPerWorker()`, `parkedCallers()`, `overflowWaits()` | read the effective capacity and its diagnostics |
| `JuringException.EINTR = 4` | the errno a blocking call returns when it is interrupted while parked |

**Behaviour changes**

| change | effect / migration |
|---|---|
| `JUring` enforces its single-owner-thread contract | Data-plane, registration and buffer-pool methods called from a foreign thread throw `WrongThreadException`; `close(long)` while open must be called by the owner. `isClosed()`, `ringMetrics()`, `cancelledAtClose()`, `abandonedAtClose()` and the read-only `inFlightCount()` remain callable from any thread. Create one ring per thread, or use `IoShard`/`RingGroup`. |
| `JUring` drains CQ-overflow completions automatically | Peek and blocking-wait paths may issue one non-blocking `enter(GETEVENTS)` when the visible CQ is empty but work is outstanding. No caller action needed; this fixes lost completions and hangs at very deep in-flight counts. |
| `minComplete > CQ size` is rejected | `submitAndWait`, `waitForBatchResult*` and `submitAndWaitForBatchResult*` throw `IllegalArgumentException` instead of blocking forever. |
| No-completion waits are rejected | `waitForResult`, `waitForResult(target)` and `waitForBatchResult*` with `minComplete > 0` throw `IllegalStateException` when no completion can arrive: nothing is prepared and unreaped, or fewer operations are submitted than the wait requires. Prepared SQEs are only published by `submit()`, so prepare-then-wait without `submit()` fails fast. `minComplete == 0` polling is unchanged. |
| Stream `close()` is hard-bounded | Both stream `close()` methods bound the in-flight drain, final truncate and durability sync by one deadline of `DEFAULT_CLOSE_TIMEOUT_MILLIS` (2000 ms) + 250 ms grace; they never fall back to an unbounded wait. A request that misses it is abandoned: its buffer stays alive (a bounded leak, never use-after-free), the request is left to the backing `JuringFile`/`JUring` shutdown, `close()` throws `JuringException` with `ETIMEDOUT`, and `abandonedAtClose()` reports the retained buffers (truncate/sync and buffer release are skipped). If only the final truncate/sync misses the deadline, `close()` still throws `ETIMEDOUT` but the buffers are released. `bufferMemoryBytes()` keeps reporting the retained size after an abandoned close, and ring-ownership misuse that leaves I/O un-reapable is counted too. |
| Unwired setup flags are rejected | `SQ_AFF`, `CQSIZE`, `NO_MMAP`, `REGISTERED_FD_ONLY`, `HYBRID_IOPOLL`, `IOPOLL`, `R_DISABLED`, user-supplied `ATTACH_WQ`, `DEFER_TASKRUN` without `SINGLE_ISSUER`, and `SQPOLL` on shared-worker rings throw `IllegalArgumentException` with the reason. See `docs/platforms.md`. |
| Read buffer pool tracks ownership | `setupReadBufferPool`/`prepareReadPooled`/`checkInReadBuffer` reject double and foreign check-ins; an exhausted pool throws instead of allocating outside its capacity; replacing a pool with outstanding buffers throws. |
| `JUringBlocking` buffer pool tracks ownership | `checkInBuffer` rejects double and foreign check-ins; a second `registerBuffers` call throws. |
| `JUringBlocking` frees registered-buffer memory only after all shards terminate | Shutdown no longer frees shared buffer memory while a sibling ring could still touch it. |
| Oversubscription | Before 0.1.0 a call that found every call record of its worker in use retried with `LockSupport.parkNanos(10 µs)` until one was free. `OverflowPolicy.BLOCK` (default) now parks on the worker's admission monitor until a record is released; `OverflowPolicy.FAIL` throws `IllegalStateException` immediately. No source change; raise `withMaxConcurrentCalls(...)` if `overflowWaits()` grows, or choose `FAIL` to make an undersized budget fail fast. |
| Interrupt during a parked call | Before 0.1.0 the interrupt was ignored and the call kept waiting for its completion. The call now requests an `IORING_OP_ASYNC_CANCEL` and returns `-EINTR` once the kernel completion (the cancellation or the real result) has been reaped; the interrupt flag is preserved. Callers that see `-EINTR` follow their own interrupt policy; a cancelled write may already have taken effect. |
| Non-power-of-two `RingGroup` | Before 0.1.0 worker selection used `threadId & (size - 1)`, so a group whose size was not a power of two left workers unused. Selection now falls back to `% size`; every worker receives submissions. No action needed. |
| Constructor argument validation | Before 0.1.0 `new JUringBlocking(queueDepth, ...)` accepted any queue depth (the ring was clamped to at least 8 entries) and any poller count. `queueDepth` must now be `1..32768` and an explicit poller count `1..1024`; other values throw `IllegalArgumentException`. Pass a valid value (`0` or negative queue depths were never meaningful). |

Capacity defaults are unchanged from the pre-release constructor-only configuration: with
`maxConcurrentCalls` 0, each worker still derives `nextPow2(max(256, queueDepth × 8))` records,
so code that does not use `BlockingOptions` gets the same capacity. The capacity model, sizing
recipe and memory math are in [docs/vt-scale.md](vt-scale.md).

## 3. Deprecations

0.1.0 adds no deprecations. The tree has exactly one `@Deprecated` member; it predates the
stability policy and is grandfathered.

### Pre-existing deprecation (pre-policy)

| member | replacement | removal version | note |
|---|---|---|---|
| `JUring.prepareCancel(long)` | `JUring.prepareCancelByUserData(long)` | none announced | predates the policy; grandfathered |

- The argument of `prepareCancel(long)` is the target request's `user_data` (the handle
  returned by the `prepare*` methods), not a separate logical operation id.
  `prepareCancelByUserData(long)` makes that explicit.
- Because the deprecation predates the policy, no removal version was announced. It remains at
  least until the first release plus one MINOR; from then on, removal requires a MINOR release
  with an entry in this file.
- Migration: replace `prepareCancel(handle)` with `prepareCancelByUserData(handle)`; the
  deprecated method delegates, so behaviour is identical.

No public API has been removed from the 0.1 line so far.

## 4. Migration notes

- From a planned later minor release to 0.1.0: nothing to migrate. The
  `BlockingOptions`/`OverflowPolicy` configuration and accessors and the interrupted-call
  behaviour (`-EINTR`) that had been planned for it ship in 0.1.0 instead; see §2a. A caller
  that sees `-EINTR` follows its own interrupt policy.
- From 0.1.0 to a later 0.x release: future releases record breaking changes and deprecations
  here, with the 0.1.0 baseline in §2a.
- From the pre-rewrite JUring API (the older socket operations and primitives removed before
  the current library focus): that API was never released, so there is no supported migration
  path. If you have code against an old pre-release snapshot, port it to the current public API
  and treat it as a rewrite, not an upgrade.

## 5. Reporting

If a release breaks something this file promised, report it at
[github.com/davidtos/JUring](https://github.com/davidtos/JUring) with the version, affected type
or member, and a minimal reproduction. The correction is recorded here.
