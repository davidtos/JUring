# JUring API stability and compatibility policy

**Status:** policy for the pre-1.0 release lines (`0.1.x`, and the unreleased `0.2.0`
line). JUring 0.1.0 is the first published release, so this policy is now in effect for
`0.1.0`; pre-1.0 SemVer rules in §3 apply.
Compatibility issues and questions are tracked at
[github.com/davidtos/JUring](https://github.com/davidtos/JUring).

This policy documents the public API surface, the SemVer rules, the source/binary
compatibility promise, the deprecation rules, and the things that are deliberately **not**
promised. It is the authoritative versioning document; `docs/UPGRADING.md` records concrete
changes per release and links back here.

## 1. Current status

| item | value | source of truth |
|---|---|---|
| project version | `0.1.0` | `pom.xml` (`<version>`) |
| published releases | **0.1.0** (first release; git tag `v0.1.0`) | GitHub releases / `git tag` |
| effect of this policy | in effect from 0.1.0 | this document |
| repository (report issues here) | [github.com/davidtos/JUring](https://github.com/davidtos/JUring) | `git remote -v` (`origin`) |
| build command (the project's CI command) | `mvn -B -ntp test` | `.github/workflows/ci.yml` (JDK 25/26) |

There is no older released version to migrate **from**; `docs/UPGRADING.md` records the 0.1.0
contract and migration-relevant changes for future releases.

## 2. What counts as the public API

JUring is a library-first Java module named `JUring`, which exports a single supported package
(`src/main/java/module-info.java`): `com.davidvlijmincx.lio.api`.

### Supported public API — `com.davidvlijmincx.lio.api`

**All public and protected types and their public/protected members in the exported package
`com.davidvlijmincx.lio.api`.** This includes types nested inside them (for example
`JuringFile.CancelStatus`, `MetricsJmx.Handle`). This surface is what the compatibility promise
in §4 covers.

In the current line (0.1.0 plus the unreleased 0.2.0 additions) this package has
**39 public top-level types**:

- **Entry points and core values** — `JUring`, `JUringBlocking`, `JuringFile`,
  `JuringInputStream`, `JuringOutputStream`, `FileDescriptor`, `Result`, `ResultConsumer`,
  `CompletionHandler`, `OperationType`, `IoPriority`.
- **Options and policy enums** — `IoUringOptions`, `SqeOptions`, `LinuxOpenOptions`,
  `InputStreamOptions`, `OutputStreamOptions`, `DurabilityMode`, `FadviseAdvice`, `IoPolicy`,
  `BlockingOptions`, `OverflowPolicy`, `ShardOptions`, `GroupCommitOptions`.
- **Runtime, streams and durability** — `IoShard`, `RingGroup`, `ShardContext`, `ShardTask`,
  `ShardStats`, `ShardMetrics`, `GroupMetrics`, `GroupCommit`, `GroupCommitStats`,
  `RingMetrics`, `LatencyHistogram`, `LatencySnapshot`.
- **Metrics / JMX** — `MetricsJmx`, `IoShardMetricsMXBean`, `RingGroupMetricsMXBean`.
- **Errors** — `JuringException` (a checked `IOException` subtype).

*(Note: an earlier pre-release prototype package, `com.davidvlijmincx.lio.blackpanda`, was
removed from the repository in 2026-11-16 to keep JUring strictly library-first.)*

The 2026-11-16 B10 additions are purely additive: `IoPriority` is new, and
`JUring.defaultIoprio()`/`setDefaultIoprio(short)`/`setLastSqeIoprio(short)` plus
`JuringFile.withIoPriority(short)`/`ioPriority()` add members without changing any existing
signature. The only behaviour change is a relaxation: blocking `JuringFile` helpers now
accept sibling `JuringFile`-based handles on the same ring (B10a).

0.1.0 is also purely additive at the signature level: it adds 16 no-option
`JUringBlocking` read/write/`*Fixed` overloads, `JUring.isSupported()`, and
`RingMetrics.cqFlushEnters()`. Behaviour changes (owner-thread enforcement, flag rejection,
buffer-pool misuse errors, CQ-overflow flushing) are recorded in `docs/UPGRADING.md` §2a.

The unreleased 0.2.0 additions are purely additive at the signature level: two new public
types (`BlockingOptions`, `OverflowPolicy`), a new
`JUringBlocking(BlockingOptions, IoUringOptions...)` constructor, five `JUringBlocking`
accessors and `JuringException.EINTR`. The documented behaviour changes are MINOR changes
under §3 — an interrupt during a parked `JUringBlocking` call now cancels the request and
returns `-EINTR` instead of being ignored, callers beyond capacity park for admission
instead of retrying in a timed loop, and a non-power-of-two `RingGroup` now routes across all
its workers — recorded in `docs/UPGRADING.md` §2b.

### Internal — no guarantee

Every package-private type and member, and everything that is not exported by the module,
carries **no** compatibility guarantee. This includes the ring plumbing and native-layout
helpers: `Sqe`, `Cqe`, `UserData`, `Iovec`, `IovecBlock`, `IovecBlockPool`, `ShardBody`,
`ShardLoopContext`, `MpscQueue`, `CompletionTable`, `ReadBufferPool`, `IoUringDispatcher`,
`LibCDispatcher`, `NativeDispatcher`, `io_sqring_offsets`, `io_cqring_offsets` and
`io_uring_params`. These are implementation detail and may change at any time.

## 3. Versioning rules (SemVer)

JUring uses [Semantic Versioning](https://semver.org/) `MAJOR.MINOR.PATCH` with the
pre-1.0 adaptations below (standard SemVer §4 applies while the major version is `0`).

| change | while `0.y.z` (pre-1.0) | after `1.0.0` |
|---|---|---|
| `PATCH` (`0.y.z` → `0.y.z+1`) | compatible fixes and documentation only: no API additions, removals or signature changes; no change to documented semantics | bug fixes and compatible corrections only |
| `MINOR` (`0.y` → `0.y+1`) | additions, plus — if unavoidable — breaking changes **only** with an entry in `docs/UPGRADING.md` and, where feasible, a deprecation cycle first | new functionality, backward compatible |
| `MAJOR` | **reserved for `1.0.0`** (the API freeze) | breaking changes only here |

Additional rules:

- **Additions are non-breaking.** New types, methods, overloads and default-preserving
  option additions are compatible and may appear in any MINOR release.
- **Behavioural changes to documented semantics are MINOR changes**, even when every
  signature stays the same: if the docs promise a behaviour, changing it is not a PATCH.
- **`1.0.0` freezes the `com.davidvlijmincx.lio.api` surface.** After `1.0.0`, breaking changes
  to the exported API are only allowed in a MAJOR release under standard SemVer.

## 4. Source and binary compatibility promise

For the exported API (`com.davidvlijmincx.lio.api`):

- **Source compatibility within a MINOR line:** code that compiles against a `0.y.z` public
  API compiles against a later `0.y.z'` release, **except** for documented deprecations
  (which remain for at least one MINOR release — see §5).
- **Binary compatibility within a PATCH line:** a JAR built against `0.y.z` continues to run
  against `0.y.z'` (same MINOR line, PATCH bump) without recompilation.
- **Documented behaviour does not change in a PATCH:** fixes may correct incorrect
  behaviour, but the documented semantics and guarantees of the API stay the same.
- **Breaking changes require a MINOR (pre-1.0) or MAJOR (post-1.0) release** and, pre-1.0,
  an `UPGRADING.md` entry and, where feasible, a deprecation cycle.

The promise applies to the **exported API surface**. It does not apply to the internal types
of §2 or to any of the items in §6.

## 5. Deprecation policy

- Deprecated exported API is marked with `@Deprecated` **and** carries a Javadoc
  `@deprecated` paragraph that names the replacement and the release in which the API may be
  removed.
- A deprecated API is kept for **at least one MINOR release** before removal.
- Every deprecation and every removal is recorded in `docs/UPGRADING.md`.
- A deprecated API that has been announced for removal may be removed in the announced
  MINOR release (pre-1.0) or MAJOR release (post-1.0); removal is never silent.

**Current deprecation inventory (baseline `b75515e`):** **B9 adds no deprecations.** The tree
contains **exactly one pre-policy deprecation**, which predates this policy and is
grandfathered:

- `JUring.prepareCancel(long)` at `src/main/java/com/davidvlijmincx/lio/api/JUring.java` is
  `@Deprecated`; its replacement is `JUring.prepareCancelByUserData(long)`. It announced no
  removal version (there was no policy when it was introduced), and it is promised to remain
  at least until the first release **plus one MINOR**. From the first release onward, any
  removal of it requires a MINOR release with an `UPGRADING.md` entry.

This is the complete inventory: no other `@Deprecated` exists in `src/main`.

## 6. What is not covered (explicit non-guarantees)

- **Performance and benchmark numbers are not part of the API contract.** The figures in
  `docs/internal/README.md` and `BENCHMARK.md` are measurements on one host at one time, not promises about
  future speed, latency or regression behaviour. A release may change performance; only
  documented semantics are covered.
- **Internal implementation details** — the package-private types of §2, class layouts, the
  native-layout structures, thread names, log formats, internal buffer sizes and ring-layout
  choices — may change at any time.
- **JMX registration details** beyond what is explicitly documented are not promised; see
  §8.
- **Undocumented behaviour in general** is not promised: only what the Javadoc and these docs
  state is part of the contract.

## 7. Ownership and threading

Thread-safety and ownership rules **are** part of the documented contract. They are stated
per type in the Javadoc rather than repeated (and potentially diverged from) here:

- [`JUring`](../src/main/java/com/davidvlijmincx/lio/api/JUring.java) — one ring is
  single-issuer and owned by one thread (registered buffers/files, submission and reaping
  follow that ownership).
- [`JuringFile`](../src/main/java/com/davidvlijmincx/lio/api/JuringFile.java) — not thread-safe,
  one owner thread per ring; since B10a any number of `JuringFile`-based handles (files,
  streams, group commits) may share that thread's ring, with completions routed to the
  issuing handle. Only raw un-reaped `JUring.prepare*` work on the ring is rejected by the
  blocking helpers (reap it first, or drive everything through the raw API).
- [`IoShard`](../src/main/java/com/davidvlijmincx/lio/api/IoShard.java) /
  [`RingGroup`](../src/main/java/com/davidvlijmincx/lio/api/RingGroup.java) — one ring per
  shard thread, with documented cross-thread submission/shutdown rules.
- [`JUringBlocking`](../src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java) —
  blocking calls from many (including virtual) threads, with a documented poller/worker
  layout.

Read the type's Javadoc before sharing an object between threads; a violation surfaces as a
documented `IllegalStateException` where the type guards against it.

## 8. JMX note

The MXBean interfaces [`IoShardMetricsMXBean`](../src/main/java/com/davidvlijmincx/lio/api/IoShardMetricsMXBean.java)
and [`RingGroupMetricsMXBean`](../src/main/java/com/davidvlijmincx/lio/api/RingGroupMetricsMXBean.java)
are part of the **exported API**: their Java-visible interfaces and documented attribute semantics follow this
policy. [`MetricsJmx`](../src/main/java/com/davidvlijmincx/lio/api/MetricsJmx.java) is also
the exported API, and its Javadoc **explicitly documents** the `ObjectName` scheme and registration
semantics as frozen; because they are explicitly documented, that documented scheme is
covered. JMX details that are **not** explicitly documented (for example which `MBeanServer`
an application uses, how a console presents the attributes, or attach/agent configuration)
are **not** part of the promise.

## 9. Reporting a compatibility problem

If a release breaks an exported-API source, binary or documented-behaviour promise of this policy,
report it at [github.com/davidtos/JUring](https://github.com/davidtos/JUring) with the JUring
version, the affected type/member, and a minimal reproduction. The fix or correction is
recorded in `docs/UPGRADING.md`.

## 10. How the claims in this document were verified

Checked against the current tree (0.1.0 plus the unreleased 0.2.0 additions, 2026-11-17):

| claim | verification |
|---|---|
| version `0.1.0` | `pom.xml`: `<version>0.1.0</version>` |
| first release `0.1.0` / tag `v0.1.0` | release workflow + `CHANGELOG.md`; tag is created by the maintainer during release |
| repository URL | `git remote -v` → `https://github.com/davidtos/JUring.git` |
| exported packages | `src/main/java/module-info.java` exports `com.davidvlijmincx.lio.api` |
| 39 public types in `lio.api` (37 at 0.1.0 + the 0.2.0 `BlockingOptions`/`OverflowPolicy`) | source inspection of top-level `public` type declarations |
| internal type list | source inspection of top-level package-private type declarations |
| exactly one `@Deprecated` | source grep of `src/main/java`: only `JUring.prepareCancel(long)` |
| no hosted CI | no `.github/` or other CI configuration in the repo (`docs/internal/b9-design.md` §7) |
