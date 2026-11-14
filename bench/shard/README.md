# `bench/shard/` — B4 preliminary W17-W20 shard-scaling bench

**Status: PRELIMINARY. M3 is NOT signed off by anything in this directory.** The
cells here are the R=3, one-session, single-shared-file subset from
`docs/b4-design.md` §13. The full plan-M3 campaign still needs R=5, state gating
(1 s series, 300 s rests), the seastar `--smp N` runner (does not exist yet) and
B5 per-ring metrics.

This directory contains:

| file | purpose |
|---|---|
| `run-shard-scaling.sh` | session driver: N ∈ {1,2,4,8} × {`juring-group`, `juring-manual`, `fio`}, CPUs 1..N, 10 s warmup + 30 s measured, R=3, 60 s rests, fresh process/JVM per rep |
| `analyze_shard.py` | analyzer: total IOPS, ratio vs fio, SE(N), per-shard min/mean, p99(N)/p99(1), preliminary acceptance bars, all reps reported (never trimmed) |
| `README.md` | this file |

The shard-runtime mode of the JUring harness lives in
`bench/juring/JUringFileBench.java` (`--runtime=shard`); its default
(`--runtime=threads`) is the historical harness and its JSON is field-compatible
with pre-B4 output.

---

## 1. What is measured

| engine | command | what it is |
|---|---|---|
| `juring-group-N` | `JUringFileBench --runtime=shard --shards N` | one `IoShard`/one ring per worker in one `RingGroup`, CPUs `1..N` (best-effort `sched_setaffinity` per shard), registered file + registered fixed 4K buffers, QD 64 per shard. The pipeline is driven by `ctx.onCompletion` handlers dispatched by the shard's batched poll loop (the real B4 runtime path). |
| `juring-manual-N` | `JUringFileBench --runtime=threads --threads N` | the historical harness: one plain platform thread + one ring per worker; each thread runs its own self-driving fill/reap loop. No affinity beyond the taskset mask. |
| `fio-N` | `fio` io_uring, `numjobs=N`, matched registration | reference engine: `--registerfiles=1 --fixedbufs=1 --iodepth=64 --direct=1 --rw=randread`, pinned with `taskset -c 1..N`. |

Cell: **4K randread**, QD **64 per worker**, total QD = 64·N, dataset =
`/mnt/TestData/read_files/single_file_bench.bin` (aged 10 GiB, read-only),
O_DIRECT, 10 s warmup + 30 s measured, R=3, 60 s rest between rep blocks,
30 s settle between N values, one fresh process/JVM per invocation.

Only **read** cells are run (`--readonly` for fio, read-only O_DIRECT fds for
JUring). No write cells, no seastar (out of scope this session).

## 2. Frozen harness CLI (`bench/juring/JUringFileBench.java`)

Default behaviour is unchanged (plain threads, byte-compatible JSON fields/units):

```
bench/juring/run-juring.sh --workload randread --file <10GiB> \
    --bs 4k --qd 64 --threads N --seconds 30 --warmup-sec 10 \
    --runs 1 --seed 20261114 --readonly --label <id> --json <out.json>
```

Shard-runtime mode (new):

```
bench/juring/run-juring.sh --workload randread --file <10GiB> \
    --bs 4k --qd 64 --runtime=shard --shards N --first-cpu 1 \
    --seconds 30 --warmup-sec 10 --runs 1 --seed 20261114 \
    --readonly --label <id> --json <out.json>
```

New flags (frozen for this session):

| flag | meaning |
|---|---|
| `--runtime threads\|shard` | worker runtime; default `threads` (= historical behaviour, JSON unchanged) |
| `--shards N` | shard count for `--runtime=shard`; one `IoShard`/ring per shard; defaults to `--threads`; rejected with `--runtime=threads` |
| `--first-cpu N` | base CPU for shard affinity: shard `i` → `first-cpu+i` (default 1, `-1` = no affinity); `--runtime=shard` only |

`--threads` is ignored/rejected as a conflict in shard mode: the worker count is
`--shards`. In both modes the JSON `config.threads` is the total worker count, so
"total QD = 64 × threads" stays directly comparable to fio `numjobs=N`.

