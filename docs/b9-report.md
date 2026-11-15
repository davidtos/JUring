# B9 report — API stability, Javadoc, examples and upgrade notes (delivered)

**Status:** delivered, session 10 (2026-11-15), frozen contract `docs/b9-design.md`
(sha1 `595b8488…`, including amendments A1–A3). Independent verification record:
`scratch/b9-verify/VERDICT.md` (see §9). This session also closed the plan's two conditional
items with documented decisions — **B7 deferred** (`docs/b7-decision.md`) and **B8/M4 QoS kept
out of v1** (`docs/m4-decision.md`) — bringing the B0–B9 basics roadmap to completion.

**Base tree:** `b75515e` (B6 delivered; M3 signed). **Branch:** `kafka-engine`.
**Session baseline:** 401 tests / 0 failures / 0 errors / 1 skipped, BUILD SUCCESS; javadoc
(doclint=all, true baseline with uncapped warnings) **597 warnings**.

## 0. What was asked (plan §4 B9, gap G7)

> **Do:** semver/compat policy for the public API; finish Javadoc; examples mapping to
> seastar use-cases (direct file, stream copy, many files, VT); upgrade notes.
> **Accept:** `mvn javadoc` clean; written compatibility promise; examples run in CI.

B9 is the last remaining *unconditional* roadmap item. It is documentation-and-examples work:
no behaviour, signature, default, dependency or platform change is allowed, and it does not
touch benchmark/publishing claims.

## 1. Frozen deliverables and where they live

| id | deliverable | file(s) |
|---|---|---|
| D1 | written compatibility/versioning policy (Tier 1/Tier 2/internal surface, SemVer for `0.y.z`/post-1.0, source+binary promise, deprecation policy, explicit non-guarantees, JMX note) | [`docs/api-stability.md`](api-stability.md) |
| D2 | complete Javadoc on the exported API + build enforcement + package docs | `src/main/java/**` (comments only), 2 × `package-info.java`, comment-only `module-info.java` doc, `pom.xml` `maven-javadoc-plugin` block |
| D3 | four runnable, self-checking examples mapped to seastar use-cases + mapping doc | `src/test/java/com/davidvlijmincx/lio/examples/{DirectFile,StreamCopy,ManyFiles,VirtualThreads}ExampleTest.java`, [`docs/examples.md`](examples.md), README pointer |
| D4 | upgrade notes (status, versioning summary, additive capability history, deprecations, migration statement, future template) | [`docs/UPGRADING.md`](UPGRADING.md) |
| D5 | discoverability | README additions only (QoS scope note, examples pointer + section, compatibility/upgrading section); `docs/m4-decision.md` link |

## 2. D1 — compatibility policy

`docs/api-stability.md` states, verified against the tree:

