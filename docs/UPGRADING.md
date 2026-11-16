# Upgrading JUring

This file records every user-visible API change per release, so that an upgrade is a
deliberate act with a before/after recipe. The versioning rules behind it are defined in
[`docs/api-stability.md`](api-stability.md) — this file is the changelog those rules refer
to.

## Status

| item | value |
|---|---|
| project version | `0.1.0` |
| released versions | **0.1.0** (first release; tag `v0.1.0`) |
| current deprecations added by 0.1.0 | **none** |
| pre-existing deprecations (pre-policy) | **one**, see [§3](#3-deprecations) |
| compatibility policy | in effect from 0.1.0; see [api-stability.md](api-stability.md) |

**0.1.0 is the first release.** There is no earlier released version to migrate from; the
entries below are the upgrade history future releases build on.

## 1. Versioning rules at a glance

The full policy is in [`docs/api-stability.md`](api-stability.md); the table is the short
version.

| change | pre-1.0 (`0.y.z`) | post-1.0 | migration note required? |
|---|---|---|---|
| `PATCH` | compatible fixes/documentation only; no API or documented-semantics change | bug fixes only | no |
| `MINOR` | additions; breaking changes only if unavoidable, with a deprecation cycle where feasible | new backward-compatible functionality | **yes for breaking changes/deprecations** |
| `MAJOR` | reserved for `1.0.0` (API freeze) | breaking changes only here | **yes** |

The supported public API is `com.davidvlijmincx.lio.api` (all public/protected types and
members); package-private internals are not part of the API at all. (An earlier pre-release
prototype package, `com.davidvlijmincx.lio.blackpanda`, was removed from the repository in
2026-11-16 to keep JUring strictly library-first.)

## 2. Capability history (additive; one pre-release prototype package removed)

The 0.1 line grew additively. Each capability step below added surface and none
changed an existing signature incompatibly; the only removal is the pre-release prototype
package `com.davidvlijmincx.lio.blackpanda` (D12, 2026-11-16), which carried no compatibility
guarantee.

| step | capability added | notable public API | compatibility |
|---|---|---|---|
| **B1** | async file handle abstraction | `JuringFile` (open, offset read/write, length, truncate, allocate, discard, fsync, bounded close), typed `JuringException` errno mapping, short-I/O/EAGAIN policy via `IoPolicy`, runtime file registration (`register`/`unregister`) | additive |
| **B2** | buffered stream layer | `JuringInputStream` (read-ahead, `readChunk`, complete-until-done reads), `JuringOutputStream` (write-behind, `flush`), `InputStreamOptions`, `OutputStreamOptions` | additive |
| **B3** | request-lifecycle hardening | cancellation contract (`JuringFile.CancelStatus`, `JuringFile.cancel`/`cancelAll`), timeouts and linked timeouts (`prepareTimeout`, `prepareLinkTimeout`, timed `JuringFile` overloads), bounded shutdown | additive |
| **B4** | multi-ring / shard runtime (L2 basics) | `IoShard`, `RingGroup`, `ShardOptions`, `ShardTask`, `ShardContext`, `CompletionHandler`, `ShardStats`; `JUringBlocking` can adopt a `RingGroup` | additive |
| **B5** | observability | `JUring.ringMetrics()` + `RingMetrics`, `LatencyHistogram`/`LatencySnapshot`, `IoShard.metrics()`/`RingGroup.metrics()` + `ShardMetrics`/`GroupMetrics`, JMX (`MetricsJmx`, `IoShardMetricsMXBean`, `RingGroupMetricsMXBean`), benchmark JSON 1.1 | additive |
| **B6** | buffered I/O parity + durability | `DurabilityMode`, `FadviseAdvice` (+ `JuringFile.fadvise`), durability-aware stream `flush`/`close`, `GroupCommit`/`GroupCommitOptions`/`GroupCommitStats` | additive |
| **D12** | scope enforcement (library-first) | removed the pre-release prototype package `com.davidvlijmincx.lio.blackpanda` (8 public types; no compatibility guarantee) | **pre-release removal** — no supported `lio.api` API changed |

For the design/evidence record of each step see `docs/internal/b1-report.md`, `docs/internal/b2-report.md`,
`docs/internal/b3-design.md`, `docs/internal/b4-report.md`, `docs/internal/b5-report.md` and `docs/internal/b6-report.md`.

## 2a. 0.1.0 release-readiness changes (unreleased)

These changes are part of the 0.1.0 release. Because no version has been released yet, they are
recorded here for the first release's upgrade history; they do not affect any published user.

**Additive API**

| addition | notes |
|---|---|
| 16 exact-arity `JUringBlocking` read/write/`*Fixed` overloads | no-option variants that avoid the varargs `SqeOptions[]` allocation; the varargs forms remain for explicit flags |
| `JUring.isSupported()` | cached `io_uring_setup` probe; intended for `java.nio` fallback decisions in libraries |
| `RingMetrics.cqFlushEnters()` | count of non-blocking `enter(GETEVENTS)` flushes issued to drain the kernel CQ-overflow list / run deferred task work. Note: adding a record component changes `RingMetrics`'s canonical constructor; users should not construct `RingMetrics` directly (the library creates these snapshots), so this is additive for normal use but not source/binary compatible for direct constructor calls. |
| `JuringInputStream.abandonedAtClose()` / `JuringOutputStream.abandonedAtClose()` | number of reads/writes abandoned by the hard-bounded `close()` (`0` for a normal close); the corresponding buffers are intentionally kept alive |
| `JUring.prepareReadUnique(FileDescriptor, MemorySegment[, int], long)` | raw read variant that returns a unique operation handle even when the same segment has other in-flight reads; the completion carries no buffer (`Result.validBuffer()` is `NULL`). The address-keyed `prepareRead` overloads keep the address payload for `Result.validBuffer()` and still require distinct buffers per concurrent read. |

**Behaviour changes (all part of the unreleased 0.1.0 line)**

| change | effect / migration |
|---|---|
| `JUring` enforces its single-owner-thread contract | data-plane, registration and buffer-pool methods called from a foreign thread throw `WrongThreadException`; `close(long)` while open must be called by the owner. `isClosed()`, `ringMetrics()`, `cancelledAtClose()`, `abandonedAtClose()` and the read-only `inFlightCount()` remain callable from any thread. Create one ring per thread (or use `IoShard`/`RingGroup`). |
| `JUring` automatically drains CQ-overflow completions | peek/blocking-wait paths may issue one non-blocking `enter(GETEVENTS)` when the visible CQ is empty but work is outstanding. No caller action needed; fixes lost completions/hangs at very deep in-flight counts. |
| `minComplete > CQ size` is rejected | `submitAndWait`, `waitForBatchResult*`, `submitAndWaitForBatchResult*` throw `IllegalArgumentException` instead of blocking forever. |
| No-completion waits are rejected | `waitForResult`, `waitForResult(target)` and `waitForBatchResult*` with `minComplete > 0` throw `IllegalStateException` when no completion can arrive: nothing is prepared and unreaped, or fewer operations are submitted than the wait requires. Prepared SQEs are only published by `submit()`, so prepare-then-wait without `submit()` now fails fast instead of hanging. `minComplete == 0` polling is unchanged. |
| Stream `close()` is hard-bounded | `JuringInputStream.close()` / `JuringOutputStream.close()` bound the in-flight drain, the final truncate and the durability sync by one deadline of `DEFAULT_CLOSE_TIMEOUT_MILLIS` (2000 ms) + 250 ms grace, and never fall back to an unbounded wait. A request that misses the deadline is abandoned: its buffer is kept alive (bounded leak, never a use-after-free), the request is left to the backing `JuringFile`/`JUring` shutdown, `close()` throws `JuringException` with `ETIMEDOUT`, and `abandonedAtClose()` reports the retained buffers (truncate/sync and buffer release are skipped). If only the final truncate/sync misses the deadline, `close()` still throws `ETIMEDOUT` but the stream buffers are released. `bufferMemoryBytes()` keeps reporting the retained size after an abandoned close, and ring-ownership misuse that leaves I/O un-reapable is counted too. |
| Unwired setup flags are rejected | `SQ_AFF`, `CQSIZE`, `NO_MMAP`, `REGISTERED_FD_ONLY`, `HYBRID_IOPOLL`, `IOPOLL`, `R_DISABLED`, user-supplied `ATTACH_WQ`, `DEFER_TASKRUN` without `SINGLE_ISSUER`, and `SQPOLL` on shared-worker rings throw `IllegalArgumentException` with the reason. See `docs/platforms.md`. |
| Read buffer pool tracks ownership | `setupReadBufferPool`/`prepareReadPooled`/`checkInReadBuffer` reject double/foreign check-ins, an exhausted pool throws instead of allocating outside its capacity, and replacing a pool with outstanding buffers throws. |
| `JUringBlocking` buffer pool tracks ownership | `checkInBuffer` rejects double/foreign check-ins; a second `registerBuffers` call throws. |
| `JUringBlocking` frees registered-buffer memory only after all shards terminate | shutdown no longer frees shared buffer memory while a sibling ring could still touch it. |

## 3. Deprecations

**B9 adds no deprecations.** The tree contains exactly **one** `@Deprecated` member; it
predates the stability policy and is grandfathered by it.

### Pre-existing deprecation (pre-policy)

| member | replacement | removal version | note |
|---|---|---|---|
| `JUring.prepareCancel(long)` | `JUring.prepareCancelByUserData(long)` | none announced | predates the policy; grandfathered |

- The argument of `prepareCancel(long)` is the target request's `user_data` (the handle
  returned by the `prepare*` methods), not a separate logical operation id;
  `prepareCancelByUserData(long)` makes that semantics explicit.
