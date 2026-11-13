# B1 design contract — `JuringFile` async file handle abstraction

**Status:** implemented in this session (see `docs/b1-report.md` for evidence); contract frozen at the signatures below
**Implements:** `SEASTAR-PLAN.md` §4 **B1** (gaps G1, G6, G10); `docs/HANDOVER.md` §7 Step B.
**Acceptance (from the plan):** conformance tests (seq/rand, EOF, short IO,
close-with-inflight) green; L0 numbers unchanged (re-run W1/W3 with the M1 harness and
compare CIs); zero-allocation hot path preserved.

This document is the coordination contract between the parallel workstreams. Public
signatures below are frozen unless the Lead revises this file.

## 0. Scope and non-goals

**Delivered by B1**

1. `JuringFile` — an async file handle bound to a `JUring`: open with flags/options,
   offset read/write (plain and fixed-buffer), `length`, `truncate`, `allocate`
   (fallocate), `discard` (punch hole), `fsync`/`fdatasync`, `close` that drains
   in-flight operations with a bounded deadline and never blocks forever.
2. Typed `-errno` errors: `JuringException` (checked `IOException` subclass) with
   `errno()`, `strerror()`, `isErrno()`.
3. Short-IO / EAGAIN policy: `IoPolicy` for the blocking convenience methods
   (`read`/`write` complete-until-done, EOF is a short read, `-EAGAIN` retried a
   bounded number of times); the async `prepare*` fast path is single-shot and never
   retries.
4. Registration lifecycle: ring-level `unregisterFiles()` / `unregisterBuffers()`,
   sparse fixed-file table with runtime add/remove (`registerFileAdd`,
   `unregisterFileSlot`), runtime update path for registered buffers, and `JuringFile`
   `register()`/`unregister()` on top of it.
5. Engine op support needed by the above: `IORING_OP_FALLOCATE` (17),
   `IORING_OP_STATX` (21), `IORING_OP_FTRUNCATE` (55), `IORING_OP_TIMEOUT` (11), with
   runtime opcode probing (these are *optional* — they must not be added to the
   required-opcode probe list, so rings still work on older kernels).
6. Conformance + zero-allocation tests, an L0 re-run (W1/W3) proving no regression,
   and a short B1 report.

**Non-goals (B2/B3 own them):** buffered stream layer (`Input`/`Output`, readahead,
write-behind), full cancellation/timeout guarantee rework, multi-ring runtime,
completion demultiplexing for arbitrary ring sharing. `JuringFile.close()` uses a
timeout op for its bounded drain, but the general lifecycle hardening stays in B3.

## 1. Environment facts (verified this session)

- Kernel `7.0.12-201.fc44.x86_64`; `IORING_REGISTER_PROBE` reports supported:
  `NOP, READV, WRITEV, FSYNC, READ_FIXED, WRITE_FIXED, TIMEOUT(11), ASYNC_CANCEL,
  OPENAT, CLOSE, FILES_UPDATE, STATX(21), READ, WRITE, FALLOCATE(17), FTRUNCATE(55)`.
- Existing baseline: `mvn -o test` = 90 tests green (includes `ZeroAllocationHotPathTest`,
  which asserts **exactly 0** heap bytes on the hot path — do not weaken it).
- O_DIRECT on `@TempDir` (tmpfs `/tmp`) works in this environment (existing
  `DirectIoTest`/`ZeroAllocationHotPathTest` use it).
- Ring flags used by the benchmarks: `SINGLE_ISSUER | COOP_TASKRUN | DEFER_TASKRUN`.
- Threading contract: one ring per thread; `JuringFile` is **not** thread-safe
  (same as `JUring`).

## 2. Package layout (frozen)

All new public types go in the exported `com.davidvlijmincx.lio.api` package (no
`module-info.java` change):

| Type | Owner | Notes |
|---|---|---|
| `JuringFile` | Lead | new file, public final class |
| `JuringException` | Lead | new file, `extends IOException` |
| `IoPolicy` | Lead | new file, immutable policy |
| `JUring` / `IoUringDispatcher` / `OperationType` | engine-ops | additive changes only |
| tests | tests-conformance / engine-ops | see §6 |

