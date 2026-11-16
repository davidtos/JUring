# B7 decision gate — provided-buffer ring + multishot (frozen before measurement)

**Status:** frozen gate contract, session 10 (2026-11-15), written **before any B7 probe,
probe source, or measurement exists**. This file defines the question, the frozen decision
rule and the measurement protocol. It may only be clarified by appended Lead amendments
before measurement; the rule itself does not change after raw data exists.

**Plan items covered:** `SEASTAR-PLAN.md` §4 **B7** (provided-buffer ring + multishot,
conditional, gap G9) and the related claim in `docs/juring-inventory.md` G9.

## 0. Why a gate

The plan makes B7 conditional: *"Do: only if measurements show syscall/CPU cost matters."*
B7's own acceptance is *"syscalls/op measured down vs current path at equal workload; no
regression."* This document freezes how "cost matters" is decided **before** looking at the
answer, so the outcome cannot be steered by the measurement.

Two already-known facts make the gate non-trivial and are **inputs, not results** (they were
on record before this session: `docs/m1-report.md` §8 and `SeaStarRef/src`):

- At the M1 gate cell (W3: 4K randread QD64, 1 thread, registered file, fixed buffers)
  JUring measured **0.330 syscalls/op, 11,335 cycles/op**; `fio` matched **1.012 syscalls/op,
  15,227 cycles/op**; seastar **0.186 syscalls/op, 13,859 cycles/op**. JUring is already
  ~26 % cheaper in cycles/op than `fio` and ~18 % cheaper than seastar despite having more
  syscalls/op than seastar.
- The inventory's G9 rationale says *"seastar read paths use registered buffer rings and
  multishot to cut syscalls"*. That statement is **unverified in this repo** and is checked
  here as evidence E0.

## 1. Question (frozen)

Does B7 — a kernel **provided-buffer ring** (`IORING_REGISTER_PBUF_RING`) plus **multishot
ops** (`IORING_OP_READ_MULTISHOT`, `IORING_CQE_F_MORE`) — address a measured capability or
cost need for a workload **in JUring's current scope** (regular-file I/O: random/sequential
`O_DIRECT` reads and writes on Linux x86_64, driven through JUring's `lio.api`)?

Out of scope by the plan itself (§4 non-goals, D1): sockets/pipes/network, a futures runtime,
QoS. A B7 that only helps pollable-socket-style fds is out of scope regardless of how well
it works.

## 2. Facts the gate must establish (evidence E0–E3)

| id | claim to establish | method | expected if the plan premise holds |
|---|---|---|---|
| **E0** | seastar's file-I/O path does **not** rely on provided buffer rings or multishot; its syscall/op advantage comes from batching, not B7 features | pinned-commit source audit: `grep` `SeaStarRef/` for `IORING_REGISTER_PBUF_RING`, `io_uring_buf_ring`, `IOSQE_BUFFER_SELECT`, `MULTISHOT`, `io_uring_prep_read`/`readv` usage in `src/core/reactor_backend.*`; cite `path:line` | premise false → B7 is not a seastar-comparison gap |
| **E1** | `IORING_OP_READ_MULTISHOT` cannot serve regular-file reads on this kernel (multishot is restricted to pollable fds) | C probe: pbuf ring + multishot read on the regular 10 GiB file, and on a pipe as a positive control; cite liburing `io_uring_prep_read_multishot(3)` and kernel header | regular file fails (`-EBADFD` or equivalent); pipe works |
| **E2** | JUring has **no** in-scope workload that could use multishot (no socket/pipe ops) | source/API audit: `OperationType`, `JUring.prepare*`, README scope; plan non-goals | no applicable workload |
| **E3** | provided-buffer selection (without multishot) does not reduce syscalls/op on a regular-file read path, and has no material CPU/op or throughput benefit vs JUring's current registered-fixed-buffer path at equal workload | C A/B probe (§4) | syscalls/op equal; cycles/op within noise |

## 3. Frozen decision rule (pre-registered)

Evaluate in order; the **first** matching branch decides.

1. **E1 shows multishot is usable on regular files on this kernel** (probe returns a valid
   data completion for the regular-file arm, not an error), **and** a like-for-like
   measurement at equal workload shows **syscalls/op reduced by ≥ 10 %** vs the current path
   with **no throughput regression** (one-sided 95 % LB of multishot/current IOPS ratio
   ≥ 0.95) → **IMPLEMENT B7** (multishot part first).
2. **E3 shows there is no multishot path to test (E1 restricts it to pollable fds)**, and
   provided-buffer selection alone shows **either** (a) no syscalls/op reduction
   (guaranteed a priori: selection cannot change `io_uring_enter` count) **and**
   (b) no cycles/op improvement of **≥ 5 %** at equal workload (one-sided 95 % LB of
   current/pbuf cycles/op ratio ≥ 0.95) → **DEFER B7** with a documented decision; no
   engine change; G9 recorded as *not warranted for the file-I/O scope*.
3. If provided buffers alone show a **cycles/op improvement ≥ 5 %** with no throughput
   regression, the Lead may **narrow B7 to the provided-buffer part only**, with a new
   (amended) acceptance clause written before the implementation starts. This is the only
   way branch 2 can be overturned, and it requires measured data, not judgement.

**Interpretation rule:** "cost matters" is a measured, pre-declared threshold (≥10 %
syscalls/op or ≥5 % cycles/op at equal workload), not "CPU is visible in `perf`". Any
decision-class statement that cites a smaller effect is not a B7 rationale.

## 4. Measurement protocol (frozen for E3; gate measurement, not a milestone campaign)