- Because the deprecation predates the policy, **no removal version was announced**. It is
  promised to remain at least until the first release **plus one MINOR**. From the first
  release onward, removing it requires a MINOR release with an entry in this file.
- Migration: replace `prepareCancel(handle)` with `prepareCancelByUserData(handle)`. The
  behaviour is identical — the deprecated method delegates to the replacement.

No public API has been removed from the 0.1 line so far.

## 4. Migration notes

- **From 0.1.0 to a later 0.x release:** 0.1.0 is the first release; there is nothing to migrate
  from yet. Future releases record breaking changes and deprecations here, with the 0.1.0
  behaviour baseline in §2a.
- **From the pre-rewrite JUring API** (the older socket operations and primitives that were
  removed before the current library focus): that API was **never released**, so there is no
  supported migration path to document. If you have code against an old unreleased snapshot,
  port it to the current public API and treat it as a rewrite, not an upgrade.

## 5. Template for future entries

Copy this section for every MINOR/MAJOR release that changes user-visible behaviour. Keep
entries newest-first.

````markdown
## <version> — <release date>

### Breaking changes

#### <short title> (<type>#<member>)

Before (did not compile / behaved differently):

```java
// before
```

After:

```java
// after
```

- **Replacement:** <type>#<member> (or "none — capability removed")
- **Removal version:** <version> (the deprecation was announced in <version>)
- **Why:** <one or two sentences>
- **Migration:** <mechanical steps a user takes>

### Deprecations

#### <type>#<member> (deprecated in <version>, removal >= <version>)

- **Replacement:** <type>#<member>
- **Removal:** <version or "not announced yet">

### Additions

- <new public API> — <what it is for>
````

## 6. Reporting

If a release breaks something this file promised, report it at
[github.com/davidtos/JUring](https://github.com/davidtos/JUring) with the JUring version, the
affected type/member and a minimal reproduction. The correction is recorded in this file.
