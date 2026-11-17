# `JUringBlocking` capacity and virtual-thread scale

`JUringBlocking` turns `io_uring` completions into parked (virtual) threads behind a fixed
set of poller workers. Each admitted blocking call owns one **pre-allocated call record**;
that record doubles as the wait node, so the facade allocates nothing per call on the caller
side — but it must reserve the capacity up front. This page documents the capacity model, how
to size it, the interrupt semantics, and the scale evidence. All byte figures are arithmetic
from `BlockingOptions.estimatedBytes()` and the record layout, not measurements.

## 1. Capacity model

Configuration is a `BlockingOptions` value passed to
`new JUringBlocking(BlockingOptions, IoUringOptions...)`. The two older constructors remain
shorthand for the same defaults plus a queue depth and/or poller count, so existing code is
unchanged. `BlockingOptions` is immutable (every `with*` returns a new instance) and
`BlockingOptions.defaults()` is the 0.1.0-compatible configuration. The options separate the
three quantities that the requested queue depth used to couple:

| `BlockingOptions` knob | default | what it controls |
|---|---|---|
| `withQueueDepth(int)` | `256` | `io_uring` submission-queue entries per worker ring (the hardware in-flight budget); the effective ring has at least 8 entries |
| `withPollers(int)` | CPU-derived, `1..4` (`1` when the queue depth is below 64) | number of worker rings and poller threads; an explicit value is rounded down to a power of two |
| `withMaxConcurrentCalls(int)` | `0` = derive from the queue depth (the 0.1.0 behaviour) | total blocking calls that may be admitted (parked or in flight) across all workers |
| `withOverflow(OverflowPolicy)` | `BLOCK` | what a call does when every record of its worker is already in use |
| `withPreParkSpins(int)` | `0` | bounded `Thread.onSpinWait()` iterations before a caller waiting for a completion parks |

The default poller count is derived from `Runtime.availableProcessors()` (one to four
workers), unless the `juring.blocking.pollers` system property is set (clamped to `1..16`
and rounded down to a power of two). `resolvedPollers()` reports the effective worker count.

**Call records.** Each worker owns a pre-allocated pool of call records created at
construction; the pool never grows. A record holds the 13 request/result fields, a free-list
entry and an MPSC queue entry, which is about **88 bytes** per record (including the
submission-queue entry). A record is taken by the call's admission step and released when the
kernel completion has been reaped, so the pool at once bounds and accounts for every admitted
call.

**Effective capacity.** With `maxConcurrentCalls == 0`, each worker gets
`nextPow2(max(256, queueDepth × 8))` records, which reproduces the 0.1.0 defaults. With an
explicit value, the requested total is split evenly over the workers and each share is
rounded up to a power of two with a minimum of 256 records per worker; the effective total is
`workers × recordsPerWorker` and can therefore exceed the request. Read the effective values
from `maxConcurrentCalls()` and `recordsPerWorker()` after construction (or the configuration
from `options()`). Because admission is per worker (the calling thread is bound to one
worker), an uneven thread-id distribution can saturate one worker's pool before the global
total is reached, so size the per-worker share, not only the sum.

**Memory.** `estimatedBytes()` returns
`workers × (recordsPerWorker × 88 + 4096)`, where the 4096 bytes are the rough per-worker
overhead of the queue/free-list array headers and the worker object. With the defaults
(queue depth 256, four derived workers) that is `4 × (2048 × 88 + 4096) = 720 KiB`. Caller
buffers, virtual-thread stacks and JVM overhead are **not** included.

**Admission and overflow.** The fast path pops a free record lock-free. When every record of
the caller's worker is in use, the call follows `OverflowPolicy`:

- `BLOCK` (default) parks on the worker's admission monitor (`Object.wait`) until a record is
  released, then proceeds. On a virtual thread that wait unmounts (JDK 25 does not pin the
  carrier), and the wait set is the JDK's own VM-managed queue, so there is no per-waiter
  library object and no timer.
