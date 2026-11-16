# JUring API stability and compatibility policy

**Status:** forward-looking policy for the **0.1-SNAPSHOT** development line. No JUring
release has been published yet, so this policy is the target the project is working to and
it **takes effect at the first published release**; before that, snapshot builds may change.
Compatibility issues and questions are tracked at
[github.com/davidtos/JUring](https://github.com/davidtos/JUring).

This policy documents the public API surface, the SemVer rules, the source/binary
compatibility promise, the deprecation rules, and the things that are deliberately **not**
promised. It is the authoritative versioning document; `docs/UPGRADING.md` records concrete
changes per release and links back here.

## 1. Current status

| item | value | source of truth |
|---|---|---|
| project version | `0.1-SNAPSHOT` | `pom.xml` (`<version>`) |
| published releases | **none** | `git tag` is empty; no release has been announced or published |
| effect of this policy | starts at the first published release | this document |
| repository (report issues here) | [github.com/davidtos/JUring](https://github.com/davidtos/JUring) | `git remote -v` (`origin`) |
| build command (the project's CI command) | `mvn -o test` | repo has no hosted CI; see `docs/b9-design.md` §7 |

Because no artifact has been released, there is nothing to migrate **from** yet
(`docs/UPGRADING.md` says the same). The policy is written now so that the first release
starts from a documented contract instead of an implicit one.

## 2. What counts as the public API

JUring is a library-first Java module named `JUring`, which exports a single supported package
(`src/main/java/module-info.java`): `com.davidvlijmincx.lio.api`.

### Supported public API — `com.davidvlijmincx.lio.api`

**All public and protected types and their public/protected members in the exported package
`com.davidvlijmincx.lio.api`.** This includes types nested inside them (for example
`JuringFile.CancelStatus`, `MetricsJmx.Handle`). This surface is what the compatibility promise
in §4 covers.

In the 0.1-SNAPSHOT line this package has **36 public top-level types**:

- **Entry points and core values** — `JUring`, `JUringBlocking`, `JuringFile`,
  `JuringInputStream`, `JuringOutputStream`, `FileDescriptor`, `Result`, `ResultConsumer`,
  `CompletionHandler`, `OperationType`.
- **Options and policy enums** — `IoUringOptions`, `SqeOptions`, `LinuxOpenOptions`,
  `InputStreamOptions`, `OutputStreamOptions`, `DurabilityMode`, `FadviseAdvice`, `IoPolicy`,
  `ShardOptions`, `GroupCommitOptions`.
- **Runtime, streams and durability** — `IoShard`, `RingGroup`, `ShardContext`, `ShardTask`,
  `ShardStats`, `ShardMetrics`, `GroupMetrics`, `GroupCommit`, `GroupCommitStats`,
  `RingMetrics`, `LatencyHistogram`, `LatencySnapshot`.
- **Metrics / JMX** — `MetricsJmx`, `IoShardMetricsMXBean`, `RingGroupMetricsMXBean`.
- **Errors** — `JuringException` (a checked `IOException` subtype).

*(Note: an earlier pre-release prototype package, `com.davidvlijmincx.lio.blackpanda`, was
removed from the repository in 2026-11-16 to keep JUring strictly library-first.)*

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
  `README.md` and `BENCHMARK.md` are measurements on one host at one time, not promises about
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
- [`JuringFile`](../src/main/java/com/davidvlijmincx/lio/api/JuringFile.java) — its blocking
  helpers require every in-flight operation on the ring to belong to that handle; use the
  raw ring API for sharing a ring between owners.
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

Checked against the tree at `b75515e` (0.1-SNAPSHOT, before B9 changes):

| claim | verification |
|---|---|
| version `0.1-SNAPSHOT` | `pom.xml`: `<version>0.1-SNAPSHOT</version>` |
| no releases / no tags | `git tag` prints nothing; no release artifacts in the repo |
| repository URL | `git remote -v` → `https://github.com/davidtos/JUring.git` |
| exported packages | `src/main/java/module-info.java` exports `com.davidvlijmincx.lio.api` |
| 36 public types in `lio.api` | source inspection of top-level `public` type declarations |
| internal type list | source inspection of top-level package-private type declarations |
| exactly one `@Deprecated` | source grep of `src/main/java`: only `JUring.prepareCancel(long)` |
| no hosted CI | no `.github/` or other CI configuration in the repo (`docs/b9-design.md` §7) |
