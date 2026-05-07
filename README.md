# JUring: File I/O for Java using IO_uring

JUring is a Java library that provides bindings to Linux's io_uring asynchronous I/O interface using Java's Foreign Function
& Memory API. JUring can deliver significant performance improvements over standard Java FileChannel operations, particularly for high-throughput file I/O workloads.

## Performance

### Key Performance Highlights

**JUring with registered files provides the following performance:**
- **Up to 489% faster** than pre-opened FileChannels at 4KB buffer sizes for reads
- **29% faster** at 512-byte operations, handling over 22,000 operations per millisecond
- Write performance matching or exceeding FileChannel performance across buffer sizes
- Scalability across multiple concurrent threads (1-25 threads tested)

### Benchmark Results
All benchmarks were conducted on a Linux machine using JMH (Java Microbenchmark Harness) with 2,211 operations per test invocation.

#### Read Performance: Optimized File Access (25 threads)
Comparing registered files vs pre-opened FileChannels:

| Buffer Size | Registered Files (ops/ms) | Pre-opened FileChannels (ops/ms) | **Improvement** |
|-------------|---------------------------|----------------------------------|-----------------|
| 512 bytes   | 29,023                    | 18,769                           | **+55%**        |
| 4KB         | 13,393                    | 2,275                            | **+489%**       |
| 16KB        | 679                       | 554                              | **+23%**        |
| 64KB        | 150                       | 129                              | **+16%**        |

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