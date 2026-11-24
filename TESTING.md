# Testing JUring

The correctness story matters more than the throughput story: a fast I/O library that
corrupts one completion is worse than a slow one.

## The suite

The suite has 489 tests across 67 test classes. It covers:

- conformance against `java.nio`, including `JuringFile` and the stream APIs;
- lifecycle and use-after-close;
- cancellation and timeouts;
- registered-file and registered-buffer lifetimes;
- bounded stream memory and buffer accounting;
- zero allocation on the hot paths;
- per-handle completion routing when several handles share one ring.

## Concurrency and the wake path

A separate jcstress profile models the store/load orderings the wake path relies on:
publish/consume fences and slot handoff. It drives the real package-private
`MpscQueue` through concurrent offers and a single consumer.

The live poller/park wake path is covered by concurrency integration tests, not
jcstress:

- `HandoffTest` wakes an idle shard through its inbound queue.
- `BlockingVtConcurrencyTest` runs 128 virtual threads through concurrent
  `JUringBlocking` calls.
- `JUringBlockingScaleTest` runs 10,000 virtual threads by default; a
  1,000,000-thread run is opt-in via [bench/blocking/run-scale.sh](bench/blocking/run-scale.sh).

These exercise park/unpark against the pollers and assert that every call completes
and all records are released. They are correctness tests, not throughput benchmarks.
The admission saturation path has its own deterministic eventfd test in
`JUringBlockingAdmissionTest`.

## CI

CI builds and runs the suite on JDK 25 and 26, checks Javadoc with doclint, and verifies
the release artifacts.

## Running the tests

```sh
mvn test
```

The jcstress profile runs as part of the test phase:

```sh
mvn -Pjcstress test
```

The scale test is part of the normal suite at 10,000 virtual threads. The
1,000,000-thread run is opt-in:

```sh
bench/blocking/run-scale.sh
```

## What this does not prove

- There is no power-loss test. `GroupCommit` has a fresh-process restart check, not a
  crash-consistency test.
- The tests run on Linux with `io_uring` enabled; a container whose seccomp profile
  blocks `io_uring` cannot run them.
- The benchmark harness is separate. It is documented in [BENCHMARK.md](BENCHMARK.md)
  and the [bench README](bench/README.md).
