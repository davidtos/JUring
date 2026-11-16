# JUring Foundation Plan v2 — Ring-Level Completion Multiplexing (`B10a`) + Block-Layer I/O Priority (`B10b`)

**Status:** reviewed and re-scoped 2026-11-16 (session 14) against HEAD `f7c9fbc`, and **cross-checked against the in-repo Seastar reference** (`SeaStarRef/` @ `04b1c04d`, §2b). This plan now contains **only the two capabilities requested**: **B10a** ring-level completion multiplexing and **B10b** `ioprio`. The previous draft's Part 3 (developer-focused `README.md` overhaul, `B10c`) is **removed from scope**; `README.md`/`BENCHMARK.md` stay exactly as published in Step E.

---

## 1. Scope

**In scope**
- **B10a** — multiple `JuringFile` / `JuringInputStream` / `JuringOutputStream` / `GroupCommit` handles multiplexed on one `JUring`, in one owner thread, without `IllegalStateException` from the blocking helpers.
- **B10b** — expose the kernel block-layer `ioprio` field as a small public API on `JUring` and `JuringFile` (and therefore streams / `GroupCommit`).

**Out of scope**
- README/BENCHMARK overhaul (`B10c`) — not requested; docs get only the Javadoc/contract updates the two features require.
- QoS / fair queuing / weights / `io_intent` (M4 stays closed by decision, [`docs/m4-decision.md`](docs/m4-decision.md)).
- Cross-thread ring sharing: the `JUring` contract stays **one owner thread per ring**; multiplexing is many handles, one thread.
- Ring multiplexing with **raw** un-reaped `JUring.prepare*` work — that guard must survive.
- New benchmark campaigns, JMH classes, or performance claims.

---

## 2. Verified current state (HEAD `f7c9fbc`)