- `FAIL` throws `IllegalStateException` immediately, which makes an undersized
  `maxConcurrentCalls` a visible configuration error instead of latency.

Diagnostics: `overflowWaits()` counts the calls that took the saturation path since
construction; `parkedCallers()` is a scanning snapshot of calls currently parked for their
completion (it does not count callers waiting for admission).

**Bounds.** `withQueueDepth` accepts `1..32768`, `withPollers` accepts `1..1024`, and
`withMaxConcurrentCalls` accepts `0` (derive) or `1..16,777,216` (`16 × 2^20`; about 1.5 GB
of call records at the limit; the upper guard exists to fail before an overflow rather than
to be a practical target). `withOverflow(null)`, negative spins and out-of-range values are
rejected.

## 2. Memory math

With four workers (the typical derived count when the JVM reports at least 16 available
processors, or with `juring.blocking.pollers=4`), an explicit `maxConcurrentCalls` and the
default `queueDepth`:

| `maxConcurrentCalls` | records per worker | effective total calls | estimated heap (records + worker overhead) |
|---|---|---|---|
| 1,000 | 256 | 1,024 | 104 KiB |
| 10,000 | 4,096 | 16,384 | 1.39 MiB |
| 100,000 | 32,768 | 131,072 | 11.0 MiB |
| 1,000,000 | 262,144 | 1,048,576 | 88.0 MiB (92.3 MB) |

The totals round up because each worker's share is rounded up to a power of two (never
down), so the effective total can exceed the request; for example
`withMaxConcurrentCalls(100)` on four workers yields 4 × 256 = 1,024 records. The same
capacity can be reached with one worker (one pool of 1,048,576 records is again about
88 MiB). For comparison, the unconfigured default is 720 KiB (8,192 records). The estimate
covers the library's own records only: for example, 10^6 concurrent 4 KiB caller buffers add
~4 GB of the caller's memory on top of it, and the virtual threads themselves also live on
the heap.

## 3. Sizing recipe

1. **Estimate concurrent blocked calls.** This is the peak number of calls that are inside
   `JUringBlocking` at once (parked for I/O or waiting for admission), not the number of
   threads that exist. A virtual-thread fan-out is a good proxy: 10^5 virtual threads all
   blocking on the facade need admission for 10^5 calls.
2. **Set `withPollers`.** One to four poller workers; two to four are the usual choice when
   the device should be kept busy. Each worker is one platform thread plus one ring, and each
   has its own record pool. On a small machine the CPU-derived default already picks 1–4.
3. **Set `withQueueDepth`.** Keep the default `256` unless a single worker needs a deeper
   submission pipeline. When `maxConcurrentCalls` is explicit, queue depth no longer affects
   call capacity; it only sizes the ring.
4. **Set `withMaxConcurrentCalls`.** Pass the peak from step 1 (a small margin is fine: the
   per-worker rounding already adds some). Call `estimatedBytes()` before constructing to
   check the heap cost, then read `maxConcurrentCalls()`/`recordsPerWorker()` back.
5. **Choose `withOverflow`.** Keep `BLOCK` if overload should degrade gracefully to waiting;
   choose `FAIL` to treat oversubscription as a bug and surface it as
   `IllegalStateException`. Watch `overflowWaits()` in either case.
6. **Consider `withPreParkSpins` only after measuring.** A short spin can avoid a
   park/unpark round trip for page-cache completions, but it keeps the caller (and its
   carrier) in `onSpinWait()` for that time; the default `0` is the safe choice for a large
   fan-out.

```java
BlockingOptions options = BlockingOptions.defaults()
        .withQueueDepth(256)
        .withPollers(4)
        .withMaxConcurrentCalls(1_000_000)
        .withOverflow(OverflowPolicy.BLOCK);

System.out.println(options.estimatedBytes()); // ≈ 88 MiB of call records

try (JUringBlocking io = new JUringBlocking(options)) {
    // read/write/*Fixed calls from any number of (virtual) threads
}
```

