# B9 design contract — API stability, Javadoc, examples and upgrade notes

**Status:** frozen contract, session 10 (2026-11-15), written **before any B9 file is
edited**. Public-surface, acceptance and verification rules below are frozen; only appended
Lead amendments may change them (new sha1, no silent edits).

**Plan items covered:** `SEASTAR-PLAN.md` §4 **B9** (API stability, docs, examples — gap G7;
the only remaining *unconditional* roadmap item after B0–B6) and `docs/juring-inventory.md`
G7. B7 (provided-buffer ring + multishot) is handled by its own gate (`docs/b7-gate.md`);
B8/QoS/M4 is handled by a separate documented decision. B9 deliberately does **not** touch
performance numbers, benchmark claims, or publishing decisions (plan Step E, David's call).

## 0. Scope and non-goals

**In scope:** a written compatibility/versioning policy for the public API; complete Javadoc
on the exported API with doclint enforcement in the build; four runnable examples mapped to
seastar use-cases; upgrade notes; the README/doc links that make these discoverable.

**Out of scope:** any behaviour, signature, default, performance or platform change; module
exports changes; new dependencies; benchmark-publishing decisions; a hosted CI service
(none exists in this repo — see §7); B7/B8 implementation; Javadoc for package-private
internal types; `@since` tags (not required pre-1.0).

## 1. Baseline facts (measured 2026-11-15, before B9)

| fact | value | how measured |
|---|---|---|
| base commit | `b75515e` (branch `kafka-engine`), clean tree | `git log -1` |
| authoritative suite | **401 tests / 0 failures / 0 errors / 1 skipped, BUILD SUCCESS** | `mvn -o test` (17.7 s) |
| javadoc (doclint=all) | **exits 0 with 100 warnings** (missing main descriptions, `@param`, `@throws`, etc.); main hot spots: `JuringFile` 36, `IoShard` 16, `JuringInputStream` 10, `ShardContext` 8, `GroupCommit` 7, `FileDescriptor` 7 | `mvn -o javadoc:javadoc -Ddoclint=all` |
| pom javadoc config | none (`maven-javadoc-plugin` not configured; plugin 3.12.0 available offline) | `pom.xml` §build |
| module exports | `com.davidvlijmincx.lio.api` **and** `com.davidvlijmincx.lio.blackpanda` | `src/main/java/module-info.java` |
| version | `0.1-SNAPSHOT` (no published artifact; `origin` = github.com/davidtos/JUring) | `pom.xml`, `git remote -v` |
| CI | **no CI configuration in the repo** (no `.github/`, no CI files) | `ls -a`, repo map |
| README | 5 example snippets (direct read, direct write, fused pipeline, buffered I/O, virtual threads); no runnable example suite, no compatibility policy | `README.md` |
| public API surface | 36 public types in `lio.api` (+ 7 in `lio.blackpanda`); `JuringException extends IOException` | source grep |

`scratch/b9-lead/FREEZE.txt` records the frozen sha1 of this file and the exact baseline
commands/logs.

## 2. Frozen deliverable D1 — compatibility policy (`docs/api-stability.md`)

The policy must state all of the following, accurately to the code (verifier checks each):

1. **Status/version:** project version `0.1-SNAPSHOT`; no public release has been published
   yet, so the policy is forward-looking and takes effect at the first release. Name the
   repository (`github.com/davidtos/JUring`) as the place to report compatibility issues.
2. **API surface definition:** the public API is the module `JUring`'s **exported packages**:
   - **Tier 1 — supported:** `com.davidvlijmincx.lio.api` (all public/protected types and
     their public/protected members).
   - **Tier 2 — experimental:** `com.davidvlijmincx.lio.blackpanda` (publicly visible, but no
     compatibility guarantee; may change or be removed in a MINOR release with an
     `UPGRADING.md` note).
   - **Internal:** package-private types/members (e.g. `Sqe`, `Cqe`, `UserData`,
     `IovecBlock`, `ShardBody`, `ShardLoopContext`, `MpscQueue`, the `*_offsets`/`_params`
     native-layout helpers) carry **no** guarantee.
3. **Versioning rules (SemVer):** while `0.y.z`: PATCH = compatible fixes/docs only;
   MINOR = additions and, if unavoidable, breaking changes **only with** an `UPGRADING.md`
   entry and (where feasible) a deprecation cycle; MAJOR is reserved for `1.0.0` (API
   freeze). After `1.0.0`, standard SemVer (breaking changes only in MAJOR). Additions are
   non-breaking; behavioural changes to documented semantics are MINOR.
4. **Compatibility promise:** source compatibility within a MINOR line except documented
   deprecations; binary compatibility within a PATCH line; existing documented
   semantics/guarantees do not change in a PATCH. State explicitly that performance
   numbers, benchmark results and internal implementation details are **not** covered.
5. **Deprecation policy:** use `@Deprecated` plus a Javadoc `@deprecated` paragraph naming
   the replacement and the removal version; keep a deprecated API for at least one MINOR
   release before removal; record each deprecation/removal in `UPGRADING.md`. (B9 adds no
   deprecations — verify the current tree has none.)
6. **Ownership/threading note:** thread-safety and ownership rules are part of the
   documented contract, stated per type in Javadoc (cross-link `JUring`, `JuringFile`,
   `IoShard`/`RingGroup`, `JUringBlocking`); the policy does not repeat them.
7. **JMX note:** the MXBean interfaces (`IoShardMetricsMXBean`, `RingGroupMetricsMXBean`)
   are Tier 1; JMX `ObjectName` strings/registration details are not part of the promise
   unless explicitly documented.

## 3. Frozen deliverable D2 — Javadoc contract

1. Every public/protected type, constructor, method and field in the **exported packages**
   (`lio.api`, `lio.blackpanda`) has:
   - a main description (first sentence, ends with a period; says what it does);
   - `@param` for every parameter;
   - `@return` for every non-void method (or `{@inheritDoc}` where appropriate);
   - `@throws` for each documented exception (checked and deliberately documented unchecked
     ones, e.g. `IllegalStateException` on misuse where the class documents it).
2. **Package docs:** new `package-info.java` for `com.davidvlijmincx.lio.api` and
   `com.davidvlijmincx.lio.blackpanda` (package purpose, tier, entry-point types).
3. **Build enforcement:** configure `maven-javadoc-plugin` in `pom.xml` (no lifecycle
   binding, no new dependency) with `doclint=all`, `failOnWarnings=true`,
   `failOnError=true`, `quiet=false`? (final parameter set: plugin 3.12.0 abilities). The
   acceptance command is `mvn -o javadoc:javadoc` → **0 warnings, 0 errors, BUILD SUCCESS**
   (baseline: 100 warnings).
4. **No behaviour change:** `src/main` edits are **comments only** plus the new
   `package-info.java` files. No signature/default/annotation/exception behaviour changes;
   `module-info.java` unchanged. Verifier proves this by byte-comparing compiled classes of
   all pre-existing types against the baseline build (§6.3).
5. Style: `{@code ...}` for identifiers/code, `{@link Type}` for cross-links, no `@author`,
   no HTML beyond lists/paragraphs, American spelling, consistent third-person present
   ("Returns ...", "Opens ..."). Document preconditions, ownership, and error modes where
   the design contracts (B1–B6 designs) make them normative.

## 4. Frozen deliverable D3 — runnable examples (`src/test/java/com/davidvlijmincx/lio/examples/`)

Four example classes, each a JUnit test class named `*ExampleTest` so `mvn -o test` runs it
(the repo has no CI service; the build command is the CI command — disclosure §7). Each
class carries class-level Javadoc naming its seastar mapping and the JUring↔seastar
asymmetry. No dependence on `/mnt/TestData` or `nvme-data/` (use JUnit `@TempDir`;
`O_DIRECT` only if it works on the temp path — existing suite shows direct I/O tests use
`@TempDir`, follow that pattern). Deterministic: **no throughput/timing assertions**; assert
data correctness, bounded memory/capacity invariants and clean close.

| id | class | JUring features exercised | seastar use-case it maps to | acceptance assertion |
|---|---|---|---|---|
| E1 | `DirectFileExampleTest` | registered file + fixed buffers + batched submit (raw `JUring` and/or `JuringFile` path) | `open_file_dma` + `file::dma_read/dma_write` (direct I/O) | write then read back byte-identical; correct buffer check-out/check-in accounting |
| E2 | `StreamCopyExampleTest` | `JuringInputStream` (`readAhead`, pooled/fixed buffers) + `JuringOutputStream` (write-behind, `flush`) | seastar `fstream` read-ahead/write-behind stream copy | copied file byte-identical to source; memory bounded by options |
| E3 | `ManyFilesExampleTest` | one `JUring` driving many `JuringFile`s / fds in flight (the "many files" pattern) | seastar many-open-`file` pattern / io_uring multi-file batching | N≥8 files round-trip independently, per-file offsets correct |
| E4 | `VirtualThreadsExampleTest` | `JUringBlocking` from ≥8 virtual threads | seastar futures + reactor (asymmetry: VT blocking API vs futures runtime) | every VT completes with correct data; bounded platform-thread budget documented |

Plus **`docs/examples.md`**: the table above with links to each test class, exact run command
(`mvn -o test -Dtest='*ExampleTest'`), what each example demonstrates, and the seastar
mapping/differences paragraph. README gets a short "Runnable examples" pointer (existing
snippets and benchmark tables stay untouched).

## 5. Frozen deliverable D4 — upgrade notes (`docs/UPGRADING.md`)

Must contain: status (0.1-SNAPSHOT, no releases yet), a summary table of the versioning rules
with a link to `api-stability.md`, the current additive history summarised by capability
(B1 `JuringFile`; B2 streams; B3 lifecycle/timeouts; B4 `IoShard`/`RingGroup`; B5 metrics/JMX;
B6 durability/group commit — each additive, no API removed), "deprecations: none", and the
honest migration statement: **no released version exists, so there is nothing to migrate
from yet**; the pre-rewrite API (removed sockets / older primitives) was never released and
gets no supported migration path. Include a template for future entries (before/after
snippet, replacement, removal version). README links to it.

## 6. Frozen acceptance / definition of done (Lead + independent verifier)

Run on the integrated tree (Lead) and independently (verifier, private copy):

1. **Suite:** `mvn -o test` → all tests pass; count = **401 baseline + 4 example tests**
   (+ any tests the verifier adds? no) → expected 405 tests, 0 failures/errors, 1 skipped;
   `git diff -- src/test/java` is empty for all pre-existing test files (examples are new).
2. **Javadoc:** `mvn -o javadoc:javadoc` → BUILD SUCCESS with **0 warnings, 0 errors**
   (independent run with the pom config, not just `-Ddoclint=all` on the CLI).
3. **No behaviour change:** compile the baseline tree (`git archive b75515e`) and the new
   tree; for every pre-existing public class, the `.class` file is **byte-identical**
   (comments do not compile); only new `package-info.class`/module unchanged. `module-info.java`
   and `pom.xml` dependency/compiler/surefire configuration unchanged (javadoc plugin
   addition is the only pom change).
4. **Policy accuracy:** each claim in `api-stability.md` verified against the tree
   (version string, exported packages, internal-type list, zero deprecations, no release
   artifacts); no overpromising (performance/benchmarks explicitly excluded).
5. **Examples:** executed by `mvn -o test`; verifier reads each and confirms it exercises the
   claimed JUring features (not a stub) and asserts correctness; `docs/examples.md` mapping
   matches the classes; README pointer resolves.
6. **Upgrade notes:** consistent with `api-stability.md`; versioning table matches; the
   "no releases yet" claim is true (`git tag` empty / no artifacts).
7. **Scope audit:** only expected files changed — `src/main` comments + 2 `package-info.java`,
   `pom.xml` javadoc plugin block, new `src/test/java/.../examples/`, new `docs/*.md`,
   README additions. No `bench/` changes, no `src/main` code-behaviour changes, no new
   dependencies.

## 7. Disclosures / deviations to carry

1. **No CI service exists** in the repo (`ls -a` shows no `.github/`, no CI config). Plan B9's
   "examples run in CI" is therefore satisfied as **"examples are JUnit tests, executed by
   the project's one build command (`mvn -o test`), which is exactly what a CI would run"**.
   Adding a hosted-CI workflow is out of scope (untestable here; hosted runners' io_uring
   support unverified) and is listed as a follow-up, not delivered.
2. **Pre-1.0 promise:** with no published artifact, the compatibility policy is a
   forward-looking commitment, not a guarantee already in force; stated as such.
3. **Javadoc completeness vs. behaviour:** B9 documents existing behaviour; where the
   implementation and a B*-design contract disagree, the design contract wins for wording
   and the disagreement is reported to the Lead rather than hidden.
4. **No `@since` tags** (pre-1.0), and no `@author` tags.
5. **Performance/publishing claims untouched:** README benchmark tables and `BENCHMARK.md`
   remain as-is (Step E pending); B9 adds no performance statement.
6. **B7 gate and B8/M4 decision are separate**: B9 does not implement or claim them.

## 8. Write scopes and parallel plan (advisory; exactly one owner per file)

| owner | write scope | deliverables |
|---|---|---|
| **b9-javadoc-core** | `pom.xml`; `api/package-info.java`, `blackpanda/package-info.java`; Javadoc in `JUring`, `JUringBlocking`, `FileDescriptor`, `Result`, `ResultConsumer`, `CompletionHandler`, `OperationType`, `LinuxOpenOptions`, `IoUringOptions`, `SqeOptions`, `JuringException`, `DurabilityMode`, `FadviseAdvice`, `LatencyHistogram`, `LatencySnapshot`, `MetricsJmx`, `RingMetrics`, `ShardMetrics`, `GroupMetrics`, `ShardStats`, `IoShardMetricsMXBean`, `RingGroupMetricsMXBean`, plus all 7 `blackpanda` public types | D2 + pom enforcement |
| **b9-javadoc-file** | Javadoc in `JuringFile`, `JuringInputStream`, `JuringOutputStream`, `InputStreamOptions`, `OutputStreamOptions`, `GroupCommit`, `GroupCommitOptions`, `GroupCommitStats`, `IoShard`, `RingGroup`, `ShardContext`, `ShardOptions`, `ShardTask`, `IoPolicy` | D2 remainder |
| **b9-docs** | new `src/test/java/com/davidvlijmincx/lio/examples/`; `docs/api-stability.md`; `docs/UPGRADING.md`; `docs/examples.md`; `README.md` | D1, D3, D4 |
| **b9-verify** | `scratch/b9-verify/` only | independent verification (§6) |
| **Lead** | this file, `docs/b7-gate.md`, decision/report docs, `SEASTAR-PLAN.md`, `docs/HANDOVER.md`, git commits | freeze, integration, report |

Rules: no teammate commits; no edits outside the scope above; one owner per file; `mvn`
invocations serialize via `flock -w 900 /tmp/juring-b9-build.lock mvn -o ...`; javadoc runs
while the B7 gate measurement is in progress are coordinated by the Lead (quiet window).
Joint Javadoc style: descriptions third-person present; parameters/returns/throws mandatory
on public API; no changes to package-private types.

## 9. Amendments

(none yet; appended by the Lead with a new sha1, never silently edited)

### A1 — 2026-11-15 (session 10, pre-implementation; corrects the "zero deprecations" assumption)

**Trigger:** implementation review (b9-docs) found that §1/§2.5/§5 and D1 wording assume the
tree has **no** deprecations. Fact check at `b75515e`: the tree has **exactly one**
pre-existing deprecation — `JUring.prepareCancel(long)` at
`src/main/java/com/davidvlijmincx/lio/api/JUring.java:788` (`@Deprecated`, with an
`@deprecated` Javadoc paragraph naming the replacement
`prepareCancelByUserData(long)`; no `since`/`forRemoval`, no removal version; it predates
B9 and was not introduced by this session). No other `@Deprecated` exists in `src/main`.

**Correction (frozen wording rules):**
1. `docs/api-stability.md` must state: **"B9 adds no deprecations."** The tree carries one
   **pre-policy** deprecation, `JUring.prepareCancel(long)` → replacement
   `JUring.prepareCancelByUserData(long)`, which is **grandfathered**: it predates the policy
   and announced no removal version, and it is promised to remain at least until the first
   release **plus one MINOR**; from the first release onward any removal of it requires a
   MINOR release with an `UPGRADING.md` entry.
2. `docs/UPGRADING.md` lists it under a "pre-existing deprecation (pre-policy)" heading, not
   as a B9 deprecation.
3. The method's existing Javadoc may be completed per D2 but **must not invent** a `@since`
   or removal version; if a removal sentence is added it must be exactly "No removal version
   has been announced." (and nothing more specific).
4. §6.4 acceptance item "zero deprecations" is replaced by: **deprecation inventory = exactly
   one pre-existing `@Deprecated` (`JUring.prepareCancel(long)`); B9 adds none; both docs
   describe it accurately; no new `@Deprecated` in the diff.**
5. All other terms of D1–D4 are unchanged. The baseline-facts table's "no deprecations" claim
   is corrected by this amendment (the javadoc baseline is unaffected).

**New frozen sha1** of this file after A1 is recorded in `scratch/b9-lead/FREEZE.txt`; no
other design change is implied.

### A2 — 2026-11-15 (session 10, mid-implementation; baseline count, module-info comment, scope/lock corrections)

**Trigger:** implementation flags raised by b9-javadoc-file after its files were complete
(b9-javadoc-core still in progress). None of these changes a deliverable, a policy rule or
the acceptance target; they correct measurable/factual assumptions and reassign two files.

1. **Baseline warning count corrected (fact).** §1's "100 warnings" is the javadoc default
   `-Xmaxwarns` cap, not the true count. On the pristine pre-B9 baseline (`git archive
   b75515e`, with `-Xmaxwarns=10000`) the true baseline is **597 doclint=all warnings**.
   The B9 acceptance target is unchanged (**0 warnings**); the report and verifier must use
   the 597 baseline (re-derivable from the pristine tree) and note the cap.
2. **`module-info.java` may receive a comment-only module Javadoc comment.** Acceptance
   requires 0 warnings overall, and `module-info.java:1: warning: no comment` is one of them.
   The `module-info.java` file may therefore get a **module-level Javadoc comment** (a
   comment above the `module` declaration; no `requires`/`exports`/directive/code change;
   `module-info.class` bytecode must remain byte-identical — comments do not compile).
   §3.4 "module-info.java unchanged" is amended accordingly. Owner: b9-javadoc-core.
3. **Write-scope reassignment (parallelisation).** `IoShardMetricsMXBean.java` and
   `RingGroupMetricsMXBean.java` move from **b9-javadoc-core** to **b9-javadoc-file** in §8
   (66 remaining warnings at the time of this amendment). b9-javadoc-core must not edit them;
   b9-javadoc-file's scope in task-6 covers only those two files. This is a work split, not a
   contract change.
4. **Cross-agent build lock path corrected.** `/tmp` is ephemeral per bash invocation in this
   sandbox, so `/tmp/juring-b9-build.lock` cannot serialise Maven across agents. The lock for
   all B9 Maven runs (including integrated acceptance) is the workspace-scoped
   **`scratch/.b9-build.lock`**: `flock -w 900 scratch/.b9-build.lock mvn -o ...`.
5. Everything else (D1–D4 wording, Javadoc style rules, examples, policy, §6 acceptance
   items other than the corrected baseline count, §7 disclosures) is unchanged.

**New frozen sha1** after A2 recorded in `scratch/b9-lead/FREEZE.txt`.

### A3 — 2026-11-15 (session 10, integration phase; no-behaviour-change proof method corrected)

**Trigger:** Lead integration test of §6.3. Compiling the baseline tree (`git archive
b75515e`) and the B9 tree with the normal Maven build and comparing `target/classes` hashes
shows **41 changed class files even though every `src/main` change is comment-only**. Root
cause: `javac` embeds debug info by default (`LineNumberTable`/`SourceFile` etc.), and adding
Javadoc lines shifts the recorded source line numbers, so the class bytes change while the
code is identical. The statement in §6.3 "comments do not compile" is true for code semantics
but wrong as a byte-identity method under default debug info. Also, a `package-info.java`
with only a Javadoc comment and no annotations generates **no class file**, so §6.3's "only
new `package-info.class` files may be added" is wrong.

**Correction (normative verification method, replaces §6.3's byte-identity procedure):**
1. Build the baseline (`git archive b75515e`) and the B9 tree with **debug information
   disabled** and the **same compiler**, e.g. `javac -g:none -d out @files` over all
   `src/main/java/**/*.java`. Under that build, **all pre-existing `.class` files must be
   byte-identical** (including `module-info.class`); new `package-info.java` files add **no**
   class files. (Measured by the Lead on 2026-11-15: 76/76 classes byte-identical; under
   default debug info 41 classes differ only in line-number tables, as explained above.)
2. Corroboration required: a comment-stripped/normalized token comparison of every modified
   `src/main` file against `git show HEAD:<file>` shows no code-token differences, and
   `git diff -- src/main/java` contains only comment/blank-line changes plus the two new
   `package-info.java` files and the comment-only `module-info.java` module doc.
3. Everything else in §6 (suite, javadoc 0-warning acceptance, policy accuracy, examples,
   scope audit) is unchanged.
4. The verifier's §6.3 check must use method (1)+(2) and must not report false "changed class"
   findings from default-debug-info line-number tables.

**New frozen sha1** after A3 recorded in `scratch/b9-lead/FREEZE.txt`.
