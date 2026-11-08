## Hardware Setup & Methodology
Hardware has a big impact on the performance of the I/O benchmarks. For full transparency, all benchmarks were run on this machine:

* **CPU:** 16 cores, 32 threads (SMT)
* **RAM:** 64 GB
* **Storage:** GIGABYTE GP-ASM2NE6100TTTD (1TB PCIe NVMe)
* **Filesystem & OS:** Linux Kernel `7.0.12-201.fc44.x86_64`, ext4, mount options `rw,nosuid,nodev,relatime,seclabel`
* **Java Runtime:** OpenJDK version "25.0.3"
* **Harness & Profiling:** JMH with `perf` and kernel `raw_syscalls:sys_enter` tracepoints

### Workload Datasets:
1. **`SINGLE_FILE` (10 GB):** A single 10 GB binary file.
2. **`MULTI_FILE` (2,211 Files):** 2,211 files (~4.7 MB each, ~10.4 GB total).

The idea behind this setup is to get a clear picture of how JUring performs under different workloads, both with a single large file and across thousands of open files. The big file is also there to match fio's workload.

### Single-Core Framework Overhead & Direct I/O Efficiency
The baseline framework overhead (`NopBenchmark`), followed by single-thread 4KB Direct Reads and Direct Writes (`O_DIRECT`). We compare both QD=1 and QD=64 against standard Java `FileChannel`.
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Target                   | Concurrency / QD                  | Throughput (ops/s)      | Bandwidth          | Syscalls / op (`raw_syscalls:sys_enter`) | User CPU (`cycles:u/op`) | Kernel CPU (`cycles:k/op`) | Heap Alloc |
|--------------------------|-----------------------------------|-------------------------|--------------------|------------------------------------------|--------------------------|----------------------------|------------|
| `NopBenchmark.juringNop` | 1T (QD=64, ring memory only)      | 11,590,187.4 ± 53,591.1 | —                  | 0.0313 (32.0 ops/call)                   | 52.7                     | 351.1                      | 0.006 B/op |
| `FileChannel.read`       | 1T (QD=1, synchronous `pread64`)  | 11,101.7 ± 979.7        | 43.4 ± 3.8 MB/s    | 1.0023                                   | 1370.2                   | 24409.8                    | 0.209 B/op |
| `JUring.read` (QD=1)     | 1T (QD=1, unbatched `O_DIRECT`)   | 11,479.8 ± 34.1         | 44.8 ± 0.1 MB/s    | 1.0046                                   | 3990.5                   | 25004.6                    | 6.451 B/op |
| `JUring.read` (QD=64)    | 1T (QD=64, batched pipeline)      | 330,195.7 ± 361.3       | 1,289.8 ± 1.4 MB/s | 0.4569                                   | 283.6                    | 10541.4                    | 0.227 B/op |
| `FileChannel.write`      | 1T (QD=1, synchronous `pwrite64`) | 72,470.8 ± 1,044.1      | 283.1 ± 4.1 MB/s   | 1.0004                                   | 901.3                    | 25023.8                    | 0.050 B/op |
| `JUring.write` (QD=1)    | 1T (QD=1, unbatched `O_DIRECT`)   | 72,409.4 ± 1,577.1      | 282.8 ± 6.2 MB/s   | 1.0022                                   | 724.5                    | 26086.7                    | 1.521 B/op |
| `JUring.write` (QD=64)   | 1T (QD=64, batched pipeline)      | 191,163.1 ± 17,715.3    | 746.7 ± 69.2 MB/s  | 0.1445                                   | 286.3                    | 11941.4                    | 0.574 B/op |

> [!NOTE]
> **Calling-Thread Memory Allocations:**
> In a steady state, JUring's submission and completion pipeline (submitting SQEs, reaping CQEs via `ResultConsumer`, and reusing fixed buffers) allocates 0 bytes on the calling thread. This is verified in [`ZeroAllocationHotPathTest.java`](src/test/java/com/davidvlijmincx/lio/api/ZeroAllocationHotPathTest.java).

### Equal Queue Depth Comparison (QD = 1, 16, 32, 64)
Here is a direct comparison between standard Java `FileChannel` and `JUring` on a single thread across increasing hardware Queue Depths (QD = 1, 16, 32, 64) for 4KB random direct reads (`O_DIRECT`) on a 10 GB file.

