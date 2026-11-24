# Design notes

This is the document I'd want to read if I inherited this codebase. It isn't an API
reference. It's the decisions, the dead ends, and the places where the obvious answer
turned out to be wrong. If you only read one section, read the one about lifetimes;
that's where most of the complexity lives.

## Why io_uring, and why the FFM API

Regular files aren't pollable. There is no `epoll` for a file on disk, so a thread
that calls `read(2)` on a local file stays in the kernel until the device answers.
That's why virtual threads don't help with file I/O: the virtual thread is mounted on
its carrier for the duration of the syscall, and Loom compensates by adding carriers.

`io_uring` is the kernel's answer. It gives user space two shared queues, one for
submissions and one for completions. You prepare requests into the submission queue,
ring the kernel's doorbell with one `io_uring_enter`, and reap results from the
completion queue in batches. One thread can keep hundreds of operations in flight.

The catch is that `io_uring` is a memory-mapped native interface. Before the FFM API,
the options were JNI bindings, a `liburing` wrapper, or both. The FFM API changes the
math: `MemorySegment` gives Java a safe view over mapped memory, `VarHandle` gives it
well-defined access modes, and `Linker` gives it downcalls to the syscalls. That's
enough to drive the ring directly. There's no per-operation JNI transition, because
there's no JNI at all.

## The ring is shared memory with the kernel

`io_uring_setup` doesn't hand you a struct. It hands you offsets into a mapping. Three
`mmap` calls later you have the submission ring, the completion ring, and the array of
submission entries. JUring maps all of it, then reads and writes the ring fields
through `VarHandle` accesses.

The important consequence is that the JVM and the kernel are reading and writing the
same cache lines. When you fill a submission entry and advance the submission tail,
the kernel has to see the entry before it sees the new tail. When the kernel writes a
completion and advances the completion tail, you have to read the tail before the
entry. Get the ordering wrong and you get missing or corrupt completions that only
show up under load.

I use full fences at the publish and consume points, and I run the wake path under
jcstress. It's the one part of this project where "it worked on my machine" is
completely meaningless.

## The setup flags are the performance story

The fast path uses three flags, and each one removes a specific cost:

- `IORING_SETUP_SINGLE_ISSUER` (Linux 6.0+) tells the kernel that one thread submits
  to the ring. The kernel can skip the locking it otherwise needs.
- `IORING_SETUP_COOP_TASKRUN` (Linux 5.19+) makes completion task work run on your
  thread's syscall instead of interrupting it.
- `IORING_SETUP_DEFER_TASKRUN` (Linux 6.1+, requires `SINGLE_ISSUER`) defers task
  work further, to `io_uring_enter`, which cuts IPIs on the completion path.

That's why the recommended fast path targets Linux 6.1+. On older kernels JUring
still works with fewer flags, but the numbers will be different.

The other half is registration. Registered files let each operation refer to a file
by index (`IOSQE_FIXED_FILE`) instead of doing a descriptor lookup. Fixed buffers pin
the buffer set in the kernel once, so a read refers to a buffer index instead of
mapping user memory per operation. Both remove per-request work, and both have limits:
registered buffers need `RLIMIT_MEMLOCK` headroom, and the pool is fixed size.

`SQPOLL` is supported on normal rings through the `sqThreadIdleMs` constructor. It's
not the default, and on the shared-worker ring it can't be combined with `ATTACH_WQ`.

Some flags I reject instead of passing them to the kernel. `IOPOLL`, `SQ_AFF`,
`CQSIZE`, `NO_MMAP`, `REGISTERED_FD_ONLY`, and `R_DISABLED` all throw
`IllegalArgumentException` because JUring can't honour them yet. Passing a config the
kernel accepts but the library can't drive is worse than failing at construction.

## Lifetimes are the hard part

Every submission needs a completion. If you lose track of one, you leak a request
slot. If you lose track of a buffer, you read into memory someone else is using.

The raw ring handles this with a `user_data` id per request. The completion carries
the same id back, and `Result` wraps the completion. Closing a `Result` releases the
native resources attached to it. That sounds simple until you have four thousand
requests in flight and a close() racing with the reaper.

Fixed buffers get a ledger. You check a buffer out before submitting, and check it
back in when the completion is reaped. The buffer pool never grows, so the capacity
is a hard bound, not a suggestion. If you reuse the same `MemorySegment` for
concurrent in-flight reads, `prepareReadUnique` gives each request its own handle
instead of letting two requests share one.

The streams manage their own buffers. A `JuringInputStream` allocates
`(readAhead + 1)` aligned buffers when it opens, and a `JuringOutputStream` allocates
`(writeBehind + 1)`. The memory is bounded by construction, and both report their
footprint. When a stream closes hard, `abandonedAtClose()` tells you how many
operations were abandoned rather than silently pretending everything drained.

Close is bounded. `JuringFile` and the streams cancel in-flight work and wait for
completions with a deadline (2 seconds by default, plus a 250 ms grace window). They
never block forever. Every cancelled request still gets a completion, which sounds
obvious until you watch what happens when a cancellation and a timeout complete at
the same time.

Timeouts are opcodes, not timers in user space. Standalone timeouts and linked
timeouts are supported. `IORING_TIMEOUT_MULTISHOT` is rejected: a multishot timeout
can produce multiple completion entries for one submission, and the accounting gets
subtle enough that I didn't want it in a pre-1.0 library.

## The virtual-thread facade

`JUringBlocking` exists because "use an async API" is not always a reasonable ask.
Callers write blocking calls; the facade makes them cheap.

