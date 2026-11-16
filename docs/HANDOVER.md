# Handover — JUring vs seastar comparison (sessions through 2026-11-16; M1 read PASS + **Step A & Session-12 write campaigns executed (write rule 1 INCONCLUSIVE, no write-parity claim)** + **Step D host representativeness complete** + **Step E publishing complete** + B1 + B2 + M2 + B3 + B4 + B5 + M3 + B6 + B9; B7/B8 decided; **full comparison programme complete**)

For the next agent/session. Read **§0.0 (session 12 — Step D + write-state follow-up + Step E publishing, latest)**, **`docs/stepd-report.md`** (Step D deliverable), **`docs/stepd-design.md`** (frozen Step D protocol + Amendment A1), **`docs/m1-write-state-design.md`** (frozen state-controlled write protocol), **`docs/m1-write-report.md`** (Step A deliverable + §12 state-controlled write follow-up — write rule 1 INCONCLUSIVE), **`README.md`** and **`BENCHMARK.md`** (Step E published tables), **`scratch/stepd-lead/FREEZE.md`**, **`scratch/stepd-write/FREEZE.md`** and **`scratch/stepd-verify/VERDICT.md`** (independent verification), then **`docs/b9-report.md`** (session-10
deliverable — **B9 delivered; API stability, Javadoc, examples, upgrade notes**),
**`docs/b9-design.md`** (frozen B9 contract + amendments A1–A3), **`scratch/b9-verify/VERDICT.md`**
(independent session-10 verification), **`scratch/b9-lead/FREEZE.md`** (session-10 freeze
identity + acceptance evidence), **`docs/b7-decision.md`** + **`docs/b7-gate.md`** (B7
**deferred** by measured gate) and **`docs/m4-decision.md`** (B8/M4 QoS **out of v1**); then
**`docs/b6-report.md`** (B6 deliverable — **B6 delivered, independently verified**),
**`docs/b6-design.md`** (B6 frozen contract + amendments A1/A2), **`scratch/b6-verify/VERDICT.md`**
(independent B6 verification), **`scratch/b6-lead/FREEZE.md`** (B6 freeze/session identity), then
**`docs/m3-report.md`** (M3 deliverable — **M3 PASS, signed**), **`docs/m3-design.md`**
(M3 frozen bars/protocol incl.
pre-campaign Amendment A1), `bench/results/m3-2026-11-15/` (campaign, gitignored),
**`docs/b5-report.md`** (B5 deliverable), **`docs/b5-design.md`** (B5 frozen
API/semantics contract + amendments A1/A2/A3), **`scratch/b5-verify/VERDICT.md`**
(independent B5 verification),
**`scratch/b5-lead/FREEZE.md`** (B5 freeze identity), then **`docs/b4-report.md`**
(B4 deliverable), **`docs/b4-design.md`** (B4 frozen API/semantics contract),
**`scratch/b4-verify/VERDICT.md`** (independent B4 verification), **`docs/m2-report.md`**
(M2 deliverable), **`docs/b3-design.md`** (B3 frozen API/semantics contract),
**`docs/b2-report.md`** (B2 deliverable), **`docs/b1-report.md`** (B1 deliverable),
**`docs/m1-report.md`** (M1 deliverable),
**`docs/b1-design.md`** (B1 frozen API/semantics contract), `SEASTAR-PLAN.md` (v1.9),
`docs/benchmark-methodology.md` (the measurement contract), and `bench/README.md` +
`bench/write/README.md` + `bench/stepd/README.md`.

## 0.0 Session 12 status — 2026-11-16 — **latest** (Step D + state-controlled write campaign closed under Path B; Step E publishing complete)

Session 12 froze and executed the overnight unattended host campaign (`bench/results/unattended-2026-11-16/`, 3 h 15 min, `exit=0`), closed out **Step D** and the **state-controlled write protocol** under **Path B (no benchmark reruns)**, independently verified all raw and aggregate artifacts (`scratch/stepd-verify/VERDICT.md`, 422 assertions, PASS), and completed **Step E (publishing)** across `README.md` and `BENCHMARK.md`.

**Pre-campaign freeze (`af44602`, `1c7e732`).** `docs/stepd-design.md` v1.0 (sha1 `b3781fd9…`, `scratch/stepd-lead/FREEZE.md`) and `docs/m1-write-state-design.md` v1.0 (sha1 `eca5a1c4…`, `scratch/stepd-write/FREEZE.md`) were frozen before any measurement, together with `bench/stepd/{run-stepd.sh,analyze_stepd.py,run-write-chain.sh,write_state_analysis.py,run-unattended.sh}` (all sha256s verified in `STATUS.txt`).

**Phase 1 — Step D host representativeness (`bench/results/unattended-2026-11-16/stepd/`, 75/75 runs `status=ok`, 0 failures, [`docs/stepd-report.md`](stepd-report.md)).**
- **Block A (`Q1 / F10` — seastar THP vs 2 MiB `hugetlbfs`, `R=3` paired):** Paired `huge/THP` mean ratios are **0.9966** `[0.9943, 0.9989]` at W3 (4K QD64: `317,986` vs `319,074` IOPS, −0.34 %, `< 5 %`), **1.0122** `[0.9475, 1.0768]` at W4 (4K QD128: `388,830` vs `384,248` IOPS), **0.9836** `[0.9105, 1.0567]` at W10 (1M QD8), **1.0389** `[0.8721, 1.2057]` at W11 (1M QD32), and **1.0123** `[0.9589, 1.0657]` at M3N8 (4K QD64 × 8 shards: `467,341` vs `461,800` IOPS). No material difference anywhere.
- **Amendment A1 (Path B, disclosed in `docs/stepd-design.md` and `docs/stepd-report.md` §3.2):** `run-stepd.sh` sampled `HugePages_Free` before spawning `seastar_file_bench` (`hp0 = 4608`) and after process termination (`hp1 = 4608`), emitting 15 warnings to `logs/warnings.log`. Source inspection (`SeaStarRef/src/core/memory.cc:2016-2025`, `1360-1385`) confirmed that `--hugepages` opens an unlinked temporary file (`file_desc::temporary`) in `/mnt/hugepages-juring` mapped with `MAP_SHARED | MAP_POPULATE` (and throws on failure), so all pages are freed by the kernel on process exit before `hp1` is read. Under Path B, all 15 `status=ok` `huge` runs are kept with this sampling disclosure.
- **Block B (`Q2 / A1` — `unlimited` memlock on 1 MiB seqread W9/W10/W11, `R=5` paired):** All 15 JUring runs verified `"io_buffer_mode": "registered-fixed"` and all 15 fio runs verified `"fixedbufs": 1, "registerfiles": 1`. Paired `JUring / fio-matched` mean ratios are **1.0138** `[0.9825, 1.0451]` (W9 QD1), **1.0108** `[0.9582, 1.0635]` (W10 QD8), and **1.0005** `[0.9573, 1.0437]` (W11 QD32), all at the ~4.7 GiB/s ceiling.
- **Host SMART & cold-cache (`Q3 / Q4`):** Root `nvme smart-log` before/mid/after (`state/smart-{before,mid,after}.txt`) confirmed **`Thermal Management T1/T2 Trans Count = 0`**, `critical_warning = 0`, `media_errors = 0`, `percentage_used = 3%`; root `drop_caches` verified (`F11`; primary cells are `O_DIRECT`). Cleanup restored `vm.nr_hugepages = 4096` and unmounted `/mnt/hugepages-juring`.

**Phase 2 — State-controlled write protocol (`bench/results/unattended-2026-11-16/write-state/`, 7 generations = 42/42 runs `status=ok`, [`docs/m1-write-report.md` §12](m1-write-report.md)).**
- Pilot `w0-control` (`precond_rest=600s, rest=0s`) **failed homogeneity** (`W7 max/min = 2.0921`: `191,497 / 167,671 / 91,534` IOPS), proving that a 600 s rest partially rebuilds SLC burst capacity that exhausts mid-generation.
- Pilot `w1a-continuous` (`precond_rest=0s, rest=0s`) **passed** (`W7 max/min = 1.0483`, `W8 max/min = 1.0584`, all 6 runs `steady=True`), selecting `continuous` for W2 per the pre-registered adaptivity sequence.
- Gate campaign `W2` (`w2-g1`..`w2-g5`, 5 generations with `continuous`): **2 valid generations (`w2-g1`, `w2-g3`)** and 3 invalid (`w2-g2`, `w2-g4`, `w2-g5`, where fio arms entered a 39.7k–84.8k GC state during the 120 s warmup, violating `max/min ≤ 1.25`). With `n_valid = 2 < 4`, the frozen verdict is **`INCONCLUSIVE`**. Under Path B, the v1 write half closes as `INCONCLUSIVE` (no ranking, no write-parity claim).

**Step E — Publishing (`README.md`, `BENCHMARK.md`, `SEASTAR-PLAN.md` v1.9) + `blackpanda` removal (`D12`).**
- Replaced the legacy un-gated JMH/fio tables in `README.md` with the layered L0 (M1), L1 (M2 + B6 `GroupCommit`), and L2 (M3 + B5 observability) results, 95 % CIs, paired ratios, CPU/syscall metrics, tail latencies, Step D host-representativeness findings, and the write-half `INCONCLUSIVE` disclosure (while keeping all runnable code examples and compatibility links intact).
- Upgraded `BENCHMARK.md` into Part I (the complete reproducible L0/L1/L2 `bench/` programme with reproduction commands) and Part II (supplementary JMH microbenchmarks with explicit estimator/CI disclosures).
- Removed `com.davidvlijmincx.lio.blackpanda` (`src/main/java/com/davidvlijmincx/lio/blackpanda`, `src/test/java/com/davidvlijmincx/lio/blackpanda`, `exports com.davidvlijmincx.lio.blackpanda` in `module-info.java`, and the Tier-2 sections in `docs/api-stability.md` / `docs/UPGRADING.md`) to keep the repository strictly library-first (`D1` / `D12`). `com.davidvlijmincx.lio.api` (36 public types) is now the sole exported package and is byte-for-byte unchanged.

**Session-13 independent verification (2026-11-16, Lead).** Re-derived the campaign artifacts from raw data (all Step D/write-state numbers reproduce; verdict in `scratch/verify-session13/VERDICT.md`), re-ran the build after the blackpanda removal (**397 tests, 0 failures, 0 errors, 1 skipped; javadoc 0 warnings**) and **corrected the Step E publication tables and Step D §5.2**: the M2 stream MiB/s/CIs and M3 seastar/fio means/CIs and Seastar/JUring ratios in `README.md`/`BENCHMARK.md` were wrong (now regenerated from `bench/results/m2-final/aggregate/stream.json` and the M3 raw cells), the Step D W10/W11 paired `JUring/Seastar` CIs were replaced with true paired intervals, and the Step D host-state paragraph now reports the actual `write_sectors_delta`/loadavg values plus **Amendment A2** (per-run F14 gate was implemented only at session preflight). No library or campaign data changed.

