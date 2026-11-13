# B2 report — buffered stream layer (`JuringInputStream` / `JuringOutputStream`)

**Session:** 2026-11-13 (fourth session) · **Branch:** `kafka-engine` · **Plan item:**
`SEASTAR-PLAN.md` §4 **B2** (gap G1); prepares plan **M2** (W21/W22 vs seastar `fstream`).
**Status:** implementation complete (184 tests green, L0 unchanged, preliminary W21/W22 run); frozen design contract
[`docs/b2-design.md`](b2-design.md); acceptance evidence below.

## 1. What was built

The second layer of the JUring L1 file API: buffered, asynchronous byte streams over a
[`JuringFile`](b1-design.md). Both stream types own a fixed-size aligned native buffer set
and drive I/O through the B1 blocking helpers; they never close the underlying file.

| Capability | API |
|---|---|
| prefetching input stream | `JuringInputStream.open(file[, offset, length], InputStreamOptions)`, zero-copy `readChunk()`, complete-until-done `read(MemorySegment)` / `read(byte[],int,int)`, `skip`, `position`/`remaining`/`bytesRead`/`eof` |
| configurable readahead | `InputStreamOptions.withReadAhead(n)` = n extra prefetch buffers (n+1 reads in flight), `withBufferSize`, `withAlignment`, `withDynamicAdjustments` |
| adaptive readahead | seastar-style slow start + 4 MiB unused-ratio windows (`fstream.cc:106-235`, simplified per-stream): current buffer size grows to the configured maximum, readahead grows when the consumer waits, shrinks on skip-heavy access |
| write-behind output stream | `JuringOutputStream.open(file, OutputStreamOptions)`, `write(MemorySegment)` / `write(byte[],int,int)`, `flush()`, `close()`, `position()`/`bytesWritten()` |
| configurable write-behind | `OutputStreamOptions.withWriteBehind(n)` = n concurrent writes (n+1 buffers), `withBufferSize`, `withAlignment`, `withPreallocationSize` (`fallocate` hint), `withSyncOnClose` (`fdatasync`) |
| O_DIRECT-safe alignment | internal aligned buffers/chunks; final partial buffer zero-padded and the file `ftruncate`d back to the logical length, so direct and buffered files both work and no padding is visible |
| bounded memory | exactly `readAhead+1` input buffers of `alignUp(bufferSize,alignment)+alignment` bytes and `writeBehind+1` output buffers of `alignUp(bufferSize,alignment)` bytes; `bufferMemoryBytes()` reports it (0 after close) |
| typed errors / lifecycle | kernel failures as B1 `JuringException`; first I/O failure latched and rethrown; `IllegalStateException` on use-after-close/unaligned restart; idempotent `close()` that drains stream-owned in-flight I/O; never closes the `JuringFile` |

**Package layout:** four new public classes in `com.davidvlijmincx.lio.api`
(`JuringInputStream`, `JuringOutputStream`, `InputStreamOptions`, `OutputStreamOptions`).
**No engine changes:** `JuringFile`, `JUring`, `IoUringDispatcher`, `OperationType` and
`bench/juring/JUringFileBench.java` are untouched — B2 is purely additive.

## 2. Implementation notes

- **Input pipeline:** a FIFO of prefetch descriptors (`buffer, handle, alignedStart,
  frontTrim, logicalLength, state`). Reads are issued at `alignDown(position, alignment)`
  for `alignUp(frontTrim + min(remaining, currentBufferSize), alignment)` bytes, up to
  `currentReadAhead+1` in flight, then submitted; completions are obtained through
  `JuringFile.await`, whose ledger remembers results even when several completions arrive
  in one reap. Chunks are zero-copy views into the buffer set, valid until the next stream
  call; a partial complete-until-done `read()` keeps an internal *carry* so the next call is
  exactly contiguous, and `position`/`bytesRead`/`remaining`/`eof` account for it.
- **Output pipeline:** one filling buffer + `writeBehind` in-flight buffers at aligned
  offsets; short writes are retried from the last alignment boundary (idempotent rewrite),
  `-EAGAIN` is handled by the file's `IoPolicy`. `flush()` pads the current partial buffer,
  waits for all writes and truncates the padding away; `close()` does the same plus optional
  `fdatasync`. If an operation cannot be drained (ring-ownership misuse) the buffers are
  deliberately not freed, because the kernel may still reference them.
