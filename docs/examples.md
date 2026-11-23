# Runnable examples

I keep four examples next to the test suite as JUnit tests, not `main` methods: the same
`mvn` command runs them, and they check themselves. A JUnit `@Timeout` is a hang guard, never
a speed assertion; performance measurement lives in `bench/` and `BENCHMARK.md`.

## Run them

```sh
mvn -o test -Dtest='*ExampleTest'
```

They are part of the normal suite, so `mvn -o test` runs them too. You need Linux
with io_uring, JDK 25, and `--enable-native-access=ALL-UNNAMED` (Surefire already adds the
flag; add it when running from an IDE). Drop `-o` if a dependency is missing. If `O_DIRECT`
is unavailable on your temp filesystem, the direct-I/O tests fail; use a supporting
filesystem.

## The four examples

| id | example | what it shows | Seastar pattern |
|---|---|---|---|
| E1 | [DirectFileExampleTest](../src/test/java/com/davidvlijmincx/lio/examples/DirectFileExampleTest.java) | Registered file plus fixed-buffer pool, one batched submit | `open_file_dma` + `dma_read` / `dma_write` |
| E2 | [StreamCopyExampleTest](../src/test/java/com/davidvlijmincx/lio/examples/StreamCopyExampleTest.java) | Read-ahead/write-behind copy with bounded memory | `fstream` copy |
| E3 | [ManyFilesExampleTest](../src/test/java/com/davidvlijmincx/lio/examples/ManyFilesExampleTest.java) | Many files on one ring, one batched submit | many `file` objects on one reactor |
| E4 | [VirtualThreadsExampleTest](../src/test/java/com/davidvlijmincx/lio/examples/VirtualThreadsExampleTest.java) | Eight virtual threads blocked on one facade | the opposite: blocking calls, not futures |

### E1 — Direct file I/O

`DirectFileExampleTest` opens a file with `O_DIRECT`, registers the descriptor in the ring's
fixed-file table and a pool of 4 KiB fixed buffers, then prepares 8 aligned writes into the
registered slots and submits them as one batch. The completion consumer reaps
`Result#bufferIdx()` and checks each buffer back in. The test checks that the pool is
exhausted while the batch is in flight, full again afterwards, and that reading the blocks
back gives byte-identical data.

This is the closest thing JUring has to Seastar's DMA path: one descriptor, one registered
buffer set, explicit offsets, no page-cache copy.

### E2 — Stream copy

`StreamCopyExampleTest` copies a 128 KiB pattern file through `JuringInputStream` (16 KiB
buffers, read-ahead depth 2) and `JuringOutputStream` (16 KiB buffers, write-behind depth
2), then compares the destination byte for byte. It checks the documented memory formula —
reader holds `readAhead + 1` aligned buffers, writer `writeBehind + 1`, allocated at open
and released by `close()` — and that after `flush()` the file length equals the stream
position, so write-behind really drained. 128 KiB is a multiple of the alignment,
which keeps the copy on the `O_DIRECT` path.

Both streams share **one** `JUring` here. The blocking helpers route each completion to the
handle that issued it, so a reader and a writer can be active on the same ring at once, like
Seastar's single reactor. Only raw, un-reaped `JUring.prepare*` work still makes the blocking
helpers throw `IllegalStateException`.

### E3 — Many files on one ring

`ManyFilesExampleTest` opens 8 independent `O_DIRECT` files on one `JUring`, registers each
as a fixed-file slot, and issues 16 writes (two offsets per file) in one batched submission,
then 16 reads in one batch. Each file has deterministic content, so the check is per-file
offsets and independent completion, not an aggregate byte count.

It drives the eight `JuringFile` handles with the raw `JUring` `prepare*`/reap API, using
each handle's `registeredSlot()` — the lowest-overhead submission form, which is why this
example uses it. The same handles could also run through their blocking helpers on the
shared ring. Seastar has the same pattern: many file objects multiplexed by one reactor.

### E4 — Virtual threads

`VirtualThreadsExampleTest` starts 8 virtual threads behind a barrier. Each writes a 32-byte
record at its own offset and reads it back through one shared `JUringBlocking` with a single
poller (`new JUringBlocking(64, 1)`). It checks each thread sees its own bytes, that the
file ends up as the concatenation of all records, and that every call completes.

The platform-thread count comes from the facade, not from the calling virtual threads: one
poller thread plus the JDK's virtual-thread carrier pool. Each virtual thread parks (unmounts)
while its request is in flight or while it waits for a call record. The resource to size for
a bigger fan-out is that call-record capacity, because every admitted call owns one
pre-allocated record.

`BlockingOptions.withMaxConcurrentCalls(...)` sizes that pool, and
`OverflowPolicy.BLOCK`/`FAIL` controls what a call does when the pool is full (both are
0.1.0 API). Without `BlockingOptions`, capacity is derived from the queue depth, and eight
calls are well inside the pool for `new JUringBlocking(64, 1)`. The sizing recipe and memory math are in
[`docs/vt-scale.md`](vt-scale.md). Seastar is the mirror image: single-threaded per shard
with futures and continuations, where a blocking call would be an anti-pattern.

## How JUring differs from Seastar

The mapping is at the I/O-operation level, not the application model:

- **No reactor, no futures.** Seastar is single-threaded per shard and composes work as
  futures and continuations; a caller never blocks a thread. JUring's raw API is explicit
  prepare/submit/reap on the calling thread, and `JUringBlocking` adds a blocking surface
  for virtual threads. The framework built on top is a separate project.
- **Explicit ownership and buffers.** The `JUring` ring is single-issuer/single-owner, and
  several `JuringFile`-based handles can share it. Streams manage option-bounded buffers,
  but on the raw fixed-buffer path you check indices out and back in yourself (E1).
- **Blocking is first-class.** `JUringBlocking` turns completions into parked virtual
  threads with dedicated pollers (E4), the opposite of Seastar's never-block-a-reactor rule,
  which is why E4 is its own example.

