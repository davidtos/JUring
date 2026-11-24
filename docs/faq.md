# Frequently asked questions

Short answers, with a link to the document that has the detail. The numbers here are
copied from [README.md](../README.md) or [BENCHMARK.md](../BENCHMARK.md); platform
requirements come from [docs/platforms.md](platforms.md).

## What is JUring?

A Java library that drives Linux `io_uring` from pure Java through the Foreign Function
& Memory API. It has no JNI, no `liburing`, and no runtime dependencies beyond the JDK.
It provides a raw ring, a file handle, buffered streams, group commit, a blocking facade
for virtual threads, and a sharded ring runtime. Start with the
[README](../README.md) and [docs/api-stability.md](api-stability.md) for the API surface.

## When should I not use JUring?

When your I/O is not Linux file I/O, when you need a framework rather than a library
(futures/coroutines runtime, network stack, application model), or when you need QoS or
fair queuing. It also does not improve a single request's latency, and it cannot help in
an environment that blocks `io_uring`. See
[what it doesn't do](../README.md#what-it-doesnt-do) and
[docs/qos-decision.md](qos-decision.md).

## Which kernels are supported, and which features need which kernel?

The minimum is Linux 5.6, for the opcode probe. The recommended fast path targets Linux
6.1+: `SINGLE_ISSUER` needs 6.0+, `COOP_TASKRUN` needs 5.19+, and `DEFER_TASKRUN` needs
6.1+ and requires `SINGLE_ISSUER`. Optional opcodes are gated individually at runtime.
The full table is in [docs/platforms.md](platforms.md#kernel-feature-gates).

## Which CPU architectures are supported?

Linux on x86-64 (`amd64`) or aarch64, JDK 25 or newer. The syscall numbers and mmap
constants are Linux-specific. See the
[short version table](platforms.md#short-version).

## Does it work in containers, Docker, and Kubernetes?

Often not without a profile change. Docker's default seccomp profile denies the
`io_uring` syscalls, Kubernetes seccomp profiles such as `RuntimeDefault` may deny them,
Ubuntu 23.10+/24.04 AppArmor restricts unprivileged `io_uring` by default, and
`kernel.io_uring_disabled` can turn it off system-wide. Call `JUring.isSupported()` and
fall back to `java.nio` when it returns false. The details and the sysctls are in
[docs/platforms.md](platforms.md#containers-and-sandboxes) and
[SECURITY.md](../SECURITY.md).

## Why not `java.nio` or `AsynchronousFileChannel`?

`java.nio` is the portable fallback; JUring does not implement one for you (see the
fallback note in [docs/platforms.md](platforms.md#short-version)). `FileChannel` reads are
blocking, and `AsynchronousFileChannel` needs a bounded thread pool behind it, so both
trade threads for keeping the device busy. JUring keeps one thread issuing batches of
requests to `io_uring`, so throughput does not need a pile of carriers. See
[why the library exists](../README.md#why-i-built-it).

## Why not `liburing` or JNI?

Both would add a native build step and a native dependency to a library whose point is
pure Java. The Foreign Function & Memory API is enough to map the rings and call the
syscalls, so the JDK (25 or newer) is the only dependency. The project also refuses JNI,
`liburing`, and new native build requirements in contributions: see
[CONTRIBUTING.md](../CONTRIBUTING.md#before-you-start) and the
[README](../README.md).

## What does an FFM downcall cost?

In the JMH microbenchmark of invocation strategies, the form JUring uses (a
`critical(true)` handle called through a static final `invokeExact`) measured
**2.62 ± 0.01 ns/op**; the proxy-based forms ranged up to **8.64 ± 0.07 ns/op**. That is
a CPU-side cost per syscall, not an I/O latency number. The table and caveats are in
[BENCHMARK.md](../BENCHMARK.md#panama-downcall-overhead).

## Is the API stable before 1.0?

No, not fully. `0.1.0` is the first public release. While the major version is 0, SemVer
§4 applies: PATCH releases are compatible fixes and documentation only, and MINOR
releases may add API and, only if unavoidable, break it with an `UPGRADING.md` entry and
where feasible a deprecation cycle first. Only the exported package
`com.davidvlijmincx.lio.api` is covered; package-private types are internal. See
[docs/api-stability.md](api-stability.md#4-how-i-version-releases).

## Is JUring on Maven Central?

Not yet. Today the only way to use it is to build it from source; the coordinates for
when the release is published are in the [install section](../README.md#install). The
current status is recorded in
[docs/api-stability.md](api-stability.md#1-where-the-project-stands).

## Does it work on Windows or macOS?

No. JUring is Linux-only and file-I/O-only; `java.nio` is the fallback there. The
reasons are in [what it doesn't do](../README.md#what-it-doesnt-do) and
[docs/platforms.md](platforms.md#short-version).

## Can I use it for network I/O or sockets?

No. JUring covers file I/O: reads, writes, open/close, `fsync`/`fdatasync`, `fallocate`,
`ftruncate`, `statx`, timeouts, and cancellation. Networking is out of scope by design;
see [CONTRIBUTING.md](../CONTRIBUTING.md#before-you-start) and the
[README](../README.md#what-it-doesnt-do).

## Does it do QoS, fair queuing, or priorities?

No. There is no scheduler, no weighted shares, no rate limiter, and no cancel-on-drop.
Partition work across rings or shards, or use cgroup v2 `io.weight`/`io.max` and
`ionice`; the `IoPriority` hint is passed to the block layer but is not QoS. The
reasoning is in [docs/qos-decision.md](qos-decision.md).

## What about `io_uring`'s security history? Is it safe in a sandbox?

`io_uring` is a security-sensitive kernel interface with a history of vulnerabilities,
and many environments restrict it; that is why `JUring.isSupported()` exists. JUring is
not a sandbox for untrusted Java, and it cannot enforce QoS isolation. Do not expose it
to untrusted tenants without an OS-level sandbox, and grant native access to the JUring
module only where you can. See [SECURITY.md](../SECURITY.md) and
[docs/platforms.md](platforms.md#containers-and-sandboxes).

## How do I reproduce the benchmarks?

The harness lives under `bench/` and every table in [BENCHMARK.md](../BENCHMARK.md) is
rebuilt by scripts. It needs a dedicated NVMe and root-only host state (`perf`,
`drop_caches`, SMART), so the commands are not CI jobs. Start at
[BENCHMARK.md](../BENCHMARK.md#how-to-reproduce) and
[bench/README.md](../bench/README.md), with the protocol in
[docs/benchmark-methodology.md](benchmark-methodology.md).

## How do I run the tests? Can I build without `io_uring`?

A machine without `io_uring` can still build with `mvn -DskipTests package`. The test
suite needs `io_uring` enabled and the native-access flag, and CI runs it on JDK 25 and
26. The commands are in [CONTRIBUTING.md](../CONTRIBUTING.md#build-and-test), and the
native-access setup is in
[docs/platforms.md](platforms.md#java-native-access-configuration).

## Does it make a single slow read faster?

No. Async I/O buys queue depth, not a shorter round trip. At queue depth 1 all three
engines land around 11k IOPS; the gains appear when many requests are in flight. See the
queue-depth paragraph in the [README](../README.md#performance).

## How does JUring relate to virtual threads?

`JUringBlocking` lets ordinary blocking-style calls run from virtual threads: a small
number of platform poller threads submit and reap while callers park, so carriers stay
free for other work. It does not replace virtual threads; it keeps them from pinning
carriers on file I/O. See the
[virtual-thread section](../README.md#virtual-threads) and
[docs/vt-scale.md](vt-scale.md) for sizing guidance.
