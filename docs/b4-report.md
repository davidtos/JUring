# B4 report — multi-ring / shard runtime (`IoShard`, `RingGroup`)

**Status:** DRAFT (session 6, 2026-11-14) — to be finalised after independent verification
and the preliminary scaling session. **M3 is not signed off by this report** (B5
observability + a full W17-W20 campaign incl. seastar multi-shard remain).
**Contract:** [`docs/b4-design.md`](b4-design.md) (frozen). **Plan:** `SEASTAR-PLAN.md`
§4 B4 / §5 M3. **Baseline tree:** `kafka-engine` at `25c664b` + this session's changes.

## 0. TL;DR

**B4 is delivered and independently verified (PASS) on the frozen tree; M3 is NOT signed
off.** The thin L2 runtime now exists: `IoShard` (one `JUring` ring owned by a dedicated,
optionally CPU-pinned platform thread; batched poll loop; eventfd wakeup READ; completion
dispatch; bounded shutdown; per-shard stats) and `RingGroup` (fixed ring pool, RR/hash
distribution, shared-deadline close), plus MPSC+eventfd cross-thread completion handoff and
a package-private loop-body seam that re-homes `JUringBlocking` on the shards (zero-GC VT
path kept; **bounded close fixes B3 §4.5**). Public API is frozen in `docs/b4-design.md`.

- **Tests:** 309 tests / 0 failures / 0 errors / 1 skipped on the frozen v2 tree
  (238 B3 + **71 new**); Lead and the independent verifier both ran it.
- **Independent verification:** **PASS** — 105/105 adversarial modes (937 assertions), one
  real defect found before delivery (**F1**: completion-table full-table wedge) and fixed,
  regression-tested and independently re-verified before/after; F2 documentation reconciled.
- **Preliminary W17–W20-style scaling** (R=3, 4K randread, QD64/worker, CPUs 1..N, one
  shared file; **not** M3): `juring-group` 8-shard total IOPS is **98.5 % of `fio numjobs=8`**
  (per-rep 1.041 / 0.922 / 0.996) and **97.3 % of the historical manual-thread pattern**;
  group-1 ≈ fio-1 (0.934, one disclosed slow-state rep), group-2/4 match or beat fio
  (1.007 / 1.013). The plan-M3 `SE(8)>=0.75` and `p99(8)<=1.15*p99(1)` bars are **not
  meaningful for this single-device, constant-QD-per-shard protocol**: the reference `fio`
  itself scores 0.181 / 5.90 and the pre-B4 manual pattern 0.184 / 5.46 (the drive saturates
  at ~470k IOPS by N=2-4) — those bars need restating for M3. The only discriminating
  per-shard bar (`min/mean>=0.85`) missed under the pinned layout (0.802) was traced by a
  follow-up diagnostic to the **NVMe hctx↔CPU topology** (CPUs 1 and 8 own an mq context
  while 2-7 pair on shared contexts), **not** a runtime defect: unpinned group-8 gives
  0.965–0.983 flat per-shard IOPS at the same total. Recommendation for M3: pin one shard
  per hardware queue (`1,2,4,6,8,10,12,14`) or run unpinned, and record `mq/*/cpu_list`.

## 1. What was delivered

| item | status | evidence |
|---|---|---|
| `docs/b4-design.md` frozen contract (API/semantics/tests/acceptance) | DONE | `docs/b4-design.md` |
| `IoShard` (ring-per-thread event loop, affinity, batched poll, stats, bounded close) | DONE | `IoShard.java` + `B4*Test` |
| `RingGroup` (fixed ring pool, RR/hash distribution, group lifecycle) | DONE | `RingGroup.java` + `B4GroupTest` |
| MPSC + eventfd cross-thread completion handoff | DONE | `MpscQueue`, `B4HandoffTest` + verifier |
| CPU affinity (`sched_setaffinity`/`sched_getcpu`) best-effort | DONE | `B4AffinityTest` |
| `JUringBlocking` re-homed on `IoShard` (body seam) + `RingGroup` ctor + bounded close | DONE | `JUringBlocking.java`, 4 `B4Blocking*Test` |
| Required JUnit matrix (design §12) | DONE — 65 new tests (54 core + 11 blocking) | §4 |
| Independent adversarial verification | **PASS** (FINAL, freeze v2) | `scratch/b4-verify/VERDICT.md` |
| Preliminary W17-W20 scaling session | IN PROGRESS (G5) | `bench/results/b4-<date>/` |