| Fact | Evidence |
|---|---|
| Blocking helpers require exclusive ring ownership: `ring.inFlightCount() == this.inFlight` | [`JuringFile.checkRingOwnership`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L1615-L1622), called by `cancel` (`:866`), `cancelAll` (`:918`), `awaitInternal` (`:1076`), `drain` (`:1120`); the convenience `read`/`write` paths inherit it through `await` |
| Each file reaps with its own `this::onCompletion` consumer | [`JuringFile` field `completionConsumer`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L123); ledger match loop and stray/foreign classification in [`onCompletion`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L1725-L1766) |
| `close()` abandons its own requests when the ring carries more in-flight work than this handle | [`drainForClose`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L1486-L1505) |
| Bulk cancel uses ring-wide `prepareCancelAny()` — exact only under exclusive ownership | [`issueCancelsBatched`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L946-L1005), `:973` |
| The limitation is documented in the `JuringFile` contract, with a stale promise of "a later milestone (B3)" | [`JuringFile` Javadoc "Ring ownership"](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L29-L36) |
| No ring-side file registry or shared dispatcher exists | grep: no `attachFile` / `sharedFile…` in [`JUring.java`](src/main/java/com/davidvlijmincx/lio/api/JUring.java) |
| `ioprio` field exists and is writable but nothing sets it; `getSqe` only applies flags | [`Sqe.SQE_LAYOUT` / `setIoprio`](src/main/java/com/davidvlijmincx/lio/api/Sqe.java#L18), [`Sqe.java:94`](src/main/java/com/davidvlijmincx/lio/api/Sqe.java#L94), [`JUring.getSqe`](src/main/java/com/davidvlijmincx/lio/api/JUring.java#L2174-L2185) |
| `JuringFile` funnels every prepare through `getSqe`, with registered-file / raw-fd branches and timed operation pairs (target + `LINK_TIMEOUT` companion) | prepare sites in [`JuringFile.java`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L459-L535) (read/write), `:553-L612` (fsync/ftruncate/fallocate), `:718-L766` (timed read/write), `:1249` (statx), `:663` (standalone timer) |
| Streams and `GroupCommit` drive the same `JuringFile` ledger, so they inherit both features | [`JuringInputStream` await](src/main/java/com/davidvlijmincx/lio/api/JuringInputStream.java#L657), [`JuringOutputStream` await](src/main/java/com/davidvlijmincx/lio/api/JuringOutputStream.java#L608), [`GroupCommit` await](src/main/java/com/davidvlijmincx/lio/api/GroupCommit.java#L292) |
| Host I/O scheduler is `[none]` on `nvme1n1` (with `mq-deadline kyber bfq` available) | `/sys/block/nvme1n1/queue/scheduler` — critical for B10b claims, see §4.4 |

---

## 2b. Cross-check against the Seastar reference (`SeaStarRef/` @ `04b1c04d`)

*Added on review feedback: both features were checked against the in-repo Seastar checkout, the project's comparison baseline. Paths below are gitignored local checkout paths, quoted as evidence (not links).*

| Question | Seastar (evidence) | JUring today | Consequence for this plan |
|---|---|---|---|
| Many file handles on one ring | **Yes, by design.** One ring per reactor/shard (`SeaStarRef/src/core/reactor_backend.cc:1432`, `:1835`) and every `posix_file_impl` in that shard submits through the shard's `io_queue` (`SeaStarRef/src/core/file.cc:149`, `:601`). | **No** — `checkRingOwnership` rejects it ([`JuringFile.java:1615`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L1615-L1622)). | B10a closes a real architectural gap vs Seastar; the target semantics are not invented. |
| Completion routing with many files | Per-request owner identity: SQE `user_data` is a `kernel_completion*`; the CQE is dispatched straight to it (`SeaStarRef/src/core/reactor_backend.cc:1358`, `:1703`) — O(1), no scan. | Encoded `user_data` (8-bit type + 56-bit payload; fixed ops: 16-bit buffer index + 40-bit op id — [`UserData.java:8-25`](src/main/java/com/davidvlijmincx/lio/api/UserData.java#L8-L25)), then a per-file ledger scan of up to 1024 slots ([`JuringFile.java:1725`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L1725-L1766)). | v1 routing = per-handle consumer + attached-file scan (**Option A**: no user_data wire-format change, no raw-user break). Seastar's design is the end state: **Option B** (reserve owner bits in `user_data`) or **Option C** (ring-level handle→owner table) only if an M2 stream-copy profile shows the scan matters — not in v1. |
| Cancellation granularity | Per `io_intent`: cancel stops the requests pinned to that intent (`SeaStarRef/include/seastar/core/io_intent.hh:31-39`, `SeaStarRef/src/core/io_queue.cc:373-437`); no ring-wide "cancel everything". | `cancelAll` uses ring-wide `prepareCancelAny()` because the ring is exclusive ([`JuringFile.java:973`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L973)). | Plan keeps `cancelAny` only while the ring is exclusive, per-handle cancels otherwise — same granularity intent as Seastar. |
| Closing one handle | `posix_file_impl::close()` refcounts the fd and closes only after outstanding ops (`SeaStarRef/src/core/file.cc:420-432`); it never touches other files' requests. | `close()` abandons its own work when the ring carries more in-flight ([`JuringFile.java:1492`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L1492-L1505)). | Plan: drain/cancel own only, never siblings. The bounded deadline differs from Seastar's wait-for-refcount — document it. |
| Priority / QoS | **No `ioprio` usage at all** — 0 hits across `SeaStarRef/src/`, `SeaStarRef/include/`, `SeaStarRef/apps/`. Priority is user-space: per-class shares/token buckets in `io_queue` (`SeaStarRef/include/seastar/core/io_queue.hh:72-73`, `:129`) plus `io_intent` cancellation. | `ioprio` SQE field writable but never set ([`Sqe.java:18`](src/main/java/com/davidvlijmincx/lio/api/Sqe.java#L18), [`Sqe.java:94`](src/main/java/com/davidvlijmincx/lio/api/Sqe.java#L94)). | **B10b is not a Seastar-parity feature**: it is a lower-level block-layer hint that Seastar does not use. The comparable Seastar capability is M4 (`io_priority_class`/`io_queue`), which stays closed. Do not present B10b in the comparison matrix as "matches Seastar". |
| Threading model | One reactor thread owns the ring; files are shard-local. | One owner thread per ring (unchanged). | B10a adds no synchronization; consistent. |

**Direct answers to "did you cross-check with Seastar?"** — yes, after the original review:
1. **B10a** does match Seastar's file/ring architecture (one ring, many files), and the plan now records Seastar's O(1) per-request routing as the eventual end state while keeping the minimal-change Option A for v1.
2. **B10b has no Seastar precedent** — Seastar never touches `ioprio`; its priority mechanism is in-reactor `io_queue` scheduling (M4, closed). B10b must therefore be justified and documented as a raw-kernel passthrough, not as parity.

---

## 3. B10a — Ring-level completion multiplexing

### 3.1 Goal

Allow any number of `JuringFile`-based handles on one `JUring` in its owner thread to interleave operations, while every reaping path delivers each completion to the ledger of the handle that issued it. A completion that belongs to no attached handle (i.e. raw un-reaped `JUring.prepare*` work) must still raise `IllegalStateException`, preserving the existing B1/B3 safety contract.

### 3.2 Design (reviewed)

1. **Ring-side registry.** Add to `JUring`: `JuringFile[] attachedFiles`, `int attachedFileCount`, package-private `attachFile(JuringFile)` / `detachFile(JuringFile)` and `int attachedFilesInFlightCount()` (sum of each attached handle's ledger `inFlight`). The array grows only in `attachFile` (open-time allocation — never on the hot path). `JuringFile.open(...)` attaches after a successful open/registration; `close()` detaches after its own ledger is drained/cancelled/abandoned. If `open` fails mid-way, no attach must be left behind.
   - *Correction to v1:* the registry is **not** a zero-allocation list on the hot path; it is fixed/growing storage touched only at open/close. State this explicitly so nobody puts allocation-free constraints on `attach`.

2. **Per-handle shared consumer.** Each file creates **once** at construction (same place as today's `completionConsumer`):
   `sharedConsumer = result -> ring.dispatchFileCompletion(result, this)`.
   `ring.dispatchFileCompletion(result, caller)` then:
   - **fast path** — try `caller`'s ledger first: preserves the current single-file behaviour exactly (duplicate completions ignored, `DONE`/`PENDING` semantics, counters); when the caller is the only attached file this is the same work as today plus one branch;
   - **sibling path** — scan the other attached files and deliver to the owner's ledger (`onCompletion`), decrementing *that* file's `inFlight` and counters;
   - **stray timers** — `TIMEOUT`/`LINK_TIMEOUT` completions that match no ledger stay ignored, as today ([`onCompletion` `:1757-L1763`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L1757-L1763));
   - **raw foreign work** — anything else increments the **caller's** `foreignCompletions`, so the existing guard and error text still fire.
   - *Correction to v1:* `ring.sharedFileCompletionConsumer()` as drafted has no caller identity, which is needed both for the fast path and for foreign attribution. Use the per-file consumer above (allocated once, zero allocation per reap). *Cross-check (§2b):* Seastar routes each CQE straight to a per-request `io_completion*` in `user_data`; v1 deliberately takes Option A (registry scan) so the `user_data` wire format that raw `JUring.prepare*` users rely on does not change.

3. **Ownership guard.** `checkRingOwnership` compares `ring.inFlightCount()` with `ring.attachedFilesInFlightCount()` instead of `this.inFlight`; update the exception message to say "un-reaped raw ring work" rather than "another handle". Raw `prepareNop`/`prepare*` work remains guarded.

4. **Scoped `drain` / `cancelAll` / `close`.**
   - `drain()`: waits until `this.inFlight == 0` while routing sibling completions; unchanged otherwise.
   - `cancelAll()`: if `this.inFlight == ring.inFlightCount()` (exclusive ring) keep the O(1) `prepareCancelAny()` path; otherwise issue **per-handle** `prepareCancelByUserData(handle)` for each own `PENDING` target/timeout. Do **not** cancel `ROLE_COMPANION` handles — a cancelled target disarms its linked timeout and yields the folded `-ECANCELED` completion, as documented today. Reap everything own; never cancel a sibling.
   - `close()` / `drainForClose`: replace the `ringInFlight > inFlight → abandon` test with the attached-files count. Drain own work (routing siblings), then per-handle cancels within the documented `timeout + 250 ms` bound; only requests that still have no terminal completion at the hard deadline are abandoned. A sibling's in-flight work is never abandoned or cancelled by this close.
   - *Correction to v1:* the v1 text only said "use `prepareCancelByUserData` when siblings exist"; the companion/timeout pairing and the `cancelAll` control-slot accounting need to be spelled out (above), otherwise timed pairs leak completions.

5. **Lifecycle / threading contract (added).**
   - Unchanged: one owner thread per `JUring`; all attached handles are created and used on that thread. No synchronization is added.
   - Close order: close streams → `GroupCommit` → `JuringFile` handles, then the ring. Closing a handle while siblings are active is now legal; closing the ring first keeps today's "ring is gone → abandon" path.
   - `attach`/`detach` happen only on the owner thread; `ring.close()` leaves the registry moot.

6. **Docs required by the feature.** Update the [`JuringFile` "Ring ownership" Javadoc](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java#L29-L36) (remove the "later milestone (B3)" promise and the exclusive-ownership requirement, describe the new contract), the matching paragraphs in `JuringInputStream` / `JuringOutputStream` / `GroupCommit`, and add one example to [`docs/examples.md`](docs/examples.md): two files + input stream + output stream on a single ring. No public signature changes, so no `api-stability.md` change is required beyond the behaviour clarification.

### 3.3 Tests / acceptance (`JuringFileMultiplexingTest`, new)

- Two `JuringFile`s on one ring: interleaved read/write, awaited out of order; results correct, counters isolated per handle.
- `JuringInputStream` reading file A + `JuringOutputStream` writing file B on the same ring concurrently (inside one thread).
- `GroupCommit` batch on A while B has in-flight reads; batch commits with one sync, B unaffected.
- `cancel(handle)` on A while B has in-flight reads: A's cancel resolves deterministically, B's completions are untouched and awaitable.
- `cancelAll()` and `close(A)` while B has in-flight reads **and** linked timeouts: B completes normally; A's close is bounded; no leaked slots.
- Timed pairs across files: A's timed read vs B's plain read — target/timeout folding stays correct.
- Raw `ring.prepareNop()` (un-reaped) still triggers the `IllegalStateException` guard from `await`/`drain`/`cancel`.
- Zero-allocation: extend [`JuringFileZeroAllocationTest`](src/test/java/com/davidvlijmincx/lio/api/JuringFileZeroAllocationTest.java) so the dispatcher path allocates 0 bytes per completion (single-file and two-file cases).
- Full suite green + Javadoc 0 warnings (see §5).

### 3.4 Performance note

The raw `JUring.prepare*`/reap path is untouched (M1 W1–W4 read parity cannot move). Only the `JuringFile`/stream completion path gains one branch plus a small attached-file scan; single-file fast path is first. No perf campaign is required for this change; if a stream regression is ever suspected, the existing `bench/` stream cells can be used, but this plan does not gate on them.

---

## 4. B10b — Linux block-layer I/O priority (`ioprio`)

**Design rationale — why `ioprio` and not a scheduler.** JUring stays lean (D1) and does not model fairness, shares, or token buckets (M4 closed, [`docs/m4-decision.md`](docs/m4-decision.md)). The application running on top knows its I/O pattern (foreground vs compaction vs bulk copy) far better than a generic library can infer; the block layer already has a standard place to express that intent — the SQE `ioprio` class/level. So B10b **exposes the kernel hint and stops there**:

- **Expression, not enforcement.** `ioprio` is intent; ordering/enforcement is the kernel scheduler's job (BFQ / mq-deadline honour it; the host's `none` accepts but ignores it) or the OS's job (cgroup v2 `io.weight`/`io.max`, `ionice`, separate rings/shards). The library documents the split instead of reimplementing it.
- **No library-side scheduling state.** One 16-bit field per request: no queues, no fairness algorithm, no scheduler thread, no scheduling allocations; the hot-path cost is a single store.
- **Composable.** Ring-wide default, per-handle (`JuringFile.withIoPriority`, inherited by `JuringInputStream`/`JuringOutputStream`/`GroupCommit`), and per-request raw override (`ring.setLastSqeIoprio`) coexist; `0` always means "kernel default".
- **Known limits.** Coarse (4 classes; BFQ also weights the 8 levels, `mq-deadline` only the class), `RT` needs `CAP_SYS_ADMIN`/`CAP_SYS_NICE`, and there is no *cross-request* fairness inside JUring. Workloads needing guaranteed shares/isolation use OS controls or shard separation — that remains the documented answer.
- **Validation matches the claim.** Because the effect is scheduler-dependent, tests assert encoding, SQE wiring, and kernel acceptance — never a latency/throughput outcome (§4.5).

### 4.1 Goal

Let callers express a per-request block-layer priority via the `io_uring_sqe.ioprio` field that already exists in the layout ([`Sqe.java:18`](src/main/java/com/davidvlijmincx/lio/api/Sqe.java#L18), setter [`Sqe.setIoprio`](src/main/java/com/davidvlijmincx/lio/api/Sqe.java#L94)) — ring-wide default, per-SQE for raw users, and per-handle for `JuringFile`/streams/`GroupCommit` — with zero hot-path allocation.

### 4.2 Public API

- New `com.davidvlijmincx.lio.api.IoPriority` (public final utility class):
  - `short CLASS_NONE = 0`, `CLASS_RT = 1`, `CLASS_BE = 2`, `CLASS_IDLE = 3`;
  - `short ioprio(int prioClass, int level)` — validates class `0..3`, level `0..7` for RT/BE, IDLE requires level 0, and **class `NONE` must encode exactly `0`** (kernel `ioprio_check_cap()` rejects a class-NONE value carrying data; invalid classes ≥ 4 are rejected too); packs `(prioClass << 13) | level`;
  - `short NONE = 0`, `short realTime(int level)`, `short bestEffort(int level)`, `short idle()` (IDLE requires level 0 and ignores data, per Linux semantics).
- `JUring`: `short defaultIoprio()` / `setDefaultIoprio(short)` (default `0` = unset) and `setLastSqeIoprio(short)` (package/public, tags the SQE just prepared); `getSqe` applies `defaultIoprio` when non-zero.
- `JuringFile`: `withIoPriority(short)` / `ioPriority()`; the value is applied to every request the handle prepares.
- Streams and `GroupCommit` get it for free through their backing `JuringFile` — document that inheritance instead of adding duplicate methods.

### 4.3 Wiring (reviewed)

1. `getSqe` ([`JUring.java:2174`](src/main/java/com/davidvlijmincx/lio/api/JUring.java#L2174-L2185)) records the prepared SQE address and applies `defaultIoprio` when non-zero. This is the single funnel, so every opcode is covered, including internal control/timer SQEs (kernel ignores `ioprio` for non-block-I/O opcodes — document that rather than special-casing flags).
2. `JuringFile` applies its `ioPriority` **after each user-visible prepare**, through a single helper calling `ring.setLastSqeIoprio(ioPriority)`:
   - `prepareRead` / `prepareReadFixed` / `prepareWrite` / `prepareWriteFixed`, `prepareFsync`/`fdatasync`, `prepareFtruncate`, `prepareFallocate`, `prepareStatx`, `prepareTimeout` (harmless), and the **timed read/write targets**.
   - *Correction to v1:* for timed operations apply the priority to the **target** SQE immediately after preparing the target and **before** `prepareLinkTimeout`, so the companion `LINK_TIMEOUT` SQE is not the one tagged (and no target is missed when the helper is called once per operation).
3. Precedence: per-handle `JuringFile.withIoPriority(...)` wins over `ring.setDefaultIoprio(...)`; `setLastSqeIoprio(...)` is the raw escape hatch for individual `JUring.prepare*` calls. `0` always means "leave the kernel default".
4. Zero allocation: all of the above is a `short` field write; extend `JuringFileZeroAllocationTest`/`ZeroAllocationHotPathTest` expectations stay at 0 bytes.

### 4.4 Semantics caveats (must be documented, not glossed over)

- **Effect is a hint for the block layer, not QoS, and it is scheduler-dependent.** Kernel docs: ioprio support "is io scheduler dependent and currently supported by bfq and mq-deadline"; `none` and `kyber` do not use it. `mq-deadline` dispatches by **class** only (RT > BE > IDLE queues; BE levels are not weighted); **BFQ** honours class and the 8 levels. `io_uring` copies `sqe->ioprio` into `kiocb->ki_ioprio` and validates it with `ioprio_check_cap()`, so an invalid encoding or an unprivileged RT request fails the request itself.
- **Concrete device matrix on this host (verified, no root).** `sda` (Samsung SSD 850 PRO) and `sdb` (Samsung SSD 840 PRO) run `[bfq]` → **ioprio is effective there today** for `BE`/`IDLE` without privileges. `nvme0n1`, `nvme1n1` (**the benchmark device**) and `nvme2n1` run `[none]` → the value is accepted but inert; `bfq`/`mq-deadline` are selectable on them, but re-scheduling the benchmark device is a host change, out of scope. So the feature is demonstrable on this machine on the SATA SSDs, not on the NVMe under test.
- Verification must therefore assert encoding + SQE wiring + kernel acceptance, never a throughput effect. An *optional manual* look at `sda`/`sdb` under BFQ is possible but is not part of CI and must never become a benchmark claim. Seastar itself never sets `ioprio` (§2b), so this is an additive kernel passthrough, not a parity feature.
- `IOPRIO_CLASS_RT` requires `CAP_SYS_ADMIN` or `CAP_SYS_NICE` (verified in `block/ioprio.c`); `BE` and `IDLE` are unprivileged. Document that a denied RT request fails with the request's error, not a silent fallback.
- This does not reopen M4: no scheduler, no fair queuing, no weights; isolation guidance stays "separate rings/shards or OS-level I/O controls" such as cgroup v2 `io.weight`/`io.max` and `ionice` ([`docs/m4-decision.md`](docs/m4-decision.md)). This kernel also exposes cgroup v2 `io.cost.*` (iocost) and `io.prio.class` (default `no-change`) — the effective enforcement path on `none`-scheduled NVMe, where the per-request field alone does nothing.
- Relevant docs to touch: README/`docs/examples.md` note, `JuringFile` Javadoc, `docs/api-stability.md` (a note, as the API is additive and pre-1.0).

### 4.5 Tests / acceptance (`IoPriorityTest`, new)

- **Encoding/validation table:** `NONE`, `realTime(0..7)`, `bestEffort(0..7)`, `idle()`, and rejection of class ∉ `0..3`, level ∉ `0..7`, IDLE with non-zero level, **class NONE with non-zero level** (kernel returns `-EINVAL` for it), and class `IOPRIO_CLASS_INVALID` (4). BE/RT levels must stay expressible even though `mq-deadline` only uses the class — BFQ uses the level.
- **Kernel-acceptance check:** with a priority set, the request completes (not `-EINVAL`/`-EPERM`); with `RT` and no capability the failure is reported as the request's error rather than silently downgraded.
- **SQE-level assertions** (the real proof): a package-private `JUring` test hook (e.g. `lastPreparedSqeAddress()`) so the test can read the prepared SQE's `ioprio` field via `Sqe.getIoprio` and assert: default applies, `setLastSqeIoprio` overrides, `JuringFile.withIoPriority` applies on every prepare site, timed targets carry it while their `LINK_TIMEOUT` companions do not, and the other SQE fields are unchanged.
- **End-to-end:** reads/writes/fdatasync and a stream copy + `GroupCommit` batch with `bestEffort(0)` / `idle()` complete successfully (assert success only — see §4.4); no behaviour change with priority `0`.
- Zero-allocation counters remain 0 bytes.

---

## 5. Verification plan (both items)

1. **Unit/integration:** `mvn -o test` — full suite (currently 397 tests, 0 failures/0 errors, 1 skipped) plus the new `JuringFileMultiplexingTest` and `IoPriorityTest`; the zero-allocation tests must stay strict 0 bytes.
2. **Javadoc gate:** `mvn -o javadoc:javadoc` must end at **0 warnings** (`doclint=all`).
3. **Docs:** links resolve; `JuringFile`/streams/`GroupCommit` threading contract and the `ioprio` caveats documented.
4. **Independent verification pass** (repository convention): re-derive the tests/behaviour from the frozen design, check no public signature break, and record the verdict before calling B10a/B10b delivered.
5. **No regression economics:** do not run benchmark campaigns; M1/M2/M3 results are not re-opened (see §3.4).

---

## 6. Review record — what changed vs the previous draft

- **Part 3 (`B10c` README overhaul) removed** — user wants only ring multiplexing and `ioprio`; published docs stay as-is.
- **B10a dispatcher:** per-handle consumer `ring.dispatchFileCompletion(result, caller)` replaces the caller-less `sharedFileCompletionConsumer()`; fast path, stray-timer and raw-foreign attribution are now specified.
- **B10a cancel/close:** `prepareCancelAny()` only on an exclusive ring, per-handle cancels otherwise, companions handled by kernel disarm; `close()` never touches siblings; lifecycle/detach ordering added.
- **B10a registry:** explicit that attach allocates only at open (not a hot-path structure), and that the threading contract is unchanged (one owner thread).
- **B10b timed ops:** priority is written to the target SQE **before** the `LINK_TIMEOUT` companion is prepared (v1's "apply on the last prepared SQE" would have tagged the companion).
- **B10b honesty:** added the `[none]` scheduler caveat (effect not observable on this host), RT privilege caveat, and an SQE-level test hook as the real verification instead of a claimed performance effect.
- **B10b rationale recorded (review feedback):** why the lean library exposes the kernel's `ioprio` hint rather than an in-library scheduler — the app knows its I/O pattern, enforcement belongs to the kernel/OS, and M4 remains closed; tests validate encoding/wiring/acceptance, not outcomes.
- **Kernel + device cross-check (added):** verified `io_uring/rw.c` copies `sqe->ioprio` into `kiocb->ki_ioprio` and validates it with `ioprio_check_cap()`; `block/ioprio.c` shows `RT` needs `CAP_SYS_ADMIN`/`CAP_SYS_NICE`, `BE`/`IDLE` are unprivileged, class `NONE` carrying data is `-EINVAL`; kernel docs state only `bfq` and `mq-deadline` honour ioprio (`none`/`kyber` do not), with `mq-deadline` class-only and BFQ class+level. Device matrix recorded: this host's `sda`/`sdb` run `[bfq]` (feature demonstrable there, no root), the NVMe devices including the benchmark device run `[none]` (inert; cgroup iocost/`io.prio.class` is the enforcement path there). The API validation rule was tightened accordingly (class `NONE` must be `0`).
- **Coverage:** tests now include `GroupCommit`, cross-file timed pairs, foreign-raw-work guard, and zero-allocation dispatcher checks.
- **Seastar cross-check (§2b):** B10a matches Seastar's one-ring-many-files architecture; Seastar's O(1) per-request `user_data` routing is recorded as follow-up Option B/C, with Option A kept for v1. Seastar cancels per `io_intent` (supports the per-handle cancel scope) and closes files by refcount. Seastar has **zero** `ioprio` usage, so B10b is explicitly framed as an additive kernel passthrough, **not** a Seastar-parity feature.