- **Status:** `0.1-SNAPSHOT`, **no published release** (`git tag` empty), so the policy is
  forward-looking and takes effect at the first release; repository
  [github.com/davidtos/JUring](https://github.com/davidtos/JUring) for reports.
- **Surface:** public API = the two packages exported by module `JUring`:
  **Tier 1** `com.davidvlijmincx.lio.api` (36 public top-level types, supported);
  **Tier 2** `com.davidvlijmincx.lio.blackpanda` (7 public top-level types, experimental, no
  compatibility guarantee, MINOR changes still noted in `UPGRADING.md`); internal =
  package-private types (17 enumerated: `Sqe`, `Cqe`, `UserData`, `Iovec`, `IovecBlock`,
  `IovecBlockPool`, `ShardBody`, `ShardLoopContext`, `MpscQueue`, `CompletionTable`,
  `ReadBufferPool`, `IoUringDispatcher`, `LibCDispatcher`, `NativeDispatcher`,
  `io_sqring_offsets`, `io_cqring_offsets`, `io_uring_params`) — no guarantee.
- **SemVer for `0.y.z`:** PATCH = compatible fixes/docs only; MINOR = additions (and breaking
  changes only with an `UPGRADING.md` entry and, where feasible, a deprecation cycle); MAJOR
  reserved for `1.0.0` (API freeze). After `1.0.0`: standard SemVer.
- **Promise:** source compatibility within a MINOR line except documented deprecations;
  binary compatibility within a PATCH line; documented semantics do not change in a PATCH.
- **Deprecation policy:** `@Deprecated` + Javadoc `@deprecated` formula naming replacement
  and removal release; ≥1 MINOR retention; recorded in `UPGRADING.md`. **B9 adds none.** The
  tree's exactly one `@Deprecated` (`JUring.prepareCancel(long)` → `prepareCancelByUserData(long)`)
  is **pre-policy and grandfathered**: no removal version was announced; it is promised to
  remain at least until the first release **plus one MINOR**, and from the first release
  onward any removal requires a MINOR with an `UPGRADING.md` entry (design Amendment A1).
- **Explicit non-guarantees:** performance/benchmark numbers, internal implementation
  details, Tier 2 beyond the UPGRADING note, undocumented behaviour; JMX `ObjectName`
  details are covered only where `MetricsJmx` explicitly documents them (MXBean interfaces
  are Tier 1).

## 3. D2 — Javadoc

- **Every** public/protected type, constructor, method and field in both exported packages
  now has a main description, `@param` for every parameter, `@return` for non-void methods,
  and `@throws` where the contract documents one. Package summaries in
  `api/package-info.java` and `blackpanda/package-info.java`; module summary added to
  `module-info.java` (comment only).
- **Enforcement in the build:** `pom.xml` configures `maven-javadoc-plugin` 3.12.0 (no
  lifecycle binding, no new dependency) with `doclint=all`, `failOnWarnings=true`,
  `failOnError=true` (`-Xmaxwarns/-Xmaxerrs 5000`). Acceptance command:
  `mvn -o javadoc:javadoc`.
- **Result:** **597 → 0 warnings, 0 errors, BUILD SUCCESS** (`scratch/b9-lead/javadoc-acceptance.log`).
  The previously reported "100 warnings" baseline was javadoc's default `-Xmaxwarns` cap; the
  true uncapped baseline is 597 (design Amendment A2). For the record: on the pristine
  baseline tree that command **exits 1 (BUILD FAILURE)** — it has **2 doclint errors**
  (malformed HTML in `UringSparseIndex`, blackpanda) plus the capped 100 warnings; B9's tree
  exits 0 with 0 warnings/0 errors. (A2 corrected the count; the exit-code nuance is recorded
  here after independent verification flagged it — see §9 C1.)
- **Comments-only guarantee:** 41 pre-existing compiled classes differ under the default
  build **only in `LineNumberTable`/`SourceFile` debug data** (Javadoc lines shift source
  lines). With debug info off (`javac -g:none`, same compiler) the baseline and B9 trees
  produce **76/76 byte-identical class files** and no added/removed classes (`package-info.java`
  without annotations emits no class file). This is the normative proof method (Amendment A3).

## 4. D3 — runnable examples (and their seastar mapping)

| id | class | JUring features exercised | seastar use-case | assertion |
|---|---|---|---|---|
| E1 | `DirectFileExampleTest` | `registerFiles`, `registerBuffers`, `checkOut/checkInBuffer`, `prepareWriteFixed`/`prepareReadFixed`, one batched `submitAndWaitForBatchResult` | `open_file_dma` + `file::dma_read`/`dma_write` | 8 blocks write→read-back byte-identical; pool fully owned in flight, fully returned after |
| E2 | `StreamCopyExampleTest` | `JuringInputStream` `readAhead=2`, `JuringOutputStream` `writeBehind=2`/`flush`, option-bounded memory | `fstream` read-ahead/write-behind copy | copied file byte-identical; memory equals the documented formula and returns to 0 after close; length==position after flush |
| E3 | `ManyFilesExampleTest` | one `JUring` + 8 `JuringFile`s + 8 fixed-file slots + 16 cross-file fixed ops in one batch | many-open-`file` pattern / multi-file batching | 8 files × 2 offsets round-trip independently; pool capacity bound holds |
| E4 | `VirtualThreadsExampleTest` | `JUringBlocking(64, 1)` shared by 8 virtual threads | futures/reactor (ergonomic opposite) | every VT writes/reads its own record; final file is the concatenation; bounded poller budget |

They use only `@TempDir` (no benchmark device paths), make **no timing/throughput
assertions**, and each class Javadoc names the seastar mapping and the JUring↔seastar
asymmetry (notably: `JuringFile`'s blocking helpers are single-owner-ring, so E2 uses one ring
per direction and E3 issues the cross-file batch through raw `prepare*` + ring reaping — the
same single-owner contract, documented in the examples). `docs/examples.md` carries the table,
the run command (`mvn -o test -Dtest='*ExampleTest'`), per-example sections and mapping
paragraphs; README links to it.

## 5. D4 — upgrade notes

`docs/UPGRADING.md` records the status (0.1-SNAPSHOT, no releases), the versioning rules at a
glance with a link to the policy, the **additive** capability history B1–B6 (each with its
public API), "B9 adds no deprecations" plus the pre-policy `JUring.prepareCancel(long)`
entry, the honest migration statement (no released version exists, and the pre-rewrite API
was never released → no supported migration path), and a template for future entries.

## 6. README / discoverability

Additive only (+19 lines): a scope note that **QoS/fair-queuing/priorities between workload
classes are not in v1 by design** (with the "isolate classes with separate rings/shards or
OS-level I/O controls" guidance and a link to `docs/m4-decision.md`); an examples-intro
pointer; a "Runnable examples" section; a "Compatibility and upgrading" section linking the
two new docs. Existing snippets, benchmark tables and claims are untouched (publishing
decisions remain open — plan Step E).

## 7. Acceptance evidence (Lead, integrated tree)

| check | result | evidence |
|---|---|---|
| full suite | **405 tests / 0 failures / 0 errors / 1 skipped, BUILD SUCCESS** (401 baseline + 4 examples) | `scratch/b9-lead/` session log; suite run in frozen sync tree |
| javadoc | **0 warnings, 0 errors, BUILD SUCCESS** via `mvn -o javadoc:javadoc` **after a build** | `scratch/b9-lead/javadoc-acceptance.log` |
| javadoc on a clean checkout | `javadoc:javadoc` alone fails on a checkout with no `target/classes` ("aggregated report … has no module descriptor"); run `mvn -o test` (or `compile`) first. Reproduces on the pristine baseline too, so it is maven-javadoc-plugin's modular behaviour, not a B9 change; the documented acceptance sequence is `mvn -o test && mvn -o javadoc:javadoc` (see §9 C2) | `scratch/b9-verify/` |
| no behaviour change | **76/76 pre-existing classes byte-identical** (`javac -g:none` baseline vs B9); 0 added/removed class files | `scratch/b9-lead/no-behaviour-change-evidence.txt` |
| default-build caveat | 41 classes differ only in line-number/SourceFile debug data (expected; A3) | `scratch/b9-lead/default-debug-caveat.txt` |
| scope | only expected files: 36 api + 7 blackpanda (comments), `module-info` comment, 2 new `package-info.java`, pom javadoc block only, 4 new example tests, new docs, README; `git diff -- src/test/java` empty; no `bench/` change; no dependency change | `git status`/`git diff --stat` |
| freeze identity | hashes recorded before verification | `scratch/b9-lead/FREEZE.md` |

Independent verification (separate agent, private copy, adversarial) is recorded in
`scratch/b9-verify/VERDICT.md` and summarised in §9.

## 8. Deviations and disclosures

1. **No CI service exists in the repo** (no `.github/`, no CI config). Plan acceptance
   "examples run in CI" is satisfied as *"the examples are JUnit tests executed by the
   project's one build command (`mvn -o test`), which is what a CI would run"*; adding a
   hosted-CI workflow is out of scope (untestable here; hosted-runner io_uring support
   unverified) and remains a follow-up.
2. **Pre-1.0 promise:** with no published artifact, the policy is a forward-looking
   commitment, not a guarantee already in force (stated in the doc).
3. **Amendments during the session** (all docs-only, sha1-recorded in
   `scratch/b9-lead/FREEZE.txt`): A1 corrected a false "zero deprecations" assumption (one
   pre-policy `@Deprecated` exists); A2 corrected the baseline warning count (597), allowed
   the comment-only `module-info` doc, reassigned the two MXBean files (a work split), and
   fixed the cross-agent Maven lock path; A3 corrected the no-behaviour-change proof method
   (debug-info line tables).
4. **No `@since`/`@author` tags** (pre-1.0 policy); Javadoc describes existing behaviour and
   the B1–B6 contracts, no new claims.
5. **Tier-2 (blackpanda) performance claims left untouched:** the package contains
   pre-existing prose such as "sub-millisecond P99", "zero heap allocations". B9 neither adds
   nor removes them; they are outside the L0–L2 evidence and are not part of the policy's
   guarantees. A future pass may reword them; it is not a B9 regression.
6. **No performance/publishing statement added.** README benchmark tables and
   `BENCHMARK.md` are unchanged; publishing form and the M1 write matrix remain David's
   decisions (plan Steps A/E).
7. **Phase-1 examples are not benchmarks:** they are correctness demos; the suite's ~18 s
   runtime and `@TempDir` use are deliberate (no device dependency). They contain no
   timing/throughput *performance* assertions; the JUnit `@Timeout` annotations are hang
   guards only.
8. **Baseline javadoc command exit code:** the pristine-baseline `mvn -o javadoc:javadoc`
   exits 1 (2 doclint errors + 100 capped warnings); this was not visible in the session's
   earlier `-q | tail` invocation, which masked the exit code. The corrected baseline fact is
   recorded in §3 and C1 below; the B9 acceptance result (0/0, exit 0) is unaffected.
9. **Clean-checkout javadoc:** `mvn -o javadoc:javadoc` must be run after a build
   (`mvn -o test` or `compile`); on a truly clean checkout the plugin fails with "aggregated
   report … has no module descriptor". This reproduces on the pristine baseline and is
   plugin behaviour, not a B9 change; the acceptance sequence is documented accordingly (§7,
   C2 below).

## 9. Independent verification

**Verdict: PASS-WITH-CONCERNS** — independent verifier (`b9-verify`) on a private copy;
full record `scratch/b9-verify/VERDICT.md`. Nothing found invalidates B9 acceptance or the
B7 DEFER decision; the concerns are documentation-precision items and are addressed in this
report (C1–C3) or disclosed (C4).

Independently re-derived and **PASS**:

1. `mvn -o test` on a private copy: **405 / 0 failures / 0 errors / 1 skipped, BUILD SUCCESS**;
   pristine `b75515e` baseline independently **401 / 0 / 0 / 1** ⇒ 401 + 4 examples.
2. `mvn -o javadoc:javadoc` (after a build): **BUILD SUCCESS, 0 warnings, 0 errors**, 109
   pages; deprecated list contains only `prepareCancel`. Own compiler-tree audit of the
   exported API (46 types / 731 members): 0 hard failures; the 20 notes are
   `equals/hashCode/toString` overrides inheriting `Object` docs plus `MetricsJmx.Handle.close`
   (documented inheritance — doclint cannot verify those).
3. No-behaviour-change (A3 method): **76/76 pre-existing class files byte-identical** under
   `javac -g:none`; the 41 default-debug diffs are line-table-only (`javap`-normalised, 0 other
   diffs); 38 modified `src/main` sources + `module-info` are token-identical to `b75515e`;
   the 2 `package-info.java` are comment+package only; `src/test` has only the 4 added
   examples; `bench/` untouched; pom change is only the javadoc-plugin block.
4. Policy/UPGRADING accuracy: 36 + 7 public types and all 17 internal names match; exactly one
   pre-existing `@Deprecated`; version/exports/tags/remote/JMX/performance-exclusion wording
   accurate.
5. Examples: 4 genuine feature-exercising tests pass independently and **fail when one written
   byte is corrupted** (non-vacuous); docs mapping and README links resolve; no device-path
   dependencies.
6. B7 gate: rebuilt capability probe reproduces `-77/EBADFD` on regular files, the pipe
   `F_MORE` positive control and pbuf selection; independent G1/G2 A+B reps reproduce the
   gated per-op metrics within **≤0.6 %** (G1 A 11,190 cycles/op +0.31 %, 0.3281 syscalls/op
   −0.2 %, 324,782 IOPS −0.02 %); the analyzer rerun on copied raw data regenerates
   `runs.tsv` byte-identically and recomputes all paired LBs (0.8961/0.9237/1.1709,
   0.9777/0.9959/0.9921); E0/E1/E2 re-derived; **DEFER is the only data-supported outcome** and
   D1/D2 are honestly disclosed.
7. Adversarial enforcement: removing one `@param` flips `mvn -o javadoc:javadoc` to BUILD
   FAILURE (`no @param for readAhead`), and a corrupted example assertion fails — the gates are
   real.

Concerns and disposition:

- **C1 (baseline javadoc exit code) — fixed in this report.** The baseline command exits 1
  (2 doclint errors + 100 capped / 597 uncapped warnings), not 0 as previously implied; §3
  and §8 record the corrected fact. B9's result is exit 0 / 0 warnings / 0 errors.
- **C2 (clean-checkout javadoc) — documented.** `javadoc:javadoc` needs a prior build;
  reproduces on the pristine baseline and is plugin (modular aggregated report) behaviour, not
  a B9 change. §7/§8 document the `mvn -o test && mvn -o javadoc:javadoc` acceptance sequence.
- **C3 (wording precision) — corrected.** `docs/b7-decision.md` D1 now states
  `batches/op ≈ syscalls/op` with the exact values (0.3314 vs 0.3288; 0.2768 vs 0.2748);
  `docs/m4-decision.md` no longer overstates its grep parenthetical (pre-existing mentions of
  QoS/shares are plan/inventory deferral text and descriptions of seastar's own capabilities,
  not a JUring user need); `docs/examples.md` and README now say "no timing/throughput
  *performance* assertions; JUnit `@Timeout` is a hang guard only".
- **C4 (verifier B7 re-run outside a quiet window) — disclosed, not a gate datum.** The
  verifier's spot-check ran at load1 2.48–3.05 (gate window: 0.39–1.36) and saw −3/−5 % IOPS
  in the G1/B and G2 reps while cycles/op matched ≤0.6 %; it is verification evidence only,
  not part of the gate measurement.

**Could not be verified** (verifier's explicit list): pre-A1 `docs/b7-gate.md` sha
`deed4136…` content (untracked scratch file, no history); the exact Lead invocation behind the
earlier "exit 0" claim (now corrected as C1); two `FREEZE.md` convenience hashes
(docs-bundle/bench-content — method/handover edits; methods now documented and reproduced,
see FREEZE D5); pre-`b75515e` historical "no API removals"; behavioural generalisation to
other kernels/filesystems/JDKs; remote GitHub release/CI settings (offline); the Lead's final
`b9-report`/`SEASTAR-PLAN`/`HANDOVER` edits (out of the frozen set); and README/BENCHMARK
performance claims (untouched by B9, not re-measured).

**Addendum (task-7): PASS — the PASS-WITH-CONCERNS verdict stands.** The verifier re-checked
the post-verification documentation corrections against its verified snapshot: the frozen-set
changes are exactly the four declared docs (`README.md`, `docs/examples.md`,
`docs/b7-decision.md`, `docs/m4-decision.md`, hashes in FREEZE D5), docs-only, with
`src/main`/`src/test`/pom/`module-info`/`bench` unchanged; the corrected C1/C2/C3 wording is
accurate (including the baseline exit code and the clean-checkout `mvn -o test && mvn -o
javadoc:javadoc` sequence); §9 fairly represents its verdict and "could not verify" list; and
a fresh-copy spot-check still gives 405/0/0/1 and javadoc 0/0. Record:
`scratch/b9-verify/ADDENDUM.md`.

## 10. Session-level outcomes beyond B9

- **B7 deferred (no engine change):** `docs/b7-decision.md` records the frozen gate, the
  evidence (multishot is `-EBADFD` on regular files; seastar uses no provided buffers or
  multishot — its syscall advantage is reactor batching; provided-buffer selection is
  neutral-to-worse: +11.2 % cycles/op at G1, +1.0 % at G2), and the corrected G9 rationale.
- **B8 / M4 closed by documented decision:** `docs/m4-decision.md` records that QoS and
  intent stay out of v1 (plan §5's alternative acceptance path), why (locked scope D1,
  seastar QoS is reactor-layer scheduling, no real user requirement), and what v1 offers
  instead (separate rings/shards, OS-level I/O controls, caller backpressure).
- **Roadmap status:** B0–B6 delivered/verified; B7/B8 decided (deferred, documented);
  **B9 delivered** → the "basics" roadmap is complete. Remaining plan work is measurement/
  publishing, not library basics: M1 write matrix (Step A, needs David's budget decision),
  DS-M/host-root representativeness (Step D), publishing decisions (Step E).

## 11. Provenance

- Frozen contract: `docs/b9-design.md` sha1 `595b848891e3741977f23f262eccd75d5920cf0a`
  (+A1/A2/A3); `docs/b7-gate.md` sha1 `36a19a8c…` (+A1); decision docs
  `docs/b7-decision.md`, `docs/m4-decision.md`.
- Freeze record with content hashes + acceptance evidence: `scratch/b9-lead/FREEZE.md`;
  baseline/acceptance artifacts: `scratch/b9-lead/{baseline-tree,tree,baseline-nodbg,tree-nodbg,
  baseline-classes*.sha256,new-classes*.sha256,javadoc-acceptance.log,no-behaviour-change-evidence.txt}`.
- Helper agents' evidence: `scratch/b7-gate/` (gate measurement), `scratch/b9-verify/`
  (independent verification).
- Commits: feature/docs commit(s) recorded in `scratch/b9-lead/commit-ids.txt` after the
  verification passes; final handover in `docs/HANDOVER.md` §0.0 and plan version
  `SEASTAR-PLAN.md` v1.7.