Because `FileChannel` operations are synchronous, a single thread can only issue one I/O request at a time, meaning its effective queue depth is clamped at QD=1. To get higher queue depth out of `FileChannel`, you would need to spawn more threads. In contrast, `JUring` can drive any queue depth asynchronously on that exact same single thread.
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Queue Depth (QD) | JUring (1 Thread) Throughput | JUring Bandwidth   | FileChannel (1 Thread) Throughput | FileChannel Bandwidth | Speedup (JUring vs FileChannel) |
|------------------|------------------------------|--------------------|-----------------------------------|-----------------------|---------------------------------|
| QD = 1           | 11,492.2 ± 49.6              | 44.9 ± 0.2 MB/s    | 11,431.7 ± 48.2                   | 44.7 ± 0.2 MB/s       | 1.0x                            |
| QD = 16          | 148,011.3 ± 190.3            | 578.2 ± 0.7 MB/s   | 11,447.1 ± 34.3                   | 44.7 ± 0.1 MB/s       | 12.9x                           |
| QD = 32          | 236,162.3 ± 423.9            | 922.5 ± 1.7 MB/s   | 11,174.3 ± 1,316.8                | 43.6 ± 5.1 MB/s       | 21.1x                           |
| QD = 64          | 330,089.1 ± 748.0            | 1,289.4 ± 2.9 MB/s | 11,437.3 ± 21.9                   | 44.7 ± 0.1 MB/s       | 28.9x                           |


### 4 KB Direct Read Scaling (Single 10 GB File)
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Threads    | JUring Concurrency    | FileChannel Concurrency | JUring (ops/s)       | Bandwidth            | FileChannel (ops/s)  | Bandwidth          | Speedup |
|------------|-----------------------|-------------------------|----------------------|----------------------|----------------------|--------------------|---------|
| 1 Thread   | 1T × QD=64 (QD=64)    | 1T × QD=1 (QD=1)        | 319,847.2 ± 40,983.5 | 1,249.4 ± 160.1 MB/s | 10,133.8 ± 2,439.9   | 39.6 ± 9.5 MB/s    | 31.6x   |
| 2 Threads  | 2T × QD=64 (QD=128)   | 2T × QD=1 (QD=2)        | 417,751.7 ± 48,022.9 | 1,631.8 ± 187.6 MB/s | 22,397.0 ± 61.8      | 87.5 ± 0.2 MB/s    | 18.7x   |
| 4 Threads  | 4T × QD=64 (QD=256)   | 4T × QD=1 (QD=4)        | 474,169.6 ± 9,109.1  | 1,852.2 ± 35.6 MB/s  | 43,349.6 ± 106.3     | 169.3 ± 0.4 MB/s   | 10.9x   |
| 8 Threads  | 8T × QD=64 (QD=512)   | 8T × QD=1 (QD=8)        | 436,041.5 ± 66,223.2 | 1,703.3 ± 258.7 MB/s | 79,029.2 ± 11,071.5  | 308.7 ± 43.2 MB/s  | 5.5x    |
| 16 Threads | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16)      | 477,680.3 ± 1,337.7  | 1,865.9 ± 5.2 MB/s   | 130,645.4 ± 34,947.8 | 510.3 ± 136.5 MB/s | 3.7x    |

At 4 threads and above, JUring saturates the read capability of the NVMe SSD at around 478,000 ops/s (~1.87 GB/s).

### 4 KB Direct Write Scaling
Direct writes with NVMe drives under heavy random write loads:
*Harness: 5 forks × 3 iterations × 3 s per tier*

| Threads    | JUring Concurrency    | FileChannel Concurrency | JUring (ops/s)        | Bandwidth          | FileChannel (ops/s)   | Bandwidth          | Speedup |
|------------|-----------------------|-------------------------|-----------------------|--------------------|-----------------------|--------------------|---------|
| 1 Thread   | 1T × QD=64 (QD=64)    | 1T × QD=1 (QD=1)        | 190,386.0 ± 15,741.9  | 743.7 ± 61.5 MB/s  | 72,282.7 ± 894.9      | 282.4 ± 3.5 MB/s   | 2.6x    |
| 2 Threads  | 2T × QD=64 (QD=128)   | 2T × QD=1 (QD=2)        | 209,754.7 ± 162,796.8 | 819.4 ± 635.9 MB/s | 129,047.1 ± 38,823.0  | 504.1 ± 151.7 MB/s | 1.6x    |
| 4 Threads  | 4T × QD=64 (QD=256)   | 4T × QD=1 (QD=4)        | 191,121.8 ± 17,101.6  | 746.6 ± 66.8 MB/s  | 194,392.4 ± 187,672.0 | 759.3 ± 733.1 MB/s | 1.0x    |
| 8 Threads  | 8T × QD=64 (QD=512)   | 8T × QD=1 (QD=8)        | 191,087.2 ± 25,148.1  | 746.4 ± 98.2 MB/s  | 184,816.5 ± 33,252.5  | 721.9 ± 129.9 MB/s | 1.0x    |
| 16 Threads | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16)      | 169,392.2 ± 147,763.7 | 661.7 ± 577.2 MB/s | 189,421.1 ± 38,013.8  | 739.9 ± 148.5 MB/s | 0.9x    |

