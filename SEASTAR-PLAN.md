# JUring vs seastar: where we are, and how to catch up on the basics

**Status:** v1.1 — 2026-11-13 (decisions locked; seastar checkout moved in-repo)
**Subject:** JUring `4526338` (branch `kafka-engine`)
**Opponent:** `SeaStarRef/` (in-repo checkout) at seastar `04b1c04d` (`seastar-25.05.0-1392-g04b1c04d`, 2026-11-11)
**Hardware:** AMD Ryzen 9 3950X (16C/32T), 62 GiB RAM, Linux 7.0.12, GIGABYTE GP-ASM2NE6100TTTD 1 TB NVMe (`nvme1n1`, ext4, `/mnt/TestData`)

Companion documents:

| Document | What it is |
|---|---|
| `docs/seastar-inventory.md` | seastar's file-I/O stack and benchmark tooling, with `path:line` evidence (485 lines) |
| `docs/juring-inventory.md` | JUring API/test/benchmark audit + gap list G1–G12 (359 lines) |
| `docs/benchmark-methodology.md` | the measurement contract: matrix W1–W24, fairness rules F1–F15, device protocol D1–D8, statistics, JSON schema (484 lines) |
| `docs/baseline-2026-11-13.md` | today's measured fio + JUring numbers + §9 three-way seastar addendum |
| `docs/HANDOVER.md` | next-session handover: build/run commands, current results, quirks, ordered next steps |
| `bench/` | runnable harness: `JUringFileBench`, `seastar_file_bench`, seastar `io_tester` configs, fio matrix, result parser |

### Decisions locked (2026-11-13)

| # | Decision | Consequence for this plan |
|---|---|---|
| D1 | **JUring is library-first.** The seastar alternative being built on it is a **separate flagship project**, not part of JUring. For now it can be built in this repo, but JUring itself stays a focused I/O library. | Target **L0, plus L1 and L2 "basics"** — not full seastar parity. No futures/coroutines runtime, no network stack, no application layer in JUring. §4 non-goals stay. |
| D2 | **Approve the "basics" roadmap** (B0–B9; B7/B8 conditional). | Roadmap stands as ordered; B0 first, then B1/B2, then B4/B5. |
| D3 | **Transparency, and fair engine configurations.** Publish the benchmark data; do not omit flags/features to make either side look better. | New fairness rule F0 (§3.3): **each engine runs its best documented configuration, fully disclosed**; the other side is never handicapped. Also answer the "what else do L1/L2 need" question in §3.6. |
| D4 | **Build seastar and compare on this machine.** The checkout now lives at `SeaStarRef/` inside this repo. | Build/environment work is unblocked: no more read-only seastar tree. Seastar numbers become M1's core deliverable. |
| D5 | **Manual perf gate is fine.** | B0 asks for a stored baseline + manual/occasional gate, not a nightly CI service. |
| — | **blackpanda is not the driver** for this effort; it can be ignored. | All blackpanda-driven ordering/priorities are removed from the plan. The write-path work (B1/B3/B6) stays because a fair L1 comparison and the future framework both need it. |

---

## 0. Executive summary

**The headline.** "JUring vs seastar" is not one benchmark. It is a comparison between a
kernel-I/O library and an application runtime with ~15 years of production hardening.
One IOPS number for the two is technically true, practically meaningless, and easy to
attack. The useful question is *at which layer are we behind, and by how much*.

**The scorecard.**

| Layer | What it measures | Status today | Next action |
|---|---|---|---|
| **L0 — io_uring engine** | Same kernel path, same workload, different driver code | **Measured.** 4K randread: seastar wins QD1/QD16 (**11.6k vs 11.1k**, **146.2k vs 143.7k**) and JUring wins QD64/QD128 (**324.2k vs 319.5k**, **420.8k vs ~399k fast-state**); 1M seqread parity (~4.77 GiB/s). **Engines are within ±5% everywhere**; fio is not a proxy (297.9k/303.3k at QD64/128). Caveats: one sandbox build, 3 reps, no hugepages, QD128 slow-state variance, fio not yet registration-matched. | **M1:** more reps + state gating, matched fio sweep, same-definition latency, cycles/op, `io_tester` cross-check. |
| **L1 — async file API** | `file::dma_read` + `fstream` (readahead/write-behind) vs. raw SQEs | **Not comparable yet.** seastar has a complete file+stream layer. JUring has raw SQEs, registered files/buffers and a VT facade — no stream abstraction, no truncate/allocate/discard/stat, no cancellation guarantees, no timeout op. A buffered `io_tester`/`fstream_perf` comparison cannot be run against JUring today. | **B1+B2**, then **M2**. |
| **L2 — runtime** | Shard-per-core, scheduling, backpressure, I/O QoS, metrics | **Not comparable yet, mostly absent.** JUring gives one ring per thread and lets users build their own runtime. Legitimate library design — but it means multi-shard scaling and mixed workloads are *user code*, not library behaviour, so there is nothing to compare. | **B4+B5**, then **M3**. |
| **L3 — application/framework** | the future flagship seastar-alternative built on JUring | **Out of scope for JUring.** JUring is library-first; the framework is a separate project. It will consume JUring's L1/L2 basics, so those are still worth building — but no L3 comparison belongs in this plan. | Revisit when the framework project starts. |

**Two findings that change the framing.**

1. **The published numbers are not yet defensible.** The README's `+0.9% vs fio` comes
   from overlapping confidence intervals; the write table picks a "best batching"
   baseline that is *slower* than fio's default; the same 1-thread/QD64 cell appears
   three times with means 330,196 / 330,089 / 319,847 and spreads from ±361 to
   ±40,984; the JMH runs are documented as "5 forks × 3 iterations × 3 s" but the
   source only sets `@Fork(1)` and no warmup/measurement, with no runner script
   committed. This is not a reason to distrust JUring's engine — it is a reason to
   make the next comparison methodologically airtight. The audit is in
   `docs/benchmark-methodology.md` §9. **B0 fixes it and is the cheapest credibility win
   available.**
2. **Raw IOPS is largely a solved problem; the gap is everything above the engine.**
   The NVMe saturates at ~469k read IOPS / ~1.83 GiB/s (8 fio jobs), and single-thread
   JUring at QD128 already reaches 421k (90% of that ceiling) — ahead of fio on the same
   core (though fio was not registration/batching-matched; M1 confirms). Catching up
   cannot mean "more IOPS" — it means capability, CPU-efficiency under load, tail
   latency, and the abstractions that make a fair benchmark possible at all.

**The strategy: don't clone seastar.** seastar's moat is not a faster syscall loop — it
is the accumulated "boring basics": a file/stream abstraction, cancellation semantics,
a shard runtime, QoS, observability, and a benchmarking culture. Trying to match all of
it turns a focused library into a mediocre framework.

