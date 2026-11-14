# B5 design contract — observability (ring metrics, latency histogram, JMX, benchmark JSON)

**Status:** frozen contract, written **before** implementation (2026-11-14, session 7).
This document is the coordination contract between the B5 workstreams. Public signatures
marked **frozen** may not change without a Lead revision of this file; internal algorithms
and call-site placement are owned by the implementation except where this file fixes
observable semantics. **Amendments** are appended as `A1, A2, ...` with a date, never
silently edited.
**Implements:** `SEASTAR-PLAN.md` §4 **B5** (gap G5, observability, L2); starts from the
B4 `ShardStats` counters and the B3 lifecycle contract.
**Acceptance (from the plan):** per-ring `submitted/completed/in-flight/reaped`, SQ/CQ
occupancy, error counts, latency histogram (off the hot path), JMX/Micrometer binding; the
benchmark JSON includes them. **Accept:** metrics at **< 1 % throughput cost**; the **M3
report shows per-ring queue depth and p99**.
**M3 caveat (this session):** B5 delivers the observability layer and the benchmark/JSON
plumbing only. **M3 is not signed off here**: the full R=5 W17-W20 campaign (incl. seastar
`--smp N`) and the restated M3 bars remain (`docs/b4-report.md` §8).

Baseline tree: branch `kafka-engine` at `6cc1a75` (B4 delivered in `159d60f`), 309 tests
green. B5 must not change any B1/B2/B3/B4 behaviour; all existing tests stay unchanged.

---

## 0. Scope and non-goals

**Delivered by B5**

1. **Per-ring counters + occupancy** on every `JUring` ring, exposed as an immutable
   `RingMetrics` snapshot: prepared / submitted / reaped / completed / failed / in-flight,
   SQ and CQ occupancy, enter/submit/error/retry/SQ-full counts, CQ-overflow counter.
   Counters are always on (cheap, batched where possible); occupancy is read from the
   shared ring at snapshot time (no hot-path cost).
2. **A dependency-free latency histogram** (`LatencyHistogram` + `LatencySnapshot`,
   log-linear fixed buckets, allocation-free `record`, snapshot allocates only off the hot
   path) and **per-ring dispatch-latency measurement in the B4 shard runtime**
   (`ShardOptions.withLatencySampleEvery(int)`; disabled by default, sampled to bound
   overhead).
3. **Shard/group metric snapshots**: `IoShard.metrics()` (`ShardMetrics` = B4 `ShardStats`
   + `RingMetrics` + dispatch `LatencySnapshot`), `RingGroup.metrics()`,
   `RingGroup.aggregateMetrics()` (`GroupMetrics`).
4. **JMX binding** (JDK-only, no new runtime dependency): per-shard and per-group MXBeans
   with pull-based attributes, explicit registration handles, deterministic ObjectNames,
   idempotent unregistration. The plan's "JMX/Micrometer binding" is satisfied by JMX
   plus a documented snapshot-based Micrometer seam (§9); shipping a hard Micrometer
   dependency is an explicit non-goal.
5. **Benchmark integration**: `JUringFileBench` JSON schema 1.1 adds per-ring
   `ring_metrics`, library `dispatch_latency_us`, and a 1 Hz `ring_series` (queue depth +
   dispatch p99) for both runtimes; `analyze_shard.py` surfaces per-ring queue depth and
   p99; a new overhead A/B session driver/analyzer proves the < 1 % bar against the B4
   baseline build.
6. Tests, an independent adversarial verification, and documented contracts.

**Non-goals (explicit)**

- No metrics push/exporter, no Prometheus/HTTP server, no background exporter thread, no
  global metrics registry, no JMX remote connector/authentication setup (JMX binding is
  local `MBeanServer` registration; the operator decides exposure).
- No hard Micrometer (or any third-party) runtime dependency. The snapshot types are the
  binding seam (§9).
- No histogram/measurement changes to `JuringFile`, `JuringInputStream`/`OutputStream`,
  `JUringBlocking`'s internal slot protocol, or the raw `threads` runtime pipeline; B1
  counters stay as they are. B5's latency histogram covers the **shard runtime** dispatch
  boundary (the M3 path); the benchmark's external reservoir still defines the raw
  threads-mode p99.
- No hot-path logging, no per-op allocation, no runtime-reconfigurable sampling, no
  per-op tagging/labels, no tracing spans.
- No changes to B3 cancellation/timeout semantics or B4 threading/lifecycle semantics.
- No M3 campaign, no seastar `--smp N` runner, no M1 write matrix.

---

## 1. Platform facts (live-verified this session)

- Branch `kafka-engine` @ `6cc1a75`; `mvn -o test` → **309 tests, 0 failures, 0 errors,
  1 skipped** (baseline, re-run by the Lead on the untouched tree before B5 edits).
- JDK 26.0.2 (`java`), JDK 25.0.3 for Maven/surefire; kernel 7.0.12; Ryzen 9 3950X
  (16C/32T); data NVMe GIGABYTE GP-ASM2NE6100TTTD; aged 10 GiB read-only dataset
  `/mnt/TestData/read_files/single_file_bench.bin`.
- Offline Maven (`mvn -o`) works; the local repository has JMH/JUnit/AssertJ/jcstress/
  HdrHistogram test deps but **no Micrometer**. Network is available but B5 must not add
  a runtime dependency on an external artifact (§9).
- `java.management` is a JDK module. `src/main/java/module-info.java` currently has only
  `requires static jdk.management;`; B5 adds `requires java.management;` for the JMX
  binding. Tests run on the classpath (surefire), so module-path resolution is not part
  of the test path; `mvn -o test` remains the gate.
- The B4 shard runtime is the M3 path: one `JUring` per `IoShard` thread, completions
  dispatched by the shard loop, `ShardStats` already exposes lifecycle/counter snapshots
  from any thread. B5 extends that, it does not replace it.

---

## 2. Frozen public API

New public types in `com.davidvlijmincx.lio.api`:

| type | kind | purpose |
|---|---|---|
| `RingMetrics` | record | immutable snapshot of one ring's counters + occupancy |
| `LatencySnapshot` | record | immutable histogram snapshot (nanoseconds) |
| `LatencyHistogram` | final class | reusable fixed-bucket histogram (public; used by shards and available to users) |
| `ShardMetrics` | record | `ShardStats` + `RingMetrics` + dispatch `LatencySnapshot` |
| `GroupMetrics` | record | aggregate of a `RingGroup`'s shard metrics |
| `IoShardMetricsMXBean` | interface | JMX view of one shard |
| `RingGroupMetricsMXBean` | interface | JMX view of one group aggregate |
| `MetricsJmx` | final class | registration/unregistration of the MXBeans; nested `Handle` |

Changed public surface (additive only): `JUring.ringMetrics()`, `IoShard.metrics()`,
`RingGroup.metrics()`, `RingGroup.aggregateMetrics()`,
`ShardOptions.withLatencySampleEvery(int)` + the matching constant/getter.
No existing public signature changes; `ShardStats` stays exactly as frozen in B4.

### 2.1 `RingMetrics` (frozen record)

```java
public record RingMetrics(
        long sqesPrepared,        // successful getSqe() allocations (SQEs taken from SQ ring)
        long submitted,           // SQEs the kernel has consumed (SQ khead progress, owner-observed)
        long reaped,              // CQEs consumed by the library (CQ khead progress; incl. failed)
        long completed,           // reaped CQEs with result >= 0
        long failed,              // reaped CQEs with result < 0
        long cancelled,           // reaped CQEs with result == -ECANCELED
        long timedOut,            // reaped CQEs with result == -ETIMEDOUT
        long inFlight,            // max(0, submitted - reaped)
        long enters,              // io_uring_enter() calls (submit/wait/poll paths)
        long submitBatches,       // submit()/submitAndWait*() calls that flushed a non-empty SQ tail
        long submitErrors,        // enter() failures not classified as retries (e.g. EBUSY); exceptions may still propagate
        long submitRetries,       // enter() EAGAIN/EINTR retries that re-queued the same SQEs
        long sqFullEvents,        // getSqe() returned 0 because the SQ ring was full
        long sqQueued,            // snapshot: prepared - kernel-consumed (SQ occupancy; live read)
        int  sqEntries,           // effective SQ ring depth
        long cqReady,             // snapshot: CQEs available to reap (live read)
        int  cqEntries,           // effective CQ ring depth
        long cqOverflowEvents) {  // snapshot: kernel cq.koverflow (u32, CQ-overflow events; best effort)
}
```

Frozen invariants (for snapshotting on the ring owner thread; cross-thread snapshots are
best effort, see §5):

- all counters are monotone non-decreasing for the ring's lifetime;
- `reaped == completed + failed`, `cancelled <= failed`, `timedOut <= failed`;
- `inFlight == max(0, submitted - reaped)`;
- `sqQueued >= 0`, `0 <= sqQueued <= sqEntries` while open; `cqReady >= 0` while open;
- **after close** the snapshot is frozen: counters keep their final values, `sqQueued` and
  `cqReady` are reported as `0` (the ring memory is unmapped), `sqEntries`/`cqEntries`
  keep their effective values.
