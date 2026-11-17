# Performance investigation — 0.1.0 release candidate (2026-11-17)

Status: internal engineering report. The public numbers live in `BENCHMARK.md`;
the raw sessions are under `bench/results/` (gitignored, archived separately).

## 1. Purpose

1. Confirm that the 0.1.0 shutdown/API hardening batch (bounded stream close,
   no-completion wait rejection, `prepareReadUnique`) did not change read-path
   performance versus the published numbers.
2. Use the same data to look for performance-improvement headroom.

Code under test: `5202e88` (release candidate). The hot raw-ring read path was not
touched by the hardening commits; these measurements are the evidence, not the claim.

## 2. Method

- Claims control run: `REPS=5 bench/m1/run-claims-0.1.sh` — W3 (4 KiB randread QD64) and
  W4 (QD128), rotated engine order, fio `matched` and `matchedbatch` references.
  The clean session is `bench/results/claims-0.1-20261117/read-matrix-{matched,matchedbatch}`
  (30 cells each; aggregate JSON/markdown next to each sub-session). The first run
  (`bench/results/claims-0.1-20261116`) used the old single-directory harness, so the second
  matrix overwrote the matched matrix's juring/seastar raw cells (all 60 runs remain in its
  `cell-meta.tsv`); that run is kept only as the pre-fix audit trail.
- Per-op counters: `QDS=64 REPS=3 ENGINES='juring seastar fio-io_uring' bash
  bench/m1/run-perf-cells.sh` → `bench/results/perf-claims-0.1-20261117`
  (`perf stat -p <engine>` for 30 s after warmup).
- A/B levers (bench-only knobs added in `929b44d`): `--min-complete 1/4/8/16`
  at W3 QD64 (3 interleaved reps), `--min-complete 1/16/32` at W4 QD128 (1 rep
  each), and `--sqpoll` (SQPOLL ring with `sqThreadIdleMs=0`) at both QDs.
  Logs: `scratch/exp-w3-min-batch.log`, `scratch/exp-perf-main.log`.

## 3. Perf-impact result — no hot-path regression

Clean rotated control run (n=5 each; mean ± 95 % CI of the mean):

| cell | engine | IOPS | paired ratio vs fio | paired ratio vs seastar |
|---|---|---|---|---|
| W3 QD64 (`matched`) | juring | **323,876 ± 8,104** | **1.0655 [1.0167, 1.1143]** | 1.0293 [0.9899, 1.0686] |
| W3 QD64 (`matched`) | fio-matched | 304,160 ± 9,750 | — | — |
| W3 QD64 (`matched`) | seastar | 314,786 ± 7,404 | — | — |
| W4 QD128 (`matched`) | juring | **406,966 ± 19,948**¹ | **1.2183 [1.1596, 1.2771]**¹ | 1.0622 [1.0385, 1.0859]¹ |
| W4 QD128 (`matched`) | fio-matched | 334,074 ± 8,122 | — | — |
| W4 QD128 (`matched`) | seastar | 383,200 ± 19,155 | — | — |
| W3 QD64 (`matchedbatch`) | juring | **323,753 ± 8,575** | **1.0693 [1.0209, 1.1178]** | 1.0501 [0.9787, 1.1215] |
| W3 QD64 (`matchedbatch`) | fio-matchedbatch | 302,930 ± 8,195 | — | — |
| W3 QD64 (`matchedbatch`) | seastar | 308,844 ± 16,057 | — | — |
| W4 QD128 (`matchedbatch`) | juring | **410,833 ± 14,052** | **1.2306 [1.1694, 1.2918]** | 1.0625 [1.0106, 1.1144] |
| W4 QD128 (`matchedbatch`) | fio-matchedbatch | 334,025 ± 8,714 | — | — |
| W4 QD128 (`matchedbatch`) | seastar | 386,854 ± 10,835 | — | — |

¹ One JUring W4 rep (`r3`, 385,000 IOPS) and one Seastar rep were flagged slow by the
`< 0.92 × max` rule; the fast subset is `1.2275 [1.1696, 1.2854]` vs fio and
`1.0594 [1.0349, 1.0839]` vs Seastar (n=4).

Published 0.1.0 baseline: W3 juring 327,371 (± 280), ratios 1.0771 (fio) /
1.0284 (seastar); W4 juring 411,136 (± 19,180), ratios 1.2388 / 1.0543.
Deltas: **W3 −1.1 %, W4 −1.0 % (matched) / −0.07 % (matchedbatch)** absolute. All comparator
cells moved with the same host-state noise (`fio-matched` 304,160 vs signed 304,064; Seastar
314,786 vs 318,317; W4 Seastar 383,200 vs 389,967), and the paired ratios reproduce within
1.1 % relative (W3 fio ratio 1.0655 vs 1.0771; W4 1.2183 vs 1.2388). The `matchedbatch`
reference is **not faster** than `matched` (302,930 vs 304,160 W3; 334,025 vs 334,074 W4), so
fio's default submit batching was already representative; the remaining asymmetry is JUring's
ring flags/fused submit.