**JSON additions (shard mode only; nothing is removed or renamed):**

- `config.runtime` (`"shard"`), `config.shards`, `config.first_cpu`;
- `runs[].shards[]`: per-shard `shard`, `elapsed_sec`, `ops`, `bytes`, `errors`,
  `seq_overflows`, `iops`, `mibs`, `latency_us`, plus `shard_stats` — the full
  `ShardStats` snapshot (`shard_id`, `thread_name`, `requested_cpu`,
  `current_cpu`, `affinity_applied`, `completions_registered/dispatched/
  unattributed/failures`, `in_flight_requests`, `ring_submits`, `wakeup_armed`,
  `cancelled_at_close`, `abandoned_at_close`, `close_timed_out`, …);
- `runs[].threads[]` is still populated in shard mode (same per-shard numbers,
  historical shape), so `bench/m1`/`bench/stream` style consumers keep working.

In **threads** mode no new field is emitted at all — the JSON is field-for-field
the pre-B4 schema (verified by A/B diff, see §6).

Latency convention (unchanged, all JUring modes): `System.nanoTime()` before
prepare/submit → completion handler; per-worker uniform Algorithm-R reservoir
(1,000,000 samples/worker), merged by the historical `mergeLatency`; report
p50/p90/p95/p99/p999/max in µs. fio reports `clat` (a different definition) —
fio tail values are not compared to JUring tail values; only the within-engine
p99(N)/p99(1) ratio is gated.

## 3. Driver

```bash
# plan only: preflight report + every exact command, no files, no I/O
bench/shard/run-shard-scaling.sh --dry-run

# the preliminary session (~40 min)
bench/shard/run-shard-scaling.sh

# trim to N={1,8} (only if the session is at risk -- say so in the notes)
bench/shard/run-shard-scaling.sh --n-list 1,8
```

Options: `--session NAME` (default `b4-<YYYY-MM-DD>`), `--n-list`, `--reps`,
`--seconds`, `--warmup`, `--rest`, `--settle`, `--force`, `--dry-run`.
Environment: `READ_FILE`, `JURING_RUN`, `FIO_BIN`,
`JVM_OPTS` (default `-Xms8g -Xmx8g -XX:+AlwaysPreTouch`), `FIO_GROUP_REPORTING`
(frozen template value 1), `ALLOW_RESIDUAL_LOAD=1`.

Protocol details:

- Engine order **alternates per rep** (`group,manual,fio` / `fio,manual,group`)
  so slow drift is common-mode (methodology D6); reps are never reordered after
  seeing results.
- Every invocation is a fresh process (fresh JVM for JUring) under
  `taskset -c 1..N`; fio `numjobs=N` on the same CPU set (F3/F4/F5). CPUs 0 and
  16–31 (SMT siblings) stay idle.
- First-message gate: **no Maven, no other benchmark harness, no foreign process
  >20 % CPU, governor `performance`, io_uring enabled, loadavg(1m) ≤ 0.5**
  (F14). Per-invocation the driver records `uptime`/`loadavg`/top-`ps` in
  `logs/<id>.env` and a `gate_start`/`gate_end` state in `cell-meta.tsv`; a
  foreign-process violation is recorded (`status=gate`) and disclosed, never
  silently pooled. Note: our own benchmark threads are runnable, so loadavg(1m)
  stays >0.5 for a few minutes after each cell; that residual is recorded
  verbatim as `own-residual(la1=…)` (not a violation) and disclosed by the
  analyzer — the operative per-cell interference gates are the runtime
  foreign-process/top-ps check and the session-start loadavg ≤0.5 gate.
- The driver never invokes Maven: the B4 harness is compiled `javac`-only
  (`JURING_SKIP_MVN=1 ... --help`) against `target/classes` that the Lead builds
  on the frozen tree before the session; cells then run with
  `JURING_SKIP_COMPILE=1`. The session manifest records the commit, the build
  state of `target/classes`, and harness sha256 sums.
