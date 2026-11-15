# B6 design contract — buffered I/O parity + durability (fadvise, durability modes, group commit)

**Status:** frozen contract, written **before** implementation (2026-11-15, session 9).
This document is the coordination contract between the B6 workstreams. Public signatures
marked **frozen** may not change without a Lead revision of this file; internal algorithms
and call-site placement are owned by the implementation except where this file fixes
observable semantics. **Amendments** are appended as `A1, A2, ...` with a date, never
silently edited.
**Implements:** `SEASTAR-PLAN.md` §4 **B6** (gap G12, buffered I/O parity + durability);
builds additively on the B1 `JuringFile` and B2 `JuringInputStream`/`JuringOutputStream`
contracts.
**Acceptance (from the plan):** buffered comparison vs seastar `fstream` (W21/W22);
durability modes tested; group-commit test proves **one fsync for a batch** and correct
visibility after restart.

Baseline tree: branch `kafka-engine` at `d0d549a` (M3 signed; B5 delivered in `309335b`),
349 tests green (349/0/0/1). **B6 must not change any B1/B2/B3/B4/B5 behaviour**: all
existing tests stay byte-identical, every existing default is unchanged (new defaults are
`DurabilityMode.WRITEBACK` and `FadviseAdvice.NORMAL`, i.e. exactly today's behaviour), and
`pom.xml` / `module-info.java` do not change. Because B6 grows the frozen B1/B2 public
signature lists additively (`JuringFile`, `InputStreamOptions`, `OutputStreamOptions`,
`JuringOutputStream`), the Lead appends a one-paragraph **"B6 addendum" pointer** to
`docs/b1-design.md` §8 and `docs/b2-design.md` §8 in the docs commit (pointer only; the
historical frozen text itself is not rewritten).

**M2 evidence note (important).** The plan's first B6 acceptance clause ("buffered
comparison vs seastar `fstream` (W21/W22)") was already delivered and signed off by M2
(`docs/m2-report.md`, PASS 2026-11-14: reads 98.2 %/98.7 % of seastar with strict one-sided
95 % LB 0.966/0.960; writes 139 %/160 % point estimate, position-matched 0.98–1.38×). B6
does **not** re-litigate M2; it cites it. What B6 adds is the **durability dimension** of
the same buffered-stream protocol (§6 W24) plus a group-commit measurement (W25). B6 also
discloses one benchmark asymmetry inherited from M2: the seastar `fstream` arm runs with
`file_open_options.durable = true`, so its per-pass `flush()` issues one `fdatasync`, while
the JUring arm used the B2 default `syncOnClose = false` (no sync per pass). The M2 **write
win is therefore not explained by a JUring durability shortcut** (the asymmetry favoured
seastar's cost side, not JUring's); B6's W24 arms make the durability setting explicit and
matched on both sides (§6.3).

---

## 0. Scope and non-goals

**Delivered by B6**

1. **`posix_fadvise` hints.** New `FadviseAdvice` enum (POSIX advice values), a blocking
   `JuringFile.fadvise(advice, offset, length)` helper (+ whole-file convenience), and
   stream-level hints: `InputStreamOptions.withFadvise` (applied to the stream's declared
   window at `open`) and `OutputStreamOptions.withFadvise` (applied to the whole file at
   `open`). This closes the plan's "no fadvise hooks" gap. The fixed readahead depth and
   the seastar-mirrored adaptive readahead stay exactly as frozen in B2; B6's "readahead
   policy" addition is the OS-side hint, not a new adaptive algorithm.
2. **Durability modes.** New `DurabilityMode` enum `{WRITEBACK, FDATASYNC, FSYNC, DSYNC}`;
   `JuringFile.durability()` reports the mode implied by the open flags (`O_DSYNC`/
   `O_SYNC` → `DSYNC`, else `WRITEBACK`); `OutputStreamOptions.withDurabilityMode(...)`
   makes **flush()/close() durability points** on the B2 output stream (one `fdatasync`/
   `fsync` per flush/close; `O_DSYNC` = no explicit sync, writes durable on completion).
3. **Group-commit helper.** New `GroupCommit` (+ `GroupCommitOptions`, immutable
   `GroupCommitStats`) on top of `JuringFile`: submit a batch of asynchronous writes,
   complete the batch with **exactly one** `fdatasync` (or `fsync`), a caller-controlled
   batch boundary with a `maxBatchSize` cap, bounded memory/allocation-free steady state,
   typed errors, latching, and a test-proven "one sync per batch + data visible after
   process restart" contract. This is
   the primitive seastar exposes as `file::flush()` + `durable` open options, packaged so a
   write-heavy user does not re-implement fsync policy by hand.
4. **Benchmark integration.** Durability arms for the existing buffered-stream protocol
   (W24, JUring vs seastar `fstream`, explicit matched durability: writeback / fdatasync /
   fsync / O_DSYNC) and a JUring group-commit micro-benchmark (W25, batch sizes 1/8/32/128;
   syncs-per-batch = 1) with a new driver + analyzer; sessions archived under the gitignored
   `bench/results/`. M2's W21/W22 comparison is cited, not re-run.
5. Tests, an independent adversarial verification (`scratch/b6-verify/VERDICT.md`), the B6
   report, and HANDOVER/SEASTAR-PLAN updates.

**Non-goals (explicit)**

- **No change to existing behaviour or frozen B1/B2 signatures.** New options default to
  today's semantics; existing tests are not modified or weakened.
- **No power-loss testing.** "Correct visibility after restart" is tested as *process
  restart* (a child JVM writes + group-commits + dies abruptly; a fresh parent process
  reopens and verifies the bytes). Durability beyond the kernel is the guarantee of
  `fdatasync`/`fsync` and the device cache policy, not of this library; `DSYNC` semantics
  are the kernel's `O_DSYNC` semantics.
- **No io_uring `IORING_OP_FADVISE` (opcode 24) / `MADVISE`, no new `OperationType`.** The
  B1 probe list has no FADVISE opcode; `posix_fadvise(2)` is an advisory, non-blocking
  syscall on regular files and is not on any throughput hot path. It is issued through the
  existing FFM libc dispatcher, not through the ring.
- **No group-commit timeout/cancellation policy in v1.** Group-commit writes complete like
  `JuringFile.write()` (unbounded); callers that need deadlines use the existing timed
  `prepare*`/`await` API directly. A commit deadline is a possible follow-up, not B6.
- **No concurrent multi-writer group commit.** B6 inherits the B1 rule (one ring, one owner
  thread; `JuringFile`/`GroupCommit` are not thread-safe). Cross-thread batching belongs to
  the B4 runtime if ever needed.
- **No automatic `DONTNEED`/`WILLNEED` policy on stream close**, no readahead re-tuning, no
  changes to B4/B5 shard/JMX/JSON-1.1 surfaces, no new runtime dependency, no
  `module-info.java` change.
- **No re-benchmark of the signed M2 read verdict** and no new M-milestone sign-off.

---

## 1. Platform facts (verified for this contract)

- Host/kernel: Ryzen 9 3950X, Linux `7.0.12-201.fc44.x86_64`; JDK `26.0.2`; Maven
  `3.9.11`; data NVMe `nvme1n1` (ext4) for the write cells, `/mnt/TestData` aged file for
  the read cells; governor `performance`; one NUMA node.
- `posix_fadvise` advice constants (Linux `fadvise.h`): `NORMAL=0, RANDOM=1, SEQUENTIAL=2,
  WILLNEED=3, DONTNEED=4, NOREUSE=5`. The libc call returns **the error number directly**
  (`0` or `errno`), not `-1`+`errno`; `length == 0` means "to EOF" (POSIX; the kernel turns
  the range into `[offset, offset+len)` with `len = LLONG_MAX`). Only `EBADF`, `EINVAL`,
  `ESPIPE` are documented failures; `EINVAL` includes invalid advice and negative offset.
- Linux `O_DSYNC = 0x1000`, `O_SYNC = 0x101000` on x86-64/aarch64; both are already
  available as `LinuxOpenOptions.DSYNC` / `LinuxOpenOptions.SYNC`. A file opened with either
  has every write completed durably by the kernel; `fsync`/`fdatasync` by the caller is
  then redundant (seastar's `posix_file_impl::flush()` likewise returns immediately when
  `open_flags::dsync` is set).
- Seastar reference semantics (`SeaStarRef/src/core/file.cc:218-227`,
  `include/seastar/core/file.hh:97`): `file_open_options.durable` defaults to `true`;
  `file::flush()` issues `fdatasync` unless `durable == false` **or** the file is opened
  with `open_flags::dsync`, in which case it is a ready future. `fstream`'s output stream
  `flush()`/`close()` drains and calls the file `flush()`.
- B1 `JuringFile` in-flight ledger: `DEFAULT_IN_FLIGHT_CAPACITY = 1024`; every issued but
  unawaited operation occupies a slot; `ensureCapacity` throws
  `IllegalStateException` when full; `await(handle)`/`drain()` require that every in-flight
  ring operation belong to this handle (`checkRingOwnership`).
- The existing FFM libc dispatcher (`LibCDispatcher`) already owns downcalls with and
  without `Linker.Option.captureCallState("errno")`; `posix_fadvise` needs neither the
  capture-state option (it returns errno directly) nor an arena (no pointer arguments).
- `@TempDir` on tmpfs supports `O_DIRECT` in this environment (existing tests rely on it);
  stream tests also use buffered files for unaligned positions.

---

## 2. Frozen public API

All types live in the exported `com.davidvlijmincx.lio.api` package. Additive only:
existing methods/fields/records are unchanged; new option fields participate in
`equals`/`hashCode`/`toString` (B2 option classes are immutable value objects).

### 2.1 `FadviseAdvice` (new public enum)

```java
public enum FadviseAdvice {
    NORMAL(0),
    RANDOM(1),
    SEQUENTIAL(2),
    WILLNEED(3),
    DONTNEED(4),
    NOREUSE(5);

    public int value();
}
```

Frozen semantics:

- `value()` is the POSIX advice constant forwarded verbatim to `posix_fadvise(2)`.
- `NORMAL` is the **"no hint" sentinel for stream options**: a stream opened with
  `FadviseAdvice.NORMAL` issues **no** syscall (kernel default behaviour). Calling
  `JuringFile.fadvise(NORMAL, ...)` explicitly is allowed and forwards `0`.
- The enum is the argument-validation domain: no other advice value can be expressed; a
  `null` advice is `NullPointerException`.

### 2.2 `DurabilityMode` (new public enum)

```java
public enum DurabilityMode {
    WRITEBACK,
    FDATASYNC,
    FSYNC,
    DSYNC
}
```

Frozen semantics:

| mode | meaning | sync issued at a durability point |
|---|---|---|
| `WRITEBACK` | Completion means "accepted by the kernel"; durability is the caller's job. Today's default. | none |
| `FDATASYNC` | A durability point flushes file **data** (`fdatasync`). | exactly one `fdatasync` |
| `FSYNC` | A durability point flushes data **and metadata** (`fsync`). | exactly one `fsync` |
| `DSYNC` | The file is opened with `O_DSYNC` (`LinuxOpenOptions.DSYNC`/`SYNC`); every write completion is durable. A durability point needs no explicit sync. | none |

- In `JuringInputStream`/`JuringOutputStream`/`GroupCommit` contexts, `DSYNC` is a
  **contract assertion about the backing file**, not a mode that silently adds the flag:
  selecting `DSYNC` for a file whose `JuringFile.durability()` is not `DSYNC` throws
  `IllegalArgumentException` at stream/group-commit open (before any write).
- Selecting a non-`DSYNC` mode for an `O_DSYNC` file is allowed (the implicit syncs are
  redundant but harmless); `JuringFile.durability()` still reports `DSYNC`.
- `DurabilityMode` never changes SQE preparation or the ring ownership rules.

### 2.3 `JuringFile` additions (frozen)

```java
/** @return DSYNC when the file was opened with O_DSYNC/O_SYNC; WRITEBACK otherwise. */
public DurabilityMode durability();

/**
 * Issues posix_fadvise(fd, offset, length, advice.value()).
 * length == 0 means "to the end of the file" (POSIX).
 *
 * @throws IllegalArgumentException offset < 0 or length < 0
 * @throws NullPointerException     advice == null
 * @throws IllegalStateException    handle or ring closed
 * @throws JuringException          on a non-zero posix_fadvise return (typed errno)
 */
public void fadvise(FadviseAdvice advice, long offset, long length) throws JuringException;

/** Equivalent to fadvise(advice, 0, 0) — the whole file. */
public void fadvise(FadviseAdvice advice) throws JuringException;
```

Frozen semantics:

- `fadvise` is an **advisory** call: it never changes file content, stream position or
  ring state, and it is **not** counted in `issuedOperations()`/`completedOperations()`/
  `failedOperations()`/`controlOperations()` (it is not a ring request).
- It does **not** require the ring-ownership invariant of the blocking helpers (it does not
  touch the ring), but the handle-level threading rule still applies (one owner thread;
  callers that share the handle synchronize externally).
- `fadvise` after `close()` throws `IllegalStateException`; a non-zero return is mapped to
  `JuringException(errno, "fadvise", ...)` with `strerror` text. `length == 0` is legal and
  means to EOF.
- The existing B1 open paths are the only way to set `DSYNC`; B6 adds **no** new open
  overload. `durability()` is derived once at `open` from the combined flags (the `O_DSYNC`
  bit is set by both `LinuxOpenOptions.DSYNC` and `LinuxOpenOptions.SYNC`).
Additional JuringFile observability (frozen, additive; used to prove "one sync per batch"
independently of `GroupCommit`'s own counters, see §3.5):

```java
/** @return the configured maximum number of unawaited ring requests (the in-flight ledger capacity). */
public int inFlightCapacity();

/** @return fsync/fdatasync requests prepared through this handle (OperationType.FSYNC kind). */
public long syncOperations();

/** @return fsync/fdatasync completions reaped through this handle (successful or failed). */
public long syncCompletions();
```

Frozen semantics: `inFlightCapacity()` returns the value set by `withInFlightCapacity`
(default `DEFAULT_IN_FLIGHT_CAPACITY = 1024`); `syncOperations()`/`syncCompletions()` are
owner-thread `long` counters incremented where the B1 ledger tracks/completes an
`OperationType.FSYNC` request (they include syncs issued by streams/group commit through
this handle, and **not** fadvise). They do not extend B5's `RingMetrics` record (frozen at
18 components) and are not JMX metrics.

**Blocking-call caveat (documented):** `fadvise` is the first *blocking metadata syscall*
on `JuringFile` (open/close/calloc/strerror are the existing synchronous calls; all other
metadata operations are io_uring). `NORMAL`/`SEQUENTIAL`/`RANDOM`/`NOREUSE` are cheap;
`WILLNEED`/`DONTNEED` can do real work and must **not** be called from a B4 shard task
(§9.7).

### 2.4 `InputStreamOptions` additions (frozen)

```java
public FadviseAdvice fadvise();                         // default FadviseAdvice.NORMAL
public InputStreamOptions withFadvise(FadviseAdvice advice);
```

- `defaults()` keeps `fadvise() == NORMAL`; existing option values and existing frozen
  defaults are unchanged (`bufferSize 8192`, `readAhead 0`, `alignment 4096`,
  `dynamicAdjustments false`).
- `null` → `NullPointerException`. All other validation is unchanged.
- `JuringInputStream.open(file, options)` (all three overloads) applies the hint **once,
  after the file is validated and before the first read**: when
  `options.fadvise() != NORMAL`, it calls
  `file.fadvise(advice, offset, declaredLength <= 0 ? 0 : declaredLength)` where `offset`
  is the stream's declared window start (`0` for the until-EOF overload) and `0` length
  means to EOF. A `JuringException` from the hint propagates out of `open`; nothing else
  about the stream is affected (the hint does not alter read results, buffering, deadline
  or memory bounds).

### 2.5 `OutputStreamOptions` additions (frozen)

```java
public DurabilityMode durability();                      // default WRITEBACK
public FadviseAdvice fadvise();                          // default NORMAL
public OutputStreamOptions withDurabilityMode(DurabilityMode mode);
public OutputStreamOptions withFadvise(FadviseAdvice advice);
```

- `defaults()` keeps `durability() == WRITEBACK` and `fadvise() == NORMAL`, i.e. exactly
  the frozen B2 behaviour; `syncOnClose()` remains and keeps its B2 meaning.
- `null` → `NullPointerException`; validation otherwise unchanged. `equals`/`hashCode`/
  `toString` include both new fields.
- The **effective** durability of a stream is `durability()`; `syncOnClose` is the B2
  compatibility shortcut that requests one `fdatasync` on `close()` when
  `durability() == WRITEBACK` (see §3.2). There is no conflict: `durability()` governs
  every durability point; `syncOnClose` only adds the one B2 close sync.

### 2.6 `JuringOutputStream` additions (frozen)

```java
public DurabilityMode durability();   // configured mode (echo of the option)
public long syncs();                  // durability sync operations issued so far (valid after close)
```

- No other existing B2 signature changes. The stream still does not own/close the
  `JuringFile`.
- `JuringOutputStream.open(file, options)` applies the fadvise hint once, before the first
  write: when `options.fadvise() != NORMAL`, it calls `file.fadvise(advice, 0, 0)` (whole
  file; the stream writes from offset 0). A hint failure propagates out of `open`.
- `open` validates `durability() == DSYNC` against `file.durability() == DSYNC` and throws
  `IllegalArgumentException` on mismatch (message names both). Validation order: null
  checks → DSYNC/file-mode check → preallocation hint → fadvise hint (implementation free
  after the frozen observable outcomes: no write may be accepted when the DSYNC assertion
  fails).

### 2.7 `GroupCommit` (new public final class, `AutoCloseable`)

```java
public final class GroupCommit implements AutoCloseable {
    public static final int DEFAULT_MAX_BATCH_SIZE = 32;

    public static GroupCommit open(JuringFile file) throws JuringException;
    public static GroupCommit open(JuringFile file, GroupCommitOptions options) throws JuringException;

    public JuringFile file();
    public DurabilityMode durabilityMode();
    public int maxBatchSize();
    public int pendingWrites();                 // writes in the current (uncommitted) batch
    public long pendingBytes();                 // sum of their buffer lengths
    public void write(long offset, MemorySegment source) throws JuringException;
    public int commit() throws JuringException; // writes made durable by this call
    public GroupCommitStats stats();
    public void close() throws JuringException; // commits a pending batch; never closes the file
}
```

Frozen semantics (normative; algorithm free):

- **Open / validation.** `file` and `options` must be non-null (`NullPointerException`).
  The file must be open (`IllegalStateException`). `maxBatchSize()` must satisfy
  `1 <= maxBatchSize` and `maxBatchSize + 1 <= file.inFlightCapacity()`
  (`IllegalArgumentException` otherwise — one ledger slot per queued write **plus one
  reserved slot for the commit's sync op**; this makes the capacity invariant hold by
  construction). `durabilityMode() == DSYNC` requires `file.durability() == DSYNC`
  (`IllegalArgumentException`). `WRITEBACK` is allowed: it means "commit = complete the
  writes; issue no sync". The group allocates O(`maxBatchSize`) arrays at `open` and no
  ring/thread/arena.
- **`write(offset, source)`** queues one asynchronous write through `JuringFile`
  (`prepareWrite`) and returns **before** the write completes; the batch holds its handle
  and length. It does **not** submit, drain or sync. `pendingWrites() == maxBatchSize()`
  when called → `IllegalStateException` ("batch full; commit() first") — **there is no
  implicit auto-commit**; the caller decides when a batch ends (this keeps sync timing
  caller-controlled and makes "N writes → one commit → one sync" exactly checkable).
  `offset < 0`, a non-native/read-only segment, or a closed file/group fail exactly as in
  `JuringFile.prepareWrite`/`write` (`IllegalArgumentException` / `IllegalStateException`);
  a ledger-capacity or ring-level failure is an `IllegalStateException`/`JuringException`
  as in B1, latched per below.
  **Buffer contract:** the memory of `source` must remain valid and unmodified until the
  enclosing `commit()` (or `close()`) has returned — mirrors the B1 prepare contract. The
  group does not copy data.
- **`commit()`** makes the current batch durable:
  1. `pendingWrites() == 0` → returns `0`, issues **no** write and **no** sync (an empty
     commit is a no-op, not a batch).
  2. Otherwise it waits (in submission order) for **every** queued write to complete
     before it prepares the sync, so the sync is ordered after all batch writes (no
     `IOSQE_IO_DRAIN` seam is needed in v1). Then:
     - all writes succeeded and `durabilityMode()` is `FDATASYNC` → issues **exactly one**
       `JuringFile.fdatasync()`; `FSYNC` → exactly one `JuringFile.fsync()`; `WRITEBACK`/
       `DSYNC` → **no** sync;
     - increments `batches`, `committedWrites += n`, `committedBytes += sum(lengths)`,
       `maxObservedBatch = max(maxObservedBatch, n)`, and `syncs` by the number of sync
       operations successfully issued (0 or 1);
     - returns `n` (the batch size).
  3. If **any** write fails, the group drains the remaining queued writes (so no
     unowned in-flight operation is left behind), records `failedCommits++`, latches the
     first `JuringException`, and throws it. It does **not** issue a sync for a failed
     batch (the batch's durability is undefined; the failure is latched).
     A sync failure likewise increments `failedCommits`, latches and throws.
  4. Invariants a test/verifier may rely on, for every successful non-empty batch:
     `pendingWrites() == 0` afterwards, `Δfile.issuedOperations() == n + (sync ? 1 : 0)`,
     **`Δfile.syncOperations() == (mode in {FDATASYNC, FSYNC} ? 1 : 0)`** and
     `Δfile.syncCompletions() == Δfile.syncOperations()` after the batch.
- **`close()`** commits a pending batch (exactly as `commit()`) and then marks the group
  closed; it is idempotent and **does not close the `JuringFile`** (like B2 streams). A
  `close()` after a latched failure rethrows the latched `JuringException` (and still marks
  the group closed and releases its bookkeeping); a second `close()` is a no-op. After
  close, `write`/`commit` throw `IllegalStateException`; `stats()`/`pendingWrites()`/
  `pendingBytes()` remain readable (frozen).
- **Error latching.** The first `JuringException` from a write completion or sync latches;
  every subsequent `write`/`commit`/`close` rethrows that same exception object (B2-style).
  `IllegalStateException` for use-after-close is not latched.
- **Bounded memory / allocation.** `open` allocates O(maxBatch) handle/length arrays once;
  steady-state `write`/`commit` allocate **0 bytes** (no `System.nanoTime`, no boxing, no
  streams). `stats()` allocates a fresh immutable record (off the hot path). The
  asynchronous write path uses the B1 ledger; no extra ring/thread/arena is created.
- **Threading/ownership.** Owner thread only, exactly like `JuringFile` (B1 rule: no
  foreign in-flight work on the ring while the group's blocking helpers run); `write` may
  leave up to `maxBatch` operations in flight, so a caller must not mix the group with
  other ring users on the same ring.

### 2.8 `GroupCommitOptions` (new public final immutable class)

```java
public static GroupCommitOptions defaults();      // FDATASYNC, maxBatchSize 32
public DurabilityMode durability();               // default DurabilityMode.FDATASYNC
public int maxBatchSize();                        // default 32, range 1..4096
public GroupCommitOptions withDurabilityMode(DurabilityMode mode);
public GroupCommitOptions withMaxBatchSize(int maxBatchSize);
```

Validation: `null` → `NullPointerException`; `1 <= maxBatchSize <= 4096`
(`IllegalArgumentException`); the `maxBatchSize + 1 <= file.inFlightCapacity()` and
`DSYNC` checks happen in `GroupCommit.open` because they need the file.
`equals`/`hashCode`/`toString` included.

### 2.9 `GroupCommitStats` (new public record)

```java
public record GroupCommitStats(
        long batches,            // successful non-empty commit() calls
        long committedWrites,    // writes made durable by successful commits
        long committedBytes,     // sum of their buffer lengths
        long syncs,              // successful fdatasync/fsync operations issued
        long failedCommits,      // commit calls that threw (write or sync failure)
        int maxObservedBatch     // largest committed batch seen (0 before any commit)
) {}
```

Frozen invariants: all components non-negative and monotone non-decreasing except
`maxObservedBatch` (monotone non-decreasing, bounded by `maxBatchSize()`);
`committedWrites >= batches` for non-empty batches; `syncs <= batches` with equality iff
`durabilityMode()` is `FDATASYNC`/`FSYNC` and every batch succeeded;
`pendingWrites() + committedWrites` = number of `write` calls accepted (after a failed
commit it may count writes of the failed batch as neither — the failure is latched, so
counters are frozen from then on).

---

## 3. Semantics (frozen)

### 3.1 Durability points and sync accounting

A **durability point** is a caller-visible operation after which the mode's guarantee
holds for everything accepted before it:

| API | durability point? | WRITEBACK | FDATASYNC | FSYNC | DSYNC |
|---|---|---|---|---|---|
| `JuringOutputStream.flush()` | yes | drains only | drains + 1 fdatasync | drains + 1 fsync | drains only |
| `JuringOutputStream.close()` | yes | flush + (syncOnClose ? 1 fdatasync : 0) | flush (1 fdatasync total) | flush (1 fsync total) | flush only |
| `GroupCommit.commit()` | yes | writes complete | 1 fdatasync | 1 fsync | writes complete |
| `JuringFile.fadvise` | no (advisory) | — | — | — | — |

- `JuringOutputStream.flush()` keeps its frozen B2 behaviour for `WRITEBACK`: it writes the
  pending partial buffer, waits for all in-flight write-behind operations, undoes alignment
  padding via truncate, and returns. With `FDATASYNC`/`FSYNC` it then issues exactly one
  sync before returning (and increments `syncs()` on success). Continuing to write after a
  flush keeps the B2 rule (only at an alignment-aligned position).
- `close()` performs exactly **one** flush (hence at most one mode sync) and is idempotent.
  It releases buffers/arena only when it is safe (B2 rule); a failed sync is latched first
  and rethrown after the close bookkeeping (B2 rule).
- `DSYNC` streams/groups never call `fdatasync`/`fsync` themselves; `syncs()` stays 0. The
  durability guarantee is the kernel's `O_DSYNC` write completion. The B1 rule that a
  write's completion means "the kernel accepted it" is unchanged; `O_DSYNC` makes the
  kernel's completion the durable one.
- `syncs()` counts *successful sync operations* issued by the stream (0/1 per point); it is
  valid after `close()` and is not reset by anything.
- **No implicit sync on `JuringFile` operations.** `JuringFile` keeps its B1 contract:
  `write()` completion is acceptance; `fsync()`/`fdatasync()` are explicit. B6 only adds
  the derived `durability()` report and the fadvise helper.

### 3.2 Stream durability mode vs `syncOnClose` (B2 compatibility)

- `durability() == WRITEBACK` reproduces B2 exactly: no sync in `flush()`, one `fdatasync`
  in `close()` iff `syncOnClose()`.
- `durability() == FDATASYNC`/`FSYNC` implies a sync at **every** durability point (flush
  and close), independent of `syncOnClose`; `syncOnClose` is then redundant and does not
  add a second sync.
- `durability() == DSYNC` has no explicit syncs; `syncOnClose` is redundant.
- `syncOnClose()` accessor and B2 option semantics are unchanged (existing tests untouched).

### 3.3 Visibility and restart contract

- `GroupCommit.commit()` returning normally means every write of the batch completed and,
  for `FDATASYNC`/`FSYNC`, the corresponding sync completed. A subsequent open of the same
  path (same or new process, same or new ring) reads the committed bytes (subject to the
  documented `fdatasync` metadata scope: data and the metadata needed to retrieve it).
- For `WRITEBACK`/`DSYNC`, commit's visibility guarantee after restart is exactly what the
  mode provides (writeback: page cache survives process restart but not power loss; DSYNC:
  kernel durability).
- B6's restart test (§7.5) kills a child process abruptly **after** a successful
  `FDATASYNC` group commit and verifies the bytes from a fresh parent process. It is a
  process-restart test, not a power-loss test (§0 non-goals).

### 3.4 fadvise and O_DIRECT

`posix_fadvise` controls page-cache behaviour; on an `O_DIRECT` file the hints are mostly
inert (data bypasses the page cache). B6 documents this and applies hints regardless (the
kernel accepts them); the fadvise evidence is functional (advice forwarded, no error), not
a throughput claim. No benchmark arm depends on fadvise for a verdict; the W24 arms are
durability arms and run with `fadvise = NORMAL` (recorded in the JSON).

### 3.5 Proof observables (frozen; used by tests, bench JSON and the verifier)

| claim | observable |
|---|---|
| one fsync per committed batch | `GroupCommitStats.syncs` and `ΔJuringFile.syncOperations()` per `commit()` (exactly 1 for `FDATASYNC`/`FSYNC`, 0 otherwise) |
| sync actually completed (not just issued) | `ΔJuringFile.syncCompletions()` equals the issued delta after the batch |
| no sync for an empty commit / writeback / dsync | those deltas are 0 |
| the sync is ordered after the writes | commit awaits every write before preparing the sync; `ΔissuedOperations() == n + syncs` |
| stream durability point | `JuringOutputStream.syncs()` plus the same `JuringFile.syncOperations()` delta |
| data visible after process restart | child process writes + commits + dies; fresh parent re-reads the bytes; W25 bench also has a fresh-process `--verify-only` mode on the device-backed file |
| one fsync for a batch in the W25 JSON | `syncs_total / batches_total == 1.0` and `batches_total * batch_size == committed_writes` |

---

## 4. Implementation notes (non-frozen, correctness-relevant)

- **`LibCDispatcher`** gains one downcall:
  `MH_POSIX_FADVISE = linker.downcallHandle(lookup.findOrThrow("posix_fadvise"),
  FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT))` and a package
  method `int posixFadvise(int fd, long offset, long length, int advice)` that returns the
  call result **as-is** (errno, not -1). Do not use `captureCallState` here; do not allocate
  an arena. This is the same file the B1/B4 code already extends; no new dependency.
- **`JuringFile`** stores the combined open flags (`private final int openFlags`) and
  derives `durability()` from bit `0x1000` (`O_DSYNC`; `SYNC = 0x101000` sets it too).
  `fadvise` calls `ensureOpen("fadvise")` and then the dispatcher; a non-zero return becomes
  `new JuringException(ret, "fadvise", NativeDispatcher.C.strerror(ret) + " (advice=" +
  advice + ", offset=" + offset + ", length=" + length + ")")` or the established
  `JuringException` helper shape. `inFlightCapacity()` is a package-private one-liner.
- **`JuringOutputStream`**: the sync lives in `flushInternal()` (or an equivalent
  post-drain step) so `close()`'s single internal flush is the only sync point; do not call
  the public `flush()` recursively. `syncs` is a plain `long` (owner thread). `open()` does
  the DSYNC assertion before preallocation/fadvise so a mismatch never touches the file.
- **`JuringInputStream`**: apply the hint in every `open` overload exactly once; the
  until-EOF overload passes `(offset=0, length=0)`; the windowed overload passes the
  declared window (if `length < 0`, use `0` for "to EOF"). Hints never change the frozen
  prefetch/window contract.
- **`GroupCommit`** uses `JuringFile.prepareWrite` + `await` and `JuringFile.fdatasync()`/
  `fsync()`; it does not touch the ring internals. `write` must not submit-then-wait; the
  first `await` in `commit` drives the ring submission of the whole prepared batch, then
  the remaining `await`s reap. On failure, keep draining remaining handles before latching
  (the ledger must not be left with unawaited slots; `close()`/`JuringFile.close()` would
  otherwise drain them, but the group should not leak them on a normal error path either).
  The sync can only be issued after the last write slot is consumed (capacity invariant,
  §2.7).
- **`GroupCommitOptions`/`GroupCommitStats`/`FadviseAdvice`/`DurabilityMode`** are small
  immutable types in the existing style (`Objects.requireNonNull`, explicit
  `IllegalArgumentException` messages, `equals`/`hashCode`/`toString` where the class is an
  option/value object; the record gets the default record methods).
- **No per-call heap allocation** in `GroupCommit.write/commit` steady state, no
  `System.nanoTime()` in the commit hot path, no `String` concatenation on success paths.

---

## 5. Threading and ownership (frozen)

- `JuringFile`, `JuringInputStream`, `JuringOutputStream` and `GroupCommit` are **not
  thread-safe**; one owner thread per ring/handle (B1 rule). `GroupCommit.write` leaves up
  to `maxBatch` operations in flight on the file's ring, so the handle's ring-ownership
  rule applies at commit/close time: the ring must not carry foreign in-flight work.
- `JuringFile.fadvise` does not touch the ring, so it is exempt from
  `checkRingOwnership`; it still requires an open handle and is documented as owner-thread
  use (callers sharing a handle synchronize externally).
- No new threads, rings, arenas or executors are created by B6; no `ThreadLocal`s; no
  background sync thread. The group commit is a caller-driven batch, not a daemon.

---

## 6. Benchmark integration contract (frozen)

**Principle.** M2's W21/W22 buffered-stream comparison is cited as signed evidence (PASS).
B6 adds a **durability** protocol (W24) and a **group-commit** micro protocol (W25) with
matched arms on both engines. All B6 sessions use the existing bench conventions: one fresh
process per invocation, one JSON object on stdout, `--json` file, diagnostics on stderr,
engine/arm-interleaved order alternating per rep, every rep kept, results under the
gitignored `bench/results/`, protocol recorded in `manifest.txt` + `cell-meta.tsv`.

**M2 asymmetry disclosure (frozen report obligation).** The signed M2 W21 write campaign
compared a seastar arm that issued **2 `fdatasync` per pass** (default
`file_open_options.durable = true`; `s.flush()` and `s.close()` each reach
`file::flush()` → `fdatasync`, `seastar_stream_bench.cc:368,375`,
`SeaStarRef/src/core/fstream.cc:499-508`, `iostream-impl.hh:571-586`,
`file.cc:217-227`) with a JUring arm that issued **0** (`syncOnClose = false`, no
`O_DSYNC`). `docs/m2-report.md` does not state this; B6's report must. Consequences: M2's
write ratios are *not* evidence that durability is free; B6's W24 arms **remove** this
asymmetry (matched durability settings), and a **writeback-vs-writeback control** is run
before any durability cost is quoted.

### 6.1 Harness changes (`bench/juring/JUringStreamBench.java`)

Additive CLI (all defaults preserve current behaviour):

```
--durability writeback|fdatasync|fsync|dsync   (default: writeback)
--sync-every BYTES                              (default: 0 = only at pass end)
--fadvise normal|random|sequential|willneed|dontneed|noreuse  (default: normal)
```

- `writeback` = `OutputStreamOptions.durability() == WRITEBACK, syncOnClose = false`
  (today's arm). `fdatasync`/`fsync` = the matching mode. `dsync` = open the `JuringFile`
  with `LinuxOpenOptions.DSYNC` **and** set `durability() == DSYNC`.
- `--sync-every S > 0` (write workload only): after every `S` bytes accepted within a pass,
  call `JuringOutputStream.flush()` — a durability point per §3.1 (drain only for
  writeback/dsync, drain + one sync for fdatasync/fsync). `S` must be a multiple of `--bs`
  and of 4096; the pass-end `flush()` + `close()` still happens. Read workload → usage
  error.
- `--fadvise X` sets the stream hint (informational; no verdict uses it).
- JSON: **keep `schema_version "1.0"`** (frozen by `docs/b2-design.md` §3.1 and required by
  the M2 artifact/tooling; do **not** bump — the shard/file tooling hard-asserts versions,
  `bench/shard/run-b5-overhead.sh:752-756`). Add **additively**: top-level
  `"schema_extensions": ["b6-durability-1"]`; `config.durability`,
  `config.sync_every_bytes`, `config.fadvise`; per-run `durability_mode`,
  `sync_every_bytes`, `passes`, `syncs_issued`, `syncs_completed`,
  `sync_wait_us{samples,avg,p50,p90,p99,max}` (reservoir over the durability-point calls
  only), `sync_wait_share` (Σ sync-call time / measured elapsed), `bytes_synced`.
  Existing fields keep their meanings and the currently shipped analyzers ignore unknown
  keys (`analyze_stream.py` is not edited — see §6.5).

### 6.2 Harness changes (`bench/seastar/seastar_stream_bench.cc`)

Additive CLI:

```
--durability writeback|fdatasync|dsync   (default: fdatasync = current default behaviour)
--durable 0|1        (compatibility spelling of the above for pure writeback)
--dsync 0|1          (adds open_flags::dsync; equivalent to --durability dsync)
--sync-every BYTES   (default: 0 = only at pass end)
```

- `writeback` → `file_open_options.durable = false` (file::flush() becomes a no-op;
  `open_file_dma(path, flags, opts).durable=false`); `fdatasync` → seastar's default
  (`durable = true`: every flush = exactly one fdatasync, **never fsync**);
  `dsync` → `open_flags::dsync` (writes durable on completion; flush no-op). Reject
  `fsync` with a clear error (seastar has no fsync path).
- `--sync-every S > 0` (write only): `co_await s.flush()` after every `S` bytes within a
  pass (in addition to the pass-end flush/close); same multiple-of-`--bs` rule. Seastar's
  stream flush **drains write-behind and then calls file::flush()**, so the arm is a
  "durability point incl. drain" (disclosed; §9.6).
- JSON: keep `schema_version "1.0"`, add `"schema_extensions": ["b6-durability-1"]`;
  `config.durability`, `config.sync_every_bytes`, `config.aio_fsync` (record whether the
  seastar reactor's aio-fsync is on; pin `--aio-fsync 1` in the driver so the flush path is
  `IORING_OP_FSYNC|IORING_FSYNC_DATASYNC`, not a blocking thread-pool `::fdatasync`); per-run
  `passes`, `syncs_issued`, `syncs_completed`, `syncs_failed`, `bytes_synced`,
  `sync_wait_us{...}`, `sync_wait_share`. `syncs_issued` counts the durability-point calls
  the bench makes (seastar does not export `reactor::io_stats.fsyncs`); it is the
  cross-engine normaliser.

### 6.3 W24 durability protocol (`bench/stream/run-durability-ab.sh` + `analyze_durability.py`)

**Supporting evidence only — not a milestone bar.** One write cell, same file/protocol
conventions as M2:

| item | value |
|---|---|
| cell | `W24-128k`: `stream_write`, bs 128 KiB, qd 8, `--size 2g` on the existing 10 GiB write file (`nvme-data/write_files/single_file_bench_w.bin`; the file is never resized — dataset deviation from the frozen 10 GiB is disclosed) |
| Tier 1 arms (decisive, 4) | `juring-writeback`, `juring-fdatasync` (both `--sync-every 4M`), `seastar-writeback`, `seastar-fdatasync` (both `--sync-every 4M`) |
| Tier 1 protocol | R=3; **300 s idle rest before each rep**, then the 4 arms run **back-to-back inside that post-rest window** in an order alternating every rep (rep 1 `Jw,Jd,Sw,Sd`; rep 2 `Sd,Sw,Jd,Jw`; rep 3 `Jw,Jd,Sw,Sd`); per arm **5 s warmup + 15 s measured**, one fresh process per invocation, `taskset -c 2`; the JUring writeback arm brackets the block (first and last overall) as a state control |
| Tier 2 arms (exploratory) | `juring-dsync` / `seastar-dsync` (R=2, 120 s rests); optional `juring-fsync` (`--durability fsync`, `--sync-every 4M`) reported **JUring-only** with the asymmetry disclosed (seastar cannot fsync via fstream) |
| analysis | new `analyze_durability.py`: per-arm MiB/s, `syncs_issued`/s, MiB/sync, `sync_wait_share`, sync-latency p50/p99; **within-rep paired durability-cost ratio** (durable vs writeback, per engine, 95 % CI over reps); run-state class per rep (rule fixed in the analyzer before the campaign, derived from M1 §4 + M2 §6: per-arm `fast` iff MiB/s ≥ 550 and write p50 ≤ 250 µs, `slow` iff MiB/s < 300 or p50 > 600 µs, else `mixed`; M1 0.92 same-cell rule also applied); class counts, n and CV printed |
| disclosure rule | arms are ranked only if both sides have ≥ 2 reps in the same class (else INCONCLUSIVE); an arm with CV > 10 % is "unstable, no ranking" (methodology D7); the two writeback controls (bracketing) must agree within 10 %, otherwise the session is state-confounded and only the raw table is reported |
| wall time | Tier 1 ≈ 3 × (300 + 4 × 20) = 19 min; Tier 2 ≈ 5–6 min; preflight/analysis ≈ 2 min ⇒ **≈ 26–30 min** |

The matched arm pair differs only in the sync at each 4 MiB durability point (`writeback` =
drain only, `fdatasync` = drain + one fdatasync) on both engines, so each rep yields a
within-position estimate of the **cost of durability**, not the cost of a pass-boundary
drain bubble. `--sync-every 0` pass-end arms are not used in W24 for the decisive numbers
(they yield only 1–2 sync events per 15 s window; if quoted at all they are labelled
"pass-boundary durability cost"). The M2 W21/W22 comparison is **not** re-run.

### 6.4 W25 group-commit micro protocol (`bench/juring/JUringDurabilityBench.java` + driver/analyzer)

Dedicated JUring runner (does not change the stream/file runners) that proves the group-commit
contract and measures its value:

- CLI: `--file PATH`, `--batch B` (1/8/32/128), `--records N` or `--seconds`, `--bs 4096`,
  `--durability fdatasync|fsync|writeback` (default fdatasync), `--warmup-sec`,
  `--seconds`, `--runs`, `--json PATH`, `--label`, and a `--verify-only` mode that reopens
  the file from a fresh process and re-reads/validates the written pattern.
- Workload: preallocate the file (e.g. 1 GiB, `--bs`-aligned); for each group submit exactly
  `B` `GroupCommit.write` calls at deterministic aligned offsets (record pattern), then one
  `commit()`; a `--verify-only` fresh-process run re-reads `[0, committed_bytes)` and checks
  the pattern. No `GroupCommit`-internal hook is used — the bench calls the **public API**.
- JSON: keep `schema_version "1.0"` + `"schema_extensions": ["b6-durability-1"]`; per-run
  `batch_size`, `groups_total`, `committed_writes`, `syncs_issued`, `syncs_completed`,
  `syncs_failed`, **`fsyncs_per_group`** (= `syncs_completed / groups_total`, must be
  exactly `1.0` for non-empty groups), `writes_per_group{min,mean,max}`, `bytes_synced`,
  `sync_latency_us{...}`, `sync_wait_share`, `restart_verify{ok,verified_bytes,method,
  power_cut_tested:false}`, plus MiB/s and p99 group latency.
- Cells: `W25-4k-b1`, `b8`, `b32`, `b128`; R=3, warmup 5 s, measured 15 s, **120 s rests**
  between reps (larger rests are unnecessary: this is a count/latency micro-benchmark, not a
  throughput campaign; disclose). Estimated **2–4 min** total. Seastar has no group-commit
  primitive, so W25 is **JUring-only**: the seastar reference for the cost of a batched sync
  is the W24 `seastar-fdatasync` arm (which pays a write-behind drain the JUring helper does
  not — disclosed as an upper bound).
- The machine-checkable acceptance identities are: `fsyncs_per_group == 1.0`,
  `groups_total × batch_size == committed_writes` (when records divide evenly; otherwise the
  last group is short and `committed_writes == records`), and `restart_verify.ok == true`
  from a fresh process.

### 6.5 Frozen session/analysis expectations

- Sessions are archived at `bench/results/b6-durability-<date>/` and
  `bench/results/b6-group-commit-<date>/` (gitignored) and cited by the report; the report
  keeps the tables and exact commands. Session layout mirrors M2 (`cells/`, `logs/`,
  `aggregate/`, `manifest.txt`, `cell-meta.tsv`); new sync data lives in the JSON (no new
  columns are added to the positional 21-column `cell-meta.tsv` for compatibility).
- Every invocation records `status`, `errors`, argv, kernel, git commit (dirty flag), CPU,
  governor, device/mount and the protocol; no invocation is silently dropped or trimmed
  (all reps kept; failed reps are re-run with disclosure).
- `analyze_stream.py` and the M2/M3 artifacts stay **byte-identical**; the new analyzers are
  separate Python 3 stdlib-only scripts that require `schema_extensions` to contain
  `b6-durability-1` (fail loudly on a mixed/old session) and emit `aggregate/*.json` +
  `aggregate/*.md`.
- The M2 W21/W22 comparison is NOT re-run; the report cites `docs/m2-report.md` and
  `bench/results/m2-final/`, discloses the durability asymmetry (§6 preamble), and quotes
  no new cross-engine write win/loss (methodology D7, all-reps/CV discipline).

## 7. Test contract (acceptance matrix — authors + verifier)

All tests are new `B6*Test.java` classes in `src/test/java/com/davidvlijmincx/lio/api/`
(JUnit 5, same style as B4/B5). Existing test files are byte-identical at freeze.

1. **`B6FadviseTest`** — enum constants/values/`value()`; null advice NPE; negative
   offset/length IAE; closed-handle ISE; `fadvise(advice)` convenience equals
   `(advice, 0, 0)`; every advice value succeeds on a real temp file (read-only and
   read-write, O_DIRECT and buffered); `fadvise` does not change file length/content and
   does not change `issuedOperations()`; stream `open` with `withFadvise` applies the hint
   for all three input overloads and the output stream, and read/write results are
   unchanged versus `NORMAL`; `NORMAL` issues no hint (observable: no exception and no
   counter change; white-box call-count check is allowed via a test-visible seam if the
   authors add none — the verifier may instead assert the frozen "NORMAL = no syscall"
   contract by review).
2. **`B6DurabilityModesTest`** — `JuringFile.durability()` is `WRITEBACK` for normal opens,
   `DSYNC` for `LinuxOpenOptions.DSYNC` and `SYNC` (alone and combined with
   `READ`/`WRITE`/`DIRECT`); `O_DSYNC` write then reopen shows the bytes; existing
   `fsync()`/`fdatasync()` still behave; `durability()` after close is still readable
   (frozen report value) or throws ISE per implementation — contract: readable before
   close, documented; choose "readable while open, ISE after close" if observed.
3. **`B6StreamDurabilityTest`** — `OutputStreamOptions` defaults/round-trips/equals/
   hashCode/toString for the two new fields; `withDurabilityMode(DSYNC)` on a non-DSYNC
   file → IAE at open; `withDurabilityMode(DSYNC)` on a DSYNC file → `syncs() == 0`
   across writes/flush/close and content visible; `WRITEBACK`: flush/close issue 0 syncs
   (unless `syncOnClose`), content visible; `FDATASYNC`: one sync per `flush()` and one in
   `close()` (count `syncs()` **and** `Δfile.syncOperations()`/`Δfile.syncCompletions()`,
   plus `Δfile.issuedOperations()` = writes + syncs); `FSYNC`: same count with the sync op
   count; `syncOnClose(true)` + WRITEBACK: 0 sync at flush, 1 at close; failed sync
   latching is covered by the existing B2 failure tests (no new injection seam required);
   writes after a durability flush still work at aligned positions; stream fadvise applied
   at open (all three input overloads + output).
4. **`B6GroupCommitTest`** — open validation (null file/options; closed file;
   `maxBatchSize < 1`, `maxBatchSize + 1 > inFlightCapacity`, DSYNC mismatch; defaults);
   batch-full ISE (`write` when `pendingWrites() == maxBatchSize` throws and does not
   submit; `commit` then works); basic batch (`maxBatchSize 8`: 8 writes + commit → 1
   batch / 1 sync / 8 writes / 8×size bytes; `Δfile.syncOperations() == 1`; empty commit
   afterwards returns 0 and issues no sync; reopen shows all bytes); explicit partial
   commit (5 writes + commit → 5/1/1, second commit 0); two batches (3+2 → batches 2,
   syncs 2, writes 5); `WRITEBACK` mode → writes complete, syncs 0, `ΔsyncOperations() ==
   0`, content visible; `DSYNC` mode requires DSYNC open and issues 0 explicit syncs;
   `FSYNC` mode → syncs 1/batch; `ΔissuedOperations` invariant; `pendingBytes`/
   `maxObservedBatch`; use-after-close ISE; close commits a pending batch (one sync) and
   does not close the file; close idempotent; failed write (read-only file) latches and
   poisons subsequent calls (`failedCommits`); stats invariants; allocation-free steady
   state (ThreadMXBean, 0 B/write/commit; allow the documented one-off JIT caveat only if
   reproducible); `write` buffer-lifetime contract documented (no test can prove it; the
   verifier documents the contract).
5. **`B6GroupCommitCrashTest`** — child-JVM restart/visibility: a helper `main` (nested
   class in the test or a test-utility class on the test classpath) opens a file in a
   `GroupCommit` (FDATASYNC), writes K deterministic records, commits, prints a `DURABLE`
   marker with the byte length and the `stats()` JSON, then dies abruptly
   (`Runtime.halt(137)` / SIGKILL) so no further flush/close runs. The parent JUnit test
   waits for the marker/exit and reopens the path with a **fresh `JUring` + `JuringFile`**
   and verifies every record byte-for-byte and the file length. Also assert the child's
   `stats().syncs() == stats().batches()` (one sync per batch). The test uses `@TempDir`
   (tmpfs): it proves **process-restart visibility**, not media durability (disclosed in
   the report); the device-backed fresh-process proof lives in the W25 bench
   (`--verify-only`). Guard: skip with an explicit JUnit `Assumptions.assumeTrue`
   (documented) only if the environment cannot spawn a JVM; the canonical sandbox must run
   it.
6. **Cross-cutting** — full `mvn -o test`: baseline 349 (unchanged, byte-identical test
   files) + all new B6 tests, **0 failures, 0 errors, 1 skipped**; no fd/thread/arena
   leaks (existing patterns); no new compiler warnings policy change; `mvn -o -q
   -DskipTests package` clean.

**Verifier matrix (independent, `scratch/b6-verify/**`).** Own byte-identical copy of the
frozen tree, own compiled classpath (never `target/classes`), fresh JVM per mode with
external timeout; independently re-derive: every §2 signature/validation/default; the §3.1
sync-count table via *two independent observables* (`JuringOutputStream.syncs()` /
`GroupCommitStats.syncs()` **and** `ΔJuringFile.syncOperations()` /
`ΔsyncCompletions()`), with `ΔissuedOperations()` as the ordering cross-check; `NORMAL`
fadvise issues no syscall and every other advice is forwarded (the verifier may use
`perf trace -e trace=fadvise64,posix_fadvise` where available, else the frozen code +
the functional no-error assertions); group-commit batch/sync/bytes invariants for
adversarial batch sizes, empty commits, writeback/dsync modes and failure injection; the
child-JVM restart test re-implemented from the contract (not copied); a from-scratch
recomputation of the W24/W25 aggregate tables from the raw session JSON (including
`fsyncs_per_group == 1.0`, `syncs_issued == durability points`, and the paired
durability-cost ratios/state classes); and a full independent suite run. Findings must be
classified (contract-wording vs defect) and any amendment must be a Lead append, never a
silent edit.

---

## 8. Error taxonomy (frozen)

- `NullPointerException`: null `file`, `options`, `advice`, `source`.
- `IllegalArgumentException`: negative offset/length (fadvise), `offset < 0` or invalid
  segment (`GroupCommit.write`, delegated to B1), `maxBatchSize < 1` /
  `maxBatchSize > 4096`, `maxBatchSize + 1 > file.inFlightCapacity()`, `DSYNC` mode on a
  non-DSYNC file, DSYNC stream option mismatch.
- `IllegalStateException`: closed handle/stream/group; ring closed; foreign in-flight work
  on the ring (B1); `write`/`commit` after `close`; batch full on `write` ("commit()
  first"). B1 `ensureCapacity` also throws `IllegalStateException` (in-flight capacity
  exhausted); the group's `maxBatchSize + 1 <= capacity` guard prevents reaching it in the
  normal path.
- `JuringException` (checked, typed errno): fadvise non-zero return; any write completion
  failure; sync failure; all are latched by `GroupCommit` where noted. No new errno codes
  are invented; `strerror` text is used as today.
- `UnsupportedOperationException`: unchanged B1/B2 cases (e.g. missing FTRUNCATE/STATX);
  B6 adds none.

---

## 9. Known limitations / residual risks (disclosure)

1. **Durability is only as strong as `fdatasync`/`fsync`/`O_DSYNC` + the device cache
   policy.** No power-loss test; "restart" = process restart (§3.3). The canonical JUnit
   crash test runs on `@TempDir` (tmpfs), so it proves **process-restart visibility**, not
   media durability; the device-backed fresh-process re-read is the W25 bench
   (`--verify-only` on `nvme-data`), and the "one sync per batch" claim is proven by the
   counters (§3.5), not by the tmpfs file.
2. **`GroupCommit` is single-owner and caller-driven**; it is not a database WAL writer, a
   daemon, or a concurrent group-commit implementation. Under a single-threaded caller it
   batches N writes into one sync; cross-thread batching is out of scope (B4 territory).
3. **`GroupCommit` requires the caller to submit sequentially** (one thread), to `commit()`
   before the batch is full, and to keep each source buffer valid until commit; the API
   documents this and does not copy. A call that needs a deadline is not expressible in
   v1 (no commit timeout).
4. **No `commit()` timeout**: a hung device hangs a commit exactly like `JuringFile.write`;
   callers use the B1 timed prepare/await path if they need deadlines.
5. **`fadvise` is advisory**, mostly inert on `O_DIRECT` files, and is the first blocking
   metadata syscall on `JuringFile`. `NORMAL`/`SEQUENTIAL`/`RANDOM`/`NOREUSE` are cheap;
   `WILLNEED`/`DONTNEED` can block and must not be called from a B4 shard task (a shard's
   armed eventfd wakeup makes `JuringFile` blocking helpers throw anyway). Failure mapping
   is only reachable for invalid descriptors/advice; the argument domain is validated by
   the API and the enum.
6. **M2 write-arm asymmetry disclosure** (§6 preamble): seastar's M2 `fstream` issued
   **2 `fdatasync` per pass** (flush + close) while the M2 JUring arm issued 0; M2 does not
   disclose this. B6's W24 arms match durability settings and run writeback-vs-writeback
   controls; B6 makes **no new cross-engine write win/loss claim**, and the M2 write ratios
   must not be quoted as "durability is free" evidence.
7. **The durability A/B measures durability points that include a write-behind drain**
   (B2 `flush()` and seastar `fstream::flush()` both drain). The matched arms isolate the
   sync cost (writeback = drain only vs fdatasync = drain + one sync), but the absolute
   `sync_wait_share` is an upper bound on pure sync cost; seastar's `flush()` has no
   fsync(2) path, so the `fsync` arm is JUring-only (disclosed).
8. **Write-state bimodality** of the host drive (M1/M2/M3) limits W24 to within-engine
   paired ratios with disclosed run-state classes and position-matched strata; the raw
   session is the evidence, the report tables are descriptive; CV > 10 % ⇒ "unstable, no
   ranking" (methodology D7). The W24 dataset is 2 GiB per pass, not the frozen 10 GiB
   (disclosed deviation; the file is never resized).
9. **`syncs()`/`GroupCommitStats`/`JuringFile.sync*` are owner-thread counters**, not JMX
   metrics (B5 scope ends at ring/shard/core metrics); they are frozen after close.
10. **Stream `DSYNC` mode is an assertion, not a flag-applier**: the caller must open the
    file with `LinuxOpenOptions.DSYNC`; a mismatch fails fast by design.

---

## 10. Acceptance / definition of done

1. `docs/b6-design.md` (this file) frozen before implementation; deviations via Lead
   amendments appended, never silent edits.
2. All §2 APIs implemented exactly; all §7 JUnit tests green; **full `mvn -o test`:
   349 existing (byte-identical) + new, 0 failures, 0 errors, 1 skipped**.
3. fadvise works for every advice on a real file; durability modes tested (WRITEBACK /
   FDATASYNC / FSYNC / DSYNC) with the §3.1 sync-count table; group-commit proof:
   **one fsync per batch** (stats + operation-delta invariant) and **correct visibility
   after process restart** (child-JVM crash test).
4. W24 durability session and W25 group-commit session archived (gitignored) with driver,
   analyzer, `aggregate/*.md|json`, manifest/cell-meta; report cites exact commands and
   per-rep data; M2 W21/W22 cited for the buffered comparison (with the §6 preamble
   durability-asymmetry disclosure); no unsupported claim.
5. Independent verification **PASS** on the frozen tree, with
   `scratch/b6-verify/VERDICT.md` and frozen hashes.
6. `docs/b6-report.md` (deliverable), `docs/HANDOVER.md` new §0.0 (B6 status),
   `SEASTAR-PLAN.md` v1.6 (B6 delivered) updated, and the pointer-only "B6 addendum"
   appended to `docs/b1-design.md` §8 and `docs/b2-design.md` §8.
7. Git: feature commit (code/tests/bench/design) + docs commit (report/handover/plan),
   branch `kafka-engine`, no unrelated changes; final Lead suite green on the committed
   tree. **B6 does not change M1/M2/M3 sign-off status** (M2 remains the signed buffered
   comparison; B6 is the durability follow-up).

---

## 11. Write scopes and parallel plan (advisory; disjointness is the rule)

| Workstream | Write scope |
|---|---|
| **b6-file** | `src/main/java/com/davidvlijmincx/lio/api/{FadviseAdvice,DurabilityMode,JuringFile,LinuxOpenOptions}.java` (LinuxOpenOptions javadoc only), new `src/test/java/.../B6FadviseTest.java`, `B6DurabilityModesTest.java`; `scratch/b6-file/**` |
| **b6-stream** | `src/main/java/.../{GroupCommit,GroupCommitOptions,GroupCommitStats,InputStreamOptions,JuringInputStream,OutputStreamOptions,JuringOutputStream}.java`; new `src/test/java/.../B6GroupCommitTest.java`, `B6GroupCommitCrashTest.java`, `B6StreamDurabilityTest.java`; `scratch/b6-stream/**` |
| **b6-bench** | `bench/juring/{JUringStreamBench,JUringDurabilityBench}.java`, `bench/seastar/seastar_stream_bench.cc`, `bench/stream/{run-durability-ab.sh,analyze_durability.py,run-group-commit-bench.sh,analyze_group_commit.py,README.md}`, `bench/juring/run-juring-stream.sh` (if needed), `bench/seastar/build-stream-bench.sh` (if needed); `scratch/b6-bench/**`; results in gitignored `bench/results/**` |
| **b6-verify** | `scratch/b6-verify/**` only (no `src/`, `docs/`, `bench/`, `pom.xml`) |
| **Lead** | `docs/b6-design.md`, `docs/b6-report.md`, `docs/HANDOVER.md`, `SEASTAR-PLAN.md`, `scratch/b6-lead/**`, final `target/` builds, git commits, official W24/W25 sessions |

**Gates (serialised where noted):** G1 design freeze (Lead) → G2 parallel authoring
(`b6-file`/`b6-stream`/`b6-bench`); authors run `mvn` only from their own scratch copy of the
tree, never in the repo root (avoid `target/` races) → G3 Lead integrates (all files),
full suite green, code freeze + content hashes (`scratch/b6-lead/FREEZE.md`) → G4 official
W24/W25 sessions on the frozen tree, machine otherwise idle (no Maven/heavy agents) →
G5 independent verifier matrix + its own full suite (after G4's Lead GO) → G6 Lead final
suite on the frozen tree → G7 docs + feature/docs commits.

**Author dependencies.** `b6-stream` compiles against `b6-file`'s
`FadviseAdvice`/`DurabilityMode`/`JuringFile.fadvise()`/`durability()`/
`inFlightCapacity()`; `b6-file` creates those files/methods **first** in its first turn and
notifies the Lead, who forwards "shared API ready" to `b6-stream`. `b6-bench` depends only
on the §2 signatures and the §6 CLI/JSON contract (it may author against the contract
before the library compiles, but its smoke/verification runs wait for G3). The official
sessions are run by the **Lead** (machine alone) and are the only heavy device work in B6.

---

## 12. Amendments (frozen; appended, never silently edited)

### A1 — 2026-11-15 (session 9, pre-code-freeze; implementation clarification)

**A1.1 `inFlightCapacity()` visibility (fixes a §2.3/§4 internal contradiction).** §2.3
(frozen API) declares `public int inFlightCapacity()`; §4 (non-frozen implementation notes)
loosely called it "a package-private one-liner". The frozen public form is authoritative —
it is shipped public (strictly more accessible, also usable by `GroupCommit` validation and
by callers sizing `GroupCommitOptions.maxBatchSize`). No signature/semantics change; §4 is
non-frozen so no implementation change is required. Frozen design hash re-recorded in
`scratch/b6-lead/FREEZE.md`.

### A2 — 2026-11-15 (session 9, pre-campaign; W25 rest protocol + wall estimate correction)

**A2.1 W25 rests are between cells, not before every rep.** §6.4 mixed "R=3, warmup 5 s,
measured 15 s, **120 s rests** between reps" with an estimated wall time of 2–4 min; those
are inconsistent (12 invocations × 140 s ≈ 28 min). W25 is a **count/latency
micro-benchmark**, so the frozen protocol is: R=3, warmup 5 s, measured 15 s, a 5 s
inter-rep pause inside a cell, and a **120 s rest between cells** (4 cells ⇒ 3 rests),
total ≈ 8–9 min including preallocation and the fresh-process `--verify-only` runs. The
driver exposes `--rest-between cells|reps|none` (default `cells`); the official campaign
uses the default. No metric/acceptance change: the `fsyncs_per_group == 1.0`,
`groups_total × batch_size == committed_writes` and `restart_verify.ok` identities are
unaffected. The report records the actual protocol and wall time.

**A2.2 W24 protocol unchanged.** The §6.3 Tier-1/Tier-2 protocol (R=3 / 300 s rest per rep
block / back-to-back arms; Tier-2 R=2 / 120 s rest) and its ≈26–30 min estimate stand; the
"writeback control brackets the block" means the JUring-writeback arm runs first and last
in the whole session (its two reps are compared as the state-control pair).
