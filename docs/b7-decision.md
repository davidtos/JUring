# B7 decision — provided-buffer ring + multishot: DEFERRED (no engine change)

**Status:** decision record, session 10 (2026-11-15). **Decision: do not implement B7** — no
multishot support, no narrowing to a provided-buffer-ring API, no engine change. Gap **G9**
is reclassified as *not warranted for JUring's file-I/O scope* and its original
seastar-comparison rationale is corrected (see §3.1). The plan's conditional clause for B7
("only if measurements show syscall/CPU cost matters") is answered with measurements.

**Contract:** `docs/b7-gate.md` — frozen before any probe existed (sha1 `deed4136…`), amended
once **before any E3 timed data existed** (Amendment A1: G2 arm-A buffer mechanism;
sha1 `36a19a8c…`). Recorded in `scratch/b7-gate/FREEZE.txt`. The decision rule (§3 of the
contract) was **not** amended after raw data existed.

**Evidence:** `scratch/b7-gate/RESULTS.md` (all 12 runs, no trimming),
`scratch/b7-gate/EVIDENCE-E0-E2.md`, raw data in `scratch/b7-gate/ab/`
(`runs.tsv`, `analysis.md`, per-run JSON/perf/logs), reproducible probes/scripts
(`probe_capability.c`, `ab_pbuf_vs_fixed.c`, `e0_audit.sh`, `e2_audit.sh`,
`run_capability.sh`, `run_ab.sh`, `analyzer.py`).

## 1. Decision

| question | answer |
|---|---|
| Implement multishot (`IORING_OP_READ_MULTISHOT`, `CQE_F_MORE`)? | **No** — kernel rejects multishot on regular files; JUring has no in-scope pollable-fd workload. |
| Implement provided-buffer-ring selection (`IORING_REGISTER_PBUF_RING` + `IOSQE_BUFFER_SELECT`)? | **No** — measured neutral-to-worse vs the current path; no syscall or CPU/op benefit. |
| Narrow B7 to "capability parity only" (API without a measured win)? | **No** — the plan's B7 is conditional on a measured need; adding a third buffer-provisioning mechanism with no measured benefit does not meet that bar. |
| Any `src/`, `pom.xml`, `module-info` change from B7? | **None.** |
| G9 status | Open gap, **not warranted** for the current file-I/O scope; rationale corrected in §3.1. |
| M1/M2/M3 sign-offs | Unaffected. |

## 2. What was measured (summary; full tables in RESULTS.md)

Environment: Fedora 44, kernel 7.0.12, Ryzen 9 3950X, liburing 2.13, `taskset -c 2`, aged
10 GiB read-only file opened `O_RDONLY|O_DIRECT` (never written), quiet window approved by
the Lead (10:06:57–10:15:01), R=3 alternating A/B/B-A/A-B, 10 s warmup + 30 s measured,
fresh process per run, `perf stat` over the measured window, no trimming. 12/12 runs clean
(`probe_rc=0`, `perf_rc=0`).

Arms: **A = current path** (G1: registered file + registered fixed buffers +
`READ_FIXED`, the M1 W3 path; G2: registered file + arena buffers + plain `READ`, the
documented M1 W10/W11 path per Amendment A1), **B = provided-buffer ring + single-shot
`READ` with `IOSQE_BUFFER_SELECT`** (production-like refill of buffers from CQEs). Both arms
keep identical fd/offset stream, QD, submission/wait policy (one
`io_uring_submit_and_wait(1)` per iteration) and ring flags.

| workload | arm | cycles/op | instr/op | syscalls/op | IOPS |
|---|---|---|---|---|---|
| G1 4 KiB randread QD64 | A current (fixed buffers) | **11,156** | 7,249 | **0.3288** | 324,855 |
| G1 | B pbuf selection | 12,404 (**+11.2 %**) | 8,227 (+13.5 %) | 0.2748 (−16.4 %) | 315,804 (−2.8 % point) |
| G2 1 MiB seqread QD8 | A current (arena, A1) | **309,296** | 315,224 | 0.9867 | 4,755.6 |
| G2 | B pbuf selection | 312,310 (**+1.0 %**) | 315,537 (+0.1 %) | 0.9854 (−0.1 %) | 4,756.0 (0.0 %) |

Paired one-sided 95 % lower bounds (t, n=3): G1 cycles/op A/B **0.8961** (pbuf 11.2 % more
expensive, CV 0.22 %); G1 pbuf/current IOPS **0.9237**; G1 syscalls/op A/B 1.1709
(pbuf fewer enters — see §4 D1); G2 cycles/op A/B **0.9777**; G2 pbuf/current IOPS
**0.9959**; G2 syscalls/op A/B 0.9921.

