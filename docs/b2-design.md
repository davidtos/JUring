# B2 design contract — buffered stream layer (`JuringInputStream` / `JuringOutputStream`)

**Status:** frozen contract, written before implementation (2026-11-13, session 4).
**Implements:** `SEASTAR-PLAN.md` §4 **B2** (gap G1); prepares plan **M2** (W21/W22 vs
seastar `fstream`); builds on `docs/b1-design.md` (`JuringFile`).
**Acceptance (from the plan):** W21/W22 run; seq read/write ≥85 % of seastar `fstream`;
bounded memory (configurable max in-flight); correctness tests for partial writes/EOF;
L0 unchanged.

This document is the coordination contract between the parallel workstreams. The public
signatures below are frozen unless the Lead revises this file. Internal package-private
classes and algorithms are owned by the implementation and are not frozen, except where
this document fixes semantics (§2) or resource bounds (§3).

## 0. Scope and non-goals

**Delivered by B2**

1. `JuringInputStream` — async prefetching byte input stream over a `JuringFile`:
   configurable buffer size, readahead depth (extra in-flight reads), optional adaptive
   readahead (seastar-style slow-start + 4 MiB unused-ratio windows), `skip`, zero-copy
   `readChunk()` plus complete-until-done `read(...)`, EOF handling, bounded memory.