**Workloads** (the two M1-relevant shapes, on the aged 10 GiB read file):
- **G1:** 4 KiB random `O_DIRECT` reads, QD 64, 1 thread — the M1 gate cell (W3).
- **G2:** 1 MiB sequential `O_DIRECT` reads, QD 8, 1 thread — the M1 seqread cell (W10/11).

**Arms** (identical fd, offset stream, batch size, and submission/wait policy):
- **A (current path):** registered fixed buffers + `IORING_OP_READV`/`READ` (JUring-equivalent
  fixed-buffer path; a plain registered-buffer arm is acceptable if the probe documents the
  mapping).
- **B (B7 candidate):** provided buffer group (`IORING_REGISTER_PBUF_RING`) +
  `IOSQE_BUFFER_SELECT` reads; the buffer ring is refilled from CQEs exactly like a
  production implementation would.

**Environment / controls:** same file `/mnt/TestData/read_files/single_file_bench.bin`
(read-only, never written), `taskset -c 2`, CPUs quiet (Lead coordinates a no-build,
no-other-benchmark window; any concurrent load is recorded), 10 s warmup + 30 s measured per
run, R = 3 alternating A/B runs (order alternates A-B / B-A / A-B), fresh process per run,
`perf stat -x,` (cycles:u,cycles:k,instructions,raw_syscalls:sys_enter) over the measured
window on the probe's process tree. Record: IOPS, MiB/s, p50/p99, syscalls/op, cycles/op,
instr/op, all raw reps (no trimming), CV, and the capability-probe outputs.

**Analysis:** paired A/B ratios per rep and a one-sided 95 % lower bound (t distribution,
n=3, or the documented normal approximation); device state is **not** a gate here — the
read-only file is the same aged dataset as M1 (state-control run not required for a paired
CPU-cost A/B; disclose it). The decision uses cycles/op and syscalls/op, not IOPS ranking.

## 5. Required artifacts

- `scratch/b7-gate/` — probe sources, build/run scripts, raw logs (`perf` TSVs, probe
  JSON/TSVs, capability outputs), and `RESULTS.md` with the evidence tables E0–E3, all reps,
  the computed ratios/CIs, and the branch the data selects.
- Frozen input: **this file** (`docs/b7-gate.md`), sha1 recorded in
  `scratch/b7-gate/FREEZE.txt` before probes exist.
- Decision output: `docs/b7-decision.md` (Lead) — the gate result, evidence pointers, the
  decision (implement / narrow / defer), and what would reopen B7.
- If the decision is DEFER: no `src/` change from B7; the plan's G9 is updated to record the
  measured rationale rather than an assumed seastar gap.

## 6. Disclosures to carry

- Probe is a C/liburing model of JUring's submission policy, not the Java engine itself. It
  is the correct instrument for *kernel feature capability + per-op cost*; a DEFER decision
  therefore rests on capability (E1/E2) and on the absence of a syscall-count mechanism, not
  on Java-vs-C constants. If branch 1/3 is selected, the Java implementation and its own
  measurement follow the normal design→implement→verify flow.
- E3's pbuf arm also pays buffer-ring refill cost; both arms pay their own buffer bookkeeping.
- Device data state / bimodality: disclosed, not gated (paired CPU-cost comparison).
- This gate does not re-open M1/M2/M3 sign-offs.

### A1 — 2026-11-15 (session 10, **before any E3 timed data exists**; G2 arm-A buffer mechanism)

**Trigger:** b7-gate reports that frozen §4 arm A ("registered fixed buffers") is impossible
for workload **G2** (1 MiB seqread, QD 8, 1 thread) on this host without privilege:
8 × 1 MiB = 8 MiB of registered buffers, while `RLIMIT_MEMLOCK` is **8 MiB soft = hard**
(cannot be raised in this sandbox); registration returns `-ENOMEM` for the 8th 1 MiB buffer.
This is the same documented constraint as JUring's M1 W10/W11 cells
(`docs/m1-report.md` §6: "qd × 1 MiB exceeds RLIMIT_MEMLOCK = 8 MiB; JUring falls back to
unregistered arena buffers"). Only short correctness smokes and discarded pre-runs exist; no
E3 measured rep for G1/G2 has been produced.

**Amendment (frozen):**
1. **G1 (4 KiB randread QD64) arm A is unchanged**: registered fixed buffers +
   `IORING_OP_READ_FIXED` (the M1 gate cell), which fits in `RLIMIT_MEMLOCK`.
2. **G2 (1 MiB seqread QD8) arm A becomes**: registered **file** + **unregistered arena**
   buffers + plain `IORING_OP_READ` — JUring's documented M1 path for that shape. Arm B
   remains the provided-buffer ring + `IOSQE_BUFFER_SELECT` reads.
3. Both arms stay identical in fd/offset stream, queue depth, batch submission/wait policy
   and warmup/measurement protocol; **only the buffer provisioning mechanism differs**, which
   is exactly the E3 question (does provided-buffer selection reduce per-op cost vs the
   documented current path?). Every G2/A run must print a `DISCLOSURE:` line in the log and
   the analyzer must record `fixed_buffers_registered=0` for that arm.
4. Everything else in §2–§6 (evidence set E0–E3, frozen decision rule, all-reps reporting,
   capacity-probe results, read-only file, quiet-window/perf protocol) is unchanged.
5. The decision rule of §3 is unchanged: branch 1 is evaluated on E1; if multishot remains
   regular-file-inapplicable, branch 2/3 apply to the G1/G2 E3 measurements as amended.

**New sha1** of this file after A1 is recorded in `scratch/b7-gate/FREEZE.txt`; E3 may then
start (quiet window approved by Lead).
