# JUring: File I/O for Java using IO_uring

JUring is a Java library that provides bindings to Linux's io_uring asynchronous I/O interface using Java's Foreign Function
& Memory API. JUring can deliver significant performance improvements over standard Java FileChannel operations, particularly for high-throughput file I/O workloads.

## Performance

### Key Performance Highlights

**JUring with registered files provides the following performance:**
- **Up to 426% faster** than pre-opened FileChannels at 4KB buffer sizes for reads
- **29% faster** at 512-byte operations, handling over 22,000 operations per millisecond
- Write performance matching or exceeding FileChannel performance across buffer sizes
- Scalability across multiple concurrent threads (1-25 threads tested)

### Benchmark Results
All benchmarks were conducted on a Linux machine using JMH (Java Microbenchmark Harness) with 2,211 operations per test invocation.

#### Read Performance: Optimized File Access (25 threads)
Comparing registered files vs pre-opened FileChannels:

| Buffer Size | Registered Files (ops/ms) | Pre-opened FileChannels (ops/ms) | **Improvement** |
|-------------|---------------------------|----------------------------------|-----------------|
| 512 bytes   | 22,332                    | 17,277                           | **+29%**        |
| 4KB         | 11,777                    | 2,239                            | **+426%**       |
| 16KB        | 631                       | 554                              | **+14%**        |
| 64KB        | 133                       | 129                              | **+3%**         |

#### Read Performance: JUring vs FileChannel Operations (25 threads)
Comparing different I/O approaches with open/read/close patterns:

| Buffer Size | JUring Open/Read/Close (ops/ms) | FileChannel Open/Read/Close (ops/ms) | **Improvement** |
|-------------|--------------------------------|--------------------------------------|-----------------|
| 512 bytes   | 1,252                          | 968                                  | **+29%**        |
| 4KB         | 1,268                          | 855                                  | **+48%**        |
| 16KB        | 563                            | 445                                  | **+27%**        |
| 64KB        | 141                            | 125                                  | **+13%**        |

The goal of this benchmark is to open a file, read the given buffer size, and close it again. Opening and closing the files
is the heavy part of this benchmark.

#### Read Performance: Blocking I/O with Virtual Threads (25 threads)
Comparing JUring blocking vs FileChannel with Virtual Threads:

| Buffer Size | JUring Blocking + VThreads (ops/ms) | FileChannel + VThreads (ops/ms) | **Improvement** |
|-------------|-------------------------------------|----------------------------------|-----------------|
| 512 bytes   | 1,051                               | 923                              | **+14%**        |
| 4KB         | 1,029                               | 710                              | **+45%**        |
| 16KB        | 788                                 | 350                              | **+125%**       |
| 64KB        | 286                                 | 120                              | **+138%**       |

#### Write Performance Scaling
JUring registered files vs pre-opened FileChannels across different thread counts:

**Single Thread:**

| Buffer Size | JUring (ops/ms) | FileChannel (ops/ms) | **Improvement** |
|-------------|-----------------|----------------------|-----------------|
| 512 bytes   | 891             | 400                  | **+123%**       |
| 4KB         | 860             | 260                  | **+231%**       |
| 16KB        | 498             | 144                  | **+246%**       |
| 64KB        | 151             | 53                   | **+185%**       |

**8 Threads:**

| Buffer Size | JUring (ops/ms) | FileChannel (ops/ms) | **Improvement** |
|-------------|-----------------|----------------------|-----------------|
| 512 bytes   | 4,292           | 2,429                | **+77%**        |
| 4KB         | 3,013           | 1,724                | **+75%**        |
| 16KB        | 1,189           | 895                  | **+33%**        |
| 64KB        | 286             | 294                  | **-3%**         |

**20 Threads:**

| Buffer Size | JUring (ops/ms) | FileChannel (ops/ms) | **Improvement** |
|-------------|-----------------|----------------------|-----------------|
| 512 bytes   | 5,200           | 5,204                | **0%**          |
| 4KB         | 3,381           | 3,440                | **-2%**         |
| 16KB        | 1,211           | 1,449                | **-16%**        |
| 64KB        | 233             | 346                  | **-33%**        |

### When to Use JUring

**JUring excels in scenarios with:**
- High-throughput file I/O operations (thousands of ops/ms)
- Applications that can pre-register files for optimal performance
- Workloads with small to medium buffer sizes (512B - 16KB)
- Single-threaded or lightly-threaded write operations
- Mixed read/write workloads where read performance is critical

**Consider standard FileChannel for:**
- High-concurrency write operations (20+ threads)
- Large buffer sizes (64KB+) with many concurrent writers
- Applications requiring broad platform compatibility
- Occasional file operations
- Simplicity (Working on this!)

