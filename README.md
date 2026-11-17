# JUring: io_uring for Java using the FFM API

[![CI](https://github.com/davidtos/JUring/actions/workflows/ci.yml/badge.svg)](https://github.com/davidtos/JUring/actions/workflows/ci.yml)

JUring lets Java submit file I/O to Linux `io_uring` directly, using the Foreign Function & Memory API (JEP 454). There is no JNI, no C glue code and no `liburing`. The rings are mapped into memory and driven from Java.

Java's file APIs are synchronous. A thread issues one request and waits for it. To keep storage busy you need many requests in flight, meaning lots of platform threads are needed. Virtual threads don't change this for file I/O. A virtual thread blocked in a file read stays mounted on its carrier thread, and the scheduler makes up for it by temporarily adding carrier threads (up to 256 by default). From a single thread, JUring can keep many requests in flight by submitting and collecting completions in batches.

- Only Java FFM downcalls and VarHandles.
- Zero heap allocation on the raw-ring hot path after setup, and on `JUringBlocking`'s
  caller-side no-option blocking overloads (passing `SqeOptions` explicitly allocates the
  varargs array at the call site). The blocking facade's call records are pre-allocated at
  construction, so its capacity, not per-call allocation, bounds concurrent calls.
- Virtual thread support with `JUringBlocking`, configured through `BlockingOptions`: worker
  pollers, queue depth, a concurrent-call budget and an overflow policy. Every admitted call
  owns one pre-allocated ~88-byte record; callers beyond capacity park for admission
  (`BLOCK`, the default) or fail fast (`FAIL`) instead of retrying in a timed loop or adding
  platform threads. Sizing, memory math and interrupt semantics are in [docs/vt-scale.md](docs/vt-scale.md).
- Focus on random direct I/O, registered files, and fixed buffers to drive high queue depths.

> [!NOTE]
> - JUring is a focused Linux `io_uring` file-I/O library (x86-64 and aarch64, Java FFM API): raw ring operations (`JUring`), async file handles (`JuringFile`), buffered readahead/write-behind streams (`JuringInputStream`/`JuringOutputStream`), explicit durability and group commit (`DurabilityMode`, `GroupCommit`), a multi-ring shard runtime (`IoShard`/`RingGroup`), per-ring observability (`RingMetrics`, `ShardMetrics`, `MetricsJmx`), and virtual-thread blocking (`JUringBlocking`). Several file/stream/`GroupCommit` handles can share one ring, with completions routed per handle (B10a).
> - All headline benchmarks below come from the frozen-protocol, reproducible harness in [`bench/`](bench/README.md) ([methodology](docs/benchmark-methodology.md)) against registration-matched `fio` (`--ioengine=io_uring --registerfiles=1 --fixedbufs=1`; fio's default submit batching — it cannot set `SINGLE_ISSUER`/`COOP_TASKRUN`/`DEFER_TASKRUN`) and [Seastar](https://seastar.io/) (`--reactor-backend=io_uring`, opponent bracket). The harness also supports a batching-and-registration-matched fio reference and rotated engine order (`bench/m1/run-claims-0.1.sh`); the historical read values below use the registration-only reference, so read them as best-config. The 0.1.0 control run ([BENCHMARK.md](BENCHMARK.md) §2.1a) reproduced them within ~1 % and found the batching-matched reference no faster than `matched`.

JUring does not do QoS, fair queuing or priorities between workload classes in v1 by design. Isolate classes with separate rings or shards (`IoShard`/`RingGroup`), or with OS-level I/O controls such as cgroup v2 `io.weight`/`io.max` and `ionice`; see [docs/m4-decision.md](docs/m4-decision.md).

A per-request block-layer `ioprio` **hint** is exposed (`IoPriority` with `JUring.defaultIoprio`, `JUring.setLastSqeIoprio` for raw operations, and `JuringFile.withIoPriority`, inherited by streams and `GroupCommit`; read/write operations). It is scheduler-dependent (`bfq`/`mq-deadline` act on it, `none`/`kyber` ignore it) and is not a QoS or fair-queuing mechanism; `0` inherits the thread's `ionice`/nice priority. See [docs/internal/b10-design.md](docs/internal/b10-design.md).

## Install

```xml
<dependency>
    <groupId>io.github.davidtos</groupId>
    <artifactId>juring</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requirements: Linux on x86-64 or aarch64, JDK 25+, and native access enabled
(`--enable-native-access=JUring` on the module path, `ALL-UNNAMED` on the classpath). Check
`JUring.isSupported()` before creating a ring if your environment may restrict io_uring (Docker's
default seccomp profile, AppArmor, `kernel.io_uring_disabled`); see [docs/platforms.md](docs/platforms.md)
for the full envelope and the `java.nio` fallback guidance.

0.1.0 is the first release. The API is pre-1.0 but covered by the compatibility policy in
[docs/api-stability.md](docs/api-stability.md); see [CHANGELOG.md](CHANGELOG.md) for what changed.

## Benchmark Results

Measured on AMD Ryzen 9 3950X (16C/32T, governor `performance`), Linux `7.0.12-201.fc44.x86_64`, GIGABYTE GP-ASM2NE6100TTTD 1 TB NVMe (`ext4`), OpenJDK `26.0.2`, `fio 3.40`, and Seastar `25.05.0-1392-g04b1c04d` (release build). Every cell runs one fresh process per repetition (`R=5`, 10 s warmup + 30 s measured; 120 s for latency cells) on a shared aged 10 GiB file (`O_DIRECT`), with engines interleaved per rep. `±` denotes the two-sided 95 % Student-$t$ confidence interval of the mean (`df = n - 1`). Full reports, raw JSON paths, and reproduction commands are in [BENCHMARK.md](BENCHMARK.md) and `docs/m*-report.md`.

### L0 — Single-Shard `io_uring` Engine Parity ([`docs/internal/m1-report.md`](docs/internal/m1-report.md))

JUring runs `SINGLE_ISSUER | COOP_TASKRUN | DEFER_TASKRUN` with registered files (`IOSQE_FIXED_FILE`) and kernel-registered fixed buffers. "fio registration-matched" enables `--registerfiles=1 --fixedbufs=1` with fio's default submit batching; this is a *best-config* comparison, not equal config — fio cannot set JUring's ring flags or fused submit batching. Seastar runs its stock `io_uring` reactor backend (200-entry ring, no `SINGLE_ISSUER`/`DEFER_TASKRUN`, no registered files/buffers — reported as an architectural bracket, not a pass/fail gate).

| Cell / Workload | QD | JUring IOPS (± 95% CI) | fio reg-matched IOPS (± 95% CI) | Seastar IOPS (± 95% CI) | Paired `JUring / fio` [95% CI] | Paired `JUring / Seastar` [95% CI] | Reading |
|---|---|---|---|---|---|---|---|
| **W1** — 4 KiB randread | 1 | 10,969 ± 557 | 10,748 ± 664 | 11,558 ± 31 | 1.0234 [0.9248, 1.1220] | 0.9491 [0.9002, 0.9979] | Parity vs `fio` (CI spans 1); Seastar +5.4% at QD1 |
| **W2** — 4 KiB randread | 16 | 144,235 ± 4,248 | 142,818 ± 4,994 | 145,575 ± 262 | 1.0108 [0.9556, 1.0660] | 0.9908 [0.9612, 1.0204] | Parity within ±2% across all three engines |
| **W3** — 4 KiB randread | 64 | **327,371 ± 280** | 304,064 ± 8,038 | 318,317 ± 437 | **1.0771 [1.0477, 1.1064]** | **1.0284 [1.0276, 1.0292]** | **+7.7% vs registration-matched `fio` (best-config: JUring-only ring flags/fused submit), +2.8% vs Seastar** |
| **W4** — 4 KiB randread | 128 | **411,136 ± 19,180** | 332,133 ± 13,601 | 389,967 ± 9,419 | **1.2388 [1.1669, 1.3108]**¹ | **1.0543 [1.0138, 1.0947]** | **+23.9% (+25.4% fast-subset) vs registration-matched `fio` (best-config), +5.4% vs Seastar** |
| **W9** — 1 MiB seqread | 1 | 1,729 ± 54 (1.69 GiB/s) | 1,725 ± 108 | 1,748 ± 105 | 1.0046 [0.9181, 1.0911] | 0.9916 [0.9049, 1.0783] | Device ceiling (~1.69 GiB/s at QD1) |
| **W10** — 1 MiB seqread² | 8 | 4,712 ± 129 (4.60 GiB/s) | 4,665 ± 156 | 4,752 ± 18 | 1.0108 [0.9582, 1.0635] | 0.9917 [0.9665, 1.0169] | Device PCIe/NVMe ceiling (~4.60 GiB/s) |
| **W11** — 1 MiB seqread² | 32 | 4,727 ± 128 (4.62 GiB/s) | 4,727 ± 127 | 4,713 ± 135 | 1.0005 [0.9573, 1.0437] | 1.0029 [1.0009, 1.0049] | Device PCIe/NVMe ceiling (~4.62 GiB/s) |

¹ In W4, both JUring (`r5`) and the fio reference (`r3`) had one host slow-state rep; the post-hoc calibrated fast-state subset (`n=3` paired; report §4) gives `JUring / fio = 1.2542 [1.2139, 1.2946]`. The gate is calibrated, not pre-registered.
² W10/W11 rows show the best-config `unlimited` memlock runs (`registered-fixed` / `--fixedbufs=1`, [`docs/internal/stepd-report.md` §4](docs/internal/stepd-report.md)); under the default 8 MiB `ulimit -l` ([`docs/internal/m1-report.md` §6](docs/internal/m1-report.md)), both engines fall back to unregistered buffers (`regfile`) and achieve the same ~4.65–4.76 GiB/s ceiling (`JUring/fio = 0.9788` at QD8, `1.0161` at QD32).

- **0.1.0 control run (rotated order, batching-matched `fio`, release-candidate code; [BENCHMARK.md](BENCHMARK.md) §2.1a):** JUring reproduced the W3/W4 rows within ~1 % — W3 `323,876 ± 8,104` and `323,753 ± 8,575` IOPS (signed `327,371`), W4 `406,966 ± 19,948` and `410,833 ± 14,052` (signed `411,136`) — with paired `JUring / fio-matched` ratios `1.0655` / `1.2183` and `JUring / Seastar` `1.0293` / `1.0622`. A second fio reference with `--iodepth_batch_submit=qd` (`matchedbatch`) was **not faster** than `matched` (W3 `302,930`, W4 `334,025` IOPS), so the historical reference was already representative for batching; the remaining asymmetry is JUring's ring flags and fused submit. The release-candidate hardening batch changed no read-path code; per-op cost stayed `11,438` cycles/op with `0.327` syscalls/op (signed `11,335` / `0.330`).

- **CPU & syscall cost (`perf stat`, 4 KiB randread QD64, `R=3`, [`docs/internal/m1-report.md` §8](docs/internal/m1-report.md)):** JUring spends **11,335 ± 121 cycles/op** (`0.330` syscalls/op) vs registration-matched `fio` **15,227 ± 604 cycles/op** (`1.012` syscalls/op) and Seastar **13,859 ± 158 cycles/op** (`0.186` syscalls/op) — **25.6 % fewer attributed CPU cycles/op than fio as configured**. This is process-tree `perf` attribution, not a causal claim about any single ring flag: JUring submits in fused batches (hence fewer syscalls/op), and fio cannot set the same ring flags. (At QD1, JUring uses `27,254 ± 188` vs fio `32,424 ± 229` cycles/op, while Seastar busy-polls its reactor at `383,329 ± 170` cycles/op.)
- **Tail latency (`W15` QD1 / `W16` QD64, 120 s measured, `R=5`, [`docs/internal/m1-report.md` §7](docs/internal/m1-report.md)):** At QD64 (`W16`), `p99` is at parity (`481.5 µs` JUring vs `478.8 µs` `fio` vs `504.9 µs` Seastar) and `p999` is `660.5 µs` (JUring) vs `1,881.2 µs` (Seastar). At QD1 (`W15`), `p50`/`p99` match closely (`83.6`/`107.8 µs` JUring vs `81.9`/`98.9 µs` Seastar), while `p999` reflects JVM safepoint/GC tails (`630.9 µs` JUring vs `140.5 µs` Seastar).
- **Write path (`W5`–`W8`, `W12`–`W14`, [`docs/internal/m1-write-report.md`](docs/internal/m1-write-report.md)):** **Verdict: `INCONCLUSIVE` (no write-parity ranking claimed for v1).** Across both the 130-run full write matrix (Step A) and the 42-run pre-registered state-controlled generation campaign (Session 12, [`docs/internal/m1-write-report.md` §12](docs/internal/m1-write-report.md)), this consumer NVMe exhibits SLC-burst to TLC-folding write cliffs (`~195k → 40k–90k` IOPS; `36/130` runs in Step A, and only `2/5` valid homogeneous generations `< 4` in Session 12). Inside the valid single-state continuous generations (`w1a-continuous`, `w2-g1`, `w2-g3`), JUring sustained `188.1k–188.6k` IOPS (~735 MiB/s) at 4 KiB QD64 vs `fio` `196.4k–199.6k` IOPS (`0.946–0.957×`).

### L1 — Buffered Streams (`JuringInputStream` / `JuringOutputStream`) & Durability ([`docs/internal/m2-report.md`](docs/internal/m2-report.md), [`docs/internal/b6-report.md`](docs/internal/b6-report.md))

| Benchmark / Workload | Metric | JUring | Seastar (`fstream`) | Ratio / Result |
|---|---|---|---|---|
| **W22-128k** — 128 KiB sequential stream read (`R=5`, 60 s) | MiB/s (± 95% CI) | 2,284.6 ± 76.2 | 2,327.5 ± 72.3 | **0.9816** (1s95 LB `0.966 ≥ 0.85` — **M2 PASS**, ±5% parity) |
| **W22-1M** — 1 MiB sequential stream read (`R=5`, 60 s) | MiB/s (± 95% CI) | 4,662.8 ± 287.6 | 4,724.3 ± 131.3 | **0.9865** (1s95 LB `0.960 ≥ 0.85` — **M2 PASS**, ±5% parity) |
| **W21-128k / W21-1M** — sequential stream write (`R=5`, 60 s) | Position-matched ratio | 128k: `610.0 ± 402.8` MiB/s; 1M: `569.5 ± 386.7` MiB/s | 128k: `438.6 ± 359.8` MiB/s; 1M: `355.7 ± 300.1` MiB/s | **0.98–1.38×** position-matched (pooled `1.25×` / `1.14×`; drive state-confounded, M2 durability-asymmetric³) |
| **W25** — `GroupCommit` durable 4 KiB writes (`FDATASYNC`, `R=3`) | Batch `1 → 8 → 32 → 128` | `5.9 → 47.0 → 184.5 → 720.7 MiB/s` (`1,522 → 184,497` writes/s) | — | **`fsyncs_per_group == 1.000000`**, fresh-process restart verification **PASS** |

³ In M2 W21, Seastar `fstream` ran `durable=true` (2 `fdatasync`/pass) while JUring ran `syncOnClose=false` (0/pass); B6 ([`docs/internal/b6-report.md` §6–§7](docs/internal/b6-report.md)) added matched `DurabilityMode` (`WRITEBACK`/`FDATASYNC`/`FSYNC`/`DSYNC`) and verified identical sync accounting (`MiB/sync ≈ 3.99` on W24).

### L2 — Multi-Shard Runtime (`IoShard` / `RingGroup`) & Observability ([`docs/internal/m3-report.md`](docs/internal/m3-report.md), [`docs/internal/b5-report.md`](docs/internal/b5-report.md))

4 KiB random read, `QD=64` per shard/worker (`R=5`, 10 s warmup + 30 s measured, 300 s rests, all reps kept), with B5 per-ring metrics and latency histograms enabled (`< 1 %` measured overhead: paired B5/B4 ratios `1.019 / 1.011 / 1.007`):

| Shards (`N`) | `juring-group` IOPS (± 95% CI) | `fio numjobs=N` IOPS (± 95% CI) | `seastar --smp N` IOPS (± 95% CI) | `JUring / fio` (1s95 LB) | `Seastar / JUring` (1s95 LB) | Per-Shard `min/mean` (N=8) |
|---|---|---|---|---|---|---|
| **N = 1** (W17) | 320,760 ± 9,817 | 323,284 ± 7,854 | 317,330 ± 16,836 | 0.992 (`LB 0.959`) | 0.990 (`LB 0.936`) | — |
| **N = 2** (W18) | 391,855 ± 53,079 | 403,669 ± 19,385 | 427,332 ± 1,587 | 0.973 (`LB 0.859`) | 1.102 (`LB 0.976`) | — |
| **N = 4** (W19) | 465,936 ± 13,213 | 463,263 ± 21,947 | 462,364 ± 20,049 | 1.007 (`LB 0.961`) | 0.993 (`LB 0.948`) | — |
| **N = 8** (W20) | **473,126 ± 11,105** | 459,125 ± 23,058 | 466,460 ± 10,692 | **1.032 (`LB 0.987`)** | **0.986 (`LB 0.959`)** | **0.963 (`LB 0.947 ≥ 0.85`)** |

Seastar/JUring ratios are paired per-rep means at each `N`; the signed M3 bar (d) is defined at `N=8` only. `JUring/fio` and the N=8 per-shard `min/mean` are the signed M3 values.

**Host representativeness ([`docs/internal/stepd-report.md`](docs/internal/stepd-report.md)):** Re-testing Seastar with 2 MiB `hugetlbfs` (`--hugepages /mnt/hugepages-juring`) vs THP showed no material difference across `W3`, `W4`, `W10`, `W11`, and `M3 N=8` (`huge/THP` mean ratios `0.9836–1.0389`, e.g. `M3 N=8` `467,341` vs `461,800` IOPS, `1.0123 [0.9589, 1.0657]`). Root `nvme smart-log` before/mid/after confirmed `0` thermal throttle transitions (`T1=0, T2=0`) and `0` media errors.

## Virtual Thread Performance (`JUringBlocking` vs `FileChannel`) — historical JMH

> [!IMPORTANT]
> The table below is historical JMH evidence, **not rerun for 0.1.0**. Its `JUringBlocking` confidence intervals are wide and the 1–2T and 4T intervals overlap, so it is **not a statistically separated ranking**. The defensible statement is the thread-efficiency one: at Loom defaults (uncapped carrier pool), `FileChannel` and `JUringBlocking` reach **parity**; under a fixed platform-thread budget, `JUringBlocking` keeps the device busy with a few poller threads instead of hundreds of carriers. See [BENCHMARK.md](BENCHMARK.md) Part II §3 for the full caveats and the parity-at-defaults data.

When a virtual thread performs direct I/O with `FileChannel`, the carrier thread blocks and additional carrier threads can be created to prevent a performance degradation (up to 256 by default). To compare both approaches under a constrained thread pool, both are given an equal budget of platform threads. `JUringBlocking` runs dedicated `IoShard`-hosted poller threads that submit requests and wake virtual threads via `LockSupport.unpark()` when completions arrive.

The following historical JMH results run 1,000 virtual threads under a fixed platform thread budget (`±` = JMH 99.9 % CI across iterations; the intervals overlap — see [BENCHMARK.md](BENCHMARK.md) Part II §3):

| Platform Thread Budget | Engine         | Thread Breakdown          | Throughput (ops/s)    | Bandwidth            | Historical ratio |
|------------------------|----------------|---------------------------|-----------------------|----------------------|----------|
| 1–2 Threads (Min)      | FileChannel    | 1 carrier                 | 11,433.8 ± 31.5       | 44.7 ± 0.1 MB/s      | baseline |
| 1–2 Threads (Min)      | JUringBlocking | 1 carrier + 1 poller (2T) | 399,108.7 ± 89,551.6  | 1,559.0 ± 349.8 MB/s | 34.91x   |
| 4 Threads              | FileChannel    | 4 carriers                | 43,080.4 ± 220.6      | 168.3 ± 0.9 MB/s     | baseline |
| 4 Threads              | JUringBlocking | 2 carriers + 2 pollers    | 452,192.8 ± 1,262.9   | 1,766.4 ± 4.9 MB/s   | 10.50x   |
| 8 Threads              | FileChannel    | 8 carriers                | 73,003.5 ± 23,547.5   | 285.2 ± 92.0 MB/s    | baseline |
| 8 Threads              | JUringBlocking | 4 carriers + 4 pollers    | 389,340.6 ± 158,626.3 | 1,520.9 ± 619.6 MB/s | 5.33x    |
| 16 Threads             | FileChannel    | 16 carriers               | 137,326.1 ± 32,534.5  | 536.4 ± 127.1 MB/s   | baseline |
| 16 Threads             | JUringBlocking | 8 carriers + 8 pollers    | 443,592.3 ± 40,401.8  | 1,732.8 ± 157.8 MB/s | 3.23x    |

`JUringBlocking` uses dedicated poller threads to submit queue entries and wake parked virtual threads when operations complete. The ratios above are point estimates from a historical run with overlapping intervals; they are kept for context, not as a 0.1.0 ranking. Full details and the parity-at-Loom-defaults dataset are in [BENCHMARK.md](BENCHMARK.md) Part II §3.

## Examples

Runnable, self-checking versions of these patterns are in [docs/examples.md](docs/examples.md).

### Direct Read with Registered Files and Fixed Buffers

In this example we register both the file descriptor and a pool of direct memory buffers with the kernel. This avoids buffer mapping overhead per read:

```java
import com.davidvlijmincx.lio.api.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import static com.davidvlijmincx.lio.api.IoUringOptions.*;

public class DirectReadExample {
    public static void main(String[] args) {
        int queueDepth = 64;
        int bufferSize = 4096; // 4 KB sector alignment for O_DIRECT

        // JUring instances are single-threaded by design (matching io_uring single-issuer semantics)
        try (JUring ring = new JUring(queueDepth * 2,
                IORING_SETUP_SINGLE_ISSUER,
                IORING_SETUP_COOP_TASKRUN,
                IORING_SETUP_DEFER_TASKRUN);
             FileDescriptor fd = new FileDescriptor(Path.of("FILE_YOU_WANT_TO_READ_AT_LEAST_PAGESIZE"), LinuxOpenOptions.READ_DIRECT)) {

            // Pre-register file descriptor
            ring.registerFiles(fd);

            // Pre-register buffers
            MemorySegment[] buffers = ring.registerBuffers(bufferSize, queueDepth);

            // Submit asynchronous direct read requests
            for (int i = 0; i < queueDepth; i++) {
                int bufferIndex = ring.checkOutBuffer();
                long fileOffset = (long) i * bufferSize;

                // Index 0 references registered file descriptor (IOSQE_FIXED_FILE)
                ring.prepareReadFixed(0, bufferSize, fileOffset, bufferIndex);
            }

            // Submit all prepared requests and reap completions in a single syscall
            ring.submitAndWaitForBatchResult(queueDepth, queueDepth, result -> {
                long bytesRead = result.result();
                int bufIdx = result.bufferIdx();
                System.out.printf("Read %d bytes into buffer #%d%n", bytesRead, bufIdx);

                // Check buffer back into pool
                ring.checkInBuffer(bufIdx);
            });
        }
    }
}
```

### Direct Write

Writing data using registered files and fixed buffers:

```java
import com.davidvlijmincx.lio.api.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import static com.davidvlijmincx.lio.api.IoUringOptions.*;

public class DirectWriteExample {
    public static void main(String[] args) {
        int queueDepth = 64;
        int bufferSize = 4096;

        try (JUring ring = new JUring(queueDepth * 2,
                IORING_SETUP_SINGLE_ISSUER,
                IORING_SETUP_COOP_TASKRUN,
                IORING_SETUP_DEFER_TASKRUN);
             FileDescriptor fd = new FileDescriptor(
                     Path.of("/mnt/TestData/output.bin"), 
                     LinuxOpenOptions.CREATE, LinuxOpenOptions.WRITE_DIRECT)) {

            ring.registerFiles(fd);
            MemorySegment[] buffers = ring.registerBuffers(bufferSize, queueDepth);

            for (MemorySegment buf : buffers) {
                buf.fill((byte) 0x5A);
            }

            int bufIdx = ring.checkOutBuffer();
            long fileOffset = 0L;
            ring.prepareWriteFixed(0, bufferSize, fileOffset, bufIdx);

            ring.submitAndWaitForBatchResult(1, queueDepth, result -> {
                System.out.println("Wrote " + result.result() + " bytes successfully.");
                ring.checkInBuffer(result.bufferIdx());
            });
        }
    }
}
```

### Pipeline Loop (Fused Submit & Reap)

Here is how you can build an asynchronous pipeline that keeps the queue full while reaping completions in batches:

```java
import com.davidvlijmincx.lio.api.*;

public class FusedPipelineLoop {
    public static void runPipeline(JUring ring, int totalTasks, int qd) {
        final int waitBatch = 8;
        int submitted = 0;
        int[] reapedCount = new int[1];

        ResultConsumer consumer = result -> {
            reapedCount[0]++;
            ring.checkInBuffer(result.bufferIdx());
        };

        while (reapedCount[0] < totalTasks) {
            // Fill pipeline up to queue depth
            while ((submitted - reapedCount[0]) < qd && submitted < totalTasks) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) break;

                ring.prepareReadFixed(0, 4096, (long) submitted * 4096, bufIdx);
                submitted++;
            }

            // Fused submit and wait: issues single io_uring_enter(minWait, GETEVENTS)
            int remaining = totalTasks - reapedCount[0];
            int inFlight = submitted - reapedCount[0];
            if (inFlight > 0) {
                int minWait = Math.max(1, Math.min(waitBatch, Math.min(remaining, inFlight)));
                ring.submitAndWaitForBatchResult(minWait, qd, consumer);
            }
        }
    }
}
```

### Buffered I/O with MemorySegments and Byte Arrays

You can also perform standard buffered reads and writes using off-heap `MemorySegment`s or heap byte arrays:

```java
import com.davidvlijmincx.lio.api.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class BufferedIoExample {
    public static void main(String[] args) {
        // Asynchronous buffered read
        try (JUring ring = new JUring(128);
             FileDescriptor fd = new FileDescriptor(Path.of("test.txt"), LinuxOpenOptions.READ)) {

            ring.prepareRead(fd, 1024, 0);
            ring.submit();

            // Always close Result to release native resources
            try (Result res = ring.waitForResult()) {
                System.out.println("Read " + res.result() + " bytes: " + res.getString());
            }
        }

        // Asynchronous buffered write
        try (JUring ring = new JUring(128);
             FileDescriptor fd = new FileDescriptor(Path.of("output.txt"), LinuxOpenOptions.CREATE, LinuxOpenOptions.TRUNCATE, LinuxOpenOptions.WRITE);
             Arena arena = Arena.ofConfined()) {

            byte[] bytes = "Hello from JUring io_uring!".getBytes(StandardCharsets.UTF_8);
            MemorySegment buffer = arena.allocate(bytes.length);
            buffer.copyFrom(MemorySegment.ofArray(bytes));

            ring.prepareWrite(fd, buffer, 0);
            ring.submit();

            try (Result res = ring.waitForResult()) {
                System.out.println("Wrote " + res.result() + " bytes successfully.");
            }
        }
    }
}
```

### Virtual Threads with JUringBlocking

If you want a synchronous-style blocking API that works with Virtual Threads, use `JUringBlocking`. The virtual thread unmounts while waiting for kernel completions, letting its carrier thread execute other tasks:

```java
import com.davidvlijmincx.lio.api.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

public class VirtualThreadExample {
    public static void main(String[] args) throws Exception {
        try (JUringBlocking blockingRing = new JUringBlocking(256);
             FileDescriptor fd = new FileDescriptor(Path.of("data.bin"), LinuxOpenOptions.READ)) {

            Thread vt = Thread.startVirtualThread(() -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buf = arena.allocate(4096);

                    long bytesRead = blockingRing.read(fd, buf, 0);
                    System.out.println("Virtual thread read " + bytesRead + " bytes");
                }
            });
            vt.join();
        }
    }
}
```

The no-option constructor above uses the 0.1.0 defaults (a CPU-derived poller count and a
call-record pool derived from the queue depth). For a fan-out larger than that default pool,
build the facade from `BlockingOptions` and size `withPollers`/`withMaxConcurrentCalls` for
the peak number of concurrently blocked calls; `estimatedBytes()` reports the heap cost of
the pre-allocated records. Interrupt behaviour, the admission/overflow policy and the memory
math are documented in [docs/vt-scale.md](docs/vt-scale.md).

## Runnable examples

Four self-checking examples live in the test suite and map to seastar use-cases: direct file I/O with registered files and fixed buffers, a stream copy with read-ahead/write-behind, many files driven by one ring, and `JUringBlocking` from virtual threads. They use JUnit `@TempDir` only, make no timing or throughput *performance* assertions (a JUnit `@Timeout` guards against hangs only):

```sh
mvn -o test -Dtest='*ExampleTest'
```

See [docs/examples.md](docs/examples.md) for the mapping table, the run instructions and what each example demonstrates.

## Compatibility and upgrading

- [docs/platforms.md](docs/platforms.md) — supported OS/arch/JDK/kernel, native-access flags, container/seccomp and `io_uring_disabled` caveats, O_DIRECT/memlock, and `JUring.isSupported()` fallback guidance.
- [docs/api-stability.md](docs/api-stability.md) — the public API surface (`com.davidvlijmincx.lio.api`), SemVer rules, and the compatibility and deprecation policy.
- [docs/UPGRADING.md](docs/UPGRADING.md) — capability history and migration notes.
- [CHANGELOG.md](CHANGELOG.md) — release notes.
- [DATA-AVAILABILITY.md](DATA-AVAILABILITY.md) — what benchmark data is committed, the release data archive, and the caveats for citing the numbers.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