- Guard rails: refuses to run into a non-empty `cells/` unless `--force`;
  per-cell JSON identity checks fail the cell if `juring-group` did not really
  run `runtime=shard shards=N` (or manual did not run `runtime=threads threads=N`).
- **G3 frozen-tree gate (mandatory, freeze v2):** the start preflight requires
  `git HEAD == 25c664b28956992a8f35244ee0100ad79883d5c7`,
  `find src/main/java -type f | sort | xargs cat | sha1sum ==
  93ce8a716c0f3b56e9ce76b8806db5eff99fc66f` and
  `find src/test/java -type f | sort | xargs cat | sha1sum ==
  74f9c344596a4a7c8353f915b9dcbdf0ebae2f37` (the Lead's freeze-v2 content
  hashes, after the F1 CompletionTable fix); a mismatch aborts the session. The
  manifest records the expected and live hashes; the B4 working tree is
  intentionally uncommitted (`dirty=true`), so the identity is those hashes, not
  only the commit id.

Artifacts (under the gitignored `bench/results/<session>/`):

```
cells/N<N>__<engine>__r<rep>.json     raw per-process JSON (+ .gc.log for JUring)
logs/<id>.log, logs/<id>.env          process output; pre/post uptime/loadavg/top
cell-meta.tsv                         one row per invocation (all reps, never trimmed)
manifest.txt                          kernel/JDK/CPU/NVMe identity, commit, build state,
                                      protocol, exact command templates, "only read cells"
aggregate/shard.{json,md}             analyzer output
```

## 4. Analyzer

```bash
python3 bench/shard/analyze_shard.py --session b4-2026-11-14
# fixtures/self-test: python3 bench/shard/analyze_shard.py --results-dir <dir>
```

Formulas (methodology §6 / plan §5 M3):

- `IOPS(engine, N)` = mean over reps of the process-level total IOPS
  (JUring: `runs[0].iops`; fio: sum of `jobs[].read.iops`).
- `ratio_vs_fio = IOPS(engine,N) / IOPS(fio,N)`.
- `SE(N) = IOPS(N) / (N · IOPS(1))` per engine (also `SE(4)/SE(8)`).
- per-shard spread = `min(per-shard IOPS) / mean(per-shard IOPS)`; per-shard
  IOPS come from `runs[0].shards[]` (group) or `runs[0].threads[]` (manual).
- `p99(N)/p99(1)` per engine (mean p99 ratio; same JUring latency definition).
- `group/manual` ratio per N.

Preliminary acceptance bars (design §13 / plan M3, applied to this data):
`group-8 ≥ 85 % of fio-8`; `SE_group(8) ≥ 0.75`; per-shard `min/mean ≥ 0.85`
(group-8; manual-8 reported as reference); `p99_group(8) ≤ 1.15 × p99_group(1)`.
The verdict is `PASS (preliminary bars only)` / `FAIL` / `INCONCLUSIVE`, and it
never claims M3 sign-off.

Every rep is listed in the raw table; nothing is averaged away silently. At R=3
no confidence interval is claimed — means are shown with stdev/min/max/CV and
the report says so. Data-quality section flags *unstable/bimodal* cells using the
M1 anomaly rule (any rep < 0.92 × cell max IOPS) and CV, and lists failed or
gate-flagged invocations.

**fio `group_reporting=1` caveat (verified with fio 3.40).** The frozen matched
template uses `--group_reporting=1`; with `numjobs>1` fio 3.40 then emits **one
aggregated `jobs[]` entry**, so fio per-job/per-shard spread is not available.
The analyzer uses the aggregated `read.iops` for the total gate and reports fio
per-shard spread as `n/a`; the M3 per-shard min/mean bar is evaluated on JUring
shards/threads. (`FIO_GROUP_REPORTING=0` would expose per-job entries — the
driver supports it — but the frozen template value 1 is what the session uses;
output reporting only, I/O behaviour is identical.)

## 5. Limitations (honest disclosure)

1. **Preliminary**: R=3, one session. Supports "the B4 runtime scales"; does not
   satisfy M3 (needs R=5, state gating, B5 metrics, seastar `--smp N`).