**Session-14 post-programme cleanup (2026-11-16, Lead).** The `com.davidvlijmincx.lio.blackpanda` source and tests were already removed from the tree in `ed88c77` (D12); this session removed the **leftovers**: a clean `mvn -o clean test` regenerated `target/` (stale blackpanda classes, test-classes, surefire XML/TXT and Javadoc pages are gone), `docs/juring-inventory.md` now carries an explicit superseded-snapshot banner instead of presenting `lio.blackpanda` as current state, and the L3 row in `docs/benchmark-methodology.md` no longer names the removed package. Historical audit records (the D12/D14 decision rows, session-10 B9 frozen deliverables, and this handover's session logs) are retained deliberately. Gate re-verified after the cleanup: **397 tests, 0 failures, 0 errors, 1 skipped; Javadoc 0 warnings**; `SEASTAR-PLAN.md` v1.10 records that the seastar programme is closed. **Next work: [`FINAL-PLAN.md`](../FINAL-PLAN.md) — reviewed and re-scoped 2026-11-16 to B10a (ring-level completion multiplexing) then B10b (`ioprio`) only; the README overhaul was dropped from scope.**

## 0.0a Session 11 status — 2026-11-15 (Step A executed: full M1 write matrix; write-half verdict INCONCLUSIVE — no ranking)

Session 11 executed **SEASTAR-PLAN Step A** (the M1 write matrix) end-to-end and delivered
the write-half evidence. No library/engine code changed; the work is harness + measurement +
analysis + docs.

**Frozen design and pre-campaign commits.** `docs/m1-write-design.md` v1.0 was frozen before
any measurement (sha1 `1268685b…`; freeze record + sha1s + dry-run evidence in
`scratch/stepa-lead/FREEZE.md`). Commits: `9c87532` (harness), `dc12e10` (analyzer),
`f747f49` (frozen design = campaign tree), `5e27e20` (Amendment A1 + `regfile` variant).

**Harness work (all reviewed/dry-run before use).** `bench/write/precondition.sh`: Step-A D1
fix — default 4 GiB chunks + target-driven stop (8 chunks = 34,359,738,368 B in ~45 s instead
of the 2026-11-13 128.8 GiB overshoot; `--condition and --chunk-bytes 10g` reproduces the old
block). `bench/write/run-write-cells.sh`: `--reps-by-cell`, run-granular `--resume`,
multi-invocation seq/manifest continuation (`manifest-run<N>.txt`, `state/invocations.tsv`),
fio p99 ns→µs fix for all engines + `manifest cell_meta_p99_units=us`, `batch1` sweep variant,
`regfile` variant (A1). New `bench/write/run-write-campaign.sh`: 3 sequential blocks, verified
run counts 24/70/36, recorded 300 s between-block rests, abort on mismatch/failure. Analyzer
`bench/m1/analyze_m1.py`: write-session mode, **both-comparator rule 1**, D2 series/cliff/
burst-steady/write-amplification, W7 sweep, seastar bracket, write summary + 2 new CSVs;
read-session output byte-identical, legacy W7 0.9644/[0.9417,0.9870] regression reproduced.

**Pilot + campaign.** Pilot smoke in `bench/results/stepa-pilot-2026-11-15/` (7/7 ok; never
cited) validated the end-to-end path. Campaign: `bench/write/run-write-campaign.sh
--session-dir bench/results/m1-write-2026-11-15`, **31,653 s (8 h 47 m)**; **130/130 planned
runs executed** (124 ok; **6 failed-init** 1M fio-`matched` rows due to the 8 MiB memlock
limit) and all **130 successful measured runs** analysed (124 main + 6 A1 `regfile` rerun).
Per-cell D1 bursts (8 chunks / 34.36 GB each), 2 between-block rests, 1 s device series per
run, thermal 36.9–49.9 °C, `loadavg_start` max 1.58 (0 F14 flags), read dataset stat
unchanged.

**Result — M1 write rule 1 = `INCONCLUSIVE` (no ranking; no write-parity claim).** Gate rule
(PASS iff one-sided 95 % LB ≥ 0.95 against **both** fio `matchedbatch` and `matched` at
**both** W7 and W8): W7 primary fast-subset ratio 0.9628, LB **0.9502** but only **2 pairs**
(sensitivity 0.9591/LB 0.8937 → INCONCLUSIVE); W8 both comparators INCONCLUSIVE (LB 0.8441 /
n=1). Root cause is a **device write-state transition, not rep count**: **36/130 runs show a
1 s write cliff** (within-run burst/steady ratios 0.14–3.85×; e.g. W6 fio/default r1
195k→59k, W7 fio/matched r2 194k→87k) and **21/34 cells are unstable (17 with CV>10 %)** →
methodology D7 "unstable, no ranking". The 2026-11-13 n=3 INCONCLUSIVE was therefore not
merely a rep-count problem. Everything is in `docs/m1-write-report.md` (all tables generated
from `bench/results/m1-write-2026-11-15/aggregate/m1-write.*`); read-path PASS is unchanged.

**Amendment A1 (execution, disclosed).** fio `--fixedbufs=1` cannot be registered for 1 MiB
cells at QD8/QD32 (8 MiB/32 MiB locked memory vs 8 MiB memlock): six W13/W14 `matched`
attempts failed at fio init and are recorded/excluded; they were re-measured as `regfile`
(`--registerfiles=1` only, read-path precedent) in a separate invocation into the same
session dir. The campaign wrapper therefore ended `status=aborted` (runs_failed=6) after all
three blocks — no measurement was lost.

**Docs updated in this session:** `docs/m1-write-report.md` (new deliverable),
`docs/m1-write-design.md` (frozen + A1), `docs/m1-report.md` (session-11 cross-links and the
write-half status in the status line/§13/§14/§15), `SEASTAR-PLAN.md` v1.8 (Step A done,
decision D9), `bench/write/README.md` (campaign executed + results pointer).

**Independent verification — final PASS.** `scratch/stepa-verify/VERDICT.md` (322 lines) =
**PASS-WITH-CONCERNS** with no blocking finding: every gate number, the protocol accounting
(136 order/runs/cells rows = 130 ok + 6 disclosed failed-init; 9 precondition bursts all
target-reached; reps/rests/series/units/read-file stat), the D2 cliff statistics (36/130),
the report tables (row-for-row vs a fresh analyzer run) and the harness regressions were
independently reproduced, and adversarial checks showed the gate consumes the raw data (a
perturbed JSON changes the ratio; a fabricated rep is caught by the ledger). The concerns
were documentation-only (C1–C4/N1–N7); all were corrected in `fda0b8f` + `e0b7693` and
re-checked in `scratch/stepa-verify/ADDENDUM.md` → **final PASS**, no re-measurement. One
verifier process incident is disclosed in VERDICT §I: a gitignored raw JSON was accidentally
overwritten through a symlink, restored byte-equivalently and re-validated (fresh analyzer
run byte-identical to the committed aggregate; only the file mtime differs); no tracked file
was touched.

**Session-11 commits:** `9c87532` harness · `dc12e10` analyzer · `f747f49` frozen design
(main-campaign tree) · `5e27e20` Amendment A1 + `regfile` · `24120e7` write report/plan/
handover · `308ad45` read-path status-line clarification · `fda0b8f` + `e0b7693` verifier
corrections; the final docs commit records these ids. Worktree clean; `scratch/` evidence
(`stepa-lead/`, `stepa-harness/`, `stepa-analyzer/`, `stepa-verify/`) is gitignored.

**What remains after Step A:** **Step D** (host-root/representativeness: hugetlbfs,
drop_caches, thermal counters) and **Step E** (publishing decisions) — see §7. For the write
half, the next technical step is a **pre-registered state-controlled write protocol** (pair
engines inside one preconditioned generation and classify 1 s windows into burst/steady
before measuring, or use a drive with verified steady state), not more reps. Optional
follow-ups unchanged (§0.0b/§8): hosted CI, blackpanda prose, M3 N=2, VT-throughput, B0
close-out.

## 0.0a Session 10 status — 2026-11-15 (B9 delivered; B7 deferred; M4 closed; **B0–B9 basics roadmap complete**)

Session 10 closed out the roadmap: the only remaining unconditional item (**B9**) was
delivered, and the two conditional items were decided on the record — **B7 deferred by a
measured gate** (no engine change) and **B8/M4 QoS kept out of v1 by documented decision**.
No behaviour/performance change was made anywhere in the session.

### B9 — delivered (and committed)

**Deliverables:** [`docs/b9-report.md`](b9-report.md) (deliverable), frozen
[`docs/b9-design.md`](b9-design.md) (+ amendments A1–A3, sha1 `595b8488…`),
[`docs/api-stability.md`](api-stability.md) (Tier 1 `lio.api` / Tier 2 experimental
`blackpanda` / 17 internal types; SemVer for `0.y.z` and after 1.0; source+binary promise;
deprecation policy — one pre-policy grandfathered `@Deprecated`, B9 adds none; explicit
non-guarantees; JMX note), [`docs/UPGRADING.md`](UPGRADING.md) (no releases yet; additive
B1–B6 history; future-entry template), [`docs/examples.md`](examples.md) (4 examples mapped
to seastar use-cases), and the README additions (QoS scope note + links; existing snippets
and benchmark tables untouched).

**Javadoc:** complete on both exported packages + package/module docs; `pom.xml` configures
`maven-javadoc-plugin` with `doclint=all`, `failOnWarnings=true`, `failOnError=true`.
Acceptance `mvn -o javadoc:javadoc` = **0 warnings, 0 errors, BUILD SUCCESS**
(baseline, uncapped: **597 warnings**; the old "100" was javadoc's default `-Xmaxwarns` cap —
design A2). The module doc in `module-info.java` is comment-only.

**Examples:** `src/test/java/com/davidvlijmincx/lio/examples/{DirectFile,StreamCopy,ManyFiles,VirtualThreads}ExampleTest.java`
— one `@Test` each, `@TempDir` only, no timing assertions, each genuinely exercising the
claimed feature; they run in the normal suite (`mvn -o test -Dtest='*ExampleTest'`).

**Evidence:** full suite **405 tests / 0 failures / 0 errors / 1 skipped, BUILD SUCCESS**
(401 baseline + 4 examples); javadoc 0/0; **no behaviour change** — with debug info off
(`javac -g:none`, same compiler) baseline `b75515e` and the B9 tree give **76/76
byte-identical class files** (0 added/removed; `package-info.java` emits no class file);
default builds differ only in line-number debug tables (design A3). Freeze record with
content hashes + logs: `scratch/b9-lead/FREEZE.md`.

**Independent verification:** on the frozen tree, `scratch/b9-verify/VERDICT.md` —
**PASS-WITH-CONCERNS** (no finding invalidates B9 or the B7 DEFER; concerns are doc-precision
items). Independently re-derived: 405/0/0/1 suite, javadoc 0/0 BUILD SUCCESS, 76/76 byte-identical
classes under `javac -g:none`, 36+7 public and 17 internal types, exactly one pre-existing
`@Deprecated`, examples non-vacuous, B7 probes/reps reproduce ≤0.6 %, D1/D2 honestly disclosed,
adversarial `@param` removal breaks the javadoc build. Verification-driven corrections (in
`docs/b9-report.md` §8/§9): (C1) the **pristine-baseline** `mvn -o javadoc:javadoc` actually
exits **1** with 2 doclint errors + 100 capped (597 uncapped) warnings — the earlier "exit 0"
came from a `-q | tail` invocation that masked the exit code; B9's tree is exit 0 / 0/0.
(C2) `mvn -o javadoc:javadoc` needs a prior build on a clean checkout (plugin modular
aggregated-report behaviour; reproduces on the pristine baseline) — acceptance sequence is
`mvn -o test && mvn -o javadoc:javadoc`. (C3) wording precision fixed in
`docs/b7-decision.md`, `docs/m4-decision.md`, `docs/examples.md`, README (D1
`batches/op ≈ syscalls/op` with values; QoS-grep wording; `@Timeout` = hang guard only).
(C4) the verifier's B7 spot-check ran outside the quiet window (load1 2.48–3.05) with IOPS
−3/−5 % but cycles/op within 0.6 % — verification data only, not a gate datum.
**Verification addendum (task-7): PASS** — the corrected docs were re-checked against the
verified snapshot: frozen-set changes are exactly the four declared docs (docs-only; src/pom/
test/bench unchanged, hashes in FREEZE D5), the C1/C2/C3 wording is accurate, §9 fairly
represents the verdict, and a fresh-copy spot-check still gives 405/0/0/1 + javadoc 0/0
(`scratch/b9-verify/ADDENDUM.md`).

**Disclosures:** no CI service exists in the repo, so "examples run in CI" is satisfied as
"examples are JUnit tests executed by `mvn -o test` (the command a CI would run)" — a hosted
CI workflow is a follow-up. The compatibility policy is forward-looking (no release exists).
Blackpanda's pre-existing performance prose ("sub-millisecond P99", "zero heap allocations")
was left untouched (not part of the policy/evidence; possible future reword). Amendments
A1 (one pre-policy `@Deprecated`), A2 (597 baseline; module-info comment-only; MXBean work
split; workspace lock), A3 (`-g:none` proof method) are all docs-only and sha1-recorded.

### B7 — DEFERRED, no engine change (measured decision gate)

Frozen gate [`docs/b7-gate.md`](b7-gate.md) (+A1), outcome
[`docs/b7-decision.md`](b7-decision.md), raw evidence `scratch/b7-gate/` (12/12 clean runs,
R=3, quiet window, all reps kept). Key findings: `IORING_OP_READ_MULTISHOT` returns
**`-EBADFD` on regular files** on this kernel (pipe positive control works); JUring has no
in-scope pollable-fd workload; the pinned seastar uses **no** provided buffers, multishot or
fixed buffers — its 0.186 syscalls/op is reactor batching (`reactor_backend.cc:1652-1660,
1709-1721,1752-1771`), so inventory G9's rationale is corrected; and provided-buffer
selection is **neutral-to-worse** (G1 4K/QD64: 11,156 vs 12,404 cycles/op = **+11.2 %**,
324,855 vs 315,804 IOPS; G2 1M/QD8: +1.0 % cycles/op, IOPS identical). Strict-literal rule
reading is disclosed as matching no branch (the pbuf arm regresses); data direction is
unambiguously DEFER (gate readings D1/D2, decision §4). Re-open only if the kernel adds
regular-file multishot, JUring's scope gains pollable fds, or a real workload shows a pbuf
≥5 % cycles/op win.

### B8 / M4 — CLOSED by documented decision (QoS/intent out of v1)

[`docs/m4-decision.md`](m4-decision.md) satisfies M4's alternative acceptance path. Reasons:
locked scope puts scheduler/QoS in the separate framework (D1; §4 scope note; §3.6 item 5);
seastar QoS is reactor-layer scheduling (`io_queue`/`fair_queue` scheduling groups — a
framework feature); no real consumer exists yet; JUring's API cannot express class/priority;
B8 is L-sized against the library identity. v1 answer: "bring your own scheduler" — partition
with `IoShard`/`RingGroup`, use OS-level I/O controls, apply caller backpressure. No B8 code.

### Provenance and tree state

- Base commit `b75515e` (B6 delivered; M3 signed). Session feature commit **`9de5867`**
  (`docs(api): complete public Javadoc with doclint enforcement and runnable examples (B9)`);
  session-10 docs commit **`ac332ab`** (this file, B9 report, decision docs, plan v1.7,
  README); a final docs commit records these ids (`scratch/b9-lead/commit-ids.txt`).
- Frozen content hashes and acceptance evidence: `scratch/b9-lead/FREEZE.md`;
  contracts: `docs/b9-design.md` `595b8488…`, `docs/b7-gate.md` `36a19a8c…`.
- Worktree is clean after the commits; `scratch/` is gitignored (evidence only).

### What remains (nothing from B0–B9; all measurement/publishing)

- **Step A — M1 write matrix — DONE 2026-11-15 (session 11).** Full W5–W8/W12–W14 matrix
  measured; write rule 1 `INCONCLUSIVE` (device write-state cliffs; no ranking, no
  write-parity claim); `docs/m1-write-report.md`. The budget decision below is closed.
- **Step D — host-root / representativeness runs** (hugetlbfs, drop_caches, thermal counters).
- **Step E — publishing decisions** (README/BENCHMARK claims vs `docs/m1-report.md`).
- Optional follow-ups: hosted-CI workflow (examples already run in `mvn test`), reword
  blackpanda's pre-existing performance prose, M3 N=2 re-run for the narrowest bar,
  VT-throughput re-benchmark, B0 close-out (stored baseline + per-commit W1/W3 gate).

## 0.0b B6 status — ninth session (2026-11-15)

**B6 (buffered I/O parity + durability, L1 / gap G12) is DELIVERED, tested and independently
verified.** Independent verification: **PASS** (55/55 adversarial modes, 1,680 assertions, own private-copy suite 401/0/0/1, 4,916 bench assertions, frozen identity unchanged; `scratch/b6-verify/VERDICT.md`). Deliverables:
[`docs/b6-design.md`](b6-design.md) (frozen contract written before implementation, sha1
`7490aa9b…`; amendments A1 = `inFlightCapacity()` public per §2.3, A2 = W25 rests between
cells), [`docs/b6-report.md`](b6-report.md) (deliverable), `scratch/b6-verify/VERDICT.md`
(independent verification), `scratch/b6-lead/{FREEZE.md,session-hashes.txt,code-hashes.txt}`
(frozen identity + official-session hashes), official sessions
`bench/results/b6-durability-2026-11-15/` (W24) and `bench/results/b6-group-commit-2026-11-15/`
(W25) (gitignored).

- **New public API (com.davidvlijmincx.lio.api, additive only; no new dependency, no
  `module-info`/`pom` change, existing defaults unchanged):** `FadviseAdvice` (POSIX advice
  enum), `DurabilityMode` (`WRITEBACK`/`FDATASYNC`/`FSYNC`/`DSYNC`), `GroupCommit` +
  `GroupCommitOptions` + `GroupCommitStats`; `JuringFile.durability()`/`fadvise(...)`/
  `fadvise(...)`/`inFlightCapacity()`/`syncOperations()`/`syncCompletions()`;
  `InputStreamOptions.fadvise()`/`withFadvise(...)`; `OutputStreamOptions.durability()`/
  `fadvise()`/`withDurabilityMode(...)`/`withFadvise(...)`; `JuringOutputStream.durability()`/
  `syncs()`. Full signatures in report §2.
- **Semantics:** `posix_fadvise` hints are advisory (blocking libc downcall through
  `LibCDispatcher`, no ring request/ledger/counter change, no ring-ownership check) and are
  applied once at stream open (`NORMAL` = no syscall). `JuringOutputStream`'s `flush()` and
  `close()` are **durability points**: `FDATASYNC`/`FSYNC` issue exactly one
  `fdatasync`/`fsync` per point, `WRITEBACK` keeps the frozen B2 behaviour (flush never
  syncs; close syncs once iff `syncOnClose`), `DSYNC` asserts an `O_DSYNC`/`O_SYNC` open and
  issues no explicit sync. `GroupCommit` is a caller-driven batch: `write()` queues async
  writes (no auto-commit; batch-full ISE), `commit()` awaits them and issues **exactly one**
  `fdatasync`/`fsync` for the batch (none for `WRITEBACK`/`DSYNC`; empty commit = no-op),
  failures latch, `close()` commits a pending batch and never closes the file;
  `maxBatchSize + 1 <= file.inFlightCapacity()` is validated at open. Proof counters on both
  the helper and `JuringFile` (`syncOperations()`/`syncCompletions()`).
- **Tests:** full `mvn -o test` on the frozen tree = **401 tests, 0 failures, 0 errors,
  1 skipped** (349 baseline byte-identical + **52 new**: `B6FadviseTest` 12,
  `B6DurabilityModesTest` 8, `B6GroupCommitTest` 17, `B6GroupCommitCrashTest` 1,
  `B6StreamDurabilityTest` 14). The crash test spawns a child JVM that commits one FDATASYNC
  batch and `halt(137)`s; a fresh parent reopens and verifies every byte (process-restart
  visibility on tmpfs — no power-loss claim). Zero-allocation steady state for
  `GroupCommit.write/commit` measured (0 B).
- **Benchmark evidence (supporting, not a milestone bar):** W24 durability A/B (JUring vs
  seastar `fstream`, matched explicit durability, `--sync-every 4M`, 19/19 invocations ok,
  0 errors) — matched-durability accounting confirmed (`MiB/sync` ≈ 3.99 both engines;
  writeback/dsync 0 explicit syncs) but the drive's write-state bimodality confounded
  throughput (writeback bracket control drifted 148.9 % > 10 %), so **no ranked
  durability-cost number is quoted** (raw per-rep table in report §7). W25 group-commit
  micro-bench (12/12 ok, 0 errors) proves the plan's acceptance clause:
  **`fsyncs_per_group == 1.000000`** at batch sizes 1/8/32/128 with
  `groups × batch == committed_writes`, 0 syncs failed, and **fresh-process restart
  verification PASS** in every cell; durable-write throughput rises 5.9 → 720.7 MiB/s
  (1,522 → 184,497 4 KiB writes/s) at one `fdatasync` per batch (report §8).
- **M2 disclosure (new, important):** the signed M2 W21 write comparison was
  durability-asymmetric — seastar ran `durable=true` (2 `fdatasync` per pass: fstream
  `flush()` + `close()`) while the JUring arm ran `syncOnClose=false` (0 per pass); M2 did
  not state this. B6's W24 arms remove the asymmetry and run writeback-vs-writeback controls;
  M2's numbers remain the signed buffered comparison and **must not** be quoted as
  "durability is free" evidence (report §6).
- **Deliverable scope:** B6 does **not** change M1/M2/M3 sign-off status, does not touch the
  B1/B2/B3/B4/B5 behaviours, and intentionally does not add async `IORING_OP_FADVISE`,
  concurrent/multi-writer group commit, a commit timeout, or a power-loss test (design §0/§9).
- **Provenance:** frozen tree G3b src/main `5690c498…`, src/test `6796f83e…`, bench
  `b2eb2d80…`, design `7490aa9b…` (all re-derived unchanged after G4 and G5); **feature
  commit `2883b34`** (`feat(api): add fadvise hints, durability modes and group commit (B6)`);
  final Lead suite on the committed tree = 401/0/0/1 BUILD SUCCESS; docs commit (this
  HANDOVER + `docs/b6-report.md` + `SEASTAR-PLAN.md` v1.6 + B1/B2 addenda) follows. Official
  sessions: `bench/results/b6-durability-2026-11-15/` (19/19 ok) and
  `bench/results/b6-group-commit-2026-11-15/` (12/12 ok), hashes in
  `scratch/b6-lead/session-hashes.txt`.
- **Next:** B7 (provided-buffer ring + multishot) is the next library item and is conditional
  on a measurement need; B8/M4 QoS remains conditional; B9 API stability/docs later. Still
  open from earlier sessions: M1 write matrix (Step A), DS-M / host-root representativeness
  (Step D), publishing decisions (Step E), optional VT-throughput re-benchmark and the N=2 M3
  re-run.

## 0.1 M3 status — eighth session (2026-11-15)

**M3 (L2 multi-shard scaling + metrics) is SIGNED OFF: PASS.** All five frozen bars pass on the
official campaign; every rep kept, no trimming. Deliverables: [`docs/m3-design.md`](m3-design.md)
(frozen before measurement, v1 `334bb55a…` commit `b8b7832`; **Amendment A1** pre-campaign
`d391cad3…` commit `1f1e548`), [`docs/m3-report.md`](m3-report.md) (deliverable), session
`bench/results/m3-2026-11-15/` (gitignored; **60/60 invocations `status=ok`**, 5 h 36 m campaign),
seastar `--smp N` runner `bench/seastar/seastar_file_bench.cc` (commit `97e818d`),
driver `bench/shard/run-m3-campaign.sh` + analyzer `bench/shard/analyze_m3.py` (commits
`93fb6fe`/`1f1e548`/`8bbde41`), independent seastar evidence `scratch/m3-seastar/STATUS.md`
(`parity.txt`), Lead evidence `scratch/m3-lead/` (diagnostics, pilot, chunk logs, device state).

- **Frozen protocol:** 12 cells = {`juring-group`, `fio numjobs=N`, `seastar --smp N`} ×
  N∈{1,2,4,8}, 4K randread QD 64 per worker/shard, **10 s warmup + 30 s measured, R=5,
  300 s rest between invocations**, fresh process/JVM, engines interleaved with the order
  alternating per rep, every rep kept; aged shared 10 GiB DS-S file (read-only; **no DS-M**);
  N=8 on the data-NVMe hctx CPU set `1,2,4,6,8,10,12,14` (fio/seastar confined to it,
  `juring-group` **unpinned** inside the same `taskset` mask, `mq/*/cpu_list` recorded);
  machine alone; pre-campaign 30 s placement diagnostic + 2 s pilot (`m3-pilot-2026-11-15`,
  not analysed into M3).
- **Bars (a)–(e) all PASS:** (a) one-sided 95 % LB of JUring/fio = **0.959 / 0.859 / 0.961 /
  0.987** at N=1/2/4/8 (N=8 **473,126 vs 459,125 IOPS = 103.2 %**; N=2's LB is the narrow
  margin, one disclosed slow-state rep); (b) complete same-N SE + p99-vs-fio comparison with
  one-sided CIs (SE_j/SE_f 1.017 at N=4, 1.041 at N=8; p99 ratio 0.886/0.832 — different
  engine latency definitions, disclosure only); (c) per-shard min/mean at N=8 mean **0.963**,
  95 % LB **0.947** (closes the B4 0.802 pinning artifact); (d) seastar/JUring at N=8 mean
  **0.986**, 95 % LB **0.959 ≥ 0.90** (plan-direction 1.015); (e) 75 per-ring rows from B5
  JSON 1.1 — mean in-flight 62–65, runtime completion p99 492 µs (N=1) → 2,962 µs (N=8),
  external p99 493 → 2,771 µs, **0 failed/cancelled/timedout**. B5 <1 % overhead bar carried
  from the previous session.
- **State gate:** 6/60 reps flagged (host QD64/saturation bimodality), all `status=ok`, kept in
  every mean/CI, disclosed in report §4; no gate-retry invocation was needed.
- **Disclosures:** Amendment A1 was made before any campaign invocation existed (only 2 s
  pilot/smoke data) to restore the brief-literal wording of bars (b)/(d); the first automatic
  analysis pass hit a **session-loader bug** in the analyzer (bars c/e read `n/a` →
  INCONCLUSIVE), fixed before the report (path only; no bar rule, no data touched; pre-fix
  output archived under `aggregate/first-pass-loader-bug/`). The 2 s pilot under-reports
  `juring-group` N=8 (240 k; JIT/warmup), which the 30 s diagnostic resolved before the
  campaign; see report §7.
- **Provenance:** code under test = B5 frozen tree `0952deb` (`src/main` `04f12dbc…`,
  `src/test` `666e0644…`); campaign/benchmark-tree HEAD `1f1e548`; full suite `mvn -o test`
  green on the frozen tree (349 tests, 0 failures, 0 errors, 1 skipped — see
  `scratch/m3-lead/FINAL-SUITE.log`). Exact commands in §7 Step C and report §6.
- **Next:** publishing decisions (§8); optional M1 write matrix (Step A), B6 durability,
  VT-throughput re-benchmark, DS-M / host-root representativeness (Step D); optional
  re-run of the N=2 cell on a different day to firm bar (a)'s narrowest margin.

## 0.2 B5 status — seventh session (2026-11-14)

**B5 (observability, L2) is DELIVERED, tested and independently verified (PASS) — but M3 is
still NOT signed off**: the full R=5 W17–W20 campaign (incl. a seastar `--smp N` runner and
the restated M3 scaling bars) remains. Deliverables: [`docs/b5-design.md`](b5-design.md)
(frozen contract incl. amendments A1/A2/A3, written before implementation),
[`docs/b5-report.md`](b5-report.md) (deliverable), [`scratch/b5-core/STATUS.md`](../scratch/b5-core/STATUS.md)
(engine metrics evidence), [`scratch/b5-runtime/STATUS.md`](../scratch/b5-runtime/STATUS.md)
(shard/JMX evidence), [`scratch/b5-verify/VERDICT.md`](../scratch/b5-verify/VERDICT.md)
(independent verdict FINAL PASS), [`scratch/b5-lead/FREEZE.md`](../scratch/b5-lead/FREEZE.md)
(frozen identity).

- **New public API (com.davidvlijmincx.lio.api):** `RingMetrics` (18-component per-ring
  snapshot: prepared/submitted/reaped/completed/failed/cancelled/timedOut/inFlight,
  SQ+CQ occupancy, enters/submitBatches/submitErrors/submitRetries/sqFullEvents,
  cqOverflowEvents), `LatencySnapshot`, `LatencyHistogram` (512-slot log-linear,
  allocation-free record, inclusive-bucket-max percentiles clamped to the recorded max),
  `ShardMetrics`, `GroupMetrics`, `IoShardMetricsMXBean`, `RingGroupMetricsMXBean`,
  `MetricsJmx` (+ nested `Handle`). Additive: `JUring.ringMetrics()`, `IoShard.metrics()`,
  `RingGroup.metrics()`/`aggregateMetrics()`, `ShardOptions.DEFAULT_LATENCY_SAMPLE_EVERY`/
  `latencySampleEvery()`/`withLatencySampleEvery(int)`. `ShardStats` unchanged (B4 compat).
  **No new runtime dependency**; `module-info` gains `requires java.management;` for JMX;
  `pom.xml` unchanged.
- **Semantics:** ring counters are owner-thread opaque/atomic writes with acquire snapshots
  (no tearing); CQEs are classified in every consumption path so `reaped == completed +
  failed`; `timedOut` counts kernel `-ETIME(-62)` or `-ETIMEDOUT(-110)` (A1.1); snapshots are
  best effort and frozen after close (`sqQueued/cqReady = 0`, effective ring depths retained,
  never throw); ring counters include runtime-internal wakeup/cancel ops. Shard dispatch
  latency = deterministic every-Nth `onCompletion` registration → handler invocation
  (registration→dispatch; includes kernel/device completion; disabled by default; histogram
  allocated only when enabled; empty for body-hosted `JUringBlocking` shards). JMX =
  pull-based MXBeans on the platform `MBeanServer`, deterministic ObjectNames, duplicate
  rejection, group rollback, idempotent handles, latency in µs, no exporter thread.
  Micrometer deliberately not shipped (the snapshot API is the binding seam). Full contract:
  design §2–§11 + A1/A2/A3.
- **Tests:** `mvn -o test` **349 tests, 0 failures, 0 errors, 1 skipped**, BUILD SUCCESS
  16.698 s on the frozen tree (309 B4 baseline byte-identical + **40 new** in 4 classes:
  `B5RingMetricsTest` 14, `B5LatencyHistogramTest` 11, `B5ShardMetricsTest` 10,
  `B5JmxTest` 5). Zero-allocation: metrics-on engine hot loop 0 B/op, histogram `record()`
  0 B, shard registration path 0 B (a sporadic single-round JVM one-off is documented in
  `scratch/b5-runtime/STATUS.md`).
- **Overhead A/B (< 1 % bar) — PASS** on the frozen tree vs a `git archive 6cc1a75`
  baseline build: mean paired throughput ratios `1.0190 / 1.0110 / 1.0070` for shard N=1 /
  shard N=8 / threads N=1 (stdev 0.030/0.040/0.037; one-sided 95 % CI lower 0.991/0.973/
  0.972; min rep 0.9543; no rep-floor violation under either state-gate labelling; no
  fallback re-run needed). Final config: metrics on, 1 Hz series,
  `withLatencySampleEvery(1)`. Session: `bench/results/b5-overhead-2026-11-14/`
  (gitignored; `aggregate/overhead.{md,json}`, identity in `manifest.txt`).
- **Benchmark JSON schema 1.1:** additive per-worker `ring_metrics` + measured-phase 1 Hz
  `ring_series` (queue depth + p99) for both runtimes, shard-mode `dispatch_latency_us`
  (runtime completion latency, A2.2), run-level `metrics`, aggregate `ring_queue_depth` +
  `dispatch_latency_us`; `analyze_shard.py` emits per-ring queue-depth/p99 tables (tolerant
  of old sessions). Example per-ring evidence (shard8, B5 arm): mean in-flight 64.1–64.9
  (max 65), runtime-completion p99 2831–4116 µs reproducing the known CPU/hctx split, 0
  failed/cancelled. **This is the M3 per-ring queue-depth/p99 data source.**
- **Independent verification PASS:** 60/60 adversarial modes (own byte-identical src copy,
  own classpath — never `target/classes`; fresh JVM + external timeout per mode), 2 355
  assertions, its own full suite 349/0/0/1 and 21 365 independent bench-artifact assertions;
  frozen hashes re-derived before/after every phase. One contract-wording finding (F1,
  histogram percentile clamping) was resolved by amendment A3 (no source change); no open
  defect. See `scratch/b5-verify/VERDICT.md` (R1–R11 residual risks).
- **Residual risks:** design §10 + A1/A2/A3 — best-effort non-linearizable snapshots; ring
  counters include internal ops; JMX is pull-based/local-only; histogram percentiles are
  bucket maxima (over-estimate < 1/16 for ≥16 ns, clamped to the recorded max) and not for
  single-request SLOs; dispatch latency is a runtime-boundary completion latency (not fio
  `clat` / not the external `latency_us`); body-hosted shards have no latency histogram;
  `cqOverflowEvents` is best effort; the overhead bar is established for the §7.1 protocol
  only; no Micrometer dependency.
- **Provenance:** base commit `6cc1a75` (B4 `159d60f`); **B5 delivered in commit
  `309335b`** (`feat(api): add ring metrics, latency histogram, JMX and bench JSON (B5)`);
  this HANDOVER + `docs/b5-report.md` + `SEASTAR-PLAN.md` v1.4 follow in a docs commit.
  Frozen-tree identity: `src/main` content sha1 `04f12dbc85d723a10dd952b7ac3c51bb6253ed46`,
  `src/test` content sha1 `666e0644dc063c0b15c5bb52571002a79b5ec657`, tracked
  `git diff src/main/java` sha1 `2933a9064465f4e85968052fba2b2a8eabb20790`, design
  (final, incl. A3) sha1 `ff60e870d0d4d08acbbd6bc9931b0a9d457f0698`.
- **Next:** **M3 campaign** (full W17–W20 R=5, state gating, 300 s rests, seastar
  `--smp N` runner, DS-M/per-shard-file decision, seastar 90 % LB; restate the scaling bars
  vs `fio` at the same N or hold total QD fixed; topology-aware placement one shard per
  NVMe hctx or unpinned). B5 completes the metrics half of M3; **M3 is still not signed**.
  Earlier-session work still open: M1 write matrix (Step A), B6 buffered durability. The
  optional B4 follow-up (VT-throughput re-benchmark) is unchanged.

## 0.3 B4 status — sixth session (2026-11-14)

**B4 (multi-ring / shard runtime, L2) is DELIVERED, tested and independently verified
(PASS) — but M3 is NOT signed off**: B5 (observability) and the full W17–W20 campaign
(incl. a seastar `--smp N` runner and the seastar 90 % CI LB) remain. Deliverables:
[`docs/b4-design.md`](b4-design.md) (frozen contract, written before implementation),
[`docs/b4-report.md`](b4-report.md) (deliverable), [`scratch/b4-core/STATUS.md`](../scratch/b4-core/STATUS.md)
(core author evidence), [`scratch/b4-blocking/STATUS.md`](../scratch/b4-blocking/STATUS.md)
(integration evidence), [`scratch/b4-verify/VERDICT.md`](../scratch/b4-verify/VERDICT.md)
(independent verdict, FINAL PASS).

- **New public API (com.davidvlijmincx.lio.api):** `IoShard` (one `JUring` ring owned by a
  dedicated platform thread: best-effort CPU affinity via `sched_setaffinity`, batched
  poll loop through `io_uring_enter` with an eventfd wakeup READ, completion dispatch,
  bounded/graceful shutdown, per-shard `ShardStats`), `RingGroup` (fixed ring pool,
  round-robin + hash work distribution, shared-deadline group close), `ShardOptions`,
  `ShardTask`, `ShardContext`, `CompletionHandler`, `ShardStats`. Package-private:
  bounded Vyukov `MpscQueue`, allocation-free `CompletionTable`, `ShardBody`/
  `ShardLoopContext` loop-body seam. Minimal additive internals: `LibCDispatcher.
  sched_setaffinity`/`sched_getcpu` FFM handles, `JUring.prepareWakeupRead`/
  `prepareCancelAnyInternal`/`pendingSqes`/`dispatcher`. **No existing B1/B2/B3 behaviour
  changed** (all existing tests unchanged green).
- **Runtime semantics:** ring-per-thread affinity (ring created/used/closed on the shard
  thread, `SINGLE_ISSUER` owner), RR/hash distribution (stable splitmix64 for keys, no
  rebalancing), cross-thread completion handoff via MPSC + eventfd (bounded spin → inline
  fallback on overflow, countable, never drops/blocks), batched poll loop
  (`cqeBatch`/`taskBatch`/`spinIterations`), graceful bounded shutdown (drain accepted
  tasks → ring-wide cancel → dispatch `-ECANCELED` → B3 bounded ring close; `timeout+250 ms`
  wait, `closeTimedOut` on a stuck user task), backpressure on full inbound (foreign
  blocks, shard-thread ISE), use-after-close throws. Full contract: design §2–§11.
- **`JUringBlocking` integration:** each worker is now hosted by an `IoShard` through the
  package-private loop-body seam (the tuned MPSC/eventfd/slot state machine/park-unpark
  loop is unchanged and runs on the shard thread → zero-GC VT hot path preserved, measured
  **0 bytes/call**). Public API only adds `JUringBlocking(RingGroup)` (adopts an unstarted
  group, inherits affinity) and `close(long timeoutMillis)`; **bounded close now fixes the
  B3 §4.5 residual hazard** (a cancellation completion that never arrives is abandoned by
  the bounded ring close; parked callers unblock; close is `timeout + 250 ms` bounded).
- **Tests:** `mvn -o test` **309 tests, 0 failures, 0 errors, 1 skipped**, BUILD SUCCESS
  (~16 s) on the frozen v2 tree; 238 B3 baseline + **71 new** (60 core/table + 11 blocking)
  in 18 new test classes; no existing test changed/weakened. Lead and the independent
  verifier both ran the full suite on the frozen tree.
- **Independent verification (PASS):** 105/105 adversarial modes (fresh JVM + external
  timeout each; 937 assertions) + independent suite on the frozen tree. The verifier found
  **one real defect before delivery (F1)**: `CompletionTable` deletion could spin forever
  on a full table after a table-full-ISE task left prepared SQEs to complete unregistered →
  deterministic 100 %-CPU shard wedge. Fixed (bounded probes + textbook backward-shift
  deletion, no re-insertion/tombstones), regression-tested (`B4CompletionTableTest`),
  design §4/§5/§9.3 amended, and independently re-verified before/after (F1 repro now
  `stalled=false`, bounded close 2–3 ms, `unattributed=128`, progress continues). Second
  finding F2 was documentation-only (close-unblocked callers get a real `-ECANCELED`, not
  always ISE) and §9.3 was reconciled.
- **Preliminary W17–W20 scaling (NOT M3 sign-off):** session `bench/results/b4-2026-11-14/`
  (gitignored): 36/36 invocations `status=ok`, 4K randread, QD64/worker, N∈{1,2,4,8},
  CPUs 1..N, 10 s warmup + 30 s measured, R=3, 60 s rests, all reps kept. **`juring-group`
  8-shard total IOPS = 98.5 % of `fio numjobs=8`** (per-rep 1.041/0.922/0.996; the
  runtime-relevant M3 bar, PASS), 97.3 % of the historical manual-thread pattern at N=8,
  and matching/beating fio at N=2/4 (1.007/1.013). The plan-M3 `SE(8)>=0.75` and
  `p99(8)<=1.15*p99(1)` bars are **invalid for this single-device, constant-QD-per-shard
  protocol** — reference `fio` itself scores SE(8)=0.181 and p99 ratio 5.90 (manual 0.184 /
  5.46) because the drive saturates at ~470k IOPS by N=2-4; those bars need restating for
  M3. The per-shard `min/mean>=0.85` miss under the pinned layout (0.802) was diagnosed to
  the **data-NVMe blk-mq hctx↔CPU topology** (CPUs 1 and 8 own an hctx; 2-7 pair on shared
  queues), **not** a runtime defect: unpinned group-8 diag = 0.965/0.983/0.976 flat, same
  total IOPS. M3 guidance: pin one shard per hctx (CPUs `1,2,4,6,8,10,12,14`) or run
  unpinned, and record `mq/*/cpu_list`. Incident disclosed: the first pass was SIGTERMed
  when the bench agent's turn ended (17 good cells kept, one incomplete rep archived and
  re-run via a new `--resume` path; final 36/36 ok, no trimming).