2. `JuringOutputStream` — write-behind byte output stream over a `JuringFile`:
   configurable buffer size, write-behind depth, complete-until-done writes (short-write
   and `EAGAIN` handling per the file's `IoPolicy`), `flush`, optional preallocation hint,
   optional `fdatasync` on close, bounded memory, O_DIRECT-safe alignment.
3. `InputStreamOptions` / `OutputStreamOptions` — immutable option objects (validated
   boundaries, `with*` builders), mirroring `seastar::file_{input,output}_stream_options`.
4. Conformance + bounded-memory tests, stream benchmark runners for both engines and a
   W21/W22 driver/aggregator, a preliminary W21/W22 measurement, an L0 non-regression
   check, and a B2 report.

**Non-goals (B6/B3 own them):** `posix_fadvise` hints, durability/group-commit modes
(B6), cancellation/timeout guarantees beyond draining stream-owned operations (B3),
multi-ring runtime, pipe/socket streams (seastar's pipe streams are not comparable to
file fstream; out of scope), full cross-stream persistent readahead history (see §4.2).
The stream layer does not own the `JuringFile`: `close()` flushes/drains but never
closes the file (documented difference from seastar `data_sink::close()`, which closes
the file).

**Engine impact:** none. B2 is purely additive (new classes only). `JuringFile`,
`JUring`, `IoUringDispatcher`, `bench/juring/JUringFileBench.java` and `bench/m1/**`
must stay untouched.

## 1. Public API (frozen)

All types go in the exported `com.davidvlijmincx.lio.api` package (no
`module-info.java` change). Names follow B1 (`Juring*`), not the plan's shorthand
`Input`/`Output`.

### 1.1 `InputStreamOptions` (final, immutable)

```java
public static final int DEFAULT_BUFFER_SIZE = 8192;
public static final int DEFAULT_ALIGNMENT = 4096;
public static InputStreamOptions defaults();
public int bufferSize();          // configured maximum chunk/buffer size
public int readAhead();           // max EXTRA prefetch buffers; total in-flight = readAhead + 1
public int alignment();           // DMA/alignment for buffers and aligned reads
public boolean dynamicAdjustments();
public InputStreamOptions withBufferSize(int bufferSize);
public InputStreamOptions withReadAhead(int readAhead);
public InputStreamOptions withAlignment(int alignment);
public InputStreamOptions withDynamicAdjustments(boolean enabled);
```

Defaults mirror seastar (`file_input_stream_options`: buffer_size 8192, read_ahead 0,
dynamic adjustments disabled). Validation (`IllegalArgumentException`):
`bufferSize >= 1`; `0 <= readAhead <= 1024`; `alignment` is a power of two in
`[1, 1 MiB]`; `null` option object is `NullPointerException`.

### 1.2 `OutputStreamOptions` (final, immutable)

```java
public static final int DEFAULT_BUFFER_SIZE = 65536;
public static final int DEFAULT_ALIGNMENT = 4096;
public static OutputStreamOptions defaults();
public int bufferSize();          // logical buffer size (rounded up to alignment internally)
public int writeBehind();         // concurrent writes; total buffers = writeBehind + 1
public int alignment();
public long preallocationSize();  // fallocate hint at open; 0 = none
public boolean syncOnClose();     // fdatasync on close()
public OutputStreamOptions withBufferSize(int bufferSize);
public OutputStreamOptions withWriteBehind(int writeBehind);
public OutputStreamOptions withAlignment(int alignment);
public OutputStreamOptions withPreallocationSize(long preallocationSize);
public OutputStreamOptions withSyncOnClose(boolean syncOnClose);
```

Defaults mirror seastar (`file_output_stream_options`: buffer_size 65536,
preallocation_size 0, write_behind 1). Validation: `bufferSize >= 1`;
`1 <= writeBehind <= 1024`; alignment as above; `preallocationSize >= 0`.

### 1.3 `JuringInputStream` (final, `AutoCloseable`)

```java
public static JuringInputStream open(JuringFile file) throws JuringException;
public static JuringInputStream open(JuringFile file, InputStreamOptions options) throws JuringException;
public static JuringInputStream open(JuringFile file, long offset, long length,
                                     InputStreamOptions options) throws JuringException;

public JuringFile file();
public MemorySegment readChunk() throws JuringException;      // empty segment at EOF
public int read(MemorySegment destination) throws JuringException;      // complete-until-done
public int read(byte[] destination, int offset, int length) throws JuringException;
public long skip(long bytes) throws JuringException;          // returns bytes actually skipped
public long position();          // next absolute file offset
public long remaining();         // bytes left in the declared window; -1 = until EOF
public long bytesRead();
public boolean eof();
public int bufferSize();         // configured maximum
public int currentBufferSize();  // adaptive current (== bufferSize when dynamic off)
public int readAhead();          // configured maximum extra prefetch buffers
public int currentReadAhead();   // adaptive current
public long bufferMemoryBytes(); // bytes currently allocated by this stream
public void close() throws JuringException;
```

Semantics (frozen):

- **Window:** the default `open(file)` reads from offset 0 until EOF. The 3-arg open
  reads `[offset, offset+length)`; `length < 0` means "until EOF"; the stream never
  returns bytes outside the window, and `remaining()` reports `-1` for until-EOF.
  `offset < 0` or `length < -1` is `IllegalArgumentException`.
- **`readChunk()`** returns the next chunk of up to `currentBufferSize()` bytes; an empty
  segment (`byteSize() == 0`) signals EOF. The returned segment is a **view into the
  stream's internal buffer and is only valid until the next call to `readChunk`,
  `read`, `skip` or `close`** (zero-copy contract, like seastar `temporary_buffer`
  ownership but narrower). Callers that need to keep data must copy it.
- **`read(destination)`** is complete-until-done: fills `destination` completely and
  returns `destination.byteSize()`, or returns the short count only at EOF (`0` when
  already at EOF before the call). `destination` must be a writable native segment.
  `read(byte[], off, len)` has the same contract with standard
  `IndexOutOfBoundsException` argument checks. A partial read keeps the unconsumed tail
  internally, so the next read/chunk/skip call is exactly contiguous.
- **`remaining()`** reports the remainder of the *declared* window (or `-1` for until-EOF);
  an early EOF does not shrink a declared window, `eof()` is the end-of-file signal.
- **Prefetch:** up to `currentReadAhead() + 1` reads are in flight; reads are issued at
  `alignDown(position, alignment)` for
  `alignUp(frontTrim + min(remaining-or-window, currentBufferSize), alignment)` bytes and
  trimmed to the logical stream bytes. The stream tolerates arbitrary (unaligned) offsets
  and O_DIRECT or buffered files. A short completion means EOF was reached (set `eof()`);
  a chunk shorter than the buffer is legal.
- **`skip(bytes)`** advances without copying: drops fully-prefetched chunks (awaiting
  their completions when needed), trims a partially-consumed chunk, or — when nothing is
  buffered — advances the position directly (bounded by the declared window). Returns the
  number of bytes skipped. When chunks are already prefetched the skip stops at the first
  observed EOF; when nothing is prefetched on an **until-EOF** stream the position advances
  without probing the file, so the returned count is the requested amount and the next read
  observes EOF (`eof()` becomes true then). Callers that need an EOF-clamped count should
  use a declared window or read to EOF first.
- **Adaptive readahead** (`dynamicAdjustments() == true`) follows seastar
  `fstream.cc:106-235` in simplified form (§4.2): slow-start buffer growth from
  `min(max(bufferSize/4, 8192), bufferSize)` up to `bufferSize` driven by a 4 MiB
  unused-ratio window (target ≤25 % unused), readahead increase when the consumer has to
  wait for a chunk, and shrink + readahead reset when skipping makes the unused ratio
  exceed the target. Adaptation never exceeds the configured `bufferSize`/`readAhead`
  and never allocates more than the §3 bound.
- **Errors:** file I/O failures propagate as the checked `JuringException` produced by
  `JuringFile` (typed errno). `close()` drains/cancels stream-owned in-flight operations
  (bounded by `JuringFile`'s await), releases buffers and is idempotent; it throws
  `JuringException` only if a drained operation failed. Using the stream after
  `close()` throws `IllegalStateException`.
- **Ring ownership:** the stream uses `JuringFile`'s blocking helpers, so it inherits the
  B1 rule: no foreign in-flight work may be on the ring while the stream is used. Not
  thread-safe (one owner thread, like `JuringFile`).

### 1.4 `JuringOutputStream` (final, `AutoCloseable`)

```java
public static JuringOutputStream open(JuringFile file) throws JuringException;
public static JuringOutputStream open(JuringFile file, OutputStreamOptions options) throws JuringException;

public JuringFile file();
public void write(MemorySegment source) throws JuringException;
public void write(byte[] source, int offset, int length) throws JuringException;
public void flush() throws JuringException;
public long position();          // logical bytes accepted/written
public long bytesWritten();      // == position()
public int writeBehind();        // configured depth
public long bufferMemoryBytes();
public void close() throws JuringException;
```

Semantics (frozen):

- **Write position:** the stream writes starting at file offset 0 (seastar
  `make_file_output_stream` contract). It never seeks; the logical position advances with
  every accepted byte. Writing over an existing larger file leaves its tail untouched
  unless the final partial write had to be alignment-padded (§ below), in which case
  `flush`/`close` truncates the file to `position()`.
- **`write(source)`** is complete-until-done: copies `source` into internal buffers,
  issuing write-behind operations as buffers fill. A full write-behind window blocks
  until the oldest write completes. Short writes are retried from the last completed
  byte; `-EAGAIN` is retried per the file's `IoPolicy`. Returns normally only when every
  byte has been accepted (not necessarily durable — see `flush`).
- **Alignment / O_DIRECT:** internal buffers are `alignment`-aligned and writes use
  `alignUp(bufferSize, alignment)` chunks at aligned offsets, so a single stream works on
  both O_DIRECT and buffered files. The final partial buffer at `flush`/`close` is
  zero-padded to alignment, written, and then the file is `ftruncate`d to `position()`
  (so no padding bytes are visible). Requires `IORING_OP_FTRUNCATE`; on a kernel without
  it the padding cannot be undone and the stream throws `UnsupportedOperationException`
  at flush time (this kernel supports it).
- **`flush()`** writes the pending partial buffer, waits for all in-flight write-behind
  operations, undoes alignment padding via truncate, then returns. It does not `fsync`
  unless `syncOnClose()`. **Continuing to write after a `flush()`** is supported when the
  logical position is alignment-aligned (the usual case: whole buffers); after a padded
  flush at an unaligned position further writes throw `IllegalStateException` (an O_DIRECT
  stream cannot restart at an unaligned offset; close the stream or keep positions
  aligned).
- **Preallocation hint:** when `preallocationSize() > 0`, `open()` issues
  `JuringFile.allocate(0, preallocationSize)` (one blocking `fallocate`, mode 0) as a
  hint; failures propagate (kernel lacks fallocate → `UnsupportedOperationException`).
- **`close()`** = `flush()`, then `fdatasync()` when `syncOnClose()`, then releases
  buffers; it drains every stream-owned in-flight write even after an individual
  completion failed; it does **not** close the `JuringFile`. After an I/O error (including
  a failed `truncate`/`fdatasync`) the first `JuringException` is latched and rethrown by
  subsequent `write`/`flush` calls; `close()` remains idempotent (a second `close()` after
  a failed first one is a no-op) and reports the latched failure on the close that
  observes it.
- **Errors/threading/ownership:** as §1.3.

### 1.5 Frozen constants

`InputStreamOptions.DEFAULT_BUFFER_SIZE = 8192`,
`OutputStreamOptions.DEFAULT_BUFFER_SIZE = 65536`,
both `DEFAULT_ALIGNMENT = 4096`. `JuringInputStream`/`JuringOutputStream` expose
`DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000` (used for draining at close, like B1).

## 2. Resource bounds (acceptance: bounded memory)

Let `A = alignUp(bufferSize, alignment)`.

- Input allocates exactly `(readAhead + 1)` native buffers of
  `A + alignment` bytes each, lazily or eagerly (implementation choice), and never more,
  regardless of file size, duration or adaptive adjustments.
  `bufferMemoryBytes() <= (readAhead + 1) * (A + alignment)`.
- Output allocates exactly `(writeBehind + 1)` native buffers of `A` bytes each.
  `bufferMemoryBytes() <= (writeBehind + 1) * A`.
- No per-chunk heap allocation is required on the `readChunk()`/`write()` success path
  beyond FFM view objects; the harness must not observe growing retained memory with
  bytes processed (bounded-memory test asserts the buffer accounting above after a
  multi-hundred-MiB transfer).

## 3. Benchmark contract (frozen CLIs + W21/W22 protocol)

### 3.1 `bench/juring/JUringStreamBench.java` (new; worker: juring-bench)

Standalone JUring stream runner (does **not** modify `JUringFileBench.java`). Same JSON
schema as `bench/juring/JUringFileBench.java` / `bench/seastar/seastar_file_bench.cc`
(schema_version "1.0", one JSON object on stdout, `--json PATH` also writes it,
diagnostics on stderr). CLI:

```
--workload stream_read|stream_write   required
--file PATH                           required
--bs SIZE       stream buffer size (default 128k)
--qd N          stream concurrency = in-flight depth (default 8)
--size SIZE     dataset bytes per pass / measured window cap
                (read default: file size; write default: file size)
--seconds S (default 10)  --warmup-sec S (default 2)  --runs N (default 1)
--readonly      assert read-only
--buffered      open without O_DIRECT (default: O_DIRECT)
--no-register-file   do not ring-register the file (default: register via JuringFile.register())
--json PATH  --label TEXT  --seed N  --help
```

- Ring: `entries = max(256, 2*qd)`, flags
  `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN`, one thread (external `taskset`).
- Read: repeat passes (open stream at offset 0 with `length = dataset`; `readChunk()`
  until empty; close stream) until the phase deadline. Chunks counted as ops; bytes
  accumulated; per-chunk latency = time around `readChunk()` (including wait).
- Write: repeat passes (open stream; write exactly `dataset` bytes as `bs`-sized chunks
  from a reused aligned source buffer; `flush`; `close`) until the phase deadline,
  stopping cleanly mid-pass at the deadline. File size must stay ≈ dataset (0..dataset
  overwrite each pass). Source buffer is filled once per pass with a deterministic
  pattern.
- `--buffered` and `--no-register-file` are disclosure flags for the asymmetry table.
- JSON must include the usual `schema_version`, `engine:"juring"`,
  `engine_detail:"JUringStreamBench"`, `status`, `error`, `label`, `manifest`
  {kernel, cpu_model, hostname, git_commit, jdk, command_line},
  `config` {workload, file, file_size_bytes, dataset_bytes, bs, qd, buffer_size,
  concurrency, seconds, warmup_sec, runs, seed, readonly, direct_io, ring_flags,
  registered_files, fixed_buffers:false, stream_buffer_size, stream_concurrency},
  `runs` [{run, seconds, ops_total, bytes_total, errors_total, iops, mibs,
  latency_us{samples,sampled,avg,p50,p90,p95,p99,p999,max}}],
  `aggregate` {runs, elapsed_sec, ops_total, bytes_total, errors_total,
  iops{mean,stdev}, mibs{mean,sd,ci95?}, latency_us{...}}.
  `mibs` = MiB/s over measured bytes and measured elapsed.

Companion script `bench/juring/run-juring-stream.sh` mirrors `run-juring.sh` (same
`JURING_SKIP_COMPILE`/`JURING_SKIP_MVN`/`JAVA_BIN` env, compiles
`bench/juring/JUringStreamBench.java` into `bench/juring/out`).

### 3.2 `bench/seastar/seastar_stream_bench.cc` (new; worker: seastar-bench)

Standalone seastar stream runner on an existing file (do **not** modify
`seastar_file_bench.cc`). Build with new `bench/seastar/build-stream-bench.sh` (copy of
`build-file-bench.sh`; output `bench/seastar/seastar_stream_bench`, add the binary to
`bench/.gitignore`). Same CLI as §3.1 including `--buffered`? **No:** seastar file I/O is
always DMA/O_DIRECT on this filesystem, so `--buffered` is rejected with a clear error
and the JSON always records `direct_io:true`. `--no-register-file` is accepted as a no-op
disclosure flag (seastar cannot register files; JSON records `registered_files:false`).

- File open: `seastar::open_file_dma(file, ro | rw|create)`.
- Read: **seastar only honours `read_ahead > 1` when adaptive adjustments are on**
  (`file_data_source_impl::get_initial_read_ahead()` returns `!!read_ahead` when
  `dynamic_adjustments` is null, i.e. 1 in flight), so the runner must pass a fresh shared
  history: `auto h = make_lw_shared<file_input_stream_history>(); file_input_stream_options
  o; o.buffer_size = bs; o.read_ahead = qd - 1; o.dynamic_adjustments = h;` and reuse `h`
  across passes of the same cell (seastar's slow start then ramps the effective depth to
  `qd`-ish; this is an engine asymmetry vs JUring's fixed `readAhead`, disclosed in the
  JSON/report as `read_ahead_mode:"seastar-adaptive"`). Loop `co_await s.get()` until an
  empty buffer (EOF), then close the stream and open a new pass until the phase deadline.
- Write: `make_file_output_stream(f, {.buffer_size = bs, .preallocation_size = 0,
  .write_behind = qd})`; write `dataset` bytes (chunks of `bs`) per pass, then
  `flush()` + `close()`; repeat passes until the deadline. Note fstream's
  write path internally pads unaligned tails exactly like our §1.4 semantics.
- Same JSON schema/fields as §3.1, `engine:"seastar"`,
  `engine_detail:"seastar_stream_bench"`, manifest with `seastar_version`,
  `reactor_backend`, `smp`, `command_line`.
- Run flags as for `seastar_file_bench`: `--smp 1 --cpuset 2 --memory 1G
  --lock-memory 0 --reactor-backend io_uring` + `taskset -c 2`.

### 3.3 W21/W22 protocol (`bench/stream/`; worker: juring-bench, run by Lead)

Cells (all `bs`/concurrency disclosed, R=3, one process per rep, CPU 2, interleaved
engines):

| cell | workload | buffer | concurrency | dataset |
|---|---|---|---|---|
| W21-128k | stream_write | 128 KiB | 8 | `nvme-data/write_files/single_file_bench_w.bin` (10 GiB, writable) |
| W22-128k | stream_read | 128 KiB | 8 | `/mnt/TestData/read_files/single_file_bench.bin` (aged 10 GiB, read-only) |
| W21-1M | stream_write | 1 MiB | 8 | same write file |
| W22-1M | stream_read | 1 MiB | 8 | same read file |

(The 1 MiB cells exist because plan-M2 words the bar as "seq 1M read/write";
the methodology's W21/W22 words it as 128K buffer. Both are reported.)

- Default protocol: warmup 10 s, measured 30 s, R=3, rest 60 s between measured reps
  (preliminary; the full M2 campaign uses the methodology's 60 s cells and 300 s rests —
  deviations must be listed in the report). Engine-matched primary rows use O_DIRECT on
  both sides (seastar `fstream` is DMA/O_DIRECT here); JUring also gets one disclosed
  buffered (`--buffered`) exploratory row per cell, never used for the ≥85 % verdict.
- `bench/stream/run-stream-matrix.sh`: preflight (files exist, sizes, free space,
  governor = performance, loadavg, no foreign >20 % CPU), per-cell/rep engine
  interleaving, writes raw JSON to `bench/results/b2-2026-11-13/cells/`, logs to
  `logs/`, `cell-meta.tsv` (cell, engine, rep, argv, status, start/end, loadavg),
  `manifest.txt` (kernel, git commit/dirty, JDK, g++/seastar version, CPU, governor,
  device, mount, flags). `--dry-run`, `--reps`, `--seconds`, `--warmup`, `--rest`,
  `--cells` overrides.
- `bench/stream/analyze_stream.py`: reads the cells, emits
  `aggregate/stream.json` + `aggregate/stream.md`: per-cell per-engine mean/sd/CI95 of
  MiB/s and chunk latency; per-rep paired ratio `juring/seastar` (mean, 95 % CI);
  verdict vs plan-M2 bar "juring MiB/s ≥ 0.85 × seastar MiB/s" and a parity note;
  CV and errors reported; no rep silently dropped.
- `bench/stream/README.md`: protocol, deviations (dataset choices, durations/rests,
  asymmetry table: seastar = 200-entry ring, no fixed files/buffers, no
  SINGLE_ISSUER/COOP_TASKRUN/DEFER_TASKRUN, no file registration; JUring = registered
  file primary row, optional buffered exploratory row), and how to reproduce.

Results are gitignored (`bench/results/`); the report keeps the tables.

## 4. Implementation notes (non-frozen, but correctness-relevant)

### 4.1 Input pipeline

- Buffers: `(readAhead + 1)` aligned `MemorySegment`s from one `Arena.ofConfined()`,
  capacity `A + alignment` (covers an unaligned front trim of up to `alignment - 1`).
- A small ring-buffer/FIFO of prefetch descriptors
  `(bufferIndex, handle, alignedStart, frontTrim, logicalLength, state, result)`; issue
  reads until `currentReadAhead + 1` pending or EOF/window end, then one
  `ring.submit()`. Completion is obtained with `JuringFile.await(handle)` (the handle's
  ledger remembers results for handles reaped as a side effect of other awaits).
- Keep the buffer of the last returned chunk alive until the next stream call, then
  recycle it; prefetch never runs ahead into a buffer the caller still holds.
- Adaptive accounting: `total_read`/`unused_read` windows of 4 MiB; call
  `update_history_consumed(chunkSize)` on every consumed chunk and
  `update_history_unused(skipped)` on every `skip` (see §4.2).

### 4.2 Simplified adaptive readahead (documented deviation)

Ported from seastar `file_data_source_impl` (`SeaStarRef/src/core/fstream.cc:106-235`),
per-stream, no cross-stream `file_input_stream_history` sharing (seastar disables
adaptation by default too; our option is the equivalent of supplying a fresh history):

- construction with adaptation on: `currentBufferSize = min(max(bufferSize/4, 8192),
  bufferSize)` (slow start), `currentReadAhead = min(1, readAhead)`.
- `update_history_consumed(n)`: `total += n`; if in slow start, try
  `newSize = min(2 * currentBufferSize, bufferSize)` and grow iff the window including
  `newSize` still stays below the 25 % unused target; stay in slow start until
  `currentBufferSize == bufferSize`.
- when the consumer has to wait for the front buffer and adaptation is on and not in slow
  start: `currentReadAhead = min(currentReadAhead + 1, readAhead)`.
- `update_history_unused(n)`: `total += n; unused += n`; if above the 25 % unused target,
  shrink `currentBufferSize` to `max(minimalBufferSize, pow2floor(newTarget))`, re-enter
  slow start and clamp `currentReadAhead` to ≤1.
- 4 MiB windows rotate (`previous_window = current_window; current_window = 0`).

### 4.3 Output pipeline

- `(writeBehind + 1)` aligned buffers of `A` bytes; one is the filling buffer, the rest
  are in-flight/pending writes.
- On a full buffer: if `outstanding == writeBehind`, drain the oldest (await + short
  write/EAGAIN completion); take a free buffer; `prepareWrite(flushOffset, buffer)`;
  `flushOffset += A`.
- `flush`: pad/zero the partial fill to `A`, submit it, drain all, then
  `file.truncate(position)` iff the last buffer was padded. Zero `fillPos` afterwards;
  writing may continue.
- `preallocationSize` → `file.allocate(0, size)` in `open`.
- Latch the first failure; drain outstanding in `close()`; always release the arena.

### 4.4 Alignment defaults

`alignment = 4096` is safe for this host's ext4 data NVMe and the tmpfs `/tmp` test
filesystem (O_DIRECT works there per B1). Users of other O_DIRECT devices should pass
their device's DMA alignment. Input works with arbitrary unaligned offsets/lengths;
output requires `position() % alignment == 0` for the alignment trick to be exact (it is,
because all writes start at 0 with `A`-sized chunks).

## 5. Test contract (worker: stream-tests)

New files under `src/test/java/com/davidvlijmincx/lio/api/`
(`JuringInputStreamTest.java`, `JuringOutputStreamTest.java`, or one combined
`JuringStreamsConformanceTest.java` + `JuringStreamsBoundedMemoryTest.java`). Maven
surefire runs them; use a dedicated `JUring` in `@BeforeEach`/`@AfterEach` and `@TempDir`
files, following `JuringFileConformanceTest` patterns. Required coverage:

1. **Round trip / EOF:** write 4 MiB pattern with `JuringOutputStream` (buffered and
   O_DIRECT) → read back with `JuringInputStream` and compare all bytes; file length ==
   bytes written; empty file yields empty chunks / `read == 0`.
2. **Complete-until-done reads:** `read(dst)` fills `dst` across chunk boundaries
   (dst size > bufferSize), returns short only at EOF, returns 0 past EOF; `read(byte[],
   off, len)` argument checks.
3. **Chunk contract:** chunks ≤ `bufferSize()`, sum equals file content, empty only at
   EOF; chunk is a valid view until the next stream call (assert next call gets correct
   continuing bytes).
4. **Window:** `open(file, offset, length, opts)` returns exactly that range (offset
   unaligned, length > EOF clamps, length 0); `position()`, `remaining()`.
5. **Skip:** skip across chunk boundaries and within a chunk then read → matches bytes at
   the new position; skip past the declared window clamps; skip 0.
6. **Write matrix:** sources of sizes 1, alignment-1, bufferSize-1, bufferSize,
   bufferSize+1, 4 MiB+3 → file content and final length exact; `flush` mid-stream then
   continue writing (aligned case; unaligned restart must throw `IllegalStateException`);
   `write` after `flush` works; `bytesWritten()/position()`.
   Also test a partial `read(dst)` (dst smaller than the chunk) followed by another
   `read(dst)`/`readChunk()`: the stream must continue contiguously (internal carry), and
   `position()/bytesRead()/remaining()/eof()` must reflect the unconsumed carry.
7. **Write-behind:** write > (writeBehind+1) buffers without flush; after flush content
   exact; short-write alignment padding leaves no tail bytes (final length == logical).
8. **Options:** defaults, `with*` immutability, invalid values throw
   `IllegalArgumentException`; dynamic adjustments on == functional parity, bounded.
9. **Lifecycle/errors:** use-after-close throws `IllegalStateException`; double
   `close()` no-op; write on a read-only file throws typed `JuringException`; write to a
   read-only fd yields `EBADF`; close with in-flight reads (open mid-file, read few
   chunks, close) does not hang and releases.
10. **Bounded memory:** after reading/writing hundreds of MiB, assert
    `bufferMemoryBytes()` stays within §2 bounds and does not grow with bytes processed;
    assert no retained per-chunk growth (e.g. measure allocated-bytes growth over a
    200 MiB read with a fixed baseline, allowing a small constant).

## 6. Write scopes (advisory; disjointness is the rule)

| Task | Owner | Write scope |
|---|---|---|
| design + core streams | Lead | `docs/b2-design.md`, `src/main/java/com/davidvlijmincx/lio/api/{JuringInputStream,JuringOutputStream,InputStreamOptions,OutputStreamOptions}.java` |
| stream tests | stream-tests | `src/test/java/com/davidvlijmincx/lio/api/JuringStream*Test.java` |
| JUring stream bench + driver/analyzer | juring-bench | `bench/juring/JUringStreamBench.java`, `bench/juring/run-juring-stream.sh`, `bench/stream/**` |
| seastar stream bench | seastar-bench | `bench/seastar/seastar_stream_bench.cc`, `bench/seastar/build-stream-bench.sh`, `bench/.gitignore` |
| report / handover / commit | Lead | `docs/b2-report.md`, `docs/HANDOVER.md`, git commit |
| verification scratch | verify (later) | `scratch/b2-review/**` (gitignored) |

**Do not edit:** `bench/juring/JUringFileBench.java`, `bench/seastar/seastar_file_bench.cc`,
`bench/m1/**`, `src/main/java/.../{JuringFile,JUring,IoUringDispatcher,OperationType}.java`.
**Do not touch the data device or run benchmarks while another benchmark/L0 run is
active.** Maven is not safe to run concurrently: the Lead compiles the main tree and
announces "API compiled"; after that stream-tests may run
`mvn -o test -Dtest='JuringStream*'` only when the Lead says the build is idle; the Lead
runs the final full `mvn -o test`.

## 7. Verification plan (Lead + verify)

1. `mvn -o test` full suite green (B1 baseline 142 + new stream tests).
2. Independent review of the stream sources (alignment/lifetime/error/lifecycle) + an
   adversarial test pass; findings fixed by the Lead.
3. Bounded-memory evidence from the tests + stream `bufferMemoryBytes()` reported.
4. L0 non-regression: W1/W3 juring-only, R=5, 10/30 s, CPU 2 (B1 protocol §7) compared
   to the stored M1 baseline; engine hot paths are untouched so this is a control, not a
   suspect.
5. Preliminary W21/W22 (both engines, §3.3) → ratio table → B2 report/M2 readiness.
6. Docs + commit.

## 8. Risks / known limitations

- The stream layer is single-threaded and ring-owned (B1 constraint); a ring-level
  completion dispatcher is B3.
- `JuringFile.await` reaps with a `ResultConsumer` and a bounded ledger. An input stream
  needs the file ledger to hold `readAhead + 1` operations and the ring SQ to hold at
  least `readAhead + 2` entries; keep `withInFlightCapacity(readAhead + 2)` and ring
  entries `>= readAhead + 2` (the benchmark uses `max(64, 2*qd+8)` and `max(256, 2*qd)`).
  The prefetch pipeline submits mid-batch on SQ-full, but a window larger than the file's
  ledger capacity cannot work and fails with a clear `IllegalStateException`. Callers may
  still keep the ledger small (e.g. 64) to reduce the linear scan cost.
- An output stream needs `writeBehind` ledger slots; keep
  `withInFlightCapacity(writeBehind + 2)`.
- O_DIRECT requires aligned buffers; streams align internally, but `bufferSize` smaller
  than the device alignment is rounded up for output writes (documented, bounded).
- Buffered vs O_DIRECT: seastar `fstream` is DMA/O_DIRECT on this filesystem; the
  primary W21/W22 rows are therefore O_DIRECT on both sides. A JUring buffered row is
  exploratory only (F0 asymmetry disclosure).
- Full plan-M2 (60 s cells, DS-M multi-file W22, 300 s rests) remains a separate run;
  B2 delivers the layer, the harness and a preliminary verdict.
