# JUring: High-Performance Linux io_uring for Java

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-22%2B%20%7C%2025%2B-orange.svg)](https://openjdk.org/)
[![Linux](https://img.shields.io/badge/Linux-Kernel%205.1%2B-green.svg)](https://kernel.org/)
[![io_uring](https://img.shields.io/badge/io__uring-Native%20Zero--Copy-red.svg)](https://git.kernel.dk/cgit/liburing/)
[![Oracle ACE](https://img.shields.io/badge/Author-David%20Vlijmincx%20(Oracle%20ACE)-blueviolet.svg)](https://davidvlijmincx.com)

When I first started exploring **Virtual Threads** in Java, I wanted to understand everything about them—their performance characteristics, when they yield, and where their limits lie. That research quickly led me to a major architectural roadblock in the JVM: **file I/O operations**.

In standard Java, file I/O operations on `FileChannel` perform synchronous kernel system calls (`pread64`/`pwrite64`). Because POSIX file descriptors don't support readiness polling like network sockets (`epoll`), the JVM cannot unmount virtual threads during file I/O. Instead, Loom's `ForkJoinPool` tries to compensate by spinning up extra OS carrier threads. Under heavy concurrency, that leads to carrier thread exhaustion, CPU cache thrashing, and throughput collapse.

I built **JUring** to solve this problem.

JUring is a pure-Java library that provides direct native bindings to Linux's high-performance **`io_uring`** asynchronous I/O subsystem using Java's **Foreign Function & Memory (FFM) API** (JEP 454). No JNI code, no external `.so` dependencies (not even `liburing`), and zero heap allocations on the hot path (`0.000 B/op`). It turns blocking file I/O into non-blocking, yielding operations for Virtual Threads, and delivers asynchronous direct NVMe throughput that beats tuned native C `fio` while outperforming standard Java `FileChannel` by up to **31x**.

---

## Table of Contents

- [Why I Built JUring: The Problem with Java File I/O](#why-i-built-juring-the-problem-with-java-file-io)
- [Key Performance Highlights](#key-performance-highlights)
- [Hardware Lab & Benchmark Methodology](#hardware-lab--benchmark-methodology)
- [Empirical Benchmark Results](#empirical-benchmark-results)
  - [1. Single-Core Efficiency & Syscall Amortization](#1-single-core-efficiency--syscall-amortization)
  - [2. 4 KB Direct Read Scaling (Single 10 GB File)](#2-4-kb-direct-read-scaling-single-10-gb-file)
  - [3. 4 KB Direct Write Scaling & Physical SLC Dynamics](#3-4-kb-direct-write-scaling--physical-slc-dynamics)
  - [4. Multi-File Scaling (2,211 Discrete Files)](#4-multi-file-scaling-2211-discrete-files)
  - [5. Project Loom Virtual Threads Scaling (50 to 1,000 VTs)](#5-project-loom-virtual-threads-scaling-50-to-1000-vts)
  - [6. Buffered Page-Cache I/O (DRAM Resident)](#6-buffered-page-cache-io-dram-resident)
  - [7. Physical NAND Flash Cell Latency (QD=1)](#7-physical-nand-flash-cell-latency-qd1)
  - [8. Pure Project Panama (FFM API) Downcall Overhead](#8-pure-project-panama-ffm-api-downcall-overhead)
- [Code Examples: Using JUring in Your Application](#code-examples-using-juring-in-your-application)
  - [1. High-Throughput Direct Read (Registered Files & Fixed Buffers)](#1-high-throughput-direct-read-registered-files--fixed-buffers)
  - [2. High-Throughput Direct Write](#2-high-throughput-direct-write)
  - [3. Fused Submission & Completion Pipeline Loop](#3-fused-submission--completion-pipeline-loop)
  - [4. Buffered I/O with ByteBuffers / MemorySegments](#4-buffered-io-with-bytebuffers--memorysegments)
  - [5. Non-Blocking Virtual Thread Blocking API (`JUringBlocking`)](#5-non-blocking-virtual-thread-blocking-api-juringblocking)
- [How Under-the-Hood Architectural Optimizations Work](#how-under-the-hood-architectural-optimizations-work)
- [How to Run the Benchmarks Yourself](#how-to-run-the-benchmarks-yourself)
- [Requirements & Setup](#requirements--setup)
- [Further Reading & Connect](#further-reading--connect)

---

## Why I Built JUring: The Problem with Java File I/O

For years, Java developers accepted a fundamental limitation: disk I/O blocks the calling OS thread. When Project Loom introduced Virtual Threads in Java 21, millions of concurrent lightweight threads became possible for network services. But file I/O was left behind.

When a Virtual Thread calls `fileChannel.read()`:
1. It executes a synchronous kernel call (`pread64`).
2. The Virtual Thread **cannot yield** because standard Linux file descriptors don't support non-blocking readiness notifications like network sockets do.
3. The underlying OS carrier thread remains blocked until the physical storage media responds.
4. Loom's `ForkJoinPool` tries to compensate by creating more carrier threads (spawning dozens or hundreds of platform threads).

At 500 to 1,000 concurrent Virtual Threads, this compensation mechanism collapses. OS carrier thread pools become exhausted, context switches skyrocket, and throughput drops sharply.

By integrating Linux **`io_uring`** via **Project Panama (FFM API)**, JUring fixes this:
* **True Asynchronous Submissions:** Applications push I/O requests into a shared ring buffer without making a system call per request.
* **Non-Blocking Virtual Thread Yielding (`JUringBlocking`):** Virtual threads unmount cleanly (`LockSupport.park()`), releasing the carrier thread back to the `ForkJoinPool` while the NVMe controller processes the I/O.
* **Single-Issuer Poller Workers:** Dedicated platform poller threads process ring completions and wake up the parked virtual threads with zero carrier thread starvation.
* **Zero JNI Overhead:** We talk directly to the Linux kernel using pure Java downcall method handles, `VarHandle` mapped ring memory, and off-heap `MemorySegment` buffers.

---

## Key Performance Highlights

Here is what the empirical measurements on physical PCIe Gen4 NVMe flash storage show:

* **Faster than Tuned Native C `fio`:** JUring beats tuned native C `fio` (v3.40) on direct NVMe I/O across multi-thread scaling tiers:
  * **Direct Read (4 Threads):** **716,882 ops/s (2,800 MB/s)** vs. tuned `fio` **627,346 IOPS** (**+14.3% faster**).
  * **Direct Read (8 Threads):** **715,163 ops/s (2,794 MB/s)** vs. tuned `fio` **635,000 IOPS** (**+12.6% faster**).
  * **Direct Write (1 Thread):** **475,094 ops/s (1,856 MB/s)** vs. tuned `fio` **414,450 IOPS** (**+14.6% faster**).
* **Multi-File Scalability (`IOSQE_FIXED_FILE`):** Across 2,211 discrete pre-allocated files, JUring sustains **473,970 ops/s read (1,851 MB/s)** and **404,329 ops/s write (1,579 MB/s)**. Native C `fio` completely crashed under this workload with `EMFILE (Too many open files)`.
* **Single-Thread Dominance over `FileChannel`:** A single JUring thread delivers **275,463 read ops/s** and **475,094 write ops/s**, easily outperforming **16 concurrent platform threads** of standard Java `FileChannel` (199,364 read ops/s, 239,030 write ops/s).
* **Microscopic User CPU Cost:** Only **24.6 – 53.1 ns** of user CPU per op. HotSpot's C2 JIT compiles the ring submission and completion loops into zero-allocation (`0.000 B/op`) primitive `VarHandle` memory operations.
* **Fused System Call Amortization:** By combining SQE submission and completion waiting into single `io_uring_enter` system calls, syscall frequency drops to **0.05 – 0.12 enters/op** (up to 20 operations per syscall).
* **Project Loom Virtual Thread Dominance:** At 1,000 concurrent Virtual Threads on direct NVMe storage, standard `FileChannel` collapses to 256,395 ops/s due to carrier pool exhaustion. `JUringBlocking` sustains **617,735 ops/s (+140.9% faster / 2.41x over FileChannel)** while slashing kernel CPU time by **75.8%** (23.0 ns vs. 95.1 ns/op).
* **Storage Authenticity Verified:** DMA NVMe TLC NAND transfers verified via physical `/proc/diskstats` sector deltas (+1,859,586 commands / 7.26 GB) and 100% bit-accurate poison-buffer byte comparisons against disk.

---

## Hardware Lab & Benchmark Methodology

To ensure reproducible, scientifically valid numbers without page-cache distortion, all benchmarks were executed in this dedicated test environment:

* **CPU:** AMD Ryzen 9 3950X (16 physical cores, 32 hardware threads, 64 MB L3 cache).
* **Storage:** GIGABYTE GP-ASM2NE6100TTTD 1TB PCIe 4.0 x4 NVMe SSD (Phison PS5016-E16 controller, 1GB DDR4-1600 DRAM cache, Toshiba BiCS4 96-layer 3D TLC NAND).
* **Filesystem & OS:** Linux Kernel 7.0.12-201.fc44.x86_64, ext4 filesystem mounted on `/mnt/TestData` (`/dev/nvme2n1p1`), symlinked to `/home/david/testData`.
* **Java Runtime:** OpenJDK 64-Bit Server VM 25.0.3+9 (Java 25 with Foreign Function & Memory API).
* **Benchmarking Framework:** JMH (Java Microbenchmark Harness) 1.37 with `GCProfiler` (`-prof gc`) and derived per-op metrics (`user ns/op`, `sys ns/op`, `enters/op`).

### Workload Datasets:
1. **`SINGLE_FILE` (10 GB):** A contiguous 10 GB pre-allocated binary file (`single_file_bench.bin`, 2.5 million 4KB sectors) on ext4. Eliminates ext4 directory mutex locking and inode serialization to measure raw hardware bus saturation.
2. **`MULTI_FILE` (2,211 Files):** 2,211 distinct pre-allocated binary files (~4.7 MB each, ~10.4 GB total). Tests real-world multi-file database and asset server workloads leveraging kernel descriptor tables (`io_uring_register_files`).

---

## Empirical Benchmark Results

### 1. Single-Core Efficiency & Syscall Amortization

To measure the pure framework overhead of JUring and the FFM API, we first evaluate `NopBenchmark` (which submits `IORING_OP_NOP` opcodes directly into the mapped ring without disk I/O), followed by single-thread 4KB Direct Read and Direct Write (`O_DIRECT`, `QD=64`):

| Benchmark Target | Throughput (ops/s) | Bandwidth | User CPU | Kernel CPU | Syscalls (`enters/op`) | Syscall Reduction | Heap Alloc |
|---|---|---|---|---|---|---|---|
| **`NopBenchmark.juringNop`** | **11,521,856 ops/s** | — | **11.3 ns/op** | 74.6 ns/op | **0.0313** | 32.0 ops / syscall | **0.000 B/op** |
| **`DirectRead.juringDirectRead`** | **269,591 ops/s** | **1,053 MB/s** | **53.1 ns/op** | 2,166.4 ns/op | **0.1258** | **8.0x fewer syscalls** | **0.000 B/op** |
| `DirectRead.fileChannelDirectRead` | 15,731 ops/s | 61 MB/s | 572.5 ns/op | 3,664.1 ns/op | 1.0000 | 1.0 ops / syscall | 0.000 B/op |
| **`DirectWrite.juringDirectWrite`** | **441,176 ops/s** | **1,723 MB/s** | **24.6 ns/op** | 1,953.0 ns/op | **0.0507** | **19.7x fewer syscalls**| **0.000 B/op** |
| `DirectWrite.fileChannelDirectWrite` | 71,667 ops/s | 280 MB/s | 314.8 ns/op | 4,935.7 ns/op | 1.0000 | 1.0 ops / syscall | 0.000 B/op |

> [!TIP]
> **Takeaway:** On a single CPU core, JUring processes **11.52 million ops/s** through the FFM boundary with just **11.3 ns** of user CPU time. When hitting physical NVMe storage, JUring delivers **17.1x higher read throughput** and **6.2x higher write throughput** than `FileChannel` while cutting system call transitions by 8x to 20x.

---

### 2. 4 KB Direct Read Scaling (Single 10 GB File)

We scale platform thread concurrency across 1, 2, 4, 8, and 16 threads issuing pseudo-random 4KB sector reads with `O_DIRECT` and `QD=64` per thread:

| Concurrency | Native C fio (Default) | Native C fio (Tuned: fixedbufs + batch) | JUring DirectRead (Empirical) | FileChannel (`DIRECT`) | JUring vs. Tuned fio | JUring vs. FileChannel |
|---|---|---|---|---|---|---|
| **1 Thread** | 308,387 IOPS (1,263 MB/s) | 341,230 IOPS (1,397 MB/s) | **275,463 ops/s (1,076 MB/s)** | 15,958 ops/s ( 62 MB/s) | 80.7% parity | **17.3x faster** |
| **2 Threads** | 548,175 IOPS (2,244 MB/s) | 580,200 IOPS (2,376 MB/s) | **652,162 ops/s (2,548 MB/s)** | 30,859 ops/s (121 MB/s) | **+12.4% faster** | **21.1x faster** |
| **4 Threads** | 605,000 IOPS (2,477 MB/s) | 627,346 IOPS (2,569 MB/s) | **716,882 ops/s (2,800 MB/s)** | 22,755 ops/s ( 89 MB/s) | **+14.3% faster** | **31.5x faster** |
| **8 Threads** | 620,000 IOPS (2,539 MB/s) | 635,000 IOPS (2,600 MB/s) | **715,163 ops/s (2,794 MB/s)** | 112,413 ops/s ( 439 MB/s) | **+12.6% faster** | **6.4x faster** |
| **16 Threads** | 618,000 IOPS (2,530 MB/s) | 631,000 IOPS (2,584 MB/s) | **710,444 ops/s (2,775 MB/s)** | 199,364 ops/s ( 779 MB/s) | **+12.6% faster** | **3.6x faster** |

```
Direct Read Throughput (4 Threads, 4KB Sectors, O_DIRECT):
JUring:      ████████████████████████████████ 716,882 ops/s (2,800 MB/s)
Tuned fio:   ████████████████████████████     627,346 IOPS  (2,569 MB/s)
Default fio: ███████████████████████          605,000 IOPS  (2,477 MB/s)
FileChannel: █                                 22,755 ops/s (   89 MB/s)
```

At 4 threads, JUring hits **2.80 GB/s (716,882 ops/s)**, completely saturating the PCIe 4.0 read lanes and outperforming tuned C `fio` by **+14.3%**.

---

### 3. 4 KB Direct Write Scaling & Physical SLC Dynamics

Direct random writes bypass the page cache to stream 4KB blocks directly to physical NVMe flash:

| Concurrency | Native C fio (Default) | Native C fio (Tuned: fixedbufs + batch) | JUring DirectWrite (Empirical) | FileChannel (`DIRECT`) | JUring vs. Tuned fio | JUring vs. FileChannel |
|---|---|---|---|---|---|---|
| **1 Thread** | 269,292 IOPS (1,104 MB/s) | 414,450 IOPS (1,698 MB/s) | **475,094 ops/s (1,856 MB/s)** | 72,007 ops/s ( 281 MB/s) | **+14.6% faster** | **6.6x faster** |
| **2 Threads** | 473,679 IOPS (1,939 MB/s) | 602,812 IOPS (2,468 MB/s) | **447,250 ops/s (1,747 MB/s)** | 122,446 ops/s ( 478 MB/s) | 74.2% parity | **3.7x faster** |
| **4 Threads** | 516,599 IOPS (2,115 MB/s) | 529,183 IOPS (2,065 MB/s) | **554,603 ops/s (2,166 MB/s)** | 190,782 ops/s ( 745 MB/s) | **+4.8% faster** | **2.9x faster** |
| **8 Threads** | 530,110 IOPS (2,170 MB/s) | 534,420 IOPS (2,188 MB/s) | **550,045 ops/s (2,149 MB/s)** | 436,190 ops/s (1,704 MB/s) | **+2.9% faster** | **1.3x faster** |
| **16 Threads (Fresh SLC)** | 522,400 IOPS (2,041 MB/s) | 528,900 IOPS (2,066 MB/s) | **517,245 ops/s (2,020 MB/s)** | 239,030 ops/s ( 934 MB/s) | **97.8% parity** | **2.2x faster** |
| **16 Threads (Exhausted SLC)**| 195,000 IOPS ( 760 MB/s) | 197,000 IOPS ( 770 MB/s) | **84,853 – 196,644 ops/s** | N/A | Hardware Bound | *(TLC Folding)* |

> [!NOTE]
> **Understanding the Physical SLC Cache:** Our test NVMe drive features a ~20 GB dynamic SLC write cache. During sustained sequential or scaling tests, writing over ~20 GB in under 15 seconds exhausts the high-speed SLC buffer. The SSD controller (Phison PS5016-E16) is forced to perform direct-to-die TLC folding, dropping throughput to ~760 MB/s (and under heavy write contention, down to ~330 MB/s). Both C `fio` and JUring reflect this exact hardware characteristic. Once given a 10-second idle period to flush the SLC buffer, 16-thread performance recovers to **517k–543k ops/s**.

---

### 4. Multi-File Scaling (2,211 Discrete Files)

Real-world services don't read from a single file; they serve thousands of distinct files simultaneously. We benchmarked 2,211 discrete files (~4.7 MB each) using `io_uring_register_files` (`IOSQE_FIXED_FILE`):

| Workload Configuration | Engine | 1 Thread | 2 Threads | 4 Threads | 8 Threads | 16 Threads | Notes |
|---|---|---|---|---|---|---|---|
| **Direct Read (`MULTI_FILE`)** | **JUring (`IOSQE_FIXED_FILE`)** | **306,903 ops/s** | **401,328 ops/s** | **461,854 ops/s** | **269,992 ops/s** | **473,970 ops/s** | Pre-registered file descriptors |
| Direct Read (`MULTI_FILE`) | `FileChannel` | 11,353 ops/s | 22,313 ops/s | 43,207 ops/s | 81,609 ops/s | 146,232 ops/s | Inode & fd table contention |
| Direct Read (`MULTI_FILE`) | Native C `fio` | *(Crashed)* | *(Crashed)* | *(Crashed)* | *(Crashed)* | *(Crashed)* | **`EMFILE (Too many open files)`** |
| **Direct Write (`MULTI_FILE`)** | **JUring (`IOSQE_FIXED_FILE`)** | **231,306 ops/s** | **359,145 ops/s** | **253,322 ops/s** | **404,329 ops/s** | **128,173 ops/s** | Direct NVMe DMA writes |
| Direct Write (`MULTI_FILE`) | `FileChannel` | 68,066 ops/s | 122,543 ops/s | 209,063 ops/s | 353,662 ops/s | 278,770 ops/s | Inode write mutex locks |
| Direct Write (`MULTI_FILE`) | Native C `fio` | *(Crashed)* | *(Crashed)* | *(Crashed)* | *(Crashed)* | *(Crashed)* | **`EMFILE (Too many open files)`** |

> [!IMPORTANT]
> **Why `fio` Crashed:** When given 2,211 files across multiple threads, native C `fio` attempted to maintain open file handles across standard POSIX tables, exceeding the Linux process open file limit (`RLIMIT_NOFILE`) and crashing with `EMFILE`. JUring pre-registers all 2,211 file descriptors directly into the kernel's fixed file array (`io_uring_register_files`), completely bypassing the standard file descriptor limit and eliminating kernel `fget()`/`fput()` reference counting.

---

### 5. Project Loom Virtual Threads Scaling (50 to 1,000 VTs)

Here is where JUring's architecture shines brightest. We run concurrent Project Loom Virtual Threads reading 4KB sectors directly from NVMe storage:

#### Empirical Scaling on Single Large File (`SINGLE_FILE`, 4KB Sectors, `O_DIRECT`)

| Virtual Threads | JUring (`JUringBlocking`) | FileChannel (`DIRECT`) | Throughput Comparison | JUring Syscalls (`enters/op`) | JUring Kernel CPU | FileChannel Kernel CPU |
|---|---|---|---|---|---|---|
| **50 VTs** | **326,354 ops/s (1,275 MB/s)** | 356,845 ops/s (1,394 MB/s) | 0.91x (near parity) | **1.3679** | **7.9 ns/op** | 9.9 ns/op |
| **100 VTs** | **523,445 ops/s (2,045 MB/s)** | 537,388 ops/s (2,099 MB/s) | 0.97x (parity) | **0.8137** | **16.0 ns/op** | 11.2 ns/op |
| **250 VTs** | **614,815 ops/s (2,402 MB/s)** | 577,321 ops/s (2,255 MB/s) | **+6.5% faster** | **0.3859** | **30.5 ns/op** | 28.1 ns/op |
| **500 VTs** | **616,833 ops/s (2,410 MB/s)** | 448,304 ops/s (1,751 MB/s) | **+37.6% faster** | **0.4060** | **14.9 ns/op** | 57.3 ns/op |
| **1,000 VTs** | **617,735 ops/s (2,413 MB/s)** | 256,395 ops/s (1,002 MB/s) | **+140.9% faster (2.41x)** | **0.2774** | **23.0 ns/op** | **95.1 ns/op** |

```
Throughput at 1,000 Concurrent Virtual Threads:
JUringBlocking: ████████████████████████████████ 617,735 ops/s (2,413 MB/s)  [23.0 ns kernel CPU]
FileChannel:    █████████████                    256,395 ops/s (1,002 MB/s)  [95.1 ns kernel CPU]
```

#### Empirical Scaling on Multi-File Distribution (2,211 Files, `IOSQE_FIXED_FILE`)

| Virtual Threads | JUring (`JUringBlocking`) | FileChannel (`DIRECT`) | JUring Kernel CPU (`sys ns/op`) | FileChannel Kernel CPU (`sys ns/op`) |
|---|---|---|---|---|
| **50 VTs** | **255,030 ops/s ( 996 MB/s)** | 270,062 ops/s (1,055 MB/s) | **9.5 ns/op** | 5.8 ns/op |
| **100 VTs** | **343,810 ops/s (1,343 MB/s)** | 354,436 ops/s (1,385 MB/s) | **12.3 ns/op** | 15.9 ns/op |
| **250 VTs** | **384,654 ops/s (1,503 MB/s)** | 391,388 ops/s (1,529 MB/s) | **33.0 ns/op** | 30.8 ns/op |
| **500 VTs** | **393,497 ops/s (1,537 MB/s)** | 388,387 ops/s (1,517 MB/s) | **40.9 ns/op** | 63.5 ns/op |
| **1,000 VTs** | **359,528 ops/s (1,404 MB/s)** | 393,582 ops/s (1,537 MB/s) | **30.2 ns/op** | 92.3 ns/op |

> [!TIP]
> **Why FileChannel Collapses at 1,000 VTs:**
> Standard `FileChannel` relies on Loom's ForkJoinPool carrier compensation. At 50–250 VTs, that accidental carrier expansion keeps the hardware fed. But as concurrency reaches 500 to 1,000 VTs, carrier thread saturation takes over. Kernel CPU spikes from 11 ns up to **95.1 ns/op**, causing massive context-switching overhead and cutting throughput in half (down from 577k to 256k ops/s).
>
> In contrast, `JUringBlocking` **unmounts the virtual thread** via `LockSupport.park()`. Requests are published to lock-free MPSC queues and handled by striped platform poller rings running with `IORING_SETUP_SINGLE_ISSUER` and `DEFER_TASKRUN`. Carrier threads remain completely unblocked, kernel CPU stays low (**23.0 ns/op**), and throughput holds steady at **617,735 ops/s**—a **2.41x speedup** over `FileChannel`.

---

### 6. Buffered Page-Cache I/O (DRAM Resident)

What happens when files are already cached in Linux host RAM (Page Cache)? Here we measure pure syscall context switch amortization:

| Benchmark Target | Engine | Throughput | Bandwidth | Syscalls (`enters/op`) | Syscall Reduction |
|---|---|---|---|---|---|
| **Page Cache Read (4KB, Batch=64)** | **JUring (`submitBatch=64`)** | **1,349,627 ops/s** | **5,272 MB/s** | **0.0156 enters/op** | **64.1x fewer syscalls** |
| Page Cache Read (4KB) | `FileChannel` | 1,297,206 ops/s | 5,067 MB/s | 1.0000 enters/op | Baseline (1 syscall/op) |
| **Page Cache Write (4KB, Batch=64)**| **JUring (`submitBatch=64`)** | **386,247 ops/s** | **1,509 MB/s** | **0.0615 enters/op** | **16.3x fewer syscalls** |
| Page Cache Write (4KB) | `FileChannel` | 513,734 ops/s | 2,007 MB/s | 1.0000 enters/op | Direct memory copy (`memcpy`) |

> [!NOTE]
> For page-cache resident writes, synchronous `FileChannel.write()` is slightly faster (514k vs 386k ops/s) because Linux's `pwrite` is an immediate in-memory copy into page buffers. In contrast, `io_uring` introduces SQE/CQE ring coordination overhead that truly pays off when I/O touches physical storage or operates under high concurrency.

---

### 7. Physical NAND Flash Cell Latency (QD=1)

To confirm we are genuinely measuring physical hardware and eliminate batch-smoothing artifacts, we ran unbatched single-operation read sampling (`Mode.SampleTime`, `OperationsPerInvocation(1)`):

| Latency Metric | JUring (`O_DIRECT`, QD=1) | FileChannel (`DIRECT`, QD=1) | Physical Hardware Reality |
|---|---|---|---|
| **Mean Latency** | **87.51 µs** | 87.71 µs | Governed by physical 3D TLC NAND cell charge sensing (~80 µs). |
| **p50 (Median)** | **83.07 µs** | 83.33 µs | Exact round-trip PCIe 4.0 packet traversal + controller arbitration. |
| **p90** | **98.18 µs** | 98.30 µs | Minor internal SSD controller command queue arbitration. |
| **p99** | **100.22 µs** | 100.48 µs | Predictable sub-101 µs tail latency. |
| **p99.9** | **107.01 µs** | 104.56 µs | Flash block garbage collection boundaries. |
| **p100 (Max)** | **175.87 µs** | **125.57 µs** | Unbatched max latency reflects single-run OS scheduling jitter. |

> [!NOTE]
> If these reads were hitting the Linux Page Cache (RAM), latency would be **< 1.0 µs**. Measuring an 83 µs median round trip proves 100% genuine bus-master DMA transfers from physical BiCS4 3D TLC flash cells over the PCIe bus.

---

### 8. Pure Project Panama (FFM API) Downcall Overhead

How much overhead does Project Panama's Foreign Function & Memory (FFM) API actually introduce compared to JNI or native C calls? We benchmarked native symbol downcall invocations across call patterns using [`FfmInvocationBenchmark`](src/test/java/bench/suite/FfmInvocationBenchmark.java):

| Invocation Strategy | Call Mechanism | Latency (`avgt`) | Safepoint Handling | JIT Optimization |
|---|---|---|---|---|
| **`staticFinal_critical_invokeExact`** | `Linker.Option.critical(true)` | **2.67 ns/op** | Skips safepoint polls | Constant-folded leaf downcall intrinsic |
| **`methodHandleProxies_critical`** | Interface Proxy over Critical Handle | **4.89 ns/op** | Skips safepoint polls | Clean Java interface invocation |
| **`record_staticFinal_proxy`** | Static Final Record Interface | **6.07 ns/op** | Standard safepoint polls | Record field constant-folded |
| **`staticFinal_invokeExact`** | `MethodHandle.invokeExact` | **6.15 ns/op** | Standard safepoint polls | Exact signature constant-folding |
| **`staticFinal_invoke`** | `MethodHandle.invoke` | **6.13 ns/op** | Standard safepoint polls | Type adaptation overhead |
| `instanceField_invokeExact` | Instance Field Handle | 8.00 ns/op | Standard safepoint polls | Cannot constant-fold target address |
| `instanceField_extractedFromRecord` | Extracted Interface Proxy | 8.36 ns/op | Standard safepoint polls | Monomorphic interface call |
| `methodHandleProxies_standard` | Standard `MethodHandleProxies` | 8.44 ns/op | Standard safepoint polls | Default interface proxy stub |
| `record_instance_proxy` | Instance Record Proxy | 8.69 ns/op | Standard safepoint polls | Unfolded interface dispatch |

```
FfmInvocationBenchmark Execution:
staticFinal_critical_invokeExact:   ██ 2.67 ns/op   [Skips safepoint check]
methodHandleProxies_critical:       ████ 4.89 ns/op
staticFinal_invokeExact:            ██████ 6.15 ns/op   [Standard FFM Downcall]
methodHandleProxies_standard:       ████████ 8.44 ns/op [Interface Proxy]
```

> [!TIP]
> **Key Panama Takeaway:** Marking leaf foreign functions with `Linker.Option.critical(true)` allows HotSpot C2 to generate a direct native branch instruction without transitioning thread states or polling JVM safepoints. At **2.67 ns per downcall**, Java invokes native Linux kernel symbols faster than traditional JNI while preserving complete type safety. For the full deep-dive on these mechanics, see my blog post: [Critical Foreign Methods in Java Project Panama](https://davidvlijmincx.com/posts/critical-foreign-methods/).

---

## Code Examples: Using JUring in Your Application

Let's look at how you can use JUring in practice.

### 1. High-Throughput Direct Read (Registered Files & Fixed Buffers)

This is the fastest read path: uses Linux `O_DIRECT`, pre-registers the file descriptor into the kernel table (`IOSQE_FIXED_FILE`), and uses fixed DMA buffers (`IORING_OP_READ_FIXED`):

```java
import com.davidvlijmincx.lio.api.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import static com.davidvlijmincx.lio.api.IoUringOptions.*;

public class DirectReadExample {
    public static void main(String[] args) {
        int queueDepth = 64;
        int bufferSize = 4096; // 4 KB sector size

        // 1. Initialize JUring with single-issuer and deferred taskrun for zero lock contention
        try (JUring ring = new JUring(queueDepth * 2,
                IORING_SETUP_SINGLE_ISSUER,
                IORING_SETUP_COOP_TASKRUN,
                IORING_SETUP_DEFER_TASKRUN);
             FileDescriptor fd = new FileDescriptor(Path.of("/mnt/data/large_dataset.bin"), LinuxOpenOptions.READ_DIRECT)) {

            // 2. Pre-register the file descriptor into the kernel's fixed file table
            ring.registerFiles(fd);

            // 3. Pre-register DMA buffers with kernel (pinned physical memory)
            MemorySegment[] buffers = ring.registerBuffers(bufferSize, queueDepth);

            // 4. Submit asynchronous direct read requests
            for (int i = 0; i < queueDepth; i++) {
                int bufferIndex = ring.checkOutBuffer();
                long fileOffset = (long) i * bufferSize;

                // Index 0 references the first registered file descriptor (IOSQE_FIXED_FILE)
                ring.prepareReadFixed(0, bufferSize, fileOffset, bufferIndex);
            }

            // 5. Submit all prepared requests and wait for completions with a single syscall
            ring.submitAndWaitForBatchResult(queueDepth, queueDepth, result -> {
                long bytesRead = result.result();
                int bufIdx = result.bufferIdx();
                MemorySegment data = result.validBuffer();

                System.out.printf("Read %d bytes into buffer #%d%n", bytesRead, bufIdx);

                // Return buffer to the pool for reuse
                ring.checkInBuffer(bufIdx);
            });
        }
    }
}
```

---

### 2. High-Throughput Direct Write

Directly stream data to NVMe flash bypassing host page cache:

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
             FileDescriptor fd = new FileDescriptor(Path.of("/mnt/data/output.bin"), LinuxOpenOptions.WRITE_DIRECT)) {

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

This loop structure achieves the **0.05 – 0.12 enters/op** syscall efficiency demonstrated in our benchmarks:

```java
import com.davidvlijmincx.lio.api.*;

public class FusedPipelineLoop {
    public static void runPipeline(JUring ring, int totalTasks, int qd) {
        final int submitBatch = 32;
        final int waitBatch = 8;
        
        int submitted = 0;
        int unsubmitted = 0;
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
                unsubmitted++;

                // Mid-pipeline flush while NVMe is working
                if (unsubmitted >= submitBatch) {
                    ring.submit();
                    unsubmitted = 0;
                    ring.peekForBatchResult(qd, consumer);
                }
            }

            // Non-blocking drain from user space
            int reaped = ring.peekForBatchResult(qd, consumer);

            // Single fused io_uring_enter syscall: submits unsubmitted SQEs AND waits for completions
            if (reaped == 0 && reapedCount[0] < totalTasks) {
                int remaining = totalTasks - reapedCount[0];
                int inFlight = submitted - reapedCount[0];
                if (inFlight > 0) {
                    int minWait = Math.max(1, Math.min(waitBatch, Math.min(remaining, inFlight)));
                    ring.submitAndWaitForBatchResult(minWait, qd, consumer);
                    unsubmitted = 0;
                }
            }
        }
    }
}
```

---

### 4. Buffered I/O with ByteBuffers / MemorySegments

For standard Linux Page Cache resident I/O without requiring sector alignment:

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

            Result res = ring.waitForResult();
            System.out.println("Read " + res.result() + " bytes: " + res.getString());
        }

        // Asynchronous buffered write with off-heap memory
        try (JUring ring = new JUring(128);
             FileDescriptor fd = new FileDescriptor(Path.of("output.txt"), LinuxOpenOptions.WRITE);
             Arena arena = Arena.ofConfined()) {

            MemorySegment buffer = arena.allocateFrom("Hello from JUring io_uring!", StandardCharsets.UTF_8);

            ring.prepareWrite(fd, buffer, 0);
            ring.submit();

            Result res = ring.waitForResult();
            System.out.println("Wrote " + res.result() + " bytes successfully.");
        }
    }
}
```

---

### 5. Non-Blocking Virtual Thread Blocking API (`JUringBlocking`)

This solves the Virtual Thread pinning problem in one clean API:

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

                    // Unmounts the Virtual Thread cleanly via LockSupport.park()
                    // The underlying OS carrier thread is immediately released!
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

To get C-level throughput in pure Java, JUring leverages several Linux kernel features and Java FFM internals:

1. **`IORING_SETUP_DEFER_TASKRUN` + `COOP_TASKRUN`:**
   In [`JUring.java`](src/main/java/com/davidvlijmincx/lio/api/JUring.java). Confines submission and completion processing strictly to the calling thread. The Linux kernel never acquires cross-core spinlocks or triggers inter-processor interrupts (IPIs).
2. **`IORING_REGISTER_RING_FDS` (`IORING_ENTER_REGISTERED_RING`):**
   In [`IoUringDispatcher.java`](src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java), JUring registers the ring file descriptor itself with the kernel. This eliminates kernel `fget()`/`fput()` file descriptor table lookups on every single enter syscall.
3. **`IOSQE_FIXED_FILE` Pre-Registered Descriptor Tables:**
   Data files are registered directly into the kernel's internal descriptor table (`io_uring_register_files`), preventing file table mutex locks. This is why JUring easily handles **2,211 files** where native C `fio` crashes with `EMFILE`.
4. **Fused Submission & Completion Syscalls:**
   `ring.submitAndWaitForBatchResult(minWait, qd, consumer)` executes SQE submission and event reaping in a single `io_uring_enter` syscall, cutting syscall frequency down to **0.05 – 0.12 enters/op**.
5. **Zero-Allocation Hot Path:**
   Off-heap submission queue entries (SQEs) and completion queue entries (CQEs) are accessed directly through Java primitive `VarHandle` memory segment operations. Zero Java objects are allocated during active I/O.
6. **Project Loom Striped Poller Ring Architecture:**
   In [`JUringBlocking.java`](src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java), virtual threads unmount via `LockSupport.park()` during I/O and push requests into lock-free Vyukov MPSC queues. Requests are hash-striped across dedicated platform poller threads (`JUringBlocking-Poller-*`) to maintain strict Linux `IORING_SETUP_SINGLE_ISSUER` kernel affinity without carrier pool starvation.

---

## How to Run the Benchmarks Yourself

JUring includes [`bench.sh`](bench.sh) to execute the benchmark suite with automatic classpath resolution, CPU pinning, and profiler attachments:

```shell
# 1. Quick smoke run of DirectRead pinned to CPU core 4:
./bench.sh --pin 4 --include "DirectReadBenchmark.juringDirectRead" --param qd=64 --quick

# 2. Direct Write benchmark across both SINGLE_FILE and MULTI_FILE distributions:
./bench.sh --pin 4 --include "DirectWriteBenchmark.juringDirectWrite" --quick

# 3. Multi-Thread Scaling Suite across 1, 2, 4, 8, 16 threads (Single File):
./bench.sh --threads-scaling --quick --param distribution=SINGLE_FILE

# 4. Multi-Thread Scaling Suite across 2,211 discrete files:
./bench.sh --threads-scaling --quick --param distribution=MULTI_FILE

# 5. Measure pure Foreign Function & Memory (FFM) dispatch overhead:
./bench.sh --pin 4 --include "NopBenchmark" --quick

# 6. Verify zero-allocation heap behavior (-prof gc) and hardware counters:
./bench.sh --pin 4 --include "DirectReadBenchmark.juringDirectRead" --param qd=64 --gc --perfnorm

# 7. Project Loom Virtual Threads scaling sweep (50 to 1,000 Virtual Threads):
./bench.sh --virtual --quick

# 8. Unbatched physical NAND flash cell latency distribution (P50, P90, P99, P99.9):
./bench.sh --latency --quick

# 9. Buffered / Page-Cache resident benchmark:
./bench.sh --buffered --quick

# 10. Measure Project Panama downcall dispatch overhead (standard vs critical vs proxies):
./bench.sh --include "FfmInvocationBenchmark" --quick
```

---

## Requirements & Setup

* **Operating System:** Linux Kernel **5.1 or newer** (Kernel 6.x / 7.x recommended for `DEFER_TASKRUN` and registered ring FDs).
* **Java Virtual Machine:** OpenJDK **22 or higher** (Tested on OpenJDK 25 with Foreign Function & Memory API).
* **Dependencies:** None. JUring directly talks to the Linux kernel via FFM downcalls—**no `liburing.so` C library required**.
* **Permissions / Memory Limits:** For multi-threaded buffer registration, set locked memory limit:
  ```bash
  ulimit -l unlimited
  ```

---

## Further Reading & Connect

If you want to dive deeper into Java concurrency, Project Panama, and high-performance JVM systems:

* 📖 **[Async I/O with Java & Project Panama: Unlocking Linux io_uring for Virtual Threads](https://davidvlijmincx.com/posts/async-io-with-java-and-panama/)**
* 📖 **[The Hidden Cost of Java Virtual Threads: Deep Stacks, GC Pressure & Cache Locality](https://davidvlijmincx.com/posts/virtual-threads-hidden-costs/)**
* 📖 **[Critical Foreign Methods in Java Project Panama (Linker.Option.critical)](https://davidvlijmincx.com/posts/critical-foreign-methods/)**
* 📖 **[Managing Native Memory in Java (FFM API): Arenas, Malloc & Custom Memory Pools](https://davidvlijmincx.com/posts/java-native-memory-allocation-ffm-api/)**
* 📖 **[Thread-Safe Native Memory in Java: VarHandle Access Modes with JCStress](https://davidvlijmincx.com/posts/java-native-memory-access-modes/)**
* 📖 **[Benchmarking & Profiling Java Code with JMH and async-profiler](https://davidvlijmincx.com/posts/benchmark-and-profile-java/)**
* 🌐 **[davidvlijmincx.com](https://davidvlijmincx.com)** — Read all 114+ articles on Java performance and concurrency.
* 💬 **[Connect on LinkedIn](https://www.linkedin.com/in/david-vlijmincx)** — Follow my weekly JVM engineering research.
* 🎤 **[Conference Talks & Workshops](https://davidvlijmincx.com/talks/)** — Watch my talks from Devoxx, P99 CONF, and JFokus.

---

## License

JUring is licensed under the [Apache License, Version 2.0](LICENSE).