- **Residual risks:** design §11 (not a scheduler; inline handoff fallback breaks strict
  target-thread affinity only on counted overflow; cyclic `execute` backpressure can
  deadlock by user error; a stuck user task cannot be killed — `close` returns bounded with
  `closeTimedOut` and the ring is released when the task returns; handler `Result` is
  reusable; affinity best-effort; only the default
  `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` flags are in the acceptance path; handler-table
  capacity must cover peak live registrations or registration ISE degrades to counted
  unattributed completions; B3 §11.9/F1 (concurrent non-fixed `READ_EXT` same-buffer
  `user_data` sharing) is documented, not fixed; `JuringFile` blocking helpers inside a
  shard task can hit the B1 ownership guard in the rare queued-behind-a-batch window).
- **Provenance:** base commit `25c664b`; **B4 delivered in commit `159d60f`**
  (`feat(api): add multi-ring shard runtime IoShard/RingGroup (B4)`; this HANDOVER +
  SEASTAR-PLAN v1.3 follow in a docs commit). Frozen v2 content hash
  `93ce8a716c0f3b56e9ce76b8806db5eff99fc66f` (src/main), test content
  `74f9c344596a4a7c8353f915b9dcbdf0ebae2f37` (`scratch/b4-lead/FREEZE.md`).
