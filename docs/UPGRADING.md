# Upgrading JUring

This file records every user-visible API change per release, so that an upgrade is a
deliberate act with a before/after recipe. The versioning rules behind it are defined in
[`docs/api-stability.md`](api-stability.md) — this file is the changelog those rules refer
to.

## Status

| item | value |
|---|---|
| project version | `0.1-SNAPSHOT` |
| released versions | **none** — no JUring release has been published (`git tag` is empty) |
| current deprecations added by B9 | **none** |
| pre-existing deprecations (pre-policy) | **one**, see [§3](#3-deprecations) |
| compatibility policy | takes effect at the first published release; see [api-stability.md](api-stability.md) |

**There is no released version yet, so there is nothing to migrate from.** This file exists
so that the first release starts with a real upgrade history instead of a blank page;
entries below describe what the 0.1-SNAPSHOT line has accumulated, not steps you must take.

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

The 0.1-SNAPSHOT line grew additively. Each capability step below added surface and none
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

For the design/evidence record of each step see `docs/b1-report.md`, `docs/b2-report.md`,
`docs/b3-design.md`, `docs/b4-report.md`, `docs/b5-report.md` and `docs/b6-report.md`.

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

No public API has been removed from the 0.1-SNAPSHOT line so far.

## 4. Migration notes

- **From a previous JUring release:** not possible — there is no released version yet. The
  first release will be the first version anyone can migrate from, and changes after it will
  be recorded here.
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