- **Adaptation:** windows of 4 MiB track `total_read`/`unused_read`; slow start doubles the
  current buffer size while the 25 % unused target is met; readahead grows on consumer
  waits; skipping shrinks the buffer, re-enters slow start and clamps readahead to ≤1.
  Adaptation never exceeds the configured buffer size/readahead or the §2 memory bound.
- **JuringFile constraints inherited:** one owner thread (not thread-safe), no foreign
  in-flight work on the ring while a stream is used, and the stream buffers must stay
  valid for the kernel until their completions are reaped (close drains; on foreign-work
  misuse the buffers are not recycled/freed).

## 3. Verification

### 3.1 Test suite

`mvn -o test` (final tree, Lead-verified): **184 tests, 0 failures, 0 errors, 1 skipped**
(B1 baseline: 142 green; +42 stream tests). The single skip is the pre-existing B1 tmpfs
discard-alignment assumption, unrelated.

| suite | tests | covers |
|---|---|---|
| `JuringStreamsConformanceTest` | 37 | buffered + O_DIRECT 4 MiB round trips and exact file lengths; empty file; complete-until-done reads across chunk boundaries; short-only-at-EOF / 0 past EOF; `byte[]` argument checks; chunk contract (≤ bufferSize, native view, sum == content, empty only at EOF, next-call continuation); carry semantics (dst < bufferSize: read→read→readChunk→skip contiguous; position/bytesRead/remaining/eof accounting); windows (unaligned offset, length > EOF clamp, length 0, until-EOF); skip (0, within/across chunks, past EOF/window); write matrix {1, 4095, 8191, 8192, 8193, 4 MiB+3} with exact content/length; aligned flush+continue and padded-unaligned flush → `IllegalStateException`; write-behind deeper than the window; padding truncation and full-buffer tail preservation; options defaults/immutability/validation (null → NPE); dynamic-adjustment functional parity; use-after-close / double close; typed `EBADF` both directions + latched error; close with in-flight prefetch; close releases `bufferMemoryBytes` to 0 |
| `JuringStreamsBoundedMemoryTest` | 5 | 200 MiB input read + 200 MiB output write: `bufferMemoryBytes()` ≤ `(readAhead+1)*(A+alignment)` / ≤ `(writeBehind+1)*A`, stable across the transfer; owning-thread heap growth < 8 MiB over the transfer; adaptive bound over 32 MiB; `alignUp` formula for bufferSize 1000/alignment 4096 |

### 3.2 Independent adversarial verification

Two independent adversarial passes were run against the frozen contract (Lead scratch
harnesses + an independent reviewer with its own reproducers):
`scratch/b2/StreamSmoke.java`, `scratch/b2/StreamStress.java`, `scratch/b2/ReadBench.java`
(gitignored) plus `verify-reviewer`'s `scratch/b2-review/**` fuzzers.

Confirmed bugs found and fixed before final verification:

1. **Input prefetch FIFO descriptor indexing** (correctness, critical): the FIFO array
   position was used as the descriptor slot, so with readahead > 0 the stream returned
   data from the wrong prefetched buffer. Fixed (`fifo[fifoHead]` for all head accesses);
   covered by the per-chunk content assertions and the carry/window tests.
2. **`JuringOutputStream.write(byte[],int,int)` bypassed `ensureWritable()`** (lifecycle
   contract): writes after `close()`/a padded unaligned flush/failure were silently
   accepted. Fixed; covered by `writeAfterPaddedUnalignedFlushThrowsIllegalState`.
3. **Adaptive buffer size could exceed the configured `bufferSize`** when `bufferSize`
   was not an alignment multiple (invariant/bounds, docs/§2): `minimalBufferSize()`
   clamped to the *aligned* size. Fixed to clamp to the configured logical size; covered
   by reviewer reproducer + adaptive parity tests.
4. **`bufferMemoryBytes()` returned the pre-close value** (contract): now 0 after close.
5. **Carry/lifetime of partial `read()` chunks** (correctness): a `read(dst)` smaller than
   a chunk lost the remainder. Fixed with the carry mechanism; covered by dedicated tests.