**Do not renumber existing `OperationType` indices 0–15** (the `user_data` type field
and `populateResultFromCqe` depend on them). Append only:

```java
TIMEOUT(16),
FALLOCATE(17),
FTRUNCATE(18),
STATX(19)
```

## 3. Engine contract (engine-ops owns these exact signatures)

### 3.1 `IoUringDispatcher` (package-private)

```java
// new opcode constants
static final byte IORING_OP_TIMEOUT    = 11;
static final byte IORING_OP_FALLOCATE  = 17;
static final byte IORING_OP_STATX      = 21;
static final byte IORING_OP_FTRUNCATE  = 55;

// new register opcodes
private static final int IORING_UNREGISTER_BUFFERS = 1;
private static final int IORING_UNREGISTER_FILES   = 3;
private static final int IORING_REGISTER_BUFFERS_UPDATE = 16;

// probe cache: filled by probeSupportedOpcodes(); REQUIRED_OPCODES stays unchanged
boolean isOpcodeSupported(byte op);

// SQE prep (all other fields zeroed by getSqe(); offsets as in liburing)
void prepareFallocate(long sqe, int fd, int mode, long offset, long length);
    // opcode=FALLOCATE; fd; off=offset; len=mode; addr=length
void prepareFtruncate(long sqe, int fd, long length);
    // opcode=FTRUNCATE; fd; off=length; len=0; addr=0
void prepareStatx(long sqe, int fd, long pathnameAddr, int flags, int mask, long statxBufAddr);
    // opcode=STATX; fd; addr=pathnameAddr; len=mask; off=statxBufAddr; rw_flags=flags
void prepareTimeout(long sqe, long timespecAddr, long count, int flags);
    // opcode=TIMEOUT; fd=-1; addr=timespecAddr; len=1; off=count; rw_flags=flags

// registration teardown
int unregisterBuffers();  // IORING_UNREGISTER_BUFFERS; returns 0 or -errno
int unregisterFiles();    // IORING_UNREGISTER_FILES;   returns 0 or -errno
int inFlightCount();      // accessor for the existing counter (for JuringFile guards)

// one lazily allocated NUL byte inside the ring arena, for statx AT_EMPTY_PATH
long emptyPathAddress();

// statx result accessor (used by JuringFile.length(); statx layout is arch-stable)
long staticxSize(MemorySegment statxBuffer);   // JAVA_LONG at offset 40 (stx_size)
```

`populateResultFromCqe` gets cases 16–19 that set the matching `OperationType` and no
buffer ownership (same shape as the `FSYNC` case). `unregisterBuffers`/`unregisterFiles`
must use `register(callState, fd, opcode, MemorySegment.NULL, 0)`.

### 3.2 `JUring` (public additions; additive only)

```java
// async metadata ops (plain fd and registered fixed-file slot variants, + SqeOptions)
public long prepareFallocate(FileDescriptor fd, int mode, long offset, long length);
public long prepareFallocate(int indexFD, int mode, long offset, long length);
public long prepareFtruncate(FileDescriptor fd, long newLength);
public long prepareFtruncate(int indexFD, long newLength);
public long prepareStatx(FileDescriptor fd, MemorySegment statxBuffer);   // AT_EMPTY_PATH, STATX_BASIC_STATS
public long prepareStatx(int indexFD, MemorySegment statxBuffer);

// timeout (used by JuringFile.close for its bounded drain)
public long prepareTimeout(long timeoutNanos);                 // count=1, flags=0
public long prepareTimeout(long timeoutNanos, int count, int flags);

// registration lifecycle
public int registerFileAdd(FileDescriptor fd);   // sparse table, returns slot; lazy init (capacity 64)
public void unregisterFileSlot(int slot);        // FILES_UPDATE with fd = -1
public void unregisterFiles();                   // UNREGISTER_FILES + clear tracking
public void unregisterBuffers();                 // UNREGISTER_BUFFERS + reset JUring buffer state
public void updateRegisteredBuffers(int offset, MemorySegment... buffers); // optional: BUFFERS_UPDATE
public boolean isClosed();
int inFlightCount();                             // package-private, returns dispatcher counter

// package-private opcode capability checks (JuringFile uses them)
boolean supportsFallocate();
boolean supportsFtruncate();
boolean supportsStatx();
boolean supportsTimeout();
```