Catch up on the *minimum set of basics that (a) makes a like-for-like benchmark possible
and (b) makes JUring a solid foundation for the future flagship framework*, while
keeping the engine identity that is already at or above fio on the read path. Roadmap
order, by leverage:
**instrument first → file/handle → streams → lifecycle → multi-ring → observability →
buffered/durability → conditional QoS**. §4 has the details.

**Milestones.** **M0 done 2026-11-13** (harness + seastar built + first L0 read matrix);
M1 L0 parity report; M2 L1 stream parity; M3 L2 scaling + observability; M4 conditional
QoS. Pass/fail in §5.

---

## 1. Define the comparison before measuring it

The same two projects can produce a 30× gap or a 1.03× gap depending on the layer. Freeze
these definitions and put them in every report.

### L0 — io_uring engine parity

Both sides issue the same kernel operation (read/write or readv/writev) against the same
`O_DIRECT` file, at the same per-thread queue depth, on the same pinned core, with
equivalent batching. This isolates *the code that drives io_uring*.

- **JUring:** `SINGLE_ISSUER | COOP_TASKRUN | DEFER_TASKRUN`, registered file(s),
  registered fixed buffers, `IOSQE_FIXED_FILE`, fused submit+wait batching.
- **seastar:** `io_tester --reactor-backend=io_uring`. Its normal ring is created with
  **no setup flags**, `QUEUE_LEN = 200`, no registered files or buffers, and file I/O
  passes through `io_queue` → `io_sink` → `IORING_OP_READ/WRITE`
  (`src/core/reactor_backend.cc:1259-1262`, `src/core/reactor_backend.hh:467`).
- **Neutral referee:** `fio --ioengine=io_uring`, with `--registerfiles=1 --fixedbufs=1`
  and a `--iodepth_batch_submit` sweep so batching is matched rather than assumed.
- The seastar asymmetries are *real engine differences*. Document them, report them as
  a bracket, and never claim the delta is an API/framework advantage.

### L1 — async file API

- **seastar:** `open_file_dma` → `file::dma_read/dma_write`, `fstream` (`read_ahead`,
  `write_behind`, buffer sizing), metadata ops (`truncate/allocate/discard/stat`),
  `flush`, `io_intent` cancellation. No plain `read()/write()` on `file` — byte streams
  are `fstream`. No per-operation timeout in the public API (cancellation only; timeouts
  are app-level timers).
- **JUring:** raw `prepareRead/Write`, registered files/buffers, `FileDescriptor`
  open/close, `prepareFsync`, `prepareCancel`. No stream abstraction, no
  truncate/allocate/discard/statx, no timeout op, no multi-op cancellation guarantee,
  no unregister-files/buffers.
- **Fair L1 test:** same access pattern and concurrency, driven through each library's
  intended high-level API. Today only the direct-I/O subset is comparable; buffered and
  streaming workloads need B1/B2/B6.

### L2 — runtime

Shard/thread model, task scheduling, I/O scheduling/QoS, backpressure, cancellation
guarantees, metrics. seastar: reactor per shard, io_queue token buckets, scheduling-group
shares, per-class metrics. JUring: N private rings + `JUringBlocking` (1–4 poller
threads, MPSC slot state machine, `LockSupport` park/unpark for virtual threads). Any L2
number must state shard count, cpuset, per-shard QD, and mixed-workload ratios.

### L3 — application / future framework

The seastar-alternative framework will be its own project, built on JUring. Comparing it
to seastar applications belongs to that project, not to JUring's benchmark programme.
Deferred.

---

## 2. Where we are now

### 2.1 Performance at L0 — measured today

Source: `docs/baseline-2026-11-13.md`; raw JSON under `bench/results/2026-11-13/`.
Read path, `O_DIRECT`, 10 GiB file, single pinned core, 10 s per run, 3 reps. The data
mount is read-only in this sandbox, so writes were deferred to the host run.

**fio on this machine (2026-11-13, adopted runs after outlier re-runs):**

| Workload | QD / jobs | IOPS | MiB/s | p99 clat |
|---|---|---|---|---|
| 4K randread, io_uring | 1 | 10,901 ± 180 | 42.6 | 104 µs |
| 4K randread, io_uring | 16 | 143,332 ± 2,355 | 559.9 | 215 µs |
| 4K randread, io_uring | 64 | 297,931 ± 2,256 | 1,163.8 | 461 µs |
| 4K randread, io_uring | 128 | 303,258 ± 2,026 | 1,184.7 | 676 µs |
| 4K randread, libaio | 64 | 275,535 ± 2,271 | 1,076.3 | 449 µs |
| 4K randread, libaio | 128 | 275,666 ± 1,762 | 1,076.9 | 777 µs |
| 4K randread, io_uring batch=8 | 64 | 288,290 ± 7,292 | 1,126.2 | 508 µs |
| 4K randread, io_uring batch=16 | 64 | 280,408 ± 17,446 | 1,095.4 | 534 µs |
| 4K randread, io_uring | 64 × 2 jobs | 410,645 ± 6,532 | 1,604.1 | 1,042 µs |
| 4K randread, io_uring | 64 × 4 jobs | 468,076 ± 2,171 | 1,828.5 | 1,810 µs |
| 4K randread, io_uring | 64 × 8 jobs | 468,990 ± 12,997 | 1,832.2 | 2,780 µs |
| 1M seqread, io_uring | 1 | 1,658 ± 35 | 1,657.7 | 709 µs |
| 1M seqread, io_uring | 8 | 4,697 ± 81 | 4,697.7 | 2,236 µs |
| 1M seqread, io_uring | 32 | 4,774 ± 2 | 4,777.6 | 8,225 µs |

Notes: the first QD64 pass had one cold rep (254.9k), re-run once and adopted at
297.9k; batching did not help fio (`batch=8/16` below default); random reads saturate
at ~469k IOPS / 1.83 GiB/s with 4+ jobs; 1M sequential reads are bandwidth-bound at
~4.7 GiB/s; io_uring beats libaio by ~8% at QD≥64. Raw JSON in
`bench/results/2026-11-13/fio/`, summary in `fio-stats.md`.

**JUring on this machine (2026-11-13, 3 reps, seed fixed, `taskset -c 2`):**

| Workload | QD | IOPS | MiB/s | p50 (JUring) | p99 (JUring) | vs fio |
|---|---|---|---|---|---|---|
| 4K randread | 1 | 11,081 ± 42 | 43.3 | 83.5 µs | 102 µs | +1.7% (n.s.) |
| 4K randread | 16 | 143,714 ± 259 | 561.4 | 101.6 µs | 219 µs | +0.3% (n.s.) |
| 4K randread | 64 | 324,186 ± 679 | 1,266.4 | 170.2 µs | 492 µs | **+8.8%** |
| 4K randread | 128 | 420,794 ± 188 | 1,643.7 | 259.1 µs | 887 µs | **+38.8%** |
| 1M seqread | 1 | 1,791 ± 3 | 1,791.4 | 537.0 µs | 638 µs | **+8.0%** |
| 1M seqread | 8 | 4,769 ± 2 | 4,768.6 | 205.6 µs | 445 µs | +1.5% (n.s.) |
| 1M seqread | 32 | 4,775 ± 0 | 4,775.3 | 179.2 µs | 504 µs | +0.0% (n.s.) |
| nop (ring only) | 64 | 7.42M ± 0.06M | — | 6.5 µs | 9 µs | n/a |

