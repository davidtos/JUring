# Handover — JUring vs seastar comparison (session of 2026-11-13; M1 + B1)

For the next agent/session. Read this first, then **`docs/b1-report.md`** (B1
deliverable, most recent), **`docs/m1-report.md`** (M1 deliverable),
**`docs/b1-design.md`** (B1 frozen API/semantics contract), `SEASTAR-PLAN.md` (v1.1),
`docs/benchmark-methodology.md` (the measurement contract), and `bench/README.md` +
`bench/write/README.md`.

## 0. B1 status — third session addendum (2026-11-13 evening)

**B1 `JuringFile` is DONE, tested and committed** in this session (commit
`feat(api): add JuringFile async file handle abstraction (B1)`, branch `kafka-engine`).
The detailed evidence is in [`docs/b1-report.md`](b1-report.md); the frozen API/semantics
contract is [`docs/b1-design.md`](b1-design.md).

- New public API: `JuringFile` (open with `LinuxOpenOptions`, offset `prepare*`
  read/write plain + registered-file/fixed-buffer fast path, `await`/`drain`, `length`,
  `truncate`, `allocate`, `discard`, `fsync`/`fdatasync`, bounded `close`,
  `register`/`unregister`, counters), `JuringException` (typed positive `errno`), and
  `IoPolicy` (short-IO / `EAGAIN` policy).
- Engine additions (additive only; hot paths untouched): optional-opcode probe cache and
  `IORING_OP_FALLOCATE(17)`, `IORING_OP_STATX(21)`, `IORING_OP_FTRUNCATE(55)`,
  `IORING_OP_TIMEOUT(11)`; registration lifecycle (`unregisterFiles`/`unregisterBuffers`,
  sparse fixed-file `registerFileAdd`/`unregisterFileSlot`, `updateRegisteredBuffers`).
- Verified this session: `mvn -o test` = **142 tests, 0 failures, 1 skipped** (baseline
  before B1: 90); **exactly 0 heap bytes** over 100k measured `prepareReadFixed+await` and
  100k `prepareWriteFixed+await` ops; L0 W1/W3 pre/post with the unchanged M1 harness
  inside the B1 non-regression band (report §3.3). Kernel 7.0.12 supports all four new
  opcodes; `IORING_REGISTER_BUFFERS_UPDATE` was exercised end-to-end.
- Known deviations/limitations: `IORING_OP_STATX` cannot use `IOSQE_FIXED_FILE` (the
  engine resolves the slot to the tracked real fd; `length()` works for registered files);
  blocking `JuringFile` helpers refuse to drain a ring carrying foreign in-flight work
  (ring-level completion dispatcher is B3); `prepareTimeout` allocates a ring-lifetime
  timespec per call (control path).
- **Next: B2** (buffered stream layer: `Input`/`Output`, readahead, write-behind, flush;
  then M2/W21-W22 vs seastar `fstream`). The write matrix (Step A above) is still pending
  and remains an independent M1 gap.


## 1. TL;DR of where we are

- **Step 0 is done:** `nvme-data/` is a writable bind mount of the data NVMe
  (`/dev/nvme1n1p1[/juring-bench]`, ext4, ~131 GiB free). Read dataset
  `/mnt/TestData/read_files/single_file_bench.bin` stays read-only and is never written.
- **M1 READ PATH: PASS.** `docs/m1-report.md` is final: six-phase, 3-hour session
  (177 cell JSON, 183 meta rows, 0 failed cells) comparing JUring vs seastar
  (`seastar_file_bench`, `file::dma_read`) vs fio on the same aged 10 GiB file, same
  pinning, same per-thread QD, with CIs and state gating. Verdict: rules 1, 2, 3, 5
  PASS; seastar reported as a bracket (rule 4); details in `docs/m1-report.md` §14.
- **Step 2 started:** the first trimmed write block ran (W7 4K randwrite QD64, R=3,
  juring/fio-default/fio-matched/seastar, D1 session-once preconditioning). seastar's
  write path is now **validated** (it was untested before). Write rule 1 is
  **INCONCLUSIVE at n=3** (paired ratio 0.9644, one-sided-95 LB 0.9490) — no write-parity
  claim; W5/W6/W8/W12–W14 remain pending.