Per-op counters at W3 QD64 (mean of 3):

| engine | cycles/op | user | kernel | instr/op | syscalls/op |
|---|---|---|---|---|---|
| juring | 11,438 | 662 | 10,777 | 7,781 | 0.327 |
| seastar | 14,118 | 2,233 | 11,884 | 9,830 | 0.189 |
| fio-matched | 14,324 | 2,208 | 12,116 | 10,274 | 0.998 |

Published: juring 11,335 cycles/op / 0.330 syscalls/op; fio-matched 15,227; seastar 13,859.
**Conclusion: no measurable hot-path regression.** CPU per op is within run spread,
userspace stays ~3.4× cheaper than Seastar/fio, and the small absolute deltas are
host-state noise (mirrored by every engine), not a code effect.

## 4. Where the time goes (W3 QD64)

- **Userspace is only 662 cycles/op (~6 %).** The Java path (SQE prep, submit,
  reap, `Result` population) is already cheap; there is very little to win here.
- **Kernel is 10,777 cycles/op (~94 %)**, and the other engines pay the same
  (11.7–12.1k). This is the io_uring/block-layer submission path, not library code:
  a library-level optimisation cannot recover most of it.
- `0.327` syscalls/op = one `io_uring_enter` per ~3 completions: `GETEVENTS(min=1)`
  wakes on the first completion, so only a few completions are reaped before the
  next top-up submit. Seastar batches the same path to 0.189.
- W3 is jointly device/CPU-limited: QD64 at a ~197 µs mean completion latency is
  ~325k IOPS, and all three engines converge at 296–325k. W4 (QD128) still has
  headroom; there JUring's lower CPU/op shows up as +10.9 % vs Seastar and +26 %
  vs fio.

## 5. Lever experiments (negative results are results)

**`--min-complete` (completions requested per submit+wait), W3 QD64, 3 interleaved reps:**

| minComplete | IOPS mean (sd) | p99 (r1) |
|---|---|---|
| 1 (default) | **320,291 (11,580)** | 482 µs |
| 4 | 309,201 (16,187) | 480 µs |
| 8 | 303,532 (18,434) | 475 µs |
| 16 | 285,201 (1,576) | 464 µs |

W4 QD128, 1 rep: min1 423,546 (p99 874 µs) → min16 405,237 (809 µs) →
min32 373,523 (756 µs).

Waiting for more completions per enter *does* trim tail latency, but throughput
falls monotonically: the extra waiting starves the submission pipeline faster than
the saved syscalls pay back. **The default `minComplete=1` is the right default**;
batching is a latency/throughput knob for callers that prefer tail over peak.

**`--sqpoll` (SQPOLL ring, idle 0):** W3 63,922 IOPS (−80 %, p99 1,013 µs),
W4 127,775 (−70 %). The current submission/wakeup handling is not usable with
SQPOLL (kernel thread sleeps/wakeups dominate). Not a viable optimisation without
a redesign; parked.

## 6. Improvement candidates, ranked

1. **Kernel/device side (dominant, out of library scope).** 94 % of CPU/op is
   kernel; the remaining throughput headroom at W3 is device/queueing. Options are
   host tuning (NVMe IRQ affinity, `io_poll` on block devices, deeper queues), not
   library code. Documented for users rather than pursued.
2. **Raw completion consumer (user-space only).** A raw CQE callback on `JUring`
   (like the internal `reapRawCqes`) would skip `Result` population. Upside is
   bounded by 662 user cycles/op: likely ≤2–3 % CPU, maybe ~1–2 % IOPS at W4.
   Medium API-surface cost; not worth adding for 0.1.0.
3. **SQPOLL redesign (big effort, uncertain).** Would need correct SQ wakeup
   handling and a completion strategy (blocking enter vs busy-poll). Current path
   is 70–80 % slower; only worth it if it can also cut the 0.33 enters/op. Parked.
4. **Tail-latency guidance.** `minComplete`/reap batching trades throughput for
   p99 (measured above). Better as documentation (`docs/benchmark-methodology.md`,
   BENCHMARK) than as a default change.
5. **Benchmark-harness overhead.** The harness itself timestamps every prepare and
   completion and maintains a latency reservoir; this inflates the measured
   user-space cycles/op but does not change library throughput. Leave as is so the
   historical numbers stay comparable.

## 7. Artifact index

- Claims (clean, sub-sessions): `bench/results/claims-0.1-20261117/read-matrix-{matched,matchedbatch}`
- Claims aggregates: `bench/results/claims-0.1-20261117/read-matrix-*/aggregate/claims.{summary.md,aggregate.json}`
- Claims (first run, all 60 runs in `cell-meta.tsv`; matched juring/seastar raw cells
  were overwritten by the old harness): `bench/results/claims-0.1-20261116`
- Perf counters: `bench/results/perf-claims-0.1-20261117`
- Lever logs: `scratch/exp-w3-min-batch.log`, `scratch/exp-perf-main.log`
