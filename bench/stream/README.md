# B2 W21/W22 stream benchmark (`bench/stream/`)

Preliminary W21/W22 comparison of the JUring B2 stream layer
(`JuringInputStream` / `JuringOutputStream`) against seastar `fstream`
(`file_input_stream` / `file_output_stream`), plus the JUring-side stream
runner and the driver/aggregator that produce the plan-M2 readiness verdict.

Frozen contract: [`docs/b2-design.md`](../../docs/b2-design.md) §3.1 (runner
CLI + JSON), §3.2 (seastar runner) and §3.3 (W21/W22 protocol, driver,
analyzer). This directory owns only the JUring runner invocation, the matrix
driver and the aggregator; it does not modify the engines.

Related files (owned by other workstreams):

| file | role |
|---|---|
| `bench/juring/JUringStreamBench.java` | JUring stream runner (this workstream) |
| `bench/juring/run-juring-stream.sh` | compile/run wrapper for the runner |
| `bench/seastar/seastar_stream_bench.cc`, `bench/seastar/build-stream-bench.sh` | seastar fstream runner |
| `docs/b2-design.md` | frozen contract |
| `docs/b2-report.md` | B2 report (Lead) |

## 1. Cells and protocol

| cell | workload | stream buffer | concurrency (`qd`) | dataset |
|---|---|---|---|---|
| `W21-128k` | `stream_write` | 128 KiB | 8 | `nvme-data/write_files/single_file_bench_w.bin` (10 GiB, writable) |
| `W22-128k` | `stream_read` | 128 KiB | 8 | `/mnt/TestData/read_files/single_file_bench.bin` (10 GiB, read-only) |
| `W21-1M` | `stream_write` | 1 MiB | 8 | same write file |
| `W22-1M` | `stream_read` | 1 MiB | 8 | same read file |

Primary rows (used for the verdict):

* **juring** — `bench/juring/JUringStreamBench` with O_DIRECT
  (`--buffered` off), registered file (`JuringFile.register()`), ring
  `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` with `entries = max(256, 2*qd)`,
  stream options `bufferSize=bs`, `readAhead=qd-1` (input) / `writeBehind=qd`
  (output), `alignment=4096`, dynamic adjustments disabled.
* **seastar** — `bench/seastar/seastar_stream_bench` (fstream over
  `open_file_dma`, DMA/O_DIRECT on this filesystem), run with
  `--smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring`.

Disclosed exploratory row per cell (never used for the verdict): **juring
buffered** (`--buffered`, no O_DIRECT).

Default protocol (preliminary, per §3.3):

* warmup **10 s** + measured **30 s** per process invocation;
* **R = 3** reps per cell, **one fresh process per rep**, everything pinned
  with `taskset -c 2`;
* primary engines **interleaved inside every rep** (rep odd: juring then
  seastar; rep even: seastar then juring) so host drift is common-mode; the
  exploratory buffered row runs last;