- **Major validity finding:** read performance is dominated by dataset data state. A
  freshly written 10 GiB file reads **5.2–5.6× faster at QD1** and 1.1–1.7× at QD64 than
  the aged 10-day file (`§11b`; `bench/results/m1-2026-11-13-locality/`). Cross-dataset
  absolute comparisons are invalid; the aged arm reproduced the matrix within 1–3 %.
  Consequently the `io_tester` cross-check is **NOT COMPARABLE** (no runner-accuracy
  conclusion drawn).
- **No JUring source files were changed.** Repo diff is additive: `SEASTAR-PLAN.md`,
  `docs/`, `bench/`, `.gitignore`. `SeaStarRef/`, `.tools/`, `.sysroot/` are gitignored.
  No commits were made.
- **Next real milestone:** finish the write matrix, then start the library work
  (B0 close-out → **B1 `JuringFile`**; see §7).

## 2. Repo map (what exists now)

```
SEASTAR-PLAN.md                 plan v1.1 (read §0, §4, §5; M1 row updated)
docs/
  b1-report.md                  ** B1 DELIVERABLE ** JuringFile + engine ops + L0 evidence
  b1-design.md                  B1 frozen API/semantics contract (JuringFile, engine ops)
  m1-report.md                  M1 read path PASS + W7 write block
  benchmark-methodology.md      measurement contract (F0-F15, D1-D8, §7 gates)
  handover (this file)
  baseline-2026-11-13.md        first-pass baseline (§9 seastar addendum; superseded for citations)
  seastar-inventory.md          seastar stack + benchmark tooling, path:line evidence
  juring-inventory.md           JUring API/tests/benchmarks + gaps G1-G12
bench/
  m1/                           ** M1 session harness (this session) **
    lib.sh                      preflight, per-cell meta, engine command builder
    run-read-session.sh         runs the 6 phases in order, logs/session.log
    run-read-matrix.sh          W1-W4 4K randread QD1/16/64/128, 10/30 s, R=5
    run-fio-brackets.sh         fio default/batch8-32/sqpoll/libaio, QD64(+QD128)
    run-latency-matrix.sh       W15 (QD1), W16 (QD64), 10/120 s, R=5
    run-perf-cells.sh           perf stat cycles/instr/syscalls per op, QD1+QD64, R=3
    run-seqread-matrix.sh       W9-W11 1M seqread QD1/8/32, 10/30 s, R=5
    run-iotester-check.sh       io_tester cross-check (writes 20 GiB; runs last)
    run-write-first-block.sh    Step-2 kickoff: create write file, validate seastar
                                randwrite, W7 block (12 runs)
    run-locality-check.sh       aged-vs-fresh dataset control (result in report §11b)
    analyze_m1.py               CIs, paired ratios, state gating, gate table (read)
    normalize-write-session.py  fixes the write session fio p99 ns->us (see §6)
    json_summary.py / perf_summary.py
  write/                        Step-2 write harness (dry-run/help; see its README)
    create-write-file.sh precondition.sh run-write-cells.sh sample-device-iops.sh README.md
  seastar/seastar_file_bench.cc seastar runner (latency reservoir FIXED this session)
  seastar/build-file-bench.sh   rebuild it
  juring/JUringFileBench.java   JUring runner (io_tester-compatible workloads)
  results/m1-2026-11-13/        ** read session ** cells/ perf/ iotester/ aggregate/
                                cell-meta.tsv perf-cells.tsv iotester-check.tsv
                                manifest.txt notes.md logs/
  results/m1-2026-11-13-write/  write block: cells/ series/ state/ aggregate/ logs/
  results/m1-2026-11-13-locality/ aged/ fresh/ locality.log
  results/2026-11-13/           first-pass baseline (fio matrix + JUring + seastar)
SeaStarRef/                     seastar @ 04b1c04d (own git; build/ gitignored)
.tools/ .sysroot/               local cmake/ninja + dependency sysroot (gitignored, do not delete)
nvme-data/                      writable data-NVMe bind mount: write_files/ (10 GiB),
                                fresh-fill/ (10 GiB), iotester-storage/
```

## 3. Sandbox / host facts (important)

- Workspace `/home/david/projects/JUring` is the only writable place; the workspace
  itself is on btrfs on a **different** NVMe (never use it for I/O numbers).
- `/mnt/TestData` and `/mnt/data` are **read-only** in the sandbox. `/tmp` is
  per-command ephemeral — keep anything persistent under the workspace.