Latency caveat: JUring's numbers are its harness definition (prep → completion,
including submission-side work); fio's are `clat`. **They are not numerically
comparable** — compare IOPS/MiB/s between engines and latency trends within one engine.

How to read this:

- **4K cells use registered files + fixed buffers** (JUring's optimum). The fio runs
  above did **not** pass `--registerfiles=1 --fixedbufs=1`, so the QD64/QD128 deltas
  partly measure registration, not only the engine. M1 re-runs fio matched.
- **1M cells used unregistered arena buffers** (RLIMIT_MEMLOCK is 8 MiB here). That is
  actually the *like-for-like* path for seastar, which has no fixed-buffer API; JUring is
  at parity there (+8% at QD1, +1.5% at QD8, 0% at QD32) while bandwidth-bound at
  ~4.77 GiB/s.
- **The QD128 result is the interesting one:** single-thread JUring reaches 421k IOPS,
  ~90% of the 4-job fio ceiling (469k), while fio's own single-thread QD128 stalls at
  303k. The gap is repeatable (CV 0.0–0.2%) but **not yet explained** — it may be fio's
  own submission/completion bookkeeping rather than an intrinsic io_uring property.
  Do not use it as a seastar proxy.
- **Tail latency:** the harness and fio use different definitions (prep→completion vs
  `clat`), so today's p99 values cannot be compared across engines. A same-definition
  latency comparison is an M1 deliverable; until then report latencies per engine and per
  QD, never "JUring has better/worse tails".
- **Stability:** the 3 matrix reps were stable (CV ≤ 0.2%) — the bimodal ~255k/~325k
  state from the smoke phase did not recur in the matrix (see §6).

**seastar on this machine (2026-11-13, in-sandbox build, 3 reps, `taskset -c 2`):**

| Workload | QD | seastar IOPS | MiB/s | p50 | p99 | JUring vs seastar |
|---|---|---|---|---|---|---|
| 4K randread | 1 | 11,576 ± 10 | 45.2 | 82 µs | 99 µs | −4.3% |
| 4K randread | 16 | 146,195 ± 198 | 571.1 | 101 µs | 213 µs | −1.7% |
| 4K randread | 64 | 319,532 ± 247 | 1,248.2 | 176 µs | 470 µs | **+1.5%** |
| 4K randread | 128 | 381,082 ± 25,349¹ | 1,488.6 | 289 µs | 1,055 µs | **+5.4%** |
| 1M seqread | 1 | 1,827 ± 7 | 1,826.5 | 531 µs | 622 µs | −2.0% |
| 1M seqread | 8 | 4,538 ± 330² | 4,538.4 | 1,694 µs | 3,851 µs | +1.5% (fast state) |
| 1M seqread | 32 | 4,777 ± 1 | 4,777.4 | 6,755 µs | 7,302 µs | 0.0% |

¹ two fast-state reps (398,557 / 399,451) and one slow-state rep (345,238) — the same
bimodal behaviour as JUring/fio (§6). ² one slow rep (4,071); fast reps 4,776 / 4,767.

Configuration (best available here, F0): `SeaStarRef/` release, `io_uring` reactor
backend (default when the probe succeeds), `--smp 1 --cpuset 2 --memory 1G
--lock-memory 0`, taskset-pinned; no hugetlbfs exists so THP is used; the runner
`bench/seastar/seastar_file_bench` opens the *same* 10 GiB file read-only and issues
`file::dma_read` with unregistered aligned buffers and per-fiber in-flight I/O. Seastar's
ring is the stock 200-entry io_uring with no `SINGLE_ISSUER`/`COOP_TASKRUN`/
`DEFER_TASKRUN` and no registered files/buffers — those engine differences are exactly
what L0 measures, and they are disclosed, not hidden.

**Reading of the three-way comparison:**

- **The engines are close: within ±5% everywhere.** seastar wins low QD (per-op
  latency/overhead: QD1 −4.3%, QD16 −1.7%), JUring wins high QD (batching + fixed
  buffers + DEFER_TASKRUN: QD64 +1.5%, QD128 +5.4%). Neither side has a blowout; any
  single-number "X beats Y" headline would misrepresent the data.
- **fio is a bad proxy for seastar at high QD.** fio's default io_uring (batch=1, no
  registration) gave 297.9k @QD64 / 303.3k @QD128, while seastar reached 319.5k / ~399k.
  The JUring-vs-fio numbers in §2.1 above understate seastar; the fair comparison is this
  table (M1 adds matched fio + `io_tester` cross-check).
- At QD64 tail latency is comparable (seastar p99 470 µs vs JUring 492 µs; same
  before-submit→completion definition in both harnesses).
- This is a first result on one build/machine with 3 reps, no hugepages, no state gating,
  and no io_tester cross-check. M1 tightens exactly that.

**What is already known and what is missing:**

1. **seastar is now measured, and the expected overhead did not materialise at QD64.**
   With stock configuration (200-entry ring, no `SINGLE_ISSUER`/`DEFER_TASKRUN`, no
   registered buffers, io_queue+futures in the path) seastar reaches 319.5k IOPS vs
   JUring's 324.2k — ~1.5%. JUring's edge grows to ~5% at QD128. The old static argument
   ("seastar's per-op overhead must be higher") is therefore wrong in magnitude: the
   engine paths are close, and the real difference is *above* the engine. M1 should
   report this as parity-with-a-small-JUring-edge, not as a win.
2. **CPU cost per I/O is not in the current report.** At saturation IOPS stops
   discriminating; `cycles/op` (user + kernel), syscalls/op, and p99/p999 do. JUring's
   nop ceiling is 7.4M ops/s, so the engine has headroom; the question is how many CPU
   cycles it spends per completed I/O.
3. **Write numbers are known to be unstable** (README admits it). The write protocol in
   `docs/benchmark-methodology.md` §5 exists precisely because the current one is not
   rigorous: preconditioning, SLC-cliff detection, rest periods, run order, and
   reporting all reps.

### 2.2 Capability gap vs. seastar's basics

Summary; full evidence in `docs/juring-inventory.md` and `docs/seastar-inventory.md`.