Rules:

- Every `prepare*` that needs an optional opcode throws
  `UnsupportedOperationException("kernel does not support IORING_OP_… (errno …)")
  before touching the SQ ring when `isOpcodeSupported` is false.
- `prepareStatx` validates `statxBuffer.isNative()` and `byteSize() >= 256`.
- `prepareTimeout` validates `timeoutNanos >= 0`, allocates a 16-byte `__kernel_timespec`
  (i64 sec, i64 nsec) in the ring arena, and documents that this is a control-path API
  (it allocates ring-lifetime memory per call — not for hot loops).
- `registerFileAdd`: lazily `IORING_REGISTER_FILES` a table of `capacity` × `-1`
  (kernel supports sparse empty slots), then `IORING_REGISTER_FILES_UPDATE` the chosen
  slot; slot tracking arrays are updated. `registerFiles(FileDescriptor...)` must also
  populate the tracking (capacity = count). `unregisterFiles` resets it.
- `unregisterBuffers()` resets `registeredBuffersArray`/addresses/sizes/free stack so a
  later `registerBuffers()` succeeds again.
- **Hot paths must not change**: no extra work in `prepareRead/prepareWrite/
  prepareReadFixed/prepareWriteFixed`, `submit*`, `reap*`, `waitForResult*`. The L0
  benchmark (`bench/juring/JUringFileBench.java`) must stay untouched.

## 4. `JuringFile` contract (Lead owns)

```java
public final class JuringFile implements AutoCloseable {
    public static final int DEFAULT_IN_FLIGHT_CAPACITY = 1024;
    public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000;
    public static JuringFile open(JUring ring, Path path, LinuxOpenOptions... options)
            throws JuringException;
    public static JuringFile open(JUring ring, Path path, int mode, LinuxOpenOptions... options)
            throws JuringException;

    public JUring ring();
    public Path path();
    public int fd();
    public int registeredSlot();          // -1 when not registered
    public boolean isRegistered();
    public boolean isOpen();
    public IoPolicy policy();
    public JuringFile withPolicy(IoPolicy policy);
    public JuringFile withInFlightCapacity(int capacity);   // before first op
    public JuringFile withCloseTimeout(long millis);

    public int register();      // idempotent; returns slot (unchecked on ring misuse)
    public void unregister();   // idempotent; unsafe operations throw IllegalStateException

    // Async issue (single-shot; caller submits via ring or awaits via await)
    public long prepareRead(long offset, int length, MemorySegment buffer) throws JuringException;
    public long prepareReadFixed(long offset, int length, int bufferIndex) throws JuringException;
    public long prepareWrite(long offset, MemorySegment buffer) throws JuringException;
    public long prepareWriteFixed(long offset, int length, int bufferIndex) throws JuringException;
    public long prepareFsync(boolean dataOnly) throws JuringException;
    public long prepareTruncate(long newLength) throws JuringException;
    public long prepareFallocate(long offset, long length, int mode) throws JuringException;
    public long prepareDiscard(long offset, long length) throws JuringException;
    // FALLOC_FL_* constants: KEEP_SIZE 0x01, PUNCH_HOLE 0x02, ZERO_RANGE 0x10

    // Blocking / policy
    public int await(long handle) throws JuringException;                  // bytes for rw, 0 for meta
    public int await(long handle, long timeoutMillis) throws JuringException;
    public int drain() throws JuringException;                             // wait all own in-flight
    public int read(long offset, MemorySegment buffer) throws JuringException;
    public int write(long offset, MemorySegment buffer) throws JuringException;
    public long length() throws JuringException;                           // async statx
    public void truncate(long newLength) throws JuringException;
    public void allocate(long offset, long length) throws JuringException;
    public void discard(long offset, long length) throws JuringException;
    public void fsync() throws JuringException;
    public void fdatasync() throws JuringException;

    public long issuedOperations();
    public long completedOperations();
    public long failedOperations();
    public int inFlightOperations();
    public int abandonedOperations();