- Data NVMe: GIGABYTE GP-ASM2NE6100TTTD 1 TB (`nvme1n1`), ext4, `/mnt/TestData` ro,
  `nvme-data/` rw (bind mount, **not** in `/etc/fstab` — lost after reboot until re-made).
- Host: Ryzen 9 3950X 32T, 62 GiB, Fedora 44, kernel 7.0.12. `perf` works
  (`perf_event_paranoid=-1`), `raw_syscalls:sys_enter` works. `sudo` disabled; no
  hugepages; `ulimit -l` = 8 MiB (limits JUring fixed buffers at 1 MiB bs / high QD).
- seastar is built in-repo without root: `.sysroot/root/usr` deps, `.tools/` cmake+ninja.
  Sandbox flags are mandatory: `--smp 1 --cpuset 2 --memory 1G --lock-memory 0`
  (plus `taskset -c 2`). No hugetlbfs → THP.
- Bimodal host state is real: QD64/QD128 cells can be fast or slow. The state gate is
  p99 < 600 µs (QD64) / < 1200 µs (QD128) **plus** an IOPS anomaly rule
  (rep < 0.92 × cell max). See report §4; never trim a rep silently.

## 4. Exact commands (copy/paste)

### 4.1 Re-run the full M1 read session (~3 h: W1-W4, brackets, latency, perf, seqread, io_tester)

```bash
cd /home/david/projects/JUring
./bench/m1/run-read-session.sh          # phases in order, logs/session.log, ~3 h
```

### 4.2 Aggregate + gate table (writes <session>/aggregate/m1.*)

```bash
python3 bench/m1/analyze_m1.py --dir bench/results/m1-2026-11-13
# report tables are generated from the aggregate JSON:
python3 bench/results/m1-2026-11-13/aggregate/gen_report.py   # (m1-analyst-owned)
```

### 4.3 Single cell examples

```bash
# JUring read cell (one fresh JVM; JURING_SKIP_COMPILE=1 reuses bench/juring/out)
JURING_SKIP_COMPILE=1 taskset -c 2 bench/juring/run-juring.sh \
  --workload randread --file /mnt/TestData/read_files/single_file_bench.bin \
  --bs 4k --qd 64 --threads 1 --seconds 30 --warmup-sec 10 --runs 1 \
  --readonly --seed 20261113 --label rr4k_qd64 --json /tmp/out.json

# seastar read cell (LD_LIBRARY_PATH needed)
SYS=$PWD/.sysroot/root/usr
LD_LIBRARY_PATH=$SYS/lib64 taskset -c 2 bench/seastar/seastar_file_bench \
  --file /mnt/TestData/read_files/single_file_bench.bin \
  --workload randread --bs 4k --qd 64 --seconds 30 --warmup-sec 10 --runs 1 \
  --readonly --seed 20261113 --label rr4k_qd64 --json /tmp/ss.json \
  --smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring

# fio matched reference cell
taskset -c 2 fio --name=W3 --filename=/mnt/TestData/read_files/single_file_bench.bin \
  --readonly --direct=1 --rw=randread --bs=4k --iodepth=64 --numjobs=1 \
  --ioengine=io_uring --registerfiles=1 --fixedbufs=1 --randrepeat=1 \
  --time_based --runtime=30 --ramp_time=10 --clocksource=clock_gettime \
  --percentile_list=50:95:99:99.9 --clat_percentiles=1 --lat_percentiles=0 \
  --group_reporting=1 --output-format=json --output=/tmp/fio.json
```

### 4.4 Rebuild the seastar runner (only if its source changes)

```bash
bench/seastar/build-file-bench.sh       # -> bench/seastar/seastar_file_bench
```

### 4.5 Step-2 write block (create file + validate seastar write + W7, ~1 h)

```bash
./bench/m1/run-write-first-block.sh
# trim/override examples:
bench/write/run-write-cells.sh --dry-run                     # print the full plan
bench/write/run-write-cells.sh --cells W7 --reps 1 --precondition none \
  --engines juring --loadavg-action warn
```

### 4.6 Dataset data-state control (repeat §11b evidence, ~10 min)

```bash
./bench/m1/run-locality-check.sh        # aged vs fresh 10 GiB file, same engines
```

### 4.7 seastar env exports (needed for any seastar build/run)

