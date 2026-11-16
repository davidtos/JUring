# M4 / B8 decision — QoS and intent stay out of JUring v1 (documented decision, no experiment)

**Status:** closed by documented decision, session 10 (2026-11-15). This is the plan's own
alternative acceptance path for milestone **M4**: *"shares honoured ±10 % with ≤5 % overhead,
no starvation — **or** a documented decision to keep QoS out of v1"* (`SEASTAR-PLAN.md` §5),
and the B8 instruction *"Otherwise document 'bring your own scheduler' and keep it out of
v1"* (`SEASTAR-PLAN.md` §4 B8).

**Decision:** JUring v1 ships **no QoS, fair-queuing, weighted-share scheduler, rate limiter
or intent/cancel-on-drop layer**. B8 is not implemented; M4 is closed by this decision, not
by a shares experiment. The supported answer to "I need priorities" is **bring your own
scheduler**: partition work into separate rings/shards, or use OS-level I/O controls.

> **Update 2026-11-16 (B10b).** A *low-level* `ioprio` **hint** is now exposed
> (`IoPriority`, `JUring.defaultIoprio`/`setLastSqeIoprio`, `JuringFile.withIoPriority`,
> inherited by streams/`GroupCommit`; read/write opcodes only, `0` inherits the thread's
> `ionice`). This does **not** reopen M4: there is still no scheduler, fair queuing, weights,
> rate limiting or intent layer inside JUring, and the hint is inert on `none`-scheduled
> NVMe. See [`docs/b10-design.md`](b10-design.md).

## 1. Why (evidence)

### 1.1 The locked scope puts QoS in the framework, not the library

- **D1 (locked 2026-11-13):** *"JUring is library-first. The seastar alternative being built
  on it is a separate flagship project... No futures/coroutines runtime, no network stack,
  no application layer in JUring"* (`SEASTAR-PLAN.md` §Decisions D1).
- The **scope note** at the end of §4: *"the framework supplies the scheduler, network stack,
  application model and QoS. That boundary is why the roadmap stops at B9."*
- **§3.6 item 5:** *"QoS/backpressure is out of 'basics' scope (B8 conditional); do not
  compare it until a concrete user requirement exists."*
- **Non-goals** (§4): a task scheduler / work-stealing actor model is explicitly excluded.

QoS in seastar is **not** an io_uring capability — it is reactor-layer scheduling. The
seastar inventory shows it living in the reactor's `io_queue`/`fair_queue`: scheduling
groups with `shares`/`class`, fair-queue token-bucket accounting, starvation accounting and
per-class metrics (`docs/seastar-inventory.md` §C; `SeaStarRef/src/core/io_queue.cc`
`priority_class_data`/`_tb.replenish`/`_starvation_time`, ~`:150-335,:991-1023,:1147-1190`;
`include/seastar/core/fair_queue.hh:150-230`). Building that inside JUring would pull the
library toward "a worse seastar" — the exact outcome D1/D2 rule out.

### 1.2 There is no real user requirement today

- B8's own gate is *"only if real users (e.g. a future framework's compaction vs foreground)
  need it"*. The framework that would supply those workload classes **does not exist yet**
  (D1; plan §0/§3) and no such consumer exists in the repo.
- Before B10b (the historical state this decision was made on; see the update above) JUring's
  public API could not express a class/priority: the native SQE `ioprio` field accessor existed
  (`Sqe.java:18,36,90-96`) but was **never set by any public API** —
  `docs/juring-inventory.md` §SQE: *"Struct accessor exists but is never set by a public API
  -> no priorities"*, and G3: *"Absent: no public ioprio setter ... no scheduler,
  shares/weights, or intent cancellation"*. The decision below still stands because a hint is
  not a scheduler.
- No QoS experiment, benchmark cell, report section or user story in the project history
  requires a scheduler. Pre-existing mentions of QoS/shares are only (a) the plan/inventory
  and report/methodology deferral or limitation text, and (b) descriptions of *seastar's*
  capabilities (reactor `io_queue` scheduling groups, `--sched-groups` YAML in `bench/`), not a
  JUring user need. There is
  therefore nothing to validate a scheduler against; inventing a two-class experiment would
  test a synthetic requirement, not a user need.

### 1.3 The cost/risk is L-sized against a library identity

B8 is rated **L**. A minimal but honest implementation needs: class/weight plumbing through
the public API and the shard runtime, per-class in-flight accounting, a weighted dispatch
policy that composes with the existing RR/hash distribution and batched poll loop, tail-latency
guards/no-starvation accounting, plus its own test and benchmark campaign (two-class shares
within ±10 %, ≤5 % overhead at QD64, no starvation) — i.e. a milestone-scale effort that would
touch the B4 runtime contracts. The plan already rates it conditional; without a user it is
deferred work with no acceptance target that a real consumer defines.

## 2. What v1 offers instead ("bring your own scheduler")

Documented, supported patterns (no library scheduler needed):

1. **Resource partitioning instead of prioritisation.** Give each workload class its own
   `IoShard`/`RingGroup`/`JUring` (optionally pinned to its own CPU via the B4 affinity
   options) and size the classes by how much queue depth / CPU each gets. This is isolation
   by construction and composes with the existing metrics (B5) for per-class observation.
2. **OS-level I/O controls.** cgroup v2 `io.weight` / `io.max` and per-process block-layer
   priorities (`ionice`, `ioprio`) still apply to io_uring traffic; use them when isolation
   must be enforced system-wide rather than inside the JVM.
3. **Backpressure at the caller.** Bounded in-flight capacity is already explicit
   (`inFlightCapacity()`, stream bounded memory, shard backpressure); a caller that needs to
   shed or throttle class A when class B is latency-sensitive does that above JUring.
4. **Cancellation isn't intent.** B3 gives per-request cancellation/timeouts; cancel-on-drop
   (seastar's `io_intent`) is not implemented and is part of this deferral.

This guidance lives here and is summarised in the README (scope note) so users do not
mistake the absence of QoS for an unfinished feature.

## 3. What this decision does **not** change

- No source/test/bench change for B8; the public API is unchanged.
- M1 (read path), M2 (L1 streams), M3 (L2 scaling/metrics) sign-offs are unaffected; M4 was
  the only milestone with QoS content.
- The B7 decision (`docs/b7-decision.md`) is independent; the B9 work (stability/docs/
  examples) is independent.
- The comparison programme gains no new cross-engine claim: seastar's QoS is documented as a
  framework-layer capability difference (fair-queue/scheduling groups) and remains **not
  compared** under JUring.

## 4. Re-open condition (frozen)

Reopen B8/M4 only if **all** hold:
1. a concrete consumer exists (e.g. the future framework's compaction-vs-foreground
   workloads) with a written requirement for class differentiation;
2. a baseline shows the consumer cannot be served by §2 patterns (partitioning/OS controls);
3. the plan's M4 acceptance (two classes, shares honoured within ±10 %, overhead ≤5 % at
   QD64, no starvation) has a defined workload and owner.

Until then, B8 stays out of v1 and the plan records this documented decision as M4's closure.
