# Backlog — deferred after 0.1.0

Items found during the 0.1.0 release-readiness audit (2026-11-16) that were deliberately **not**
fixed in the release. They are engineering backlog, not user-facing documentation.

## Correctness / robustness follow-ups

1. **Fixed before 0.1.0: stream close is hard-bounded.**
   `JuringInputStream.close()` / `JuringOutputStream.close()` no longer fall back to an
   unbounded `file.await(handle)`. The drain, the final truncate and the durability sync all
   share a 2 s + 250 ms grace deadline; a request that misses it is abandoned with its
   buffer kept alive (bounded leak, never a UAF), `ETIMEDOUT` is thrown, and the count is
   reported via `abandonedAtClose()`. `flush()` remains a deliberately unbounded durability
   point.
2. **CQ-overflow observability is partial.**
   `RingMetrics.cqOverflowEvents` reads the kernel `cq.koverflow`, which was observed to stay 0
   while completions were parked in the overflow list. `cqFlushEnters` now counts the flushes we
   issue, but the number of completions that actually overflowed is not observable. Add explicit
   own counters if this matters operationally.
3. **Completion-routing cost.** JuringFile routes a completion with a linear scan of its ledger
   (default capacity 1024) plus a scan of attached files. Store the slot index in `user_data` or
   use an open-addressing table if a profile shows it matters.
4. **`minComplete` semantics.** Values above the CQ size are now rejected with
   `IllegalArgumentException` (previously an unbounded hang). If callers genuinely need
   "wait for at least N total completions across calls", expose an explicit helper instead of
   overloading the CQ-wait primitive.
5. **Fixed before 0.1.0: no-completion waits throw.** `waitForResult`/`waitForResult(target)`
   and `waitForBatchResult*` with `minComplete > 0` now throw `IllegalStateException` when no
   completion can arrive: nothing is prepared and unreaped, or fewer submitted operations
   are outstanding than the wait requires (prepared SQEs are only published by `submit()`, so
   prepare-then-wait now fails fast instead of hanging).
6. **Non-Linux error paths outside the entry points.** `isSupported()` and the `JUring`
   constructor are gated before the native layer, but other public types (`FileDescriptor`,
   `IoShard`, `JUringBlocking`) still touch `NativeDispatcher.C` and would fail class init on a
   non-Linux JVM. Acceptable for a Linux-only library that documents "check isSupported() first";
   revisit if multi-platform probing is ever needed.
7. **Multishot timeouts.** `IORING_TIMEOUT_MULTISHOT` is rejected in 0.1.0 because one SQE can
   post multiple CQEs and the completion ledger is one-SQE/one-CQE. Implement a multi-completion
   ledger slot before enabling it (same modelling issue as provided buffers/multishot, B7).
8. **Raw-read user_data collision.** The raw `JUring.prepareRead(..., MemorySegment)` path still
   uses the buffer address in `user_data`, so two concurrent non-fixed reads into the same
   segment collide (B3 F1, fixed for `JuringFile` in B10). The limitation is documented on the
   `prepareRead` overloads, and `prepareReadUnique(...)` is an opt-in escape hatch (unique
   handle, but `Result.validBuffer()` is `NULL`). Removing the limitation from the default
   overloads needs unique op ids plus a side table (or tagged payload), which is why it is
   deferred.

## Portability / platform

6. **CI matrix.** Current tests are one-host evidence. Add aarch64, musl/Alpine, and old-kernel
   (5.15/6.1/6.8) jobs; skip only what the environment genuinely cannot run (e.g. O_DIRECT on
   overlayfs) and never blanket-disable suites.
7. **Feature probing beyond opcodes.** Probe and document `IORING_REGISTER_RING_FDS` support
   (currently best-effort at ring creation) and any other kernel-feature fallbacks, so old
   kernels fail or degrade with a precise message.
8. **Glossary of kernel/flag support** could be generated from the probe at runtime for
   diagnostics (`JUring.capabilities()`).

## API / ergonomics

9. **Error-model unification.** Mixed taxonomy: raw `JUring` throws unchecked
   `RuntimeException`/`IllegalStateException`, `JuringFile` throws checked `JuringException`,
   `JUringBlocking` returns negative errno. Decide a consistent story for 1.0 and document it.
10. **`Result` naming.** `Result.bytesRead()` returns the raw result even for writes; rename or
    add `transferSize()` in a minor release.
11. **Standard Java interfaces.** `JuringInputStream`/`JuringOutputStream` do not implement
    `java.io.InputStream`/`OutputStream`, and there is no `FileChannel`/`AsynchronousFileChannel`
    integration in-tree. The manticore-projects `FileChannel` fork is the adoption wedge; bring
    it in as a separate module (`juring-filechannel`) with H2 as reference consumer.
12. **API surface size.** 37 public types and a large overload matrix at 0.1.0 is hard to evolve;
    consider a smaller "stable core" (Ring + builder + completion + a FileChannel-like facade)
    before 1.0.

## Observability / operations

13. **Prometheus/HTTP metrics endpoint** and per-class I/O metrics (Seastar's `io_queue` class
    metrics) are not implemented; JMX + in-process snapshots only.
14. **Stall detector / watchdog** (Seastar has one) for shard loops that stop making progress.

## Release follow-ups

15. **Maven Central automation**: release workflow needs repository secrets; add a dry-run
    `-DskipPublishing` path and a release checklist test.
16. **Byte-reproducible benchmark aggregates** are generated by scripts under `bench/`; add a
    committed small fixture + CI check that the analyzer reproduces the published numbers to
    rounding.

## Known non-goals (documented decisions, not backlog)

- QoS / fair queuing / weighted shares (M4 closed): users partition work across rings/shards or
  use cgroup v2 / ionice.
- Futures/coroutines runtime, networking, and an application framework (D1: JUring is
  library-first; any Seastar-like framework is a separate project built on it).
- Provided buffers / multishot (B7 deferred by its measured gate).