```bash
SYS=$PWD/.sysroot/root/usr; SYSROOT=$PWD/.sysroot/root
export CMAKE_PREFIX_PATH=$SYS PKG_CONFIG_PATH=$SYS/lib64/pkgconfig:$SYS/share/pkgconfig
export PKG_CONFIG_SYSROOT_DIR=$SYSROOT CPATH=$SYS/include LIBRARY_PATH=$SYS/lib64
export LD_LIBRARY_PATH=$SYS/lib64
export PATH=$SYS/bin:$PWD/.tools/cmake/bin:$PWD/.tools/ninja:$PATH
# rebuild seastar itself: cd SeaStarRef && ninja -C build/release -j16
```

## 5. Results snapshot (full tables in `docs/m1-report.md`)

Read path, aged 10 GiB file, 10 s warmup + 30 s measured, R=5, CPU 2, O_DIRECT:

| case | JUring IOPS | seastar IOPS | fio-matched IOPS | JUring/fio (95 % CI) |
|---|---|---|---|---|
| W1 QD1 | 10,969 | 11,558 | 10,748 | 1.0234 (0.9248–1.1220) no measurable diff |
| W2 QD16 | 144,235 | 145,575 | 142,818 | 1.0108 (0.9556–1.0660) no measurable diff |
| W3 QD64 | 327,371 | 318,317 | 304,064 | **1.0771 (1.0477–1.1064) JUring faster** |
| W4 QD128 | 411,136 | 389,967 | 332,133 | **1.2388 (1.1669–1.3108) JUring faster** |
| W9 QD1 1M | 1,729 | 1,748 | 1,726 | ceiling ~1.7 GiB/s |
| W10 QD8 1M | 4,720 | 4,763 | 4,757 | ceiling ~4.7 GiB/s |
| W11 QD32 1M | 4,772 | 4,632 | 4,647 | ceiling |

CPU per op (`perf`, QD64): JUring **11,335** cyc/op, 0.330 syscalls/op; fio-matched
15,227 / 1.012; seastar 13,859 / 0.186. At QD1 seastar burns **383,329** cyc/op
(busy-polls the reactor) vs JUring 27,254 — a runtime difference, reported as caveat.

Tails (W15/W16): p99 QD64 JUring 481.5 µs vs fio 478.8 vs seastar 504.9 (parity).
p999 QD64 JUring 660.5 vs seastar 1,881 µs. At QD1 JUring p999 630.9 µs vs seastar
140.5 (JVM stalls, max 7.1 ms) — an L0+JVM conflation, not an io_uring result.

fio bracket (QD64): matched 304,064 > default 290,440 ≈ batch32 297,026 ≈ batch16
290,624 > batch8 282,977 > libaio 277,209; **sqpoll collapses to 10,766** (3.5 % of
matched) — genuine negative, reported, not dropped. Registration matters more than
batching: fio default vs matched differ ~9–10 % at QD128.

Write block (fresh 10 GiB file, 4K randwrite QD64, R=3, all CV<10 %):
fio-matched 198,327, fio-default 198,376, seastar 193,238, JUring 191,261 IOPS
(~747–775 MiB/s); p99 1.45–2.63 ms, write amplification ≈3×, **no SLC cliff** in the
measured windows but 1 s IOPS CV up to 30 % (bursts hidden by the 60 s means).

Data-state control (§11b): fresh file reads 5.2–5.6× faster at QD1
(p50 14.9–16.8 µs vs 81.8–83.6) and 1.1–1.7× at QD64; aged arm reproduced the matrix
within 1–3 % in a separate run.

## 6. Known issues / quirks (read before re-running anything)

1. **perf-cell id collision (read session).** In frozen `run-perf-cells.sh` the cell
   id always embeds `perf`, so fio `perf` and `perf-matched` wrote the same JSON/txt;
   matched overwrote default. Both variants' numbers survive in `perf-cells.tsv`
   (computed live); only matched has a raw capture. Cite `perf-matched` for gate 2;
   fio-default CPU row is "computed-only". Fix the id (add the variant) before the
   next perf phase.
2. **Write-session fio p99 units bug.** `bench/write/run-write-cells.sh` writes fio's
   `clat_ns` p99 (nanoseconds) into a `p99_us` column (juring/seastar are correct µs).
   Use `bench/results/m1-2026-11-13-write/aggregate/cells-normalized.csv`
   (`bench/m1/normalize-write-session.py`, recomputed from raw JSON in µs). Never cite
   the write `cell-meta.tsv` p99 for fio.
