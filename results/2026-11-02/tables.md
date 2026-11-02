================================================================================
 JUring Publication Benchmark Report (results/2026-11-02)
================================================================================

### FIO vs JUring Head-to-Head Comparison
| Workload | Native C `fio` (Default, 3 repeats) | JUring (JMH, 3 repeats) | Relative Delta | Notes |
|---|---|---|---|---|
| **Random Read (QD=64, 4KB)** | 354,183.4 ± 6,259.3 IOPS (1,383.5 MB/s) | **441,664.3 ± 2,815.3 ops/s (1,725.3 MB/s)** | **+24.7%** | Single 10 GB file, `O_DIRECT`, fixed buffers, registered files |
| **Random Write (QD=64, 4KB)** | 227,565.8 ± 31,845.0 IOPS (888.9 MB/s) | **202,737.4 ± 23,394.1 ops/s (791.9 MB/s)** | **-10.9%** | Physical SLC write buffer drain observed on both engines |

### Single-Core Framework Overhead & Direct I/O Efficiency
| Target | Concurrency / QD | Throughput (ops/s) | Bandwidth | Syscalls / op (`raw_syscalls:sys_enter`) | User CPU (`cycles:u/op`) | Kernel CPU (`cycles:k/op`) | Heap Alloc |
|---|---|---|---|---|---|---|---|
| **`NopBenchmark.juringNop`** | 1T (QD=64, ring memory only) | **11,196,476.1 ± 116,072.0** | — | **0.0313** (32.0 ops/call) | **52.5** | 353.2 | **0.000 B/op** |
| `FileChannel.read` | 1T (QD=1, synchronous `pread64`) | 12,051.8 ± 151.7 | 47.1 MB/s | 1.0020 | 1447.6 | 25131.0 | 0.000 B/op |
| **`JUring.read` (QD=1)** | 1T (QD=1, unbatched `O_DIRECT`) | **12,127.4 ± 51.3** | 47.4 MB/s | **1.0032** | **3508.7** | 25436.7 | **0.000 B/op** |
| **`JUring.read` (QD=64)** | 1T (QD=64, batched pipeline) | **345,115.0 ± 619.6** | **1,348.1 MB/s** | **0.4395** | **274.9** | 10298.9 | **0.000 B/op** |
| `FileChannel.write` | 1T (QD=1, synchronous `pwrite64`) | 71,452.1 ± 504.1 | 279.1 MB/s | 1.0008 | 889.6 | 25619.4 | 0.000 B/op |
| **`JUring.write` (QD=1)** | 1T (QD=1, unbatched `O_DIRECT`) | **71,247.9 ± 732.5** | 278.3 MB/s | **1.0009** | **693.9** | 26555.7 | **0.000 B/op** |
| **`JUring.write` (QD=64)** | 1T (QD=64, batched pipeline) | **318,718.9 ± 221,581.8** | **1,245.0 MB/s** | **0.0383** | **109.3** | 8584.5 | **0.000 B/op** |

### 4 KB Direct Read Scaling (Single 10 GB File)
| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| **1 Thread** | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | **323,971.0 ± 10,378.8** | **1,265.5 MB/s** | 15,941.2 ± 1,513.6 | 62.3 MB/s | **20.3x** |
| **2 Threads** | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | **678,682.2 ± 15,605.4** | **2,651.1 MB/s** | 27,395.4 ± 1,800.7 | 107.0 MB/s | **24.8x** |
| **4 Threads** | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | **717,986.5 ± 1,090.9** | **2,804.6 MB/s** | 52,676.5 ± 1,182.2 | 205.8 MB/s | **13.6x** |
| **8 Threads** | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | **716,319.0 ± 5,207.2** | **2,798.1 MB/s** | 99,471.1 ± 2,669.1 | 388.6 MB/s | **7.2x** |
| **16 Threads** | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | **712,629.3 ± 30,805.4** | **2,783.7 MB/s** | 166,806.9 ± 7,688.9 | 651.6 MB/s | **4.3x** |

