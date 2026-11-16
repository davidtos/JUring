# Contributing to JUring

Thanks for taking a look. JUring is a Linux-focused, FFM-based file-I/O library; the most
useful contributions are correctness reports, small reproducible probes, platform reports
(kernel/arch/libc/container), and documentation fixes that make the supported envelope clearer.

## Before you start

- Read [docs/platforms.md](docs/platforms.md) for the supported OS/arch/kernel/JDK envelope and
  the container/seccomp caveats. Many "it does not work" reports are environment restrictions
  (`io_uring` denied by Docker seccomp, AppArmor, or `kernel.io_uring_disabled`).
- JUring is **library-first**: futures/coroutines runtimes, networking, QoS schedulers and an
  application framework are out of scope (see [docs/m4-decision.md](docs/m4-decision.md) and
  `docs/internal/`). FileChannel-style integration is planned as a separate module, not as a
  change to the core API.
- The public API is `com.davidvlijmincx.lio.api` only. Everything else is internal and may
  change; do not add JNI, `liburing`, or new native build requirements.

## Build and test

Requirements: Linux with io_uring enabled, JDK 25+ (Maven), Maven 3.9+.

```sh
mvn clean test                      # full JUnit suite (io_uring required)
mvn javadoc:javadoc                 # strict javadoc: doclint=all, warnings are errors
mvn -o test                         # offline, if dependencies are already cached
mvn -Pjcstress test -DskipTests     # optional jcstress memory-model run
```

The suite creates `O_DIRECT` files via JUnit `@TempDir`; some tests self-skip when the
filesystem or kernel genuinely cannot support the operation. Do not blanket-disable a test —
guard it with an explicit assumption and explain why.

Runnable examples are JUnit tests (`*ExampleTest`, see [docs/examples.md](docs/examples.md)):

```sh
mvn -o test -Dtest='*ExampleTest'
```

## Code guidelines

- Keep the hot path allocation-free: no per-operation boxing, lambdas capturing state, varargs,
  or `String` building in `prepare*`/reap loops. Add or extend an allocation test when touching
  `JUring`, `JuringFile` or `JUringBlocking` fast paths.
- Respect the ownership model: a `JUring` is single-owner-thread; shard/blocking runtimes own
  their rings on their shard threads. `WrongThreadException` is the intended outcome of misuse.
- Memory-mapped ring memory is `MemorySegment`-wrapped manually; any `mmap`/`munmap`,
  `Arena` lifetime, or registered-buffer ownership change needs a lifecycle test (see the
  `*Lifetime*`, `*Ledger*`, `*UseAfterClose*` tests).
- Javadoc is part of the build: every public/protected member needs complete `@param`/`@return`
  /`@throws` docs; `doclint=all` + `failOnWarnings` is enforced.
- Cross-thread reads are allowed only for documented snapshots (`ringMetrics`, `isClosed`,
  shard/group stats). Everything else must stay owner-thread confined.
- Add regression tests for bugs. New tests belong next to the existing package-private tests and
  should fail without the fix (verify by reverting the code path mentally or locally).

## Benchmarks

Benchmark harnesses live under `bench/` and are documented in
[docs/benchmark-methodology.md](docs/benchmark-methodology.md) and [BENCHMARK.md](BENCHMARK.md).
They rely on a dedicated NVMe device and root-only host state (`perf`, `drop_caches`, SMART);
do not add CI jobs that pretend to measure performance. Process/audit documents live under
`docs/internal/` and are not user-facing documentation.

## Pull requests

- Keep changes focused; one logical change per PR. Update `docs/UPGRADING.md` and
  `docs/api-stability.md` for public API or behaviour changes.
- Include the test evidence in the PR description (`mvn clean test` count, javadoc result,
  platform used). If a test only runs on specific kernels/filesystems, say so.
- Do not rewrite published history or force-push shared branches. DCO is not required today,
  but by submitting a PR you agree your contribution is licensed under Apache-2.0.
- Security issues: see [SECURITY.md](SECURITY.md) — do not open a public issue.

## Reporting bugs

Include: kernel version (`uname -r`), JDK version, distro/arch/libc, filesystem + mount options,
`JUring.isSupported()`, whether you run in a container (and its seccomp profile), the smallest
reproducible snippet, and the complete exception/errno. For hangs, a `jstack` of the blocked
thread and whether the ring had more in-flight requests than its CQ size is useful.