## Benchmark Methodology

The benchmarks use JMH (Java Microbenchmark Harness) with the following configuration:

- **Operations per test**: 2,211 operations per invocation, each thread has to process a given list of files and offsets
- **Queue depth**: 256 inflight requests
- **Access pattern**: Random offsets within files
- **Thread counts**: 1, 8, 20, and 25 concurrent threads (varies by test)
- **Buffer sizes**: 512 bytes, 4KB, 16KB, 64KB
- **Warmup**: Ring initialization performed outside benchmark timing

### Benchmark Categories

- **`registeredFiles`**: io_uring with pre-registered file descriptors (optimal performance)
- **`preOpenedFileChannels`**: FileChannel with pre-opened file handles
- **`juringOpenReadClose`**: JUring with full open/read/close cycle
- **`fileChannelOpenReadClose`**: FileChannel with full open/read/close cycle
- **`juringBlockingWithVirtualThreads`**: JUring blocking API with Virtual Threads
- **`fileChannelOpenReadCloseOnVirtualThreads`**: FileChannel with Virtual Threads

For complete benchmark source code and detailed methodology, see the test files in the repository `src/test/java/bench/random`.

## Requirements

- Linux kernel 5.1 or higher
- liburing installed
- Java 22 or higher (for Foreign Function & Memory API)

## Current Limitations and Future Improvements

### Points of interest
- **Read operations**: JUring shows consistent advantages, especially with registered files
- **Write operations**: Performance advantages diminish at high concurrency (20+ threads)
- **Sweet spot**: 4KB buffer size shows the most dramatic improvements for reads
- **Scaling**: JUring shows better scaling characteristics for single-threaded operations

### Known Limitations
- **Initialization overhead**: Creating JUring instances takes a few milliseconds
- **Platform dependency**: Linux-only due to io_uring requirement
- **High concurrency writes**: FileChannel may perform better with many concurrent writers

### Planned Improvements
- Ring pooling for reduced initialization costs
- Write performance optimization for high-concurrency scenarios
- Additional io_uring features (file modes, flags, sockets)
- Enhanced blocking API performance
- Improved memory cleanup strategies

## Creating the benchmark files
If you want to run the benchmark yourself, you can use the following:
```shell
seq 1 2211 | xargs -P 8 -I {} bash -c 'yes "{} " | head -c 5242880 > "file_{}.bin"'
```

---

*Note: Benchmark results show that JUring's advantages are most pronounced for read operations and single-threaded scenarios. For write-heavy workloads with high concurrency, evaluate both approaches based on your specific use case.*

# The Read benchmarks:

Local file performance @ 25 threads:
```text
Benchmark                                                     (bufferSize)   Mode  Cnt     Score    Error   Units
RandomReadBenchMark.juringBlockingWithVirtualThreads                   512  thrpt    5  1050.689 ±  2.313  ops/ms
RandomReadBenchMark.juringBlockingWithVirtualThreads                  4096  thrpt    5  1028.819 ±  1.627  ops/ms
RandomReadBenchMark.juringBlockingWithVirtualThreads                 16386  thrpt    5   787.902 ±  3.424  ops/ms
RandomReadBenchMark.juringBlockingWithVirtualThreads                 65536  thrpt    5   286.451 ±  2.304  ops/ms
RandomReadBenchMark.fileChannelOpenReadCloseOnVirtualThreads           512  thrpt    5   923.494 ± 11.217  ops/ms
RandomReadBenchMark.fileChannelOpenReadCloseOnVirtualThreads          4096  thrpt    5   710.151 ±  3.830  ops/ms
RandomReadBenchMark.fileChannelOpenReadCloseOnVirtualThreads         16386  thrpt    5   350.201 ±  1.265  ops/ms
RandomReadBenchMark.fileChannelOpenReadCloseOnVirtualThreads         65536  thrpt    5   120.250 ±  0.845  ops/ms
RandomReadBenchMark.juringOpenReadClose                                512  thrpt    5  1252.103 ± 72.777  ops/ms
RandomReadBenchMark.juringOpenReadClose                               4096  thrpt    5  1267.618 ± 61.142  ops/ms
RandomReadBenchMark.juringOpenReadClose                              16386  thrpt    5   562.698 ± 25.074  ops/ms
RandomReadBenchMark.juringOpenReadClose                              65536  thrpt    5   141.287 ± 17.662  ops/ms
RandomReadBenchMark.fileChannelOpenReadClose                           512  thrpt    5   968.433 ±  7.388  ops/ms
RandomReadBenchMark.fileChannelOpenReadClose                          4096  thrpt    5   854.720 ± 11.367  ops/ms
RandomReadBenchMark.fileChannelOpenReadClose                         16386  thrpt    5   445.172 ± 11.166  ops/ms
RandomReadBenchMark.fileChannelOpenReadClose                         65536  thrpt    5   124.710 ±  2.004  ops/ms

```

