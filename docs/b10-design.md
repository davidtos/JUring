# B10 design — shared-ring completion multiplexing (`B10a`) + block-layer I/O priority (`B10b`)

**Status:** frozen and implemented 2026-11-16 (session 14). Supersedes the corresponding parts of
[`FINAL-PLAN.md`](../FINAL-PLAN.md) where the implementation found a constraint the plan did not
know about (documented in §4). No benchmark campaign is part of this design.

---

## 1. Scope

- **B10a:** several `JuringFile`-based handles (`JuringFile`, `JuringInputStream`,
  `JuringOutputStream`, `GroupCommit`) may share one `JUring` on its owner thread; every
  completion is routed to the handle that issued it, and the blocking helpers no longer reject
  sibling work.
- **B10b:** expose the `io_uring_sqe.ioprio` field as a small additive API (ring default, raw
  per-SQE setter, per-handle `JuringFile` setting, inherited by streams/group commit).
- **Out of scope:** any scheduler/fair-queuing/QoS implementation (M4 stays closed), cross-thread
  ring sharing, README/BENCHMARK rewrites, benchmark campaigns.

---

## 2. B10a — contract

### 2.1 Ring registry and dispatcher

- `JUring` keeps `JuringFile[] attachedFiles` + `attachedFileCount`. `JuringFile.open` attaches
  after a successful open; `JuringFile.close` detaches in a `finally` (also when the ring is
  already closed). The array grows only in `attach` (open-time allocation; never per request).
- Each `JuringFile` creates one `ResultConsumer` at construction:
  `ring.dispatchFileCompletion(result, this)`. Dispatch order:
  1. the reaping handle's ledger (single-handle fast path — identical work to the old
     `onCompletion`, plus one branch);
  2. the other attached handles (owner receives `DONE` + result, and decrements **its** counters);
  3. a completion matching no handle is a stray `TIMEOUT`/`LINK_TIMEOUT` (ignored, as before) or
     raw un-reaped ring work, which is counted as foreign on the reaping handle.
- Duplicate completions for a handle still in the ledger are swallowed (`true`), so they are not
  mis-counted as foreign.

### 2.2 Ownership guard

