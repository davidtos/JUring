# Step D — host representativeness runs (FROZEN 2026-11-15, v1.0)

**Status: FROZEN before measurement.** This is the pre-campaign contract for the Step D
session. Any deviation is an amendment recorded here (A1, A2, …) **after** the campaign,
with the reason and time; the cell list, arms, reps, rests and acceptance below do not
change. Frozen by `scratch/stepd-lead/FREEZE.md` (sha1 record).

Parent: `SEASTAR-PLAN.md` §7 Step D; methodology controls F7/F10/F11/D5/F12/F14.

## 1. Question

Are the signed M1/M2/M3 conclusions artifacts of two sandbox limits, and what does the
real host state add?

- **Q1 (F10)** — the sandbox ran seastar without hugetlbfs. Does seastar's
  hugetlbfs-backed memory allocator materially change its brackets?
- **Q2 (A1/memlock)** — the sandbox `ulimit -l` = 8 MiB forced JUring
  `arena-unregistered` at W10/W11 and fio `regfile` at W10/W11. Lifting the cap
  (root `prlimit --memlock=unlimited:unlimited`) makes JUring's fixed-buffer path and
  fio `--fixedbufs=1` available at 1 MiB / QD8 / QD32. Do the comparators change?
- **Q3 (D5/F12/F7)** — record the host's thermal/throttle state, dataset
  size/extents/mount, and governor/EPP/boost. (Recording, not measurement.)
- **Q4 (F11)** — close cold-cache: the signed primary cells are O_DIRECT for both
  engines, so `drop_caches` cannot affect them; the exploratory `juring --buffered`
  row is non-comparable by design. `drop_caches` control was verified by the host audit.

**No re-ranking.** M1 read PASS, M2 PASS, M3 PASS stay as signed; the write half is
untouched. Step D can only add disclosures, or escalate to David if a *gated* number
moves > 5 %.

## 2. Environment pins (must appear in the campaign manifest)

| Pin | Value |
|---|---|
| user | `david` (uid 1000); root only for prlimit/smart-log/hugepages |
| JDK | `JAVA_BIN=/home/david/.jdks/openjdk-26.0.2/bin/java` (campaign JDK; a login shell gives 25.0.3) |
| memlock | block B launched under `prlimit --memlock=unlimited:unlimited` |
| hugepages | dedicated hugetlbfs `/mnt/hugepages-juring` (`pagesize=2M`, gid 1000, mode 1770), pool ≥ 4608 pages (9 GiB) |
| dataset | `/mnt/TestData/read_files/single_file_bench.bin` (aged DS-S, read-only, never written) |
| CPU | single-shard cells `taskset -c 2` + seastar `--cpuset 2`; M3N8 arm seastar `--smp 8 --cpuset 1,2,4,6,8,10,12,14` |
| governor/EPP/boost | `performance` / `performance` / active (verified by host audit) |
| load gate | F14: abort the run if loadavg(1m) ≥ 1.6 |
| harness | `bench/stepd/run-stepd.sh` (sha256 in manifest), `bench/stepd/analyze_stepd.py` |

## 3. Block A — seastar THP vs hugetlbfs bracket (F10)

- **Cells:** W3 (4K randread QD64), W4 (4K randread QD128), W10 (1M seqread QD8),
  W11 (1M seqread QD32), M3N8 (4K randread QD64 per shard, `--smp 8`, dataset-size
  capped, hctx cpuset).
- **Arms:** seastar THP (campaign config) vs seastar hugetlbfs
  (`--hugepages /mnt/hugepages-juring`); same seastar binary/commit.