- **Next:** **B5** (observability: per-ring counters/histograms, JMX/Micrometer, benchmark
  JSON integration, <1 % overhead) then the M3 campaign (W17–W20 R=5, state gating, 300 s
  rests, DS-M/per-shard-file decision, seastar `--smp N`); the M1 write matrix (Step A) and
  B6 (buffered durability) are still open from earlier sessions; optional B4 follow-up:
  move `JUringBlocking`'s slot protocol onto the generic task loop only if a VT-throughput
  re-benchmark justifies the dedup.

## 0.4 M2 + B3 status — fifth session (2026-11-14)

**M2 (L1 stream parity) is SIGNED OFF: PASS.** Deliverable `docs/m2-report.md`; raw session
`bench/results/m2-final/` (gitignored; 60/60 invocations `status=ok`, 0 errors; 4 cells ×
{juring, seastar, juring-buffered} × R=5; 10 s warmup + 60 s measured; 300 s rests; CPU 2;
commit `2198330`, clean).

- **Verdict:** reads **98.2 % / 98.7 %** of seastar `fstream` (W22-128k / W22-1M; strict
  one-sided-95 % LB 0.966 / 0.960 ≥ 0.85; ±5 % parity equivalence test passes). Writes
  **139 % / 160 %** of seastar on point estimates (W21-128k / W21-1M), but the strict write
  LB is **not established at n=5**: the drive alternates fast (first process after a 300 s
  rest, ~0.6–0.85 GiB/s) / slow (second process, ~0.107–0.572 GiB/s; one fast→fast
  exception, W21-128k r3) write state, so per-rep
  paired ratios are position-confounded (0.16–7.6). Position-matched write ratios:
  **1.23× / 1.27× fast, 0.98× / 1.38× slow**, pooled 1.25× / 1.14× — **no JUring deficit**.