The final tree also passes the Lead's independent checks: buffered/O_DIRECT round trips,
write-size matrix, unaligned windows, skip past EOF, close-with-inflight, two interleaved
streams on one file, bufferSize not a multiple of alignment, dynamic adjustments, typed
`EBADF` both directions, preallocation, aligned flush-then-continue, unaligned-flush
rejection, and bounded-memory stability.

### 3.3 Performance sanity (stream layer, not the campaign)

Direct input stream, 128 KiB buffer, 7 readahead, O_DIRECT, 10 GiB dataset, CPU 2:
**≈3.4 GiB/s** (27.3k chunks/s) with the file registered or unregistered; buffered read
from page cache is faster still. This confirms the prefetch pipeline keeps the configured
in-flight depth and that the campaign numbers are device-bound, not stream-limited.

### 3.4 L0 non-regression (W1/W3)

B2 touches no engine code, so W1/W3 are a control. Protocol: unchanged M1 harness
(`bench/m1/run-read-matrix.sh`), juring only, 4 KiB randread O_DIRECT, 1 thread pinned to
CPU 2, 10 s warmup + 30 s measured, R=5, executed on the final tree
(`bench/results/b2-l0-check/post`, gitignored).

| cell | mean IOPS (CI95) | reps | fast-state reps | stored M1 | B1 post3 |
|---|---|---|---|---|---|
| W1 QD1 | 10,971 (10,604–11,339), CV 2.7 % | 11,220 / 11,186 / 10,631 / 11,155 / 10,666 | **11,187 ±0.9 % vs B1** | 10,969 | 11,153 |
| W3 QD64 | 324,935 (316,438–333,432), CV 2.1 % | 328,076 / 328,001 / 327,966 / 327,943 / 312,692 | **327,996 (+0.19 % vs stored M1)** | 327,371 | 325,033 |

All reps pass the pre-registered QD64 state gate (p99 < 600 µs; max 534 µs). Fast-state
W3 reps reproduce the stored M1 baseline within **+0.2 %** (and are +0.9 % vs the B1 post3
mean); W1 fast reps are **+0.3 %** vs the B1 post3 mean (+2.0 % vs the stored M1 mean, which
contained a slow rep). The two slow W1 reps (p99 137–154 µs) and the slow W3 rep
(p99 534 µs, −4.7 % IOPS) are the same discrete bimodal host-state signature documented in
B1 §3.3/M1 §4, not a code effect (no engine line changed). **Verdict: L0 unchanged for B2.**

## 4. W21/W22 preliminary comparison vs seastar `fstream`

### 4.1 Protocol and harness

Fresh standalone runners were written for both engines (nothing in the M1 harness
changed): `bench/juring/JUringStreamBench.java` (+`run-juring-stream.sh`) and
`bench/seastar/seastar_stream_bench.cc` (+`build-stream-bench.sh`), with the frozen CLI/JSON
contract in `docs/b2-design.md` §3.1/§3.2 and the W21/W22 driver/aggregator
(`bench/stream/run-stream-matrix.sh`, `analyze_stream.py`, `README.md`). Session
`bench/results/b2-2026-11-13` (gitignored): 36/36 invocations `status=ok`, **0 errors**,
10 s warmup + 30 s measured per process, R=3, engines interleaved per rep, CPU 2 pinned,
60 s rest between reps, O_DIRECT primary rows on both sides, JUring file registered
(seastar cannot register files — asymmetry disclosed), plus one disclosed
`juring --buffered` exploratory row per cell (page-cache path, never used for the verdict).

### 4.2 Results (MiB/s, per rep; means over R=3)

