# B4 design contract — multi-ring / shard runtime (`IoShard`, `RingGroup`)

**Status:** frozen contract, written **before/with implementation** (2026-11-14, session 6).
This document is the coordination contract between the parallel workstreams. Public
signatures marked **frozen** below may not change without a Lead revision of this file;
internal algorithms are owned by the implementation except where this document fixes
observable semantics.
**Implements:** `SEASTAR-PLAN.md` §4 **B4** (gap G2, L2 runtime); builds on
`docs/b1-design.md` (`JuringFile`, engine), `docs/b2-design.md` (streams) and
`docs/b3-design.md` (lifecycle, bounded close, cancellation, `user_data` safety).
**Acceptance (from the plan):** "a thin `IoShard`/`RingGroup`: ring per thread, work
distribution (RR/hash), cross-thread completion handoff (MPSC + eventfd), batched poll
loop, ring pool, graceful shutdown; integrate with `JUringBlocking`. Library, not a
scheduler." **Accept:** W17-W20: 8 threads within 85 % of `fio numjobs=8`; per-shard
isolation; documented threading contract. **Milestone M3.**
**M3 caveat (this session):** M3 also needs **B5** (observability) and a full W17-W20
campaign incl. seastar `--smp N` (which `bench/seastar/seastar_file_bench` does not support
yet). B4 this session therefore delivers the runtime + contract + tests + a **preliminary**
W17-W20-style scaling session; **M3 is not signed off** here.

## 0. Scope and non-goals

**Delivered by B4**

1. `IoShard` — a thin, single-threaded event loop that owns **one** `JUring` ring: the
   ring is created, used and closed on the shard's dedicated platform thread; optional
   CPU affinity; inbound work queue; batched poll loop; completion dispatch; graceful,
   bounded shutdown; counters.
2. `RingGroup` — a fixed pool of `IoShard`s (one ring per shard), round-robin and
   hash-based work distribution, aggregate lifecycle/stats.
3. Cross-thread **completion handoff** via a bounded MPSC queue + `eventfd` signaling
   (`IoShard.handoff` / `ShardContext.handoff`).
4. `JUringBlocking` integration: its poller threads become `IoShard`s, its rings come from
   the shard runtime, and it gains a `RingGroup` constructor plus a **bounded** close
   (fixing the B3 §4.5 unbounded-shutdown residual hazard). Public `JUringBlocking` API is
   otherwise unchanged.
5. Tests, a preliminary W17-W20-style scaling benchmark, and documented contracts
   (threading/ownership, backpressure, shutdown, residual risks).

**Non-goals (explicit)**

- A task scheduler, work stealing, load rebalancing, futures/continuations runtime,
  network stack, QoS. Distribution is fixed (RR/hash); "library, not scheduler".