| Capability | seastar | JUring today | Needed for fair comparison? | Effort |
|---|---|---|---|---|
| Direct async read/write, registered files/buffers | `dma_read/dma_write`; no public fixed-buffer API | ✅ core strength: `readFixed/writeFixed`, `registerFiles`, `registerBuffers`, `IOSQE_FIXED_FILE` | Yes (L0) | done |
| open/close/fsync | ✅ | ✅ (`FileDescriptor`, `prepareFsync`) | Yes | done |
| Size/metadata ops: truncate, allocate, discard, stat | ✅ `file` API | ❌ absent | Host-run setup + real apps | S |
| Buffered stream abstraction: readahead, write-behind, flush, preallocation, complete-until-done | ✅ `fstream` (adaptive readahead, write_behind semaphore, zero-pad tail) | ❌ absent; short I/O is caller's problem | **Yes for L1 buffered workloads** | M/L |
| Cancellation semantics / timeouts / linked ops | `io_intent::cancel` (no per-op timeout in API) | Partial: raw `prepareCancel` (untested); no timeout op; `SqeOptions` link flags are pass-through only | Yes for robustness claims | M |
| Shard-per-core runtime, cross-shard handoff | ✅ `--smp`, `--cpuset`, `smp::submit_to` | ❌; one `JUring` per thread is user code; `JUringBlocking` = 1–4 pollers | **Yes for L2** | L |
| Fair I/O queuing/QoS/intent | ✅ io_queue token buckets, scheduling-group shares, bandwidth caps | ❌ no scheduler, no priority, SQE ioprio accessor exists but never set | Conditional (M4) | L |
| Metrics: ops/bytes/queue/delay/exec/starvation, per-shard | ✅ reactor + io_queue metrics, Prometheus | ❌ benchmark-level only | Yes for M3 | M |
| Buffered (page-cache) I/O parity + durability modes | ✅ fstream + `posix_fadvise` + dsync/durable open | Partial: buffered ops exist, no policy, no stream, no durability abstraction | Yes for L1 buffered | M |
| Provided-buffer ring + multishot ops | partially (multishot networking; storage path is plain read/write) | ❌ `IOSQE_BUFFER_SELECT` exposed but no `IORING_REGISTER_PBUF_RING`; no `CQE_F_MORE` handling | Engine completeness; not a fair-comparison blocker | M |
| Registration lifecycle: unregister files/buffers, add at runtime | n/a (no registration) | ❌ register-buffers once, no unregister; no eventfd registration | Long-running services | M |
| Virtual-thread friendly blocking API | ❌ | ✅ `JUringBlocking` — **keep as differentiator** | Not needed for parity; positive | done |
| API stability / docs / compatibility policy | ✅ mature | ⚠️ good README, sparse Javadoc, no compat policy, 0.1-SNAPSHOT | Credibility | S |

**Reading of the table.** The engine is not the problem. Everything above the engine is
either absent or user-supplied — exactly the part seastar built over a decade, and exactly
the part a benchmark can measure only after it exists.

### 2.3 Engineering maturity

seastar's benchmark culture is part of its lead: `test.py`, perf tests, sanitizer builds,
reactor metrics, and `io_tester` (production-grade fio for seastar). JUring has a strong
JMH suite (throughput, QD sweep, VT scaling, FFM overhead, zero-allocation probes) but:

- the benchmark classes do not run in the build and have no runner script;
- the JMH configs in `BENCHMARK.md` don't match the source (no warmup/measurement
  annotations; `@Fork(1)`); [V]
- single-file benchmark offsets are seeded with `System.nanoTime()`, so run-to-run
  samples are not reproducible (`bench.suite.common.DatasetManager`);
- there is no perf-CI gate, no stored baseline, no seastar workload anywhere;
- tests are broad in the happy path but cancellation, timeouts, SQPOLL, IOPOLL,
  CQ-overflow, EAGAIN/short-IO, and multi-thread-same-ring are untested.

### 2.4 Credibility audit of the current public numbers

This matters because the next report will be read against the existing one. The audit in
`docs/benchmark-methodology.md` §9 finds:

| Claim where | Problem |
|---|---|
| `README.md:27-30` "fio 322,908 ± 13,644 vs JUring 325,676 ± 9,495 → +0.9%" | Spreads are larger than the delta; 95% CIs overlap almost entirely. The `±` convention (JMH vs fio, sd vs CI) is never defined. |
| `README.md:27` "fio default settings" | No command/version/batching recorded. "Default" hides `iodepth_batch_submit=1`, the main reason the comparison looks favourable. |
| `README.md:36-39` write table | The chosen "best batching" baseline (205,279) is *slower* than fio's own default (214,941); the −5.6% is computed against a worse-than-default config, and CIs are ±31–66k. |
| `BENCHMARK.md:26,45,53` | Same 1T/QD64/4K cell reported three ways: 330,196 ± 361, 330,089 ± 748, 319,847 ± 40,984. |
| `BENCHMARK.md:19` "5 forks × 3 iterations × 3 s" | Source says `@Fork(1)`, no `@Warmup`/`@Measurement`, no runner. Config not reproducible from the repo. |
| `README.md:42` "450k burst write due to DEFER_TASKRUN" | No table, no CI, no rest protocol, no same-config-without-the-flag control. UNVERIFIED. |
| No "seastar" anywhere | Confirmed: the comparison target is entirely absent from the repo. |

**This does not invalidate the engine result.** It means the next benchmark must be
published with raw data, commands, manifests, and CIs — B0/M1 do that.

---

## 3. The comparison programme

**Principle: fio is the referee, seastar is the opponent, JUring is the subject.** Never
publish a JUring-vs-seastar delta without the fio number for the same cell. If both sit
at the device ceiling, the correct conclusion is "both saturated the device", not "JUring
won".

### 3.1 Phases

| Phase | Work | Exit criteria |
|---|---|---|
| **P0 — Instrument** — **done 2026-11-13** | Build seastar (release, io_uring backend) — built in-sandbox without root; validate `bench/`; run the fio + JUring + seastar L0 read matrix; publish manifest + raw results. | One command reproduces the baseline; seastar runs (`io_tester` + `seastar_file_bench`); schema documented. Remaining for full P0: writable data dir for `io_tester`/writes. |
| **P1 — L0** (methodology Phase A, ≈4 h machine time) | W1–W4, W9–W11, W15, W17–W20: 4K randread/randwrite QD 1/16/64/128, 1M seqread QD 1/8/32, latency QD1/QD64, 1/2/4/8 shard scaling; **fio matched to JUring: `--registerfiles=1 --fixedbufs=1` + `--iodepth_batch_submit` sweep**; cycles/op and syscalls/op. | L0 report: JUring vs *matched* fio within the §5 bounds; seastar measured; cycles/op comparison; tail-latency table; QD64 run-state classification; every claim has a CI. |
| **P2 — L1** (after B1+B2) | W21–W22: buffered/streaming read/write; `fstream_perf` vs JUring streams; direct-I/O regression. | JUring stream ≥85% of seastar `fstream` on 1M sequential read/write; bounded memory; correctness tests pass. |
| **P3 — L2** (after B4+B5) | W6/W17–W20 scaling, W23 mixed, W24 nop; per-ring metrics; conditional QoS (W9/M4). | 8-thread scaling ≥85% of `fio numjobs=8`, SE(8) ≥0.75; per-ring metrics demonstrated; fairness result published. |