**Model validation:** G1 arm A reproduces the M1 W3 JUring gate cell within −1.6 % cycles/op
(11,156 vs 11,335), −0.4 % syscalls/op (0.3288 vs 0.330) and −0.8 % IOPS (324,855 vs
327,371) — the C/liburing model is a faithful instrument for this per-op cost question.

## 3. Evidence that settles the premise

### 3.1 E0 — seastar does **not** use B7 features (the G9 rationale is unsupported)

At the pinned seastar commit `04b1c04d` (`SeaStarRef/`), source audit over `src/`, `include/`,
`apps/`:

- **no** provided-buffer rings (`IORING_REGISTER_PBUF_RING`, `io_uring_buf_ring`,
  `io_uring_setup_buf_ring`), **no** `IOSQE_BUFFER_SELECT`, **no** multishot flags/op;
- **no** fixed buffers or registered files either — file I/O is plain
  `io_uring_prep_read`/`io_uring_prep_readv` at `src/core/reactor_backend.cc:1363`/`:1373`;
- the ring is created with zeroed params (`io_uring_queue_init_params`,
  `reactor_backend.cc:1236-1302`), no pbuf/fixed-file setup;
- seastar's 0.186 syscalls/op comes from **batching**: one SQ flush + one `io_uring_wait_cqes`
  per reactor iteration and batch CQ reaping (`reactor_backend.cc:1652-1660`,
  `:1709-1721`, `:1752-1771`) ⇒ ≈10.75 I/Os per submit+wait pair, not per-op enters.

**Consequence:** `docs/juring-inventory.md` G9's rationale ("seastar read paths use
registered buffer rings and multishot to cut syscalls; without them the low-syscall-per-op
comparison is incomplete") is **factually unsupported**. B7 is not a seastar-comparison gap;
the L0 comparison is complete without it.

### 3.2 E1 — multishot cannot serve regular files on this kernel

C/liburing capability probe (`probe_capability.c`, log in
`scratch/b7-gate/logs/capability-probe.log`):

| test | CQE `res` / `flags` | verdict |
|---|---|---|
| `READ_MULTISHOT` on the regular 10 GiB file, `O_DIRECT` | **−77 (`-EBADFD`)** / `0x0` | rejected |
| same, buffered `O_RDONLY` | **−77 (`-EBADFD`)** / `0x0` | rejected (fd-type based, not `O_DIRECT`) |
| `READ_MULTISHOT` on a **pipe** (positive control) | 4096 / `F_BUFFER\|F_MORE`, then 4096, 4096, final `0` / `0x0` at EOF | works |
| single-shot `READ` + `IOSQE_BUFFER_SELECT` on the regular file (4 KiB, 1 MiB) | 4096 / `CQE_F_BUFFER` buf-id; 1,048,576 / `CQE_F_BUFFER` | pbuf selection itself is supported |

Kernel/liburing semantics: `io_uring_prep_read_multishot(3)` — *"can only be used with a file
type that is pollable … If used with a regular file … fail with `-EBADFD`"* (available since
6.7); uapi `IORING_OP_READ_MULTISHOT` (`/usr/include/linux/io_uring.h:303`). So the *only*
mechanism that could reduce per-op submission count is unavailable for JUring's file I/O.

### 3.3 E2 — and JUring has no in-scope workload that could use it anyway

`OperationType`/`JUring.prepare*` expose no socket/pipe/recv/send op; sockets are an explicit
plan non-goal and were removed in the rewrite (`README.md:12-14`, `SEASTAR-PLAN.md` §4
non-goals). The only `MULTISHOT` token in the library is `IORING_TIMEOUT_MULTISHOT`
(`JUring.java:31-32`), a timeout re-arm flag unrelated to I/O. There is no public API to set
a buffer group, and `Cqe.getFlags()` never interprets `CQE_F_MORE`. So even if multishot
worked on regular files, JUring has no in-scope workload to apply it to.

### 3.4 E3 — provided-buffer selection alone is neutral-to-worse

With multishot excluded, only pbuf *selection* remains. It does not touch the syscall count
(one `io_uring_enter` per submit/wait iteration in both arms) and it is **not cheaper per
op**: +11.2 % cycles/op at G1 (kernel-side fixed-buffer indexing is cheaper than the
provided-buffer lookup/refill path: cycles:k +11.6 %), +1.0 % at G2, and 2.8 % lower IOPS
(point) at G1. No workload shows the ≥5 % cycles/op improvement that would justify narrowing
B7 to the buffer-ring part.

## 4. Frozen-rule evaluation and the two disclosed rule readings

The frozen decision rule (docs/b7-gate.md §3) is evaluated branch by branch:

| branch | outcome |
|---|---|
| **1 — IMPLEMENT** (regular-file multishot usable + syscalls/op −≥10 % + IOPS LB ≥0.95) | **not selected** — E1 blocks it (multishot unusable); G1 throughput LB 0.9237 < 0.95 as well |
| **2 — DEFER** (multishot restricted to pollable fds + no syscalls/op mechanism + no pbuf cycles/op improvement ≥5 %) | **conclusion selected** — G2 satisfies it directly; at G1 two literal sub-conditions need the disclosed readings D1/D2 below |
| **3 — NARROW to pbuf only** (pbuf cycles/op improvement ≥5 %, no throughput regression) | **excluded** — pbuf is worse at G1 (point 0.8994) and at G2 (0.9903); no improvement exists |

**Disclosed rule-reading defects (not amendable after data exists):**

- **D1 (G1 syscalls/op).** Branch 2(a) assumed "selection cannot change the enter count".
  Measured, the pbuf arm has 16.4 % *fewer* syscalls/op — but `batches/op ≈ syscalls/op` in
  both arms (0.3314 vs 0.3288 for A; 0.2768 vs 0.2748 for B: essentially one enter per
  submit-and-wait iteration, the ≤0.8 % gap being enter accounting), so this is a
  **completion-batching epiphenomenon**: the pbuf arm is slower per op, wakes less often and
  reaps 3.61 vs 3.02 completions per wake (context switches halve). It bought no throughput
  (−2.8 %) and no CPU (+11 % cycles/op) and is not a selectable syscall-reduction mechanism.
- **D2 (G1 cycles/op direction).** Branch 2(b)'s numeric test (one-sided LB of A/B cycles/op
  ≥ 0.95) fails at G1 **only because pbuf is significantly worse** (LB 0.8961). The branch-2
  conclusion ("no pbuf cycles/op improvement ≥5 %") holds a fortiori. A strict literal
  reading therefore matches **no** branch (G1 appears INCONCLUSIVE); the data direction is
  unambiguously **DEFER**, and branch 3 — the only pro-B7 branch — is excluded by a wide
  margin. This is a defect in the Lead's gate drafting (the "guaranteed a priori" clause),
  not a data ambiguity; it is recorded here for transparency.

## 5. Consequences

1. **No engine change:** `src/`, `pom.xml`, `module-info.java` untouched by B7; the capability
   surface is unchanged; no new public API.
2. **G9** stays open in the inventory but is reclassified as *not warranted for the
   regular-file I/O scope*, with the corrected rationale above. The L0 "low-syscall-per-op"
   comparison is complete: seastar's advantage is reactor batching, not B7 features.
3. **M1/M2/M3 sign-offs are unaffected**; this was a capability/cost gate, not a new
   cross-engine performance claim (the E3 numbers compare a C probe's two buffer mechanisms,
   not JUring vs seastar).
4. **B9 is independent** (API stability/docs/examples); the roadmap's remaining unconditional
   item is delivered in `docs/b9-report.md`.

## 6. What would reopen B7 (frozen)

Re-run this gate (new frozen contract, not an amendment to this one) if **any** holds:

1. the kernel gains `IORING_OP_READ_MULTISHOT` (or equivalent) for **regular files**, making
   a ≥10 % syscalls/op reduction at equal workload possible;
2. JUring's scope expands to pollable fds (sockets/pipes) — a plan change under D1 — where
   multishot is the documented mechanism;
3. a real workload shows provided-buffer selection cutting cycles/op **≥5 %** vs the current
   fixed/arena path with no throughput regression (this record measured the opposite on this
   host/kernel).

Until one of those happens, B7 remains deferred and G9 remains a *documented, measured
non-need* rather than an assumed seastar gap.

## 7. Provenance

- Frozen gate: `docs/b7-gate.md` sha1 `36a19a8c7bb8c3c2c7fb63b4123bba6d7416ebf3` (post-A1);
  pre-A1 `deed4136…`. `scratch/b7-gate/FREEZE.txt`.
- Session: `20261115T100657` (10:06:57–10:15:01 +01:00), R=3 × 2 workloads × 2 arms = 12
  runs, all `status=ok`; load1 at run starts 0.39–1.36, `procs_running=1`; read file opened
  read-only (`ST_RDONLY=1`), never written.
- Probe binary sha256 `6b0749801d334c760960a8cbf61dd683266310c978262a4fd00fd1b286c9720b`;
  liburing pinned 2.13 (`.sysroot/root/usr`); perf 7.2.11, `perf_event_paranoid=-1`.
- Deviations disclosed: G2 arm A is the documented M1 arena fallback (Amendment A1, because
  8×1 MiB registered buffers exceed `RLIMIT_MEMLOCK` soft=hard=8 MiB); G1 rep B3 is a
  kept slow rep (304.8 k IOPS, p99 535 µs, within the M1 anomaly rule); G2 p99 is bimodal
  (not gated); dispatched-completion accounting excludes ≤QD drained completions (disclosed
  in RESULTS.md §1).