## 2. Public API (as frozen in design §2)

New public types: `ShardOptions` (immutable `with*` options), `ShardTask`,
`ShardContext`, `CompletionHandler`, `ShardStats` (25-component record), `IoShard`,
`RingGroup`. `JUringBlocking` gained `JUringBlocking(RingGroup)` (takes ownership; group
must be unstarted) and `close(long timeoutMillis)`; all other public signatures are
unchanged. Package-private support: `MpscQueue` (bounded Vyukov MPSC), `CompletionTable`
(open addressing, no hot-path allocation), `ShardBody`/`ShardLoopContext` (the §9 body
seam), plus minimal additive helpers in `JUring`/`IoUringDispatcher`/`LibCDispatcher`
(wakeup READ with a reserved `user_data`, pending-SQE count, `sched_setaffinity`/
`sched_getcpu` FFM bindings). No existing B1/B2/B3 behaviour was changed.

## 3. Semantics and implementation summary

- **Threading/ownership.** Each `IoShard` owns exactly one `JUring` ring, created on,
  used on and closed on its dedicated daemon platform thread (`juring-shard-<id>-<name>`);
  the ring is the `SINGLE_ISSUER` owner registration for that thread. Tasks, completion
  handlers and (body-hosted) poll loops run on that thread; foreign threads only enqueue
  work (`execute`/`handoff`) and observe lifecycle/stats. Optional CPU pinning is applied
  at thread start (`sched_setaffinity`, best-effort); `ShardStats` records
  requested/actual CPU and whether it was applied.
- **Batched poll loop.** Per pass: reap/dispatch up to `cqeBatch` completions inline, run
  up to `taskBatch` inbound entries, submit pending SQEs once; spin
  `spinIterations` on `onSpinWait`, then arm a wakeup `READ` on the shard `eventfd` and
  block in `io_uring_enter` (`submitAndWait(1)`); because `DEFER_TASKRUN` only posts
  completions inside the kernel call, the loop never parks while I/O could complete.
  Completions are always serviced before the next task batch (no handler starvation).
  Wakeups are coalesced by an atomic pending flag: at most one `eventfd` write per queued
  burst; a stop signal always forces a write.
- **Completion dispatch.** A task registers `ctx.onCompletion(handle, handler)` before the
  submit; the loop looks up the reaped CQE's raw `user_data` (`Result.id()`) in a
  shard-thread-local open-addressing table and invokes the handler with the reusable
  `Result`. Duplicate/full/off-thread registration is `IllegalStateException`; unregistered
  completions are counted (`completionsUnattributed`), never crash a shard. Handler/task
  exceptions are caught and counted; the loop continues.
- **Distribution.** `RingGroup.execute(task)` round-robins; `execute(key, task)` and
  `shardFor(key)` use a stable splitmix64 mix, so a key always targets the same shard for
  the group's lifetime (no rebalancing). Per-producer FIFO within a shard; cross-producer
  total order unspecified. The group is the fixed ring pool: one ring per shard, created
  lazily on its thread, closed exactly once.
- **Cross-thread handoff.** `handoff` enqueues a continuation on the target shard's bounded
  MPSC queue and signals its `eventfd`; the target loop runs it on its own thread. On queue
  overflow after a bounded spin the continuation runs inline on the calling thread and is
  counted (`handoffsInlineFallback`) — it never blocks and never drops.
- **Backpressure/lifecycle errors.** Foreign `execute` blocks while the bounded queue is
  full; from a shard thread it throws immediately (a shard must not block on itself).
  `requestStop()` is non-blocking and rejects new work; `close(timeout)` (foreign thread
  only) drains accepted tasks, cancels in-flight requests ring-wide, dispatches the
  `-ECANCELED` completions to handlers, closes the ring with B3's bounded close, and waits
  at most `timeout + 250 ms`, recording `closeTimedOut` for a stuck user task. Idempotent;
  stats stay readable after close; use-after-close throws `IllegalStateException`.