> [!NOTE]
> **Direct Write Variance:**
> Direct writes are _very_ tricky to benchmark accurately. Under sustained random direct write pressure my storage seems to give up, which leads to high variance and wide error margins. I included the raw numbers here to show the realistic behavior on this hardware. I tried pauses between runs, flushing without success. IF you know a fix, let’s talk!

### Multi-File Scaling (2,211 Discrete Files)
In this benchmark we spread the reads and writes across 2,211 discrete files to see how it performs with many open files.
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Threads    | JUring Read (ops/s)   | FileChannel Read (ops/s) | Read Speedup | JUring Write (ops/s)  | FileChannel Write (ops/s) | Write Speedup |
|------------|-----------------------|--------------------------|--------------|-----------------------|---------------------------|---------------|
| 1 Thread   | 330,196.5 ± 858.5     | 11,208.8 ± 961.7         | 29.5x        | 182,490.2 ± 105,670.7 | 66,920.4 ± 2,223.0        | 2.7x          |
| 2 Threads  | 408,268.4 ± 76,881.6  | 21,540.7 ± 3,201.0       | 19.0x        | 166,410.9 ± 26,827.3  | 122,919.8 ± 34,570.7      | 1.4x          |
| 4 Threads  | 415,991.5 ± 170,630.8 | 43,216.2 ± 86.2          | 9.6x         | 34,286.3 ± 147,050.3  | 186,134.7 ± 106,743.9     | 0.2x          |
| 8 Threads  | 477,423.5 ± 1,286.0   | 81,451.0 ± 181.6         | 5.9x         | 174,209.0 ± 32,179.4  | 158,444.4 ± 18,290.8      | 1.1x          |
| 16 Threads | 400,720.3 ± 123,011.8 | 146,090.4 ± 315.6        | 2.7x         | 133,530.4 ± 397,418.0 | 171,070.4 ± 18,539.7      | 0.8x          |

### Virtual Threads Scaling (50 to 1,000 VTs)
When looking at this, remember that virtual threads will spawn more carrier threads to keep the CPUs busy.
*Harness: 3 forks × 3 iterations × 3 s per tier*

| Virtual Threads | JUringBlocking (ops/s) | Bandwidth            | FileChannel (ops/s)  | Bandwidth            | Speedup | JUring Syscalls/op | FC Syscalls/op | JUring CPU/op | FC CPU/op |
|-----------------|------------------------|----------------------|----------------------|----------------------|---------|--------------------|----------------|---------------|-----------|
| 50 VTs          | 257,117.2 ± 60,960.8   | 1,004.4 ± 238.1 MB/s | 283,203.3 ± 321.6    | 1,106.3 ± 1.3 MB/s   | 0.91x   | 5.4819             | 1.6938         | 30.8 µs       | 25.6 µs   |
| 100 VTs         | 352,439.5 ± 84,936.2   | 1,376.7 ± 331.8 MB/s | 382,098.6 ± 472.8    | 1,492.6 ± 1.8 MB/s   | 0.92x   | 4.4565             | 1.7665         | 30.1 µs       | 26.5 µs   |
| 250 VTs         | 441,046.7 ± 3,085.8    | 1,722.8 ± 12.1 MB/s  | 445,255.8 ± 502.2    | 1,739.3 ± 2.0 MB/s   | 0.99x   | 4.3933             | 1.4086         | 28.9 µs       | 31.2 µs   |
| 500 VTs         | 413,256.9 ± 116,060.1  | 1,614.3 ± 453.4 MB/s | 444,553.8 ± 10,378.7 | 1,736.5 ± 40.5 MB/s  | 0.93x   | 3.8754             | 1.2930         | 28.4 µs       | 24.1 µs   |
| 1000 VTs        | 413,265.4 ± 148,754.4  | 1,614.3 ± 581.1 MB/s | 426,301.9 ± 85,555.4 | 1,665.2 ± 334.2 MB/s | 0.97x   | 3.4514             | 1.2538         | 28.9 µs       | 23.6 µs   |

Notice that as the virtual thread count goes up, `FileChannel` throughput stays high because the scheduler keeps adding carrier threads behind the scenes.

#### Impact of Loom Carrier Thread Cap (`maxPoolSize=1024` vs default 256 at 1,000 VTs)
If we raise the carrier thread limit to 1,024, `FileChannel` can spin up even more carrier threads:

| Configuration                                   | Virtual Threads | Engine         | Throughput (ops/s)   | Bandwidth            |
|-------------------------------------------------|-----------------|----------------|----------------------|----------------------|
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000           | FileChannel    | 379,776.6 ± 21,294.0 | 1,483.5 ± 83.2 MB/s  |
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000           | JUringBlocking | 425,417.5 ± 86,203.3 | 1,661.8 ± 336.7 MB/s |

#### Equal Thread Budget (Carrier Threads Limited to JUring Poller Count)
To see what happens when carrier threads cannot expand indefinitely, we can limit the carrier thread pool to 4 platform threads (matching the 4 poller threads used by `JUringBlocking`).

When carrier threads are capped, `FileChannel` gets throttled because its carrier threads block waiting on storage. `JUringBlocking` unmounts the virtual thread via `LockSupport.park()`, so carrier threads stay free to execute other work:

| Configuration              | Virtual Threads | Engine         | Throughput (ops/s)   | Bandwidth           |
|----------------------------|-----------------|----------------|----------------------|---------------------|
| Equal Carriers (4 threads) | 1,000           | FileChannel    | 39,917.4 ± 10,251.7  | 155.9 ± 40.0 MB/s   |
| Equal Carriers (4 threads) | 1,000           | JUringBlocking | 449,448.2 ± 12,379.6 | 1,755.7 ± 48.4 MB/s |

#### Equal Total Platform Thread Budget (1–2, 4, 8, 16 Threads at 1,000 VTs)
Here is a breakdown where the platform thread budget is held equal between both engines (1,000 virtual threads):

| Platform Thread Budget | Engine         | Thread Breakdown          | Throughput (ops/s)    | Bandwidth            | Speedup  |
|------------------------|----------------|---------------------------|-----------------------|----------------------|----------|
| 1 Threads (Min)        | FileChannel    | 1 carrier                 | 11,433.8 ± 31.5       | 44.7 ± 0.1 MB/s      | baseline |
| 1–2 Threads (Min)      | JUringBlocking | 1 carrier + 1 poller (2T) | 399,108.7 ± 89,551.6  | 1,559.0 ± 349.8 MB/s | 34.91x   |
| 4 Threads              | FileChannel    | 4 carriers                | 43,080.4 ± 220.6      | 168.3 ± 0.9 MB/s     | baseline |
| 4 Threads              | JUringBlocking | 2 carriers + 2 pollers    | 452,192.8 ± 1,262.9   | 1,766.4 ± 4.9 MB/s   | 10.50x   |
| 8 Threads              | FileChannel    | 8 carriers                | 73,003.5 ± 23,547.5   | 285.2 ± 92.0 MB/s    | baseline |
| 8 Threads              | JUringBlocking | 4 carriers + 4 pollers    | 389,340.6 ± 158,626.3 | 1,520.9 ± 619.6 MB/s | 5.33x    |
| 16 Threads             | FileChannel    | 16 carriers               | 137,326.1 ± 32,534.5  | 536.4 ± 127.1 MB/s   | baseline |
| 16 Threads             | JUringBlocking | 8 carriers + 8 pollers    | 443,592.3 ± 40,401.8  | 1,732.8 ± 157.8 MB/s | 3.23x    |

### Project Panama (FFM API) Downcall Overhead
To see where the dispatch overhead comes from, we benchmarked different ways of invoking foreign functions using the FFM API:

| Invocation Strategy                       | Call Mechanism                         | Average Time (ns/op) |
|-------------------------------------------|----------------------------------------|----------------------|
| `staticFinal_critical_invokeExact`        | Linker.Option.critical(true)           | 2.62 ± 0.01 ns/op    |
| `methodHandleProxies_critical`            | Linker.Option.critical(true)           | 4.67 ± 0.02 ns/op    |
| `staticFinal_invoke`                      | Direct MethodHandle                    | 5.75 ± 0.43 ns/op    |
| `record_staticFinal_proxy`                | MethodHandleProxies / Record Interface | 5.91 ± 0.01 ns/op    |
| `staticFinal_invokeExact`                 | Direct MethodHandle                    | 5.92 ± 0.02 ns/op    |
| `instanceField_invokeExact`               | Instance Field Handle                  | 7.99 ± 0.06 ns/op    |
| `instanceField_extractedFromRecord_proxy` | MethodHandleProxies / Record Interface | 8.23 ± 0.07 ns/op    |
| `methodHandleProxies_asInterfaceInstance` | MethodHandleProxies / Record Interface | 8.24 ± 0.12 ns/op    |
| `record_instance_proxy`                   | MethodHandleProxies / Record Interface | 8.64 ± 0.07 ns/op    |