    @Override public void close();            // = close(DEFAULT_CLOSE_TIMEOUT_MILLIS)
    public void close(long timeoutMillis);
}
```

### 4.1 Semantics (frozen)

- **Open** is a synchronous `open(2)` through `NativeDispatcher.C.open` (typed
  `JuringException` with errno on failure). Mode default 0644. `O_CLOEXEC` is always
  added like `FileDescriptor`. It is legal to use `register()` after operations.
- **Offset I/O** is always explicit (`pread`/`pwrite` semantics, `offset` is never
  implicit). Plain fds use `IORING_OP_READ/WRITE`; registered files use the
  `IOSQE_FIXED_FILE` variants (`prepareRead(int indexFD, …)`); fixed buffers use
  `READ_FIXED/WRITE_FIXED`. The fixed-buffer/registered-file path is the
  zero-allocation fast path.
- **`await(handle)`** drains completions from the ring until the completion carrying
  `handle` is seen, recording all completions of *this* file in a fixed-size in-flight
  ledger (`long[] user_data`, `int[] result`, `byte[] state`, free-index stack; no heap
  allocation per op). Positive/zero result is returned; negative result raises
  `JuringException` with `errno = -result`.
- **Ownership guard:** blocking helpers (`await`, `drain`, `close`, and all
  convenience methods) require that all operations currently in flight on the ring
  belong to this `JuringFile` (checked as
  `ring.inFlightCount() == ownUnreapedCount`). If the ring carries foreign in-flight
  work, they throw `IllegalStateException` with a message telling the caller to await
  that work first. This is how B1 stays safe without a ring-level completion
  dispatcher; B3 adds the dispatcher. Raw `prepare*` + caller-driven ring reaping is
  always safe (no guard needed).
- **`read(offset, buffer)`** = complete-until-done: issues one op at a time, retries
  `-EAGAIN` up to `policy.eagainRetries()` (with `policy.eagainBackoffNanos()` sleep
  between attempts), stops at EOF (`res == 0`) and returns the short byte count; a
  short read before EOF is retried until the buffer is full or EOF. If
  `!policy.shortReadAtEofAllowed()` EOF raises `JuringException`.
- **`write(offset, buffer)`** = complete-until-done: loops over short writes, retries
  `-EAGAIN` per policy; returns total bytes written (== buffer size on success).
- **`length()`** = async `IORING_OP_STATX` with `AT_EMPTY_PATH` on a 256-byte
  confined-arena statx buffer; returns `stx_size` (read directly at offset 40 of the
  arch-stable `struct statx`). Metadata convenience ops may allocate (small,
  documented). **Deviation found during implementation:** the kernel rejects
  `IORING_OP_STATX` with `IOSQE_FIXED_FILE` (`io_statx_prep` returns `-EBADF`; verified
  on this host: plain `AT_EMPTY_PATH` statx res=0, fixed-file slot res=-9). The frozen
  `prepareStatx(int slot, ...)` signature is kept, but the engine resolves the slot via
  JUring's tracked real fd and submits a plain `AT_EMPTY_PATH` statx on the same file,
  so `JuringFile.length()` works for registered files too. An empty/untracked slot falls
  back to a fixed-file SQE and completes with `-EBADF` like other fixed-file ops.
- **`truncate`/`allocate`/`discard`/`fsync`/`fdatasync`** = one async op + `await`
  (metadata ops return 0). `allocate(offset, len)` uses `fallocate(mode=0)`; `discard`
  uses `FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE`. A negative truncate length is
  rejected by `JuringFile` as a typed `EINVAL` (matching what the kernel would return)
  rather than an unchecked argument error. Callers must use filesystem-appropriate
  alignment for discard (documented).
- **`close()`**:
  1. flush prepared SQEs (`ring.submit()`);
  2. bounded drain until `inFlightOperations() == 0` or the deadline; the deadline is
     enforced with a `LockSupport.parkNanos` bounded poll of
     `ring.peekForBatchResult(...)` (50–100 µs slices, never a blocking wait). The
     `IORING_OP_TIMEOUT` opcode is delivered and tested by engine-ops for B3, but
     `JuringFile` does not depend on it (a stray timeout completion cannot corrupt the
     ledger, and cancellation of an internal timeout would leave extra CQEs behind);
  3. if still in flight: cancel each own pending handle (`ring.prepareCancel(handle)`),
     submit, and give a short grace drain;
  4. if still in flight after the grace: count them in `abandonedOperations()` and
     force-close (never hang);
  5. unregister the file slot (if registered) and close the fd;
  6. idempotent; never throws for operation failures (those are recorded in counters),
     but rethrows unexpected runtime errors.
- **Use-after-close** on any operation throws `IllegalStateException`.
- **`JuringException`** is checked and carries `errno()` (0 for synthetic errors),
  `operation()`, `strerror()`, `isErrno(int)`; `getMessage()` includes op, errno,
  strerror and target.
- **`IoPolicy`** is immutable: `IoPolicy.defaults()` (eagainRetries=8,
  eagainBackoffNanos=50_000, shortReadAtEofAllowed=true), `withEagainRetries`,
  `withEagainBackoffNanos`, `withShortReadAtEofAllowed`.

## 5. Test contract (tests-conformance owns)

`src/test/java/com/davidvlijmincx/lio/api/JuringFileConformanceTest.java` (or two files
if it gets large) must cover:

- **open/close:** flags (`READ`, `WRITE`, `CREATE`, `TRUNCATE`, `DIRECT`, `DSYNC`),
  default mode, typed `ENOENT` error, use-after-close throws.
- **seq/rand I/O round-trip:** buffered write → read back (`length()` + content), 4 KiB
  random offsets on a 1 MiB file, registered-file + fixed-buffer fast path, plain-fd
  path, O_DIRECT 4 KiB aligned round-trip.
- **EOF / short IO:** read past EOF → 0; read a 4 KiB+100 file → short count; `read()`
  returns short at EOF; write() completes a partial-remaining case where possible.
- **metadata:** `length()` before/after `truncate`, `allocate` (fallocate extends),
  `discard` keeps length but zeroes data, `fsync`/`fdatasync` succeed (inside a
  try/catch for `UnsupportedOperationException` + `Assumptions.assumeTrue` on older
  kernels; this kernel supports all).
- **close-with-inflight:** with a dedicated ring, issue e.g. 64 fixed reads without
  awaiting, then `close()` → returns within the close timeout, `completedOperations()`
  = issued, `abandonedOperations()` = 0, no hang, slot unregistered and fd closed.
- **registration lifecycle:** `register()` returns slot, repeated `register()`
  idempotent, `unregister()` frees slot, slot reused by a second file, ring-level
  `unregisterFiles()`/`unregisterBuffers()` then re-register works (engine-ops owns the
  ring-level tests; JuringFile test covers its own register/unregister).
- **policy:** `IoPolicy` defaults + `with*` immutability; `read` at EOF with
  `shortReadAtEofAllowed(false)` throws `JuringException` (non-negative errno
  synthetic; documented).
- **errors:** read from write-only fd → typed `EBADF`; `truncate(-1)` → typed
  `EINVAL`; discard on unsupported alignment → `EINVAL`.

`src/test/java/com/davidvlijmincx/lio/api/JuringFileZeroAllocationTest.java`:
- new ring (`SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN`, qd 64),
  `registerBuffers(4096, 16)`, JuringFile over a 1 MiB `@TempDir` file, `register()`;
- warmup until 3 consecutive 0-allocation rounds (existing pattern), then
  `prepareReadFixed + await` (or `prepareReadFixed + ring.submitAndWaitForBatchResult`)
  for 100k ops and assert exactly **0** heap bytes allocated
  (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`).