- **`JUringBlocking` integration (B4 §9).** Each worker is hosted by one `IoShard`: the
  shard provides the thread, ring, CPU affinity and bounded lifecycle; the existing tuned
  worker poll loop (MPSC/eventfd/slot state machine/park-unpark) runs unchanged as the
  shard's loop body, preserving the zero-GC VT hot path. New `JUringBlocking(RingGroup)`
  adopts an unstarted group (inherits its affinity), and `close(timeout)` is now bounded —
  fixing the B3 §4.5 "cancellation completion never arrives can block forever" residual
  hazard (ring teardown uses the bounded close; all parked callers are unblocked).
- **Known B3 limitation preserved.** Concurrent non-fixed `READ_EXT` into the same
  `MemorySegment` still shares one `user_data` (B3 §11.9/F1); the shard runtime documents
  the safe patterns (fixed-buffer I/O, distinct buffers, or raw `prepare*` +
  `onCompletion`) rather than changing B3 semantics.

## 4. Test evidence

- **Lead frozen-tree full suite (final, v2 after the verifier's F1 fix):** `mvn -o test` →
  **309 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS, 16.019 s**
  (`scratch/b4-lead/lead-g3v2-full-suite.log`). Baseline before B4: 238 tests (B3); B4 adds
  **71 tests** (60 core/table + 11 blocking) in 18 new test classes; no existing test was
  weakened or changed. (Pre-F1-fix v1 freeze was 303 tests; v1 evidence is archived.)
- Core classes (60): `B4LifecycleTest` (7), `B4AffinityTest` (3), `B4DistributionTest` (7),
  `B4OrderingTest` (2), `B4CompletionTest` (6), `B4HandoffTest` (4),
  `B4BackpressureTest` (4), `B4ShutdownTest` (7), `B4GroupTest` (6), `B4StatsTest` (2),
  `B4JuringFileTaskTest` (2), `B4BodySeamTest` (3), `B4StressTest` (1),
  `B4CompletionTableTest` (6, added with the F1 fix) — core tests repeated 3× green at G1;
  final full suite green twice (G2 303, Lead v2 309).
- Blocking classes (11): `B4BlockingGroupConstructorTest` (5),
  `B4BlockingVtConcurrencyTest` (2), `B4BlockingBoundedCloseTest` (3),
  `B4BlockingAffinityTest` (1); the 21 pre-existing `JUringBlockingTest` tests pass
  unchanged; new classes repeated 5× green.
- **Verifier independent suite:** 309/0/0/1 on the same freeze (see §5).
- **Zero-GC blocking path:** 20 000 registered-file reads measured with
  `ThreadMXBean.getCurrentThreadAllocatedBytes` → **0.0000 bytes/call** on the old
  constructor, the new `RingGroup` constructor and a plain registered-file read (slot
  protocol adds no allocation; only caller-side empty varargs arrays show caller cost).
- **Bounded close:** `JUringBlocking.close(2000)` with 32 deterministic in-flight reads →
  **2 ms** (bound 2 250 ms); `close(0)` → **1 ms** (bound 250 ms); all callers unblocked
  (`-ECANCELED`/ISE), shard threads/fds stable, idempotent.
- Full details: `scratch/b4-core/STATUS.md`, `scratch/b4-blocking/STATUS.md`.

## 5. Independent verification

**Verdict: PASS** on the frozen v2 tree (`scratch/b4-verify/VERDICT.md`, STATUS: FINAL).
The verifier is independent of both authors: it re-derived all four frozen hashes, copied
`src/` byte-identically, compiled its own classpath (never used `target/classes`), and ran
**105/105 adversarial modes under fresh JVMs and external timeouts, 937 PASS assertions,
0 FAIL, 0 crashes/timeouts** (`logs/matrix-v2/`). It independently re-ran `mvn -o test`:
**309 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS 16.119 s** — matching the Lead.
Modes cover threading/affinity (`sched_getcpu` + `/proc` masks), ring confinement,
distribution edge keys, per-producer FIFO, completion exactly-once, handoff storms/idle
wake/overflow, backpressure, shutdown (close(0), double close, close-from-shard ISE,
stuck-task bound, 100-cycle fd/thread/RSS leak loops), use-after-close on every entry
point, concurrent-close races, 10k-op stress, `JuringFile` in a shard task, `JUringBlocking`
old-vs-`RingGroup` parity/VT/interrupt/bounded-close/affinity/zero-GC. On the **first**
pass, 12/105 modes failed but all 12 were verifier-harness bugs (flags mix-up, `/proc` comm
truncation, over-capacity burst vs §11.8, over-strict ISE expectation) — recorded as
methodology provenance in the verdict (§7), not as library defects.

**F1 (found by the independent verifier; fixed before delivery).** A completion arriving
with no registered handler probed a **full** `CompletionTable` without an `EMPTY` stop
marker: `CompletionTable.remove` could spin forever (100 % CPU shard wedge, deterministic
5/5 reproducer). A second independent hang path existed in the old cluster re-insertion
deletion. Fix: bounded probes + textbook backward-shift deletion (no re-insertion, no
tombstones, no allocation), `remove` returns `null` in bounded time for missing keys even
on a full table, and `B4CompletionTableTest` (100 k-op churn vs HashMap shadow model,
fill-to-capacity/remove-all, full-table bounded removal, IoShard no-wedge + table-full
ISE-with-unattributed degradation regression). Design §4/§5/§9.3 were amended to state the
degradation precisely (table-full ISE may leave SQEs prepared earlier in the failing task
to complete as counted `completionsUnattributed`; probes always terminate). Lead v2 suite:
309/0/0/1; F1 reproducer after fix: `stalled=false`, bounded close 2 ms, progress continues.
**F2 (documentation):** blocking-close callers unblock with a real `-ECANCELED` result (not
always ISE); §9.3 now states both documented terminal outcomes (ISE only when the SQE was
never submitted).

## 6. Preliminary W17-W20 scaling (NOT M3 sign-off)

**Protocol.** One 4K-randread, QD-64-per-worker session on the aged 10 GiB read-only dataset
`/mnt/TestData/read_files/single_file_bench.bin` (DS-S; the full W18-W20 DS-M/per-shard-file
layout is not part of this preliminary run), CPUs `1..N` via `taskset`/`--first-cpu 1`,
10 s warmup + 30 s measured, R=3, 60 s rests, fresh process/JVM per invocation, engines
interleaved and order alternated per rep, every rep kept (no trimming). Engines:
`juring-group` (`JUringFileBench --runtime=shard --shards N`, one `IoShard`/`RingGroup` ring
per worker, handler-driven QD-64 `READ_FIXED` pipeline with registered file+buffers),
`juring-manual` (the historical `--runtime=threads --threads N`: one Java thread + one
`JUring` per worker), `fio numjobs=N` (matched: `--ioengine=io_uring --registerfiles=1
--fixedbufs=1 --iodepth=64 --direct=1`, pinned 1..N). Driver/analyzer:
`bench/shard/run-shard-scaling.sh`, `bench/shard/analyze_shard.py`; raw session
`bench/results/b4-2026-11-14/` (gitignored).

**Bars** (preliminary application of plan M3): group-8 total IOPS >= 85 % of fio-8;
SE(8)=IOPS(8)/(8*IOPS(1)) >= 0.75 per engine; per-shard min/mean >= 0.85; group
p99(8) <= 1.15 x p99(1). This is **preliminary** (R=3, one shared file, no 1 s state
series, no seastar multi-shard): it can support "the B4 runtime scales", not M3.

**Result (36/36 invocations `status=ok`, all reps kept; session `bench/results/b4-2026-11-14/`):**

| metric | N=1 | N=2 | N=4 | N=8 |
|---|---|---|---|---|
| juring-group total IOPS (mean) | 303,772 (CV 11.4 %, 1 slow rep) | 421,286 | 473,753 | 463,666 |
| juring-manual total IOPS (mean) | 324,076 | 401,811 | 455,590 | 476,392 |
| fio total IOPS (mean) | 325,321 | 418,374 | 467,708 | 470,517 |
| **juring-group / fio** | 0.934 | 1.007 | 1.013 | **0.985** |
| juring-manual / fio | 0.996 | 0.960 | 0.974 | 1.012 |
| group / manual | 0.937 | 1.048 | 1.040 | 0.973 |
| SE(N) group / manual / fio | 1 / 1 / 1 | 0.693 / 0.620 / 0.643 | 0.390 / 0.351 / 0.359 | 0.191 / 0.184 / 0.181 |
| p99 group / manual / fio (µs) | 655 / 502 / 483 | 882 / 1004 / 952 | 1750 / 2016 / 1961 | 3359 / 2738 / 2845 |
| p99(N)/p99(1) group / manual / fio | 1 / 1 / 1 | 1.35 / 2.00 / 1.97 | 2.67 / 4.02 / 4.06 | 5.12 / 5.46 / 5.90 |

**Bar pass/fail.** (1) group-8 >= 85 % of fio-8: **PASS** (0.985; 3/3 reps pass, 0.922
lowest). (2) per-shard min/mean >= 0.85: **MISS under the pinned layout** (0.802, exactly
reproducible r1/r2/r3) — but see the topology diagnostic below; unpinned group-8 is
0.965/0.983/0.976, and the runtime's own counters show exactly-once dispatch with zero
unattributed completions. (3) SE(8)>=0.75: **not applicable/invalid for this protocol** —
fio 0.181, manual 0.184, group 0.191; the drive's ~470k IOPS ceiling makes N*IOPS(1)
meaningless (3.0M/8 ≈ 2.6M target vs 470k ceiling). (4) p99(8)<=1.15*p99(1): **not
applicable/invalid for this protocol** — fio 5.90, manual 5.46, group 5.12; total
in-flight grows 8x with N at constant per-shard QD, so this measures SSD queueing, not the
runtime. Both (3) and (4) are plan-bar defects to fix for M3 (e.g. compare SE/latency
*against fio at the same N*, or hold total QD fixed), not B4 regressions.

**Per-shard imbalance diagnostic (N=8).** Pinned group-8 shards: s0=94.5k, s7=94.4k,
s1-s6≈47.8k (min/mean 0.803). The data NVMe's blk-mq contexts cover 4 CPUs each
(`mq/0: 0,1,16,17`, `mq/1: 2,3,18,19`, ...), so CPUs 1 and 8 each own a hardware queue while
CPUs 2-7 pair up on shared queues: `/proc/interrupts` deltas show exactly 5 active NVMe
queues (2 dedicated + 3 shared pairs), and per-CPU `/proc/stat` shows 91-92 % busy / 4-5 %
iowait on the dedicated CPUs vs 73 % busy / 22-23 % iowait on the paired ones. Unpinned
group-8 (`--first-cpu -1`): **0.965 / 0.983 / 0.976** min/mean with flat 56-63k per shard at
the same total IOPS (459-477k). **Conclusion: device-topology artifact of CPU pinning, not
a runtime distribution defect.** M3 should pin one shard per hctx
(`1,2,4,6,8,10,12,14`), or run unpinned, and record `mq/*/cpu_list`.

**Incident disclosure.** The first pass (16:16-16:33) ran as a background job of the bench
agent's turn and was SIGTERMed when the turn ended: 17 good invocations survived and
`N2__fio__r3` was incomplete. The driver gained a `--resume` path, kept all 17 valid cells,
archived the incomplete artifacts (`bench/results/b4-2026-11-14/incomplete-archive/`),
dropped only that stale meta row and ran the remaining 19 invocations in the same session:
final 36/36 `status=ok`, all reps kept, no good cell re-run or trimmed.

## 7. Known limitations / residual risks

**Design-level (frozen; full text in `docs/b4-design.md` §11):**
1. **Not a scheduler** — no work stealing, rebalancing, producer fairness or QoS; RR/hash
   distribution is fixed and a slow shard stays slow.
2. **Inline handoff fallback** on queue overflow runs the continuation on the calling
   thread (breaks strict target-thread confinement only for that counted continuation;
   never blocks/drops).
3. **Cyclic `execute` backpressure can deadlock** (user error: shard A submits to B while
   B submits to A with full queues); `handoff` cannot deadlock (inline fallback).
4. **A stuck user task cannot be killed** — `close` returns bounded (`timeout+250 ms`) with
   `closeTimedOut=true`, and the ring/native resources are released only when the task
   returns (package-private `isTerminated()` lets owners wait).
5. Handler `Result` is reusable — do not retain it past the handler.
6. CPU affinity is **best effort** (`sched_setaffinity` may be denied by cgroups/cpuset);
   recorded in `ShardStats`, never fatal.
7. Only the default `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` flags are in the acceptance
   path; `SQPOLL`/`IOPOLL` pass through untested by B4.
8. **Handler-table capacity** must cover peak live registrations; a table-full registration
   ISE leaves earlier-prepared SQEs to complete as counted `completionsUnattributed`
   (degradation — no wedge since the F1 fix; see §5).
9. **B3 §11.9/F1** (concurrent non-fixed `READ_EXT` into the same `MemorySegment` shares
   `user_data`) is documented, not fixed; `JuringFile` blocking helpers inside a shard task
   can hit the B1 ownership guard in the rare "queued behind a running batch" window
   (documented in `ShardContext` javadoc; raw `prepare*` + `onCompletion` is the safe path).
10. `JUringBlocking`'s internals are re-homed on the shard runtime but its slot protocol is
    retained; the zero-GC path is measured at 0 B/call, but a **full VT-throughput
    re-benchmark is out of B4 scope** (M3/B5 should re-measure).
11. `user_data` op-id wrap (2^56 / 2^48) remains documented, not defended (B3).

**Independent-verifier residual risks R1-R9** (`scratch/b4-verify/VERDICT.md`): capacity
preconditions now degrade to ISE/unattributed instead of a wedge; stuck-task close;
reusable `Result`; best-effort affinity; inline fallback; non-goals; bench/M3 out of scope;
`SQPOLL`/`IOPOLL` untested; CQ-pressure note (all in-budget runs clean).

**Preliminary-bench caveats** (`bench/results/b4-2026-11-14/aggregate/shard.md`): R=3, one
session, single shared 10 GiB file (not DS-M), no 1 s state series / 300 s rests, no
seastar multi-shard; the host is bimodal at QD64 (two slow-state reps kept and disclosed);
`juring-group` p99 includes the shard runtime's batched dispatch while fio reports `clat`
(no cross-engine tail comparison); SE(N) and p99(N)/p99(1) are dominated by SSD saturation
in this protocol and the plan bars need restating; per-thread CPU pinning must respect the
data-NVMe hctx→CPU topology (see §6). No release blocker is open after the F1 fix.

## 8. Next steps (M3 complete path)

- **B5 observability** (per-ring metrics/histograms, JMX/Micrometer, benchmark JSON
  integration, <1 % overhead) — start from `ShardStats`.
- **Restate the M3 scaling bars** before the campaign: `SE(8)>=0.75` and
  `p99(8)<=1.15*p99(1)` are unattainable/meaningless for the current single-device,
  constant-QD-per-shard protocol (the `fio` reference fails them identically); use
  SE/latency **relative to `fio` at the same N** (or hold total QD fixed), and keep the
  runtime-relevant bar `N-shard JUring >= 85 % of fio numjobs=N` (already 98.5 % at N=8 in
  the preliminary session).
- **Topology-aware placement** for the campaign: pin one shard per NVMe hctx
  (`mq/*/cpu_list`, e.g. CPUs `1,2,4,6,8,10,12,14`) or run unpinned; record it in the
  manifest. This removes the pinning-induced per-shard spread artifact (0.802 pinned →
  0.965-0.983 unpinned at the same total).
- seastar `--smp N` multi-shard runner and the full W17-W20 campaign (R=5, state gating,
  300 s rests, DS-M/per-shard file decision) for the M3 bars incl. the seastar 90 % LB.
- Optional: move `JUringBlocking`'s slot protocol onto the generic task loop (dedup), only
  if a VT-throughput re-benchmark shows it is worth it.
- B3 §11.9/F1 and other pre-existing residuals unchanged unless a future milestone takes
  them.

## 9. Provenance

- Branch `kafka-engine`; base commit `25c664b28956992a8f35244ee0100ad79883d5c7`; B4
  working tree frozen at G3 with `src/main` content sha1
  `1126e08d35788b0ff2dbb43ea2ddc73398a3e71a` (see `scratch/b4-lead/FREEZE.md`).
- Commands: `mvn -o test` (G1 292 → G2 303 → Lead G3 303; all green), harness commands in
  `bench/shard/README.md`, verification commands in `scratch/b4-verify/README.md`.
- Machine: AMD Ryzen 9 3950X (16C/32T), 62 GiB, Fedora 44, kernel 7.0.12, JDK 26.0.2,
  data NVMe GIGABYTE GP-ASM2NE6100TTTD (`nvme1n1`), aged 10 GiB read dataset
  `/mnt/TestData/read_files/single_file_bench.bin` (read-only, never written).
