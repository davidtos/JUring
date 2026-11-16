# B3 design contract — request lifecycle hardening (timeouts, cancellation, bounded shutdown)

**Status:** frozen contract, written before/with implementation (2026-11-14, session 5). The
semantics marked **verified** were measured on the live kernel with
`scratch/b3/Probe.java` / `scratch/b3/Probe2.java` (`scratch/b3/probe-output.txt`,
`scratch/b3/probe2-output.txt`), not assumed.
**Implements:** `SEASTAR-PLAN.md` §4 **B3** (gaps G4, G6); builds on `docs/b1-design.md`
(`JuringFile`, engine opcodes, bounded close) and `docs/b2-design.md` (streams).
**Acceptance (from the plan):** timeout ops incl. linked timeouts; defined cancellation
guarantees (every cancelled request gets a completion); queue-full/CQ-overflow contract;
bounded shutdown (drain or cancel, never hang); `user_data`/`Result` safety audit; error
taxonomy. Stress: cancel 50 % of in-flight + close with 10k in flight — no leaks, no hangs,
deterministic completions; documented semantics.

*Post-freeze note (2026-11-14): items 9–12 were added after the frozen implementation was
independently verified — they are documented limitations/findings, not contract changes
(see §11 and `scratch/b3-verify/VERDICT.md`).*

This document is the coordination contract between the parallel workstreams. Public
signatures below are frozen unless the Lead revises this file. Internal package-private
helpers and algorithms are owned by the implementation and are not frozen, except where this
document fixes semantics.

## 0. Scope and non-goals

**Delivered by B3**

1. **Timeout ops.** Standalone `IORING_OP_TIMEOUT` (opcode 11, already present from B1) and
   new `IORING_OP_LINK_TIMEOUT` (opcode 15) with runtime probe (`supportsLinkTimeout()`),
   same-submit-batch/link validation, and the exact CQE sequences/races frozen in §2.
2. **High-level timed operations** on `JuringFile`: `prepareTimeout(ms)`,
   `prepareRead(..., timeoutMillis)` / `prepareWrite(..., timeoutMillis)` linked pairs and
   `read(..., timeoutMillis)` / `write(..., timeoutMillis)` convenience methods that return
   or throw deterministically; plus the documented difference between caller-side
   `await(handle, timeoutMillis)` and a kernel timeout (§2.6).
3. **Cancellation contract.** `JUring.prepareCancelByUserData` (real semantics; the B1
   `prepareCancel(long targetOpId)` is kept as a deprecated correct alias), bulk cancel by
   fd (`prepareCancelFile`, plain and fixed-file) and ring-wide `prepareCancelAny`; a
   deterministic `JuringFile.cancel(handle)` status enum, `cancelAll()`, a
   `cancelledOperations()` counter, control-request accounting, and the race table in §3.
4. **Bounded shutdown.** `JuringFile.close(timeoutMillis)` and
   `JUring.close(timeoutMillis)` with deadlines, cancel-all, reaping of cancelled
   completions before fd/mmap teardown, a counted force-close path, idempotency, and the
   preserved B1 foreign-in-flight-work rule (§4).
5. **Queue-full / CQ-overflow contract** (§5), **`user_data`/`Result` safety audit** (§6),
   **error taxonomy** (§7).
6. **Lifecycle hardening found by the independent verifier:** every public `JUring`
   `prepare*`/`submit*`/`wait*`/`reap*`/registration path checks `closed` before touching
   the native SQ/CQ rings, so use-after-close throws `IllegalStateException` instead of
   SIGSEGV (§8).
7. B3 tests and standalone stress evidence (`scratch/b3/STATUS.md`), keeping the existing
   suite green.

**Non-goals (later milestones own them)**

- `JUringBlocking` bounded shutdown rework (its poller/worker shutdown remains unbounded in
  the pathological "cancellation completion never arrives" case; documented residual hazard
  in §4.5). A control-command deadline was judged riskier than the documentation for B3.
- A ring-level completion dispatcher / safe sharing of a ring between a `JuringFile` and
  foreign in-flight work (B4); B3 keeps the B1 ownership guard.
- `IORING_OP_TIMEOUT_UPDATE`/`MULTISHOT` high-level helpers (flags stay pass-through on the
  raw API; linked timeouts reject them at prepare time as the kernel does).
- Benchmark changes, NVMe/disk benchmarks, M2 report edits.

## 1. Environment facts (verified this session)

- Kernel `7.0.12-201.fc44.x86_64`; JDK for `mvn` is 25 (pom target 25).
- `IORING_REGISTER_PROBE` on this host advertises `IORING_OP_TIMEOUT` (11) and
  `IORING_OP_LINK_TIMEOUT` (15): `supportsTimeout=true supportsLinkTimeout=true`.
- Baseline before B3: `mvn -o test` = 184 tests, 0 failures, 0 errors, 1 skipped (tmpfs
  discard-alignment skip).
- `IORING_TIMEOUT_*` / `IORING_ASYNC_CANCEL_*` numeric values used below come from the
  build tree's `include/uapi/linux/io_uring.h` for this kernel (verified, see §2.1).
- **`IORING_TIMEOUT_ETIME_SUCCESS` (bit 5) is accepted at prep but NOT honoured by this
  running kernel:** every expired standalone/linked timeout still completes `-ETIME`
  (probe D4/D5/D6/D7, `probe2-output.txt`). B3 therefore passes the flag through on the raw
  API but must not rely on it, and the high-level timed-operation API does not use it.
- Signed `-ECANCELED` completions are `-125`; kernel timeout expiry is `-ETIME = -62`
  (distinct from libc `ETIMEDOUT = 110`, which the `JuringFile` API uses).

