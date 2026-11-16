# Virtual threads, disk I/O, and the 256-carrier trap

**Draft for foojay — not published yet.** Target ~1,800 words; run every code block before
submitting; add a 1600×900 preview image. Do not lead with a speed multiplier: lead with the
thread-budget problem and the parity caveat.

---

## The promise Loom makes — and where it stops

Virtual threads made network concurrency cheap: when a virtual thread waits on a socket, the JVM
parks it, frees the carrier thread, and schedules other work. That works because sockets are
pollable: Linux gives us `epoll`, so readiness can be awaited without a thread.

Regular files are not pollable. A `read(2)` on a regular file blocks the calling OS thread until
the storage device answers. On JDK 25/26, `FileChannel` file I/O therefore does not unmount the
virtual thread in the socket sense: the carrier thread is blocked in the syscall while Loom
compensates by starting additional carrier threads (up to `jdk.virtualThreadScheduler.maxPoolSize`,
256 by default). In a container with a 2–4 CPU quota that can mean hundreds of OS threads, CFS
throttling and cache thrash.

io_uring is the Linux answer: submit file I/O asynchronously into shared submission/completion
queues, and collect completions from one thread. JUring drives those rings from Java using the
Foreign Function & Memory API (JEP 454) — no JNI, no C glue, no liburing.

## What the measurements actually show

We tested `JUringBlocking` (many virtual threads parked; a small number of platform poller threads
submit and reap) against `FileChannel` at equal *total* platform-thread budgets, 1,000 virtual
threads doing 4 KiB random `O_DIRECT` reads on a 10 GiB aged file, on one Ryzen 9 3950X / consumer
PCIe 4.0 NVMe host (all raw JSON, scripts and methodology are in the repository).

Two results matter:

1. **At Loom's default settings, `FileChannel` already reaches the device with its compensating
   carriers.** Once you allow up to 256 carriers, a 250-virtual-thread run reaches ~445k ops/s
   with `FileChannel` and ~441k with `JUringBlocking` — parity. If your container is not CPU
   constrained, plain `FileChannel` may be all you need.
2. **At a fixed small thread budget, the difference is thread efficiency, not a magic engine.**
   With a 4-thread total budget, `FileChannel` needed 4 carriers and managed ~43k ops/s while
   `JUringBlocking` used 2 carriers + 2 pollers and reached ~452k ops/s in a historical JMH run.
   That is a ~10× point estimate for the *same device throughput with ~50× fewer OS threads*.

The second number is easy to misquote, so be precise: the JMH confidence intervals overlap across
several budget points, the run was not repeated for the 0.1.0 release, and the device is near
saturation at 2–4 pollers. The defensible claim is **parity at Loom defaults; same device
throughput with a few poller threads instead of hundreds of carriers under a fixed budget**.

At QD1 there is no advantage at all: a single synchronous thread and a single io_uring request
both pay one round trip (~11k ops/s). Async I/O buys queue depth; it does not make one request
faster.

## The code

```java
import com.davidvlijmincx.lio.api.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

public class VirtualThreadStorageExample {
    public static void main(String[] args) throws Exception {
        try (var blocking = new JUringBlocking(256);
             var fd = new FileDescriptor(Path.of("/mnt/nvme/data.bin"), LinuxOpenOptions.READ_DIRECT)) {

            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                 var arena = Arena.ofShared()) {

                for (int i = 0; i < 1_000; i++) {
                    long offset = i * 4096L;
                    executor.submit(() -> {
                        MemorySegment buffer = arena.allocate(4096, 4096);
                        // parks the virtual thread; the poller submits the io_uring request
                        long bytes = blocking.read(fd, buffer, offset);
                        if (bytes != 4096) throw new IllegalStateException("read returned " + bytes);
                        return bytes;
                    });
                }
            }
        }
    }
}
```

Run with `--enable-native-access=ALL-UNNAMED` (or `JUring` on the module path). Maven coordinate:
`io.github.davidtos:juring:0.1.0`. Linux 6.1+ is the practical floor for the recommended
`SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN` setup.

## When not to use this

- **Your environment may forbid io_uring.** Docker's default seccomp profile denies it; Ubuntu
  23.10+/24.04 restricts unprivileged io_uring through AppArmor; `kernel.io_uring_disabled` and
  hardened/gVisor/ChromeOS environments can disable it. Check `JUring.isSupported()` and fall
  back to `java.nio`.
- **You do not have an NVMe/queueing problem.** Cached or low-concurrency file I/O will not
  benefit; QD1 is parity by definition.
- **You need QoS or fair queuing.** JUring 0.1.0 deliberately ships no scheduler, weights or
  rate limiting; partition work across rings/shards or use cgroup v2 / `ionice`. State clearly
  what the library does not do.
- **You need portable semantics across OSes.** This is Linux-only by design.
- **Writes are not a ranked claim yet.** The test NVMe's SLC/TLC write cliffs make the write
  comparison `INCONCLUSIVE`; do not quote a write-parity number.

## Closing

Modern Java can drive a kernel I/O interface without JNI, and virtual threads make the blocking
facade pleasant. But "virtual threads are free" is only true for pollable descriptors; for file
I/O the interesting question is *how many platform threads your workload needs*, not just how
many virtual threads it can start. Measure that budget, check `JUring.isSupported()`, and keep a
`FileChannel` fallback.

Repository: <https://github.com/davidtos/JUring> — benchmarks, raw-data availability and the
"when not to use" details are in the README, BENCHMARK.md and docs/platforms.md.