- include the JuringFile write fixed path similarly if cheap.

Engine-ops tests: `JUringOpcodeOperationsTest` (fallocate/truncate/statx/timeout async
ops via raw ring, incl. timeout actually fires and statx returns the right size) and
`JUringRegistrationLifecycleTest` (sparse file add/remove/reuse, unregister files,
unregister buffers then re-register, optional BUFFERS_UPDATE with an `Assumptions`
guard if the kernel rejects it).

## 6. Write scopes (advisory; disjointness is the rule)

| Task | Write scope |
|---|---|
| engine-ops | `src/main/java/com/davidvlijmincx/lio/api/{IoUringDispatcher,OperationType,JUring}.java`, `src/test/java/com/davidvlijmincx/lio/api/JUringOpcodeOperationsTest.java`, `src/test/java/com/davidvlijmincx/lio/api/JUringRegistrationLifecycleTest.java` |
| Lead (JuringFile core) | `src/main/java/com/davidvlijmincx/lio/api/{JuringFile,JuringException,IoPolicy}.java` |
| tests-conformance | `src/test/java/com/davidvlijmincx/lio/api/JuringFile*Test.java` |
| bench-verify | `bench/results/b1-l0-check/**` (gitignored), `scratch/b1/**` (gitignored) |
| Lead (report/commit) | `docs/b1-report.md`, `docs/HANDOVER.md`, `docs/b1-design.md`, git commit |

