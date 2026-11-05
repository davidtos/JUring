# JUring: High-Performance Linux io_uring for Java

[![License](https://img.shields.io/badge/License-Unlicense-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![Linux](https://img.shields.io/badge/Linux-Kernel%206.1%2B-green.svg)](https://kernel.org/)
[![io_uring](https://img.shields.io/badge/io__uring-Native%20Zero--Copy-red.svg)](https://git.kernel.dk/cgit/liburing/)
[![Oracle ACE](https://img.shields.io/badge/Author-David%20Vlijmincx%20(Oracle%20ACE)-blueviolet.svg)](https://davidvlijmincx.com)

When I first started exploring **Virtual Threads** in Java, I wanted to understand their runtime performance characteristics, unmounting semantics, and system boundaries. That research led directly to a longstanding architectural constraint in the JVM: **file I/O operations**.

In standard Java, file I/O operations on `FileChannel` execute synchronous kernel system calls (`pread64`/`pwrite64`). Because POSIX file descriptors do not support readiness notifications like network sockets (`epoll`), the JVM cannot yield virtual threads during blocking file operations. In OpenJDK 25, `FileChannel` attempts carrier thread compensation via `Blocker.begin(direct)` for direct and synchronous I/O, spinning up extra OS carrier threads up to `jdk.virtualThreadScheduler.maxPoolSize` (default 256). For ordinary buffered file I/O, `Blocker.begin(false)` returns immediately without compensating, causing the OS carrier thread to remain blocked in the kernel syscall for the full duration of the I/O. Under high concurrency, this leads to carrier exhaustion, thread scheduling overhead, and throughput degradation.

I built **JUring** to provide true asynchronous file I/O for modern Java.

JUring is a pure-Java library that provides direct native bindings to Linux's high-performance **`io_uring`** asynchronous I/O subsystem using Java's **Foreign Function & Memory (FFM) API** (JEP 454). No JNI code, no external `.so` dependencies (not even `liburing`), and zero heap allocations on the hot path (strictly `0L` heap bytes on the calling thread; see [Allocation Accounting](#2-single-core-framework-overhead--direct-io-efficiency)). It turns blocking file I/O into non-blocking operations for Virtual Threads, delivers competitive throughput and parity against native C `fio` (`ioengine=io_uring`), and amortizes system calls across high-concurrency NVMe workloads.

---

## Table of Contents

- [Why I Built JUring: The Problem with Java File I/O](#why-i-built-juring-the-problem-with-java-file-io)
- [Key Performance Highlights](#key-performance-highlights)
- [Hardware Lab & Benchmark Methodology](#hardware-lab--benchmark-methodology)
- [Empirical Benchmark Results](#empirical-benchmark-results)
  - [1. Head-to-Head Parity vs. Native C fio](#1-head-to-head-parity-vs-native-c-fio)
  - [2. Single-Core Framework Overhead & Direct I/O Efficiency](#2-single-core-framework-overhead--direct-io-efficiency)
  - [3. Equal Hardware Queue Depth Comparison (QD = 1, 16, 32, 64)](#3-equal-hardware-queue-depth-comparison-qd--1-16-32-64)
  - [4. 4 KB Direct Read Scaling (Single 10 GB File)](#4-4-kb-direct-read-scaling-single-10-gb-file)
  - [5. 4 KB Direct Write Scaling & Physical SLC Dynamics](#5-4-kb-direct-write-scaling--physical-slc-dynamics)
  - [6. Multi-File Scaling (2,211 Discrete Files)](#6-multi-file-scaling-2211-discrete-files)
  - [7. Project Loom Virtual Threads Scaling (50 to 1,000 VTs)](#7-project-loom-virtual-threads-scaling-50-to-1000-vts)
  - [8. Buffered Page-Cache I/O (DRAM Resident)](#8-buffered-page-cache-io-dram-resident)
  - [9. Physical NAND Flash Cell Latency (QD=1)](#9-physical-nand-flash-cell-latency-qd1)
  - [10. Project Panama (FFM API) Downcall Overhead](#10-project-panama-ffm-api-downcall-overhead)
- [Code Examples: Using JUring in Your Application](#code-examples-using-juring-in-your-application)
  - [1. High-Throughput Direct Read (Registered Files & Fixed Buffers)](#1-high-throughput-direct-read-registered-files--fixed-buffers)
  - [2. High-Throughput Direct Write](#2-high-throughput-direct-write)
  - [3. Fused Submission & Completion Pipeline Loop](#3-fused-submission--completion-pipeline-loop)
  - [4. Buffered I/O with MemorySegments and byte[]](#4-buffered-io-with-memorysegments-and-byte)
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
2. For buffered I/O, `FileChannel` performs no carrier compensation (`Blocker.begin(false)` returns immediately), leaving the carrier platform thread blocked in the syscall until the kernel completes the read.
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

* **Competitive Parity vs. Native C `fio` (`ioengine=io_uring`):** Matches native C `fio` with low system-call overhead (~0.35 enters/op) under 4KB random direct reads (+0.9% delta vs best fio). See [Head-to-Head Parity vs. Native C fio](#1-head-to-head-parity-vs-native-c-fio).
* **Hardware Saturation on Direct Reads:** Reaches the NVMe controller's random read ceiling (~478,000 IOPS / ~1.87 GB/s) at 4 threads and above with zero heap allocations on the calling-thread hot path (`0L` bytes). See [4 KB Direct Read Scaling](#3-4-kb-direct-read-scaling-single-10-gb-file).
* **Multi-Descriptor Handling with Registered Files:** Pre-registering 2,211 file descriptors via `io_uring_register_files` (`IOSQE_FIXED_FILE`) avoids per-op POSIX file table lookups. See [Multi-File Scaling](#5-multi-file-scaling-2211-discrete-files).
* **Fixed-Thread Polling for Virtual Threads:** `JUringBlocking` handles up to 1,000 concurrent Virtual Threads through a small, fixed pool of platform pollers, parking and unparking virtual threads without carrier thread exhaustion. See [Project Loom Virtual Threads Scaling](#6-project-loom-virtual-threads-scaling-50-to-1000-vts).

---

## Hardware Lab & Benchmark Methodology

<!-- BEGIN GENERATED: hardware -->
All benchmarks were executed on bare-metal hardware with ext4 mounted directly on a dedicated PCIe Gen4 NVMe drive:

* **CPU:** AMD Ryzen 9 3950X 16-Core Processor (32 cores, SMT enabled, governor `performance`).
* **RAM:** 65738292 kB.
* **Storage:** GIGABYTE GP-ASM2NE6100TTTD (firmware `EGFM11.2`).
* **Filesystem & OS:** Linux Kernel `7.0.12-201.fc44.x86_64`, ext4 mounted on `/mnt/TestData` (`/dev/nvme1n1p1`), mount options `rw,nosuid,nodev,relatime,seclabel`.
* **Java Runtime:** openjdk version "25.0.3" 2026-04-21 with Foreign Function & Memory API (JEP 454).
* **Harness & Profiling:** JMH with tracepoint profiling (`raw_syscalls:sys_enter`) and hardware perf counters (perf_event_paranoid `-1`).

### Workload Datasets:
1. **`SINGLE_FILE` (10 GB):** A contiguous 10 GB pre-allocated binary file (`single_file_bench.bin`, 2,621,440 4KB blocks) on ext4. Eliminates directory mutex contention to measure raw I/O engine and drive controller throughput.
2. **`MULTI_FILE` (2,211 Files):** 2,211 distinct pre-allocated binary files (~4.7 MB each, ~10.4 GB total) on ext4. Measures multi-descriptor handling via `io_uring_register_files`.
<!-- END GENERATED: hardware -->

---

## Empirical Benchmark Results

### 1. Head-to-Head Parity vs. Native C fio

<!-- BEGIN GENERATED: fio-compare -->
*Note: ± indicates sample standard deviation over N independent repeats.*

#### 4KB Random Direct Read (`randread`, QD=64, 10 GB File, `O_DIRECT`, Fixed Buffers, Registered Files)

| Engine | Throughput (ops/s) | Bandwidth (MB/s) | Enters / op (`io_uring_enter`) | Delta vs Best fio | Configuration |
|---|---|---|---|---|---|
| `fio` (default settings) | 310,609.8 ± 12,688.8 | 1,213.3 ± 49.6 MB/s | 1.5224 | — | Default submission / reap |
| `fio` (best batching: B=8) | 322,908.4 ± 13,644.2 | 1,261.4 ± 53.3 MB/s | 0.7251 | baseline | `--iodepth_batch_submit=8` |
| JUring (JMH) | 325,676.1 ± 9,495.2 | 1,272.2 ± 37.1 MB/s | 0.3465 | +0.9% | Batched `submitAndWait(1)` |

#### 4KB Random Direct Write (`randwrite`, QD=64, 10 GB File, `O_DIRECT`, Fixed Buffers, Registered Files)

| Engine | Throughput (ops/s) | Bandwidth (MB/s) | Enters / op (`io_uring_enter`) | Delta vs Best fio | Configuration |
|---|---|---|---|---|---|
| `fio` (default settings) | 214,941.3 ± 33,471.8 | 839.6 ± 130.7 MB/s | 1.5476 | — | Default submission / reap |
| `fio` (best batching: B=16) | 205,278.8 ± 64,687.3 | 801.9 ± 252.7 MB/s | 0.9086 | baseline | `--iodepth_batch_submit=16` |
| JUring (JMH) | 193,768.7 ± 1,678.7 | 756.9 ± 6.6 MB/s | 0.0395 | -5.6% | Batched `submitAndWait(1)` |
<!-- END GENERATED: fio-compare -->

> [!NOTE]
> **Syscall Amortization and Batching:**
> System calls per operation are measured via `perf stat -e syscalls:sys_enter_io_uring_enter` for fio and JMH `--syscalls`. JUring combines submission and completion waiting into single `io_uring_enter(min_complete=1, GETEVENTS)` syscalls (~0.35 enters/op), whereas default fio configuration commits submissions and reaps completions in separate syscalls (~1.52 enters/op). Sweeping fio batch settings narrows this difference.

---

### 2. Single-Core Framework Overhead & Direct I/O Efficiency

<!-- BEGIN GENERATED: single-core -->
Evaluation of pure framework overhead (`NopBenchmark`), followed by single-thread 4KB Direct Read and Direct Write (`O_DIRECT`) comparing QD=1 and QD=64 against `FileChannel`:
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Target | Concurrency / QD | Throughput (ops/s) | Bandwidth | Syscalls / op (`raw_syscalls:sys_enter`) | User CPU (`cycles:u/op`) | Kernel CPU (`cycles:k/op`) | Heap Alloc |
|---|---|---|---|---|---|---|---|
| `NopBenchmark.juringNop` | 1T (QD=64, ring memory only) | 11,590,187.4 ± 53,591.1 | — | 0.0313 (32.0 ops/call) | 52.7 | 351.1 | 0.006 B/op |
| `FileChannel.read` | 1T (QD=1, synchronous `pread64`) | 11,101.7 ± 979.7 | 43.4 ± 3.8 MB/s | 1.0023 | 1370.2 | 24409.8 | 0.209 B/op |
| `JUring.read` (QD=1) | 1T (QD=1, unbatched `O_DIRECT`) | 11,479.8 ± 34.1 | 44.8 ± 0.1 MB/s | 1.0046 | 3990.5 | 25004.6 | 6.451 B/op |
| `JUring.read` (QD=64) | 1T (QD=64, batched pipeline) | 330,195.7 ± 361.3 | 1,289.8 ± 1.4 MB/s | 0.4569 | 283.6 | 10541.4 | 0.227 B/op |
| `FileChannel.write` | 1T (QD=1, synchronous `pwrite64`) | 72,470.8 ± 1,044.1 | 283.1 ± 4.1 MB/s | 1.0004 | 901.3 | 25023.8 | 0.050 B/op |
| `JUring.write` (QD=1) | 1T (QD=1, unbatched `O_DIRECT`) | 72,409.4 ± 1,577.1 | 282.8 ± 6.2 MB/s | 1.0022 | 724.5 | 26086.7 | 1.521 B/op |
| `JUring.write` (QD=64) | 1T (QD=64, batched pipeline) | 191,163.1 ± 17,715.3 | 746.7 ± 69.2 MB/s | 0.1445 | 286.3 | 11941.4 | 0.574 B/op |
<!-- END GENERATED: single-core -->

> [!NOTE]
> **Allocation Accounting (JMH GC Profiler vs Calling-Thread Zero Allocation):**
> * **JMH Profiler (`-prof gc`):** The `Heap Alloc` column above reports whole-process GC allocation rate (`gc.alloc.rate.norm`) recorded by JMH during benchmark iterations. These sub-byte to few-byte readings (0.006 to 6.451 B/op depending on ops/sec divisor) capture background HotSpot C2 compiler thread activity and JVM housekeeping threads during the measurement window.
> * **Zero Calling-Thread Allocations:** In steady state, JUring's hot-path pipeline (submitting SQEs, reaping CQEs via `ResultConsumer`, and reusing fixed buffers) allocates strictly **`0L`** bytes on the calling application thread across 100,000+ operations. This is deterministically enforced by [`ZeroAllocationHotPathTest.java`](src/test/java/com/davidvlijmincx/lio/api/ZeroAllocationHotPathTest.java) via `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`.

> [!TIP]
> **Equal Concurrency Parity:**
> At QD=1, `FileChannel` and `JUring` perform identically, directly matching the flash cell access latency (~83 µs). When queue depth increases to 64, JUring keeps the NVMe hardware command queue filled, amortizing syscalls down to ~0.46 enters/op.

---

### 3. Equal Hardware Queue Depth Comparison (QD = 1, 16, 32, 64)

<!-- BEGIN GENERATED: equal-qd -->
Head-to-head comparison of standard Java `FileChannel` against `JUring` with **identical thread counts (1 thread)** across increasing hardware Queue Depths (QD = 1, 16, 32, 64) for 4KB random direct reads (`O_DIRECT`) on a contiguous 10 GB pre-allocated file.

*Both benchmarks run on a single calling thread. Because standard Java FileChannel operations (`pread64`) are synchronous and blocking, FileChannel cannot submit concurrent requests to the storage controller without spawning threads; its effective queue depth remains clamped at QD = 1 (~83 µs flash latency). In contrast, JUring drives any requested hardware queue depth asynchronously on the **exact same single thread** via non-blocking io_uring submission queues.*
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Queue Depth (QD) | JUring (1 Thread) Throughput | JUring Bandwidth | FileChannel (1 Thread) Throughput | FileChannel Bandwidth | Speedup (JUring vs FileChannel) | Notes |
|---|---|---|---|---|---|---|
| QD = 1 | 11,492.2 ± 49.6 | 44.9 ± 0.2 MB/s | 11,431.7 ± 48.2 | 44.7 ± 0.2 MB/s | 1.0x | Flash cell access latency bound (~83 µs) |
| QD = 16 | 148,011.3 ± 190.3 | 578.2 ± 0.7 MB/s | 11,447.1 ± 34.3 | 44.7 ± 0.1 MB/s | 12.9x | FileChannel clamped at QD=1 (synchronous); JUring drives QD=16 |
| QD = 32 | 236,162.3 ± 423.9 | 922.5 ± 1.7 MB/s | 11,174.3 ± 1,316.8 | 43.6 ± 5.1 MB/s | 21.1x | FileChannel clamped at QD=1 (synchronous); JUring drives QD=32 |
| QD = 64 | 330,089.1 ± 748.0 | 1,289.4 ± 2.9 MB/s | 11,437.3 ± 21.9 | 44.7 ± 0.1 MB/s | 28.9x | FileChannel clamped at QD=1 (synchronous); JUring drives QD=64 |
<!-- END GENERATED: equal-qd -->

---

### 4. 4 KB Direct Read Scaling (Single 10 GB File)

<!-- BEGIN GENERATED: scaling-read -->
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| 1 Thread | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | 319,847.2 ± 40,983.5 | 1,249.4 ± 160.1 MB/s | 10,133.8 ± 2,439.9 | 39.6 ± 9.5 MB/s | 31.6x |
| 2 Threads | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | 417,751.7 ± 48,022.9 | 1,631.8 ± 187.6 MB/s | 22,397.0 ± 61.8 | 87.5 ± 0.2 MB/s | 18.7x |
| 4 Threads | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | 474,169.6 ± 9,109.1 | 1,852.2 ± 35.6 MB/s | 43,349.6 ± 106.3 | 169.3 ± 0.4 MB/s | 10.9x |
| 8 Threads | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | 436,041.5 ± 66,223.2 | 1,703.3 ± 258.7 MB/s | 79,029.2 ± 11,071.5 | 308.7 ± 43.2 MB/s | 5.5x |
| 16 Threads | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | 477,680.3 ± 1,337.7 | 1,865.9 ± 5.2 MB/s | 130,645.4 ± 34,947.8 | 510.3 ± 136.5 MB/s | 3.7x |
<!-- END GENERATED: scaling-read -->

At 4 threads and above, JUring saturates the SSD controller's random read IOPS ceiling at ~478,000 ops/s (1.87 GB/s) with zero calling-thread JVM heap allocations (`0L` bytes).

---

### 5. 4 KB Direct Write Scaling & Physical SLC Dynamics

<!-- BEGIN GENERATED: scaling-write -->
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| 1 Thread | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | 190,386.0 ± 15,741.9 | 743.7 ± 61.5 MB/s | 72,282.7 ± 894.9 | 282.4 ± 3.5 MB/s | 2.6x |
| 2 Threads | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | 209,754.7 ± 162,796.8 | 819.4 ± 635.9 MB/s | 129,047.1 ± 38,823.0 | 504.1 ± 151.7 MB/s | 1.6x |
| 4 Threads | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | 191,121.8 ± 17,101.6 | 746.6 ± 66.8 MB/s | 194,392.4 ± 187,672.0 | 759.3 ± 733.1 MB/s | 1.0x |
| 8 Threads | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | 191,087.2 ± 25,148.1 | 746.4 ± 98.2 MB/s | 184,816.5 ± 33,252.5 | 721.9 ± 129.9 MB/s | 1.0x |
| 16 Threads | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | 169,392.2 ± 147,763.7 | 661.7 ± 577.2 MB/s | 189,421.1 ± 38,013.8 | 739.9 ± 148.5 MB/s | 0.9x |
<!-- END GENERATED: scaling-write -->

> [!NOTE]
> **Understanding Direct Write Saturation & Steady-State TLC Dynamics:**
> On a single thread (`1T × QD=64`), `JUring` saturates the drive's steady-state TLC random write ceiling (~190,386 ops/s / ~744 MB/s), delivering a **2.6x speedup** over single-threaded `FileChannel` (`72,283 ops/s`). Because NVMe write completions are acknowledged via the controller's non-volatile write cache (~14 µs per `pwrite64`), 4 synchronous `FileChannel` threads (`QD=4`) are already sufficient to reach the drive's ~191k–194k IOPS steady-state TLC write ceiling, resulting in ~1.0x parity from 4 to 16 threads. Measuring inside the steady-state TLC regime—after draining the initial ~6 GB pSLC burst buffer, using full-dataset PRNG offsets (`FioPrng`) to avoid 256 MB LBA overwrite coalescing, and allowing idle GC recovery between tiers—eliminates bimodal pSLC/GC transitions.

---

### 6. Multi-File Scaling (2,211 Discrete Files)

<!-- BEGIN GENERATED: multifile -->
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Threads | JUring Read (ops/s) | FileChannel Read (ops/s) | Read Speedup | JUring Write (ops/s) | FileChannel Write (ops/s) | Write Speedup |
|---|---|---|---|---|---|---|
| 1 Thread | 330,196.5 ± 858.5 | 11,208.8 ± 961.7 | 29.5x | 182,490.2 ± 105,670.7 | 66,920.4 ± 2,223.0 | 2.7x |
| 2 Threads | 408,268.4 ± 76,881.6 | 21,540.7 ± 3,201.0 | 19.0x | 166,410.9 ± 26,827.3 | 122,919.8 ± 34,570.7 | 1.4x |
| 4 Threads | 415,991.5 ± 170,630.8 | 43,216.2 ± 86.2 | 9.6x | 34,286.3 ± 147,050.3 | 186,134.7 ± 106,743.9 | 0.2x |
| 8 Threads | 477,423.5 ± 1,286.0 | 81,451.0 ± 181.6 | 5.9x | 174,209.0 ± 32,179.4 | 158,444.4 ± 18,290.8 | 1.1x |
| 16 Threads | 400,720.3 ± 123,011.8 | 146,090.4 ± 315.6 | 2.7x | 133,530.4 ± 397,418.0 | 171,070.4 ± 18,539.7 | 0.8x |
<!-- END GENERATED: multifile -->

---

### 7. Project Loom Virtual Threads Scaling (50 to 1,000 VTs)

<!-- BEGIN GENERATED: virtual-threads -->
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Virtual Threads | JUringBlocking (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup | JUring Syscalls/op | FC Syscalls/op | JUring CPU/op | FC CPU/op |
|---|---|---|---|---|---|---|---|---|---|
| 50 VTs | 257,117.2 ± 60,960.8 | 1,004.4 ± 238.1 MB/s | 283,203.3 ± 321.6 | 1,106.3 ± 1.3 MB/s | 0.91x | 5.4819 | 1.6938 | 30.8 µs | 25.6 µs |
| 100 VTs | 352,439.5 ± 84,936.2 | 1,376.7 ± 331.8 MB/s | 382,098.6 ± 472.8 | 1,492.6 ± 1.8 MB/s | 0.92x | 4.4565 | 1.7665 | 30.1 µs | 26.5 µs |
| 250 VTs | 441,046.7 ± 3,085.8 | 1,722.8 ± 12.1 MB/s | 445,255.8 ± 502.2 | 1,739.3 ± 2.0 MB/s | 0.99x | 4.3933 | 1.4086 | 28.9 µs | 31.2 µs |
| 500 VTs | 413,256.9 ± 116,060.1 | 1,614.3 ± 453.4 MB/s | 444,553.8 ± 10,378.7 | 1,736.5 ± 40.5 MB/s | 0.93x | 3.8754 | 1.2930 | 28.4 µs | 24.1 µs |
| 1000 VTs | 413,265.4 ± 148,754.4 | 1,614.3 ± 581.1 MB/s | 426,301.9 ± 85,555.4 | 1,665.2 ± 334.2 MB/s | 0.97x | 3.4514 | 1.2538 | 28.9 µs | 23.6 µs |

#### Impact of Loom Carrier Thread Cap (`maxPoolSize=1024` vs default 256 at 1,000 VTs)

| Configuration | Virtual Threads | Engine | Throughput (ops/s) | Bandwidth | Notes |
|---|---|---|---|---|---|
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | FileChannel | 379,776.6 ± 21,294.0 | 1,483.5 ± 83.2 MB/s | Carrier pool expanded from default 256 to 1024 |
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | JUringBlocking | 425,417.5 ± 86,203.3 | 1,661.8 ± 336.7 MB/s | Fixed pool of 4 poller threads |

#### Equal Thread Budget (Carrier Threads Limited to JUring Poller Count)
*When carrier threads are constrained to match the exact number of JUring pollers (4), FileChannel carrier pinning collapses throughput, whereas JUring unmounts virtual threads cleanly.*

| Configuration | Virtual Threads | Engine | Throughput (ops/s) | Bandwidth | Notes |
|---|---|---|---|---|---|
| Equal Carriers (4 threads) | 1,000 | FileChannel | 39,917.4 ± 10,251.7 | 155.9 ± 40.0 MB/s | Pinned carriers bottlenecked at QD=4 |
| Equal Carriers (4 threads) | 1,000 | JUringBlocking | 449,448.2 ± 12,379.6 | 1,755.7 ± 48.4 MB/s | Clean unmount via park(), full NVMe QD |

#### Equal Total Platform Thread Budget (1, 4, 8, 16 Threads at 1,000 VTs)
*Comparing FileChannel and JUringBlocking under strictly equal total OS platform thread budgets. FileChannel dedicates all platform threads to Loom carriers (which get pinned by synchronous pread64), while JUringBlocking divides its budget between carriers and background pollers.*

| Total Platform Threads | Engine | Thread Breakdown | Throughput (ops/s) | Bandwidth | Speedup | Notes |
|---|---|---|---|---|---|---|
| 1 Thread(s) | FileChannel | 1 carriers | 11,433.8 ± 31.5 | 44.7 ± 0.1 MB/s | baseline | Pinned in pread64, capped at QD=1 |
| 1 Thread(s) | JUringBlocking | 1 carrier + 1 poller (2T) | 399,108.7 ± 89,551.6 | 1,559.0 ± 349.8 MB/s | 34.91x | Minimum decoupled M:N poller |
| 4 Thread(s) | FileChannel | 4 carriers | 43,080.4 ± 220.6 | 168.3 ± 0.9 MB/s | baseline | Pinned in pread64, capped at QD=4 |
| 4 Thread(s) | JUringBlocking | 2 carriers + 2 pollers | 452,192.8 ± 1,262.9 | 1,766.4 ± 4.9 MB/s | 10.50x | Clean unmount via park(), full NVMe QD |
| 8 Thread(s) | FileChannel | 8 carriers | 73,003.5 ± 23,547.5 | 285.2 ± 92.0 MB/s | baseline | Pinned in pread64, capped at QD=8 |
| 8 Thread(s) | JUringBlocking | 4 carriers + 4 pollers | 389,340.6 ± 158,626.3 | 1,520.9 ± 619.6 MB/s | 5.33x | Clean unmount via park(), full NVMe QD |
| 16 Thread(s) | FileChannel | 16 carriers | 137,326.1 ± 32,534.5 | 536.4 ± 127.1 MB/s | baseline | Pinned in pread64, capped at QD=16 |
| 16 Thread(s) | JUringBlocking | 8 carriers + 8 pollers | 443,592.3 ± 40,401.8 | 1,732.8 ± 157.8 MB/s | 3.23x | Clean unmount via park(), full NVMe QD |

#### Cold-Cache Buffered Virtual Thread Read (`BUFFERED_COLD`, Page Cache Dropped)
*Working set sized so operations touch <25% of dataset. Device read rate measured via `/sys/block/<dev>/stat`.*

| Virtual Threads | JUring (ops/s) | Device Read Rate | FileChannel (ops/s) | Device Read Rate | Speedup |
|---|---|---|---|---|---|
| 50 VTs | 1,015,797.3 ± 14,125.4 | 84.2 MB/s | 1,745,526.6 ± 814,840.9 | 84.2 MB/s | 0.58x |
| 100 VTs | 1,093,363.5 ± 11,315.9 | 84.2 MB/s | 1,747,205.0 ± 539,509.2 | 84.2 MB/s | 0.63x |
| 250 VTs | 1,183,755.3 ± 14,773.4 | 84.2 MB/s | 1,662,725.0 ± 58,404.7 | 84.2 MB/s | 0.71x |
| 500 VTs | 1,324,229.1 ± 28,431.8 | 84.2 MB/s | 1,367,436.5 ± 622,970.4 | 84.2 MB/s | 0.97x |
| 1000 VTs | 1,357,944.7 ± 266,218.5 | 84.2 MB/s | 784,140.3 ± 27,358.8 | 84.1 MB/s | 1.73x |
<!-- END GENERATED: virtual-threads -->

---

### 8. Buffered Page-Cache I/O (DRAM Resident)

<!-- BEGIN GENERATED: buffered -->
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Workload | Engine | Concurrency | Throughput (ops/s) | Bandwidth | Syscalls/op | CPU time/op (`task-clock`) | Kernel CPU (`cycles:k/op`) |
|---|---|---|---|---|---|---|---|
| Page Cache Read | JUring (`submitBatch=64`) | 1T (Batch=64) | 1,532,004.1 ± 11,539.0 | 5,984.4 ± 45.1 MB/s | 0.0157 | 0.66 µs | 2,985 |
| Page Cache Read | FileChannel | 1T (Synchronous) | 1,314,810.3 ± 18,017.4 | 5,136.0 ± 70.4 MB/s | 1.0022 | 0.75 µs | 2,977 |
| Page Cache Write | JUring (`submitBatch=64`) | 1 submitting thread + io-wq workers | 944,411.5 ± 55,977.3 | 3,689.1 ± 218.7 MB/s | 0.0530 | 5.17 µs | 20,841 |
| Page Cache Write | FileChannel | 1T (Synchronous) | 475,367.8 ± 2,160.9 | 1,856.9 ± 8.4 MB/s | 1.0016 | 2.11 µs | 9,123 |
<!-- END GENERATED: buffered -->

> [!NOTE]
> **io-wq Worker Threads in Buffered Writes:**
> For buffered writes, ext4 cannot complete writes asynchronously without blocking on inode locks and page cache dirtier accounting. The Linux kernel offloads these operations to kernel `io-wq` worker threads (`iou-wrk`). This kernel-level concurrency delivers higher ops/s (944k vs 475k) at the cost of higher CPU time per op (20,841 vs 9,123 kernel cycles) compared to synchronous `pwrite64`.

---

### 9. Physical NAND Flash Cell Latency (QD=1)

<!-- BEGIN GENERATED: latency -->
Unbatched single-operation read sampling (`Mode.SampleTime`, `OperationsPerInvocation(1)`) confirming direct hardware NVMe DMA latency:

| Metric | JUring Fused (`submitAndWait(1)`) | JUring Unfused (`submit() + wait()`) | FileChannel (`DIRECT pread64`) |
|---|---|---|---|
| Syscalls / op | 1 enter/op (`to_submit=1, min_complete=1`) | 2 enters/op (`submit=1` + `wait=1`) | 1 `pread64` syscall |
| Mean Latency | 92.09 µs | 87.19 µs | 87.39 µs |
| p0 (Min) | 78.85 µs | 78.72 µs | 78.98 µs |
| p50 (Median) | 82.94 µs | 82.82 µs | 82.94 µs |
| p90 | 98.05 µs | 97.79 µs | 98.05 µs |
| p95 | 98.56 µs | 98.30 µs | 98.56 µs |
| p99 | 113.18 µs | 99.46 µs | 99.84 µs |
| p99.9 | 1272.28 µs | 104.19 µs | 104.19 µs |
| p100 (Max) | 7389.18 µs | 3829.76 µs | 524.29 µs |
<!-- END GENERATED: latency -->

> [!NOTE]
> An 82.8 µs median round trip at QD=1 reflects physical BiCS4 3D TLC flash cell access latency over PCIe, whereas page cache hits register in sub-microsecond times.

---

### 10. Project Panama (FFM API) Downcall Overhead

<!-- BEGIN GENERATED: ffm -->
Benchmarking Foreign Function & Memory downcall invocations across dispatch patterns using [`FfmInvocationBenchmark`](src/test/java/bench/suite/FfmInvocationBenchmark.java):

| Invocation Strategy | Call Mechanism | Average Time (ns/op) |
|---|---|---|
| `staticFinal_critical_invokeExact` | Linker.Option.critical(true) | 2.62 ± 0.01 ns/op |
| `methodHandleProxies_critical` | Linker.Option.critical(true) | 4.67 ± 0.02 ns/op |
| `staticFinal_invoke` | Direct MethodHandle | 5.75 ± 0.43 ns/op |
| `record_staticFinal_proxy` | MethodHandleProxies / Record Interface | 5.91 ± 0.01 ns/op |
| `staticFinal_invokeExact` | Direct MethodHandle | 5.92 ± 0.02 ns/op |
| `instanceField_invokeExact` | Instance Field Handle | 7.99 ± 0.06 ns/op |
| `instanceField_extractedFromRecord_proxy` | MethodHandleProxies / Record Interface | 8.23 ± 0.07 ns/op |
| `methodHandleProxies_asInterfaceInstance` | MethodHandleProxies / Record Interface | 8.24 ± 0.12 ns/op |
| `record_instance_proxy` | MethodHandleProxies / Record Interface | 8.64 ± 0.07 ns/op |
<!-- END GENERATED: ffm -->

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

### 4. Buffered I/O with MemorySegments and byte[]

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
   Defined in [`IoUringOptions.java`](src/main/java/com/davidvlijmincx/lio/api/IoUringOptions.java) and configured in [`IoUringDispatcher.java`](src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java). Confines submission and completion processing strictly to the calling thread. The Linux kernel skips internal completion queue spinlocks and cross-CPU Inter-Processor Interrupts (IPIs).
2. **`IORING_REGISTER_RING_FDS` (`IORING_ENTER_REGISTERED_RING`):**
   In [`IoUringDispatcher.java`](src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java). JUring registers the ring file descriptor with the kernel (`io_uring_register(IORING_REGISTER_RING_FDS)`). Subsequent `io_uring_enter` syscalls pass `IORING_ENTER_REGISTERED_RING`, bypassing POSIX file table lookups and `fget()`/`fput()` atomic reference counts.
3. **Fixed Buffer & Registered File Tables:**
   Memory pages and file descriptors are mapped in advance via `io_uring_register_buffers` and `io_uring_register_files`, avoiding page pin/unpin costs (`get_user_pages`) and descriptor resolution on the hot path.
4. **Fused Pipeline Syscalls:**
   `ring.submitAndWaitForBatchResult(minWait, qd, consumer)` executes SQE submission and event reaping in a single `io_uring_enter` syscall.
5. **Zero-Allocation Primitive Access:**
   Submission queue entries (SQEs) and completion queue entries (CQEs) are accessed directly through Java primitive `VarHandle` operations, ensuring zero heap allocation on the calling thread during steady-state I/O loops.
6. **Project Loom Striped Poller Ring Architecture:**
   In [`JUringBlocking.java`](src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java), virtual threads unmount via `LockSupport.parkNanos()` during I/O and submit tasks to bounded MPSC queues (Vyukov-style). Requests are hash-striped across dedicated platform poller threads (`JUringBlocking-Poller-*`) to maintain strict Linux single-issuer kernel affinity without carrier pool starvation.
7. **StoreLoad Memory Barrier Handshake:**
   In [`JUringBlocking.java`](src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java) and [`IoUringDispatcher.java`](src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java), two-way StoreLoad fences (`VarHandle.fullFence()`) prevent lost wakeup hangs in poller unpark handshakes on out-of-order store-buffering architectures. Handshake safety has been tested with OpenJDK JCStress (196 configurations, 0 forbidden outcomes).

---

## Methodology and Limitations

1. **Fixed-QD Closed-Loop Latency:**
   Latency percentiles in Section 8 are measured in a closed-loop benchmark at fixed QD=1 (`Mode.SampleTime`). Closed-loop benchmarks hold the issue rate to the completion rate, which avoids artificial queueing delays but is subject to coordinated omission under external arrival distributions.
2. **`perfnorm` Measurement Scope:**
   Hardware cycle and instruction metrics (`cycles:u`, `cycles:k`, `task-clock`) collected via JMH's `LinuxPerfNormProfiler` cover the threads of the forked JVM process over the active measurement window. They do not capture kernel interrupt-context completion handling executed on other CPU cores by the NVMe driver.
3. **fio Benchmark Configuration:**
   The head-to-head comparison against native C `fio` (v3.40) was executed with 5 repeats for read benchmarks and 3 repeats for write benchmarks per engine with randomized execution order. Both engines targeted the identical 10 GB contiguous pre-allocated file on ext4 with `O_DIRECT`, 4KB block size, fixed buffers, registered files, and QD=64. Raw JSON files for all runs are preserved in `results/2026-11-05/`.
4. **Write Cache / Steady-State TLC Dynamics:**
   Consumer NVMe drives exhibit three distinct write regimes: (1) a short dynamic pSLC cache burst (~450k IOPS for the first ~6 GB), (2) a flat steady-state direct-to-TLC plateau (~190k–194k IOPS on a 10 GB single file; ~170k–173k IOPS across 2,211 files), and (3) a garbage-collection / inline block-erase cliff (~20k IOPS) if >120 GB is written continuously without idle GC recovery. All write benchmarks (`FioComparisonBenchmark`, `DirectWriteBenchmark`, and `MultiThreadScalingBenchmark`) use full-dataset `FioPrng` offsets (`waitBatch = 1`), drain the pSLC burst during warmup, and include inter-tier idle pauses for background GC recovery so that every measurement captures steady-state TLC throughput.
5. **Single-Thread 1T×QD=64 Direct Read Discrepancies across Sections:**
   In earlier benchmark runs, three single-thread 4KB `O_DIRECT` read suites at QD=64 reported differing throughput numbers: Section 1 (`FioComparisonBenchmark`, ~441k IOPS), Section 2 (`DirectReadBenchmark`, ~345k IOPS), and Section 3 (`MultiThreadScalingBenchmark`, ~324k IOPS). Investigation identified three root causes:
   - **Shared file state and drive SLC cache contamination:** Reads and writes previously shared `write_files/single_file_bench.bin`. Recent writes left LBAs cached in the drive's SLC buffer, altering subsequent read throughput. Separating read benchmarks onto dedicated, read-only files in `read_files/` and scheduling all read suites prior to write suites eliminates this cross-contamination.
   - **Offset distribution differences:** `DirectReadBenchmark` and `MultiThreadScalingBenchmark` previously replayed a fixed pool of 65,536 pseudo-random offsets (256 MB working set) while `FioComparisonBenchmark` drew full-file random offsets via `FioPrng`. Standardizing all direct-read benchmarks on the same full-file PRNG stream aligns I/O working sets across suites.
   - **Wait batching parameter (`waitBatch`):** `MultiThreadScalingBenchmark` used `waitBatch = min(8, qd >> 3)` (8 at QD=64), whereas `DirectReadBenchmark` and `FioComparisonBenchmark` used `waitBatch = 1` (`submitAndWait(1)`).

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

* **Operating System:** Linux Kernel 6.1 or newer for documented setup (`IORING_SETUP_DEFER_TASKRUN`, `IORING_SETUP_SINGLE_ISSUER`, and registered ring FDs). Kernel 5.6 is the minimum for basic `io_uring` read/write opcodes.
* **Java Virtual Machine:** OpenJDK 25 or higher (compiled with source/target 25).
* **Dependencies:** None. Direct native downcalls via FFM (JEP 454) — **no `liburing.so` required**.
* **Permissions / Memory Limits:** For multi-threaded buffer registration, ensure adequate locked memory limits:
  ```bash
  ulimit -l unlimited
  ```

### Building & Testing

```bash
# Build the project and execute the JUnit 5 test suite
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