- **Read rep-1 artifact:** both read cells' rep 1 started immediately after the preceding
  write cell under residual page-cache writeback (loadavg 1.93 / 2.19) and juring ran
  first; juring 1M r1 was −10.9 % vs its r2–r5 while seastar's was −5.0 %. This explains
  the residual ~1.6 % read gap; disclosed, no rep dropped.
- **Non-throughput M2 criteria:** 184 tests green (0 failures/errors, 1 skipped), bounded
  memory tests and L0 unchanged — all from B2 at the same commit (`2198330`), re-verified
  by the Lead at M2 sign-off.
- **Provenance cautions:** `aggregate/stream.md|.json` carry stale hard-coded
  "preliminary" text and B2 default-protocol fields (`analyze_stream.py:404,491,599`); the
  recorded protocol in `manifest.txt`/`cell-meta.tsv`/cell JSON is authoritative. Session
  deviations vs methodology: one mixed read+write session (no phase separation), W22 = DS-S
  single file (not DS-M), no D1 preconditioning / D2 1 s series, no thermal telemetry, the
  exploratory buffered row runs last (never used for the verdict). A strict write claim
  needs a separated, state-controlled rerun (see `docs/m2-report.md` §10).

**B3 (request lifecycle hardening) — IMPLEMENTED 2026-11-14; design frozen, suite green,
stress acceptance met, independent adversarial verification PASS.** Deliverables:
[`docs/b3-design.md`](b3-design.md) (frozen contract, semantics live-probed not assumed),
[`scratch/b3/STATUS.md`](../scratch/b3/STATUS.md) (author evidence), and
[`scratch/b3-verify/VERDICT.md`](../scratch/b3-verify/VERDICT.md) (independent verdict).
The independent verifier ran a 68-mode adversarial matrix (**68/68 clean,
MATRIX_FAILURES=0**), independently reproduced **238 tests / 0 failures / 0 errors /
1 skipped**, and confirmed all four phase-1 baseline defects are fixed; it found two
medium documentation gaps (F1/F2, now recorded in design §11) and no acceptance
violation. The Lead independently re-ran the full suite and the stress acceptance below.