Nobody edits `bench/juring/JUringFileBench.java`, `bench/m1/**`, or the existing
`JUring` hot-path methods. Maven is **not** safe to run concurrently: engine-ops owns
`mvn` verification until it reports the engine API landed; then Lead owns it; the
tests-conformance teammate only runs `mvn -o test -Dtest=JuringFile*` after the Lead
announces the API compiles.

## 7. L0 non-regression protocol (bench-verify)

1. **Pre-baseline, before source changes are compiled:** run the M1 harness
   juring-only W1 (4K randread QD1) and W3 (QD64), R=5, 10 s warmup + 30 s measured,
   CPU 2, `JURING_SKIP_COMPILE=1` (uses the baseline `target/classes`):
   ```bash
   M1_RESULTS=$PWD/bench/results/b1-l0-check/pre \
     ENGINES=juring QDS="1 64" REPS=5 WARMUP=10 DURATION=30 \
     bench/m1/run-read-matrix.sh
   ```
   Note: CPU governor must be `performance` and no foreign >20 % CPU process
   (harness preflight); report if preflight fails instead of forcing it.
2. **Post-baseline, after the final tree is committed/built**, run the identical
   command into `bench/results/b1-l0-check/post` (this time without
   `JURING_SKIP_COMPILE=1`, or with it after the Lead compiled the final tree).
3. Compare pre vs post per cell (mean IOPS, sample SD, 95 % CI of the mean; paired
   per-rep ratios) and against the stored M1 values for the same cells
   (`bench/results/m1-2026-11-13/cells/W1__juring__std__*`,
   `W3__juring__std__*`).
   **Verdict rule (equivalence, pre-registered):** L0 is *unchanged* iff
   (a) the post/pre mean ratio is inside **[0.95, 1.05]**,
   (b) the two-sided **95 % CI of the paired ratio is entirely contained in
   [0.95, 1.05]** (a tight CI on a +1 % difference is a detectable improvement, not a
   regression), and
   (c) every QD64 rep passes the state gate (p99 < 600 µs). All reps are reported; host
   -state outliers are flagged, never dropped. If (a) and (c) hold but (b) does not, run
   one confirmation block (same protocol, new `post2/` dir) before drawing any code
   conclusion and report both post blocks honestly.
4. Emit `bench/results/b1-l0-check/comparison.md` (raw table + verdict) and report
   the numbers to the Lead for `docs/b1-report.md`. Raw results are gitignored; the
   report keeps the table.

## 8. Known constraints / risks

- Ring sharing with foreign in-flight ops is guarded, not solved (B3). Conformance
  tests use dedicated rings while draining.
- `IORING_OP_STATX` needs `AT_EMPTY_PATH` (+ `IOSQE_FIXED_FILE` for slots); the empty
  pathname byte must stay alive until completion — allocated once per ring.
- `prepareTimeout` allocates ring-lifetime memory per call; control path only.
- `IORING_REGISTER_BUFFERS_UPDATE` support varies; JuringFile does not depend on it.
  The sparse-file add/remove path (`IORING_REGISTER_FILES` with `-1` slots +
  `FILES_UPDATE`) is the required, kernel-portable mechanism.
- `close()` failing to drain within the deadline is a device/kernel hang class of
  problem; it is bounded and counted (`abandonedOperations()`), never silent.