3. **D1 preconditioning overshoot.** `bench/write/precondition.sh` uses one full file
   size (10 GiB) as chunk, so the ≥120 s minimum forced 12 chunks = **120 GiB** written
   instead of ~32 GiB (recorded, capped=0). Deepens steady state, but fix the chunk
   size (e.g. 4 GiB) before the full write matrix.
4. **`cell-meta.tsv` has no phase column.** perf juring/seastar rows share keys with
   the read-matrix rows; use `perf-cells.tsv` for perf cells, don't count them twice.
5. **Sampler `io_busy_pct` is wrong by ~100×** in `sample-device-iops.sh` v1; don't use
   that column (write IOPS/series columns are fine).
6. **Latency reservoir semantics.** JUring's per-thread reservoir is uniform Algorithm R
   (`bench/juring/JUringFileBench.java:1113-1127`), but its JSON `sampled` flag only
   reports *merge-level* striding — read `samples` vs `ops`. seastar's runner was fixed
   this session to use a uniform reservoir (it used to keep the first ~6 s of a 120 s
   run) and its `sampled` is truthful.
7. **fio latency is `clat`** (completion latency) — a different definition from
   JUring/seastar (before-submit → completion). Do not compare fio's tail to theirs
   except where the gate explicitly says so (W15/W16 gate uses fio p99 as reference and
   the report states the definition).
8. **seastar busy-polls** the reactor (~1 core at QD1, 383 k cyc/op). Do not read that
   as an engine inefficiency for sustained load; it is the default runtime behaviour.
9. **`uuid`/thermal/drop_caches need root** and are unavailable in the sandbox (D5,
   F11). Hugepages are unavailable (THP path used) — record it.
10. **Results are gitignored** (`results/` in root `.gitignore`); if they must be
    committed, force-add. No commits were made this session.
11. **`/tmp` is per-command ephemeral** — never leave a JSON/result there.
12. **Do not delete `.sysroot/` or `.tools/`** (1 GB, recreate takes minutes and needs
    the exact package list in `bench/seastar/build-and-run.md` §5).