## 2. Timeout contract

### 2.1 Constants and field layout (frozen)

```java
// IoUringDispatcher (package-private)
static final byte IORING_OP_TIMEOUT      = 11;
static final byte IORING_OP_LINK_TIMEOUT = 15;
static final int IORING_ASYNC_CANCEL_ALL     = 1;
static final int IORING_ASYNC_CANCEL_FD      = 1 << 1;
static final int IORING_ASYNC_CANCEL_ANY     = 1 << 2;
static final int IORING_ASYNC_CANCEL_FD_FIXED = 1 << 3;

// JUring (public flags, uapi values for this kernel)
public static final int IORING_TIMEOUT_ABS            = 1;
public static final int IORING_TIMEOUT_UPDATE         = 1 << 1;
public static final int IORING_TIMEOUT_BOOTTIME       = 1 << 2;
public static final int IORING_TIMEOUT_REALTIME       = 1 << 3;
public static final int IORING_LINK_TIMEOUT_UPDATE    = 1 << 4;
public static final int IORING_TIMEOUT_ETIME_SUCCESS  = 1 << 5;
public static final int IORING_TIMEOUT_MULTISHOT      = 1 << 6;
public static final long TIMESPEC_BYTES = 16L;
```

SQE layout, both follow liburing:

| opcode | fd | addr | len | off | rw_flags (`timeout_flags`) |
|---|---|---|---|---|---|
| `IORING_OP_TIMEOUT` (11) | -1 | `__kernel_timespec*` | 1 | `count` | `IORING_TIMEOUT_*` |
| `IORING_OP_LINK_TIMEOUT` (15) | -1 | `__kernel_timespec*` | 1 | 0 | `IORING_TIMEOUT_*` (ABS/BOOTTIME/REALTIME/ETIME_SUCCESS) |

`struct __kernel_timespec` is two i64s: `tv_sec` at offset 0, `tv_nsec` at offset 8.

### 2.2 Standalone `IORING_OP_TIMEOUT` (exists since B1; contract frozen)

```java
public long prepareTimeout(MemorySegment timespec);                       // count=1, flags=0
public long prepareTimeout(MemorySegment timespec, int count, int flags);
public long prepareTimeout(MemorySegment timespec, int count, int flags, SqeOptions...);
```

Frozen semantics (verified probes D1–D4):

- Relative timeout (`flags` without `ABS`): completes `-ETIME` at expiry (D1: 20 ms → `-62`
  at 20 ms). With `IORING_TIMEOUT_ETIME_SUCCESS` the kernel **still** completes `-ETIME`
  on this host (D4); the flag is pass-through only.
- `IORING_TIMEOUT_ABS`: the timespec is an absolute `CLOCK_MONOTONIC` time
  (`System.nanoTime()` scale); verified fires at the requested instant (D3: now+100 ms →
  `-62` at 100 ms).
- `count` > 0: the timeout completes with result `0` when `count` further completions are
  posted after it is armed (D2: count=1 + NOP → `0`). `count == 0` is a pure timer: it
  completes on expiry only. **B3 decision:** `JuringFile.prepareTimeout(ms)` uses
  `count=0`, because it is the only shape that is deterministically cancellable without a
  completion-count race.
- `BOOTTIME`/`REALTIME` are pass-through and not independently live-verified; callers must
  not assume them beyond the uapi contract.
- Exactly one completion per timeout request (verified in all probes/loops).
- Required: `timespec` native/alive/thread-confined/`>= 16` bytes; unsupported opcode
  throws `UnsupportedOperationException` at prepare; invalid arguments throw
  `IllegalArgumentException` before an SQE is consumed.

### 2.3 New `IORING_OP_LINK_TIMEOUT` (opcode 15)

```java
// IoUringDispatcher (package-private)
void prepareLinkTimeout(long sqe, long timespecAddr, int flags);
long lastQueuedSqe();          // last prepared, not-yet-submitted SQE address (0 = empty batch)

// JUring
public long prepareLinkTimeout(MemorySegment timespec);                 // flags=0
public long prepareLinkTimeout(MemorySegment timespec, int flags);
public long prepareLinkTimeout(MemorySegment timespec, int flags, SqeOptions...);
boolean supportsLinkTimeout();                                          // from the opcode probe
```

**Validation (frozen; before touching the SQ ring):** opcode probed; `timespec` same checks
as `prepareTimeout`; `flags` must be a subset of
`ABS | BOOTTIME | REALTIME | ETIME_SUCCESS` (the kernel rejects update/multishot for linked
timeouts); the immediately preceding SQE in the **same not-yet-submitted batch** must exist,
must carry `IOSQE_IO_LINK`, and must not be another `IORING_OP_LINK_TIMEOUT`. Violations
throw `IllegalStateException`/`IllegalArgumentException` with an actionable message. The
caller must not queue a third SQE between the target and the linked timeout; the linked
timeout must be the last SQE of the pair.

**Frozen CQE sequences (verified, `Probe.java` A/B/C/F, `Probe2.java` D7/H2/H3):**

