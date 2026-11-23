# What you can rely on in the JUring API

Here's the short version: if a type is in the exported package `com.davidvlijmincx.lio.api`, I
will tell you before I break it. If it isn't, I may change it at any time.

This page is the versioning policy for the `0.1.x` line. It's in effect from `0.1.0`, the
first public release. Compatibility questions go to
[github.com/davidtos/JUring](https://github.com/davidtos/JUring).

## 1. Where the project stands

The project version is `0.1.0` (`pom.xml`), the first public release. The `v0.1.0` tag and
the Maven Central publication are still pending, so today the only way to use the library is
to build it from source. The build command is `mvn -B -ntp test`, which CI
runs on JDK 25 and 26. There is no older release to migrate from;
[docs/UPGRADING.md](UPGRADING.md) records the 0.1.0 contract and future upgrade notes.

## 2. What counts as the public API

The Java module `JUring` exports one supported package: `com.davidvlijmincx.lio.api`. All
public and protected types in that package, and their public and protected members, are the
public API. That includes nested types such as `JuringFile.CancelStatus` and
`MetricsJmx.Handle`.

`0.1.0` has 39 public top-level types:

- Entry points and core values: `JUring`, `JUringBlocking`, `JuringFile`, `JuringInputStream`,
  `JuringOutputStream`, `FileDescriptor`, `Result`, `ResultConsumer`, `CompletionHandler`,
  `OperationType`, `IoPriority`.
- Options and policy enums: `IoUringOptions`, `SqeOptions`, `LinuxOpenOptions`,
  `InputStreamOptions`, `OutputStreamOptions`, `DurabilityMode`, `FadviseAdvice`, `IoPolicy`,
  `BlockingOptions`, `OverflowPolicy`, `ShardOptions`, `GroupCommitOptions`.
- Runtime, streams and durability: `IoShard`, `RingGroup`, `ShardContext`, `ShardTask`,
  `ShardStats`, `ShardMetrics`, `GroupMetrics`, `GroupCommit`, `GroupCommitStats`,
  `RingMetrics`, `LatencyHistogram`, `LatencySnapshot`.
- Metrics and JMX: `MetricsJmx`, `IoShardMetricsMXBean`, `RingGroupMetricsMXBean`.
- Errors: `JuringException`, a checked `IOException` subtype.

An earlier pre-release prototype package, `com.davidvlijmincx.lio.blackpanda`, was removed on
2026-11-16 to keep JUring strictly library-first.

The 2026-11-16 additions are additive: `IoPriority` and the ioprio members
(`JUring.defaultIoprio()`, `setDefaultIoprio(short)`, `setLastSqeIoprio(short)`,
`JuringFile.withIoPriority(short)`, `ioPriority()`) add API without changing a signature. One
behaviour change is a relaxation: blocking `JuringFile` helpers now accept sibling
`JuringFile`-based handles on the same ring.

0.1.0 is also additive at the signature level across the pre-release snapshots (16 no-option
`JUringBlocking` overloads, `JUring.isSupported()`, `RingMetrics.cqFlushEnters()`); its
behaviour changes are in `docs/UPGRADING.md` §2a.

The first public release additionally includes `BlockingOptions`, `OverflowPolicy`, the
`JUringBlocking(BlockingOptions, IoUringOptions...)` constructor, five `JUringBlocking`
accessors and `JuringException.EINTR`. These were developed for a later minor release that
was never published, and they ship in 0.1.0 instead; because 0.1.0 is the first public
release, no public compatibility promise is affected. Their documented behaviour changes
(interrupts, admission, `RingGroup` routing) are recorded in `docs/UPGRADING.md` §2a.

## 3. What is internal

Every package-private type and member, and everything the module does not export, has no
compatibility guarantee. That includes the ring plumbing and native-layout helpers: `Sqe`,
`Cqe`, `UserData`, `Iovec`, `IovecBlock`, `IovecBlockPool`, `ShardBody`, `ShardLoopContext`,
`MpscQueue`, `CompletionTable`, `ReadBufferPool`, `IoUringDispatcher`, `LibCDispatcher`,
`NativeDispatcher`, `io_sqring_offsets`, `io_cqring_offsets` and `io_uring_params`. These are
implementation detail and may change at any time.

## 4. How I version releases

JUring uses [Semantic Versioning](https://semver.org/) `MAJOR.MINOR.PATCH`, with the pre-1.0
adaptations below. Standard SemVer §4 applies while the major version is `0`.

| change | while `0.y.z` (pre-1.0) | after `1.0.0` |
|---|---|---|
| `PATCH` (`0.y.z` → `0.y.z+1`) | compatible fixes and documentation only: no API additions, removals or signature changes; no change to documented semantics | bug fixes and compatible corrections only |
| `MINOR` (`0.y` → `0.y+1`) | additions, plus breaking changes only if unavoidable, with an `UPGRADING.md` entry and, where feasible, a deprecation cycle first | new functionality, backward compatible |
| `MAJOR` | reserved for `1.0.0` (the API freeze) | breaking changes only here |

Additional rules:

- Additions are non-breaking: new types, methods, overloads and default-preserving option
  additions may appear in any MINOR release.
- Behavioural changes to documented semantics are MINOR changes even when every signature stays
  the same. If the docs promise a behaviour, changing it is not a PATCH.
- `1.0.0` freezes the `com.davidvlijmincx.lio.api` surface; after it, breaking changes to the
  exported API need a MAJOR release under standard SemVer.

## 5. Source and binary compatibility

For the exported API:

- Source compatibility within a MINOR line: code that compiles against a `0.y.z` public API
  compiles against a later `0.y.z'`, except for documented deprecations (kept at least one
  MINOR release, §6).
- Binary compatibility within a PATCH line: a JAR built against `0.y.z` runs against `0.y.z'`
  (same MINOR line) without recompilation.
- Documented behaviour doesn't change in a PATCH. Fixes may correct incorrect behaviour, but
  documented semantics and guarantees stay the same.
- Breaking changes need a MINOR (pre-1.0) or MAJOR (post-1.0) release and, pre-1.0, an
  `UPGRADING.md` entry and, where feasible, a deprecation cycle first.

The promise covers the exported API surface. It does not cover the internal types in §3 or the
items in §7.

## 6. Deprecation

- Deprecated exported API is marked `@Deprecated` and carries a Javadoc `@deprecated` paragraph
  naming the replacement and the removal release.
- It is kept for at least one MINOR release before removal.
- Every deprecation and removal is recorded in `docs/UPGRADING.md`.
- Announced removals happen in the announced MINOR (pre-1.0) or MAJOR (post-1.0) release.
  Removal is never silent.

The tree has one `@Deprecated` member; it predates this policy and is grandfathered:

- `JUring.prepareCancel(long)` → `JUring.prepareCancelByUserData(long)`. It announced no
  removal version because there was no policy when it was introduced. It is promised to remain
  at least until the first release plus one MINOR; from the first release onward, removing it
  requires a MINOR release with an `UPGRADING.md` entry.

No other `@Deprecated` member exists in `src/main`. 0.1.0 adds no deprecations.

## 7. What I don't promise

- Performance and benchmark numbers. Figures in `BENCHMARK.md` are measurements on one host at
  one time, not promises about future speed, latency or regressions; only documented semantics
  are covered.
- Internal implementation details: the package-private types in §3, class layouts, native-layout
  structures, thread names, log formats, internal buffer sizes and ring-layout choices may
  change at any time.
- JMX registration details beyond what is explicitly documented; see §9.
- Undocumented behaviour in general. Only what the Javadoc and these docs state is part of the
  contract.

## 8. Ownership and threading

Thread-safety and ownership rules are part of the documented contract. They are stated per type
in the Javadoc rather than repeated here, where they could drift:

- `JUring`: one ring is single-issuer and owned by one thread. Registered buffers and files,
  submission and reaping follow that ownership.
- `JuringFile`: not thread-safe, one owner thread per ring. Since 0.1.0, any number of
  `JuringFile`-based handles (files, streams, group commits) may share that thread's ring, with
  completions routed to the issuing handle. The blocking helpers reject only raw un-reaped
  `JUring.prepare*` work; reap it first or use the raw API.
- `IoShard` / `RingGroup`: one ring per shard thread, with documented cross-thread submission
  and shutdown rules.
- `JUringBlocking`: blocking calls from many threads, including virtual ones, with a documented
  poller/worker layout.

Read the type's Javadoc before sharing an object between threads. A violation surfaces as a
documented `IllegalStateException` where the type guards against it.

## 9. JMX

`IoShardMetricsMXBean` and `RingGroupMetricsMXBean` are exported API: their Java-visible
interfaces and documented attribute semantics follow this policy. `MetricsJmx` is exported API
too; its Javadoc explicitly documents the `ObjectName` scheme and registration semantics as
frozen, so that scheme is covered. JMX details that are not explicitly documented (which
`MBeanServer`, console presentation, attach/agent configuration) are not promised.

## 10. Reporting a compatibility problem

If a release breaks an exported-API source, binary or documented-behaviour promise, report it
at [github.com/davidtos/JUring](https://github.com/davidtos/JUring) with the version, the
affected type or member, and a minimal reproduction. The correction is recorded in
`docs/UPGRADING.md`.