`JuringFile.checkRingOwnership` now compares `ring.inFlightCount()` with
`ring.attachedFilesInFlightCount()` (sum of the attached handles' ledger counts). Anything else on
the ring is raw un-reaped `JUring.prepare*` work, and the blocking helpers still throw
`IllegalStateException` for it — the B1/B3 safety contract survives.

### 2.3 Scoped cancellation and close

- `cancel(handle)` is unchanged logically: cancel by `user_data`, then reap the target and the
  control completion. Sibling completions reaped meanwhile are routed to their owner.
- `cancelAll`:
  - if `inFlight == ring.inFlightCount()` (no sibling has anything in flight) it keeps the
    O(1) ring-wide `cancelAny()` path — exact because the ring is exclusively owned right now;
  - otherwise it issues one `cancel-by-user_data` per own pending **target or standalone
    timer** (`ROLE_NORMAL`/`ROLE_TIMEOUT`); `ROLE_COMPANION` linked timeouts are not cancelled
    (a cancelled target disarms them and both completions fold into `await`). Then it drains its
    own ledger. Never cancels a sibling.
  - capacity rule: every control request needs a ledger slot of its own, so a completely full
    ledger (no free slot) throws `IllegalStateException` instead of silently dropping `await`
    handles; raise `withInFlightCapacity` or await completions.
- `close(timeout)`: the foreign-work test uses the attached-files count (raw work still abandons
  safely); own work is drained within the deadline, then per-handle cancels, then detach. A
  sibling's in-flight work is never cancelled or abandoned by this close.
- `drain()` waits for its own `inFlight == 0`; sibling completions reaped on the way are delivered,
  not dropped.

### 2.4 Lifecycle / threading

- Unchanged: one owner thread per `JUring`; all attached handles are created and used on that
  thread. No synchronization is added.
- Close order: streams → `GroupCommit` → `JuringFile` → ring. Closing a handle while siblings are
  active is legal. `ring.close()` does not need to detach anything; handles closed afterwards
  detach against the closed ring safely.

### 2.5 Performance / allocation

- The raw `JUring.prepare*`/reap path is untouched (M1 W1–W4 cannot move).
- The single-handle path adds one branch; sibling routing is an O(#attached) scan of ledgers (the
  per-handle ledger scan already existed). Registry memory is allocated at `open`, never per I/O.
- `JuringFileZeroAllocationTest#testTwoFileMultiplexedHotPathZeroAllocations` proves 0 heap bytes
  on the multiplexed hot path (registered files + fixed buffers, both await orders).

### 2.6 B3 §11.9 / F1 duplicate `user_data` — fixed for `JuringFile`, raw API unchanged

Non-fixed **reads** on the raw `JUring` API (`prepareRead(..., MemorySegment, ...)`,
`READ_EXT`) encode the **buffer address** as the SQE `user_data` so that
`Result.validBuffer()` can be reconstructed without a side table (B3 F1). Two concurrent raw
reads into the **same** `MemorySegment` therefore still share one handle; that raw-API rule —
do not reuse one buffer for concurrent non-fixed raw reads — is unchanged and documented in
[`docs/b3-design.md`](b3-design.md) §11.9.

**Fixed for `JuringFile` (the path B10a made reachable), for both its registered-file and
unregistered branches:** `JuringFile.prepareRead` now uses
an **internal `user_data` type** (`UserData.INTERNAL_READ_EXT_TYPE = 21`, unused by
`OperationType` and by the B4 shard's reserved `0xFF` wakeup reads) with a unique 56-bit
operation id as payload, instead of the buffer address, so:
- concurrent non-fixed reads into the same `MemorySegment` get **distinct handles**, are
  routed to their own ledgers, and both complete (no stranded entry, no JVM crash);
- the dispatcher decodes the internal type back to `OperationType.READ_EXT` with **no**
  `Result.validBuffer` (the caller already owns the buffer; `JuringFile` never needed it);
- the raw `READ_EXT` decode arm is byte-for-byte the pre-fix code, so the raw benchmark path
  gains no per-completion work beyond one more (unreachable for raw handles) switch arm.

Cost is confined to the `JuringFile` non-fixed read path: one `opSeq++` at prepare (no
allocation, no table, no extra syscall) and the internal-type decode at completion. A/B
measurement on the data NVMe (4 KiB random O_DIRECT reads, QD64, pinned CPU2, 9 paired
interleaved runs of 320k reads each): raw path paired `fixed/baseline` mean ratio **0.9931**
with 95 % CI **[0.9816, 1.0046]** (includes 1; raw decode is unchanged), `JuringFile`
non-fixed path median ratio **1.006** (5 pairs) — no measurable regression.

Verified by `JuringFileMultiplexingTest#concurrentNonFixedReadsIntoSameBufferGetDistinctHandles`
(unregistered) and `#concurrentNonFixedReadsOnRegisteredFilesGetDistinctHandles` (registered
handles, timed read included), by independent verifier probes (two handles / one handle /
8-read stress / raw-path invariance) and by the raw-path regression probe; the raw API keeps
its B3 F1 rule (do not reuse one buffer for concurrent non-fixed **raw** reads).

---

## 3. B10b — contract

### 3.1 Encoding (`IoPriority`, new public type)

- `(class << 13) | level`, matching `ioprio_set(2)`/`ionice` and the kernel's
  `IOPRIO_CLASS_SHIFT = 13`.
- Classes: `CLASS_NONE = 0`, `CLASS_RT = 1`, `CLASS_BE = 2`, `CLASS_IDLE = 3`; `NONE = 0`.
- `ioprio(class, level)` validates class `0..3`, level `0..7`; class `NONE` and `IDLE` require
  level `0` (the kernel rejects class-NONE-carrying-data with `-EINVAL`). `bestEffort(level)`,
  `realTime(level)`, `idle()` are the factories; `isValid(short)` mirrors the kernel rules.

### 3.2 Application surface

| Level | API | Applies to |
|---|---|---|
| ring default | `JUring.setDefaultIoprio(short)` / `defaultIoprio()` | read/write-family SQEs prepared while set |
| raw per-SQE | `JUring.setLastSqeIoprio(short)` | the SQE just prepared, read/write opcodes only |
| per-handle | `JuringFile.withIoPriority(short)` / `ioPriority()` | every read/write SQE the handle prepares, incl. timed-operation **targets** (not their `LINK_TIMEOUT` companion); inherited by streams and group commit. A **non-zero** value wins over the ring default. |

Precedence: **non-zero** per-handle value > ring default > thread priority. `0` is not a
priority value but "no override at this level": `withIoPriority(0)` removes the handle
override (a non-zero ring default then applies, otherwise the kernel inherits the submitting
thread's `ionice`/nice priority via `get_current_ioprio()`); `setLastSqeIoprio(0)` clears the
field on one raw SQE, overriding the ring default for that request.

### 3.3 Kernel facts (verified on Linux 7.0.12-201.fc44, the campaign host)

- `io_uring/rw.c` (`__io_prep_rw`) copies `sqe->ioprio` into `kiocb->ki_ioprio` and validates it
  with `ioprio_check_cap()`; the read/write/fixed/vectored family therefore honours it.
- **Non-read/write opcodes reject a non-zero `ioprio` with `-EINVAL` in this kernel** (verified
  for `NOP` and `FSYNC`; `STATX` follows the same rule). The ring default is therefore applied
  only in the read/write prepare path, and `setLastSqeIoprio` rejects other opcodes client-side.
- `RT` class needs `CAP_SYS_ADMIN` or `CAP_SYS_NICE` (`block/ioprio.c`); without it the request
  fails with `-EPERM`. `BE`/`IDLE` are unprivileged.
- Only `bfq` and `mq-deadline` act on `ioprio` (kernel docs); `mq-deadline` dispatches by class
  only (RT > BE > IDLE), `bfq` also weights the 8 levels. `none` and `kyber` ignore it.
- Host matrix: `sda`/`sdb` (SATA SSDs) run `[bfq]`, so the feature is observable there; all NVMe
  devices (including the benchmark device) run `[none]`, so `ioprio` is inert there — the
  effective enforcement path on that hardware is cgroup v2 (`io.cost.*`, `io.prio.class`) or
  separate rings/shards.

### 3.4 Docs honesty

No performance/effect claim is made: tests assert encoding, SQE wiring and kernel acceptance
(completion, or `-EPERM` for an unprivileged `RT` request). Javadoc and `docs/examples.md` state
that this is a hint, not QoS, and that `0` means "no override at that level" (a non-zero ring
default still applies to handles; a raw `setLastSqeIoprio(0)` clears the field so the thread's
`ionice` is inherited).

---

## 4. Deviations found during implementation (vs the reviewed plan)

1. **`ioprio` is not universally writable.** The reviewed plan said "apply the ring default to
   every SQE"; this kernel returns `-EINVAL` when `ioprio` is set on `NOP`/`FSYNC` (and other
   non-read/write opcodes). Fixed: ring default + `setLastSqeIoprio` are restricted to the
   read/write opcode family; per-handle priority is applied only after read/write prepares
   (including timed targets, before the linked-timeout companion is prepared). The API Javadoc
   and tests capture this.
2. **`RT` privilege test is capability-tolerant.** On an unprivileged host the request fails with
   `-EPERM`; the test accepts either success (when caps are available) or the documented
   `EPERM` failure.
3. **Shared-ring cancellation capacity.** `cancelAll` on a shared ring needs one ledger slot per
   pending request for its per-handle cancels; a completely full ledger throws a documented
   `IllegalStateException` rather than dropping `await` handles.
4. **Example update.** `StreamCopyExampleTest` now runs the reader and writer on one shared ring
   (it previously needed one ring per direction); `docs/examples.md` was updated accordingly.

---

## 5. Verification evidence

- `JuringFileMultiplexingTest` (10 tests): interleaved two-file awaits, interleaved read/write,
  stream copy on one ring, group commit with a sibling read, scoped `cancel`/`cancelAll`/`close`,
  and the surviving raw-work guard.
- `IoPriorityTest` (11 tests): encoding/validation, ring-default and raw-SQE wiring, per-handle
  coverage of prepare sites, timed target/companion split, `RT` capability behaviour, and
  end-to-end read/write/sync acceptance. No non-read/write opcode is tagged.
- `JuringFileZeroAllocationTest`: new two-file multiplexed hot-path test stays at exactly
  0 allocated bytes.
- `mvn -o clean test` (full suite, after the verification fixes) = **419 tests, 0 failures,
  0 errors, 1 skipped**; `mvn -o javadoc:javadoc` (doclint=all) = **BUILD SUCCESS, 0 warnings**.
  No benchmark campaign is run or re-opened.
- **Independent verification** (separate agent, own probes; reports in the gitignored
  `scratch/b10-verify/VERDICT.md` and `REVERDICT.md`):
  - first pass **FAIL** on one blocking safety defect (C1): the new priority setters did not
    `ensureOpen()`, so a call after `ring.close()` SIGSEGV'd on the unmapped SQE mapping.
    Fixed in commit `5590afe` (`ensureOpen()` in both setters; `close()` clears
    `lastSqeAddress`/`previousSqeAddress`; two regression tests).
  - re-verification at `5590afe` = **PASS-WITH-CONCERNS, no blocking finding**: C1
    independently reproduced as fixed (both settlement paths throw
    `IllegalStateException: JUring is closed`, addresses cleared, process survives, including a
    probe with a submitted timed read + timer in flight at close); C2–C8 confirmed fixed;
    additive-API check confirmed; no public regression.
  - two doc-only concerns remained and are corrected after re-verification: **C9** — only
    non-fixed *reads* share `user_data` (fixed above), and **C10** — the `0` wording, section
    order and test counts in this document/`docs/m4-decision.md`/`JuringFile` field comment.
  - **F1 follow-up then required two more rounds:** C8 was disclose-only, so the `JuringFile`
    non-fixed read path was fixed with an internal unique-id `user_data` type (`a47ed88`);
    re-verification then found the fix missed the **registered-file** branch (C11), which was
    fixed in `d4109fa`; re-verification also corrected two text nits (C12/C13). The final
    independent verification at `d4109fa` is **PASS** with no blocking or non-blocking
    correctness findings, 419 tests / 0 failures / 0 errors / 1 skipped, Javadoc 0 warnings,
    no public API change, and no measurable performance change on the raw benchmark path.