- Dynamic ring creation/resizing after `RingGroup.start()`; no ring pool checkout API
  (the **pool is the group's fixed shard/ring set**, see §8).
- `JuringFile`/streams becoming multi-owner/shared-ring safe (B3 §11.4/F1 stay as-is);
  a `JuringFile` may be created and used **inside one shard task** (single owner), which
  the tests exercise, but a `JuringFile` is not a cross-shard object.
- B5 metrics/JMX/histograms; B4 provides plain per-shard counters that B5 will extend.
- Full W17-W20/M3 campaign; seastar multi-shard runner.
- Changing any B1/B2/B3 behaviour of `JUring`, `JuringFile`, streams.

## 1. Platform facts (live-verified for this contract)

- Kernel `7.0.12`; JDK 26.0.2; 16C/32T Ryzen 9 3950X. `lscpu`: CPUs `0..15` are the
  first SMT siblings (one per physical core), `16..31` the seconds. Benchmark shards use
  CPUs `1..N` (W17-W20 protocol), never the SMT sibling pair.
- `sched_setaffinity(0, 128, cpu_set)` works for the process's own threads from the
  sandbox: probe `scratch/b4-lead/AffinityProbe` set CPU 3, rc=0, `sched_getcpu()`=3.
  Affinity is therefore **best effort**: failures are recorded, not fatal (§11).
- `eventfd(0, EFD_CLOEXEC|EFD_NONBLOCK)` + a ring-armed 8-byte `IORING_OP_READ` on that
  fd is the existing, working wake protocol (`JUringBlocking` uses exactly this idiom,
  `JUringBlocking.java:339-366,476-486`); B4 reuses the *protocol*, not the code.
- `JUring` user_data always encodes an `OperationType` index in bits 56..63 (0..20), so
  `0xFF` is a safe reserved type byte for the shard's internal wakeup read
  (`SHARD_WAKEUP_USER_DATA = 0xFF00_0000_0000_0001L`). `populateResultFromCqe` maps an
  unknown type to a buffer-less NOP-shaped `Result` (`IoUringDispatcher.java:1543-1551`).
- Default ring flags (same as the M1/M2 harness and this host's proven combination):
  `IORING_SETUP_SINGLE_ISSUER | IORING_SETUP_COOP_TASKRUN | IORING_SETUP_DEFER_TASKRUN`.
  All shard ring operations run on the shard thread, which is the registered owner
  thread for `SINGLE_ISSUER` (`IoUringDispatcher.registerRingFd`), so the ring **must** be
  created, submitted to, reaped and closed on that thread.
- `IOURING_SETUP_DEFER_TASKRUN` requires `SINGLE_ISSUER` (kernel: `EINVAL` otherwise), and
  completions are only posted from inside `io_uring_enter`; the loop therefore blocks in
  `submitAndWait(1)`/`pollForBatchResult`, never on a bare `LockSupport.park` while
  I/O could complete.

## 2. Frozen public API

New public types in `com.davidvlijmincx.lio.api`: `ShardOptions`, `ShardTask`,
`ShardContext`, `CompletionHandler`, `ShardStats`, `IoShard`, `RingGroup`.
`JUringBlocking` gains one constructor (`JUringBlocking(RingGroup)`) and one bounded-close
overload (`close(long timeoutMillis)`), both in §9. No other public API changes.

### 2.1 `ShardOptions` (immutable, `with*` style, like `InputStreamOptions`)

```java
public final class ShardOptions {
    public static final int  DEFAULT_RING_ENTRIES = 256;      // power-of-two target, >= 8
    public static final int  DEFAULT_TASK_BATCH = 64;         // inbound tasks per pass
    public static final int  DEFAULT_CQE_BATCH = 256;         // completions reaped per pass
    public static final int  DEFAULT_SPIN_ITERATIONS = 1024;  // idle onSpinWait polls
    public static final int  DEFAULT_INBOUND_CAPACITY = 1024; // MPSC slots, power of two
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 2000L;
    public static final int  NO_CPU_AFFINITY = -1;

    public static ShardOptions defaults();

    public String name();                    // default "shard"; used in the thread name
    public int ringEntries();                // ring depth, >= 8 (kernel may round up)
    public IoUringOptions[] ringOptions();   // defensive copy; default = the 3 flags above
    public int cpuAffinity();                // NO_CPU_AFFINITY or a logical CPU id
    public int taskBatch();                  // >= 1
    public int cqeBatch();                   // >= 1
    public int spinIterations();             // >= 0
    public int inboundCapacity();            // power of two, >= 2
    public long shutdownTimeoutMillis();     // >= 0

    // withName, withRingEntries, withRingOptions(IoUringOptions...), withCpuAffinity,
    // withTaskBatch, withCqeBatch, withSpinIterations, withInboundCapacity,
    // withShutdownTimeoutMillis -> new ShardOptions; validation is IllegalArgumentException
}
```

`withRingOptions` **replaces** (does not merge) the default flags; callers who need the
proven default set must pass it explicitly. `ringEntries` is the SQE ring depth requested
from `JUring`; the effective per-shard in-flight budget is the same accounting `JUring`
already uses.

### 2.2 Tasks, context, handlers

```java
@FunctionalInterface
public interface ShardTask { void run(ShardContext ctx); }

public interface ShardContext {
    int shardId();
    JUring ring();                 // shard-thread confined; WrongThreadException elsewhere
    void onCompletion(long userData, CompletionHandler handler); // register BEFORE submit()
    void submit();                 // flush prepared SQEs now (optional; loop also batches)
    void execute(ShardTask task);  // enqueue to this shard
    void handoff(IoShard target, Runnable continuation); // MPSC + eventfd to target shard
    boolean stopping();
}

@FunctionalInterface
public interface CompletionHandler { void completed(Result result); }
```

- `ShardContext.ring()` throws `java.lang.WrongThreadException` when called outside the
  shard thread (defence in depth; it is not a general-purpose ring handle).
- `onCompletion` must be called on the shard thread **before** the matching `ring.submit()`
  that can complete the request. The handler runs on the same shard thread, in CQ order.
  The `Result` handed to a handler is the reaping loop's **reusable** object: read/copy
  what you need inside the handler; never retain it.
- `ShardContext.submit()` is a convenience for tasks that want to submit immediately;
  the loop always submits before reaping or blocking.

### 2.3 `IoShard` (frozen public surface)

```java
public final class IoShard implements AutoCloseable {
    public static IoShard create(int shardId, ShardOptions options); // 0 <= shardId, unstarted
    public int id();
    public ShardOptions options();
    public void start();                 // idempotent; creates the ring on the shard thread
    public boolean isStarted();
    public boolean isStopping();
    public boolean isClosed();
    public void execute(ShardTask task); // any thread; backpressure; ISE after stop/close
    public void handoff(Runnable continuation); // any thread; MPSC+eventfd; ISE when closed
    public <T> T call(Callable<T> action);      // run on shard thread; caller blocks
    public boolean inShardThread();
    public Thread shardThread();         // null before start
    public ShardStats stats();           // snapshot; readable before/after start/close
    public void requestStop();           // non-blocking stop request; rejects new work
    public boolean awaitTermination(long timeoutMillis); // interrupt => restore+return false
    public void close();                 // == close(options.shutdownTimeoutMillis())
    public void close(long timeoutMillis); // graceful + bounded; idempotent; never throws
}
```

- `execute`/`handoff` **auto-start** the shard (lazy start is the group's default too).
- `start()` on a closed shard throws `IllegalStateException`. `start()` failure (ring
  creation) throws, marks the shard closed and releases resources.
- `close()` from **inside** the shard thread throws `IllegalStateException` (a shard
  cannot join itself); use `requestStop()` from tasks/handlers and close from a foreign
  thread.
- `close()` never throws for an operation failure; lifecycle misuse (negative timeout)
  is `IllegalArgumentException`. A second `close()` is a no-op.

### 2.4 `RingGroup` (fixed pool of shards/rings)

```java
public final class RingGroup implements AutoCloseable {
    public static RingGroup create(int shardCount, ShardOptions options); // 1..1024
    public static RingGroup create(int shardCount, ShardOptions options, int firstCpu);
    public int size();
    public IoShard shard(int index);       // 0 <= index < size
    public IoShard shardFor(long key);     // stable hash; same key -> same shard
    public IoShard nextShard();            // round-robin
    public void start();                   // start all; on failure close started shards
    public void execute(ShardTask task);              // round-robin
    public void execute(long key, ShardTask task);    // hash(key)
    public void handoff(IoShard target, Runnable continuation);
    public List<ShardStats> stats();       // snapshot in shard order
    public void requestStop();
    public boolean awaitTermination(long timeoutMillis);
    public void close();                   // == close(max shutdown timeout)
    public void close(long timeoutMillis); // shared deadline; parallel stop, bounded joins
}
```

**Affinity semantics.** `create(n, options)`: if `options.cpuAffinity() == NO_CPU_AFFINITY`
no affinity; otherwise shard `i` is pinned to `options.cpuAffinity() + i` ("base CPU").
`create(n, options, firstCpu)` is that base-CPU form explicitly (`firstCpu < 0` = none).
For a standalone `IoShard`, `options.cpuAffinity()` is the **exact** CPU.
Out-of-range CPUs are a best-effort failure recorded in `ShardStats.affinityApplied=false`
(non-fatal); the group does not fail to start.

**Distribution semantics.** `execute(task)` = round-robin over shards (`AtomicInteger`
counter, `floorMod`). `execute(key, task)`/`shardFor(key)` = stable mixing of `key`
(splitmix64 finalizer, `floorMod`) — same key always targets the same shard for the
group's lifetime; there is **no rebalancing**. Per-producer order is FIFO within a
shard's inbound queue; cross-producer total order is unspecified.

### 2.5 `ShardStats` (public record; B5 will add its own richer metrics type)

```java
public record ShardStats(
    int shardId, String threadName,
    int requestedCpu, int currentCpu, boolean affinityApplied,
    boolean started, boolean stopping, boolean closed,
    long tasksSubmitted, long tasksExecuted, long taskFailures, long tasksRejected,
    long completionsRegistered, long completionsDispatched,
    long completionsUnattributed, long completionFailures,
    long handoffsSent, long handoffsInlineFallback,
    long eventfdWrites, long ringSubmits,
    long inFlightRequests,          // ring requests in flight, excluding the wakeup read
    boolean wakeupArmed,
    long cancelledAtClose, long abandonedAtClose, boolean closeTimedOut) { }
```

`inFlightRequests` excludes the internal wakeup `READ`; `completionsUnattributed` counts
CQEs with no registered handler (wakeup completions excluded); `handoffsInlineFallback`
counts §6.3 overflow fallbacks.

## 3. Threading model and ownership (frozen)

1. **One ring, one thread.** Each `IoShard` owns exactly one `JUring`. The ring is
   created, submitted to, reaped, and closed **only** on that shard's platform thread
   (`juring-shard-<id>-<name>`), which is the `SINGLE_ISSUER` owner thread. No other
   thread touches the ring or its native memory.
2. **Tasks and handlers run on the shard thread** (single-threaded, serial). They may
   call every `JUring.prepare*`/`submit`/`peek*` API, use a `JuringFile`/stream opened
   inside the shard (single owner), register completions, and hand off continuations.
   They must not block the shard thread on other shards (deadlock risk, §11).
3. **Foreign threads** may only call `execute`, `handoff`, `call`, lifecycle methods and
   `stats`. `execute`/`handoff` never touch the ring; they enqueue via the shard's MPSC
   queue and wake the loop via `eventfd`.
4. **Shard threads are daemon platform threads** (matching `JUringBlocking`'s pollers).
   Correct resource release still requires `close()`.
5. **Affinity.** `start()` records `requestedCpu`, applies `sched_setaffinity` (best
   effort) and records `currentCpu` (`sched_getcpu`) in `ShardStats`. If the CPU is out of
   range or unavailable (cgroup/cpuset), the shard still starts and
   `affinityApplied=false`.
6. **Multiple `IoShard`s never share a `JUring`.** Sharing rings between owners is out of
   scope (B3 §11.4/F1 stay open).

## 4. Batched poll loop (frozen observable behaviour)

The loop is a single-threaded "drain completions -> run inbound work -> submit -> reap ->
block" cycle. Fixed order and bounds:

1. Reap and dispatch up to `cqeBatch` available completions (handlers run inline).
2. Run up to `taskBatch` inbound tasks.
3. If SQEs were prepared, `ring.submit()` once (batched); count `ringSubmits`.
4. Repeat while there is progress. Completions are always serviced before the next task
   batch, so a flood of submissions cannot starve completion handlers.
5. When idle: spin up to `spinIterations` (`Thread.onSpinWait`) checking inbound/ring,
   then arm the wakeup read if needed and block inside `io_uring` waiting for at least one
   completion (`submitAndWait(1)` when the ring has requests, otherwise arm+wait); the
   wakeup `READ` on the shard `eventfd` is the wake signal for foreign submissions.
6. The wakeup read is kept armed while the shard is running; its completion is consumed
   internally (`user_data == SHARD_WAKEUP_USER_DATA`), never delivered to handlers, and it
   is re-armed after each wake. A submission that arrives while the shard is busy leaves
   the eventfd counter non-zero, so the next armed read completes immediately.
7. Handler/task exceptions are caught: counted (`completionFailures`/`taskFailures`) and
   the loop continues. A failing task/handler never kills the shard.

Backpressure/overflow rules (frozen):

| situation | behaviour |
|---|---|
| `execute` from a foreign thread, inbound full | **blocks** (spin/park) until space, stop, or close (then ISE) |
| `execute` from the shard thread, inbound full | ISE immediately (a shard must never block on itself); counted `tasksRejected` |
| `handoff` target inbound full | bounded spin, then run the continuation **inline** on the calling thread and count `handoffsInlineFallback` (never blocks, never drops; a continuation that comes from a completion handler therefore runs on **some** shard thread, not necessarily the target one — document/design handoffs so this is safe) |
| `onCompletion` table full | ISE at registration time; SQEs that the same task prepared **before** the failing registration may still be submitted by the loop and complete with no handler — those completions are counted `completionsUnattributed` and the loop continues (never spins) |
| completion with no handler | counted `completionsUnattributed`; loop continues; handler-table lookup/deletion is bounded and always terminates (capacity/probe invariants, see §5) |

## 5. Completion dispatch (frozen)

- A task calls `ctx.onCompletion(userDataFromPrepare, handler)` then submits. The handler
  table is a shard-thread-local open-addressing `long -> CompletionHandler` map (no
  allocation on the hot path, no boxing) sized `nextPow2(max(1024, ringEntries*2))`;
  `onCompletion` outside the shard thread or after stop → ISE.
- On reaping, the loop looks up `Result.id()` (the raw `user_data`, `Result.id()`) and
  removes/invokes the handler. Registration must precede `ring.submit()`; a completion
  that was already posted with no handler is `completionsUnattributed` (not a crash).
- The table implementation must keep the structural invariant that every probe loop
  (`put`/`remove`) is bounded and terminates even under churn and near-capacity load
  (no tombstones-without-rebuild, no cyclic cluster re-insertion); a missing handler is
  always reported as `completionsUnattributed`, never a spin or a hang. Capacity covers
  live (registered-not-yet-dispatched) handlers; callers keep peak in-flight within it
  (`nextPow2(max(1024, ringEntries*2))` by default) and a too-small capacity degrades to
  registration ISE (§4), not to a wedged shard.
- Cancelled/timed-out requests are ordinary completions (`-ECANCELED`/`-ETIME`), so their
  handlers run and blocking callers observe the same terminal result as B3.
- At force-close, handlers of requests that never completed are dropped and counted via
  the ring's `abandonedAtClose` (plus `cancelledAtClose` for reaped cancels, §7).

## 6. Cross-thread completion handoff (frozen)

1. MPSC queue: bounded, lock-free (Vyukov-style sequence ring, same algorithm family as
   `JUringBlocking`'s internal MPSC), capacity `inboundCapacity` (power of two).
   Multi-producer enqueue, single-consumer (the shard thread) drain.
2. `eventfd` signal: one per shard, `EFD_CLOEXEC|EFD_NONBLOCK`; producers write an 8-byte
   `1` (coalesced by a wake-state CAS so a storm of producers does not add syscalls when a
   wake is already pending). Writes are counted (`eventfdWrites`); `EAGAIN` means the
   counter saturated and a wake is already pending (ignored).
3. `handoff(target, continuation)` is the completion-continuation path: enqueue to the
   target's queue and signal; if the queue is full after a bounded spin, run the
   continuation inline on the current shard (`handoffsInlineFallback++`, document that
   such continuations must not assume target-thread confinement). It never blocks.
4. A handoff continuation runs on the target shard thread, in FIFO order with that
   shard's tasks and other handoffs. This is the mechanism for a completion produced on
   shard A to be delivered/processed on shard B.
5. `IoShard.handoff(Runnable)` is the same primitive sent to *this* shard;
   `ShardContext.handoff(target, r)` is sugar for `target.handoff(r)`.
6. Thread-to-shard blocking (the `JUringBlocking` integration path) uses the same pattern
   with `LockSupport.park/unpark` as the caller-side wake primitive; the eventfd is for
   shard loops, park/unpark for virtual-thread-friendly blocking callers (§9).

## 7. Graceful, bounded shutdown (frozen)

`requestStop()` (non-blocking): sets `stopping`, rejects new `execute`/`handoff`/`call`
(ISE), wakes the loop; returns immediately. Tasks already accepted before the stop are
still executed while the shutdown budget allows; `stopping()==true` inside tasks.

`close(timeoutMillis)` (foreign thread; idempotent; never throws):

1. `requestStop()`; if never started, mark closed and free idempotently.
2. The shard loop first drains the inbound tasks that were accepted before the stop
   (bounded by the shutdown budget). Requests still in flight after that are then
   cancelled with one ring-wide `IORING_OP_ASYNC_CANCEL ALL|ANY` (the B3 `close()`
   convention: shutdown cancels in-flight work instead of waiting for it to finish) and
   the resulting `-ECANCELED` completions are dispatched to their handlers until the
   deadline. Then the ring is closed with the remaining budget
   (`JUring.close(remaining)`). Handlers that never run are dropped and reflected in
   `abandonedAtClose`.
3. The closing caller waits for the shard thread to terminate up to
   `timeoutMillis + 250 ms` (the B3 grace convention); on timeout it records
   `closeTimedOut=true` and returns anyway (a user task stuck in the shard thread cannot
   be killed; the ring is closed when it returns). `awaitTermination` returns whether the
   thread ended.
4. A closed shard rejects `execute`/`handoff`/`call`/`start` with `IllegalStateException`;
   `stats()` stays readable.
5. `RingGroup.close(timeout)` uses one shared deadline: `requestStop()` every shard, then
   closes every shard against the same deadline (package-private `closeUntil(deadline)`),
   so group shutdown is bounded by the deadline plus teardown grace, not N×timeout.

## 8. Ring pool / group lifecycle (frozen)

- `RingGroup` **is** the ring pool: `create()` allocates shard objects only; each shard
  creates its one ring on its own thread on (lazy) start. No ring is created per request;
  no dynamic resize (create a new group instead). The group owns and closes exactly the
  rings of its shards.
- `RingGroup.start()` starts all shards eagerly and throws on the first failure after
  closing the already-started shards; `execute` may also lazily start one shard.
- Work distribution is fixed at `create` time (§2.4); `shard(i)`/`shardFor(key)` let
  callers submit affinity-aware work or hand off to a specific shard.
- `RingGroup` does not own any file descriptors, buffers, or `JuringFile`s by itself;
  those belong to user tasks and must be closed by them before the group closes.

## 9. `JUringBlocking` integration contract (frozen)

`JUringBlocking` remains the virtual-thread-friendly **blocking** façade; B4 makes its
poller threads and rings come from the shard runtime instead of a private ad-hoc pool.

1. **API:** all existing constructors/methods keep their signatures and semantics. New:
   ```java
   public JUringBlocking(RingGroup group); // takes ownership of the group; group must be unstarted
   public void close();
   public void close(long timeoutMillis);  // NEW bounded-close overload
   ```
   `group.size()` becomes the worker/poller count; its rings are the worker rings. The
   group must be **unstarted** when passed in (`IllegalStateException` if started) because
   `JUringBlocking` installs its own loop body into each shard (§9.2); `JUringBlocking`
   takes ownership (its `close()` closes the group; the caller must not use it after).
   An already-closed group → `IllegalStateException`.
2. **Implementation (lowest-risk seam, Lead decision):** each `JUringBlocking` worker is
   hosted by one `IoShard`. `IoShard` provides a package-private **loop-body seam**
   (`ShardBody.run(ShardLoopContext)`; `ShardLoopContext` exposes the shard-thread-confined
   `JUring`/`IoUringDispatcher`, the shard id, and `stopRequested()`); the generic
   task/handoff loop of §4 is simply the default body. `JUringBlocking` installs its
   **existing, tuned slot protocol** (per-worker MPSC, eventfd wake, parked VT, batched
   submit/reap) as the body of each shard — the ring, thread, CPU affinity, counters and
   lifecycle/bounded-close come from `IoShard`. The slot protocol's *observable* behaviour
   is unchanged; this is what makes the zero-GC and VT-latency guarantees preservable
   without a risky rewrite.
3. **Preserved behaviour:** blocking read/write/fixed/open/close/registration semantics,
   per-call zero-GC slot path (unchanged: no lambda/closure/wrapper allocation per I/O),
   interrupt handling (restore flag and return/throw as today), close-while-in-flight
   callers unblocked deterministically — a request cancelled by close completes with
   `-ECANCELED` (returned/`JuringException` per the method's normal error contract), while
   a request whose SQE was never submitted throws `IllegalStateException`; both are
   documented terminal outcomes (§9.4), broadcast registration/`openDirect`/`closeDirect`
   to all workers, virtual-thread concurrency. All existing `JUringBlockingTest` tests
   must pass unchanged.
4. **Bounded close (new guarantee):** stop accepting, cancel in-flight, dispatch
   cancellations to unblock callers, bound the wait by `timeoutMillis` (default 2000 ms,
   `JUring.DEFAULT_CLOSE_TIMEOUT_MILLIS` convention) + 250 ms grace, then close the group
   boundedly. Ring teardown uses B3's bounded `JUring.close(remainingBudget)` on each shard
   thread; a request whose cancellation completion never arrives is abandoned/counted and
   the ring is force-closed at the deadline. The B3 §4.5 "can block forever" hazard is
   **fixed** by this path. Negative timeout → `IllegalArgumentException`; idempotent.
5. **Affinity:** `JUringBlocking(RingGroup)` inherits the group's shard→CPU pinning;
   existing constructors create an unbound private group (no affinity), as today — they
   also use this body seam (`numPollers` shards, `ShardOptions.defaults()
   .withRingEntries(queueDepth).withRingOptions(flags)`), so the default worker count
   logic and the `juring.blocking.pollers` property are unchanged.
6. **Dedup non-goal:** B4 does not delete the slot protocol or move it to the generic task
   loop; it only re-homes its thread/ring/lifecycle. That is an explicit trade-off:
   contract-preserving integration over a risky rewrite of a tuned VT hot path.

## 10. Error taxonomy (frozen)

| call/situation | result |
|---|---|
| `execute`/`handoff`/`call` on stopping/closed shard | `IllegalStateException` (rejected, no side effect) |
| `close()` called from the shard thread | `IllegalStateException("cannot close a shard from its own thread")` |
| `start()` after `close()` / on closed group | `IllegalStateException` |
| `start()` ring creation failure | unchecked exception (B1 taxonomy), shard marked closed, resources released |
| `close(negative)` / invalid options | `IllegalArgumentException` |
| `ctx.ring()` off the shard thread | `WrongThreadException` |
| `onCompletion` off shard thread / after stop / duplicate / table full | `IllegalStateException` |
| handler/task throws | caught, counted, loop continues (never propagates) |
| kernel completion error | handed to the registered handler as a negative `Result.result()` exactly as `JUring` does |
| inbound full from foreign `execute` | blocks (backpressure) until space/stop → then ISE |
| handoff overflow | inline fallback, counted (never blocks/drops) |

## 11. Known limitations / residual risks (frozen disclosure)

1. **Not a scheduler.** No work stealing, rebalancing, fairness across producers, or
   per-shard load limits; RR/hash distribution is fixed. A slow shard stays slow.
2. **Inline handoff fallback** (queue overflow) runs the continuation on the current shard
   thread, breaking strict target-thread confinement for that continuation; it is counted
   and documented. Size queues / batch handoffs if strict affinity matters.
3. **Cyclic submits can deadlock** foreign `execute` backpressure (A's task submits to B
   while B's task submits to A with full queues). Documented user error; handoff cannot
   deadlock (inline fallback).
4. **`close()` cannot kill a shard thread stuck in a user task.** The caller-side wait is
   bounded (`timeout + 250 ms`) but ring/native resources are released only when the task
   returns; `closeTimedOut` is recorded. Same class of caveat as B3 §4.2.
5. **Handler `Result` is reusable**; retaining it past the handler returns is a bug.
6. **Affinity is best effort** (cgroups/cpuset may deny); recorded, never fatal.
7. **Ring flags:** only the default
   `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` combination is in the acceptance path.
   Other flag sets pass through to `JUring` but the loop's wake protocol assumes
   `DEFER_TASKRUN` behaviour is handled by `JUring` (it is); `SQPOLL`/`IOPOLL` are not
   tested by B4.
8. **`user_data`/completion-table capacity** must cover peak in-flight; a too-small
   `ringEntries` (relative to registered-but-unsubmitted handlers) can produce
   `IllegalStateException` rather than a kernel error.
9. **No shared-ring multi-owner support**: B3 §11.4/F1 remain open; a `JuringFile` is
   used by one shard task at a time.
10. **`JUringBlocking` internals are re-homed** on the shard runtime; a full VT
    throughput re-benchmark is out of B4 scope (existing tests + a B4 smoke stand in;
    M3/B5 campaign can re-measure). Any regression found by the verifier is a release
    blocker.
11. **M3 is not signed off**: B5 metrics, seastar multi-shard and the full W17-W20
    campaign (R=5, state gating, 300 s rests) remain. B4's bench is explicitly
    preliminary.

## 12. Required tests (acceptance matrix — author + verifier)

Test budget: deterministic JUnit tests use small counts and ≤ 1 s deadlines; heavier
stress lives in `scratch/b4-*/` standalone harnesses so `mvn test` stays fast.

**Core (`IoShard`/`RingGroup`) — required JUnit**
1. lifecycle: create/start/execute/close; idempotent start/close; execute after close
   throws; close from shard thread throws; stats before/after; `call` before/after start.
2. affinity: `withCpuAffinity(3)` → `stats().affinityApplied`, `currentCpu==3`,
   `inShardThread`/thread name; invalid CPU recorded non-fatally.
3. distribution: RR covers all shards evenly; `shardFor(key)` stable for a key set;
   `floorMod` edge keys (`0`, `-1`, `Long.MIN_VALUE`, `Long.MAX_VALUE`); single-shard
   group.
4. order: per-producer FIFO for a burst of tasks from one thread; all tasks executed.
5. task/handler exceptions are counted and do not kill the loop (next task runs).
6. completion dispatch: N reads -> one registered handler per op, exactly one dispatch
   each; handlers run on the shard thread; `completionsUnattributed` only for
   unregistered cases.
7. cross-thread handoff: shard A completion hands off to shard B -> continuation observed
   on B's thread, ordering FIFO, counter `handoffsSent`; handoff to an idle B wakes it.
8. handoff overflow: tiny target inbound + slow consumer -> bounded spin then inline
   fallback counted, no hang, no loss.
9. backpressure: tiny inbound, N foreign producers -> all accepted (blocking), no loss,
   shard drain completes; from own shard thread full queue -> ISE.
10. shutdown: pending tasks before close are executed (or counted); in-flight ops
    cancelled and their registered handlers receive `-ECANCELED`; `close(0)` bounded;
    double close; `requestStop` + `awaitTermination`; `closeTimedOut` only for a stuck
    task; ring/fd/eventfd resources released (fd count stable).
11. batching: `cqeBatch`/`taskBatch` bounds respected observably (stats + staged
    submission); large burst (e.g. 4096 ops) completes.
12. crash/stress: 10k ops across 4 shards, mixed tasks and handoffs, no loss, no hang,
    exactly one dispatch per op; repeated create/close cycles leak no threads/fds.
13. `JuringFile` inside a shard task (single owner) reads/writes correctly and closes.
14. `RingGroup` close(timeout) with an idle group and with in-flight work is bounded;
    `stats()` sizes and ids; `nextShard`/RR distribution.
15. `ShardStats` invariants: counters monotone, in-flight returns to 0 after drain.

**`JUringBlocking` integration — required JUnit**
16. `new JUringBlocking(RingGroup)` basic read/write/fixed/open/close parity with the
    existing constructor; ownership (close closes group; double close safe).
17. VT concurrency on the group constructor: many VTs, reads/writes, all results correct.
18. bounded close with in-flight: `close(2000)` with many reads in flight returns within
    bound+250 ms; callers unblocked; no fd/thread leak; `close(0)` bounded.
19. affinity: group with CPU pinning -> blocking pollers report the pinned CPUs
    (via group stats while open).
20. All 238 existing tests unchanged and green (regression gate).

**Independent verifier (`scratch/b4-verify/`)** — must re-derive, not just re-run:
adversarial modes for the above (especially handoff storms, queue-full, forced closes,
stuck-task timeout, use-after-close from every entry point, thread/fd/memory leak loops,
`JUringBlocking` old-vs-new parity, double-close races, handler re-entrancy), plus its
own `mvn -o test` run and a `git diff` frozen-tree checksum.

## 13. Benchmark / acceptance plan (preliminary)

- **Harness:** extend `bench/juring/JUringFileBench.java` with a shard-runtime mode
  (`--shards N`, `--runtime=shard|threads`, default unchanged = current manual threads;
  same JSON/latency/PRNG conventions so `bench/m1`/`bench/stream` analyses still apply).
  The shard mode runs one self-driving read loop per `IoShard` (QD per shard, pinned CPUs
  1..N) through `RingGroup`; per-shard IOPS/p99 reported from `IoShard`/`ShardStats`.
- **Cell set (preliminary W17-W20):** 4K randread, QD 64/shard, N ∈ {1,2,4,8}, CPUs
  1..N, `single_file_bench.bin` (aged 10 GiB read-only), 10 s warmup + 30 s measured,
  R=3, 60 s rests; engines: `juring-group-N`, `juring-manual-N` (existing `--threads N`),
  `fio numjobs=N` (matched: `--ioengine=io_uring --registerfiles=1 --fixedbufs=1`,
  `--iodepth=64`, `--direct=1`, pinned 1..N). No write cells, no seastar (out of scope
  this session).
- **Acceptance bars (plan M3, applied to the preliminary data):**
  `juring-group-8 >= 85 % of fio-8` total IOPS; `SE(8)=IOPS(8)/(8*IOPS(1)) >= 0.75`;
  per-shard min/mean >= 0.85; `p99(8) <= 1.15 * p99(1)`. Bars missed or invalidated are
  reported, not trimmed. This is explicitly **preliminary** (R=3, one session, no state
  gating by 1 s series): it can support "B4 runtime scales" but **not** the M3 sign-off.
- Deliverables: `bench/juring/JUringShardBench` support + `bench/shard/` driver/analyzer +
  `bench/results/b4-<date>/` (gitignored) + numbers in `docs/b4-report.md`.

## 14. Write scopes and parallel plan (advisory; disjointness is the rule)

| Workstream | Write scope |
|---|---|
| **b4-engineer** (core) | `docs/b4-design.md` (Lead-owned; read-only for the engineer), `src/main/java/com/davidvlijmincx/lio/api/{ShardOptions,ShardTask,ShardContext,CompletionHandler,ShardStats,IoShard,RingGroup,MpscQueue,CompletionTable}.java` (new), minimal package-private additions to `LibCDispatcher.java`, `JUring.java` (e.g. raw-fd wakeup read helper), and new `src/test/java/com/davidvlijmincx/lio/api/B4*.java` tests; `scratch/b4-core/**` |
| **b4-blocking** (integration) | `src/main/java/.../JUringBlocking.java`, new `src/test/java/.../B4Blocking*.java`; `scratch/b4-blocking/**` |
| **b4-verifier** | `scratch/b4-verify/**` only (no `src/`, no `docs/`, no `bench/`, no `pom.xml`) |
| **b4-bench** | `bench/**` only (esp. `bench/juring/JUringFileBench.java`, `bench/shard/**`) and `scratch/b4-bench/**` |
| **Lead** | `docs/b4-design.md`, `docs/b4-report.md`, `docs/HANDOVER.md`, `SEASTAR-PLAN.md`, `scratch/b4-lead/**`, git commits, final full-suite runs |

**Gates (serialised):** G1 core compiles + core tests green (only b4-engineer runs
`mvn`); G2 `JUringBlocking` integration + full suite green (b4-blocking runs `mvn`,
engineer idle); G3 code freeze (Lead checksum `git diff | sha1sum`; no further `src/`
edits); G4 verifier matrix + independent suite run; G5 bench session (no other heavy
load); G6 Lead final suite + docs + commit. Bench harness authoring and verifier harness
authoring may proceed in parallel throughout (no execution until their gate).

## 15. Definition of done for B4

1. `IoShard`/`RingGroup` public API implemented exactly as §2; semantics in §3-§8 hold.
2. `JUringBlocking` on the shard runtime: §9 contract holds; all pre-existing tests green.
3. Required JUnit matrix (§12) added; `mvn -o test` green (count reported in
   `docs/b4-report.md`); no regressions in the 238 B3 tests.
4. Independent adversarial verification PASS (`scratch/b4-verify/VERDICT.md`), with any
   findings fixed or frozen as disclosed limitations in §11.
5. Preliminary W17-W20 data + analysis (honest, all reps, no trimming), M3 explicitly not
   signed off.
6. `docs/b4-report.md` + `docs/HANDOVER.md` §0 + `SEASTAR-PLAN.md` B4 status updated;
   committed on branch `kafka-engine` with the B4 code/tests/docs.