### 4 KB Direct Write Scaling (Single 10 GB File)
| Threads | JUring Concurrency | FileChannel Concurrency | JUring (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|---|---|
| **1 Thread** | 1T × QD=64 (QD=64) | 1T × QD=1 (QD=1) | **450,987.4 ± 138,095.8** | **1,761.7 MB/s** | 71,161.2 ± 7,245.0 | 278.0 MB/s | **6.3x** |
| **2 Threads** | 2T × QD=64 (QD=128) | 2T × QD=1 (QD=2) | **598,437.3 ± 128,484.2** | **2,337.6 MB/s** | 139,071.2 ± 4,753.5 | 543.2 MB/s | **4.3x** |
| **4 Threads** | 4T × QD=64 (QD=256) | 4T × QD=1 (QD=4) | **541,630.0 ± 21,049.8** | **2,115.7 MB/s** | 258,057.9 ± 52,784.5 | 1,008.0 MB/s | **2.1x** |
| **8 Threads** | 8T × QD=64 (QD=512) | 8T × QD=1 (QD=8) | **100,077.8 ± 2,414,155.8** | **390.9 MB/s** | 441,552.0 ± 36,979.0 | 1,724.8 MB/s | **0.2x** |
| **16 Threads** | 16T × QD=64 (QD=1024) | 16T × QD=1 (QD=16) | **289,711.6 ± 4,380,413.5** | **1,131.7 MB/s** | 51,686.3 ± 407,548.2 | 201.9 MB/s | **5.6x** |

### Multi-File Scaling (2,211 Discrete Files, `IOSQE_FIXED_FILE`)
| Threads | JUring Read (ops/s) | FileChannel Read (ops/s) | Read Speedup | JUring Write (ops/s) | FileChannel Write (ops/s) | Write Speedup |
|---|---|---|---|---|---|---|
| **1 Thread** | **330,708.3 ± 7,524.9** | 11,391.2 ± 418.4 | **29.0x** | **63,185.8 ± 467,067.3** | 65,597.6 ± 10,861.3 | **1.0x** |
| **2 Threads** | **376,268.9 ± 1,671,721.1** | 22,239.9 ± 820.6 | **16.9x** | **91,574.5 ± 1,838,402.7** | 124,377.0 ± 114,705.9 | **0.7x** |
| **4 Threads** | **424,319.6 ± 1,475,543.7** | 43,076.5 ± 272.5 | **9.9x** | **191,506.7 ± 3,732,824.9** | 208,693.3 ± 385,447.7 | **0.9x** |
| **8 Threads** | **475,027.7 ± 11,473.9** | 81,387.2 ± 1,157.6 | **5.8x** | **59,786.2 ± 28,187.9** | 110,660.2 ± 1,284,998.1 | **0.5x** |
| **16 Threads** | **478,447.7 ± 15,797.1** | 145,925.0 ± 1,978.9 | **3.3x** | **43,318.7 ± 24,734.6** | 306,246.5 ± 728,477.4 | **0.1x** |

### Project Loom Virtual Threads Scaling (Single File, `O_DIRECT`, 50 to 1,000 VTs)
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
| `-Djdk.virtualThreadScheduler.maxPoolSize=1024` | 1,000 | **JUringBlocking** | **536,926.3 ± 91,610.8** | **2,097.4 MB/s** | Carrier pool expanded from default 256 to 1024 |

#### Empirical Scaling on Multi-File Distribution (2,211 Files, `IOSQE_FIXED_FILE`)
| Virtual Threads | JUringBlocking (ops/s) | Bandwidth | FileChannel (ops/s) | Bandwidth | Speedup |
|---|---|---|---|---|---|
| **50 VTs** | **274,996.3 ± 1,450.6** | **1,074.2 MB/s** | 269,391.5 ± 69,102.2 | 1,052.3 MB/s | **1.02x** |
| **100 VTs** | **375,740.5 ± 2,003.4** | **1,467.7 MB/s** | 383,349.9 ± 635.7 | 1,497.5 MB/s | **0.98x** |
| **250 VTs** | **395,832.2 ± 123,496.9** | **1,546.2 MB/s** | 446,128.9 ± 878.5 | 1,742.7 MB/s | **0.89x** |
| **500 VTs** | **428,726.6 ± 115,205.3** | **1,674.7 MB/s** | 446,426.8 ± 5,197.4 | 1,743.9 MB/s | **0.96x** |
| **1000 VTs** | **425,041.6 ± 103,145.0** | **1,660.3 MB/s** | 429,683.0 ± 77,720.3 | 1,678.4 MB/s | **0.99x** |

### Buffered Page-Cache I/O (DRAM Resident, 4KB blocks)
| Workload | Engine | Concurrency / Batch | Throughput (ops/s) | Bandwidth | Syscalls/op (`raw_syscalls:sys_enter`) | Syscall Ratio |
|---|---|---|---|---|---|---|
| **Page Cache Read** | **JUring (`submitBatch=64`)** | 1T (Batch=64) | **1,458,945.4 ± 28,133.6** | **5,699.0 MB/s** | **0.0168** | **59.8x fewer** |
| Page Cache Read | `FileChannel` | 1T (Synchronous) | 1,280,284.0 ± 20,747.6 | 5,001.1 MB/s | 1.0070 | Baseline (1 call/op) |
| **Page Cache Write** | **JUring (`submitBatch=64`)** | 1T (Batch=64) | **890,809.5 ± 61,836.9** | **3,479.7 MB/s** | **0.0446** | **22.6x fewer** |
| Page Cache Write | `FileChannel` | 1T (Synchronous) | 475,238.3 ± 9,206.3 | 1,856.4 MB/s | 1.0089 | In-memory `pwrite` `memcpy` |

### Physical NAND Flash Cell Latency (QD=1, `Mode.SampleTime`)
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

### Foreign Function & Memory (FFM API / Panama) Downcall Overhead
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