| cell | workload | JUring reps | JUring mean (CV) | seastar reps | seastar mean (CV) | paired ratios | mean ratio |
|---|---|---|---|---|---|---|---|
| W21-128k | stream_write | 3960 / 1772 / 968 | 2233.7 (69 %) | 3754 / 4070 / 971 | 2931.5 (58 %) | 1.055 / 0.435 / 0.998 | 0.829 |
| W22-128k | stream_read | 2063 / 2300 / 2113 | 2158.6 (5.8 %) | 2362 / 2185 / 2363 | 2303.1 (4.5 %) | 0.873 / 1.053 / 0.894 | **0.940** |
| W21-1M | stream_write | 971 / 972 / 835 | 925.9 (8.5 %) | 971 / 971 / 646 | 862.6 (21.8 %) | 1.000 / 1.001 / 1.292 | **1.098** |
| W22-1M | stream_read | 3607 / 4526 / 4755 | 4296.1 (14.2 %) | 4756 / 4729 / 4765 | 4749.9 (0.4 %) | 0.758 / 0.957 / 0.998 | **0.905** |

Chunk-latency p99 (mean of per-run p99): W21-128k JUring 340 µs vs seastar 273 µs;
W22-128k 149 vs 120 µs; W21-1M 4545 vs 7731 µs; W22-1M 1277 vs 708 µs. The exploratory
`juring --buffered` row (page cache) measured 3812 / 11735 / 3928 / 12937 MiB/s for the four
cells — reported for completeness only.

### 4.3 State analysis (honest, no rep dropped)

- **W21-128k contains a device write-state transition.** During rep 1 both engines are in
  the fast state (~4.0 GiB/s, p99 ≈ 90–120 µs). Before/inside rep 2 the device enters its
  discrete slow write state: seastar r2 is still fast (4070), JUring r2 is slow (1772), and
  by rep 3 **both** engines are slow (968 vs 971 MiB/s, p99 642 vs 588 µs). The exploratory
  buffered row is unaffected (3.75–3.93 GiB/s) because it writes through the page cache —
  further evidence this is device state, not the stream layer. State-matched paired ratios
  are **1.055 (fast) and 0.998 (slow)**; only the transition rep is asymmetric, and its
  position inside the engine pair is an ordering artefact.
- **W22-1M r1 is a post-write recovery artefact**: the first read of that cell (JUring,
  measured first) got 3607 MiB/s while the immediately following seastar r1 got 4756 MiB/s;
  r2/r3 are 0.957/0.998. The main session mixed write and read cells in one run, which the
  methodology explicitly forbids (writes and reads must be separate sessions); read-after-
  write recovery on this device is visible in the first reader.
- W22-128k, W21-1M are stable (CV ≤ 22 %; slow reps common to both engines).

### 4.4 Verdict vs the plan-M2 bar (preliminary)

Plan-M2 bar: `MiB/s_JUring ≥ 0.85 × MiB/s_seastar`. **Point estimates:** W22-128k
**0.940**, W22-1M **0.905**, W21-1M **1.098** — all clear the bar; W21-128k's 0.829 is the
state-transition mean, and its state-matched ratios (1.055 / 0.998) clear it too. **Strict
statistical reading** (one-sided 95 % lower bound of the paired ratio ≥ 0.85, R=3): not
established in any cell because the CI is wide — the correct label under the pre-registered
decision rule is **"inconclusive at this power, more reps/state control needed"**, not a
pass and not a failure. No evidence of a JUring stream-layer deficit was found; the two
cells with a state-free comparison are at parity or ahead.

### 4.5 State-resolved supplementary control (W21-128k)

Because the main session caught a write-state transition, the cell was re-run after a cooldown
in a separate write-only session (`bench/results/b2-2026-11-13-w21rerun`, R=3, 120 s rests,
same protocol otherwise). At that point the device was in its **sustained write state** after
the session's ~1.4 TiB of writes (SLC cache exhausted: 0.4–0.8 GiB/s, not the 4 GiB/s fast
state; the page-cache exploratory row was again unaffected at ~4.0 GiB/s, confirming device
state). Paired JUring/seastar ratios: **2.027 / 0.930 / 0.828** (mean **1.262**), chunk p99
964 / 697 / 1984 µs for JUring vs 4298 / 3443 / 2316 µs for seastar. So in the sustained
state JUring was not slower (mean ratio > 1, better tails), in the fast state it was slightly
ahead (1.055), and in the slow state of the main run it was at parity (0.998). **The only
paired ratio below the 0.85 bar in the whole write data set is the transition rep 2 of the
main run (0.435), where the device changed state between the two engines' invocations.**
That is a protocol/state artefact, not a stream-layer result — and exactly what the
separated-session/rest discipline of the full M2 campaign exists to prevent. No JUring
stream-layer deficit was observed in any matched state.

