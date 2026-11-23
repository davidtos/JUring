# Contributing to JUring

JUring is a Linux-only file-I/O library built on the Foreign Function & Memory API. I maintain
it alone, so the most useful thing you can send me is rarely a large patch. It's usually a small
report I can act on.

The contributions that move the project forward:

- correctness bugs, ideally with a reproducer,
- platform reports: kernel, architecture, libc and container setup,
- documentation fixes that make the supported-platform story clearer.

The scope is narrow on purpose, and the build stays pure Java.

## Before you start

Read [docs/platforms.md](docs/platforms.md) first. It lists the supported OS/arch/kernel/JDK
envelope and the container caveats. Many "it does not work" reports turn out to be environment
restrictions: Docker's default seccomp profile denies io_uring, AppArmor blocks it on newer
Ubuntu, or `kernel.io_uring_disabled` is set.

JUring is library-first. Futures/coroutines runtimes, networking, QoS schedulers and an
application framework are out of scope. FileChannel-style integration is planned as a separate
module, not as a change to the core API. The public API is `com.davidvlijmincx.lio.api` only;
everything else is internal and may change. I don't want JNI, liburing, or new native build
requirements.

## Build and test

You need Linux with io_uring enabled, JDK 25+, and Maven 3.9+.

```sh
mvn clean test                      # full JUnit suite (io_uring required)
mvn javadoc:javadoc                 # strict javadoc: doclint=all, warnings are errors
mvn -o test                         # offline, if dependencies are already cached
mvn -Pjcstress test -DskipTests     # optional jcstress memory-model run
```

The suite creates `O_DIRECT` files through JUnit's `@TempDir`. Some tests skip themselves when
the filesystem or kernel genuinely can't support an operation. Don't blanket-disable a test.
Guard it with an explicit assumption and say why.

The runnable examples are JUnit tests (`*ExampleTest`, see [docs/examples.md](docs/examples.md)):

```sh
mvn -o test -Dtest='*ExampleTest'
```

## Code guidelines

- Keep the hot path allocation-free: no per-operation boxing, no lambdas that capture state, no
  varargs, no `String` building in `prepare*`/reap loops. If you touch the `JUring`,
  `JuringFile` or `JUringBlocking` fast paths, add or extend an allocation test.
- Respect the ownership model. A `JUring` is single-owner-thread, and the shard and blocking
  runtimes own their rings on their shard threads. `WrongThreadException` is the intended
  outcome of misuse.
- The ring memory is `MemorySegment`-wrapped by hand. Any change to `mmap`/`munmap`, `Arena`
  lifetime or registered-buffer ownership needs a lifecycle test. The `*Lifetime*`, `*Ledger*`
  and `*UseAfterClose*` tests are the place to look.
- Javadoc is part of the build. Every public or protected member needs complete
  `@param`/`@return`/`@throws` docs, and `doclint=all` with `failOnWarnings` is enforced.
- Cross-thread reads are allowed only for the documented snapshots (`ringMetrics`, `isClosed`,
  shard/group stats). Everything else stays confined to its owner thread.
- Add a regression test for every bug. New tests belong next to the existing package-private
  tests, and they should fail without your fix. Check that by reverting the code path locally.

## Benchmarks

The harnesses live under `bench/`, and the write-ups are
[docs/benchmark-methodology.md](docs/benchmark-methodology.md) and [BENCHMARK.md](BENCHMARK.md).
They need a dedicated NVMe device and root-only host state (`perf`, `drop_caches`, SMART).
Please don't add CI jobs that pretend to measure performance.

Before you change anything under `bench/*.py`, run the analyzer regression tests:

```sh
python3 bench/analysis/selftest.py --unit
uvx ruff check bench                  # config: ruff.toml
```

## Pull requests

- Keep changes focused: one logical change per PR. If you change public API or behaviour,
  update `docs/UPGRADING.md` and `docs/api-stability.md`.
- Put the test evidence in the PR description: the `mvn clean test` result and count, the
  javadoc result, and the platform you used. If a test only runs on certain kernels or
  filesystems, say so.
- Don't rewrite published history or force-push shared branches. DCO isn't required today, but
  submitting a PR means your contribution is licensed under Apache-2.0.
- Security issues go through [SECURITY.md](SECURITY.md). Please don't open a public issue.

## Reporting bugs

Send me `uname -r`, your JDK version, distro/arch/libc, the filesystem and mount options, the
result of `JUring.isSupported()`, and whether you run in a container (plus its seccomp profile).
Include the smallest snippet that reproduces the problem and the complete exception or errno.

For hangs, a `jstack` of the blocked thread helps. So does whether the ring had more in-flight
requests than its CQ size.