| Scenario | target CQE | linked-timeout CQE | verified |
|---|---|---|---|
| (a) target completes before timeout | target's real result (e.g. NOP `0`) | **`-ECANCELED`** (disarmed, *not* `0`) | B: NOP `0`, link `-125`, elapsed 0 ms |
| (b) timeout fires first | **`-ECANCELED`** | **`-ETIME`** (`-62`) | A: link `-62` at 101 ms, target `-125`; C: 20/20 iterations exactly one CQE each |
| (b') target fails before timeout | target's real errno (e.g. `-EBADF`) | `-ECANCELED` | H3: `-9` / `-125` |
| target cancelled by user (`prepareCancelByUserData(target)`) | `-ECANCELED` | `-ECANCELED` (disarmed) | H2: 50/50 iterations |
| `ETIME_SUCCESS` on the link timeout | `-ECANCELED` | `-ETIME` on this kernel (flag not honoured) | D7 |

**Frozen rules:**

1. Exactly **one CQE per request**: the target gets one, the linked timeout gets one. No
   completion is lost or duplicated. A linked-timeout completion owns **no user buffer**.
2. The **order** of the two CQEs in the CQ ring is not part of the contract; consumers must
   key on `user_data`/`Result.type()` (`OperationType.LINK_TIMEOUT`, index 20), not order.
3. (b) and (b') may occur for the same pair; the observable difference is the target result
   (`-ECANCELED` vs the real errno) and the linked timeout result (`-ETIME` vs
   `-ECANCELED`), so a kernel timeout is distinguishable from an explicit cancel.
4. The linked timeout is **not cancellable by its own `user_data`**: an
   `IORING_OP_ASYNC_CANCEL` addressing it completes `-ENOENT` (E7). Cancelling the *target*
   is the supported way to tear the pair down.
5. A linked timeout attached to a target that is itself an `IORING_OP_TIMEOUT` with
   `count > 0` interacts with completion counting (a cancel CQE can satisfy the target's
   `count`, E7). B3's high-level API only links timeouts to real file operations; tests use
   `count=0` for standalone timers used as cancellation targets.

**`__kernel_timespec` lifetime (frozen):** the segment belongs to the caller and must stay
valid (native, alive, not moved/freed) **until the timeout request's completion has been
reaped** (conservative rule; the kernel copies the timespec when it consumes the SQE). For
`ABS` timeouts the same rule applies. `JUring` never allocates or owns the caller's segment;
`JuringFile` owns and frees its internal 16-byte segments (see §2.5).

### 2.4 `OperationType` addition (frozen)

Append only, never renumber indices 0–19:
```java
LINK_TIMEOUT(20)
```
`populateResultFromCqe` maps type index 20 to `OperationType.LINK_TIMEOUT` with no buffer
ownership. The `default` branch now maps unmatched `user_data` types to a NOP-shaped
completion with no buffer ownership (defensive: a reusable `Result` can no longer carry
stale ownership into a consumer).

### 2.5 High-level `JuringFile` timed-operation API (frozen)

```java
public long prepareTimeout(long timeoutMillis) throws JuringException;            // count=0, role TIMEOUT
public long prepareRead(long offset, int length, MemorySegment buffer, long timeoutMillis) throws JuringException;
public long prepareWrite(long offset, MemorySegment buffer, long timeoutMillis) throws JuringException;
public int  read(long offset, MemorySegment buffer, long timeoutMillis) throws JuringException;   // single-shot
public int  write(long offset, MemorySegment buffer, long timeoutMillis) throws JuringException;  // single-shot
```

- The timed read/write pair is `IORING_OP_READ/WRITE` with `IOSQE_IO_LINK` immediately
  followed by `IORING_OP_LINK_TIMEOUT(ms)` in one submission batch. The returned handle is
  the **target** handle; `await(handle)` reconciles **both** completions and returns/throws
  deterministically:
  - target completes → returns its real result (short reads are not retried; timed helpers
    are single-shot);
  - timeout fired → target `-ECANCELED` + linked timeout `-ETIME` → **throws
    `JuringException` with `errno = ETIMEDOUT (110)`** (note: raw `JUring` reports the
    kernel `-ETIME (62)`; the file layer maps it);
  - explicit `cancel(handle)` → target `-ECANCELED` + linked timeout `-ECANCELED` →
    **throws `JuringException` with `errno = ECANCELED (125)`**;
  - target failure → the real errno.
- `prepareTimeout(ms)` is a standalone kernel timer tracked by the file ledger with
  `count=0`. `await(handle)` throws `ETIMEDOUT` when it fires and `ECANCELED` when it is
  cancelled. It exists so a file handle can own a controllable long-running request (used by
  bounded-shutdown tests and by callers wanting a deadline tied to the handle's lifecycle).
  It is not linked to file I/O by itself; use the timed read/write helpers for that.
- **Ledger accounting:** a timed operation is **two ring requests** (target + linked
  timeout). `issuedOperations()`/`completedOperations()`/`failedOperations()` and
  `inFlightOperations()` count ring requests, so one timed operation contributes 2. The
  ledger needs two free slots; `withInFlightCapacity(n)` must be `>= 2` per timed operation.
  `cancelledOperations()` excludes linked-timeout disarms (only target/timeout requests
  count), so a successful timed operation does not look cancelled.
- **Ownership:** the file allocates one 16-byte native `__kernel_timespec` per prepared
  timeout/pair with `calloc` and frees it exactly once when the ledger slot is consumed or
  abandoned (not ring-arena memory; no ring-lifetime growth). A timed operation must be
  awaited/cancelled/closed; abandoning at force-close frees the segment via the ledger reset.
- If the linked-timeout SQE cannot be prepared after the target SQE was queued (e.g. ring
  closing/SQ failure), the target is still tracked as a single request so its completion is
  reconciled; the thrown exception is the caller's signal that no kernel timeout was armed.

### 2.6 `await(handle, timeoutMillis)` vs kernel timeout (frozen documentation)

| | caller-side `await(handle, timeoutMillis)` | kernel timeout (`prepareRead(..., timeoutMillis)`) |
|---|---|---|
| who measures | the calling thread (`LockSupport.parkNanos` + non-blocking CQ polls) | kernel hrtimer |
| request on expiry | **stays in flight**; the op is not cancelled | target is cancelled (`-ECANCELED`) and linked timeout completes `-ETIME` |
| thrown errno | `JuringException(ETIMEDOUT, "await", ...)`; the handle can be awaited/drained later | `JuringException(ETIMEDOUT)`; the request is terminal |
| works while the caller does other work | no (blocks the thread) | yes, the kernel cancels even if the device hangs |
| completion count | none added by `await` | exactly one target + one linked-timeout CQE |
| late result | still delivered on the next `await`/`drain`/`close` | already delivered |

`await(handle, timeoutMillis)` is bounded but does not stop the I/O; a caller that needs
resource-bounded shutdown should use the kernel timeout or `cancel(handle)`/`close(timeout)`.

## 3. Cancellation contract

### 3.1 Raw engine API (frozen)

```java
// JUring
public long prepareCancelByUserData(long userData);          // IORING_OP_ASYNC_CANCEL, key=user_data
public long prepareCancelFile(int fdOrSlot, boolean fixedFile); // ALL|FD (+FD_FIXED), key=file
public long prepareCancelAny();                              // ALL|ANY, ring-wide (dangerous)
@Deprecated public long prepareCancel(long targetOpId);       // correct alias of prepareCancelByUserData
```

SQE layout follows liburing: by-user-data `fd=-1, addr=user_data, cancel_flags=0` (user_data
match is the kernel default; the explicit `IORING_ASYNC_CANCEL_USERDATA` bit is not set for
compatibility); by-fd `fd=fdOrSlot, addr=0, cancel_flags=FD[|FD_FIXED][|ALL]`; by-any
`fd=-1, addr=0, cancel_flags=ALL|ANY`. The cancel request's own `user_data` is a well-formed
`CANCEL` handle so `Result` parsing is clean.

**Every cancel request posts exactly one completion**: its own CQE. Result codes:

| cancel CQE result | meaning | `JuringFile.cancel` status |
|---|---|---|
| `0` | matched and cancelled (target will complete `-ECANCELED`) | `CANCELLED` |
| `-ENOENT` (`-2`) | no matching in-flight request: already completed/reaped or never existed | `ALREADY_COMPLETED` (if the handle is known) / `NOT_FOUND` (unknown handle) |
| `-EALREADY` (`-114`) | matched but already completing (kernel-dependent; mapped for completeness) | `ALREADY_COMPLETED` |
| `-EINVAL` etc. | rejected request (bad flags/fd) | `JuringException` thrown |
| `n > 0` | only with `ALL`: number of requests cancelled | `cancelAll()` count evidence |

**Verified (probes E1–E6, H1/H2):** cancel unknown user_data → `-ENOENT` (E1); cancel an
already-completed target → `-ENOENT` on this kernel (E2; `-EALREADY` is mapped but not
observed); cancel queued in the same batch *after* its target → target `-ECANCELED`, cancel
`0` (E3); bulk `ALL|ANY` of 20 timers → cancel CQE `20` + 20 × `-ECANCELED` (E4); cancel by
plain fd and by fixed-file slot → cancel CQE `1`, target `-ECANCELED` (E5/E6); cancelling an
in-flight timeout → target `-ECANCELED`, cancel `0`, **200/200 deterministic iterations for
both `count=0` and `count=1`** (H1); cancelling a timed-pair target → target `-ECANCELED`,
linked timeout `-ECANCELED`, cancel `0`, **50/50 deterministic** (H2).

### 3.2 `JuringFile` cancellation API (frozen)

```java
public enum CancelStatus { CANCELLED, ALREADY_COMPLETED, NOT_FOUND }
public CancelStatus cancel(long handle) throws JuringException;
public int cancelAll() throws JuringException;
public long controlOperations();
public long cancelledOperations();
```

- `cancel(handle)`:
  1. closed handle / foreign ring work → `IllegalStateException` (ownership guard);
  2. unknown handle → `NOT_FOUND` (no kernel request issued);
  3. ledger state `DONE` → `ALREADY_COMPLETED` (no kernel request; the real result is still
     returned by `await(handle)`);
  4. otherwise issue one cancel-by-user_data control request, track it as a **control**
     ledger slot (counted in `controlOperations()`, not `issuedOperations()`), reap until
     both the target and control completions are recorded, then return the status. The
     target's terminal completion is **not consumed**: a later `await(handle)` delivers it
     (`-ECANCELED` for `CANCELLED`, the real result for `ALREADY_COMPLETED`).
- `cancelAll()` first takes the ownership guard (`ring.inFlightCount() == inFlight`), which
  proves every in-flight ring request belongs to this handle. It then issues **one ring-wide
  `IORING_OP_ASYNC_CANCEL` with `ALL|ANY`** and drains until the ledger reports no in-flight
  requests, bounded by `withCloseTimeout` (default 2000 ms). This is O(1) even with tens of
  thousands of pending requests (verified: cancelling 10k timers via one request completes in
  ~10 ms) and is exact because of the guard. On success every target has exactly one terminal
  completion and `controlOperations`/`cancelledOperations` are updated; it returns the number
  of `-ECANCELED` target completions observed. A bounded drain timeout throws
  `JuringException(ETIMEDOUT)` and leaves the remaining requests tracked (they can still be
  awaited or closed); it never hangs. The file-scoped `JUring.prepareCancelFile` bulk API
  remains available for callers that need fd-scoped cancellation without ring ownership.
- **No lost or duplicated completions:** every issued ring request is tracked (user
  request, companion, or control) and every completion decrements the ledger's in-flight
  count exactly once; `inFlightOperations()` therefore stays equal to
  `JUring.inFlightCount()` while the handle is healthy (the ownership guard asserts this).
- **await-after-cancel never hangs, deterministic:** a cancelled request always has exactly
  one terminal completion (kernel guarantee, verified in the loops above). `await` after
  `cancel` returns `ECANCELED`; `await` after `ALREADY_COMPLETED` returns the real result.

### 3.3 Race table (frozen)

| race | kernel behaviour | observable API result |
|---|---|---|
| cancel prepared in the same batch, after the target | target is found and cancelled | target `-ECANCELED`; cancel `0`; `await` → `ECANCELED` (verified E3) |
| cancel issued while target just completed | kernel reports `-ENOENT` | `cancel` → `ALREADY_COMPLETED`; target's real result still delivered by `await` (verified E2) |
| cancel issued after the completion was reaped into the ledger | no request needed | `cancel` → `ALREADY_COMPLETED` without a kernel request |
| cancel of an unknown/consumed handle | no request | `NOT_FOUND` |
| cancel by fd races completion of one of many | kernel cancels what it finds | cancel CQE result = count cancelled (≥0) or `-ENOENT`; every matched target gets `-ECANCELED` |
| timeout fires while a cancel is in flight | one of the two wins | exactly one target terminal completion (`-ECANCELED` with either `-ETIME` or `-ECANCELED` companion; mapped to `ETIMEDOUT` or `ECANCELED`) |

Cancellation of a request that carries an owned write buffer (legacy `prepareWrite(byte[])`
malloc buffer) still frees it exactly once: the buffer is attached to the target's
`user_data` and `populateResultFromCqe` frees it on the target's completion regardless of the
sign of the result.

## 4. Bounded shutdown contract

### 4.1 `JuringFile.close(long timeoutMillis)` (frozen)

```java
public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;   // close() delegates
public void close();                     // == close(DEFAULT_CLOSE_TIMEOUT_MILLIS)
public void close(long timeoutMillis);   // timeoutMillis >= 0, else IllegalArgumentException
```

Phases, all bounded:

1. **Drain phase** (`ring.submit()` then reap until the ledger's in-flight count is 0 or
   `deadline = now + timeoutMillis`): non-blocking CQ polls
   (`pollForBatchResult`: peeks the CQ and, for `DEFER_TASKRUN` rings, issues a
   non-blocking `io_uring_enter(GETEVENTS, min_complete=0)` so deferred task work is
   flushed) with 100 µs `LockSupport.parkNanos` slices. This fixes the B1 stall where the
   full deadline was burned because deferred completions were invisible to a peek-only loop.
2. **Cancel phase** if the deadline expired: one ring-wide `ASYNC_CANCEL ALL|ANY` request
   (safe and exact under the ownership guard, O(1) for 10k in-flight requests; tracked as a
   control request), and the cancellation CQEs are reaped/reconciled in the next phase.
3. **Grace phase**: drain cancelled completions for up to `GRACE_NANOS = 250 ms`
   (`closeTimeoutMillis` does not apply to this phase).
4. **Force path**: collect completions already posted, then abandon only the still-pending
   requests, counting them in `abandonedOperations()` (never silent). Their CQEs may still
   be delivered through the ring's CQ; the B1 note stands.

**Guarantees:** `close(long)` returns within `timeoutMillis + 250 ms` when the cancel phase
is needed (earlier on a clean drain; measured 2020 ms for `close(2000)` with 10,000 in-flight
timers, i.e. drain-to-deadline plus ~20 ms to cancel and reap all 10k completions); never
throws for operation/cancellation failures;
idempotent; unregisters the fixed-file slot (quietly) and closes the descriptor; use after
close throws `IllegalStateException`. Counters after a clean close satisfy
`issuedOperations() == completedOperations()` and `abandonedOperations() == 0` for
requests that were allowed to complete/cancel.

**Foreign in-flight work rule (preserved and tightened):** if `ring.inFlightCount()` does not
equal the handle's ledger in-flight count, the handle cannot distinguish its completions from
another owner's and must not advance the shared CQ head. B3 therefore **neither reaps nor
cancels** in that case: pending requests are abandoned and counted, and their completions
remain for the ring's owner. This fixes the observed baseline defect where per-handle cancels
were submitted but never reaped (ring in-flight count grew 9 → 17 after the file closed). If
the ring is already closed, remaining requests are abandoned and counted, then the fd is
closed and the call returns immediately.

### 4.2 `JUring.close(long timeoutMillis)` (frozen)

```java
public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;
public void close();                                 // == close(DEFAULT)
public void close(long timeoutMillis);               // >= 0
public long cancelledAtClose();                      // -ECANCELED completions reaped during close
public int abandonedAtClose();                       // requests left in flight at force-close
```

- If the ring is initialised: `submit()` queued SQEs, submit one
  `IORING_OP_ASYNC_CANCEL ALL|ANY` (well-formed `CANCEL` user_data), then drain until the
  dispatcher's in-flight counter is 0 or the deadline expires (non-blocking CQ polls +
  `enter(GETEVENTS, 0)` flush + 100 µs parks); reap once more, record counters, then
  force-close: unregister the ring fd, munmap SQ/CQ/SQE regions, close the ring fd, free the
  iovec pool and close the arena. Never blocks on I/O beyond the deadline; syscall teardown
  is bounded. Never throws for operation failures; idempotent.
- A request that never completes (or cannot be cancelled) is counted in `abandonedAtClose`
  and dropped with the mmap; the kernel releases it with the ring. Its CQE cannot be
  observed after the ring is gone.
- Cancellation of the cancel request itself is impossible (it is the last SQE of the batch),
  and its own CQE is counted as a normal completion.

### 4.3 Counters (frozen)

| counter | meaning |
|---|---|
| `JuringFile.issuedOperations()` | ring requests prepared through the handle (timed pair = 2) |
| `JuringFile.completedOperations()` | ring-request completions recorded (timed pair = 2) |
| `JuringFile.failedOperations()` | completions with a negative result |
| `JuringFile.cancelledOperations()` | terminal `-ECANCELED` completions of **target/timeout** requests (excludes disarmed linked timeouts) |
| `JuringFile.controlOperations()` | internal cancel requests issued by `cancel`/`cancelAll`/`close` |
| `JuringFile.abandonedOperations()` | pending requests abandoned at close (counted, never silent) |
| `JuringFile.inFlightOperations()` | ring requests prepared but not completed; equals `JUring.inFlightCount()` while the ownership guard holds |
| `JUring.cancelledAtClose()` | `-ECANCELED` completions reaped by the most recent bounded close |
| `JUring.abandonedAtClose()` | requests still in flight when the ring was force-closed |

### 4.4 Idempotency / call-order rules (frozen)

- `close()` on an already-closed handle/ring is a no-op (counters and fd untouched).
- File closed after the ring: returns fast, abandons/counts pending requests, closes the fd.
- Ring closed after the file: the file's already-abandoned completions are dropped with the
  ring; `JUring.close` counters reflect whatever it could reap.
- `JuringFile.close(timeout)` does **not** close the `JUring`.

### 4.5 `JUringBlocking` residual hazard (documented, not reworked)

`JUringBlocking` still shuts its poller down by submitting `ASYNC_CANCEL ALL|ANY` and then
looping `submitAndWait(1)` until its own in-flight holder reaches 0. If a kernel/driver
never delivers a cancellation completion for a request, that loop can block. B3 leaves it
unchanged (the class has its own MPSC/eventfd control path and was out of the acceptance
path); the hazard is recorded here and in `scratch/b3/STATUS.md`. Its resource cleanup
(ring close, eventfd) happens only after the poller thread joins.

## 5. Queue-full / CQ-overflow contract (frozen)

- **SQ full:** every `prepare*` that needs more SQEs than are free throws
  `IllegalStateException("Submission queue ring is full; call submit() ...")` *before*
  consuming an SQE; the call has no ledger/user_data side effect (a timed pair that fails
  after the target SQE was queued is the documented exception in §2.5). Callers must
  `submit()`/reap and retry. `JuringFile.cancel`/`cancelAll`/`close` retry internally by
  submitting before re-preparing, so lifecycle paths do not fail on a full SQ.
- **CQ full / overflow:** modern kernels advertise `IORING_FEAT_NODROP` and spill
  completions into an internal overflow list; **no completions are lost**, but they are only
  posted to the CQ ring when the application reaps (advances the CQ head). The library
  therefore requires callers to reap during long issue bursts. On a legacy kernel without
  NODROP, `io_uring_enter`/submit returns `EBUSY`; the dispatcher maps that to
  `IllegalStateException("Completion queue ring is full (EBUSY); reap completions first")`.
  B3 does not attempt to reap inside `prepare*`; the bounded close path force-reaps over
  overflowed completions with a deadline.
- **Ring capacity vs ledger:** the file ledger capacity is the maximum unawaited ring
  requests; exhausting it throws `IllegalStateException` before a kernel SQE is consumed.
  Timed operations need two slots (§2.5).

## 6. `user_data` / `Result` safety audit (frozen result)

### 6.1 Encoding

`user_data` = 8-bit `OperationType` index at bit 56, 56-bit payload below
(`UserData.PAYLOAD_MASK = 2^56-1`). Fixed-buffer ops additionally reserve a 16-bit buffer
index at bit 40 (`opId` is 48 bits). Consequences, all audited:

- Payloads used as **addresses** (legacy `READ`/`WRITE`/`OPEN`/`READV`/`WRITEV`) are Linux
  user-space addresses (< 2^47), so they never touch the type bits on x86_64/aarch64.
- Payloads used as **op ids** (`NOP`, `CLOSE`, `FSYNC`, `TIMEOUT`, `LINK_TIMEOUT`,
  `FALLOCATE`, `FTRUNCATE`, `STATX`, `CANCEL`, `WRITE_EXT`, `WRITE_FIXED`, `READ_FIXED`)
  wrap only after 2^56 (or 2^48 for fixed ops) operations. A wrap that collides with a
  *still-in-flight* handle could make `await` match the wrong ledger slot. This requires
  ~7.2×10^16 issued operations; it is documented, not defended against (no per-op space
  overhead is added to the zero-allocation fast path).
- `OperationType` indices are append-only; adding `LINK_TIMEOUT(20)` cannot collide with
  indices used by existing persisted/expected user_data values (indices 0–19 unchanged).

### 6.2 Buffer ownership

| completion type | owns user buffer? | freed where |
|---|---|---|
| `READ` (legacy `prepareRead(fd, size, off)`, ring `malloc`) | yes | `Result.freeBuffer()`/`Result.close()`; on cancellation the target completion still carries the address, so it is freed exactly once |
| `WRITE` (legacy `prepareWrite(byte[])`) | yes (temp malloc) | freed eagerly inside `populateResultFromCqe` (regardless of result sign) |
| `OPEN` (path malloc) | yes | freed eagerly inside `populateResultFromCqe` |
| `READV` (malloc'd iovecs) | yes | `Result.freeBuffers()`/`close()` |
| `READ_EXT` (caller `MemorySegment`) | no | never freed by `Result` |
| `WRITE_EXT` (caller `MemorySegment`) | no | never freed |
| `WRITE_FIXED` / `READ_FIXED` | no (registered buffer) | never freed by `Result` |
| `FSYNC`, `FALLOCATE`, `FTRUNCATE`, `STATX`, `CLOSE`, `NOP`, `CANCEL`, `TIMEOUT`, **`LINK_TIMEOUT`** | **no** | never freed |

**Audited specifically for B3:** `CANCEL`, `TIMEOUT` and `LINK_TIMEOUT` completions set
`bufferAddress = 0`, `buffer = null`, `bufferIdx = -1` → `freeBuffer()`/`freeBuffers()` are
no-ops; a timeout/cancel completion can never free a user buffer. A cancelled *target*
completion retains its own ownership fields, so a cancelled legacy read still frees its
malloc buffer exactly once (verified by the Result-safety tests). The reusable `Result`
used in batch reaping is reset by `populateResultFromCqe` on every CQE (including the new
`default` NOP-shaped branch), so a stale ownership field cannot leak into the next consumer.
`freeBuffer()` is idempotent (zeroes the address); double `close()`/`freeBuffer()` cannot
double-free. A `Result` must be consumed by one owner only; completions reaped by the ring
dispatcher with `Result::close` follow the same rule.

## 7. Error taxonomy (frozen)

| situation | type | errno/result |
|---|---|---|
| kernel I/O/metadata failure | checked `JuringException` | positive `errno` (e.g. `EBADF=9`, `EINVAL=22`) |
| kernel timeout expired (raw `JUring` result) | — | CQE result `-ETIME = -62` |
| kernel timeout expired (`JuringFile.await`/timed helpers) | checked `JuringException` | `ETIMEDOUT = 110` (mapped; `JuringException.ETIME=62` documents the kernel value) |
| request cancelled (`JuringFile.await`) | checked `JuringException` | `ECANCELED = 125`, `isCancelled()` |
| cancel request found nothing / already completed | `JuringFile.CancelStatus` | `NOT_FOUND`/`ALREADY_COMPLETED` (no exception) |
| cancel request rejected by kernel | checked `JuringException` | positive errno (e.g. `EINVAL`) |
| kernel lacks an optional opcode | unchecked `UnsupportedOperationException` | at prepare time, message carries `errno 95` |
| caller-argument validation | unchecked `IllegalArgumentException` / `NullPointerException` / `WrongThreadException` | — |
| lifecycle misuse: use-after-close, unknown handle, ownership/foreign work, exhausted ledger, SQ full | unchecked `IllegalStateException` | — |

`JuringException` also exposes `isTimedOut()`, `isCancelled()`, `isAgain()`,
`isNotFound()`, `isBadFileDescriptor()`, `isInvalidArgument()` and the kernel constants
`ETIME`/`EALREADY` for callers that read raw `JUring` results.

## 8. Use-after-close hardening (frozen; fixes a verifier SIGSEGV)

The verifier reproduced a JVM SIGSEGV on the baseline tree:
`JUring.prepareNop()` after `JUring.close()` reached
`IoUringDispatcher.getSqe()` → `VarHandle…getAcquire` and read the SQ ring after the
dispatcher arena/mmap had been freed. The frozen contract is:

- **All public `JUring` methods that touch the native ring** (`prepare*` through the shared
  `getSqe()`, `submit*`, `wait*/reap*` variants, registration/buffer lifecycle, shared-worker
  ring creation, `inFlightCount`/`staticxSize`) check `closed` first and throw
  `IllegalStateException("JUring is closed")` with no native access. Capability queries
  (`isClosed()`, `supports*()`) remain safe/non-throwing.
- `JUring.close()`/`close(long)` are idempotent and never throw because of teardown races.
- `JuringFile` operations check both file-open and ring-closed (`ensureOpen`) before
  touching the ring; `JuringFile.close()` after the ring closed is fast and counter-consistent.
- The verifier's reproducer (`prepareNop` after `close`, plus prepare/await/submit paths for
  ring and file) must terminate with `IllegalStateException`, never a crash; B3 tests and
  `scratch/b3/` cover it.

## 9. Test contract (frozen IDs)

New JUnit 5 files under `src/test/java/com/davidvlijmincx/lio/api/` (all `@Timeout`):

| file | coverage (IDs are the test method names) |
|---|---|
| `JUringLinkTimeoutTest` | opcode advertised; link fires first (target `-ECANCELED`, link `-ETIME`, exactly one CQE each, ~deadline); target completes first (link `-ECANCELED`, target real result); ABS link timeout; target-fails case; prepare validation (same batch, `IOSQE_IO_LINK`, no link-timeout-on-link-timeout, heap timespec, bad flags); stray CQE count == 2 |
| `JUringCancellationTest` | cancel by user_data in flight (target `-ECANCELED` + cancel `0`); cancel unknown (`-ENOENT`); cancel already-completed (`-ENOENT` mapped, real result not re-delivered); same-batch cancel; bulk cancel by plain fd and fixed slot (eventfd target); bulk ANY|ALL cancel count; cancel request completion always present; ring in-flight returns 0; `prepareCancel` alias equivalence |
| `JuringFileTimeoutTest` | timed read/write success; kernel timeout fires → `ETIMEDOUT`; explicit cancel of a timed pair → `ECANCELED`; `await(handle, timeout)` caller-side timeout leaves the op in flight and the late result is still delivered; `prepareTimeout` fires → `ETIMEDOUT`; zero/negative argument validation |
| `JuringFileCancelTest` | `cancel` status matrix (`CANCELLED`, `ALREADY_COMPLETED`, `NOT_FOUND`); deterministic await-after-cancel; `cancelAll` with standalone timers and file ops; `cancelledOperations`/`controlOperations` counters; ring in-flight returns 0 |
| `JuringFileBoundedCloseTest` | close fast when ops complete; `close(timeout)` with many long timers returns within bound, all reaped, abandoned 0; `close(0)` bound; foreign in-flight work → abandoned counted, ring counter not inflated, no hang; ring-closed-before-file; idempotent double close; `JUring.close(timeout)` with in-flight timers within bound + counters |
| `JuringLifecycleUseAfterCloseTest` | ring use-after-close (`prepareNop`, `prepareRead`, `submit`, `waitForResult`, `peekForBatchResult`, `registerBuffers`) throws `IllegalStateException` (no SIGSEGV); file use-after-close; ring-close-before-file fast close |
| `JuringResultSafetyTest` | timeout/cancel/linked-timeout completions own no buffers (`freeBuffer`/`freeBuffers`/`close` no-ops); cancelled legacy read frees its buffer exactly once; double `freeBuffer` safe; `LINK_TIMEOUT` Result type/mapping |

Runtime budget: every deterministic test uses small counts (≤256) and ≤1 s deadlines; the
50 %-cancel stress at QD 64/256 and the 10k-in-flight close run in `scratch/b3/Stress.java`
(stanalone `javac` against `target/classes`), so `mvn test` stays fast.

## 10. Write scopes (advisory; disjointness is the rule)

| Task | Write scope |
|---|---|
| b3-engineer (this task) | `docs/b3-design.md`, `src/main/java/com/davidvlijmincx/lio/api/**`, `src/test/java/com/davidvlijmincx/lio/api/**`, `scratch/b3/**` |
| b3-verifier | `scratch/b3-verify/**` only |
| Lead | `docs/m2-report.md`, `SEASTAR-PLAN.md`, `docs/HANDOVER.md`, `bench/**`, `pom.xml`, git commits |

## 11. Known limitations / residual risks (frozen disclosure)

1. `IORING_TIMEOUT_ETIME_SUCCESS` is not honoured by the running kernel (verified); raw flag
   pass-through only, high-level API unaffected.
2. `BOOTTIME`/`REALTIME` clock flags are pass-through, not live-verified in B3.
3. Cancellation of a **linked timeout request itself** is not supported by the kernel
   (`-ENOENT`); cancel the target instead (frozen rule §2.3.4).
4. Foreign in-flight work on the same ring: `JuringFile.close` abandons (counted) instead of
   cancelling/reaping, preserving the B1 ownership guard. A future ring-level dispatcher (B4)
   removes this limitation.
5. `JUringBlocking` shutdown remains unbounded in the pathological
   cancellation-does-not-complete case (§4.5).
5b. `JuringFile.cancelAll()`/`close()` use a ring-wide cancel and therefore require the
   B1 ownership invariant (no foreign in-flight work). With foreign work they do not cancel
   (they abandon and count) rather than risk another owner's requests.
6. `user_data` op-id wrap (2^56 / 2^48) is documented, not defended (§6.1).
7. CQ-overflow contract is documented and implemented defensively (EBUSY →
   `IllegalStateException`, close force-reaps) but not stress-tested by deliberately
   exhausting the CQ ring; no completions are intentionally dropped by the library.
8. Timed operations double the ring-request accounting and ledger usage by design (§2.5).
9. **Duplicate `user_data` for concurrent non-fixed reads (verifier finding F1).**
   `READ_EXT` encodes the buffer address in `user_data`, so two in-flight reads into the
   same `MemorySegment` share one handle; after the first is consumed the ownership guard
   wedges the second (`await` throws `IllegalStateException`, `cancel`/`await` cannot
   disambiguate, `close` abandons) because the kernel matches by `user_data`. B3 rule: do
   not reuse one buffer for concurrent non-fixed reads on a handle/ring; B4 may encode an
   op-id and carry the address in a side table (as `READ_FIXED` does). Reproducer:
   `scratch/b3-verify` `P2fTaxonomy duplicate-userdata`.
   **(Update 2026-11-16, B10.)** The `JuringFile` form is fixed, for both registered-file
   and unregistered branches: non-fixed `JuringFile` reads use the internal `user_data` type
   index 21 (bits 56-63; unused by `OperationType`) with a unique 56-bit operation id as the
   payload instead of the buffer address, so distinct handles/ledgers; see
   `docs/b10-design.md` §2.6. The raw `JUring.prepareRead(..., MemorySegment, ...)` form keeps
   the address payload (`READ_EXT` type 14) for `Result.validBuffer()`, so this B3 rule still
   applies to the raw API.
10. **Control-ledger headroom (verifier finding F2).** `JuringFile.cancel`/`cancelAll` track
   the internal cancel request in the in-flight ledger, so they need at least one free slot
   (two for a timed pair); a *saturated* ledger makes them throw
   `IllegalStateException("... in-flight capacity exhausted ...")` instead of cancelling.
   `close(timeout)` is safe (bounded; abandons and counts). Keep ≥1 (≥2 timed) slots free,
   or use the default 1024-slot capacity. Reproducer: `P2eQueueFull ledger-exhaustion`.
11. **`IOSQE_CQE_SKIP_SUCCESS` phantoms (verifier risk R3).** A successful skip-success
   request posts no CQE by kernel design, so the library cannot balance its in-flight count;
   `JUring.close(timeout)` remains bounded (force-close, `abandonedAtClose` counts the
   phantoms) but may burn the full deadline, and callers must not expect a completion for a
   skipped success. (The B1 unbounded `close()` hang from this case is fixed.)
12. **`supportsLinkTimeout()` is package-private** (like `supportsTimeout()`): public users
   detect support by catching `UnsupportedOperationException` from `prepareLinkTimeout`. A
   public capability probe can be added in a later API-stability pass (B9).
