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

* **Competitive Parity vs. Native C `fio` (`ioengine=io_uring`):** Matches native C `fio` with low system-call overhead (~0.29 enters/op) under 4KB random direct reads. See [Head-to-Head Parity vs. Native C fio](#1-head-to-head-parity-vs-native-c-fio).
* **Hardware Saturation on Direct Reads:** Reaches the NVMe controller's random read ceiling (~711,000 IOPS / ~2.78 GB/s) at 4 threads with zero heap allocations on the calling-thread hot path (`0L` bytes). See [4 KB Direct Read Scaling](#3-4-kb-direct-read-scaling-single-10-gb-file).
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
| `fio` (default settings) | 372,417.2 ± 9,128.0 | 1,454.8 ± 35.7 MB/s | 1.1810 | — | Default submission / reap |
| `fio` (best batching: B=8) | 440,522.3 ± 9,534.9 | 1,720.8 ± 37.2 MB/s | 0.2541 | baseline | `--iodepth_batch_submit=8` |
| JUring (JMH) | 462,330.7 ± 1,877.8 | 1,806.0 ± 7.3 MB/s | 0.2928 | +5.0% | Batched `submitAndWait(1)` |

#### 4KB Random Direct Write (`randwrite`, QD=64, 10 GB File, `O_DIRECT`, Fixed Buffers, Registered Files)

| Engine | Throughput (ops/s) | Bandwidth (MB/s) | Enters / op (`io_uring_enter`) | Delta vs Best fio | Configuration |
|---|---|---|---|---|---|
| `fio` (default settings) | 194,055.8 ± 3,064.6 | 758.0 ± 12.0 MB/s | 1.4869 | — | Default submission / reap |
| `fio` (best batching: B=32) | 191,982.3 ± 4,350.2 | 749.9 ± 17.0 MB/s | 0.7858 | baseline | `--iodepth_batch_submit=32` |
| JUring (JMH) | 190,413.8 ± 4,322.6 | 743.8 ± 16.9 MB/s | 0.2860 | -0.8% | Batched `submitAndWait(1)` |
<!-- END GENERATED: fio-compare -->

> [!NOTE]
> **Syscall Amortization and Batching:**
> System calls per operation are measured via `perf stat -e syscalls:sys_enter_io_uring_enter` for fio and JMH `--syscalls`. JUring combines submission and completion waiting into single `io_uring_enter(min_complete=1, GETEVENTS)` syscalls (~0.29 enters/op), whereas default fio configuration commits submissions and reaps completions in separate syscalls (~1.18 enters/op). Sweeping fio batch settings narrows this difference.

---

### 2. Single-Core Framework Overhead & Direct I/O Efficiency

<!-- BEGIN GENERATED: single-core -->
Evaluation of pure framework overhead (`NopBenchmark`), followed by single-thread 4KB Direct Read and Direct Write (`O_DIRECT`) comparing QD=1 and QD=64 against `FileChannel`:
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Target | Concurrency / QD | Throughput (ops/s) | Bandwidth | Syscalls / op (`raw_syscalls:sys_enter`) | User CPU (`cycles:u/op`) | Kernel CPU (`cycles:k/op`) | Heap Alloc |
|---|---|---|---|---|---|---|---|
| `NopBenchmark.juringNop` | 1T (QD=64, ring memory only) | 11,465,042.1 ± 80,341.0 | — | 0.0313 (32.0 ops/call) | 52.3 | 352.4 | 0.006 B/op |
| `FileChannel.read` | 1T (QD=1, synchronous `pread64`) | 16,003.3 ± 61.6 | 62.5 ± 0.2 MB/s | 1.0020 | 1454.4 | 24506.6 | 0.210 B/op |
| `JUring.read` (QD=1) | 1T (QD=1, unbatched `O_DIRECT`) | 16,055.0 ± 47.1 | 62.7 ± 0.2 MB/s | 1.0028 | 3141.4 | 25385.4 | 6.451 B/op |
| `JUring.read` (QD=64) | 1T (QD=64, batched pipeline) | 461,926.4 ± 5,608.3 | 1,804.4 ± 21.9 MB/s | 0.2519 | 209.3 | 8703.9 | 0.225 B/op |
| `FileChannel.write` | 1T (QD=1, synchronous `pwrite64`) | 69,868.9 ± 5,779.7 | 272.9 ± 22.6 MB/s | 0.9995 | 866.3 | 25068.8 | 0.049 B/op |
| `JUring.write` (QD=1) | 1T (QD=1, unbatched `O_DIRECT`) | 69,738.1 ± 5,435.5 | 272.4 ± 21.2 MB/s | 1.0009 | 738.0 | 26132.8 | 1.542 B/op |
| `JUring.write` (QD=64) | 1T (QD=64, batched pipeline) | 190,961.5 ± 13,755.4 | 745.9 ± 53.7 MB/s | 0.1990 | 250.8 | 11477.1 | 0.583 B/op |
<!-- END GENERATED: single-core -->

> [!NOTE]
> **Allocation Accounting (JMH GC Profiler vs Calling-Thread Zero Allocation):**
> * **JMH Profiler (`-prof gc`):** The `Heap Alloc` column above reports whole-process GC allocation rate (`gc.alloc.rate.norm`) recorded by JMH during benchmark iterations. These sub-byte to few-byte readings (0.006 to 6.451 B/op depending on ops/sec divisor) capture background HotSpot C2 compiler thread activity and JVM housekeeping threads during the measurement window.
> * **Zero Calling-Thread Allocations:** In steady state, JUring's hot-path pipeline (submitting SQEs, reaping CQEs via `ResultConsumer`, and reusing fixed buffers) allocates strictly **`0L`** bytes on the calling application thread across 100,000+ operations. This is deterministically enforced by [`ZeroAllocationHotPathTest.java`](src/test/java/com/davidvlijmincx/lio/api/ZeroAllocationHotPathTest.java) via `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`.

> [!TIP]
> **Equal Concurrency Parity:**
> At QD=1, `FileChannel` and `JUring` perform identically, directly matching the flash cell access latency (~83 µs). When queue depth increases to 64, JUring keeps the NVMe hardware command queue filled, amortizing syscalls down to ~0.25 enters/op.

---

### 3. 4 KB Direct Read Scaling (Single 10 GB File)

<!-- BEGIN GENERATED: scaling-read -->
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| 1 Thread | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | 463,050.2 ± 5,082.3 | 1,808.8 ± 19.9 MB/s | 16,015.6 ± 34.2 | 62.6 ± 0.1 MB/s | 28.9x |
| 2 Threads | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | 689,876.8 ± 390.0 | 2,694.8 ± 1.5 MB/s | 31,313.2 ± 107.9 | 122.3 ± 0.4 MB/s | 22.0x |
| 4 Threads | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | 709,969.0 ± 78.4 | 2,773.3 ± 0.3 MB/s | 59,962.2 ± 146.8 | 234.2 ± 0.6 MB/s | 11.8x |
| 8 Threads | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | 710,519.3 ± 401.2 | 2,775.5 ± 1.6 MB/s | 112,427.8 ± 168.4 | 439.2 ± 0.7 MB/s | 6.3x |
| 16 Threads | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | 711,008.6 ± 1,259.3 | 2,777.4 ± 4.9 MB/s | 199,891.8 ± 334.5 | 780.8 ± 1.3 MB/s | 3.6x |
<!-- END GENERATED: scaling-read -->

At 4 threads and above, JUring saturates the SSD controller's random read IOPS ceiling at ~711,000 ops/s (2.78 GB/s) with zero calling-thread JVM heap allocations (`0L` bytes).

---

### 4. 4 KB Direct Write Scaling & Physical SLC Dynamics

<!-- BEGIN GENERATED: scaling-write -->
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| 1 Thread | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | 190,555.5 ± 21,848.7 | 744.4 ± 85.3 MB/s | 71,848.8 ± 2,265.2 | 280.7 ± 8.8 MB/s | 2.7x |
| 2 Threads | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | 189,824.8 ± 18,864.3 | 741.5 ± 73.7 MB/s | 119,601.5 ± 2,813.4 | 467.2 ± 11.0 MB/s | 1.6x |
| 4 Threads | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | 190,854.6 ± 12,295.2 | 745.5 ± 48.0 MB/s | 189,822.3 ± 23,741.2 | 741.5 ± 92.7 MB/s | 1.0x |
| 8 Threads | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | 190,349.8 ± 18,913.6 | 743.6 ± 73.9 MB/s | 192,691.5 ± 4,571.7 | 752.7 ± 17.9 MB/s | 1.0x |
| 16 Threads | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | 193,105.4 ± 26,574.6 | 754.3 ± 103.8 MB/s | 191,675.9 ± 19,227.1 | 748.7 ± 75.1 MB/s | 1.0x |
<!-- END GENERATED: scaling-write -->

> [!NOTE]
> **Understanding Direct Write Saturation & Steady-State TLC Dynamics:**
> On a single thread (`1T × QD=64`), `JUring` saturates the drive's steady-state TLC random write ceiling (~190,556 ops/s / ~744 MB/s), delivering a **2.7x speedup** over single-threaded `FileChannel` (`71,849 ops/s`). Because NVMe write completions are acknowledged via the controller's non-volatile write cache (~14 µs per `pwrite64`), 4 synchronous `FileChannel` threads (`QD=4`) are already sufficient to reach the drive's ~191k IOPS steady-state TLC write ceiling, resulting in `1.0x` parity from 4 to 16 threads. Measuring inside the steady-state TLC regime—after draining the initial ~6 GB pSLC burst buffer, using full-dataset PRNG offsets (`FioPrng`) to avoid 256 MB LBA overwrite coalescing, and allowing idle GC recovery between tiers—eliminates bimodal pSLC/GC transitions.

---

### 5. Multi-File Scaling (2,211 Discrete Files)

<!-- BEGIN GENERATED: multifile -->
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Threads | JUring Read (ops/s) | FileChannel Read (ops/s) | Read Speedup | JUring Write (ops/s) | FileChannel Write (ops/s) | Write Speedup |
|---|---|---|---|---|---|---|
| 1 Thread | 330,786.1 ± 780.0 | 10,248.5 ± 4,754.1 | 32.3x | 171,411.8 ± 17,476.3 | 66,819.1 ± 777.3 | 2.6x |
| 2 Threads | 409,954.3 ± 76,671.2 | 22,282.1 ± 86.8 | 18.4x | 172,302.9 ± 17,024.1 | 130,227.8 ± 1,668.8 | 1.3x |
| 4 Threads | 468,020.6 ± 38,165.8 | 43,159.0 ± 77.0 | 10.8x | 170,032.1 ± 13,988.8 | 171,839.0 ± 5,008.8 | 1.0x |
| 8 Threads | 477,037.5 ± 1,084.0 | 81,498.2 ± 201.3 | 5.9x | 173,193.1 ± 13,104.3 | 169,649.0 ± 14,776.6 | 1.0x |
| 16 Threads | 477,476.8 ± 1,274.2 | 144,912.6 ± 4,295.8 | 3.3x | 170,913.6 ± 19,535.4 | 166,644.1 ± 21,540.5 | 1.0x |
<!-- END GENERATED: multifile -->

---

### 6. Project Loom Virtual Threads Scaling (50 to 1,000 VTs)

<!-- BEGIN GENERATED: virtual-threads -->
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Virtual Threads | JUringBlocking (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup | JUring Syscalls/op | FC Syscalls/op | JUring CPU/op | FC CPU/op |
|---|---|---|---|---|---|---|---|---|---|
| 50 VTs | 371,009.9 ± 2,433.3 | 1,449.3 ± 9.5 MB/s | 373,307.8 ± 79,977.3 | 1,458.2 ± 312.4 MB/s | 0.99x | 4.6350 | 1.7031 | 29.3 µs | 24.4 µs |
| 100 VTs | 552,956.2 ± 5,617.7 | 2,160.0 ± 21.9 MB/s | 589,949.2 ± 2,698.5 | 2,304.5 ± 10.5 MB/s | 0.94x | 3.2696 | 1.7331 | 27.6 µs | 28.6 µs |
| 250 VTs | 644,876.0 ± 3,163.5 | 2,519.0 ± 12.4 MB/s | 644,655.6 ± 15,314.8 | 2,518.2 ± 59.8 MB/s | 1.00x | 2.5765 | 1.7109 | 27.4 µs | 34.4 µs |
| 500 VTs | 654,002.1 ± 2,314.4 | 2,554.7 ± 9.0 MB/s | 649,593.1 ± 3,344.5 | 2,537.5 ± 13.1 MB/s | 1.01x | 2.3585 | 1.7721 | 26.6 µs | 33.8 µs |
| 1000 VTs | 657,283.0 ± 2,342.6 | 2,567.5 ± 9.2 MB/s | 592,315.0 ± 24,526.4 | 2,313.7 ± 95.8 MB/s | 1.11x | 2.0744 | 1.7187 | 25.7 µs | 32.9 µs |

#### Impact of Loom Carrier Thread Cap (`maxPoolSize=1024` vs default 256 at 1,000 VTs)

| Configuration | Virtual Threads | Engine | Throughput (ops/s) | Bandwidth | Notes |
|---|---|---|---|---|---|
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | FileChannel | 481,494.2 ± 57,122.3 | 1,880.8 ± 223.1 MB/s | Carrier pool expanded from default 256 to 1024 |
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | JUringBlocking | 656,997.5 ± 1,041.2 | 2,566.4 ± 4.1 MB/s | Fixed pool of 4 poller threads |

#### Cold-Cache Buffered Virtual Thread Read (`BUFFERED_COLD`, Page Cache Dropped)
*Working set sized so operations touch <25% of dataset. Device read rate measured via `/sys/block/<dev>/stat`.*

| Virtual Threads | JUring (ops/s) | Device Read Rate | FileChannel (ops/s) | Device Read Rate | Speedup |
|---|---|---|---|---|---|
| 50 VTs | 1,026,586.4 ± 10,445.0 | 84.2 MB/s | 1,638,921.7 ± 310,093.8 | 84.2 MB/s | 0.63x |
| 100 VTs | 1,088,723.9 ± 15,832.9 | 84.1 MB/s | 1,700,734.3 ± 67,157.5 | 84.2 MB/s | 0.64x |
| 250 VTs | 1,187,601.5 ± 18,235.5 | 84.1 MB/s | 1,868,938.4 ± 324,242.6 | 84.2 MB/s | 0.64x |
| 500 VTs | 1,313,172.2 ± 30,039.0 | 84.2 MB/s | 1,505,350.9 ± 100,475.0 | 84.2 MB/s | 0.87x |
| 1000 VTs | 1,440,305.5 ± 38,722.7 | 84.2 MB/s | 793,549.6 ± 30,409.6 | 84.1 MB/s | 1.82x |
<!-- END GENERATED: virtual-threads -->

---

### 7. Buffered Page-Cache I/O (DRAM Resident)

<!-- BEGIN GENERATED: buffered -->
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Workload | Engine | Concurrency | Throughput (ops/s) | Bandwidth | Syscalls/op | CPU time/op (`task-clock`) | Kernel CPU (`cycles:k/op`) |
|---|---|---|---|---|---|---|---|
| Page Cache Read | JUring (`submitBatch=64`) | 1T (Batch=64) | 1,524,305.1 ± 18,406.0 | 5,954.3 ± 71.9 MB/s | 0.0168 | 0.66 µs | 2,942 |
| Page Cache Read | FileChannel | 1T (Synchronous) | 1,294,654.4 ± 42,589.2 | 5,057.2 ± 166.4 MB/s | 1.0061 | 0.80 µs | 3,072 |
| Page Cache Write | JUring (`submitBatch=64`) | 1 submitting thread + io-wq workers | 893,554.4 ± 30,515.4 | 3,490.4 ± 119.2 MB/s | 0.0341 | 5.22 µs | 20,998 |
| Page Cache Write | FileChannel | 1T (Synchronous) | 493,274.5 ± 25,402.3 | 1,926.9 ± 99.2 MB/s | 1.0093 | 2.04 µs | 8,655 |
<!-- END GENERATED: buffered -->

> [!NOTE]
> **io-wq Worker Threads in Buffered Writes:**
> For buffered writes, ext4 cannot complete writes asynchronously without blocking on inode locks and page cache dirtier accounting. The Linux kernel offloads these operations to kernel `io-wq` worker threads (`iou-wrk`). This kernel-level concurrency delivers higher ops/s (893k vs 493k) at the cost of higher CPU time per op (20,998 vs 8,655 kernel cycles) compared to synchronous `pwrite64`.

---

### 8. Physical NAND Flash Cell Latency (QD=1)

<!-- BEGIN GENERATED: latency -->
Unbatched single-operation read sampling (`Mode.SampleTime`, `OperationsPerInvocation(1)`) confirming direct hardware NVMe DMA latency:

| Metric | JUring Fused (`submitAndWait(1)`) | JUring Unfused (`submit() + wait()`) | FileChannel (`DIRECT pread64`) |
|---|---|---|---|
| Syscalls / op | 1 enter/op (`to_submit=1, min_complete=1`) | 2 enters/op (`submit=1` + `wait=1`) | 1 `pread64` syscall |
| Mean Latency | 87.01 µs | 87.10 µs | 87.65 µs |
| p0 (Min) | 78.85 µs | 78.85 µs | 79.23 µs |
| p50 (Median) | 82.82 µs | 82.82 µs | 83.20 µs |
| p90 | 97.79 µs | 97.92 µs | 98.43 µs |
| p95 | 98.43 µs | 98.43 µs | 98.94 µs |
| p99 | 99.71 µs | 99.71 µs | 101.25 µs |
| p99.9 | 106.63 µs | 107.01 µs | 112.17 µs |
| p100 (Max) | 3198.98 µs | 3747.84 µs | 2633.73 µs |
<!-- END GENERATED: latency -->

> [!NOTE]
> An 82.9 µs median round trip at QD=1 reflects physical BiCS4 3D TLC flash cell access latency over PCIe, whereas page cache hits register in sub-microsecond times.

---

### 9. Project Panama (FFM API) Downcall Overhead

<!-- BEGIN GENERATED: ffm -->
Benchmarking Foreign Function & Memory downcall invocations across dispatch patterns using [`FfmInvocationBenchmark`](src/test/java/bench/suite/FfmInvocationBenchmark.java):

| Invocation Strategy | Call Mechanism | Average Time (ns/op) |
|---|---|---|
| `staticFinal_critical_invokeExact` | Linker.Option.critical(true) | 2.64 ± 0.02 ns/op |
| `methodHandleProxies_critical` | Linker.Option.critical(true) | 4.71 ± 0.04 ns/op |
| `record_staticFinal_proxy` | MethodHandleProxies / Record Interface | 5.95 ± 0.03 ns/op |
| `staticFinal_invokeExact` | Direct MethodHandle | 5.96 ± 0.06 ns/op |
| `staticFinal_invoke` | Direct MethodHandle | 5.96 ± 0.08 ns/op |
| `instanceField_invokeExact` | Instance Field Handle | 8.04 ± 0.05 ns/op |
| `methodHandleProxies_asInterfaceInstance` | MethodHandleProxies / Record Interface | 8.28 ± 0.06 ns/op |
| `instanceField_extractedFromRecord_proxy` | MethodHandleProxies / Record Interface | 8.33 ± 0.12 ns/op |
| `record_instance_proxy` | MethodHandleProxies / Record Interface | 8.70 ± 0.05 ns/op |
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
   The head-to-head comparison against native C `fio` (v3.40) was executed with 5 repeats for read benchmarks and 3 repeats for write benchmarks per engine with randomized execution order. Both engines targeted the identical 10 GB contiguous pre-allocated file on ext4 with `O_DIRECT`, 4KB block size, fixed buffers, registered files, and QD=64. Raw JSON files for all runs are preserved in `results/2026-11-03/`.
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