* **60 s rest between successive reps of a cell** (baseline rest; the full M2
  campaign uses the methodology's 60 s cells and 300 s rests — see §5);
* O_DIRECT on both primary sides; no cache drop (no root), no dataset
  rewriting/preconditioning.

Each process emits one JSON object (`schema_version 1.0`); the driver stores it
under `cells/` and one row per invocation in `cell-meta.tsv`.

## 2. Running

One-time prerequisites (owned elsewhere): the stream API compiled into
`target/classes` (Lead's build), `bench/seastar/seastar_stream_bench` built via
`bench/seastar/build-stream-bench.sh` (seastar workstream), the two 10 GiB
datasets present.

Plan only (side-effect free, prints preflight + every command line):

```bash
bench/stream/run-stream-matrix.sh --dry-run
```

Short validation run (small R, same protocol):

```bash
bench/stream/run-stream-matrix.sh --dry-run --reps 1 --seconds 3 --warmup 1 --rest 0 \
    --cells "W21-128k W22-128k"
```

Full preliminary W21/W22 session (≈35–40 min wall):

```bash
bench/stream/run-stream-matrix.sh
# -> bench/results/b2-2026-11-13/{cells,logs,aggregate}/, cell-meta.tsv, manifest.txt
```

Re-runs never overwrite a populated session; start a new one explicitly:

```bash
bench/stream/run-stream-matrix.sh --session b2-2026-11-13-r2
```

Then aggregate (the driver runs this automatically at the end; it can also be
run standalone):

```bash
python3 bench/stream/analyze_stream.py --session b2-2026-11-13
# -> bench/results/b2-2026-11-13/aggregate/stream.json + stream.md
```

Driver overrides: `--reps N`, `--seconds S`, `--warmup S`, `--rest S`,
`--cells "W21-128k,..."`, `--session NAME`, `--force` (append to a populated
session), `--dry-run`. Environment: `CPU` (default 2), `READ_FILE`,
`WRITE_FILE`, `SEASTAR_BIN`, `JURING_RUN`, `ALLOW_RESIDUAL_LOAD=1` (relax the
1-min loadavg gate for chained sessions). Results live under
`bench/results/` (gitignored); the report keeps the tables.

## 3. Preflight (fails the run before any benchmark)

`run-stream-matrix.sh` refuses to start unless:

* both dataset files exist; the read file is readable, the write file
  writable; both are ≥ 10 GiB and a multiple of 1 MiB;
* the write filesystem has ≥ 2 GiB free;
* `kernel.io_uring_disabled=0`;
* the CPU governor is `performance` on every CPU;
* pinned CPU 2 is online;
* 1-min loadavg < 1.0 (unless `ALLOW_RESIDUAL_LOAD=1`);
* no foreign process is using > 20 % CPU;
* the JUring runner script, the seastar stream binary and the analyzer exist.

`--dry-run` reports the same checks as PASS/FAIL/NOTE but never exits on
failure and never creates files. The session `manifest.txt` records host,
kernel, CPU, governor, mounts/device, dataset stats, JDK/g++/seastar versions,
git commit/dirty state, engine-binary/harness SHA256, the exact protocol and the
disclosed engine asymmetries.

## 4. Aggregator (`analyze_stream.py`)

Reads `cells/<cell>__<engine>__<variant>__r<rep>.json` (stdlib only) and writes:

* `aggregate/stream.json` — machine-readable: per cell `config`, per engine row
  `mibs {mean, sd, ci95, cv_pct, min, max, values_by_rep}`, `iops`, chunk
  latency `p50`/`p99` (per-rep means), `errors_total`, `direct_io`,
  `registered_files`, `excluded`/`missing` lists, and the `paired` block
  (`ratio_mean`, `ratio_sd`, `ratio_ci95`, `ratio_one_sided_95_lower`,
  `point_estimate_pass`, `lower_bound_pass`, `verdict`, `dropped_reps`), plus
  `overall`.
* `aggregate/stream.md` — the same as report tables: verdict table, MiB/s
  detail, chunk-latency detail, and a data-quality section.

Statistics: sample sd (`n-1`), two-sided 95 % CI via Student t, paired per-rep
ratio `juring_mibs / seastar_mibs` (same rep index, primary O_DIRECT rows only),
one-sided 95 % lower confidence bound of the ratio. `CV%` is reported per
engine row. Failed/malformed/unpaired reps are listed explicitly; nothing is
silently dropped and the exploratory buffered row is never used for the
verdict.

Verdict (plan M2): `juring MiB/s >= 0.85 * seastar MiB/s`. Per cell:

* `below-bar` — mean ratio < 0.85;
* `pass` — mean ratio ≥ 0.85 **and** the one-sided 95 % lower bound ≥ 0.85;
* `inconclusive` — point estimate clears the bar but the one-sided lower bound
  does not (small-R noise);
* `insufficient-n` — fewer than 2 paired reps.

The overall verdict requires every primary cell listed in `stream.json`
(`W21-128k`, `W22-128k`, `W21-1M`, `W22-1M`) to clear the pairwise bar (point
estimate), with the stricter lower-bound reading reported alongside. The 128K
cells are the methodology's W21/W22 wording; the 1 MiB cells address the plan's
"seq 1M" wording.

## 5. Deviations from the frozen protocol (disclosed)

These are preliminary-measurement deviations; the full plan-M2 campaign is a
separate run owned by the Lead.

1. **Durations / rests are preliminary.** 10 s warmup + 30 s measured + R=3
   with 60 s rests between cell reps (design §3.1/§3.3 defaults), not the full
   M2 methodology (60 s cells, 300 s rests, R=5). The driver records the exact
   values used in `manifest.txt` and the analyzer repeats the observed warmup/
   measured values in `stream.json`/`stream.md`.
2. **Datasets are the existing 10 GiB single files** (`single_file_bench.bin`,
   `single_file_bench_w.bin`) — the writable one is overwritten in place by the
   write cells (`0..dataset`, exactly `dataset` bytes per pass; file size is not
   changed). DS-M multi-file W22 layouts are out of scope for B2.
3. **Buffer-size asymmetry is reported, not hidden.** Cells run at 128 KiB
   (methodology W21/W22) and 1 MiB (plan-M2 "seq 1M" wording); both are reported
   separately rather than collapsed.
4. **JUring buffered row is exploratory only.** It exists to quantify the
   O_DIRECT asymmetry (seastar fstream is DMA/O_DIRECT here) and is never
   counted toward the ≥ 85 % verdict.
5. **No cache drop / no root.** Like the M1 session: files are not dropped from
   page cache; O_DIRECT primary rows make this mostly moot. The 10 GiB datasets
   are far larger than the page cache-friendly working set of a 30 s cell.
6. **Latency is per stream chunk** (`readChunk()` / `write()` complete-to-done),
   i.e. per 128 KiB / 1 MiB buffer, not per underlying 4 KiB I/O. This matches
   the frozen §3.1/§3.2 latency definition on both engines.
7. **The runner never extends or truncates the shared dataset.** `--size` is
   capped at the file size; a write dataset must be a multiple of
   `alignUp(bs, 4096)` so the stream's flush-time alignment truncate cannot
   change the file's size. (The stock `JuringOutputStream` truncates to its
   logical length when the final buffer was alignment-padded.)

## 6. Disclosed engine asymmetries (F0)

| aspect | JUring stream row (primary) | seastar fstream row |
|---|---|---|
| io_uring ring | `entries=max(256,2*qd)`; `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` | stock seastar io_uring reactor backend, ~200-entry ring, no equivalent setup flags |
| files | registered via `JuringFile.register()` (runtime fixed-file table) | not registered (seastar has no file registration) |
| buffers | stream-allocated 4096-aligned native arenas, `fixed_buffers=false` | seastar aligned buffers, unregistered |
| concurrency | blocking `JuringFile.await()` on stream-owned in-flight ops, one owner thread | io_queue + futures/fibers, one reactor shard |
| stream options | `bufferSize=bs`, `readAhead=qd-1` / `writeBehind=qd`, `alignment=4096`, dynamic adjustments off | `buffer_size=bs`, `read_ahead=qd-1` / `write_behind=qd` (seastar defaults otherwise) |
| pinning | `taskset -c 2`, one thread | `taskset -c 2 --smp 1 --cpuset 2`, one shard |
| O_DIRECT | primary row yes (`--buffered` off); buffered row exploratory | always DMA/O_DIRECT on this filesystem |
| latency | per chunk from before `readChunk()`/`write()` to completion | per chunk from before `get()`/`put()` to completion |

Both rows use the same file, same O_DIRECT, same `bs`/`qd`/dataset, same
warmup/measured windows and the same JSON schema (`schema_version 1.0`).

## 7. Manual single-run smoke commands

The runner is usable standalone (short cells; the Lead owns the full campaign):

```bash
# write, O_DIRECT, registered file
env JURING_SKIP_MVN=1 bash bench/juring/run-juring-stream.sh \
    --workload stream_write --file nvme-data/write_files/single_file_bench_w.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 \
    --label w21_smoke --json /tmp/w21_smoke.json

# read, O_DIRECT, registered file
env JURING_SKIP_MVN=1 bash bench/juring/run-juring-stream.sh \
    --workload stream_read --file /mnt/TestData/read_files/single_file_bench.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 --readonly \
    --label w22_smoke --json /tmp/w22_smoke.json

# the seastar side standalone (same CLI, reactor flags, taskset)
LD_LIBRARY_PATH="$PWD/.sysroot/root/usr/lib64" taskset -c 2 \
    bench/seastar/seastar_stream_bench \
    --workload stream_read --file /mnt/TestData/read_files/single_file_bench.bin \
    --bs 128k --qd 8 --size 1g --seconds 3 --warmup-sec 1 --runs 1 --readonly \
    --label w22_seastar_smoke --json /tmp/w22_seastar_smoke.json \
    --smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring
```

`--json -` is not supported: the runner always writes one JSON object to
stdout and, with `--json PATH`, also to `PATH`.

## 8. Reproduce

```bash
# 0. prerequisites (Lead/other workstreams)
mvn -o -q -DskipTests compile                 # produces the B2 stream API classes
bench/seastar/build-stream-bench.sh           # seastar stream runner binary

# 1. validate the plan (no side effects)
bench/stream/run-stream-matrix.sh --dry-run

# 2. run the preliminary W21/W22 session
bench/stream/run-stream-matrix.sh             # default session b2-2026-11-13

# 3. aggregate + verdict
python3 bench/stream/analyze_stream.py --session b2-2026-11-13

# 4. cite
#    bench/results/b2-2026-11-13/manifest.txt
#    bench/results/b2-2026-11-13/cell-meta.tsv
#    bench/results/b2-2026-11-13/aggregate/stream.{json,md}
```

The datasets are read-only for the read cells and overwritten in place (never
resized) by the write cells; results are gitignored under `bench/results/`.

---

# B6 durability + group commit (W24/W25)

B6 adds a **durability** protocol (W24, supporting evidence only — not a milestone
bar) and a **group-commit** micro-protocol (W25, JUring-only) on top of the B2
buffered-stream work. Frozen contract: [`docs/b6-design.md`](../../docs/b6-design.md)
§6 (W24 = §6.3, W25 = §6.4) + its **A2** amendment (W25 rests are between cells, not
before every rep). The M2 W21/W22 comparison is **cited, not re-run**.

## 9. B6 runner changes (additive; schema stays `"1.0"`)

`bench/juring/JUringStreamBench.java` and `bench/seastar/seastar_stream_bench.cc`
keep every historical field/meaning and their `schema_version "1.0"` (frozen by
`docs/b2-design.md`; the shard/file tooling hard-asserts versions). B6 marks itself
with a top-level `"schema_extensions": ["b6-durability-1"]` and adds only new keys:

| runner | new CLI | new JSON |
|---|---|---|
| JUring stream | `--durability writeback\|fdatasync\|fsync\|dsync` (default writeback); `--sync-every BYTES` (write only, multiple of `--bs` and 4096, default 0 = pass end only); `--fadvise normal\|random\|sequential\|willneed\|dontneed\|noreuse` | `config.{durability,sync_every_bytes,fadvise,sync_latency_samples_cap}`; per-run `{durability_mode,sync_every_bytes,durability_points,syncs_issued,syncs_completed,syncs_failed,bytes_synced,sync_wait_us{samples,avg,p50,p90,p99,max},sync_wait_share}`; `aggregate.*_total` |
| seastar stream | `--durability writeback\|fdatasync\|dsync` (default fdatasync = historical behaviour); `--durable 0\|1`; `--dsync 0\|1`; `--sync-every BYTES`; `--durability fsync` is **rejected** (seastar has no `fsync(2)` path: `file::flush()` is `fdatasync` only) | `config.{durability,sync_every_bytes,durable,dsync,aio_fsync,sync_counting}`; per-run same fields + `passes`; `aggregate.passes_total` and sync totals |

Semantics (`docs/b6-design.md` §3.1/§6.1–§6.2):

* `writeback` = drain only at a durability point; `fdatasync`/`fsync` = drain + exactly
  one matching sync; `dsync` = `O_DSYNC` file, writes durable on completion and **no**
  explicit synced call (`syncs_issued == 0`; the stream/group asserts the file mode).
* `--sync-every S` makes `flush()` a durability point every `S` bytes inside a write
  pass; the pass-end flush+close is unchanged. On JUring `close()` is one durability
  point (one sync for fdatasync/fsync); seastar's historical pass end is
  `s.flush(); s.close()` = **two** fdatasync per pass — irrelevant for the W24
  `--sync-every 4M` arms (<1 % of the points) and reported through `syncs/s` and
  `MiB/sync` instead of being hidden.
* `syncs_issued/completed` are actual sync operations for JUring (`JuringOutputStream.
  syncs()` delta) and bench-derived for seastar (one fdatasync per stream flush/close
  with `durable=true`; seastar exports no fsync counter — recorded in
  `config.sync_counting`). `durability_points` counts *all* durability-point calls
  (draining writeback/dsync arms included) so `sync_wait_us`/`sync_wait_share` are
  defined for every arm; `sync_wait_share = Σ durability-point call time / measured
  elapsed`.
* The seastar runner must be run with `--aio-fsync 1` (pinned by the driver) so
  `file::flush()` is `IORING_OP_FSYNC|IORING_FSYNC_DATASYNC`, not a blocking
  thread-pool `fdatasync`; the effective value is recorded as `config.aio_fsync`.
* `--fadvise` is informational only (no W24 verdict uses it; W24 runs `normal`).

`bench/stream/analyze_stream.py` is **byte-identical** (sha256
`6ce4bb94f53076ffb64119480d81209a9567af6604b02ec436c19da79fb51b43`) and still
re-emits `m2-final` from the frozen raw cells (`python3
bench/stream/analyze_stream.py --session m2-final`; only the `generated_utc`
timestamp changes — verified byte-identical modulo that timestamp).

## 10. W24 durability A/B (`run-durability-ab.sh` + `analyze_durability.py`)

**Supporting evidence only, not a milestone bar.** One write cell, matched durability
settings on both engines, writeback-vs-writeback control bracketing the block.

| item | value |
|---|---|
| cell | `W24-128k`: `stream_write`, bs 128 KiB, qd 8, `--size 2g` on the existing 10 GiB write file (the file is never resized; the 2 GiB dataset is a **disclosed deviation** from the frozen 10 GiB) |
| Tier 1 (decisive) | 4 arms, all `--sync-every 4M`: `juring-writeback`, `juring-fdatasync`, `seastar-writeback`, `seastar-fdatasync`, R=3 |
| Tier 1 protocol | **300 s idle rest before each rep window**, then the 4 arms run **back-to-back inside that post-rest window** in an order alternating per rep (rep 1 `Jw,Jd,Sw,Sd`; rep 2 `Sd,Sw,Jd,Jw`; rep 3 `Jw,Jd,Sw,Sd`); 5 s warmup + 15 s measured per arm; one fresh process per invocation; `taskset -c 2`; the `juring-writeback` arm runs **first overall** (rep 1) and **last overall** (an extra `writeback-bracket` control after rep 3) — those two appearances are the write-state control pair |
| Tier 2 (exploratory) | `juring-dsync` / `seastar-dsync` (R=2, 120 s rests, `--sync-every 4M`); optional `juring-fsync` (`--durability fsync`, JUring-only: seastar cannot fsync). `--skip-fsync` disables it |
| wall time | Tier 1 ≈ 3×(300+4×20)+20 s ≈ 19.3 min; Tier 2 ≈ 5–6 min; preflight/analysis ≈ 2 min ⇒ **≈ 26–30 min** |
| session | `bench/results/b6-durability-<date>/` with `cells/`, `logs/`, `aggregate/`, `manifest.txt`, `cell-meta.tsv` (M2's 20 columns; new sync data lives in the JSON, §6.5) |
| variants | `writeback`, `fdatasync`, `dsync`, `fsync` (JUring only), `writeback-bracket` |

```bash
# plan only (side-effect free: preflight report + every command line + wall estimate)
bench/stream/run-durability-ab.sh --dry-run
# short validation run (same protocol, tiny windows; machine must stay quiet)
ALLOW_RESIDUAL_LOAD=1 bench/stream/run-durability-ab.sh \
    --reps 1 --warmup 1 --seconds 2 --rest 5 --tier2-reps 1 --tier2-rest 5 \
    --size 64m --session b6-durability-smoke
# official session (Lead only, quiet machine; default R=3/5 s/15 s/300 s, size 2g)
bench/stream/run-durability-ab.sh
# aggregate standalone / resume an interrupted session
python3 bench/stream/analyze_durability.py --session b6-durability-<date>
bench/stream/run-durability-ab.sh --resume
```

Driver overrides: `--reps`, `--warmup`, `--seconds`, `--rest`, `--tier2-reps`,
`--tier2-rest`, `--size`, `--skip-tier2`, `--skip-fsync`, `--no-analyze`, `--resume`,
`--session`, `--force`, `--dry-run`. Environment: `CPU` (2), `WRITE_FILE`,
`SEASTAR_BIN`, `JURING_RUN`, `ALLOW_RESIDUAL_LOAD=1`. The driver refuses to overwrite a
populated session (use `--resume`/`--session`), preflights the machine (dataset/space,
io_uring, governor, CPU, loadavg, foreign CPU, artifacts) and records everything in
`manifest.txt`, including the pinned seastar `--aio-fsync 1`.

`analyze_durability.py` (**requires** `schema_extensions` to contain
`b6-durability-1`; a mixed/old session fails loudly with exit 3) emits
`aggregate/durability.{json,md}` with:

* per arm: MiB/s mean/sd/95 % CI (Student t)/CV %, `syncs_issued`/s,
  `MiB/sync` = `bytes_synced/syncs_completed`, `sync_wait_share`, durability-point
  (sync) latency p50/p99, per-rep write-state class;
* the **within-rep paired durability-cost ratio** per engine (durable MiB/s ÷ writeback
  MiB/s, same rep) with 95 % CI over reps, plus the state-matched subset;
* the frozen **state-class rule** (fixed in the analyzer *before* the campaign, derived
  from M1 §4 + M2 §6): a rep is `fast` iff MiB/s ≥ 550 **and** write p50 ≤ 250 µs;
  `slow` iff MiB/s < 300 **or** p50 > 600 µs; else `mixed`; the M1 0.92 same-arm rule
  also marks a rep slow. Classes are computed **within the arm** (a durable arm is
  designed to be slower), never across arms; class counts/n/CV are printed;
* the disclosure rules: an arm pair is ranked only if both sides have ≥ 2 reps in the
  same class (else INCONCLUSIVE); an arm with CV > 10 % is *"unstable, no ranking"*
  (methodology D7); the two writeback controls bracketing the Tier-1 block must agree
  within 10 %, otherwise the session is state-confounded and only the raw table is
  reported;
* every rep is kept; excluded/missing/unparsed files are listed explicitly.

**W24 disclosures** (also in `aggregate/durability.md`, `manifest.txt` and
`docs/b6-design.md` §6/§9): supporting evidence only, no new cross-engine write
win/loss claim; M2's write arms issued 2 fdatasync/pass on seastar vs 0 on JUring and
did not disclose it — W24 matches durability and brackets with writeback controls;
durability points include a write-behind drain on both engines (so absolute
`sync_wait_share` is an upper bound on pure sync cost); seastar's pass-end double
fdatasync; the `juring-fsync` arm is JUring-only; the dataset is 2 GiB, not 10 GiB; no
cache drop and no power-loss test.

## 11. W25 group-commit micro-benchmark (`run-group-commit-bench.sh` + `analyze_group_commit.py`)

**JUring-only**: seastar has no group-commit primitive (the nearest equivalent is the
W24 `seastar-fdatasync` arm, which pays a write-behind drain the JUring helper does
not — disclosed as an upper bound). The bench calls the **public `GroupCommit` API**
(`GroupCommitOptions`/`GroupCommit`/`GroupCommitStats`) and uses no test hook.

| item | value |
|---|---|
| runner | `bench/juring/JUringDurabilityBench.java`: `--file`, `--batch B`, `--records N` **or** `--seconds S`, `--bs 4096`, `--durability fdatasync\|fsync\|writeback` (default fdatasync), `--warmup-sec`, `--runs`, `--file-size`, `--json`, `--label`, `--verify-only`, `--verify-bytes`, `--no-verify` |
| cells | `W25-4k-b1`, `W25-4k-b8`, `W25-4k-b32`, `W25-4k-b128` (bs 4096 B, batch = `GroupCommit.maxBatchSize`) |
| workload | preallocate the file (setLength, `--file-size 1g` default); for each group fill `B` distinct 4096-aligned buffers with a deterministic position-indexed xorshift64 pattern, `B` `GroupCommit.write()` calls at consecutive aligned offsets (wrapping modulo the file capacity), then **one** `commit()`; measured window 15 s after a 5 s warmup, fresh process per invocation, `taskset -c 2`, R=3 |
| rests (A2) | 5 s pause between reps inside a cell, **120 s idle rest between cells** (3 rests; `--rest-between cells\|reps\|none`, default `cells`; total session ≈8–12 min incl. the read-back) |
| restart visibility | after **every** measured run the bench spawns a fresh JVM (`--verify-only`) that re-reads `[0, min(committed_bytes,file_size))` and compares every 4096-byte block with the deterministic pattern; `restart_verify{ok,verified_bytes,method,power_cut_tested:false,...}` is in the run JSON. This is **process-restart visibility, NOT a power-loss test** |
| acceptance identities | `fsyncs_per_group == 1.0` (exactly one fdatasync per committed batch), `groups_total × batch_size == committed_writes` (a short last group is allowed only in `--records` mode with `committed_writes == records`), `syncs_completed == syncs_issued == groups_total`, `syncs_failed == 0`, `restart_verify.ok == true` |
| session | `bench/results/b6-group-commit-<date>/` with `cells/`, `logs/`, `aggregate/`, `manifest.txt`, `cell-meta.tsv` |

```bash
# plan only
bench/stream/run-group-commit-bench.sh --dry-run
# short validation run (same runner/analyzer, tiny windows, scratch file)
ALLOW_RESIDUAL_LOAD=1 bench/stream/run-group-commit-bench.sh \
    --reps 1 --warmup 1 --seconds 2 --rest 0 --rep-pause 0 --batches "1 8 32 128" \
    --file scratch/b6-bench/smoke/w25-driver.bin --file-size 16m --session b6-group-commit-smoke
# official session (Lead only, quiet machine; defaults R=3/5 s/15 s/120 s between cells)
bench/stream/run-group-commit-bench.sh
# aggregate standalone / resume
python3 bench/stream/analyze_group_commit.py --session b6-group-commit-<date>
bench/stream/run-group-commit-bench.sh --resume
```

Driver overrides: `--reps`, `--warmup`, `--seconds`, `--rest`, `--rest-between`,
`--rep-pause`, `--batches`, `--file`, `--file-size`, `--skip-compile`, `--no-analyze`,
`--resume`, `--session`, `--force`, `--dry-run`. Environment: `CPU`, `W25_FILE`,
`W25_FILE_SIZE`, `JURING_CLASSES` (default `target/classes`), `JURING_OUT` (default
`bench/juring/out`), `ALLOW_RESIDUAL_LOAD=1`. The driver compiles the runner with
`javac` against the existing `JURING_CLASSES` (it never runs Maven; the Lead owns the
repo-root build) and preflights the B6 API presence, file space, io_uring, governor,
CPU, loadavg and foreign CPU.

`analyze_group_commit.py` (**requires** `b6-durability-1`) emits
`aggregate/group-commit.{json,md}`: per cell MiB/s (mean/sd/95 % CI/CV), writes/s,
groups/s, group-latency p50/p99 (ms), sync-latency p50/p99, syncs/s, MiB/sync,
`sync_wait_share`, `fsyncs_per_group`, wrap-arounds, identity PASS/FAIL rows and the
per-rep table; it **exits 3** (and lists the offending files/reps) if any identity,
`restart_verify` or schema check fails. `sync_latency_us` is the `commit()` duration
and is an upper bound on the sync-only cost (it includes waiting for the batch writes;
the public API exposes no sync-only hook).

## 12. B6 smoke evidence and guardrails

* Smoke sessions and their raw JSON/logs live under `scratch/b6-bench/smoke/`
  (never in `bench/results/`, which is reserved for real sessions); see
  `scratch/b6-bench/STATUS.md`.
* `bench/stream/analyze_stream.py`, the M1/M2/M3 artifact scripts, `src/`, `docs/` and
  `pom.xml` are untouched by the B6 bench layer (the design fixes the write scope).
* The official W24/W25 sessions are run by the **Lead** on a quiet machine; they are
  the only heavy device work in B6, and the drivers refuse to start if the preflight
  (loadavg/foreign CPU/governor/files/io_uring) fails.