- **API delivered:** `IORING_OP_LINK_TIMEOUT` (15) + probe + same-batch/`IOSQE_IO_LINK`
  validation (`JUring.prepareLinkTimeout`); `OperationType.LINK_TIMEOUT(20)` appended
  (0–19 unchanged); `JuringFile` kernel-timed operations (`prepareTimeout(ms)`,
  `prepareRead/Write(...,timeoutMillis)` linked pairs, `read/write(...,timeoutMillis)`)
  with deterministic `ETIMEDOUT(110)` vs `ECANCELED(125)`; cancellation contract
  (`CancelStatus{CANCELLED,ALREADY_COMPLETED,NOT_FOUND}`, `cancel(handle)` → status,
  `cancelAll()`, `cancelledOperations()`, `controlOperations()`; raw
  `prepareCancelByUserData` (real semantics; `prepareCancel` deprecated alias),
  `prepareCancelFile(fdOrSlot, fixedFile)`, `prepareCancelAny()`); bounded shutdown
  `JuringFile.close(timeout)` / `JUring.close(timeout)` (`cancelledAtClose`,
  `abandonedAtClose`, `DEFAULT_CLOSE_TIMEOUT_MILLIS=2000`, hard bound timeout + 250 ms) with
  cancel/reap/force-close and counted abandonment; **use-after-close hardening on every
  native-touching public path** (fixes the verifier's baseline SIGSEGV); use-after-close
  after `ring.close()` now throws `IllegalStateException` instead of crashing.
- **Verified:** `mvn -o test` **238 tests, 0 failures, 0 errors, 1 skipped** (25 classes;
  184 baseline + 54 new in 7 test classes: `JUringLinkTimeoutTest` 8,
  `JUringCancellationTest` 9, `JuringFileTimeoutTest` 7, `JuringFileCancelTest` 7,
  `JuringFileBoundedCloseTest` 9, `JuringLifecycleUseAfterCloseTest` 7,
  `JuringResultSafetyTest` 7). One existing test was disambiguated for the new `close(long)`
  overload (`assertDoesNotThrow(dedicated::close)` → explicit lambda; no assertion
  weakened). Lead re-ran the full suite on the final tree.
- **Stress acceptance (external `timeout`, exit 0; Lead re-ran too):** 50 % in-flight cancel
  at QD64 (100 rounds / 6 400 ops / 3 189 cancelled) and QD256 (30 rounds / 7 680 ops /
  3 828 cancelled) → every op exactly one terminal completion, ring in-flight 0; `JuringFile`
  ledger variant deterministic. Close with 10 000 in flight: `JuringFile.close(2000)`
  2019–2022 ms (bound 2 250 ms), issued=completed=10 000, abandoned=0, cancelledOps=10 000,
  fds stable, RSS plateau; `JUring.close(2000)` with 10 000 in flight ≈8–12 ms,
  cancelledAtClose=10 000, abandonedAtClose=0; use-after-close after stress throws
  `IllegalStateException` on prepare/submit/wait (no SIGSEGV).
- **Live-verified kernel semantics worth knowing:** link timeout fires first → target
  `-ECANCELED`, link `-ETIME`; target wins → link `-ECANCELED` (not 0); a cancel request
  completes `0` when it cancels and `-ENOENT` when the target already completed; a linked
  timeout cannot be cancelled by its own user_data (kernel `-ENOENT` — cancel the target);
  `IORING_TIMEOUT_ETIME_SUCCESS` is accepted but **not honoured by kernel 7.0.12**.
- **Residual risks (design §11, STATUS §8, VERDICT.md R1–R10):** CQ overflow deliberately
  not force-triggered (`IORING_FEAT_NODROP` kernel; contract documented,
  `EBUSY`→`IllegalStateException`); `BOOTTIME/REALTIME` pass-through not separately
  verified; `JUringBlocking` shutdown is still unbounded in the pathological
  cancel-completion-never-arrives case (design §4.5); foreign ring work → abandon+count
  (B4 dispatcher scope); `user_data` 2^56/2^48 opId wrap documented not defended; a timed
  op counts as 2 ring requests in the counters. Verifier-added documentation items (design
  §11.9–§11.12): **F1** concurrent non-fixed reads into the same `MemorySegment` share a
  `user_data` handle and can wedge the file ledger (do not reuse a buffer for concurrent
  `READ_EXT`; B4 may side-table the address), **F2** `cancel`/`cancelAll` need >=1 free
  ledger slot (2 for timed pairs; default 1024 is ample; `close` is safe), **R3**
  `IOSQE_CQE_SKIP_SUCCESS` phantoms make `JUring.close` burn the full (bounded) deadline
  and count abandoned, and `supportsLinkTimeout()` is package-private (detect via
  `UnsupportedOperationException`).
- **Next:** finish the independent verifier pass if needed, then **B6** (buffered durability:
  `posix_fadvise`, durability modes, group commit) and the optional state-controlled strict
  write rerun / DS-M W22 for a stronger M2 write claim.

## 0.5 B2 status — fourth session addendum (2026-11-13 evening)

**B2 buffered stream layer is DONE, tested and verified** (working tree; commit follows this
handover). Detailed evidence: [`docs/b2-report.md`](b2-report.md); frozen contract:
[`docs/b2-design.md`](b2-design.md).

- New public API in `com.davidvlijmincx.lio.api`: `JuringInputStream` (prefetching,
  zero-copy `readChunk()`, complete-until-done `read`, `skip`, adaptive readahead,
  bounded memory), `JuringOutputStream` (write-behind, complete-until-done writes,
  flush/pad+truncate, preallocation hint, `syncOnClose`), `InputStreamOptions`,
  `OutputStreamOptions`. Streams wrap `JuringFile`; they never close it. **No engine
  changes** (`JuringFile`/`JUring`/`IoUringDispatcher`/`OperationType` untouched).
- Bench harness: `bench/juring/JUringStreamBench.java` + `run-juring-stream.sh`,
  `bench/seastar/seastar_stream_bench.cc` + `build-stream-bench.sh`, and the W21/W22
  driver/aggregator under `bench/stream/` (frozen CLI/JSON in b2-design §3).
- Evidence: **mvn -o test = 184 tests, 0 failures, 1 skipped** (B1: 142; +42 stream tests);
  independent reviewer + Lead adversarial fuzzing found 5 real bugs before release
  (input prefetch FIFO indexing, `write(byte[])` lifecycle check, adaptive buffer bound,
  memory-accounting after close, partial-read carry), all fixed; bounded memory verified
  (200 MiB transfers, `bufferMemoryBytes()` within §2 bounds, 0 after close); **L0 W1/W3
  unchanged** (fast-state W3 327,996 vs stored M1 327,371 = +0.19 %, all reps pass the
  p99<600 µs gate; slow reps are the documented host signature); **W21/W22 preliminary
  session** `bench/results/b2-2026-11-13` (36/36 cells ok, 0 errors): point-estimate
  JUring/seastar stream ratio 0.940 (W22-128k), 0.905 (W22-1M), 1.098 (W21-1M) and
  state-matched 1.055/0.998 in the state-transition-contaminated W21-128k; strict 95 % LB
  not established at R=3 → **M2 itself remains open** (needs separated read/write
  sessions, 300 s rests, DS-M, 60 s cells, R=5; the harness is ready).
- Known limits (full list b2-report §5): streams are single-threaded/ring-owned (B3),
  input needs ring entries and `JuringFile.withInFlightCapacity` ≥ `readAhead+2`, output
  semantics are "write from offset 0" with pad+truncate at flush/close, adaptive readahead
  is per-stream/simplified, durability is `fdatasync`-on-close only (B6), not a
  zero-allocation API by contract (the B1 `prepare*`+`await` path remains the
  zero-allocation interface).
- **Next: M2** (full W21/W22 campaign + report), then the write matrix (Step A, still
  pending) and B3/B6.

## 0.6 B1 status — third session addendum (2026-11-13 evening)

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
- Known deviations/decisions (full list in `docs/b1-report.md` §4): `IORING_OP_STATX`
  cannot use `IOSQE_FIXED_FILE` — the engine resolves the slot to the tracked real fd, so
  `length()` works for registered files **while the original fd stays open** (kept by
  decision); `await`/`drain`/convenience ops throw `IllegalStateException` if the ring
  carries foreign in-flight work, while **`close()` never throws** — it cancels its own
  ops, counts them as abandoned and force-closes (their results are lost and completions
  may still arrive via the CQ); `prepareTimeout` requires a caller-managed 16-byte
  `__kernel_timespec` (no ring-arena leak; it is B3 groundwork, unused by `close()`, which
  uses a bounded peek/park drain instead); buffer lifecycle is replace-or-full-unregister
  (files also have per-slot add/remove); the error taxonomy is mixed by design (typed
  `JuringException` for kernel I/O, unchecked exceptions for misuse/unsupported); ring
  registration is ring-wide — do not re-point a live `JuringFile`'s slot externally. The
  ring-level completion dispatcher remains B3.
- **Next: B2** (buffered stream layer: `Input`/`Output`, readahead, write-behind, flush;
  then M2/W21-W22 vs seastar `fstream`). The write matrix (Step A) was measured in session 11:
  **write rule 1 INCONCLUSIVE — no ranking/no write-parity claim** (`docs/m1-write-report.md`).


## 1. TL;DR of where we are

- **Step 0 is done:** `nvme-data/` is a writable bind mount of the data NVMe
  (`/dev/nvme1n1p1[/juring-bench]`, ext4, ~131 GiB free). Read dataset
  `/mnt/TestData/read_files/single_file_bench.bin` stays read-only and is never written.
- **M1 READ PATH: PASS.** `docs/m1-report.md` is final: six-phase, 3-hour session
  (177 cell JSON, 183 meta rows, 0 failed cells) comparing JUring vs seastar
  (`seastar_file_bench`, `file::dma_read`) vs fio on the same aged 10 GiB file, same
  pinning, same per-thread QD, with CIs and state gating. Verdict: rules 1, 2, 3, 5
  PASS; seastar reported as a bracket (rule 4); details in `docs/m1-report.md` §14.
- **Step 2 (write half) measured in session 11** (after the historical first trimmed block
  described below): the full W5–W8/W12–W14 matrix ran under the frozen Step-A protocol
  (130 successful runs, W7/W8 at R=5). Write rule 1 is **INCONCLUSIVE** (device write-state
  cliffs; no ranking, no write-parity claim — `docs/m1-write-report.md`). seastar's write
  path is validated (bracket only). The historical W7 n=3 block (0.9644 / LB 0.9490) is
  kept in `docs/m1-report.md` §13 for provenance.
- **Major validity finding:** read performance is dominated by dataset data state. A
  freshly written 10 GiB file reads **5.2–5.6× faster at QD1** and 1.1–1.7× at QD64 than
  the aged 10-day file (`§11b`; `bench/results/m1-2026-11-13-locality/`). Cross-dataset
  absolute comparisons are invalid; the aged arm reproduced the matrix within 1–3 %.
  Consequently the `io_tester` cross-check is **NOT COMPARABLE** (no runner-accuracy
  conclusion drawn).
- **M2 — L1 stream parity: PASS 2026-11-14.** `docs/m2-report.md`; session
  `bench/results/m2-final` (60/60 ok, 0 errors, R=5, 60 s cells, 300 s rests). Reads
  98.2 %/98.7 % of seastar `fstream` (strict LB 0.966/0.960); writes 139 %/160 % point
  estimate (strict write LB not established at n=5: drive burst→steady state;
  position-matched 0.98–1.38×, no JUring deficit). Bounded memory, 184-test suite and
  L0 unchanged from B2 at commit `2198330`.
- **B3 — request lifecycle hardening: DONE 2026-11-14, suite green + acceptance stress
  PASS** (frozen contract `docs/b3-design.md`; `IORING_OP_LINK_TIMEOUT` linked timeouts,
  deterministic cancellation contract, bounded `close(timeout)` with no hang, use-after-close
  hardening; 238 tests green, 50 %-cancel and 10k-in-flight stress PASS — see §0.4).
- **B4 — multi-ring / shard runtime: DELIVERED + INDEPENDENTLY VERIFIED PASS 2026-11-14**
  (`docs/b4-report.md`, frozen contract `docs/b4-design.md`): `IoShard`/`RingGroup` with
  ring-per-thread affinity, RR/hash distribution, MPSC+eventfd cross-thread completion
  handoff, batched poll loop, bounded shutdown, per-shard stats; `JUringBlocking` re-homed
  on shards (zero-GC kept) with `RingGroup` ctor + bounded close (B3 §4.5 fixed);
  309 tests green; verifier 105/105 modes PASS after fixing one verifier-found
  completion-table wedge (F1) before delivery; **M3 NOT signed at B4 time** — see §0.3.
- **B5 — observability: DELIVERED + INDEPENDENTLY VERIFIED PASS 2026-11-14**
  (`docs/b5-report.md`, frozen contract `docs/b5-design.md` + A1/A2/A3): per-ring
  `RingMetrics` (`JUring.ringMetrics()`), dependency-free `LatencyHistogram`/
  `LatencySnapshot` + shard runtime-completion latency sampling, `ShardMetrics`/
  `GroupMetrics`, JMX MXBeans (`MetricsJmx`), benchmark JSON schema 1.1 with measured-phase
  ring series (queue depth + p99), official overhead A/B **PASS** (mean paired ratios
  1.019/1.011/1.007; <1 % bar met); 349 tests green (309 B4 + 40 new); verifier 60/60 modes
  PASS (one contract-wording finding resolved by A3) — see §0.2.
- **M3 — L2 multi-shard scaling + metrics: SIGNED OFF PASS 2026-11-15** (`docs/m3-report.md`,
  frozen bars/protocol `docs/m3-design.md`): full W17–W20 campaign (12 cells × R=5 = 60/60
  invocations ok, 300 s rests) with `juring-group` + `fio` + the new seastar `--smp N` runner;
  JUring N=8 = 103.2 % of `fio numjobs=8` (one-sided 95 % LB 0.987) and 101.5 % of seastar
  io_uring; per-shard min/mean 0.963 (LB 0.947); bars (a)–(e) all PASS — see §0.1.
- **Repo changes:** prior sessions were additive (`SEASTAR-PLAN.md`, `docs/`, `bench/`,
  `.gitignore`); session 5 changed `src/main/java/.../api/` (B3) and session 6 adds the B4
  runtime (`IoShard`, `RingGroup`, shard support, `JUringBlocking` integration) plus
  `bench/shard/` and `bench/juring/JUringFileBench --runtime=shard`. B4 is committed in
  this session (`159d60f`); `SeaStarRef/`, `.tools/`, `.sysroot/` are gitignored.
- **Milestone order:** B0 harness, B1 `JuringFile`, B2 streams, **M2 stream parity**, B3
  lifecycle, B4 runtime and B5 observability all done; M1 write matrix (Step A) and B6
  remain; M3 campaign is the next milestone. See §0.1/§0.4 and §7.

## 2. Repo map (what exists now)

```
SEASTAR-PLAN.md                 plan v1.4 (read §0, §4, §5; B5/M3 rows updated)
docs/
  b5-report.md                  ** B5 DELIVERABLE (latest) ** observability + overhead + verification
  b5-design.md                  B5 frozen contract (metrics/histogram/JMX/bench JSON) + A1/A2/A3
  b4-report.md                  B4 DELIVERABLE: shard runtime + verification + scaling
  b4-design.md                  B4 frozen contract (IoShard/RingGroup, seam, shutdown, acceptance)
  m2-report.md                  M2 DELIVERABLE: W21/W22 campaign vs seastar fstream
  b3-design.md                  B3 frozen contract (timeouts/cancel/bounded shutdown)
  b2-report.md                  B2 stream layer + tests + W21/W22 preliminary
  b2-design.md                  B2 frozen stream API/semantics + benchmark contract
  b1-report.md                  B1 DELIVERABLE JuringFile + engine ops + L0 evidence
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
  juring/JUringStreamBench.java + run-juring-stream.sh   B2 stream runner (W21/W22)
  seastar/seastar_stream_bench.cc + build-stream-bench.sh  B2 seastar fstream runner
  stream/                       W21/W22 driver (run-stream-matrix.sh), analyzer, README
  results/b2-2026-11-13/        ** B2 W21/W22 preliminary session ** (gitignored)
  results/b2-l0-check/          B2 L0 W1/W3 control (gitignored)
  results/m1-2026-11-13/        ** read session ** cells/ perf/ iotester/ aggregate/
                                cell-meta.tsv perf-cells.tsv iotester-check.tsv
                                manifest.txt notes.md logs/
  results/m1-2026-11-13-write/  write block: cells/ series/ state/ aggregate/ logs/
  results/m1-2026-11-13-locality/ aged/ fresh/ locality.log
  results/2026-11-13/           first-pass baseline (fio matrix + JUring + seastar)
  shard/                        ** B4 W17-W20-style scaling driver/analyzer/README **
  juring/JUringFileBench.java   ** B4: --runtime=threads|shard (+ --shards/--first-cpu) **
  results/b4-<session>/         ** B4 preliminary scaling session (gitignored) **
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

### 4.8 M3 scaling campaign (re-run / resume, ~5.6 h for the frozen R=5 protocol)

```bash
# rebuild the seastar --smp N runner once after any seastar-src change
bash bench/seastar/build-file-bench.sh
# chunks of <=3 rep-blocks; the driver checkpoints every invocation and enforces the
# 300 s inter-invocation rest across chunk boundaries from cell-meta.tsv
ALLOW_RESIDUAL_LOAD=1 bench/shard/run-m3-campaign.sh --session m3-2026-11-15 --max-blocks 3
ALLOW_RESIDUAL_LOAD=1 bench/shard/run-m3-campaign.sh --session m3-2026-11-15 --resume --max-blocks 3
# ... repeat until 60/60; then the frozen-bar analysis:
python3 bench/shard/analyze_m3.py --session m3-2026-11-15   # aggregate/m3.{md,json}
```
Placement/identity/protocol are frozen in `docs/m3-design.md`; a run that changes any of them
is a new design, not an M3 re-run. `ALLOW_RESIDUAL_LOAD=1` relaxes ONLY the chunk-start
loadavg check for our own residual (foreign-process >20 % CPU stays strict).

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

**Session-12 status:** the B0–B9 library roadmap and **all measurement & publishing steps (Steps A, B, C, D, E) are COMPLETE**:
- **Step A** (session 11, [`docs/m1-write-report.md`](m1-write-report.md)): full write matrix measured; write rule 1 `INCONCLUSIVE`.
- **Step B / C** (sessions 3–10): B0–B9 basics roadmap complete; M1 read PASS, M2 stream PASS, M3 scaling PASS, B7 deferred by measured gate, B8/M4 closed by documented decision.
- **Step D + write-state follow-up** (session 12, [`docs/stepd-report.md`](stepd-report.md), [`docs/m1-write-report.md` §12](m1-write-report.md)): host-representativeness campaign (`75/75` ok) and state-controlled write campaign (`42/42` ok) executed and closed under Path B (no benchmark reruns).
- **Step E** (session 12, [`README.md`](../README.md), [`BENCHMARK.md`](../BENCHMARK.md)): public benchmark tables and documentation updated with layered L0/L1/L2 results, 95 % CIs, paired ratios, and disclosures.

### Step A — the write matrix — **DONE 2026-11-15/16 (Step A + Session-12 state-controlled follow-up executed; rule 1 INCONCLUSIVE)**
- Done in session 11 (`docs/m1-write-report.md`, frozen `docs/m1-write-design.md` + A1):
  precondition fix (4 GiB chunks, target-driven stop); W5–W8 + W12–W14 measured with the
  frozen protocol (per-cell D1, 300 s rep rests, 600 s post-preconditioning, 1 s series,
  3 blocks with resume); seastar + fio default/matched/matchedbatch + W7 batching sweep;
  W7/W8 R=5. 130/130 planned runs executed (8 h 47 m), 124 ok + 6 failed-init disclosed
  and re-measured as `regfile` (A1).
- **Outcome:** write rule 1 `INCONCLUSIVE` at W7/W8 (W7 primary comparator passes only on a
  2-pair fast-state subset, LB 0.9502; W7 sensitivity and all W8 rows INCONCLUSIVE). Cause:
  device write-state cliffs inside runs (36/130) → 21/34 cells unstable (17 with CV>10 %) → methodology
  D7 no-ranking. **No write-parity claim.** Read path PASS unchanged.
- **Follow-up (write half) — EXECUTED 2026-11-16 (`docs/m1-write-report.md` §12, `docs/m1-write-state-design.md`):**
  The pre-registered state-controlled write protocol (`W0 → W1-A → W2 × 5`) ran in Phase 2 of
  `bench/results/unattended-2026-11-16/write-state/`. `w0-control` proved 600 s rests rebuild
  transient burst state (`PILOT_PASS=0`); `w1a-continuous` passed (`PILOT_PASS=1`); `W2` yielded
  `2/5` valid generations (`w2-g1`, `w2-g3`, `n_valid = 2 < 4`) → **`INCONCLUSIVE`**. Closed for v1
  under Path B (no benchmark reruns).

### Step B — B0 close-out, then the library work begins
- **B0:** bench/ is now the single source of truth (M1 harness, manifest, one-command
  session, aggregate + generated report) and Step E retired the un-gated JMH claims from
  `README.md` / `BENCHMARK.md`. Optional remaining item: a per-commit manual perf-gate wrapper
  for W1/W3 (fail on >10 % regression).
- **B1 (`JuringFile`) — DONE 2026-11-13** (third session; see §0.6 and
  `docs/b1-report.md`): open with flags/options, offset read/write (plain + fixed-buffer
  fast path), length/truncate/allocate/discard/fsync/fdatasync, close-with-drain
  (bounded, never hangs), typed `-errno` errors, short-IO/EAGAIN policy, registration
  lifecycle (unregister, add/remove at runtime). Evidence: 142 tests green
  (conformance seq/rand/EOF/short-IO/close-with-inflight), **L0 W1/W3 unchanged** (pre/post
  M1 harness inside the §7 band), zero-allocation hot path strict 0 bytes. Deviations and
  open decisions: see §0.6 above and `docs/b1-report.md` §4 (statx tracking workaround;
  non-throwing `close()` vs guarded `await`; caller-managed timeout timespec; buffer
  replace-or-unregister scope; mixed error taxonomy; ring-wide registration).
- **B2 (streams) — DONE 2026-11-13** (see §0.5 and `docs/b2-report.md`): `JuringInputStream`
  /`JuringOutputStream` with readahead/write-behind/flush, bounded memory, 42 new tests,
  L0 unchanged, preliminary W21/W22 (36/36 cells ok) with point-estimate stream ratios
  0.940/0.905/1.098 and state-matched 1.055/0.998; strict M2 bound needs the full campaign.
- **M2 (full W21/W22 campaign) — DONE 2026-11-14** (`docs/m2-report.md`, session
  `bench/results/m2-final`): 60 s cells, R=5, 300 s rests, buffered exploratory row last;
  reads strict-pass (98.2 %/98.7 %), writes point-estimate pass (139 %/160 %; strict LB not
  established due to the drive's burst→steady write state, position-matched 0.98–1.38×).
  Still open from the intended full protocol: separated read/write sessions, DS-M W22,
  D1 preconditioning, D2 1 s series; a state-controlled strict write rerun is optional.
- **Library roadmap — COMPLETE 2026-11-15 (session 10):** B3/B4/B5/B6 delivered earlier;
  **B7 DEFERRED** by its measured gate (`docs/b7-decision.md`; no engine change), **B8/M4
  CLOSED** by documented decision (`docs/m4-decision.md`; QoS out of v1), **B9 DELIVERED**
  (`docs/b9-report.md`; API stability/Javadoc/examples/upgrade notes).

### Step C — the M3 campaign — **DONE 2026-11-15 (M3 PASS, signed)**
- **B5 observability — DONE 2026-11-14** (`SEASTAR-PLAN.md` §4 B5; `docs/b5-report.md`):
  per-ring counters/occupancy, shard runtime-completion latency histogram, JMX MXBeans,
  benchmark JSON 1.1 with per-ring queue depth + p99; overhead A/B PASS (<1 % bar, mean
  paired ratios 1.019/1.011/1.007; session `bench/results/b5-overhead-2026-11-14/`).
- **M3 campaign — DONE 2026-11-15, PASS** (`docs/m3-report.md`; frozen `docs/m3-design.md`;
  session `bench/results/m3-2026-11-15/`, 60/60 invocations ok, 5 h 36 m): `juring-group` +
  `fio` + seastar `--smp N` at N=1,2,4,8, R=5, 10 s + 30 s, 300 s rests, state-gated, all reps
  kept, DS-S shared 10 GiB file (no DS-M), N=8 hctx placement. Bars (a)–(e) all PASS:
  JUring N=8 = 473.1 k IOPS = 103.2 % of `fio numjobs=8` (LB 0.987) and 101.5 % of seastar
  io_uring; per-shard min/mean 0.963 (LB 0.947); seastar/JUring 0.986 (LB 0.959); per-ring
  qd + runtime p99 from B5 JSON 1.1 (0 failed/cancelled/timedout). State gate: 6/60 slow-state
  reps disclosed and kept; no trimming.

### Step D — host-root / representativeness runs — **DONE 2026-11-16 (`docs/stepd-report.md`)**
- Executed in Phase 1 of `bench/results/unattended-2026-11-16/stepd/` (`75/75` runs `status=ok`,
  frozen `docs/stepd-design.md` + Amendment A1 / Path B):
  - **Block A (`hugetlbfs` vs THP, R=3):** paired `huge/THP` mean ratios `0.9966` (W3), `1.0122`
    (W4), `0.9836` (W10), `1.0389` (W11), `1.0123` (M3N8) — no material difference.
  - **Block B (`unlimited` memlock W9–W11, R=5):** `JUring/fio-matched` `1.0138` (W9), `1.0108`
    (W10), `1.0005` (W11), all 30 runs verified `registered-fixed` / `--fixedbufs=1`.
  - **Root SMART & cold-cache:** `nvme smart-log` `T1=0, T2=0` throttle transitions, `media_errors=0`;
    `drop_caches` verified (`F11`).

### Step E — publishing decisions — **DONE 2026-11-16 (`README.md`, `BENCHMARK.md`, `SEASTAR-PLAN.md` v1.9)**
- Replaced the legacy un-gated JMH/fio claims in `README.md` and `BENCHMARK.md` with the
  reproducible L0 (M1), L1 (M2 + B6 `GroupCommit`), and L2 (M3 + B5) tables, 95 % CIs, paired
  ratios, CPU/syscall cost (`perf stat`), tail latencies (`W15/W16`), Step D host-representativeness
  findings, and the write-half `INCONCLUSIVE` disclosure.

## 8. Remaining optional housekeeping (outside the completed comparison programme)

1. ~~Publish the M1 read-path result~~ **DONE 2026-11-16 (Step E)** — published in `README.md`
   and `BENCHMARK.md` with CIs and layered L0/L1/L2 context.
2. ~~Write-matrix budget (Step A) & state-controlled write follow-up~~ **CLOSED 2026-11-16
   (Path B, Decision D10)** — both campaigns executed (`docs/m1-write-report.md` §1–§12); write
   half closed for v1 as `INCONCLUSIVE` (no ranking) unless a future server/enterprise NVMe is
   benchmarked.
3. `nvme-data` bind mount: persist in `/etc/fstab` or re-create when running write benchmarks.
4. `SeaStarRef/` as a git submodule (pinned `04b1c04d`) or keep gitignored.
5. ~~Hugepages/threshold policy~~ **CLOSED 2026-11-16 (Step D, `docs/stepd-report.md`)** —
   `hugetlbfs` vs THP measured on the host (`0.9836–1.0389×`, no material difference).
6. **Hosted CI** (B9 follow-up): optional `.github/workflows/` running `mvn -o test` and
   `mvn -o javadoc:javadoc` on a kernel with `io_uring` enabled.
7. ~~**Blackpanda performance prose** (B9 flag)~~ **CLOSED 2026-11-16 (Decision D12)** —
   `com.davidvlijmincx.lio.blackpanda` was removed from the repository so `JUring` is strictly
   library-first (`com.davidvlijmincx.lio.api` only).

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
git status --short                       # expect clean (session-11 commits in place)
git log --oneline -6                     # expect 5e27e20 (A1) + the session-11 report/docs commit
mvn -o test                              # expect 397 tests / 0 failures / 0 errors / 1 skipped (405 at B9; 8 blackpanda tests removed by D12)
mvn -o javadoc:javadoc                   # expect BUILD SUCCESS, 0 warnings (doclint enforced)
ls -ld nvme-data && touch nvme-data/.probe && rm nvme-data/.probe   # writable?
ls bench/seastar/seastar_file_bench SeaStarRef/build/release/apps/io_tester/io_tester
python3 bench/m1/analyze_m1.py --dir bench/results/m1-2026-11-13   # read aggregates reproduce
python3 bench/m1/analyze_m1.py --dir bench/results/m1-write-2026-11-15 \
    --out-prefix bench/results/m1-write-2026-11-15/aggregate/m1-write   # write aggregate reproduces
head -20 docs/m1-write-report.md         # Step A write-half verdict (rule 1 INCONCLUSIVE)
head -40 docs/HANDOVER.md                # §0.0 session-11 status + §7 what next
# then, per David: Step D (host-root/representativeness) or Step E (publishing); the
# write-half follow-up is a pre-registered state-controlled write protocol (§0.0).
# Optional: CI workflow (§8 item 6).
```