2. **Single shared file** for all shards/engines (DS-S); the W18-W20 DS-M
   one-file-per-shard layout is not used. Page cache/extent mapping is shared.
3. Host is **bimodal at QD64**; R=3 cannot gate device state (no 1 s series, no
   300 s rests). Bimodal cells are flagged, not trimmed.
4. `juring-group` per-shard latency includes the shard runtime's batched dispatch
   overhead; `juring-manual` measures the same before-submit → callback window
   without that dispatch. That is the intended runtime cost being measured.
5. `juring-group` workers are individually CPU-pinned by the shard runtime;
   `juring-manual` threads and fio jobs only inherit the taskset mask (F0
   disclosure; fio has no equivalent per-job pinning flag in the frozen template).
6. The shard mode does **not** exercise the seastar-style multi-shard file
   layout, work stealing or QoS (B4 explicitly has none), and a `JuringFile` per
   shard is not used: the benchmark opens one fd per shard on the same file with
   `readFixed`/registered buffers.
7. No CPU/op, syscalls/op, IOPriority, write cells or seastar comparison here.
8. Sample count: the latency reservoir is per worker (1M), merged for the total
   latency; per-shard p99 in the JSON is each shard's own reservoir.
9. If the session must be trimmed, only `--n-list 1,8` (N={1,8}, R=3) is the
   documented fallback — and the notes must say so.
10. **Per-shard fairness vs the host NVMe queue topology.** The data device's
    blk-mq contexts each cover 4 CPUs (`/sys/block/nvme1n1/mq/*/cpu_list`, e.g.
    `mq/0: 0,1,16,17 mq/1: 2,3,18,19 ...`). Pinning shards to CPUs `1..N` (the
    design default) therefore gives CPU1/CPU8 a dedicated hardware queue while
    CPUs {2,3},{4,5},{6,7} share queues; measured per-shard IOPS ratios become
    ~2:1 at N=8 (min/mean 0.802) although total IOPS is at fio parity. Unpinned
    group mode (`--first-cpu -1`) is balanced (0.965-0.983) and matches the
    manual reference. This is a host topology artifact, not a runtime
    distribution defect — see `scratch/b4-bench/notes.md` §7. The full M3
    campaign should pin one shard per hctx (first CPUs of `mq/0..7`) or run
    unpinned, and record `mq/*/cpu_list` in the manifest.
11. **SE(N) and p99(N)/p99(1) are not meaningful for this single-device,
    constant-QD-per-shard protocol.** One shard at QD64 already reaches ~68 % of
    the measured device ceiling (~476 k IOPS), so SE(8) is capped at ~0.18 for
    every engine — the fio reference itself gets 0.181 and manual 0.184, and
    fio's p99(8)/p99(1) is 5.90 (manual 5.46). These bars measure the saturated
    SSD queue, not the runtime; restate them (SE relative to fio at the same N,
    or fixed total QD) before the full campaign. The analyzer prints this
    framing next to its raw bar table (`bar_interpretation`).

## 6. Verification log for the harness changes

- `javac`-only type check against the frozen B4 API stubs
  (`scratch/b4-bench/stubs/`) before the core existed: clean.
- `javac` against the real `target/classes` after the B4 core landed: clean.
- A/B field-compatibility check: an old `HEAD` build of `JUringFileBench` and the
  new build are run with the same threads-mode arguments; the JSONs are compared
  with volatile fields (timestamps, loadavg, duration-dependent numbers) masked;
  identical field sets/units are required. Result: see
  `scratch/b4-bench/STATUS.md`.
- Shard smoke: `--runtime=shard --shards 2 --seconds 2` on the real dataset, only
  after the Lead's G1, with all shards' `affinity_applied=true`, non-zero
  `completions_dispatched`, `completions_unattributed=0` and no Little's-law
  mismatch warning.
- Analyzer self-test on synthetic clean and deliberately-broken fixtures
  (`scratch/b4-bench/fixtures/`): verdict/bars/raw-rep/quality sections render,
  missing JSON / anomalous rep / gate flag / failed invocation are reported.