- **Design:** R=3 independent reps; 10 s warmup + 30 s measured, one fresh process per
  run; arms interleaved within each rep, arm order alternating by rep; 2 s inter-run
  pause. No device rests (read-only cells; matches the M1 read protocol's rep structure).
- **Evidence:** `HugePages_Free` before/after every hugetlbfs run (expect ≈ −512 pages
  per 1 GiB shard); a hugetlbfs run that consumes no pages is a warning and is excluded
  from the bracket (status kept `ok`, flagged in `logs/warnings.log`); hwmon temperature
  per run; `nvme smart-log` before/after the block (runbook step).
- **Reading:** per-cell paired hugetlbfs/THP ratios, mean + 95 % CI (t, n=3), plus
  absolute IOPS. Seastar is a bracket, never a gate.

## 4. Block B — memlock best-config read cells (A1 fix)

- **Cells:** W9 (1M seqread QD1), W10 (QD8), W11 (QD32), read-only, DS-S.
- **Engines:** JUring std (fixed-buffer path enabled by the lifted memlock), fio
  `matched` (`--registerfiles=1 --fixedbufs=1`), seastar std.
- **Design:** R=5 (matches M1), 10 s + 30 s, fresh process per run, engine order rotated
  per rep, `taskset -c 2`; **all** runs inside the root prlimit wrapper.
- **Evidence:** per-run JSON must show JUring `io_buffer_mode` = fixed-buffer path and
  fio `fixedbufs=1`; if a JUring run falls back to the arena, the row is recorded and the
  block is repeated once; a second fallback aborts block B (cannot test Q2 without the
  fixed path).
- **Reading:** paired JUring/fio-matched and seastar/fio-matched ratios (mean + 95 % CI,
  n=5), compared with the signed M1 W9–W11 numbers (which used `arena-unregistered` /
  fio `regfile`) as disclosure only. No gate change.

## 5. Analysis and acceptance

1. `bench/stepd/analyze_stepd.py` must reproduce `analysis/stepd-summary.md` and
   `analysis/stepd.ratios.csv` from the raw `cell-meta.tsv` (fresh run, byte-compare in
   verification).
2. All planned block A/B runs present and `ok`; any failure is reported, not silently
   dropped. Warnings (hugepage consumption, load, fallback) are listed.
3. No pass/fail claim is made. Escalation rule: if a number inside the **signed M1
   gate** (W1–W4, W15/W16) moved > 5 % it would be escalated — but Step D re-measures
   only W3/W4 as the seastar bracket and W9–W11 best-config cells, so no signed-gate
   recomputation is performed.
4. Deliverables after the campaign: `docs/stepd-report.md` (tables from the analyzer),
   M1/M2/M3 disclosure rows where relevant, handover §0.0, plan v1.9 + decision D10,
   independent verification. Cleanup: hugetpage pool restored, mount removed
   (`scratch/stepd-host/stepd-preflight.sh --cleanup`).
5. **Thermal abort:** if `nvme smart-log` throttle T1/T2 counters increment between the
   before/after snapshots, the affected block is repeated after cooldown; if it recurs,
   the block is reported as thermally confounded (not run again in this session).

## 6. Proof that this is not a re-ranking

Block A cannot move a signed verdict (seastar is a bracket in M1/M3). Block B's cells
(W9–W11) are reported as seqread throughput in M1, not part of the strict W1–W4 ratio
gate; the comparison is to the signed numbers, with the sandbox's buffer-mode/regfile
substitutions disclosed. If the deltas are material (> 5 %), the Step D report says so
and proposes, but does not take, a plan amendment.

## 7. Freeze identity

- design sha1: recorded in `scratch/stepd-lead/FREEZE.md` (this file is the frozen copy)
- runner sha256: `2a8f05269be4ca9c410eccba4d4afc64857477bb044a8b53108137109c76416d`
- analyzer sha256: `dfdb877d3931d066b0ec4d2736ba213fdb04d7031776105162746dfaacd472ad`
- freeze record: `scratch/stepd-lead/FREEZE.md`

---

## Amendment A1 (post-campaign, 2026-11-16 — Path B: `HugePages_Free` pre/post-process sampling vs unlinked temporary file backing)

**Recorded:** 2026-11-16 (after the unattended campaign `bench/results/unattended-2026-11-16/` completed with `exit=0`).

- **What happened:** In `bench/stepd/run-stepd.sh` (`run_cell`, lines 160–173), `hp0=$(huge_free)` is read **before** spawning `seastar_file_bench` and `hp1=$(huge_free)` is read **after** `seastar_file_bench` exits (plus a 2 s post-run sleep). Because `seastar::memory::configure` (`SeaStarRef/src/core/memory.cc:2016-2025`, `1360-1385`) backs `--hugepages <path>` with an **unlinked temporary file** (`file_desc::temporary(*hugetlbfs_path)`) mapped via `MAP_SHARED | MAP_POPULATE` (`allocate_hugetlbfs_memory`), the kernel releases all allocated hugepages on process termination before `hp1` is sampled. Consequently `hp0` and `hp1` both read `4608` (`hp_used = 0`) on all 15 `huge` runs, and `run-stepd.sh` emitted 15 warnings to `logs/warnings.log`.
- **Why hugetlbfs backing was active during the runs:** (1) Every `huge` run's recorded JSON `argv` passes `--hugepages /mnt/hugepages-juring`; (2) Phase 0 verified `/mnt/hugepages-juring` mounted `rw,relatime,seclabel,gid=1000,mode=1770,pagesize=2M` with `HugePages_Free = 4608` (9 GiB) and `run-stepd.sh` `preflight()` verified write access by user `david` before Block A; (3) in `seastar::memory::configure`, `file_desc::temporary` + `fd.truncate` + `fd.map(MAP_SHARED | MAP_POPULATE)` has no silent fallback to anonymous THP — any failure throws an exception and aborts reactor startup before the benchmark runs.
- **Path B disposition (no benchmark reruns):** Consistent with the frozen `bench/stepd/analyze_stepd.py` behavior (which keeps all `status=ok` rows and reports the warning count at the top of `stepd-summary.md`), all 15 `huge` runs are retained in the Block A bracket comparison in `docs/stepd-report.md`, and the pre/post-process `HugePages_Free` sampling limitation is explicitly disclosed alongside the table. No benchmark rerun is performed.


---

## Amendment A2 (post-campaign, 2026-11-16 — F14 per-run load gate implemented only at session preflight)

**Recorded:** 2026-11-16, after independent verification of the campaign.

- **What happened:** §2 pins "F14: abort the run if loadavg(1m) ≥ 1.6". `bench/stepd/run-stepd.sh` implements this as a **session-start preflight** check only; it does not re-check loadavg before each cell. Observed `loadavg_start(1m)` therefore reached `6.01` at the start of Block B (residual from the preceding, already-exited own M3N8 8-shard cell) and `0.81–4.47` at M3N8 starts (`3.97–6.27` at ends).
- **Why the numbers remain valid:** the high values are the 1-minute exponentially weighted average of **our own just-exited** benchmark processes (no foreign process was observed; Block B single-shard engines were pinned to CPU 2), and the Block B `JUring/fio-matched` results are within 1–3 % of the signed M1 numbers. No rep is trimmed or re-classified after the fact.
- **Disposition (Path B, no reruns):** all 75 Step D runs are kept; the discrepancy is disclosed in `docs/stepd-report.md` §5.2; future Step D-style runs must implement the per-run gate (or record it explicitly as session-only) before measurement.