Machine-time phases are also split by device state (methodology §2): **A** = read path;
**B** = writes (overnight, preconditioned); **C** = streams/nop. Never interleave write
and read cells in one session.

### 3.2 Workload matrix

The authoritative matrix is `docs/benchmark-methodology.md` §2 (W1–W24). Summary:

| Group | Cells | Purpose |
|---|---|---|
| 4K random read/write | W1–W8 (QD 1/16/64/128) | L0 engine parity + QD curves |
| 1M sequential read/write | W9–W14 (QD 1/8/32) | bandwidth path; stream layer (W12–W14 after B2) |
| Latency | W15–W16 (QD1, QD64) | p50/p95/p99/p999/max; tail behaviour |
| Multi-shard scaling | W17–W20 (1/2/4/8, QD64 each) | L2 scaling vs `fio numjobs=N` |
| Buffered streams | W21–W22 | L1 `fstream` parity (after B2) |
| Mixed | W23 (70R/30W) | backpressure/latency under mixed load |
| Nop/overhead | W24 | ring/FFM/framework ceiling |

Datasets: single 10 GiB preallocated file for W1–W4, W9–W11, W15–W20; a dedicated
preallocated write file for W5–W8/W12–W14; multi-file corpus for W18–W20 (seastar
`io_tester` creates one file per shard — record it, don't hide it). Read cells run with
`--readonly` / read-only fds so a typo cannot modify the dataset.

**Writable NVMe data dir.** Write cells and `io_tester --storage` need a writable
directory on the data NVMe. Convention: `./nvme-data/` in the repo, bind-mounted on the
host from the data device (gitignored; setup commands in `docs/HANDOVER.md` §7). The
sandbox's own view of `/mnt/TestData` is read-only, so without this mount the programme
is read-path only.

### 3.3 Fairness rules

Normative list in `docs/benchmark-methodology.md` §4 (F1–F15); the six that matter most:

0. **Best configuration, fully disclosed (locked decision D3).** Run each engine with the
   fastest documented configuration *it* supports, and publish the exact flags. Never
   omit a flag, registration, hugepage setting, batching option or backend to make either
   side look better or worse. Examples: fio gets `--registerfiles=1 --fixedbufs=1
   --iodepth_batch_submit` sweep **and** its SQPOLL variant; seastar gets
   `--reactor-backend=io_uring`, THP/hugepages when available, pinned `--io-properties`;
   JUring gets `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` + registered files/buffers.
   Capabilities the other side does not have (fixed buffers, DEFER_TASKRUN) are reported
   as engine differences, never silently equalised.
1. **fio batching sweep + fixed buffers.** fio defaults to `iodepth_batch_submit=1`;
   JUring fuses submit+wait. Without `--iodepth_batch_submit=1,8,16,32` and
   `--registerfiles=1 --fixedbufs=1` you are measuring batching/registration, not the
   engine.
2. **Equal core budget and SMT discipline.** This host: physical cores 0–15, SMT
   siblings 0/16 … 15/31. Use CPUs 1..N for N shards, never put two shards on siblings,
   and count `JUringBlocking` poller threads in the budget.
3. **Document seastar's asymmetries, don't hide them.** 200-entry ring, no
   `SINGLE_ISSUER/COOP_TASKRUN/DEFER_TASKRUN`, no fixed files/buffers. Report seastar as
   a bracket; only claim "faster/slower" for engine-matched comparisons.
4. **Same file, offsets, alignment, O_DIRECT, per-thread QD, duration, warmup.** Use the
   same dataset; for seastar multi-shard, state the per-shard-file difference.
5. **Manifest + gate.** Kernel, governor, device, mount, commits, JDK, flags, loadavg
   before/after, and raw per-rep data in every artifact. A cell missing the manifest is
   not citable.

### 3.4 Statistics

From methodology §7: R=5 read reps (R=3 writes), discarded warmup, mean ± 95% CI plus
min/max/CV, no silent trimming; interleave engines to cancel drift; a difference is real
only if the ratio/paired-difference CI excludes the parity band. Decision rule:

- **parity** = 90% CI of the JUring/comparator ratio inside `[0.95, 1.05]` (TOST-style);
- **faster/slower** = 95% lower/upper bound beyond ±5%;
- otherwise **"inconclusive at this power"** — quote the achieved CI half-width.

The pass/fail thresholds in `docs/benchmark-methodology.md` §7 are the contract for
milestones M1 and M3 (see the numbering note in §5); this plan's M2 stream threshold is
its own.

### 3.5 Harness

```
bench/
  juring/JUringFileBench.java   # JUring runner, io_tester-compatible workloads, JSON out
  juring/run-juring.sh          # mvn compile + javac + run
  seastar/seastar_file_bench.cc # seastar runner on an existing file (same JSON schema)
  seastar/build-file-bench.sh   # build it against SeaStarRef/build/release
  seastar/conf/*.yaml           # io_tester configs mirroring W1–W14, W23
  seastar/build-and-run.md      # build seastar (host + sandbox recipes) + run commands
  run-comparison.sh             # one-command driver (JUring + fio + optional seastar)
  parse_results.py              # JUring/seastar/fio/io_tester -> common CSV/table
  results/YYYY-MM-DD/           # raw JSON/YAML + manifest + notes
```

Sandbox build support: `.tools/` (CMake/Ninja) and `.sysroot/` (Fedora 44 dev packages)
are local build plumbing, gitignored, and not part of the deliverable.

What runs where: JUring, fio and seastar all run in this sandbox on the read path.
seastar is built locally from the in-repo `SeaStarRef/` check-out against a
self-contained dependency sysroot (`.sysroot/`, sandbox-only) — see
`bench/seastar/build-and-run.md`. On a normal host the simpler path is
`sudo ./install-dependencies.sh` + `configure.py` + `ninja`. Write cells and
`io_tester --storage` need the writable `./nvme-data/` bind mount (host step).

### 3.6 What L1 and L2 still need (locked decision D3)

Today's harness gives a fair **L0** comparison. These are the additional pieces, in
order, for L1 and L2 — nothing here needs new seastar-side instrumentation beyond one
small program.

**L1 — file/stream API**
1. **A seastar-side runner on an existing dataset — implemented.** `io_tester` creates
   and prefills its own files, so it cannot use the 10 GiB read-only file and its setup
   perturbs device state. `bench/seastar/seastar_file_bench.cc` (+
   `bench/seastar/build-file-bench.sh`) opens the same file read-only, issues
   `dma_read`/`dma_write` with N in-flight per shard, and emits the same JSON fields as
   `JUringFileBench` (IOPS/MiB/s/percentiles), so `dma_read` vs `readFixed` is
   apples-to-apples. First results are already in §2.1; multi-shard mode is the next
   increment.
2. **One latency definition for both engines.** Both apps timestamp just before
   submission and at completion; store the definition in the report. Never compare
   `clat` to prep→completion.
3. **Best-config disclosure** (F0): seastar uses io_uring + THP/hugepages + pinned
   io-properties; JUring uses its ring flags + registered files/buffers. Missing
   capabilities are reported as engine differences.
4. **Buffered/stream L1 needs B2 first.** Until JUring has a stream layer, buffered
   `fstream` comparison is a capability gap, not a benchmark. Seastar side:
   `fstream_perf` + a custom stream app.
5. **Semantic parity for correctness claims:** short I/O, EOF, error taxonomy — needed
   before any "same durability/error behaviour" statement, not for throughput cells.

**L2 — runtime / scaling**
1. **JUring multi-ring runtime (B4).** Today "L2" is N Java threads each with a ring —
   that is a user pattern, not a library feature. Until B4, label L2 results
   "manual N-thread pattern vs seastar N-shard reactor" and do not claim framework
   parity from them.
2. **Per-shard observability (B5).** seastar reports io_queue latency/queue depth from
   inside; JUring needs per-ring counters/histograms to compare tails and detect
   stragglers.
3. **Identical shard/CPU/QD budget and layout.** Same physical cores, per-shard QD, and a
   decision on per-shard files: seastar creates one file per shard; add a per-thread file
   split mode to `JUringFileBench` (or document the shared-file difference explicitly).
4. **Mixed workload mode** in `JUringFileBench` (read/write split) to match seastar's
   `multi_job_mixed.yaml` and fio `--rw=randrw --rwmixread=70`.
5. **QoS/backpressure is out of "basics" scope** (B8 conditional); do not compare it
   until a concrete user requirement exists.

---

## 4. Catch-up roadmap: "the basics"

Ordered by leverage. Each item has an acceptance test measured with the harness above.
Effort: S < 1 person-week, M = weeks, L = months. Gap IDs refer to
`docs/juring-inventory.md` G1–G12.

### B0. Reproducible benchmark harness + perf gate — **S, do first** (G8)
**Now:** JMH suite only, no runner, no CI, unreproducible seeds, published configs that
don't match the source.
**Do:** keep `bench/` as the single source of truth; fix seeds; record manifests; add a
nightly/manual perf gate that fails on >10% regression for W1/W3.
**Accept:** one command reproduces the published tables from raw JSON; a stored baseline
is compared per commit. **Milestone M0.**

### B1. Async file handle abstraction — **S/M** (G1, G6, G10)
**Now:** `FileDescriptor` + raw `prepare*`; no truncate/allocate/discard/stat; no
short-IO policy; no typed errors; no unregister.
**Do:** `JuringFile`: open with flags/options, offset `read`/`write`, `length`,
`truncate`, `allocate`, `discard`, `fsync`, `close` (drain in-flight, never block
forever), `-errno` → typed exception, short-IO/EAGAIN policy, registration lifecycle
(unregister files/buffers, add/remove at runtime). Keep the fixed-buffer fast path.
**Accept:** conformance tests (seq/rand, EOF, short IO, close-with-inflight); L0 numbers
unchanged; zero-allocation hot path preserved.

### B2. Buffered stream layer — **M/L, biggest gap** (G1)
**Now:** nothing between "raw SQE" and "user writes their own pipeline".
**Do:** `Input`/`Output` streams over `JuringFile`: configurable buffer size, adaptive
readahead (seastar uses slow-start + dynamic adjustment, `fstream.cc:106-235`),
write-behind depth, `flush`/`close` semantics, preallocation hints, complete-until-done
semantics. Model the API on `fstream` but do not port all its complexity — goal is
usability + comparability.
**Accept:** W21/W22 run; seq 1M read/write ≥85% of seastar `fstream`; bounded memory
(configurable max in-flight); correctness tests for partial writes/EOF. **Milestone M2.**

### B3. Request lifecycle hardening — **M** (G4, G6)
**Now:** `prepareCancel` only (untested); no timeout op; link flags pass-through only;
`close()` drains+cancels via `ASYNC_CANCEL_ALL|ANY` but can block indefinitely if an op
never completes; no completion-after-cancel contract; no bulk cancel API.
**Do:** timeout op (incl. linked timeouts), **defined** cancel guarantees (every
cancelled request gets a completion), link-chain helper, queue-full/CQ-overflow
contract, bounded shutdown (drain or cancel, never hang), `user_data`/`Result` safety
audit, error taxonomy. Note: seastar has cancellation but no per-op timeout either —
timeouts are a JUring addition, not parity.
**Accept:** stress test cancelling 50% of in-flight + closing with 10k in flight — no
leaks, no hangs, deterministic completions; documented semantics.

### B4. Multi-ring / shard runtime — **L** (G2)
**Now:** users hand-roll one ring per thread; `JUringBlocking` covers the blocking/VT
style with 1–4 pollers and no affinity.
**Do:** a thin `IoShard`/`RingGroup`: ring per thread, work distribution (RR/hash),
cross-thread completion handoff (MPSC + eventfd), batched poll loop, ring pool, graceful
shutdown; integrate with `JUringBlocking`. Library, not a scheduler.
**Accept:** W17–W20: 8 threads within 85% of `fio numjobs=8`; per-shard isolation;
documented threading contract. **Milestone M3.**

### B5. Observability — **M** (G5)
**Now:** benchmark-only counters.
**Do:** per-ring submitted/completed/in-flight/reaped, SQ/CQ occupancy, error counts,
latency histogram (off hot path), JMX/Micrometer binding; the benchmark JSON includes
them.
**Accept:** metrics at <1% throughput cost; M3 report shows per-ring queue depth and p99.
**Milestone M3.**

### B6. Buffered I/O parity + durability — **M** (G12)
**Now:** buffered ops exist but have no readahead policy, no fadvise hooks, no durability
abstraction; there is no group-commit helper, so any write-heavy user re-implements fsync
policy by hand.
**Do:** buffered stream semantics on B2, `posix_fadvise` hints, durability modes
(writeback vs `fsync`/`fdatasync`/`O_DSYNC`), and a group-commit helper (batch N writes
into one `fdatasync`) — the same primitive seastar exposes through `file::flush()` +
`durable` open options.
**Accept:** buffered comparison vs seastar `fstream` (W21/W22); durability modes tested;
group-commit test proves one fsync for a batch and correct visibility after restart.

### B7. Provided-buffer ring + multishot — **M** (G9, conditional)
**Now:** `IOSQE_BUFFER_SELECT` exposed but no `IORING_REGISTER_PBUF_RING`/buf-group API;
no multishot, no `IORING_CQE_F_MORE` handling.
**Do:** only if measurements show syscall/CPU cost matters: provided buffer rings and
multishot ops (readv multishot only where the kernel supports it).
**Accept:** syscalls/op measured down vs current path at equal workload; no regression.

### B8. QoS / intent — **L, conditional** (G3)
**Now:** absent (SQE ioprio accessor never set; no scheduler).
**Do:** only if real users (e.g. a future framework's compaction vs foreground) need it: minimal
weighted fair queuing across classes, intent-like cancel-on-drop, optional rate limits.
Otherwise document "bring your own scheduler" and keep it out of v1.
**Accept (if built):** two-class experiment honours shares within ±10%, overhead ≤5% at
QD64, no starvation. **Milestone M4.**

### B9. API stability, docs, examples — **S** (G7)
**Now:** README is good, but no compatibility policy, sparse Javadoc, no migration story.
**Do:** semver/compat policy for the public API; finish Javadoc; examples mapping to
seastar use-cases (direct file, stream copy, many files, VT); upgrade notes.
**Accept:** `mvn javadoc` clean; written compatibility promise; examples run in CI.

### Explicit non-goals (for now)

- A futures/coroutines runtime, task scheduler, or work-stealing actor model.
- Network stack / HTTP / RPC parity (JUring's socket ops were removed in the rewrite;
  the stated focus is random direct file I/O).
- Prometheus server, DPDK, hugepage allocator, cgroups integration.
- L3 application/framework comparison (that belongs to the separate flagship project).

**Rationale:** each is a multi-month project that pulls JUring toward "a worse seastar".
Revisit only when a concrete user requirement forces it.

**Scope note.** The flagship seastar-alternative framework is a separate project and is
not part of this plan. JUring stays library-first: it supplies the engine plus the
L1/L2 basics (file/streams/lifecycle/multi-ring/metrics); the framework supplies the
scheduler, network stack, application model and QoS. That boundary is why the roadmap
stops at B9.

---

## 5. Milestones and pass/fail criteria

| Milestone | When | Deliverable | Pass criteria |
|---|---|---|---|
| **M0 — Measure** | week 0 — **done 2026-11-13** | harness + baseline + seastar built | one-command reproduction; seastar runs (`io_tester` + `seastar_file_bench`); manifest complete; first L0 read matrix collected |
| **M1 — L0 parity** | weeks 1–2 — **read path PASS 2026-11-13** (`docs/m1-report.md`); write half pending | L0 report vs fio + seastar | read and write cells: ratio 90% CI inside `[0.90, 1.10]` and point estimate inside `[0.95, 1.05]` vs fio (matched batching + registration); p99 ≤1.10× fio, p999 ≤1.15× **measured with one common latency definition** (today's definitions differ); seastar reported as bracket with asymmetry table; every claim has a CI; QD64 reps classified by run state (§6) and all reps reported |
| **M2 — L1 file/stream** | weeks 3–5 | B1+B2 + W21/W22 | seq 1M read/write ≥85% of seastar `fstream`; bounded memory; correctness suite green; L0 unchanged |
| **M3 — L2 scaling + metrics** | weeks 6–9 | B4+B5 + W17–W20 | 8-thread ≥85% of `fio numjobs=8`; SE(8) ≥0.75; total IOPS vs seastar uring 90% CI LB ≥0.90; per-shard min/mean ≥0.85; p99 at 8 ≤1.15× 1-shard; metrics <1% overhead |
| **M4 — mixed/QoS** | weeks 10–12 | B8 experiment + report | shares honoured ±10% with ≤5% overhead, no starvation — **or** a documented decision to keep QoS out of v1 |

**Numbering note.** `docs/benchmark-methodology.md` calls its multi-shard pass criteria
"M2"; that is **plan M3** here. Methodology M1 = plan M1. The stream threshold is
plan-M2-specific and not in the methodology's M1/M2 tables.

If M1 shows seastar's io_uring backend at parity with JUring (plausible when both hit
the device ceiling), the message shifts to "the engine is done; the gap is all above the
engine" — which is exactly why M2–M4 exist.

---

## 6. Risks, threats to validity, open questions

**Execution risks**
- **seastar build on Fedora 44 is unproven.** `install-dependencies.sh` targets
  Debian/Ubuntu-style and Fedora packages; boost/liburing/fmt versions may need
  `--cook` fallbacks. Budget a day; pin the commit; verify `Reactor backend: io_uring`
  in the startup log. *Fallback:* container/VM with a writable copy of the tree.
- **io_tester's defaults silently change the question.** `--smp` defaults to one shard
  per CPU (a "QD64" run becomes 32 rings × 64 in-flight), `parallelism` defaults to 0
  (silent 0 IOPS), `data_size` is divided across shards, the ring is 200 entries, and
  there is no `seqwrite` type (use `overwrite`). Always pass `--smp 1 --cpuset 0` for
  single-thread parity and scale `data_size` with shards.
- **`asymmetric_io_uring` needs `--async-workers-cpuset`** and SQPOLL privileges; treat
  it as an optional third seastar configuration.
- **`io_tester` writes its own files** (one per shard, prefill with 256 KiB chunks), so
  dataset identity differs from the JUring pre-created file unless `--keep-files` is
  used. Document the difference or add a small custom seastar app (open fixed file,
  `dma_read`) for exact parity as a P2 follow-up.
- **Device state dominates writes.** Preconditioning + SLC-cliff time series + rest
  periods + all-reps reporting, or restrict headline claims to the read path until the
  write protocol is stable.
- **JVM effects:** JIT warm-up, GC, deoptimisation, FFM linkage. Fresh JVM per rep,
  fixed heap, warmup ≥10 s, GC logs in the manifest.
- **Bimodal run state on this host.** At QD64 the same binary was measured at ~255k
  (p99 1.7–2.1 ms) and ~325k (p99 ~0.5 ms) IOPS in different runs; fio's first QD64 rep
  showed the same ~255k state. GC, harness accounting (0 errors), CPU frequency
  (4.2–4.5 GHz) and load (<1.2) were ruled out; the cause is not identified
  (device/inner state or brief external interference). **M1/M3 must gate on run state**
  — e.g. require p99 < 600 µs and repeat-until-stable, or classify reps and report both
  states — otherwise a single slow sample swings 25% and swamps any 5% engine delta.
  See `docs/baseline-2026-11-13.md` §6.
- **Sandbox limits:** `/mnt/TestData` is read-only here → write cells need the host-side
  `nvme-data/` bind mount (`docs/HANDOVER.md`). seastar *is* now built here (no root,
  local sysroot); on a host use the supported `install-dependencies.sh` path.

**Threats to validity**
- Comparing at different layers, QDs, thread counts, or dataset states.
- Using an io_uring configuration difference (flags, queue length) as if it were an API
  difference — or hiding it.
- Publishing point estimates from overlapping CIs.
- Concluding "faster" at device saturation, where both engines wait on flash.
- Mixing fio's time-window estimator with JMH's ops-per-invocation estimator.

**Open questions for David**
1. **Identity** — answered: library-first; the framework is a separate project.
2. **Scope of "basics"** — answered: B0–B9; L0/L1/L2 basics are the target.
3. **Publishing** — answered: transparent, fio as referee, all cells including seastar wins.
4. **Hardware:** publish on this 3950X + GIGABYTE NVMe, or a server platform? Absolute
   numbers are device-specific; the methodology transfers. (Recommended: publish this
   machine now, repeat the matrix on server hardware when available.)
5. **Write protocol timing:** the write matrix is host-only today (the sandbox mounts the
   data device read-only). Decide when to run it once a writable NVMe data directory is
   available; the read-path M1 can ship first.

---

## 7. Decision log

All five decisions are locked (2026-11-13):

| # | Decision | Locked answer | Plan change |
|---|---|---|---|
| D1 | Library-first vs framework | **Library-first**; flagship seastar-alternative is a separate project | Target L0 + L1/L2 basics only; no scheduler/network/application code in JUring |
| D2 | "Basics" roadmap B0–B9 | **Approved**, B7/B8 conditional | Roadmap stands |
| D3 | Transparent, fair comparison | **Yes** — best configuration for every engine, fully disclosed; fio referee; all results published | Fairness rule F0 added (§3.3); L1/L2 extra requirements in §3.6 |
| D4 | Build seastar here | **Yes** — checkout is now `SeaStarRef/` in-repo | M0 moves from "host-only" to "runnable in this workspace"; seastar numbers are in M1 |
| D5 | Perf gate | **Manual** is fine | B0 corrected: stored baseline + manual gate, no CI service |
| — | blackpanda | **Ignore** for this effort | Blackpanda-driven ordering removed |

---

## 8. Appendix

### A. Building and running seastar

The checkout is in this repo at `SeaStarRef/`. Two paths:

**A.1 Normal host (recommended): distro packages + sudo.**

```bash
cd SeaStarRef
sudo ./install-dependencies.sh            # Fedora: dnf; see BUILD.md
./configure.py --mode=release --without-tests --without-demos --disable-dpdk
ninja -C build/release
```

**A.2 This sandbox (no root, no writable /usr): local dependency sysroot.**

Already bootstrapped in the workspace: `.tools/` (cmake 3.31.6, ninja 1.12.1) and
`.sysroot/` (Fedora 44 `-devel`/runtime packages extracted from rpms). Configure with
the sysroot env, then build:

```bash
cd SeaStarRef
SYS=$PWD/../.sysroot/root/usr
export CMAKE_PREFIX_PATH=$SYS PKG_CONFIG_PATH=$SYS/lib64/pkgconfig:$SYS/share/pkgconfig
export PKG_CONFIG_SYSROOT_DIR=$PWD/../.sysroot/root CPATH=$SYS/include
export LIBRARY_PATH=$SYS/lib64 LD_LIBRARY_PATH=$SYS/lib64
export PATH=$SYS/bin:$PWD/../.tools/cmake/bin:$PWD/../.tools/ninja:$PATH
./configure.py --mode=release --without-tests --without-demos --disable-dpdk \
  --disable-lttng --disable-gnutls --enable-openssl \
  --compiler /usr/bin/g++ --c-compiler /usr/bin/gcc \
  "--cflags=-I$SYS/include" "--ldflags=-L$SYS/lib64 -Wl,-rpath,$SYS/lib64"
ninja -C build/release -j16
```

This is a sandbox workaround, not a supported build recipe; on a host use A.1.

**A.3 Running workloads (host or sandbox)**

```bash
# Writable O_DIRECT-capable data dir for io_tester (it creates test-* files)
mkdir -p /mnt/TestData/seastar-bench

# L0 single-shard randread via the custom runner (same file as JUring; see §3.6)
build/release/bench/seastar_file_bench \
  --file /mnt/TestData/read_files/single_file_bench.bin \
  --bs 4k --qd 64 --seconds 10 --warmup-sec 2 --readonly \
  --reactor-backend io_uring --smp 1 --cpuset 2 --json out.json

# io_tester equivalent (needs a writable storage dir; --reactor-backend io_uring)
build/release/apps/io_tester/io_tester \
  --storage /mnt/TestData/seastar-bench \
  --conf bench/seastar/conf/randread_4k_qd64.yaml \
  --duration 30 --smp 1 --cpuset 2 --reactor-backend io_uring --keep-files=true
# confirm "Reactor backend: io_uring" in the log

# Backend brackets: --reactor-backend linux-aio
#                   --reactor-backend asymmetric_io_uring --async-workers-cpuset 16

# Buffered streams (after B2): build/release/tests/perf/fstream_perf \
#   --concurrency=16 --buffer-size=65536 --total-ops=100000
```

Exact schema, output fields and fairness knobs: `bench/seastar/build-and-run.md` +
`docs/seastar-inventory.md` §D–F.

### B. Artifact map

| Path | Purpose |
|---|---|
| `SEASTAR-PLAN.md` | this document |
| `docs/seastar-inventory.md` | seastar file-I/O stack + benchmarks (evidence) |
| `docs/juring-inventory.md` | JUring capabilities/tests/benchmarks + gap analysis |
| `docs/benchmark-methodology.md` | matrix, fairness rules, statistics, JSON schema |
| `docs/baseline-2026-11-13.md` | today's measured numbers (fio + JUring, read path) |
| `bench/` | runnable harness and reproduction scripts |
| `bench/results/2026-11-13/` | raw JSON/YAML from today's runs: `fio/`, `juring/`, `seastar/`, `l0-combined.csv` |
| `docs/HANDOVER.md` | next-session handover (commands, state, ordered next steps) |
| `nvme-data/` | host bind-mount of a writable data-NVMe dir (gitignored; not yet set up) |
| `SeaStarRef/` | in-repo seastar checkout @ `04b1c04d` (own git; build local-only) |
| `.tools/`, `.sysroot/` | local sandbox build plumbing for SeaStarRef (gitignored) |

### C. References

- seastar file API `include/seastar/core/file.hh`; streams `include/seastar/core/fstream.hh`; I/O queue `include/seastar/core/io_queue.hh`; intent `include/seastar/core/io_intent.hh`; backends `src/core/reactor_backend.{hh,cc}`; benchmark app `apps/io_tester/io_tester.cc`; perf test `tests/perf/fstream_perf.cc`.
- JUring `src/main/java/com/davidvlijmincx/lio/api/JUring.java`, `JUringBlocking.java`, `IoUringDispatcher.java`; benchmarks `src/test/java/bench/suite/`; results `README.md`, `BENCHMARK.md`.
- Kernel: `man io_uring_setup`, `man io_uring_enter`; `IORING_SETUP_*`, `IORING_OP_*`.

### D. Glossary

- **L0–L3:** comparison layers (§1).
- **QD:** queue depth — in-flight I/Os; seastar `parallelism`.
- **Referee:** `fio`, a third-party engine anchoring both sides.
- **`DEFER_TASKRUN` / `SINGLE_ISSUER` / `COOP_TASKRUN`:** io_uring setup flags that cut
  syscall/task-work overhead; JUring uses them, seastar's ring does not.
- **`fstream`:** seastar's buffered file streams with readahead/write-behind.
- **`io_intent`:** seastar's cancellation/priority handle for I/O.