- Ring counters count **every** SQE/CQE on the ring, including runtime-internal ops
  (`IoShard`'s eventfd wakeup `READ` and ring-wide shutdown cancel). This is deliberate:
  they are the kernel-level truth for the ring. User-level shard counters stay in
  `ShardStats` (B4) and in `ShardMetrics`, and the JMX/JSON naming makes the distinction
  explicit (`ring_*` vs shard/task/completion counters).
- `sqesPrepared`, `sqFullEvents`, the completion classification, `enters`, `submitBatches`,
  `submitErrors` and `submitRetries` are maintained on the ring owner thread only.
  `submitted`/`reaped`/error totals are updated from owner-thread observations; they may
  lag the kernel by one loop pass and must never be interpreted as exact at snapshot time.
  64-bit counters are written plain by the owner and read with acquire semantics from
  other threads (§5) so snapshots never tear.

### 2.2 `LatencySnapshot` + `LatencyHistogram` (frozen)

```java
public record LatencySnapshot(
        long samples,        // recorded observations
        long totalNanos,     // exact sum of recorded values
        long minNanos,       // exact (0 when samples == 0)
        long maxNanos,       // exact (0 when samples == 0)
        long p50Nanos, long p90Nanos, long p99Nanos, long p999Nanos) {
    public static LatencySnapshot empty();   // all zero
    public double meanNanos();               // totalNanos / samples, 0 when samples == 0
}

public final class LatencyHistogram {
    public LatencyHistogram();          // 512 buckets, 0..2^32-1 ns, 16 sub-buckets/octave
    public void record(long nanos);     // negative clamps to 0; > 2^32-1 clamps to max
    public LatencySnapshot snapshot();  // percentiles as bucket UPPER bounds (see contract)
    public void reset();                // zeroes the buckets and min/max/sum/count
    public long count();                // recorded observations
    public void merge(LatencyHistogram other); // add other's buckets/stats (off hot path; merge into a scratch histogram)
}
```

**Resolution / accuracy contract (frozen):** values are bucketed log-linearly with 16
sub-buckets per octave over `[0, 2^32-1]` ns (16 sub-buckets are exact 0..15 ns, then the
octaves begin at 16 ns). `p50/p90/p99/p999` are reported as the **upper bound of the
bucket** containing the requested quantile, i.e. an over-estimate of at most one bucket
width: relative error `<= 1/16` (6.25 %) for values `>= 16 ns`, better for smaller values;
exact when the value falls on a bucket upper bound (e.g. 16, 32, 48 ...). `min/max/sum/count`
are exact over recorded observations. Empty snapshot = all zeroes. `record()` performs no
allocation; `snapshot()` may allocate (called off the hot path).

`LatencyHistogram` is **not** a general-purpose multi-writer structure: record from one
thread (the owner), snapshot/merge from any thread as a best-effort telemetry view.

### 2.3 `JUring.ringMetrics()` (frozen)

```java
/** Immutable snapshot of this ring's counters and occupancy. Readable from any thread,
 *  before/while open and after close; never throws for a closed ring. */
public RingMetrics ringMetrics();
```

- Before first use: all counters zero, occupancy zero, entries = effective ring sizes.
- While open: any thread; best-effort snapshot (§5). Does **not** call `ensureOpen()` and
  does not throw for a closed ring (unlike native-touching B1 paths); after `close()` it
  returns the final frozen snapshot (§2.1).
- Each `JUring` (including wrappers returned by `getSharedWorkerRing`) reports the
  counters of its own dispatcher/ring object.
- The raw `threads` benchmark runtime uses this API for its per-worker ring metrics.

### 2.4 `ShardOptions` addition (frozen)

```java
public static final int DEFAULT_LATENCY_SAMPLE_EVERY = 0;   // disabled by default

/** 0 = disabled; 1 = measure every registered completion; N >= 2 = measure every Nth
 *  successful onCompletion registration (deterministic, 1-based). */
public int latencySampleEvery();
public ShardOptions withLatencySampleEvery(int latencySampleEvery); // IllegalArgumentException if < 0
```

Default is **off**: the latency timestamping branch is not taken and the histogram is not
allocated. All other B4 options are unchanged; `toString()` includes the new field.

### 2.5 `ShardMetrics` + `IoShard.metrics()` (frozen)

```java
public record ShardMetrics(ShardStats stats, RingMetrics ring, LatencySnapshot dispatchLatency) {}

public final class IoShard implements AutoCloseable {
    // ... B4 surface unchanged ...
    /** Snapshot of lifecycle/counters (B4), ring metrics (B5) and dispatch latency (B5).
     *  Safe from any thread, before start, while running and after close; never throws. */
    public ShardMetrics metrics();
}
```

- `dispatchLatency` is the histogram of the shard's **completion-dispatch latency**:
  elapsed time from a successful `ShardContext.onCompletion(userData, handler)` registration
  to the dispatch of the matching completion to that handler, measured on the shard thread.
  Window includes the runtime's queueing/batching after the kernel completion; it excludes
  SQE preparation and the handler body itself (recorded immediately before invoking the
  handler). Cancelled/close-dispatched completions that have a live registration are
  measured too. Registrations that are never dispatched (teardown) are not recorded, so
  `dispatchLatency.samples() <= stats().completionsDispatched()`.
- Sampling: `sampleEvery == 0` → `LatencySnapshot.empty()`; `N >= 1` → every Nth
  successful registration (registration counter 1, 2, 3 ...; timed registrations are
  `N, 2N, 3N, ...`). Sampling is deterministic and per shard.
- Body-hosted shards (`JUringBlocking` integration; `ShardBody` loop) have no generic
  completion table, so their `dispatchLatency` is always `LatencySnapshot.empty()` and the
  B4 "body-hosted shard counters stay 0" disclosure still applies; `ring` metrics are
  still real (the body's ring is the shard's ring).
- `IoShard.metrics()` never throws, including after a failed start or after close; the
  `stats` component equals `stats()` at the same instant (two snapshots, not atomic with
  each other).

### 2.6 `GroupMetrics` + `RingGroup` additions (frozen)

```java
public record GroupMetrics(
        int shards,
        long submitted, long reaped, long completed, long failed,
        long cancelled, long timedOut, long inFlight,
        long sqQueued, long cqReady,
        LatencySnapshot dispatchLatency) {}

public final class RingGroup implements AutoCloseable {
    // ... B4 surface unchanged ...
    /** Per-shard metrics in shard-id order (unstarted/closed shards included). Never throws. */
    public java.util.List<ShardMetrics> metrics();
    /** Sum of the per-shard ring counters + merged dispatch latencies. Never throws. */
    public GroupMetrics aggregateMetrics();
}
```

Aggregation rules: counters are summed over shards; `inFlight`, `sqQueued`, `cqReady`
are summed snapshots; `dispatchLatency` is the bucket-wise merge of the shard histograms
(exact merging of bucket counts, percentiles per the §2.2 contract). A group of unstarted
shards aggregates to zeroes/empty latency.

### 2.7 JMX binding (frozen)

```java
public interface IoShardMetricsMXBean {
    int getShardId(); String getThreadName(); int getCurrentCpu(); boolean isAffinityApplied();
    boolean isStarted(); boolean isStopping(); boolean isClosed();
    // ring counters/occupancy: sqesPrepared, submitted, reaped, completed, failed,
    // cancelled, timedOut, inFlight, enters, submitBatches, submitErrors, submitRetries,
    // sqFullEvents, sqQueued, sqEntries, cqReady, cqEntries, cqOverflowEvents
    // shard counters: tasksSubmitted, tasksExecuted, taskFailures, tasksRejected,
    // completionsRegistered, completionsDispatched, completionsUnattributed,
    // completionFailures, handoffsSent, handoffsInlineFallback, eventfdWrites, ringSubmits,
    // cancelledAtClose, abandonedAtClose, closeTimedOut
    // dispatch latency (microseconds; 0 when disabled/empty): latencySamples,
    // latencyMeanMicros, latencyP50Micros, latencyP90Micros, latencyP99Micros,
    // latencyP999Micros, latencyMaxMicros, latencyMinMicros
    // Accessor names follow the JavaBean convention: getSubmitted(), getCqReady(), ...
}

public interface RingGroupMetricsMXBean {
    int getShards();
    // summed ring counters: submitted, reaped, completed, failed, cancelled, timedOut,
    // inFlight, sqQueued, cqReady
    // merged dispatch latency in microseconds: latencySamples, latencyMeanMicros,
    // latencyP50Micros, latencyP90Micros, latencyP99Micros, latencyP999Micros,
    // latencyMaxMicros, latencyMinMicros
}

public final class MetricsJmx {
    public static final String DOMAIN = "com.davidvlijmincx.lio.api";
    public static Handle register(IoShard shard);   // one shard bean
    public static Handle register(RingGroup group); // one group bean + one bean per shard
    public interface Handle extends AutoCloseable {
        javax.management.ObjectName name();          // the shard/group bean of this handle
        java.util.List<javax.management.ObjectName> names(); // all beans registered by this handle (group first)
        void close();                                // unregister all; idempotent
    }
}
```

**ObjectName scheme (frozen):**

- group: `com.davidvlijmincx.lio.api:type=RingGroup,instance=<N>` where `<N>` is a
  monotonically increasing per-JVM registration instance id;
- shards of a registered group: `com.davidvlijmincx.lio.api:type=IoShard,group=<N>,id=<shardId>`;
- standalone shard (`register(IoShard)`): `com.davidvlijmincx.lio.api:type=IoShard,instance=<N>,id=<shardId>`.

**Semantics (frozen):** registration uses
`ManagementFactory.getPlatformMBeanServer().registerMBean(bean, name)`; attributes are
**pull-based**: each `getX()` calls the corresponding `metrics()`/`stats()` snapshot at
read time, so there is no exporter thread and no background memory cost. `close()` is
idempotent and unregisters every bean in the handle. Registering the same shard/group
twice (two live handles) is an `IllegalStateException` ("already registered"); a failed
JMX registration is an `IllegalStateException` carrying the cause (e.g. a restrictive
`MBeanServer`), and the partially registered beans of a failed group registration are
unregistered again. Attribute reads never throw for closed/failed shards (snapshots are
total); units are counters as longs, latency in **microseconds** as `double`, booleans as
booleans. `Handle.names()` returns an immutable list in registration order (group bean
first for `register(RingGroup)`).

### 2.8 `module-info.java` (frozen)

Add `requires java.management;` (JMX API used by `MetricsJmx`/`*MXBean`). Keep
`requires static jdk.management;` untouched.

---

## 3. Counter semantics (frozen definitions)

| metric | definition | update point (implementation hint) |
|---|---|---|
| `sqesPrepared` | SQEs taken from the SQ ring | every successful `getSqe()` (owner) |
| `submitted` | SQEs the kernel has consumed | SQ `khead` progress observed after `enter()`/flush (owner) |
| `reaped` | CQEs consumed / CQ `khead` advanced | `cqAdvance` + batch reap paths (owner) |
| `completed` | reaped with `res >= 0` | completion classification (owner) |
| `failed` | reaped with `res < 0` | completion classification (owner) |
| `cancelled` | reaped with `res == -ECANCELED` (125) | completion classification (owner) |
| `timedOut` | reaped with `res == -ETIMEDOUT` (110) | completion classification (owner) |
| `inFlight` | `max(0, submitted - reaped)` | derived at snapshot |
| `enters` | `io_uring_enter` calls | every `enter()` invocation (owner) |
| `submitBatches` | flush calls with a non-empty SQ tail | `submit()`/`submitAndWait*()`/batch paths (owner) |
| `submitErrors` | enter failures that are not EAGAIN/EINTR retries | error branches (owner) |
| `submitRetries` | EAGAIN/EINTR retries | retry branches (owner) |
| `sqFullEvents` | `getSqe()` returned 0 (SQ full) | `getSqe()` (owner) |
| `sqQueued` | `sqeTail - SQ khead` | snapshot (live read) |
| `cqReady` | `CQ ktail - CQ khead` | snapshot (live read) |
| `cqOverflowEvents` | kernel `cq.koverflow` (u32) | snapshot (live read) |

Placement requirements: no per-op allocation; classification must be branch-cheap
(a negative `res` is the uncommon path in clean runs but must still be counted); counters
must be updated in **every** CQE consumption path, including the bounded-close drain and
the raw-CQE helpers, so `reaped == completed + failed` holds after close.

Ring counters do **not** include a histogram; latency is a shard-runtime metric (§2.5).

## 4. Latency measurement (frozen semantics, internal algorithm free)

- Window: registration (`ShardContext.onCompletion`) → handler dispatch.
- Sample selection: deterministic per-shard counter, every Nth successful registration
  when `latencySampleEvery = N > 0`. Non-sampled registrations pay one counter increment
  and no timestamp (the implementation must not call `System.nanoTime()` for them).
- Histogram: one `LatencyHistogram` per shard, allocated only when sampling is enabled
  (>= 1). Recording happens on the shard thread; snapshotting from any thread is a
  best-effort telemetry read (§2.2/§5).
- Timestamps are stored alongside the completion registration (no new table); completion
  removal must keep registration/timestamp bookkeeping correct under the B4 backward-shift
  deletion + F1 full-table rules. A registration that fails (`IllegalStateException`) does
  not consume a sample and does not leave a timestamp behind.
- Handler exceptions still record the sample (recorded before invoking the handler).
- Cancellation/timeout/close-dispatched completions with a live registration are measured
  like any other completion (the window is the same); internal wakeup/cancel ops never
  reach the handler path and are never measured.
- `LatencySnapshot.empty()` for: disabled sampling, body-hosted shards, and shards that
  never dispatched a timed registration.

## 5. Threading and snapshot safety (frozen)

- Counter writes happen on the **ring owner thread** only (raw `JUring`: the creating/
  submitting thread; `IoShard`: the shard thread). `ringMetrics()`/`IoShard.metrics()` /
  `RingGroup.metrics()`/`aggregateMetrics()` may be called from any thread at any life
  stage, including during traffic and after close. They never block on the owner thread and
  never wait for the loop.
- Snapshots are **best effort, not linearizable**: cross-thread reads of 64-bit counters
  use acquire semantics / safe publication so values never tear; combined views
  (`stats` + `ring` + `latency` taken by separate snapshots) may be mutually inconsistent
  by a few events. This is documented, not a bug; metrics are for trend/straggler detection
  and M3 reporting, not for correctness assertions about application state.
- Occupancy reads (`sqQueued`, `cqReady`, `cqOverflowEvents`) read the shared io_uring
  memory while the ring is open; after close they are 0 (frozen). Reading them must be
  guarded so a closed/uninitialised ring cannot crash (`ringMetrics()` returns the cached
  final snapshot after close).
- Histogram `record()` is owner-thread only; `snapshot()`/`merge()` from foreign threads
  are best effort (atomic element reads; no tearing) and are only used for observability.
- No new locks on hot paths; no new threads (JMX is pull-based, benchmark sampling is the
  benchmark's own thread).

## 6. JMX semantics (frozen)

- One `MBeanServer` = `ManagementFactory.getPlatformMBeanServer()`; local registration
  only; no remote connector, no auth setup.
- Handles: `register(IoShard)` / `register(RingGroup)`; unregister by `handle.close()`
  (idempotent). Two handles for the same shard/group are rejected (§2.7). Registration of
  a group registers the group bean first, then the shard beans; a mid-way failure rolls
  back everything already registered by that call.
- Attribute reads are pull-based snapshots and never throw (including after close);
  unregistered beans disappear from the server. Attribute names/types are listed in §2.7;
  latency values are microseconds.
- The binding must not leak references after close: a closed handle unregisters the beans,
  and the bean objects hold a reference to the shard/group only until unregistered.
- JMX is optional at runtime in spirit (a user who never calls `MetricsJmx` pays nothing
  and does not create a `MBeanServer` reference).

## 7. Benchmark integration contract (frozen)

`bench/juring/JUringFileBench.java` (compiled javac-only against `target/classes`, no
external deps) emits **schema 1.1**: all 1.0 fields stay, new fields are additive.

- New CLI: `--metrics on|off` (default **on** for both runtimes) and
  `--metrics-interval SEC` (default `1.0`; `0` disables the series). `--runtime=shard`
  enables shard latency sampling (`withLatencySampleEvery(1)`) when metrics are on.
- Per worker (both `threads[]` and `shards[]` entries):
  - `"ring_metrics": { ... every RingMetrics field ... }` (snake_case: `sqes_prepared`,
    `submitted`, `reaped`, `completed`, `failed`, `cancelled`, `timed_out`, `in_flight`,
    `enters`, `submit_batches`, `submit_errors`, `submit_retries`, `sq_full_events`,
    `sq_queued`, `sq_entries`, `cq_ready`, `cq_entries`, `cq_overflow_events`).
  - `"ring_series": [{"t_sec": ..., "in_flight": ..., "sq_queued": ..., "cq_ready": ...,
    "dispatch_p99_us": ...}]` sampled during the measured phase (shard mode; threads mode
    has `dispatch_p99_us` omitted). The series is produced by one sampler thread reading
    the cross-thread snapshot API; sampling starts at measured-phase start and stops at
    measured-phase end.
  - shard mode only: `"dispatch_latency_us": {samples, mean, min, max, p50, p90, p99,
    p999}` from the library `LatencySnapshot` (distinct from the external `latency_us`).
- Top-level `runs[]` entry: `"metrics": {"enabled": bool, "interval_sec": double,
  "ring_series_points": N}`; aggregate: summed ring counters per run mean and
  `"ring_queue_depth": {mean_in_flight, max_in_flight, mean_sq_queued, mean_cq_ready}` +
  `"dispatch_latency_us"` (mean of per-run percentiles, same style as `latency_us`).
- `bench/shard/analyze_shard.py` consumes the new fields (tolerating their absence):
  `aggregate/shard.md` gains per-ring **queue depth** and **p99** columns (library
  dispatch p99 + external p99), engine/cell spread, error/cancel counts; `shard.json`
  carries the raw aggregates. This is the M3-report data source for "per-ring queue depth
  and p99".
- The benchmark's external `latency_us` remains the comparison metric for raw threads mode;
  the library `dispatch_latency_us` is reported separately and never conflated with the
  external definition.
- JSON stays valid with no trailing garbage; one object on stdout; `schema_version`
  becomes `"1.1"`. Old consumers must ignore unknown fields (additive).

### 7.1 Overhead A/B protocol (frozen bars)

- **Arms:** `B4` = build of commit `6cc1a75` (no B5 metrics; baseline) and `B5` = the
  frozen B5 tree with metrics **on**. Both built from the same host/JDK/flags; fresh
  JVM/process per invocation; identical ring flags, QD, CPU placement, dataset, seed and
  protocol. Baseline classes are built in a scratch worktree (`git archive 6cc1a75`),
  never by editing the workspace.
- **Cells:** shard N=1, shard N=8, threads N=1 — 4K randread QD64/worker, CPUs 1..N,
  one shared aged 10 GiB read-only file, 10 s warmup + 30 s measured, **R=5**,
  **30 s rest** between invocations, arms interleaved and arm order alternated per rep,
  every rep kept, state gate disclosed (p99 + IOPS anomaly rule from
  `docs/benchmark-methodology.md`).
- **Metric:** paired per-rep ratio `throughput(B5)/throughput(B4)` for the same
  cell/rep/position. Primary: **mean paired ratio >= 0.99 in every cell**
  (i.e. < 1 % throughput cost). Report: per-rep ratios, stdev, one-sided 95 % CI
  (`t` with df = 4), state flags. PASS also requires no individual rep below 0.95 unless
  it is state-gated and disclosed (the driver keeps all reps, no trimming).
- If the primary bar fails, reduce the sampling cost (`latencySampleEvery` 8, and/or
  series interval 2 s) and re-run once; report both configurations and use the cheapest
  configuration that still yields per-ring p99 for M3. The report must state the final
  configuration and the measured cost.
- Zero-allocation evidence: `B5RingMetricsTest`/`B5ShardMetricsTest` measure
  `ThreadMXBean.getCurrentThreadAllocatedBytes` over hot loops (metrics on) → 0 bytes/call
  for the recording paths; snapshots allocate only when called.
- The official session lives in `bench/results/b5-overhead-<date>/` (gitignored) and is
  analyzed by `bench/shard/analyze_b5_overhead.py`; the report cites the session.

## 8. Error taxonomy / numeric conventions

- Counts are `long`, non-negative; percentages/means are `double` in JSON and micros in
  JMX. All latency internals are nanoseconds (`System.nanoTime` deltas, monotone within a
  JVM run).
- `-ECANCELED = -125`, `-ETIMEDOUT = -110` are the only special-cased negative results;
  any other negative is `failed` only (no per-errno map in B5).
- Zero/empty conventions: `LatencySnapshot.empty()` is all zeroes; JMX latency doubles are
  `0.0` when no samples; occupancy after close is `0`; a never-started ring reports
  counters = 0, entries = effective ring depth.
- Counter overflow is not defended (2^63 events ≈ 10^13 years at 470k IOPS); documented.

## 9. Micrometer decision and binding seam (frozen)

B5 ships **JMX only** as the built-in binding and a documented Micrometer seam:

- `RingGroup.metrics()` / `IoShard.metrics()` / `JUring.ringMetrics()` are immutable,
  allocation-light snapshots; a user can sample them on any cadence and register gauges
  with any metrics library:
  ```java
  // Consumer-side Micrometer adapter (NOT shipped; no hard dependency):
  Gauge.builder("juring.shard.in_flight", group, g -> g.aggregateMetrics().inFlight())
       .tags("group", name).register(registry);
  ```
- Rationale: the project is library-first and currently has zero runtime dependencies;
  adding Micrometer (and its transitives) to `pom.xml` for every JUring user is a
  product decision for B9 (API stability) / the flagship framework, not a benchmark
  necessity. M3 needs per-ring queue depth and p99, which the snapshot API + JMX + JSON
  provide.
- A future optional `juring-micrometer` module is explicitly out of B5 scope; the seam is
  the frozen snapshot types, so it can be added without breaking B5 APIs.

## 10. Known limitations / residual risks (disclosure)

1. Ring counters include internal wakeup/cancel ops; shard-level and ring-level counters
   in the JSON/JMX are labelled and must not be summed as if both were user ops.
2. Snapshots are best effort and may lag by one loop pass; `stats`/`ring`/`latency` inside
   `ShardMetrics` are three separate snapshots (not atomic).
3. JMX pull-based attributes have per-read snapshot cost (allocation-light records); very
   high-frequency JMX polling (e.g. > 10 kHz) is the operator's cost, not the hot path's.
4. Histogram percentiles are bucket upper bounds (<= 6.25 % over-estimate for >= 16 ns);
   do not use them for SLO enforcement on single requests.
5. Shard dispatch latency covers the runtime boundary only; it is not end-to-end
   application latency and is not comparable to fio `clat` or the external harness
   definition.
6. Body-hosted (`JUringBlocking`) shards have no dispatch histogram (B4 body seam
   disclosure unchanged).
7. `cqOverflowEvents` is a best-effort kernel counter (u32; `IORING_FEAT_NODROP`
   behaviour); it is not force-triggered in the acceptance path.
8. Overhead is workload-dependent: the < 1 % bar is established for the S7 protocol
   (4K randread, QD64/worker, N=1/8), not for CPU-only microbenchmarks (where added
   per-op branches are a larger relative cost). The overhead session reports exactly the
   measured cells.
9. Metrics are not persisted; M3 report tables are generated from the JSON session.

## 11. Required tests (acceptance matrix — authors + verifier)

Test budget: deterministic JUnit tests, small counts, <= 1 s deadlines where possible;
heavier stress in `scratch/b5-*` standalone harnesses so `mvn test` stays fast.

**`B5RingMetricsTest` (core)**
1. exact `sqesPrepared/submitted/reaped/completed/failed/inFlight` for a known workload
   (e.g. 32 NOPs; 16 file reads; mixed NOP + read) with zero errors; invariants
   `reaped == completed + failed`, `inFlight == max(0, submitted - reaped)`.
2. error accounting: read/fsync on a closed/invalid fd → `failed > 0`, `completed == 0
   modulo clean ops`; a cancelled request → `cancelled`; a timed-out request →
   `timedOut`; invariants preserved.
3. occupancy: prepare K SQEs without submitting → `sqQueued == K`, `submitted == 0`;
   after submit+reap → drain; CQ ready observed between submit and reap; live read
   matches `pendingSqes()` where applicable.
4. enter/batch/retry/SQ-full counters: `sqFullEvents >= 1` after deliberately overfilling
   the SQ; `enters`/`submitBatches` monotone and non-zero; `submitRetries`/`submitErrors`
   non-negative.
5. lifecycle: metrics before start, after close (frozen, occupancy 0, no throw), repeated
   snapshots; `JUring` use-after-close native paths unchanged (B1 regression).
6. cross-thread: foreign thread samples while an owner does I/O; cumulative counters never
   decrease and never tear (non-negative); no exception.
7. zero-allocation: metrics-on hot loop (`prepareNop` + `submitAndWait`) →
   `getCurrentThreadAllocatedBytes` delta 0 B/call (same style as B4 evidence).

**`B5LatencyHistogramTest` (core)**
8. exact count/sum/min/max/mean; empty snapshot zeroes; reset.
9. percentile correctness vs brute-force sorted array over mixed values (0, 1, 15, 16,
   17, 100, 1k, 1M, 1G ns): reported pX is an upper bound within one bucket width and
   never below the true quantile value; monotone p50 <= p90 <= p99 <= p999 <= max.
10. boundaries: negative clamps to 0; 2^32-1 accepted; > 2^32-1 clamps to max; merge
    equals merged bucket counts; snapshot immutability.
11. no allocation per `record()` (measured over many records).

**`B5ShardMetricsTest` (runtime)**
12. `ShardMetrics` fields present/consistent before start, during load, after close;
    per-shard isolation (N=4 shards, known NOP counts → ring counters reflect each shard,
    no cross-shard leakage); `ring.reaped >= dispatch counts`.
13. dispatch latency: disabled → empty; enabled every=1 → samples equal dispatches after
    a drained NOP/read workload (within the documented `<=` bound), p50 <= p99, values
    plausible (> 0); enabled every=4 → exactly the sampled registrations
    (`ceil(dispatched/4)` for a drained run).
14. group metrics: `metrics().size() == size()` in id order; `aggregateMetrics()` sums
    equal the component-wise sums; aggregate latency samples equal the sum; unstarted
    group aggregates to zero/empty.
15. no-throw/any-thread: sampler thread reads `IoShard.metrics()`/`RingGroup.metrics()`
    during a burst; closed shard metrics readable; body-hosted shard (via
    `JUringBlocking(RingGroup)`) reports `dispatchLatency.empty()` and real ring metrics.
16. zero-allocation: metrics-on handler path (registration + dispatch) allocates
    0 B/call (histogram enabled), snapshot may allocate.

**`B5JmxTest` (runtime)**
17. register(IoShard): bean visible via `MBeanServer`, attribute values match
    `metrics()` (counters + latency micros), `handle.name()` pattern, close unregisters,
    double close no-op, duplicate registration throws, reads after shard close OK.
18. register(RingGroup): group bean + one bean per shard, `names()` order/content,
    aggregate attributes equal sums, close cleans all; two independent groups get distinct
    instance ids; mid-flight load reads never throw; disabled latency attributes are 0.
19. no thread/fd leak across register/unregister cycles (poll thread/fd counts stable).

**Regression:** all 309 existing tests unchanged and green (B1/B2/B3/B4 not weakened).

**Independent verifier (`scratch/b5-verify/`)** — must re-derive, not just re-run:
its own copy/classpath (never reuse `target/classes`), fresh JVM + external timeouts, and
an adversarial matrix covering: counter exactness under mixed/parallel/cancelled/timed-
out/close-with-inflight workloads; a brute-force or HdrHistogram percentile oracle;
sampling determinism; snapshot races (no tearing/negative/throw); use-after-close from
every metrics entry point; JMX registration/unregistration/duplication/leak loops and
attribute reads during load and after close; zero-allocation checks; overhead smoke
(its own small A/B is optional if the official session exists); full `mvn -o test`;
frozen-tree hash re-derivation; `git diff` scope check. Verdict file:
`scratch/b5-verify/VERDICT.md` with PASS/FAIL and residual risks.

## 12. Acceptance / definition of done

1. `docs/b5-design.md` (this file) frozen before implementation; deviations via Lead
   amendments.
2. All §2 APIs implemented exactly; all §11 JUnit tests green; **full `mvn -o test`:
   309 existing + new, 0 failures, 0 errors, 1 skipped** (B4 skipped test unchanged).
3. Benchmark JSON 1.1 contains per-ring metrics + series + dispatch latency for both
   runtimes; `analyze_shard.py` emits per-ring queue depth and p99; a smoke session is
   archived (gitignored) and cited.
4. Overhead A/B session per §7.1: PASS on the frozen tree (or a disclosed fallback
   configuration); report cites per-rep data and the CI.
5. Independent verification **PASS** on the frozen tree, with `scratch/b5-verify/VERDICT.md`
   and frozen hashes.
6. `docs/b5-report.md` (deliverable), `docs/HANDOVER.md` new §0.0, `SEASTAR-PLAN.md`
   v1.4 (B5 delivered; M3 still pending: campaign + restated bars).
7. Git: feature commit (code/tests/bench/design) + docs commit (report/handover/plan),
   branch `kafka-engine`, no unrelated changes; final Lead suite green on the committed
   tree. **M3 is not signed off by B5** (campaign still required).

## 13. Write scopes and parallel plan (advisory; disjointness is the rule)

| Workstream | Write scope |
|---|---|
| **b5-core** | `src/main/java/com/davidvlijmincx/lio/api/{RingMetrics,LatencySnapshot,LatencyHistogram,IoUringDispatcher,JUring}.java` (two latter only the metrics instrumentation/frozen accessor), new `src/test/java/.../B5RingMetricsTest.java`, `B5LatencyHistogramTest.java`; `scratch/b5-core/**` |
| **b5-runtime** | `src/main/java/.../{ShardOptions,CompletionTable,IoShard,RingGroup,ShardMetrics,GroupMetrics,IoShardMetricsMXBean,RingGroupMetricsMXBean,MetricsJmx}.java`, `src/main/java/module-info.java`, new `src/test/java/.../B5ShardMetricsTest.java`, `B5JmxTest.java`; `scratch/b5-runtime/**` |
| **b5-bench** | `bench/juring/JUringFileBench.java`, `bench/shard/**` (analyzer/drivers/README), `bench/README.md`; `scratch/b5-bench/**`; results in gitignored `bench/results/**` |
| **b5-verify** | `scratch/b5-verify/**` only (no `src/`, no `docs/`, no `bench/`, no `pom.xml`) |
| **Lead** | `docs/b5-design.md`, `docs/b5-report.md`, `docs/HANDOVER.md`, `SEASTAR-PLAN.md`, `scratch/b5-lead/**`, final `target/` builds, git commits |

**Gates (serialised where noted):** G1 core compiles + core tests green; G2 runtime
integrates + runtime tests green (authors may run `mvn` only from their own scratch copy
of the tree, never in the repo root, to avoid `target/` races); G3 Lead integrates, full
suite green, code freeze + content hashes; G4 official overhead A/B session (bench alone
on the machine, no other heavy load/Maven); G5 independent verifier matrix + its own full
suite (after G4); G6 Lead final suite on the frozen tree; G7 docs + feature/docs commits.
Bench harness authoring and verifier harness authoring proceed in parallel; their
**execution** waits for their gates. If the overhead session must be re-run, the verifier
waits (message from Lead).

## 14. Definition of done for B5

- [ ] frozen design (this file), no unamended public-signature drift
- [ ] §2 APIs implemented; §11 tests green; full suite 309 + new green
- [ ] bench JSON 1.1 + analyzer queue-depth/p99 columns + smoke session evidence
- [ ] overhead A/B PASS (or disclosed fallback) with per-rep data/CI in the report
- [ ] independent verification PASS + frozen hashes (`scratch/b5-lead/FREEZE.md`,
      `scratch/b5-verify/VERDICT.md`)
- [ ] `docs/b5-report.md`, HANDOVER §0.0, SEASTAR-PLAN v1.4 updated; M3 explicitly still
      not signed
- [ ] feature + docs commits on `kafka-engine`; final full suite green on the committed
      tree

---

## 15. Amendments (frozen; appended, never silently edited)

### A1 — 2026-11-14 (session 7, pre-implementation; raised by b5-verify)

**A1.1 `timedOut` errno (fixes §2.1/§8/§11.2).** The kernel's io_uring timeout completion
is `-ETIME = -62` (verified in `docs/b3-design.md`: a raw `IORING_OP_TIMEOUT` completes
with `-62`; only the `JuringFile` API layer maps it to `ETIMEDOUT = 110`). Raw-ring
`RingMetrics.timedOut` therefore counts reaped CQEs whose result is **`-ETIME (-62)` or
`-ETIMEDOUT (-110)`** (both, defensively; the mapping layer never reaches raw ring
metrics, so in practice this is -62). `cancelled` remains `-ECANCELED (-125)`. §8/§11.2
read as "timed-out request" = a `-ETIME` (or `-ETIMEDOUT`) completion.

**A1.2 `LatencyHistogram` bucket layout and reported value (fixes §2.2).** Pinned layout:
bucket 0 is the exact value 0; buckets 1..15 are the exact values 1..15 ns. For values
`v >= 16`: `mag = floor(log2(v))` (so `mag >= 4`), `width w = 2^(mag-4)`,
`base = 2^mag`, `sub = (v - base) / w` (0..15), index `= 64 + (mag-4)*16 + sub`; that
bucket covers the inclusive range `[base + sub*w, base + sub*w + w - 1]` (511 buckets are
used: 0..15 exact and 64..511; 16..63 are unused padding). Snapshot percentiles report the
**largest value in the containing bucket** (inclusive bucket maximum), i.e. the first
bucket whose cumulative count reaches `ceil(q * samples)`. Consequences: values `0..15`
and any value that is a bucket maximum are exact; for `v >= 16` the reported value is
`>= true value` and `< v + w`, hence a relative over-estimate strictly below `1/16`
(worst case at the bucket base: `w / 2^mag = 1/16`). The design's earlier example "16, 32,
48 exact" is corrected: 16 and 31 are exact in the `[16,32)` octave (width 1); 32 is the
base of the `[32,64)` octave whose first bucket is `[32,33]`, so 32 reports 33 and 33 is
exact. Tests/verifier assert: reported `>=` the true quantile value, relative
over-estimate `<= 1/16` for true values `>= 16`, exact for values `<= 15`, plus the exact
min/max/sum/count guarantees. (The JMX/JSON percentile contract is otherwise unchanged.)

**A1.3 never-started `IoShard.metrics()` (fixes §2.5).** A shard whose ring has never been
created (never started, or failed before ring creation) reports `ring` counters = 0,
occupancy = 0, `sqEntries = cqEntries = 0` (there is no effective ring yet) and
`dispatchLatency = LatencySnapshot.empty()`; `stats` keeps the B4 unstarted semantics. After
close, §2.1's frozen-snapshot rule applies (counters frozen, occupancy 0, effective
entries retained).

### A2 — 2026-11-14 (session 7, pre-freeze; sampling count + latency-label clarification)

**A2.1 Sampling counts (fixes §11 test 13 wording).** Sampling is applied to the 1-based
counter of *successful* `onCompletion` registrations: timed registrations are indices
`N, 2N, 3N, ...` when `latencySampleEvery = N` (`N = 1` times every registration); a failed
registration (duplicate/full-table ISE) consumes no index and leaves no timestamp. For a
fully drained workload with `R` successful registrations, the number of timed registrations
is **`floor(R / N)`** (not `ceil`), and `dispatchLatency.samples() <= floor(R / N)` because
registrations that are never dispatched (teardown) record nothing. Tests/verifier assert
`floor(R/N)` for drained deterministic runs.

**A2.2 `dispatchLatency` is a runtime-boundary completion latency.** The window starts at
`ShardContext.onCompletion` registration, which happens *before* the shard loop submits the
SQE, so the measured value includes the kernel/device completion time plus the runtime's
queueing/dispatch after completion; it excludes SQE preparation and the handler body. It is
therefore a per-op **completion latency at the runtime boundary** — the M3 "per-ring p99"
and the JSON `dispatch_latency_us` — and it is NOT a pure dispatch-overhead metric and NOT
comparable to fio `clat` or to the external harness `latency_us` (which starts before SQE
preparation). The frozen names stay; report prose must label it "runtime completion latency
(registration→dispatch)".

### A3 — 2026-11-14 (session 7, during independent verification; raised by b5-verify)

**A3.1 Reported percentile = `min(bucket inclusive maximum, exact recorded maxNanos)`
(fixes the A1.2 example wording).** The percentile scan reports the inclusive bucket
maximum of the quantile bucket, clamped to the exact recorded `maxNanos` so the frozen
monotonicity guarantee `p50 <= p90 <= p99 <= p999 <= max` (design §2.2/§11.9) always holds
when the sampled set's maximum lies inside the quantile's bucket. Consequences:

- `histogram {32}` (recorded max 32): p50 = **32** (bucket maximum 33 clamped to 32);
  `histogram {32, 32, 5000}`: p50 = **33** (recorded max 5000 > 33, so the bucket maximum
  applies). A1.2's "32 reports 33" example therefore holds whenever the recorded maximum is
  greater than 32 (i.e. when the bucket maximum is not clamped away).
- All other guarantees are unchanged and hold in both cases: `min/max/sum/count` exact;
  values 0..15 and any bucket maximum that is a recorded sample are exact; reported
  percentile is `>=` the true quantile value; the relative over-estimate is strictly below
  `1/16` for true values `>= 16` when not clamped (exact when clamped); monotonicity
  `p50 <= p90 <= p99 <= p999 <= max`.

No implementation change was required (the frozen tree already clamps this way); this
amendment records the intended contract precisely for the tests, the verifier oracle and
future sessions.