13. **Latency-slot collision in the JUring harness (fixed + re-measured after review).**
    `JUringFileBench` used to derive its per-op timestamp slot from `user_data & slotMask`.
    That is unique for fixed-buffer ops (monotonic opId) but NOT for non-fixed `READ_EXT`,
    which encodes the *buffer address*; 4096-aligned arena buffers collided on one slot,
    understating latency. It affected exactly the 10 `arena-unregistered` cells (W10 1 MiB
    QD8, W11 1 MiB QD32); the original §6 table and its "fio queueing artefact" sentence
    were wrong (fio's 14 ms p99 at 1 MiB/QD32 is the expected queueing latency). The fix
    indexes latency by buffer and keeps `udOfBuf[qd]`; the invalid cells are archived under
    `bench/results/_invalid/m1-2026-11-13/latency-slot-collision/` and the repaired
    cells were re-run by `bench/m1/repair-w10-w11-latency.sh` (details in session notes.md).
    M1 gates were never affected (seqread tails are not gated; IOPS/CPU unaffected).

## 7. What next (ordered, with acceptance)

### Step A — finish the write matrix (highest priority; the M1 write gap)
- Fix `precondition.sh` chunk size (target ~3× file size in <120 s, e.g. 4 GiB chunks).
- Run W5/W6/W7/W8 (4K randwrite QD1/16/64/128) and W12/W13/W14 (1M seqwrite
  QD1/8/32) with the full methodology protocol: R=3–5, 300 s rest between measured
  runs, 600 s after preconditioning, 1 s device series, preconditioned blocks.
- Include seastar (write path now validated) and fio default+matched on W7/W8.
- **Accept (plan/M1):** write saturation cells `IOPS_JUring ≥ IOPS_fio − 5 %` with
  one-sided 95 % LB ≥ 0.95; report CV and both states; publish no ranking if CV>10 %.
  The current W7 block suggests the truth is ~3.6 % below fio-matched with LB ~0.95 —
  need R=5 to decide INCONCLUSIVE vs PASS/FAIL.

### Step B — B0 close-out, then the library work begins
- **B0:** bench/ is now the single source of truth (M1 harness, manifest, one-command
  session, aggregate + generated report). Remaining: stored baseline + a **per-commit
  perf gate for W1/W3** (fail on >10 % regression), and retire the legacy JMH numbers
  from the docs. Accept: one command reproduces the tables from raw JSON; a stored
  baseline is compared per commit.
- **B1 (`JuringFile`) — DONE 2026-11-13** (third session; see §0 and
  `docs/b1-report.md`): open with flags/options, offset read/write (plain + fixed-buffer
  fast path), length/truncate/allocate/discard/fsync/fdatasync, close-with-drain
  (bounded, never hangs), typed `-errno` errors, short-IO/EAGAIN policy, registration
  lifecycle (unregister, add/remove at runtime). Evidence: 142 tests green
  (conformance seq/rand/EOF/short-IO/close-with-inflight), **L0 W1/W3 unchanged** (pre/post
  M1 harness inside the §7 band), zero-allocation hot path strict 0 bytes. Deviations:
  statx cannot use `IOSQE_FIXED_FILE`; ring-sharing guard instead of a dispatcher (B3).
- **B2 (streams)** next: `Input`/`Output` readahead/write-behind/flush, bounded memory;
  accept W21/W22 ≥85 % of seastar `fstream` (plan-M2).

### Step C — multi-shard / L2 (W17-W20)
- Extend `seastar_file_bench` with `--smp N` (it currently refuses >1) and add a
  per-thread file-split mode to `JUringFileBench`; compare vs `fio numjobs=N` on CPUs
  1..N; target plan-M3 bars (8-thread ≥85 % of fio numjobs=8, SE(8) ≥0.75, per-shard
  spread ≥0.85, p99(8) ≤1.15× p99(1)).

### Step D — host-root / representativeness runs
- On the host with root: hugetlbfs, `drop_caches` cold-cache cells, `nvme smart-log`
  thermal/throttle counters, governor verification, `filefrag` fragmentation of the
  aged dataset. Re-run the saturation cells and record the delta (F0/F10/F11).

### Step E — publishing decisions (see §8)
- Replace the README/BENCHMARK claims with the M1 report tables (layered, with CIs),
  or publish `docs/m1-report.md` separately. Do not publish a single "X beats Y" line:
  the honest shape is "parity within ±5 % at low QD; JUring ahead at QD64/QD128 vs fio
  and ~+3–6 % vs seastar; CPU/op 25 % lower; tails comparable at QD64, JVM tail at QD1".

## 8. Pending decisions for David

1. Publish the M1 read-path result? Where (README section, separate doc, blog) and in
   what form (the report is already self-contained with CIs and caveats)?
2. Write-matrix budget: full W5-W8/W12-W14 is ~6–11 h of machine time with D3 rests —
   approve a full overnight run or keep trimmed blocks?
3. `nvme-data` bind mount: persist in `/etc/fstab` (suggested line in the old handover)
   or re-create each session?
4. `SeaStarRef/` as a git submodule (pinned `04b1c04d`) or keep gitignored?
5. Hugepages/threshold policy: publish THP sandbox numbers (fast) or wait for a host
   hugetlbfs run (more representative for seastar)?
6. `docs/m1-report.md` + `bench/m1/` are currently uncommitted (additive, untracked) —
   commit them in one additive commit?

## Appendix A — recreate the sandbox build stack (if `.tools`/`.sysroot` are lost)

Same as the previous handover: download CMake 3.31.6 + Ninja 1.12.1 into `.tools/`;
extract the Fedora 44 `-devel` rpm set into `.sysroot/root` (boost, fmt, liburing,
yaml-cpp, numactl, hwloc, lz4, lksctp, c-ares, protobuf, xfsprogs, openssl, ragel);
build c-ares 1.32.3 from source (seastar rejects Fedora's 1.34.8); configure
`SeaStarRef` as in `bench/seastar/build-and-run.md` §5. `/tmp` is ephemeral per
command, so do each download+extract in a single bash call under the workspace.

## Appendix B — a good next-session opening

```bash
cd /home/david/projects/JUring
git status --short                       # expect .gitignore modified + new docs/ bench/
ls -ld nvme-data && touch nvme-data/.probe && rm nvme-data/.probe   # writable?
ls bench/seastar/seastar_file_bench SeaStarRef/build/release/apps/io_tester/io_tester
python3 bench/m1/analyze_m1.py --dir bench/results/m1-2026-11-13   # aggregates reproduce
head -20 docs/m1-report.md               # final M1 verdict
cat bench/results/m1-2026-11-13/notes.md | head -40
# then Step A (write matrix) or Step B (B1 JuringFile) per David's priority
```
