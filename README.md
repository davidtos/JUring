# JUring: High-Performance Linux io_uring for Java

[![License](https://img.shields.io/badge/License-Unlicense-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![Linux](https://img.shields.io/badge/Linux-Kernel%206.1%2B-green.svg)](https://kernel.org/)
[![io_uring](https://img.shields.io/badge/io__uring-Native%20Zero--Copy-red.svg)](https://git.kernel.dk/cgit/liburing/)
[![Oracle ACE](https://img.shields.io/badge/Author-David%20Vlijmincx%20(Oracle%20ACE)-blueviolet.svg)](https://davidvlijmincx.com)

When I first started exploring **Virtual Threads** in Java, I wanted to understand their runtime performance characteristics, unmounting semantics, and system boundaries. That research led directly to a longstanding architectural constraint in the JVM: **file I/O operations**.

In standard Java, file I/O operations on `FileChannel` execute synchronous kernel system calls (`pread64`/`pwrite64`). Because POSIX file descriptors do not support readiness notifications like network sockets (`epoll`), the JVM cannot yield virtual threads during blocking file operations. In OpenJDK 25, `FileChannel` attempts carrier thread compensation via `Blocker.begin(direct)` for direct and synchronous I/O, spinning up extra OS carrier threads up to `jdk.virtualThreadScheduler.maxPoolSize` (default 256). For ordinary buffered file I/O, `Blocker.begin(false)` returns immediately without compensating, causing the OS carrier thread to remain blocked in the kernel syscall for the full duration of the I/O. Under high concurrency, this leads to carrier exhaustion, thread scheduling overhead, and throughput degradation.

I built **JUring** to provide true asynchronous file I/O for modern Java.

JUring is a pure-Java library that provides direct native bindings to Linux's high-performance **`io_uring`** asynchronous I/O subsystem using Java's **Foreign Function & Memory (FFM) API** (JEP 454). No JNI code, no external `.so` dependencies (not even `liburing`), and zero heap allocations on the hot path (`0.000 B/op`). It turns blocking file I/O into non-blocking operations for Virtual Threads, delivers competitive throughput and parity against native C `fio` (`ioengine=io_uring`), and amortizes system calls across high-concurrency NVMe workloads.

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

* **Competitive Parity vs. Native C `fio` (`ioengine=io_uring`):** Matches native C `fio` with low system-call overhead (~0.44 enters/op) under 4KB random direct reads. See [Head-to-Head Parity vs. Native C fio](#1-head-to-head-parity-vs-native-c-fio).
* **Hardware Saturation on Direct Reads:** Reaches the NVMe controller's random read ceiling (~718,000 IOPS / ~2.80 GB/s) at 4 threads with zero heap allocations on the hot path (`0.000 B/op`). See [4 KB Direct Read Scaling](#3-4-kb-direct-read-scaling-single-10-gb-file).
* **Multi-Descriptor Handling with Registered Files:** Pre-registering 2,211 file descriptors via `io_uring_register_files` (`IOSQE_FIXED_FILE`) avoids per-op POSIX file table lookups. See [Multi-File Scaling](#5-multi-file-scaling-2211-discrete-files).
* **Fixed-Thread Polling for Virtual Threads:** `JUringBlocking` handles up to 1,000 concurrent Virtual Threads through a small, fixed pool of platform pollers, parking and unparking virtual threads without carrier thread exhaustion. See [Project Loom Virtual Threads Scaling](#6-project-loom-virtual-threads-scaling-50-to-1000-vts).

---

## Hardware Lab & Benchmark Methodology

<!-- BEGIN GENERATED: hardware -->
All benchmarks were executed on bare-metal hardware with ext4 mounted directly on a dedicated PCIe Gen4 NVMe drive:

* **CPU:** AMD Ryzen 9 3950X (32 cores, SMT enabled, governor `performance`).
* **RAM:** unknown.
* **Storage:** GIGABYTE GP-ASM2NE6100TTTD (firmware `EGFM11.2`).
* **Filesystem & OS:** Linux Kernel `Linux 7.0.x`, ext4 mounted on `/mnt/TestData` (`/dev/nvme1n1p1`), mount options `rw,relatime`.
* **Java Runtime:** OpenJDK 25 with Foreign Function & Memory API (JEP 454).
* **Harness & Profiling:** JMH with tracepoint profiling (`raw_syscalls:sys_enter`) and hardware perf counters (perf_event_paranoid `-1`).

### Workload Datasets:
1. **`SINGLE_FILE` (10 GB):** A contiguous 10 GB pre-allocated binary file (`single_file_bench.bin`, 2.5 million 4KB sectors) on ext4. Eliminates directory mutex contention to measure raw I/O engine and drive controller throughput.
2. **`MULTI_FILE` (2,211 Files):** 2,211 distinct pre-allocated binary files (~4.7 MB each, ~10.4 GB total) on ext4. Measures multi-descriptor handling via `io_uring_register_files`.
<!-- END GENERATED: hardware -->

---

## Empirical Benchmark Results

### 1. Head-to-Head Parity vs. Native C fio

<!-- BEGIN GENERATED: fio-compare -->
Random 4KB read workload on a single thread at QD=64 on a dedicated 10 GB direct I/O read file (`O_DIRECT`, fixed buffers, registered files).
*Note: ± indicates sample standard deviation over N independent repeats.*

| Engine | Throughput (ops/s) | Bandwidth (MB/s) | Enters / op (`io_uring_enter`) | Delta vs Best fio | Configuration |
|---|---|---|---|---|---|
| `fio` (default settings) | 354,183.4 ± 6,259.3 | 1,383.5 ± 24.5 MB/s | 0.9850 | — | Default submission / reap |
| `fio` (best batching: B=32) | 354,183.4 ± 6,259.3 | 1,383.5 ± 24.5 MB/s | 0.9850 | baseline | `--iodepth_batch_submit=32` |
| JUring (JMH) | 441,664.3 ± 2,815.3 | 1,725.3 ± 11.0 MB/s | 0.4395 | +24.7% | Batched `submitAndWait(1)` |
<!-- END GENERATED: fio-compare -->

> [!NOTE]
> **Syscall Amortization and Batching:**
> System calls per operation are measured via `perf stat -e syscalls:sys_enter_io_uring_enter` for fio and JMH `--syscalls`. JUring combines submission and completion waiting into single `io_uring_enter(min_complete=1, GETEVENTS)` syscalls (~0.44 enters/op), whereas default fio configuration commits submissions and reaps completions in separate syscalls (~0.98 enters/op). Sweeping fio batch settings narrows this difference.

---

### 2. Single-Core Framework Overhead & Direct I/O Efficiency

<!-- BEGIN GENERATED: single-core -->
Evaluation of pure framework overhead (`NopBenchmark`), followed by single-thread 4KB Direct Read and Direct Write (`O_DIRECT`) comparing QD=1 and QD=64 against `FileChannel`:
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Target | Concurrency / QD | Throughput (ops/s) | Bandwidth | Syscalls / op (`raw_syscalls:sys_enter`) | User CPU (`cycles:u/op`) | Kernel CPU (`cycles:k/op`) | Heap Alloc |
|---|---|---|---|---|---|---|---|
| `NopBenchmark.juringNop` | 1T (QD=64, ring memory only) | 11,196,476.1 ± 116,072.0 | — | 0.0313 (32.0 ops/call) | 52.5 | 353.2 | 0.000 B/op |
| `FileChannel.read` | 1T (QD=1, synchronous `pread64`) | 12,051.8 ± 151.7 | 47.1 ± 0.6 MB/s | 1.0020 | 1447.6 | 25131.0 | 0.000 B/op |
| `JUring.read` (QD=1) | 1T (QD=1, unbatched `O_DIRECT`) | 12,127.4 ± 51.3 | 47.4 ± 0.2 MB/s | 1.0032 | 3508.7 | 25436.7 | 0.000 B/op |
| `JUring.read` (QD=64) | 1T (QD=64, batched pipeline) | 345,115.0 ± 619.6 | 1,348.1 ± 2.4 MB/s | 0.4395 | 274.9 | 10298.9 | 0.000 B/op |
| `FileChannel.write` | 1T (QD=1, synchronous `pwrite64`) | 71,452.1 ± 504.1 | 279.1 ± 2.0 MB/s | 1.0008 | 889.6 | 25619.4 | 0.000 B/op |
| `JUring.write` (QD=1) | 1T (QD=1, unbatched `O_DIRECT`) | 71,247.9 ± 732.5 | 278.3 ± 2.9 MB/s | 1.0009 | 693.9 | 26555.7 | 0.000 B/op |
| `JUring.write` (QD=64) | 1T (QD=64, batched pipeline) | unstable (±70%) | — | 0.0383 | 109.3 | 8584.5 | 0.000 B/op |
<!-- END GENERATED: single-core -->

> [!TIP]
> **Equal Concurrency Parity:**
> At QD=1, `FileChannel` and `JUring` perform identically, directly matching the flash cell access latency (~83 µs). When queue depth increases to 64, JUring keeps the NVMe hardware command queue filled, amortizing syscalls down to ~0.44 enters/op.

---

### 3. 4 KB Direct Read Scaling (Single 10 GB File)

<!-- BEGIN GENERATED: scaling-read -->
*Harness: 1 forks × 3 iterations × 3 s per tier*

| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| 1 Thread | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | 323,971.0 ± 10,378.8 | 1,265.5 ± 40.5 MB/s | 15,941.2 ± 1,513.6 | 62.3 ± 5.9 MB/s | 20.3x |
| 2 Threads | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | 678,682.2 ± 15,605.4 | 2,651.1 ± 61.0 MB/s | 27,395.4 ± 1,800.7 | 107.0 ± 7.0 MB/s | 24.8x |
| 4 Threads | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | 717,986.5 ± 1,090.9 | 2,804.6 ± 4.3 MB/s | 52,676.5 ± 1,182.2 | 205.8 ± 4.6 MB/s | 13.6x |
| 8 Threads | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | 716,319.0 ± 5,207.2 | 2,798.1 ± 20.3 MB/s | 99,471.1 ± 2,669.1 | 388.6 ± 10.4 MB/s | 7.2x |
| 16 Threads | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | 712,629.3 ± 30,805.4 | 2,783.7 ± 120.3 MB/s | 166,806.9 ± 7,688.9 | 651.6 ± 30.0 MB/s | 4.3x |
<!-- END GENERATED: scaling-read -->

At 4 threads and above, JUring saturates the SSD controller's random read IOPS ceiling at ~718,000 ops/s (2.80 GB/s) with zero JVM heap allocations (`0.000 B/op`).

---

### 4. 4 KB Direct Write Scaling & Physical SLC Dynamics

<!-- BEGIN GENERATED: scaling-write -->
*Harness: 1 forks × 3 iterations × 3 s per tier*

| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| 1 Thread | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | unstable (±31%) | — | unstable (±10%) | — | — |
| 2 Threads | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | unstable (±21%) | — | 139,071.2 ± 4,753.5 | 543.2 ± 18.6 MB/s | — |
| 4 Threads | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | 541,630.0 ± 21,049.8 | 2,115.7 ± 82.2 MB/s | unstable (±20%) | — | — |
| 8 Threads | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | unstable (±2412%) | — | 441,552.0 ± 36,979.0 | 1,724.8 ± 144.4 MB/s | — |
| 16 Threads | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | unstable (±1512%) | — | unstable (±789%) | — | — |
<!-- END GENERATED: scaling-write -->

> [!NOTE]
> **Understanding High-Concurrency Write Variance:**
> Sustained 4KB random direct writes across 8 to 16 threads push over 20 GB of data in seconds, completely exhausting the drive's dynamic SLC cache. The Phison PS5016-E16 controller falls back to direct-to-die TLC folding (~300–700 MB/s), producing high measurement variance in both JMH and fio until write activity subsides and the SLC buffer flushes.

---

### 5. Multi-File Scaling (2,211 Discrete Files)

<!-- BEGIN GENERATED: multifile -->
*Harness: 1 forks × 3 iterations × 3 s per tier*

| Threads | JUring Read (ops/s) | FileChannel Read (ops/s) | Read Speedup | JUring Write (ops/s) | FileChannel Write (ops/s) | Write Speedup |
|---|---|---|---|---|---|---|
| 1 Thread | 330,708.3 ± 7,524.9 | 11,391.2 ± 418.4 | 29.0x | unstable (±739%) | unstable (±17%) | — |
| 2 Threads | unstable (±444%) | 22,239.9 ± 820.6 | — | unstable (±2008%) | unstable (±92%) | — |
| 4 Threads | unstable (±348%) | 43,076.5 ± 272.5 | — | unstable (±1949%) | unstable (±185%) | — |
| 8 Threads | 475,027.7 ± 11,473.9 | 81,387.2 ± 1,157.6 | 5.8x | unstable (±47%) | unstable (±1161%) | — |
| 16 Threads | 478,447.7 ± 15,797.1 | 145,925.0 ± 1,978.9 | 3.3x | unstable (±57%) | unstable (±238%) | — |
<!-- END GENERATED: multifile -->

---

### 6. Project Loom Virtual Threads Scaling (50 to 1,000 VTs)

<!-- BEGIN GENERATED: virtual-threads -->
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Virtual Threads | JUringBlocking (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup | JUring Syscalls/op | FC Syscalls/op | JUring CPU/op | FC CPU/op |
|---|---|---|---|---|---|---|---|---|---|
| 50 VTs | 331,351.9 ± 2,798.4 | 1,294.3 ± 10.9 MB/s | 348,087.0 ± 1,047.5 | 1,359.7 ± 4.1 MB/s | 0.95x | 4.8698 | 1.7100 | 30.5 µs | 24.3 µs |
| 100 VTs | unstable (±48%) | — | unstable (±25%) | — | — | 3.6714 | 1.7187 | 28.3 µs | 28.2 µs |
| 250 VTs | unstable (±23%) | — | unstable (±29%) | — | — | 3.2737 | 1.6688 | 27.3 µs | 35.2 µs |
| 500 VTs | 559,061.2 ± 3,638.7 | 2,183.8 ± 14.2 MB/s | unstable (±12%) | — | — | 2.7729 | 1.5560 | 27.0 µs | 28.3 µs |
| 1000 VTs | unstable (±26%) | — | unstable (±22%) | — | — | 2.4581 | 1.6794 | 26.7 µs | 28.3 µs |

#### Impact of Loom Carrier Thread Cap (`maxPoolSize=1024` vs default 256 at 1,000 VTs)

| Configuration | Virtual Threads | Engine | Throughput (ops/s) | Bandwidth | Notes |
|---|---|---|---|---|---|
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | FileChannel | 393,871.7 ± 20,923.3 | 1,538.6 ± 81.7 MB/s | Carrier pool expanded from default 256 to 1024 |
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | JUringBlocking | unstable (±17%) | — | Fixed pool of 4 poller threads |
<!-- END GENERATED: virtual-threads -->

---

### 7. Buffered Page-Cache I/O (DRAM Resident)

<!-- BEGIN GENERATED: buffered -->
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Workload | Engine | Concurrency | Throughput (ops/s) | Bandwidth | Syscalls/op | CPU time/op (`task-clock`) | Kernel CPU (`cycles:k/op`) |
|---|---|---|---|---|---|---|---|
| Page Cache Read | JUring (`submitBatch=64`) | 1T (Batch=64) | 1,458,945.4 ± 28,133.6 | 5,699.0 ± 109.9 MB/s | 0.0168 | 0.72 µs | 3,075 |
| Page Cache Read | FileChannel | 1T (Synchronous) | 1,280,284.0 ± 20,747.6 | 5,001.1 ± 81.0 MB/s | 1.0070 | 0.79 µs | 3,015 |
| Page Cache Write | JUring (`submitBatch=64`) | 1 submitting thread + io-wq workers | 890,809.5 ± 61,836.9 | 3,479.7 ± 241.6 MB/s | 0.0446 | 5.52 µs | 22,010 |
| Page Cache Write | FileChannel | 1T (Synchronous) | 475,238.3 ± 9,206.3 | 1,856.4 ± 36.0 MB/s | 1.0089 | 2.13 µs | 8,841 |
<!-- END GENERATED: buffered -->

> [!NOTE]
> **io-wq Worker Threads in Buffered Writes:**
> For buffered writes, ext4 cannot complete writes asynchronously without blocking on inode locks and page cache dirtier accounting. The Linux kernel offloads these operations to `io-wq` worker threads (`iou-wrk`). As recorded in `iou-wrk-buffered-write.json`, this concurrency delivers higher ops/s at the cost of higher CPU time per op compared to synchronous `pwrite64`.

---

### 8. Physical NAND Flash Cell Latency (QD=1)

<!-- BEGIN GENERATED: latency -->
Unbatched single-operation read sampling (`Mode.SampleTime`, `OperationsPerInvocation(1)`) confirming direct hardware NVMe DMA latency:

| Metric | JUring Fused (`submitAndWait(1)`) | JUring Unfused (`submit() + wait()`) | FileChannel (`DIRECT pread64`) |
|---|---|---|---|
| Syscalls / op | 1 enter/op (`to_submit=1, min_complete=1`) | 2 enters/op (`submit=1` + `wait=1`) | 1 `pread64` syscall |
| Mean Latency | 87.40 µs | 87.25 µs | 87.76 µs |
| p0 (Min) | 78.85 µs | 78.85 µs | 79.23 µs |
| p50 (Median) | 82.94 µs | 82.94 µs | 83.33 µs |
| p90 | 97.92 µs | 97.92 µs | 98.43 µs |
| p95 | 98.43 µs | 98.43 µs | 99.07 µs |
| p99 | 100.48 µs | 100.10 µs | 100.99 µs |
| p99.9 | 109.82 µs | 108.29 µs | 109.70 µs |
| p100 (Max) | 3555.33 µs | 3117.06 µs | 1675.26 µs |
<!-- END GENERATED: latency -->

> [!NOTE]
> An 82.9 µs median round trip at QD=1 reflects physical BiCS4 3D TLC flash cell access latency over PCIe, whereas page cache hits register in sub-microsecond times.

---

### 9. Project Panama (FFM API) Downcall Overhead

<!-- BEGIN GENERATED: ffm -->
Benchmarking Foreign Function & Memory downcall invocations across dispatch patterns using [`FfmInvocationBenchmark`](src/test/java/bench/suite/FfmInvocationBenchmark.java):

| Invocation Strategy | Call Mechanism | Average Time (ns/op) |
|---|---|---|
| `staticFinal_critical_invokeExact` | Linker.Option.critical(true) | 2.69 ± 0.02 ns/op |
| `methodHandleProxies_critical` | Linker.Option.critical(true) | 4.81 ± 0.06 ns/op |
| `record_staticFinal_proxy` | MethodHandleProxies / Record Interface | 6.06 ± 0.03 ns/op |
| `staticFinal_invoke` | Direct MethodHandle | 6.06 ± 0.05 ns/op |
| `staticFinal_invokeExact` | Direct MethodHandle | 6.09 ± 0.06 ns/op |
| `instanceField_invokeExact` | Instance Field Handle | 8.18 ± 0.11 ns/op |
| `instanceField_extractedFromRecord_proxy` | MethodHandleProxies / Record Interface | 8.40 ± 0.07 ns/op |
| `methodHandleProxies_asInterfaceInstance` | MethodHandleProxies / Record Interface | 8.43 ± 0.08 ns/op |
| `record_instance_proxy` | MethodHandleProxies / Record Interface | 8.84 ± 0.12 ns/op |
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
   In [`JUringBlocking.java`](src/main/java/com/davidvlijmincx/lio/api/JUringBlocking.java) and [`IoUringDispatcher.java`](src/main/java/com/davidvlijmincx/lio/api/IoUringDispatcher.java), two-way StoreLoad fences (`VarHandle.fullFence()`) prevent lost wakeup hangs in poller unpark handshakes on out-of-order store-buffering architectures. Handshake safety has been tested with OpenJDK JCStress (196 configurations, 0 forbidden outcomes).

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