The capacity is fixed per instance; changing it means constructing a new `JUringBlocking`
(close the old one first). Multiple instances each own their own pools, so the estimates add
up.

## 4. Why pre-sizing matters

- **The wait node is the record.** Because the call record doubles as the park node, the
  facade can keep the caller-side hot path allocation-free (no `Future`, task or waiter object
  per call). The trade is that the memory must exist before the call, so capacity is a
  construction-time decision.
- **Undersizing is a latency or error decision, never a thread explosion.** Calls beyond
  capacity wait for admission (`BLOCK`) or fail (`FAIL`); they never allocate a record and
  never spawn a platform thread. A budget that is too small therefore shows up as
  `overflowWaits()` growth and latency under load, or as immediate `IllegalStateException`s
  with `FAIL`.
- **Oversizing competes with the workload's own memory.** 88 bytes per record is small
  individually, but at 10^6 calls it is ~88 MiB of heap before a single I/O buffer is
  allocated; that heap cannot hold the caller's buffers or page-cache-friendly data.
- **The default is a pipeline budget, not a fan-out budget.** The 0.1.0-derived default
  (~0.7 MiB on four workers) is sized for the queue-depth-driven use of 0.1.0. A workload
  that parks 10^5–10^6 virtual threads at once should say so explicitly with
  `withMaxConcurrentCalls`.

## 5. Interrupt semantics

The blocking calls return the raw completion result (bytes transferred, a file descriptor,
`0`, or a negative errno); they do not throw `JuringException` for an I/O result. A caller
that is interrupted while parked for its completion behaves as follows:

- The call requests an `IORING_OP_ASYNC_CANCEL` for its own request and waits for the kernel
  completion (the cancellation or the operation's real completion) to be reaped. Only then
  does the method return, so the caller's buffer is not touched after it returns.
- The method returns `-JuringException.EINTR` (`-4`) and preserves the thread's interrupt
  flag.
- A request that was still queued behind the ring's in-flight cap is cancelled as soon as it
  reaches the ring: the cancellation is deferred, not lost. The caller still waits for the
  terminal completion before it returns, for the same buffer-safety reason.
- A call interrupted while waiting for admission (before anything was submitted) also
  returns `-EINTR`, without issuing I/O.
- An interrupted write may already have taken effect before the cancellation was processed:
  the result reports the interrupt, not the absence of a side effect. Callers that treat
  `-EINTR` as "not executed" should re-check their own state.

```java
long result = io.read(fd, buffer, length, offset);
if (result == -JuringException.EINTR) {
    // the interrupt flag is preserved; the request was cancelled (or raced its completion)
}
```

Before 0.2.0 an interrupt during a parked call was ignored and the call kept waiting for its
completion. That change is a documented MINOR behaviour change; see
[`docs/UPGRADING.md`](UPGRADING.md) §2b and [CHANGELOG.md](../CHANGELOG.md).

## 6. Scale evidence

The capacity model is exercised by the committed scale test
[`JUringBlockingScaleTest`](../src/test/java/com/davidvlijmincx/lio/api/JUringBlockingScaleTest.java):

| scale | how it runs | status |
|---|---|---|
| 10,000 virtual threads | the normal test suite (`mvn -o test`); `@Timeout(600)` | committed |
| 1,000,000 virtual threads | [`bench/blocking/run-scale.sh`](../bench/blocking/run-scale.sh), which adds `-Djuring.scale.vt=1000000` and `-Xmx6g` to the forked test JVM | opt-in |

The test starts the virtual threads behind a latch, each performs an 8-byte read through the
same `JUringBlocking` with an explicitly sized record pool (sized so the admission path is
exercised), and asserts that every call completes with 8 bytes, that the admission/overflow
path was exercised (`overflowWaits() > 0`), and that every record was released
(`parkedCallers() == 0`). It is correctness and bounded-resource evidence at scale, not a
throughput benchmark: it prints elapsed time and the configuration it used (including
`BlockingOptions.estimatedBytes()`) as diagnostics, and the documentation does not turn those
into a performance claim. The script prints the model memory estimate for a budget that
admits every virtual thread at once, and the test prints the estimate for the budget it
actually uses, so the heap requirement is visible up front.

Diagnostic runs on the development host (AMD Ryzen 9 3950X, 62 GiB, Linux, OpenJDK 25.0.3;
`bench/blocking/run-scale.sh`, 2026-11-17) — reported as evidence that the run completes, not
as a throughput claim:

| virtual threads | pollers | record budget | `estimatedBytes()` | elapsed |
|---|---|---|---|---|
| 10,000 | 4 | 1,024 records (deliberately small; admission path exercised) | 106,496 B | ~0.2–0.4 s |
| 1,000,000 | 4 | 1,024 records (opt-in run, admission path) | 106,496 B | ~17 s |
| 1,000,000 | 1 | 1,024 records (single ring, admission path) | 94,208 B | ~18 s |
| 1,000,000 | 1 | 1,048,576 records (single ring, every caller admitted directly) | 92,278,784 B (~88 MiB) | ~16 s |
| 500,000 | 4 | 524,288 records | 46,153,728 B (~44 MiB) | ~9 s |
| 1,500,000 | 1 | 1,048,576 records (451,424 threads above the budget) | 92,278,784 B (~88 MiB) | ~23 s |

All runs completed every call and released every record (`parkedCallers() == 0`). The
1,000,000-thread runs used a 6–8 GiB test-JVM heap (`-Xmx6g`/`-Xmx8g`) for the virtual threads
and their buffers. The small-pool runs kept a 1,024-record pool (94–106 KiB of library
metadata) and served the remaining callers through the admission monitor; the full-pool
single-ring run pre-allocated 1,048,576 records (~88 MiB) and needed no admission wait
(`overflowWaits() == 0`). The numbers are wall-clock diagnostics from `JUringBlockingScaleTest`
(8-byte page-cached reads, each virtual thread does one read), not a throughput claim — the
test's job is to show that the fan-out completes without spinning or leaking records. A single
ring is a correct configuration at this scale, but its one poller thread and `sqEntries − 1`
in-flight budget bound real I/O throughput; use two to four pollers when the device should be
kept busy (`withPollers`/`RingGroup`).

The 1,500,000-thread run is worth reading carefully: its budget (1,048,576) is below the
thread count, yet `overflowWaits()` was 0. The budget bounds **concurrently admitted** calls,
not the number of threads: with these fast 8-byte page-cached reads the pool recycled records
faster than the next virtual thread arrived, so no call actually had to wait for admission.
With slower I/O (or a tighter arrival burst) the ~451,424 threads above the budget park on the
per-worker admission monitor until a record is freed — parked, not spinning, and reported by
`overflowWaits()`. `parkedCallers()` reports calls parked for their own completion (admitted
records in `PARKED`/`SUBMITTED`); it does not count callers waiting for admission.

## See also

- [`JUringBlocking`](../src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java) and
  [`BlockingOptions`](../src/main/java/com/davidvlijmincx/lio/api/BlockingOptions.java)
  Javadoc for the per-member contract.
- [`docs/examples.md`](examples.md) E4 for the eight-virtual-thread example.
- [`BENCHMARK.md`](../BENCHMARK.md) Part II §3 for the historical JMH virtual-thread data
  (unchanged by the 0.2.0 capacity work) and the caveat that it is not a 0.2.0 ranking.
- [CHANGELOG.md](../CHANGELOG.md) for the 0.2.0 entries and
  [`docs/UPGRADING.md`](UPGRADING.md) §2b for the migration notes.
