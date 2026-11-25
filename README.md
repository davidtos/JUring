# JUring

**Asynchronous file I/O for Java, directly on Linux io_uring.**

JUring is a Java library for asynchronous file I/O on Linux, built directly on
`io_uring`. It gives you a raw ring, a file API, bounded-memory streams, group commit
and a blocking facade for virtual threads, with no JNI, no C glue, no `liburing`, and
no runtime dependencies beyond the JDK.

[![CI](https://github.com/davidtos/JUring/actions/workflows/ci.yml/badge.svg)](https://github.com/davidtos/JUring/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.davidtos/juring.svg)](https://central.sonatype.com/artifact/io.github.davidtos/juring)
![Java 25+](https://img.shields.io/badge/Java-25%2B-orange)
![Linux](https://img.shields.io/badge/platform-Linux-important)
![Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-blue)

Java's main file API, `FileChannel`, is blocking. A read on a local file keeps the
thread inside the kernel until the disk answers, so keeping fast storage busy costs
you threads. `AsynchronousFileChannel` moves that blocking work to a thread pool, and
virtual threads still pin their carrier in a file syscall. `io_uring` has the
kernel and user space share the submission and completion queues, so one
thread can keep many operations in flight and reap them in batches.

I built JUring to get that interface into Java without the usual native layer. The
rings are mapped with the Foreign Function & Memory API and driven through `VarHandle`
accesses. On one consumer NVMe, a single ring reached **412,000 4 KiB random reads per
second** with 128 requests in flight: about 21% ahead of `fio` and 5% ahead of Seastar,
with about a quarter fewer CPU cycles per request at 64 in flight.

**Status:** Linux only; JDK 25+; the fast path targets kernel 6.1+.

**Use it if** you have file I/O on local NVMe that can keep more than one request in
flight, or many virtual threads doing file I/O and you want to avoid pinning carriers.

## What's in it

| Piece                                      | What it does                                                                                                                                                                |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `JUring`                                   | The raw single-threaded ring: reads, writes, vectored I/O, open/close, `fsync`/`fdatasync`, `fallocate`, `ftruncate`, `statx`, timeouts, linked timeouts, and cancellation. |
| Registered files and fixed buffers         | Kernel-side pins that remove per-operation descriptor and buffer lookups.                                                                                                   |
| `JuringFile`                               | A file handle on top of a ring: offset I/O, truncate, allocate, discard, `fadvise`, durability modes, and typed errors.                                                     |
| `JuringInputStream` / `JuringOutputStream` | Buffered streams with bounded read-ahead and write-behind. Each stream allocates a fixed set of buffers when it opens and reports its footprint.                            |
| `GroupCommit`                              | Batches durability for a group of writes on one owner thread: one `fdatasync` covers the whole batch instead of one per write.                                              |
| `JUringBlocking`                           | Ordinary blocking calls for virtual threads. A few poller threads submit and reap while callers park without pinning a carrier.                                             |
| `IoShard` / `RingGroup`                    | One ring per core, with work spread across them, affinity, backpressure, and cross-thread-safe statistics.                                                                  |
| Metrics                                    | Per-ring and per-shard counters, latency histograms, and JMX MXBeans.                                                                                                       |

Everything lives in `com.davidvlijmincx.lio.api`, exported by the `JUring` module.

## Install

0.1.0 is on Maven Central.

```xml
<dependency>
    <groupId>io.github.davidtos</groupId>
    <artifactId>juring</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation("io.github.davidtos:juring:0.1.0")
```

You need Linux with `io_uring` enabled, JDK 25 or newer, and `RLIMIT_MEMLOCK` headroom
if you want the registered-buffer path.

To build from source:

```sh
git clone https://github.com/davidtos/JUring.git
cd JUring
mvn install
```

A machine without `io_uring` can still build the library with `mvn -DskipTests package`.

## Quick start

Batched read: one registered file, a pool of fixed buffers, and a single
submission. The three ring flags turn on the fast path.

```java
import com.davidvlijmincx.lio.api.*;
import java.nio.file.Path;
import static com.davidvlijmincx.lio.api.IoUringOptions.*;

public class DirectRead {
    public static void main(String[] args) {
        try (JUring ring = new JUring(128,
                IORING_SETUP_SINGLE_ISSUER,
                IORING_SETUP_COOP_TASKRUN,
                IORING_SETUP_DEFER_TASKRUN);
             FileDescriptor file = new FileDescriptor(Path.of("data.bin"), LinuxOpenOptions.READ_DIRECT)) {

            ring.registerFiles(file);
            ring.registerBuffers(4096, 64);

            for (int i = 0; i < 64; i++) {
                int buffer = ring.checkOutBuffer();
                ring.prepareReadFixed(0, 4096, (long) i * 4096, buffer);
            }

            // One io_uring_enter submits all 64 reads and reaps the completions.
            ring.submitAndWaitForBatchResult(64, 64, result -> {
                System.out.println("read " + result.result() + " bytes");
                ring.checkInBuffer(result.bufferIdx());
            });
        }
    }
}
```

And here's the same idea with a blocking API on virtual threads. Each call parks its
virtual thread while the poller thread submits the request, so the carrier is free to
run other work:

```java
import com.davidvlijmincx.lio.api.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class VirtualThreadReads {
    public static void main(String[] args) throws Exception {
        try (JUringBlocking ring = new JUringBlocking(256);
             FileDescriptor file = new FileDescriptor(Path.of("data.bin"), LinuxOpenOptions.READ_DIRECT);
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < 1_000; i++) {
                long offset = (long) i * 4096;
                threads.submit(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment buffer = arena.allocate(4096, 4096);
                        long read = ring.read(file, buffer, offset);
                        if (read != 4096) throw new IllegalStateException("short read: " + read);
                    }
                    return null;
                });
            }
        }
    }
}
```

Both snippets read a file named `data.bin` with `O_DIRECT`. Create one of at least 4 MB
on a filesystem that supports direct I/O, for example `fallocate -l 4M data.bin`.

## Performance

These are single-host numbers: AMD Ryzen 9 3950X, 1 TB PCIe 4.0 NVMe, Fedora 44,
kernel 7.0.12, OpenJDK 26. Reads use a 10 GiB aged file opened with `O_DIRECT`; writes
use a second, near-empty NVMe of the same model. Each read row is five repetitions in
a fresh process, with the engine order rotated. `fio` runs its `io_uring` engine with
registration matched; Seastar is a mature C++ storage framework with a battle-tested
`io_uring` reactor.

| Workload                             | JUring      | fio matched (io_uring) | Seastar     | Result                                                                   |
|--------------------------------------|-------------|------------------------|-------------|--------------------------------------------------------------------------|
| 4 KiB random read, 64 in flight      | 318k IOPS   | 302k IOPS              | 316k IOPS   | 5.4% ahead of fio (90% CI 1.9–8.9%), level with Seastar                  |
| 4 KiB random read, 128 in flight     | 412k IOPS   | 342k IOPS              | 391k IOPS   | 20.6% ahead of fio (90% CI 15.9–25.2%), 5.4% ahead of Seastar (3.5–7.3%) |
| 1 MiB sequential read, 32 in flight  | 4,720 MiB/s | 4,647 MiB/s            | 4,632 MiB/s | all within 2%; the drive is the limit                                    |
| 128 KiB stream read                  | 2,285 MiB/s | -                      | 2,328 MiB/s | within 2% of Seastar's stream API                                        |
| 8 rings, 4 KiB random read           | 473k IOPS   | 459k IOPS              | 466k IOPS   | 1.48× its own one-shard baseline (321k), within ~3% of both              |
| Durable 4 KiB writes, batches of 128 | 721 MiB/s   | -                      | -           | one `fdatasync` per batch, verified                                      |

The first two rows are the headline. At 64 in flight JUring is about 5% ahead
of `fio` and level with Seastar; at 128 it is about 21% ahead of `fio` and 5% ahead of
Seastar. QD 1 is parity: one request is one round trip for every engine (see the
[queue-depth sweep](BENCHMARK.md#queue-depth-sweep)). `fio` cannot
set JUring's three ring flags or batch submissions the way JUring does, and Seastar
cannot set the taskrun flags or use registered files and buffers, so these are
capability comparisons, not equal-config races. The full intervals and per-run data are
in [BENCHMARK.md](BENCHMARK.md).

![4 KiB random read throughput: JUring vs fio vs Seastar at QD 64 and QD 128](docs/figures/read-throughput.svg)

*JUring leads fio at both depths and is level with Seastar at QD 64.*

At 64 in flight, `perf` puts JUring at **11.3k CPU cycles per operation** against
15.2k for `fio`, with **0.33 syscalls per operation** against 1.01: one
`io_uring_enter` doing a batch instead of one per request. The trade-off is tail
latency. At QD 128 JUring's p99 is about 919 µs against 714 µs for `fio`, even though
its median is lower. The dedicated tail run and the p999 numbers are in
[BENCHMARK.md](BENCHMARK.md).

### Writes

On the test drive, all three engines converge at ~192–199k IOPS in the sustained
state. The faster windows are the drive's SLC cache, not an engine difference, so I
don't rank writes. They were to unreliable on this drive. The 400 GiB burn-in, the cache-mode probe and the per-run table are
in [BENCHMARK.md](BENCHMARK.md#second-drive-steady-state-run-2026-11-23).

### Virtual threads

With Loom's default carrier pool, `FileChannel` already saturates the device:
`FileChannel` did about 445k ops/s and `JUringBlocking` 441k. With a four-platform-thread
budget the gap opens. `FileChannel` managed about 43k ops/s from four carriers, while
`JUringBlocking` reached about 452k ops/s from two carriers and two pollers: the same
throughput `FileChannel` needed roughly 250 carriers for. The JMH caveats are in
[BENCHMARK.md](BENCHMARK.md#same-platform-thread-budget).

The harness and analyzers are under [`bench/`](bench/README.md); the raw per-repetition
JSON is produced there, and every harness table is rebuilt from it.

## Limitations

- **Linux only.** The fast path targets kernel 6.1+.
- **`io_uring` must be available.** Some container seccomp profiles and
  `kernel.io_uring_disabled` settings block it.
- **Registered buffers need `RLIMIT_MEMLOCK` headroom.** Without it, the
  registered-buffer path is unavailable; the benchmark harness falls back to
  unregistered arena buffers.
- **Not a drop-in `FileChannel` replacement.** You opt into JUring's API; existing
  `FileChannel` code keeps its current behavior.
- **No write ranking.** On the test drive all three engines converge at the same
  sustained write ceiling; the faster windows are the drive's cache, not an engine
  difference. See [BENCHMARK.md](BENCHMARK.md).
- **No QoS or fair queuing.** If you need priorities, partition work across separate
  rings or shards, or use `io.weight` and `ionice`. The `IoPriority` hint is passed
  through to the block layer, where it's only as good as the configured scheduler.
- **0.1.0 is pre-1.0.** The API can still change.

## About me

I'm David Vlijmincx, an Oracle ACE who writes about Java performance and concurrency at
[davidvlijmincx.com](https://davidvlijmincx.com). JUring started as a way to understand
`io_uring` from the inside. Reach me on
[LinkedIn](https://www.linkedin.com/in/david-vlijmincx/) or
[@davidtos](https://github.com/davidtos) on GitHub.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
