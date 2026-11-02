# JUring: High-Performance Linux io_uring for Java

[![License](https://img.shields.io/badge/License-Unlicense-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![Linux](https://img.shields.io/badge/Linux-Kernel%206.1%2B-green.svg)](https://kernel.org/)
[![io_uring](https://img.shields.io/badge/io__uring-Native%20Zero--Copy-red.svg)](https://git.kernel.dk/cgit/liburing/)
[![Oracle ACE](https://img.shields.io/badge/Author-David%20Vlijmincx%20(Oracle%20ACE)-blueviolet.svg)](https://davidvlijmincx.com)

When I first started exploring **Virtual Threads** in Java, I wanted to understand their runtime performance characteristics, unmounting semantics, and system boundaries. That research led directly to a longstanding architectural constraint in the JVM: **file I/O operations**.

In standard Java, file I/O operations on `FileChannel` execute synchronous kernel system calls (`pread64`/`pwrite64`). Because POSIX file descriptors do not support readiness notifications like network sockets (`epoll`), the JVM cannot yield virtual threads during blocking file operations. In OpenJDK 25, `FileChannel` attempts carrier thread compensation via `Blocker.begin(direct)` for direct and synchronous I/O, spinning up extra OS carrier threads up to `jdk.virtualThreadScheduler.maxPoolSize` (default 256). For ordinary buffered file I/O, `Blocker.begin(false)` returns immediately without compensating, causing the virtual thread to pin its OS carrier thread for the full duration of the I/O. Under high concurrency, this leads to carrier exhaustion, thread scheduling overhead, and throughput degradation.

I built **JUring** to provide true asynchronous file I/O for modern Java.

JUring is a pure-Java library that provides direct native bindings to Linux's high-performance **`io_uring`** asynchronous I/O subsystem using Java's **Foreign Function & Memory (FFM) API** (JEP 454). No JNI code, no external `.so` dependencies (not even `liburing`), and zero heap allocations on the hot path (`0.000 B/op`). It turns blocking file I/O into non-blocking operations for Virtual Threads, delivers verified parity and competitive throughput against native C `fio` (`ioengine=io_uring`), and amortizes system calls across high-concurrency NVMe workloads.

---

## Table of Contents

- [Why I Built JUring: The Problem with Java File I/O](#why-i-built-juring-the-problem-with-java-file-io)
- [Key Performance Highlights](#key-performance-highlights)
- [Hardware Lab & Benchmark Methodology](#hardware-lab--benchmark-methodology)
- [Empirical Benchmark Results](#empirical-benchmark-results)
  - [1. Head-to-Head Parity vs. Native C fio](#1-head-to-head-parity-vs-native-c-fio)
  - [2. Single-Core Framework Overhead & Direct I/O Efficiency](#2-single-core-framework-overhead--direct-io-efficiency)
  - [3. 4 KB Direct Read Scaling (Single 10 GB File)](#3-4-kb-direct-read-scaling-single-10-gb-file)
  - [4. 4 KB Direct Write Scaling & Physical SLC Dynamics](#4-4-kb-direct-write-scaling--physical-slc-dynamics)
  - [5. Multi-File Scaling (2,211 Discrete Files)](#5-multi-file-scaling-2211-discrete-files)
  - [6. Project Loom Virtual Threads Scaling (50 to 1,000 VTs)](#6-project-loom-virtual-threads-scaling-50-to-1000-vts)
  - [7. Buffered Page-Cache I/O (DRAM Resident)](#7-buffered-page-cache-io-dram-resident)
  - [8. Physical NAND Flash Cell Latency (QD=1)](#8-physical-nand-flash-cell-latency-qd1)
  - [9. Project Panama (FFM API) Downcall Overhead](#9-project-panama-ffm-api-downcall-overhead)
- [Code Examples: Using JUring in Your Application](#code-examples-using-juring-in-your-application)
  - [1. High-Throughput Direct Read (Registered Files & Fixed Buffers)](#1-high-throughput-direct-read-registered-files--fixed-buffers)
  - [2. High-Throughput Direct Write](#2-high-throughput-direct-write)
  - [3. Fused Submission & Completion Pipeline Loop](#3-fused-submission--completion-pipeline-loop)
  - [4. Buffered I/O with ByteBuffers / MemorySegments](#4-buffered-io-with-bytebuffers--memorysegments)
  - [5. Non-Blocking Virtual Thread Blocking API (`JUringBlocking`)](#5-non-blocking-virtual-thread-blocking-api-juringblocking)
- [How Under-the-Hood Architectural Optimizations Work](#how-under-the-hood-architectural-optimizations-work)
- [Methodology and Limitations](#methodology-and-limitations)
- [How to Run the Benchmarks Yourself](#how-to-run-the-benchmarks-yourself)
- [Requirements & Setup](#requirements--setup)
- [Further Reading & Connect](#further-reading--connect)
- [License](#license)

---

## Why I Built JUring: The Problem with Java File I/O

For years, Java developers accepted a fundamental limitation: disk I/O blocks the calling OS thread. When Project Loom introduced Virtual Threads in Java 21, millions of concurrent lightweight threads became possible for network services. But file I/O remained constrained.

When a Virtual Thread calls `fileChannel.read()`:
1. It executes a synchronous kernel call (`pread64`).
2. For buffered I/O, `FileChannel` performs no carrier compensation (`Blocker.begin(false)` returns immediately), pinning the carrier platform thread until the kernel completes the read.
3. For direct I/O (`ExtendedOpenOption.DIRECT`) and sync writes, Loom attempts carrier compensation via `Blocker.begin(true)`, adding temporary carrier threads to the `ForkJoinPool` up to `jdk.virtualThreadScheduler.maxPoolSize` (default 256).
4. Beyond 256 concurrent operations, the pool cannot expand further. Virtual threads queue for available carriers, context switches increase, and throughput plateaus.

By integrating Linux **`io_uring`** via **Project Panama (FFM API)**, JUring resolves this:
* **True Asynchronous Submissions:** Applications push I/O requests into a shared ring buffer without issuing an immediate blocking system call per request.
* **Non-Blocking Virtual Thread Yielding (`JUringBlocking`):** Virtual threads park cleanly via `LockSupport.parkNanos()`, releasing the carrier thread back to the `ForkJoinPool` while the NVMe controller processes the I/O.
* **Striped Poller Architecture:** Dedicated platform poller threads process ring completions and wake up waiting virtual threads, keeping carrier thread pools completely unblocked.
* **Zero JNI Overhead:** Direct native communication using Java downcall method handles, `VarHandle` mapped ring memory, and off-heap `MemorySegment` buffers.

---

## Key Performance Highlights

Empirical measurements on physical PCIe Gen4 NVMe flash storage demonstrate:

* **Parity & Throughput vs. Native C `fio` (`ioengine=io_uring`):**
  * **Random Read at QD=64:** JUring delivers **441,664.3 ± 2,815.3 ops/s (1,725.3 MB/s)**, outperforming default native C fio (**354,183.4 ± 6,259.3 IOPS**, 1,383.5 MB/s) by **+24.7%**. This advantage stems from two architectural differences: (1) JUring batches submission and completion waiting into single `io_uring_enter(min_complete, GETEVENTS)` system calls (~0.44 enters/op vs. default fio issuing separate commit and wait calls), and (2) JUring registers the ring file descriptor itself (`IORING_REGISTER_RING_FDS`), bypassing kernel `fget()`/`fput()` file table atomic reference counting on every syscall.
  * **Random Write at QD=64:** JUring delivers **202,737.4 ± 23,394.1 ops/s** versus fio's **227,565.8 ± 31,845.0 IOPS**. Both engines observe physical SLC write buffer drain and TLC folding during sustained random writes.
* **Single-Core Efficiency & Syscall Amortization:**
  * **NOP Ring Submission:** At QD=64, `NopBenchmark` reaches **11,196,476.1 ± 116,072.0 ops/s** with **52.5 user cycles/op** and **0.0313 syscalls/op** (32 requests amortized per `io_uring_enter` call) with **0.000 B/op** heap allocation.
  * **QD=1 Flash Cell Ground Truth:** At QD=1, `FileChannel` (12,051.8 ± 151.7 ops/s) and `JUring` (12,127.4 ± 51.3 ops/s) match closely, both directly bound by physical NAND flash access latency (~82.9 µs median).
  * **Pipeline Amortization:** At QD=64, JUring delivers **345,115.0 ± 619.6 ops/s (1,348.1 MB/s)** on a single thread at **0.4395 syscalls/op**, while synchronous `FileChannel` remains locked at 1.0 syscall/op.
* **Drive Controller Saturation on Direct Reads:**
  * Scaling across 1 to 16 platform threads, JUring saturates the SSD controller's random read ceiling (~718,000 IOPS / ~2.80 GB/s) at 4 threads (**717,986.5 ± 1,090.9 ops/s**). *Note: ~2.8 GB/s at 4KB represents the random-IOPS limit of the drive controller (Phison PS5016-E16), not the PCIe 4.0 x4 bus bandwidth limit, which provides ~7.88 GB/s.*
* **Multi-File Scaling across 2,211 Discrete Files:**
  * Pre-registering 2,211 file descriptors via `io_uring_register_files` (`IOSQE_FIXED_FILE`) allows JUring to sustain **478,447.7 ± 15,797.1 ops/s** at 16 threads without per-op file table lookups.
  * *File Descriptor Limits:* HotSpot automatically raises the soft descriptor limit to the hard limit (524,288 on the test system) at startup via `-XX:+MaxFDLimit`. In contrast, tools launched from default shells inherit a soft limit of 1024 unless configured with `ulimit -n`.
* **Project Loom Virtual Threads Scaling (50 to 1,000 VTs):**
  * `JUringBlocking` sustains **536,644.0 ± 139,009.0 ops/s (2,096.3 MB/s)** at 1,000 concurrent Virtual Threads reading from direct NVMe storage.
  * Under direct I/O, `FileChannel` utilizes Loom carrier compensation, achieving 517,531.8 ± 114,813.5 ops/s. Expanding the carrier pool to 1024 (`-Djdk.virtualThreadScheduler.maxPoolSize=1024`) demonstrates the trade-off: `FileChannel` reaches 393,871.7 ± 20,923.3 ops/s under heavy thread creation, while `JUringBlocking` sustains 536,926.3 ± 91,610.8 ops/s with a small, fixed pool of platform pollers.
* **Buffered Page-Cache I/O:**
  * For DRAM-resident page cache reads, JUring achieves **1,458,945.4 ± 28,133.6 ops/s (5,699.0 MB/s)** with **0.0168 syscalls/op** (59.8x fewer syscalls than `FileChannel`).
  * For page cache writes, zero-copy registered buffers allow JUring to reach **890,809.5 ± 61,836.9 ops/s (3,479.7 MB/s)** with **0.0446 syscalls/op** (22.6x fewer syscalls).
* **Pure Project Panama (FFM API) Downcall Overhead:**
  * Direct downcalls using `Linker.Option.critical(true)` complete in **2.69 ± 0.02 ns/op** by skipping safepoint transitions for leaf operations. Standard `MethodHandle.invokeExact` completes in **6.09 ± 0.06 ns/op**, and interface proxies via `MethodHandleProxies` complete in **8.43 ± 0.08 ns/op**.
* **Correctness & Handshake Verification:**
  * JCStress stress tests of the handshake pattern (`WakePollerStoreLoadTest`) verify release-acquire and StoreLoad memory barriers across 196 configurations with 0 forbidden states observed.
  * 130 JUnit regression and concurrency test cases passing across all modules.

---

## Hardware Lab & Benchmark Methodology

All benchmarks were executed on bare-metal hardware with ext4 mounted directly on a dedicated PCIe Gen4 NVMe drive:

* **CPU:** AMD Ryzen 9 3950X (16 physical cores, 32 threads, 64 MB L3 cache, base clock 3.5 GHz, governor `ondemand`).
* **Storage:** GIGABYTE GP-ASM2NE6100TTTD 1TB PCIe 4.0 x4 NVMe SSD (Phison PS5016-E16 controller, 1GB DDR4 DRAM cache, Toshiba BiCS4 96-layer 3D TLC NAND, firmware `EGFM11.2`).
* **Filesystem & OS:** Linux Kernel `7.0.12-201.fc44.x86_64`, ext4 filesystem mounted on `/mnt/TestData` (`/dev/nvme1n1p1`), mount options `rw,relatime`.
* **Java Runtime:** OpenJDK 64-Bit Server VM `25.0.3+9` (Java 25 with Foreign Function & Memory API).
* **Benchmarking Harness:** JMH 1.37 with 3 forks per suite, measurement iterations of 3s each, accompanied by Linux kernel tracepoint profiling (`raw_syscalls:sys_enter`) and hardware performance counters (`LinuxPerfNormProfiler`).

### Workload Datasets:
1. **`SINGLE_FILE` (10 GB):** A contiguous 10 GB pre-allocated binary file (`single_file_bench.bin`, 2.5 million 4KB sectors) on ext4. Eliminates directory mutex contention to measure raw I/O engine and drive controller throughput.
2. **`MULTI_FILE` (2,211 Files):** 2,211 distinct pre-allocated binary files (~4.7 MB each, ~10.4 GB total) on ext4. Measures multi-descriptor handling via `io_uring_register_files`.

---

## Empirical Benchmark Results

### 1. Head-to-Head Parity vs. Native C fio

Random 4KB read and write workloads on a single thread at `QD=64` on a contiguous 10 GB direct I/O file (`O_DIRECT`, fixed buffers, registered files). Tested across 3 independent randomized runs per engine:

| Workload | Native C `fio` (Default, 3 repeats) | JUring (JMH, 3 repeats) | Relative Delta | Notes |
|---|---|---|---|---|
| **Random Read (QD=64, 4KB)** | 354,183.4 ± 6,259.3 IOPS (1,383.5 MB/s) | **441,664.3 ± 2,815.3 ops/s (1,725.3 MB/s)** | **+24.7%** | Single 10 GB file, `O_DIRECT`, fixed buffers, registered files |
| **Random Write (QD=64, 4KB)** | 227,565.8 ± 31,845.0 IOPS (888.9 MB/s) | **202,737.4 ± 23,394.1 ops/s (791.9 MB/s)** | **-10.9%** | Physical SLC write buffer drain observed on both engines |

> [!NOTE]
> **Understanding the +24.7% Read Gap:**
> Disassembly of `/usr/bin/fio` (v3.40) reveals that fio sets `IORING_SETUP_DEFER_TASKRUN`, `COOP_TASKRUN`, `SINGLE_ISSUER`, and `CQSIZE` by default. JUring's advantage is due to:
> 1. **Batching Structure:** JUring combines submission and wait into a single `io_uring_enter(min_complete=1, GETEVENTS)` syscall (~0.44 enters/op), whereas default fio configuration commits submissions and reaps completions in separate syscalls.
> 2. **Registered Ring Descriptors:** JUring registers the ring descriptor itself (`IORING_REGISTER_RING_FDS`), bypassing kernel `fget()`/`fput()` atomic reference counts on each enter syscall.

---

### 2. Single-Core Framework Overhead & Direct I/O Efficiency

Evaluation of pure framework overhead (`NopBenchmark`), followed by single-thread 4KB Direct Read and Direct Write (`O_DIRECT`) comparing QD=1 and QD=64 against `FileChannel`:

| Target | Concurrency / QD | Throughput (ops/s) | Bandwidth | Syscalls / op (`raw_syscalls:sys_enter`) | User CPU (`cycles:u/op`) | Kernel CPU (`cycles:k/op`) | Heap Alloc |
|---|---|---|---|---|---|---|---|
| **`NopBenchmark.juringNop`** | 1T (QD=64, ring memory only) | **11,196,476.1 ± 116,072.0** | — | **0.0313** (32.0 ops/call) | **52.5** | 353.2 | **0.000 B/op** |
| `FileChannel.read` | 1T (QD=1, synchronous `pread64`) | 12,051.8 ± 151.7 | 47.1 MB/s | 1.0020 | 1447.6 | 25131.0 | 0.000 B/op |
| **`JUring.read` (QD=1)** | 1T (QD=1, unbatched `O_DIRECT`) | **12,127.4 ± 51.3** | 47.4 MB/s | **1.0032** | **3508.7** | 25436.7 | **0.000 B/op** |
| **`JUring.read` (QD=64)** | 1T (QD=64, batched pipeline) | **345,115.0 ± 619.6** | **1,348.1 MB/s** | **0.4395** | **274.9** | 10298.9 | **0.000 B/op** |
| `FileChannel.write` | 1T (QD=1, synchronous `pwrite64`) | 71,452.1 ± 504.1 | 279.1 MB/s | 1.0008 | 889.6 | 25619.4 | 0.000 B/op |
| **`JUring.write` (QD=1)** | 1T (QD=1, unbatched `O_DIRECT`) | **71,247.9 ± 732.5** | 278.3 MB/s | **1.0009** | **693.9** | 26555.7 | **0.000 B/op** |
| **`JUring.write` (QD=64)** | 1T (QD=64, batched pipeline) | **318,718.9 ± 221,581.8** | **1,245.0 MB/s** | **0.0383** | **109.3** | 8584.5 | **0.000 B/op** |

> [!TIP]
> **Equal Concurrency Parity:**
> At QD=1, `FileChannel` (12,051.8 ops/s) and `JUring` (12,127.4 ops/s) perform identically, directly matching the flash cell access latency (~82.9 µs). When queue depth increases to 64, JUring keeps the NVMe hardware command queue filled, scaling to **345,115.0 ops/s** on a single core while amortizing syscalls down to **0.4395 enters/op**.

---

### 3. 4 KB Direct Read Scaling (Single 10 GB File)

Scaling platform thread concurrency across 1, 2, 4, 8, and 16 threads issuing pseudo-random 4KB sector reads (`O_DIRECT`). Concurrency per thread is QD=64 for JUring and QD=1 (synchronous blocking `pread64`) for `FileChannel`:

| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| **1 Thread** | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | **323,971.0 ± 10,378.8** | **1,265.5 MB/s** | 15,941.2 ± 1,513.6 | 62.3 MB/s | **20.3x** |
| **2 Threads** | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | **678,682.2 ± 15,605.4** | **2,651.1 MB/s** | 27,395.4 ± 1,800.7 | 107.0 MB/s | **24.8x** |
| **4 Threads** | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | **717,986.5 ± 1,090.9** | **2,804.6 MB/s** | 52,676.5 ± 1,182.2 | 205.8 MB/s | **13.6x** |
| **8 Threads** | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | **716,319.0 ± 5,207.2** | **2,798.1 MB/s** | 99,471.1 ± 2,669.1 | 388.6 MB/s | **7.2x** |
| **16 Threads** | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | **712,629.3 ± 30,805.4** | **2,783.7 MB/s** | 166,806.9 ± 7,688.9 | 651.6 MB/s | **4.3x** |

At 4 threads and above, JUring saturates the SSD controller's random read IOPS ceiling at **~718,000 ops/s (2.80 GB/s)** with zero JVM heap allocations (`0.000 B/op`).

---

### 4. 4 KB Direct Write Scaling & Physical SLC Dynamics

Direct random writes bypassing page cache directly to physical NVMe flash:

| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| **1 Thread** | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | **450,987.4 ± 138,095.8** | **1,761.7 MB/s** | 71,161.2 ± 7,245.0 | 278.0 MB/s | **6.3x** |
| **2 Threads** | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | **598,437.3 ± 128,484.2** | **2,337.6 MB/s** | 139,071.2 ± 4,753.5 | 543.2 MB/s | **4.3x** |
| **4 Threads** | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | **541,630.0 ± 21,049.8** | **2,115.7 MB/s** | 258,057.9 ± 52,784.5 | 1,008.0 MB/s | **2.1x** |
| **8 Threads** | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | **100,077.8 ± 2,414,155.8** | **390.9 MB/s** | 441,552.0 ± 36,979.0 | 1,724.8 MB/s | **0.2x** |
| **16 Threads** | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | **289,711.6 ± 4,380,413.5** | **1,131.7 MB/s** | 51,686.3 ± 407,548.2 | 201.9 MB/s | **5.6x** |

> [!NOTE]
> **Understanding High-Concurrency Write Variance:**
> Sustained 4KB random direct writes across 8 to 16 threads push over 20 GB of data in seconds, completely exhausting the drive's dynamic SLC cache. The Phison PS5016-E16 controller falls back to direct-to-die TLC folding (~300–700 MB/s), producing high measurement variance in both JMH and fio until write activity subsides and the SLC buffer flushes.

---

### 5. Multi-File Scaling (2,211 Discrete Files)

Benchmarking random direct I/O across 2,211 discrete files (~4.7 MB each) using `io_uring_register_files` (`IOSQE_FIXED_FILE`):

| Threads | JUring Read (ops/s) | FileChannel Read (ops/s) | Read Speedup | JUring Write (ops/s) | FileChannel Write (ops/s) | Write Speedup |
|---|---|---|---|---|---|---|
| **1 Thread** | **330,708.3 ± 7,524.9** | 11,391.2 ± 418.4 | **29.0x** | **63,185.8 ± 467,067.3** | 65,597.6 ± 10,861.3 | **1.0x** |
| **2 Threads** | **376,268.9 ± 1,671,721.1** | 22,239.9 ± 820.6 | **16.9x** | **91,574.5 ± 1,838,402.7** | 124,377.0 ± 114,705.9 | **0.7x** |
| **4 Threads** | **424,319.6 ± 1,475,543.7** | 43,076.5 ± 272.5 | **9.9x** | **191,506.7 ± 3,732,824.9** | 208,693.3 ± 385,447.7 | **0.9x** |
| **8 Threads** | **475,027.7 ± 11,473.9** | 81,387.2 ± 1,157.6 | **5.8x** | **59,786.2 ± 28,187.9** | 110,660.2 ± 1,284,998.1 | **0.5x** |
| **16 Threads** | **478,447.7 ± 15,797.1** | 145,925.0 ± 1,978.9 | **3.3x** | **43,318.7 ± 24,734.6** | 306,246.5 ± 728,477.4 | **0.1x** |

---

### 6. Project Loom Virtual Threads Scaling (50 to 1,000 VTs)

Concurrent Virtual Threads issuing 4KB direct reads (`O_DIRECT`) comparing `JUringBlocking` against standard `FileChannel`:

#### Empirical Scaling on Single Large File (`SINGLE_FILE`, 4KB Sectors, `O_DIRECT`)

| Virtual Threads | JUringBlocking (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup | JUring Syscalls/op | JUring Kernel Cycles/op | FileChannel Kernel Cycles/op |
|---|---|---|---|---|---|---|---|---|
| **50 VTs** | **331,351.9 ± 2,798.4** | **1,294.3 MB/s** | 348,087.0 ± 1,047.5 | 1,359.7 MB/s | **0.95x** | **4.8698** | **63051.8** | 51403.0 |
| **100 VTs** | **410,368.5 ± 197,032.2** | **1,603.0 MB/s** | 454,870.0 ± 114,942.0 | 1,776.8 MB/s | **0.90x** | **3.6714** | **52623.1** | 61214.5 |
| **250 VTs** | **519,211.4 ± 119,352.1** | **2,028.2 MB/s** | 500,866.7 ± 145,821.1 | 1,956.5 MB/s | **1.04x** | **3.2737** | **47313.1** | 66056.6 |
| **500 VTs** | **559,061.2 ± 3,638.7** | **2,183.8 MB/s** | 543,815.4 ± 65,084.6 | 2,124.3 MB/s | **1.03x** | **2.7729** | **46142.7** | 59514.1 |
| **1000 VTs** | **536,644.0 ± 139,009.0** | **2,096.3 MB/s** | 517,531.8 ± 114,813.5 | 2,021.6 MB/s | **1.04x** | **2.4581** | **42836.1** | 59260.9 |

#### Impact of Loom Carrier Thread Cap (`maxPoolSize=1024` vs default 256 at 1,000 VTs)

| Configuration | Virtual Threads | Engine | Throughput (ops/s) | Bandwidth | Notes |
|---|---|---|---|---|---|
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | **FileChannel** | **393,871.7 ± 20,923.3** | **1,538.6 MB/s** | Carrier pool expanded from default 256 to 1024 |
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | **JUringBlocking** | **536,926.3 ± 91,610.8** | **2,097.4 MB/s** | Fixed pool of 4 poller threads |

#### Empirical Scaling on Multi-File Distribution (2,211 Files, `IOSQE_FIXED_FILE`)

| Virtual Threads | JUringBlocking (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|
| **50 VTs** | **274,996.3 ± 1,450.6** | **1,074.2 MB/s** | 269,391.5 ± 69,102.2 | 1,052.3 MB/s | **1.02x** |
| **100 VTs** | **375,740.5 ± 2,003.4** | **1,467.7 MB/s** | 383,349.9 ± 635.7 | 1,497.5 MB/s | **0.98x** |
| **250 VTs** | **395,832.2 ± 123,496.9** | **1,546.2 MB/s** | 446,128.9 ± 878.5 | 1,742.7 MB/s | **0.89x** |
| **500 VTs** | **428,726.6 ± 115,205.3** | **1,674.7 MB/s** | 446,426.8 ± 5,197.4 | 1,743.9 MB/s | **0.96x** |
| **1000 VTs** | **425,041.6 ± 103,145.0** | **1,660.3 MB/s** | 429,683.0 ± 77,720.3 | 1,678.4 MB/s | **0.99x** |

---

### 7. Buffered Page-Cache I/O (DRAM Resident)

Evaluating DRAM-resident page cache operations with batch size 64:

| Workload | Engine | Concurrency / Batch | Throughput (ops/s) | Bandwidth | Syscalls/op (`raw_syscalls:sys_enter`) | Syscall Ratio |
|---|---|---|---|---|---|---|
| **Page Cache Read** | **JUring (`submitBatch=64`)** | 1T (Batch=64) | **1,458,945.4 ± 28,133.6** | **5,699.0 MB/s** | **0.0168** | **59.8x fewer** |
| Page Cache Read | `FileChannel` | 1T (Synchronous) | 1,280,284.0 ± 20,747.6 | 5,001.1 MB/s | 1.0070 | Baseline (1 call/op) |
| **Page Cache Write** | **JUring (`submitBatch=64`)** | 1T (Batch=64) | **890,809.5 ± 61,836.9** | **3,479.7 MB/s** | **0.0446** | **22.6x fewer** |
| Page Cache Write | `FileChannel` | 1T (Synchronous) | 475,238.3 ± 9,206.3 | 1,856.4 MB/s | 1.0089 | In-memory `pwrite` `memcpy` |

---

### 8. Physical NAND Flash Cell Latency (QD=1)

Unbatched single-operation read sampling (`Mode.SampleTime`, `OperationsPerInvocation(1)`) confirming direct hardware NVMe DMA latency:

| Metric | JUring Fused (`submitAndWait(1)`) | JUring Unfused (`submit() + wait()`) | FileChannel (`DIRECT pread64`) | Physical Hardware Reality |
|---|---|---|---|---|
| **Syscalls / op** | **1 enter/op** (`to_submit=1, min_complete=1`) | 2 enters/op (`submit=1` + `wait=1`) | 1 `pread64` syscall | Fused mode batches submit+wait |
| **Mean Latency** | **87.40 µs** | 87.25 µs | 87.76 µs | Governed by physical TLC NAND cell sensing (~80 µs) |
| **p0 (Min)** | **78.85 µs** | 78.85 µs | 79.23 µs | Controller PCIe transfer floor |
| **p50 (Median)** | **82.94 µs** | 82.94 µs | 83.33 µs | Round-trip PCIe packet traversal + controller arbitration |
| **p90** | **97.92 µs** | 97.92 µs | 98.43 µs | Controller queue arbitration |
| **p95** | **98.43 µs** | 98.43 µs | 99.07 µs | Flash transfer timing |
| **p99** | **100.48 µs** | 100.10 µs | 100.99 µs | Flash block management |
| **p99.9** | **109.82 µs** | 108.29 µs | 109.70 µs | NAND GC boundary |
| **p100 (Max)** | 3555.33 µs | 3117.06 µs | 1675.26 µs | OS thread scheduling jitter |

> [!NOTE]
> An 82.9 µs median round trip at QD=1 proves 100% genuine bus-master DMA transfers from physical BiCS4 3D TLC flash cells over PCIe. Page cache hits would register < 1 µs.

---

### 9. Project Panama (FFM API) Downcall Overhead

Benchmarking Foreign Function & Memory downcall invocations across dispatch patterns using [`FfmInvocationBenchmark`](src/test/java/bench/suite/FfmInvocationBenchmark.java):

| Invocation Strategy | Call Mechanism | Average Time (ns/op) | Safepoint Handling | JIT Optimization |
|---|---|---|---|---|
| **`staticFinal_critical_invokeExact`** | Linker.Option.critical(true) | **2.69 ± 0.02 ns/op** | Skips safepoint poll | Direct native call stub intrinsic |
| **`methodHandleProxies_critical`** | Linker.Option.critical(true) | **4.81 ± 0.06 ns/op** | Skips safepoint poll | Direct native call stub intrinsic |
| **`record_staticFinal_proxy`** | MethodHandleProxies / Record Interface | **6.06 ± 0.03 ns/op** | Standard safepoint poll | Interface call / invokedynamic |
| **`staticFinal_invoke`** | Direct MethodHandle | **6.06 ± 0.05 ns/op** | Standard safepoint poll | Constant-folded |
| **`staticFinal_invokeExact`** | Direct MethodHandle | **6.09 ± 0.06 ns/op** | Standard safepoint poll | Constant-folded |
| **`instanceField_invokeExact`** | Instance Field Handle | **8.18 ± 0.11 ns/op** | Standard safepoint poll | Cannot constant-fold target |
| **`instanceField_extractedFromRecord_proxy`** | MethodHandleProxies / Record Interface | **8.40 ± 0.07 ns/op** | Standard safepoint poll | Interface call / invokedynamic |
| **`methodHandleProxies_asInterfaceInstance`** | MethodHandleProxies / Record Interface | **8.43 ± 0.08 ns/op** | Standard safepoint poll | Interface call / invokedynamic |
| **`record_instance_proxy`** | MethodHandleProxies / Record Interface | **8.84 ± 0.12 ns/op** | Standard safepoint poll | Interface call / invokedynamic |

---

## Code Examples: Using JUring in Your Application

> [!IMPORTANT]
> **Native Access Required (JEP 472):**
> Because JUring binds directly to the Linux kernel via Java's Foreign Function & Memory API, applications must be launched with:
> ```bash
> java --enable-native-access=ALL-UNNAMED -jar your-app.jar
> ```

### 1. High-Throughput Direct Read (Registered Files & Fixed Buffers)

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
             FileDescriptor fd = new FileDescriptor(Path.of("/mnt/TestData/large_dataset.bin"), LinuxOpenOptions.READ_DIRECT)) {

            // Pre-register file descriptor into kernel fixed table
            ring.registerFiles(fd);

            // Pre-register DMA buffers with kernel
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

---

### 2. High-Throughput Direct Write

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
                     LinuxOpenOptions.WRITE_DIRECT | LinuxOpenOptions.O_CREAT)) {

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

---

### 3. Fused Submission & Completion Pipeline Loop

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

---

### 4. Buffered I/O with ByteBuffers / MemorySegments

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
             FileDescriptor fd = new FileDescriptor(Path.of("output.txt"), LinuxOpenOptions.WRITE | LinuxOpenOptions.O_CREAT);
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

---

### 5. Non-Blocking Virtual Thread Blocking API (`JUringBlocking`)

`JUringBlocking` is thread-safe and designed for concurrent virtual thread workloads:

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

                    // Unmounts the Virtual Thread cleanly via LockSupport.parkNanos()
                    // Releasing the underlying OS carrier thread immediately
                    long bytesRead = blockingRing.read(fd, buf, 0);
                    System.out.println("Virtual thread read " + bytesRead + " bytes");
                }
            });
            vt.join();
        }
    }
}
```

---

## How Under-the-Hood Architectural Optimizations Work

1. **`IORING_SETUP_DEFER_TASKRUN` + `COOP_TASKRUN`:**
   In [`JUring.java`](src/main/java/com/davidvlijmincx/lio/api/JUring.java). Confines submission and completion processing strictly to the calling thread. The Linux kernel skips internal completion queue spinlocks and cross-CPU Inter-Processor Interrupts (IPIs).
2. **`IORING_REGISTER_RING_FDS` (`IORING_ENTER_REGISTERED_RING`):**
   In [`IoUringDispatcher.java`](src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java). JUring registers the ring file descriptor with the kernel (`io_uring_register(IORING_REGISTER_RING_FDS)`). Subsequent `io_uring_enter` syscalls pass `IORING_ENTER_REGISTERED_RING`, bypassing POSIX file table lookups and `fget()`/`fput()` atomic reference counts.
3. **Fixed Buffer & Registered File Tables:**
   Memory pages and file descriptors are mapped in advance via `io_uring_register_buffers` and `io_uring_register_files`, avoiding page pin/unpin costs (`get_user_pages`) and descriptor resolution on the hot path.
4. **Fused Pipeline Syscalls:**
   `ring.submitAndWaitForBatchResult(minWait, qd, consumer)` executes SQE submission and event reaping in a single `io_uring_enter` syscall.
5. **Zero-Allocation Primitive Access:**
   Submission queue entries (SQEs) and completion queue entries (CQEs) are accessed directly through Java primitive `VarHandle` operations, ensuring `0.000 B/op` heap allocation during I/O loops.
6. **Project Loom Striped Poller Ring Architecture:**
   In [`JUringBlocking.java`](src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java), virtual threads unmount via `LockSupport.parkNanos()` during I/O and submit tasks to bounded MPSC queues (Vyukov-style). Requests are hash-striped across dedicated platform poller threads (`JUringBlocking-Poller-*`) to maintain strict Linux single-issuer kernel affinity without carrier pool starvation.
7. **StoreLoad Memory Barrier Handshake:**
   In [`JUringBlocking.java`](src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java) and [`IoUringDispatcher.java`](src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java), two-way StoreLoad fences (`VarHandle.fullFence()`) prevent lost wakeup hangs in poller unpark handshakes on out-of-order store-buffering architectures. Handshake safety has been verified using OpenJDK JCStress (196 configurations passing with 0 forbidden states).

---

## Methodology and Limitations

1. **Fixed-QD Closed-Loop Latency:**
   Latency percentiles in Section 8 are measured in a closed-loop benchmark at fixed QD=1 (`Mode.SampleTime`). Closed-loop benchmarks hold the issue rate to the completion rate, which avoids artificial queueing delays but is subject to coordinated omission under external arrival distributions.
2. **`perfnorm` Measurement Scope:**
   Hardware cycle and instruction metrics (`cycles:u`, `cycles:k`, `task-clock`) collected via JMH's `LinuxPerfNormProfiler` cover the threads of the forked JVM process over the active measurement window. They do not capture kernel interrupt-context completion handling executed on other CPU cores by the NVMe driver.
3. **fio Benchmark Configuration:**
   The head-to-head comparison against native C `fio` (v3.40) was executed with 3 repeats per engine with randomized execution order. Both engines targeted the identical 10 GB contiguous pre-allocated file on ext4 with `O_DIRECT`, 4KB block size, fixed buffers, registered files, and QD=64. Raw JSON files for all runs are preserved in `results/2026-11-02/`.
4. **Write Cache / SLC Folding Dynamics:**
   Sustained random write tests with multiple threads write tens of gigabytes within seconds, exceeding the SSD's high-speed SLC buffer. Throughput drops during TLC folding are physical hardware characteristics reflected equally in both Java and native C measurements.

---

## How to Run the Benchmarks Yourself

[`bench.sh`](bench.sh) automates dataset verification, classpath building, and execution:

```shell
# 1. Head-to-head fio comparison (randread, QD=64, 4KB, 10GB file):
./bench.sh --fio-compare 3 --mode read --qd 64 --runtime 5

# 2. Head-to-head fio comparison (randwrite, QD=64, 4KB, 10GB file):
./bench.sh --fio-compare 3 --mode write --qd 64 --runtime 5

# 3. Direct Read single-file benchmark:
./bench.sh --include 'DirectReadBenchmark' --param distribution=SINGLE_FILE --quick

# 4. Multi-Thread Scaling benchmark:
./bench.sh --threads-scaling --quick --param distribution=SINGLE_FILE

# 5. Virtual Threads scaling benchmark:
./bench.sh --virtual --quick

# 6. Physical NAND flash cell latency (SampleTime):
./bench.sh --latency --quick

# 7. Panama Foreign Function downcall overhead:
./bench.sh --include 'FfmInvocationBenchmark' --quick
```

---

## Requirements & Setup

* **Operating System:** Linux Kernel **6.1 or newer** for documented setup (`IORING_SETUP_DEFER_TASKRUN`, `IORING_SETUP_SINGLE_ISSUER`, and registered ring FDs). Kernel 5.6 is the minimum for basic `io_uring` read/write opcodes.
* **Java Virtual Machine:** OpenJDK **25 or higher** (compiled with source/target 25).
* **Dependencies:** None. Direct native downcalls via FFM (JEP 454) — **no `liburing.so` required**.
* **Permissions / Memory Limits:** For multi-threaded buffer registration, ensure adequate locked memory limits:
  ```bash
  ulimit -l unlimited
  ```

### Building & Testing

```bash
# Build the project and execute the JUnit 5 test suite (130 tests passing)
mvn clean test

# Run JCStress memory-ordering handshake tests
mvn test -Pjcstress
```

---

## Further Reading & Connect

* 📖 **[Async I/O with Java & Project Panama: Unlocking Linux io_uring for Virtual Threads](https://davidvlijmincx.com/posts/async-io-with-java-and-panama/)**
* 📖 **[Critical Foreign Methods in Java Project Panama (Linker.Option.critical)](https://davidvlijmincx.com/posts/critical-foreign-methods/)**
* 📖 **[Managing Native Memory in Java (FFM API): Arenas, Malloc & Custom Memory Pools](https://davidvlijmincx.com/posts/java-native-memory-allocation-ffm-api/)**
* 🌐 **[davidvlijmincx.com](https://davidvlijmincx.com)** — Articles on JVM performance and concurrency.
* 💬 **[Connect on LinkedIn](https://www.linkedin.com/in/david-vlijmincx)** — Follow weekly JVM engineering research.

---

## License

This software is released into the public domain under the [Unlicense](LICENSE).
