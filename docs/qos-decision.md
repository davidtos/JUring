# What this library does not do: QoS

Sooner or later someone asks for priorities. Can class A get more of the disk than class B?
My answer is that JUring does not do that, and I don't think it should. There is no QoS, no
fair queuing, no weighted-share scheduler, no rate limiter, and no intent or cancel-on-drop
layer.

That is not a feature I forgot to finish. It is the library boundary.

## Why not

QoS in Seastar is not an io_uring capability. It lives in the reactor layer: scheduling groups
with shares and classes, fair-queue token-bucket accounting, starvation accounting, per-class
metrics. io_uring gives you a queue and completions; it does not decide whose I/O goes first.

Building that inside JUring would mean per-class in-flight accounting, a weighted dispatch
policy composed with the shard runtime's round-robin/hash distribution and batched poll loop,
tail-latency guards, and a two-class benchmark to show shares within ±10 % at ≤5 % overhead at
QD64 with no starvation. That is a large piece of work, and it would pull the library toward
being a worse Seastar. JUring is library-first by design: no futures/coroutines runtime, no
network stack, no application model. The framework that will own scheduling on top of it is a
separate project.

I also don't have a real requirement to build against. The user who would need that class
differentiation (compaction versus foreground, say) belongs to a framework that does not
exist yet. I could invent a two-class workload and tune it, but that would test a synthetic
requirement, not a user need. I would rather not ship a scheduler with nothing to validate
it.

## What to do instead

Partition the work. Give each class its own ring, shard, or `RingGroup`, and optionally pin it
to its own CPU with the affinity options. A class that gets a smaller queue depth and less CPU
is isolated by construction, and the shard metrics already keep each class observable. That is
isolation, not prioritisation, and for most workloads it is the thing that actually holds.

Use the OS where isolation must be enforced system-wide. cgroup v2 `io.weight` and `io.max`,
plus per-process block-layer priorities (`ionice`), still apply to io_uring traffic.

Throttle above the library. Bounded in-flight capacity is already explicit
(`inFlightCapacity()`, stream bounded memory, shard backpressure). If you need to shed class A
when class B is latency-sensitive, do that at the caller.

One more distinction: cancellation is not intent. You can cancel a request or give it a
timeout, but cancel-on-drop is not implemented, and it belongs to the same deferral.

## The ioprio hint is not QoS

There is a low-level `ioprio` hint in the API: `IoPriority`,
`JUring.defaultIoprio`/`setLastSqeIoprio`, and `JuringFile.withIoPriority`, inherited by
streams and `GroupCommit`. It covers read/write opcodes only, and `0` means "no override at
that level", so the ring default or the thread's `ionice` applies.

That does not reopen the decision. There is still no scheduler, fair queuing, weights, rate
limiting or intent layer inside JUring. The hint is passed through to the block layer, where
it is only as good as the configured scheduler; on a `none`-scheduled NVMe it can be inert.
It is scheduler-dependent, not QoS.

If a concrete consumer shows up with a written requirement, and partitioning plus OS controls
cannot serve it, I will revisit this. Until then: bring your own scheduler.