Performance @ 25 threads

```text
Benchmark                                  (bufferSize)   Mode  Cnt      Score     Error   Units
RandomReadBenchMark.preOpenedFileChannels           512  thrpt    5  17276.679 ± 203.531  ops/ms
RandomReadBenchMark.preOpenedFileChannels          4096  thrpt    5   2238.837 ±  70.137  ops/ms
RandomReadBenchMark.preOpenedFileChannels         16386  thrpt    5    554.172 ±  19.729  ops/ms
RandomReadBenchMark.preOpenedFileChannels         65536  thrpt    5    129.320 ±   2.716  ops/ms
RandomReadBenchMark.registeredFiles                 512  thrpt    5  22331.600 ± 400.126  ops/ms
RandomReadBenchMark.registeredFiles                4096  thrpt    5  11777.366 ± 763.342  ops/ms
RandomReadBenchMark.registeredFiles               16386  thrpt    5    631.134 ±  45.910  ops/ms
RandomReadBenchMark.registeredFiles               65536  thrpt    5    132.891 ±  15.717  ops/ms
```

# The Write benchmarks:

1 thread
```text
Benchmark                                   (bufferSize)   Mode  Cnt    Score    Error   Units
RandomWriteBenchmark.preOpenedFileChannels           512  thrpt    5  400.075 ± 17.247  ops/ms
RandomWriteBenchmark.preOpenedFileChannels          4096  thrpt    5  260.327 ±  5.694  ops/ms
RandomWriteBenchmark.preOpenedFileChannels         16386  thrpt    5  143.749 ±  1.424  ops/ms
RandomWriteBenchmark.preOpenedFileChannels         65536  thrpt    5   53.066 ±  1.149  ops/ms
RandomWriteBenchmark.registeredFiles                 512  thrpt    5  891.473 ± 96.506  ops/ms
RandomWriteBenchmark.registeredFiles                4096  thrpt    5  860.157 ± 35.019  ops/ms
RandomWriteBenchmark.registeredFiles               16386  thrpt    5  497.574 ±  3.014  ops/ms
RandomWriteBenchmark.registeredFiles               65536  thrpt    5  150.941 ± 18.614  ops/ms
```

8 threads
```text
Benchmark                                   (bufferSize)   Mode  Cnt     Score    Error   Units
RandomWriteBenchmark.preOpenedFileChannels           512  thrpt    5  2428.613 ± 57.373  ops/ms
RandomWriteBenchmark.preOpenedFileChannels          4096  thrpt    5  1723.750 ± 47.703  ops/ms
RandomWriteBenchmark.preOpenedFileChannels         16386  thrpt    5   894.529 ± 21.969  ops/ms
RandomWriteBenchmark.preOpenedFileChannels         65536  thrpt    5   294.078 ± 16.229  ops/ms
RandomWriteBenchmark.registeredFiles                 512  thrpt    5  4291.695 ± 34.726  ops/ms
RandomWriteBenchmark.registeredFiles                4096  thrpt    5  3013.474 ± 43.673  ops/ms
RandomWriteBenchmark.registeredFiles               16386  thrpt    5  1189.466 ±  6.460  ops/ms
RandomWriteBenchmark.registeredFiles               65536  thrpt    5   285.783 ± 30.037  ops/ms
```

20 threads
```text
Benchmark                                   (bufferSize)   Mode  Cnt     Score    Error   Units
RandomWriteBenchmark.preOpenedFileChannels           512  thrpt    5  5204.042 ±  65.680  ops/ms
RandomWriteBenchmark.preOpenedFileChannels          4096  thrpt    5  3440.433 ±  89.458  ops/ms
RandomWriteBenchmark.preOpenedFileChannels         16386  thrpt    5  1449.132 ± 111.456  ops/ms
RandomWriteBenchmark.preOpenedFileChannels         65536  thrpt    5   346.176 ±  17.737  ops/ms
RandomWriteBenchmark.registeredFiles                 512  thrpt    5  5200.068 ± 128.891  ops/ms
RandomWriteBenchmark.registeredFiles                4096  thrpt    5  3380.841 ±   5.979  ops/ms
RandomWriteBenchmark.registeredFiles               16386  thrpt    5  1211.093 ±  10.345  ops/ms
RandomWriteBenchmark.registeredFiles               65536  thrpt    5   232.730 ±  17.184  ops/ms
```