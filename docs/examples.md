# Runnable examples

Four small, self-checking examples live next to the test suite as JUnit tests, so the same
`mvn` command that runs the suite runs the examples. They mirror seastar use-cases, but they
use only the public JUring API and JUnit's `@TempDir` — no benchmark device paths
(`/mnt/TestData`, `nvme-data/`), no fixed files, no timing or throughput *performance*
assertions. (A JUnit `@Timeout` is used only as a hang guard, never as a performance
assertion.) Each class has one `@Test` and asserts byte-level correctness plus
bounded-resource invariants (buffer-pool accounting, option-bounded stream memory, clean
close).

## Run them

```sh
mvn -o test -Dtest='*ExampleTest'
```

The examples are part of the normal suite too, so the plain build command runs them:

```sh
mvn -o test
```

Requirements are the project's: Linux with io_uring, JDK 25, and
`--enable-native-access=ALL-UNNAMED` (the Maven Surefire configuration already adds that
flag; add it if you run the tests from an IDE). `-o` (offline) is used throughout this repo
because the build is reproducible from the local Maven repository; drop it if you need to
resolve a missing dependency online.

## Mapping to seastar

| id | example | JUring APIs exercised | seastar use-case it maps to | acceptance assertion |
|---|---|---|---|---|
| E1 | [DirectFileExampleTest](../src/test/java/com/davidvlijmincx/lio/examples/DirectFileExampleTest.java) | `JUring.registerFiles`, `registerBuffers`, `checkOutBuffer`/`checkInBuffer`, `prepareWriteFixed`/`prepareReadFixed`, batched `submitAndWaitForBatchResult` | `open_file_dma` + `file::dma_read` / `file::dma_write` (aligned direct I/O) | 8 blocks written then read back byte-identical; buffer pool is fully owned during the batch and fully returned after it |
| E2 | [StreamCopyExampleTest](../src/test/java/com/davidvlijmincx/lio/examples/StreamCopyExampleTest.java) | `JuringInputStream` (`readAhead`, `read`, `eof`, `bufferMemoryBytes`), `JuringOutputStream` (`writeBehind`, `write`, `flush`, `durability`), `InputStreamOptions`/`OutputStreamOptions`, `JuringFile` | seastar `fstream` read-ahead / write-behind stream copy | copied file is byte-identical to the source; stream memory equals the option-bounded formula while open and 0 after close; after `flush()` file length equals stream position |
| E3 | [ManyFilesExampleTest](../src/test/java/com/davidvlijmincx/lio/examples/ManyFilesExampleTest.java) | one `JUring` + 8 `JuringFile`s + `register()` (fixed-file slots), `registerBuffers`, raw `prepareWriteFixed`/`prepareReadFixed` on each slot, batched `submitAndWaitForBatchResult` | seastar many-open-`file` pattern / io_uring multi-file batching | 8 files × 2 offsets round-trip independently, per-file offsets and contents correct; fixed-buffer pool capacity bound holds |
| E4 | [VirtualThreadsExampleTest](../src/test/java/com/davidvlijmincx/lio/examples/VirtualThreadsExampleTest.java) | `JUringBlocking` (`read`, `write`) shared by 8 virtual threads, with one poller | seastar futures + reactor (the ergonomic opposite: blocking calls vs continuations) | every virtual thread writes and reads back its own record; final file is the concatenation of all records; bounded poller/thread budget |

## What each example demonstrates

### E1 — direct file, registered file + fixed buffers, batched submit

`DirectFileExampleTest` opens a file with `O_DIRECT`, registers the descriptor in the ring's
fixed-file table and a pool of 4 KiB fixed buffers, then prepares 8 aligned writes into the
registered file slots and submits them as one batch. The completion consumer reaps
`Result#bufferIdx()` and checks each buffer back in. The test asserts that the buffer pool is
exhausted while the batch is in flight, that every buffer is returned after the batch, and
that reading the blocks back through the same fast path yields byte-identical data. This is
the closest thing to seastar's `open_file_dma` + `dma_read`/`dma_write` in JUring: one
descriptor, one registered buffer set, explicit offsets, no page-cache copy.

### E2 — stream copy with read-ahead and write-behind

`StreamCopyExampleTest` copies a 128 KiB pattern file through `JuringInputStream` (16 KiB
buffers, read-ahead depth 2) and `JuringOutputStream` (16 KiB buffers, write-behind depth 2),
then compares the destination byte for byte. It checks the documented bounded-memory
formula: the reader holds `(readAhead + 1)` aligned buffers and the writer
`(writeBehind + 1)`, both allocated once at open and released by `close()`. It also checks
that after `flush()` the file length equals the logical stream position, i.e. the
write-behind queue was drained. The 128 KiB size is a multiple of the alignment so the copy
works on the `O_DIRECT` path.