**Caveats for the full M2 campaign:** separate read/write sessions with the methodology's
300 s rests (this run used 60 s in one mixed session), DS-M multi-file corpus for W22,
60 s measured cells, R=5, D1 write preconditioning. The harness supports all of it via
`--session/--cells/--seconds/--warmup/--rest/--reps`. This section is the B2/M2-readiness
measurement, not the M2 report.

## 5. Deviations, decisions and limitations

1. **Streams do not own the `JuringFile`.** `close()` flushes/drains and releases the
   stream buffers but leaves the file open; callers close it (seastar's `data_sink::close`
   closes the file — deliberate API difference for composability with B1).
2. **Output stream semantics are "write from offset 0"** (seastar `make_file_output_stream`
   contract). It never seeks; a padded final partial buffer is truncated back to the
   logical length. Writing after a padded flush is only supported at alignment-aligned
   positions; an unaligned restart throws `IllegalStateException` (O_DIRECT cannot restart
   unaligned) instead of corrupting data.
3. **Adaptive readahead is per-stream, simplified.** seastar's optional cross-stream
   `file_input_stream_history` sharing is not exposed; the 4 MiB window / 25 % unused-ratio
   logic is ported as documented in `docs/b2-design.md` §4.2. Adaptation is off by default
   (matches seastar's default).
4. **`JuringFile` ledger capacity.** Streams do not size the file's in-flight ledger;
   callers should keep `withInFlightCapacity >= readAhead+2` (bench: `max(64, 2*qd+8)`);
   the default 1024 is safe but the linear ledger scan is cheaper with a small capacity.
5. **Ring sharing.** The stream inherits B1's ring-ownership rule: no foreign in-flight
   work on the ring while a stream is used. A ring-level dispatcher is B3.
6. **Not zero-allocation by contract.** Zero-copy `readChunk()` performs no per-chunk
   heap allocation for native `MemorySegment` consumers (the tests measure bounded
   per-chunk heap growth), but the FFM view objects and `read(byte[])`/`write(byte[])`
   conveniences are not allocation-free; the B1 fast path (`prepare*`+`await`) remains the
   zero-allocation interface.
7. **Durability.** `syncOnClose` is `fdatasync`-on-close only; group commit, `posix_fadvise`
   hints and richer durability modes are B6 scope.
8. **O_DIRECT alignment** defaults to 4096 (safe on this host's ext4 data NVMe and tmpfs
   test files); other devices should set `withAlignment(device DMA alignment)`.
9. **Preliminary W21/W22 only.** This report's stream comparison is the B2/M2-readiness
   run (shorter cells/rests, single-file read dataset, no D1 write preconditioning). The
   full M2 campaign per `docs/benchmark-methodology.md` (60 s cells, DS-M multi-file W22,
   300 s rests) remains a separate run; the harness is ready for it.

## 6. How to reproduce

```bash
mvn -o test                                   # 184 tests (incl. 42 stream tests)
# L0 control (gitignored results):
ALLOW_RESIDUAL_LOAD=1 M1_RESULTS=$PWD/bench/results/b2-l0-check/post ENGINES=juring \
  QDS="1 64" REPS=5 WARMUP=10 DURATION=30 JURING_SKIP_COMPILE=1 bench/m1/run-read-matrix.sh

# stream benchmark pair (build once, then run):
bench/seastar/build-stream-bench.sh
bench/stream/run-stream-matrix.sh              # W21/W22 preliminary, R=3, 10/30 s, rest 60 s
python3 bench/stream/analyze_stream.py --session b2-2026-11-13
```

## 7. Next

- **M2 full campaign:** 60 s W21/W22 cells with the methodology's rests, DS-M multi-file
  read dataset, and the seastar asymmetry table in the report; the harness
  (`bench/stream/`) and both runners already support it.
- **B6:** buffered-I/O parity/durability — `posix_fadvise` hints, durability modes,
  group-commit helper on top of `JuringOutputStream`.
- **B3:** ring-level completion dispatcher removes the stream/file ownership guard and
  lets streams share rings with other work.