The design is a poller thread per worker ring. The caller submits a request and parks
on its pre-allocated call record with `LockSupport.park`. The poller submits to the ring, reaps completions, and unparks the
caller whose request completed. The virtual thread unmounts while parked, so no
carrier is pinned. The platform-thread count is set by the poller configuration, not
by the number of virtual threads calling in.

Each concurrent call owns a pre-allocated record of about 88 bytes. The record holds
the request fields, the result, and the wait node, so a call doesn't allocate. The
pool is created at construction, which means capacity — not allocation — bounds
concurrency. Explicit `BlockingOptions` and an overflow policy (`OverflowPolicy.BLOCK`
or `FAIL`) let you size it and choose what happens when it is full; the defaults keep
the queue-depth-derived capacity. That is what makes the facade advisable for very
large fan-outs.

Interrupts are handled properly in 0.1.0: interrupting a parked call cancels the
request in the kernel with `IORING_OP_ASYNC_CANCEL`, then returns `-EINTR` once the
completion has been reaped. A cancelled write may already have taken effect, which is
documented rather than hidden.

## One ring, many handles

Since 0.1.0, several `JuringFile`-based handles can share one `JUring` on its owner
thread: files, streams, and group commit. Each handle gets its own completion ledger,
and every completion is routed to the handle that issued it. That's what makes the
single-reactor pattern usable: one ring per thread, many file objects multiplexed on
it.

The safety rule still holds. Raw un-reaped `JUring.prepare*` work on the same ring
makes the blocking helpers throw `IllegalStateException`, because those completions
can't be attributed to a handle. Mixing the two styles is allowed only if you reap
the raw work yourself.

The `IoPriority` hint is additive and deliberately small: a ring default, a raw
per-SQE setter, and a per-file setting inherited by streams and group commit. It goes
into the block layer's `ioprio` field. It is not QoS. `bfq` and `mq-deadline` act on
it; `none` and `kyber` ignore it.

## What I didn't build, and why

**Provided-buffer rings and multishot.** I tested provided-buffer rings against the
existing fixed-buffer path: at 4 KiB random reads with 64 in flight they cost about
11% more CPU cycles per operation, and at 1 MiB sequential reads they were neutral.
Multishot doesn't apply to regular files; the kernel rejects it. And Seastar doesn't
use either feature in its file path, so the "everyone else has it" argument didn't
survive a source audit. I left both out.

**QoS and fair queuing.** In Seastar, QoS lives in the reactor's scheduler, not in
`io_uring`. Building that inside JUring would turn the library into a worse Seastar
and pull it away from what it's good at. The answer for now is to partition work
across rings or shards, or to use cgroup v2 `io.weight` and `ionice`. The library
gives you the I/O engine; you bring the scheduler.

**Network I/O and an application framework.** Sockets are a different set of opcodes
and a different set of problems. Futures, coroutines, and an actor model are a
separate project. JUring stays a library.

**Windows and macOS.** The syscall numbers and `mmap` constants are Linux-specific.
`java.nio` is the fallback on other platforms.

## How I benchmark

I don't trust a benchmark I can't reproduce, and I especially don't trust one I ran
once on a machine that was doing something else. The rules are:

- One fresh process per repetition. The JVM's JIT state is part of the measurement.
- Warm up, then measure. Ten seconds of warmup, thirty seconds measured for the read
  runs; longer for latency.
- Interleave the engines inside each repetition, so a noisy neighbor or a thermal
  step hits every engine.
- Compare paired ratios, not just means. The absolute numbers move with host state;
  the ratio cancels most of that.
- Fix the protocol before running it, because I knew I'd be tempted to explain away a
  bad run.
- Don't trim repetitions. If a run is bad, it stays in the data and is reported.

The write path is the honest failure. The consumer NVMe in this machine has an SLC
cache that folds into TLC under sustained writes, and sustained throughput moves
between roughly 40k and 195k IOPS depending on where the drive is in that cycle. I
ran a state-controlled benchmark and still couldn't get enough homogeneous
passes to rank the engines fairly. So [BENCHMARK.md](../BENCHMARK.md) says the
write path is inconclusive. It would have been easy to publish the good run.

The `fio` comparison has a known asymmetry: `fio` can't set JUring's ring flags or
fuse submits. I added a batching-matched reference specifically to see how much that
mattered, and it wasn't faster than the default reference. That's a useful negative
result: the remaining gap is the ring flags, not a strawman.

The virtual-thread numbers are older JMH runs. At Loom's default carrier pool,
`FileChannel` already reaches the device and the two are at parity. The interesting
number is under a fixed platform-thread budget, where JUring does the same device
throughput with two carriers and two pollers instead of four carriers. The 1–2, 8 and 16-thread intervals overlap the 4-thread mean, and the run predates
0.1.0, so I present it as thread efficiency, not a ranking.

## If you're building something similar

Start with completion accounting, not throughput. The ring is easy to get working
once; it's hard to keep correct when close, cancel, timeout, and interrupt all race
with the reaper. Write the lifecycle rules down first, then make the fast path fast.
And benchmark the boring path too, because the cache-friendly benchmark is the one
that will lie to you.

The rest of the public docs are in [docs/](.) and [BENCHMARK.md](../BENCHMARK.md). The
API promises are in [api-stability.md](api-stability.md), the platform envelope is in
[platforms.md](platforms.md), and the virtual-thread capacity model is in
[vt-scale.md](vt-scale.md).