Note on the ring setup: each stream owns its own ring in this example. `JuringFile`'s
blocking helpers require every in-flight operation on their ring to belong to that one file
(the B1 ownership rule), so a read-ahead reader and a write-behind writer that are active at
the same time cannot share a ring; seastar can do both on one reactor because the reactor
owns the ring and every file object participates in it.

### E3 — one ring, many files

`ManyFilesExampleTest` opens 8 independent `O_DIRECT` files on one `JUring`, registers each
as a fixed-file slot, and issues 16 writes (two offsets per file) in a single batched
submission, followed by 16 reads in a single batch. Every file has its own deterministic
content, so per-file offsets and independent completion are checked, not just aggregate
byte counts. Because `JuringFile` blocking helpers are single-owner by contract, the batch is
prepared with the raw `JUring.prepare*` methods using each handle's `registeredSlot()` and
reaped at ring level — the multi-file pattern is a ring-level batch in JUring, whereas
seastar expresses it as many file objects multiplexed by one reactor.

### E4 — eight virtual threads on one blocking ring

`VirtualThreadsExampleTest` starts 8 virtual threads behind a barrier, and each one writes a
32-byte record at its own offset and reads it back through one shared `JUringBlocking` with
a single poller (`new JUringBlocking(64, 1)`). The test asserts each virtual thread observes
its own bytes, that the file ends up as the concatenation of all records, and that every
future completes. The platform-thread budget is bounded by construction: one poller thread
plus the JDK's virtual-thread carrier pool, independent of the number of virtual threads;
each virtual thread parks while its request is in flight. seastar is the mirror image —
single-threaded per shard with futures and continuations, where a blocking call would be an
anti-pattern.

## seastar mapping and JUring differences

JUring targets the seastar **L1/L2 basics** (file/stream API and a multi-ring runtime), not
the seastar application model. The mapping is therefore at the I/O-operation level, and the
differences are deliberate:

- **No reactor / no futures.** seastar applications are single-threaded per shard and
  compose work as futures and continuations; a caller never blocks a thread. JUring's raw
  API is explicit prepare/submit/reap on the calling thread, and `JUringBlocking` adds a
  familiar blocking surface for virtual threads. There is no coroutine or task-graph layer —
  the framework built on top of JUring is a separate project.
- **Explicit ownership.** seastar's reactor owns the ring and multiplexes every file; in
  JUring the `JUring` ring is single-issuer/single-owner, and `JuringFile` blocking helpers
  require the ring's in-flight operations to belong to that handle. Multi-file and
  multi-owner batching is done with the raw `prepare*` API and ring-level reaping (E3).
- **Explicit buffers.** seastar's `fstream` manages read-ahead/write-behind internally, and
  seastar's DMA path uses aligned buffers that must stay alive until the future resolves.
  JUring's streams do manage option-bounded buffers internally, but on the raw fixed-buffer
  path the application checks indices out and back in (E1) — the kernel does not pick
  buffers for you.
- **Blocking is first-class.** `JUringBlocking` turns asynchronous completions into parked
  virtual threads with dedicated pollers (E4), which is the opposite of seastar's
  never-block-a-reactor rule; this is why the L2 comparison uses a separate configuration.

The capabilities behind these examples are snapshots of the roadmap work (B1 file API, B2
streams, B3 lifecycle, B4 shards, B5 metrics, B6 durability); see
[`docs/UPGRADING.md`](UPGRADING.md) for the capability history and
[`docs/api-stability.md`](api-stability.md) for what the public API promises.

## Notes and troubleshooting

- These are tests, not `main` methods; run them with JUnit (Maven) as shown above.
- Everything is created under JUnit's `@TempDir`; the tests delete it afterwards and never
  touch `/mnt/TestData` or `nvme-data/`.
- The examples intentionally contain **no timing or throughput performance assertion** — a
  JUnit `@Timeout` exists only to fail a hung test, not to check speed. They check
  correctness and bounded resources only. Performance measurement lives in `bench/` and
  `BENCHMARK.md`.
- If `O_DIRECT` is unavailable on your temp filesystem, the existing suite's direct-I/O
  tests fail the same way; run the build on a filesystem that supports `O_DIRECT` (the
  project's supported environment), or use the existing conformance tests as the reference.
