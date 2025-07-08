# JUring: File I/O for Java using IO_uring

JUring is a Java library that provides bindings to Linux's io_uring asynchronous I/O interface using Java's Foreign Function & Memory API. JUring can deliver significant performance improvements over standard Java FileChannel operations, particularly for high-throughput file I/O workloads.

## Performance

### Key Performance Highlights

**JUring with registered files delivers exceptional performance gains:**
- **Up to 342% faster** than pre-opened FileChannels at 4KB buffer sizes.
- On average, around 57% faster than its FileChannel counterpart.
- Excellent scalability from 15 to 20+ concurrent threads.

### Benchmark Results

All benchmarks were conducted on a Linux machine with 32 cores, NVMe SSD, and mounted remote directory using JMH (Java Microbenchmark Harness).

#### Optimized File Access (20 threads)
Comparing registered files vs pre-opened FileChannels:

| Buffer Size | Registered Files (ops/ms) | Pre-opened FileChannels (ops/ms) | **Improvement** |
|-------------|---------------------------|----------------------------------|-----------------|
| 512 bytes   | 21,043                    | 15,755                           | **+34%**        |
| 4KB         | 10,320                    | 2,337                            | **+342%**       |
| 16KB        | 825                       | 596                              | **+38%**        |
| 64KB        | 159                       | 134                              | **+19%**        |

> NOTE: Both implementations show reduced performance at 4KB (likely due to 4KB filesystem page size),
> but FileChannel experiences a particularly severe degradation. Results consistent across multiple runs.

#### Async I/O Performance (15 threads)
Comparing async io_uring vs standard FileChannel:

| Buffer Size | JUring (ops/ms) | FileChannel (ops/ms) | **Improvement** |
|-------------|-----------------|----------------------|-----------------|
| 512 bytes   | 1,384           | 920                  | **+50%**        |
| 4KB         | 1,442           | 835                  | **+73%**        |
| 16KB        | 861             | 471                  | **+83%**        |
| 64KB        | 169             | 149                  | **+13%**        |

#### Blocking I/O with Concurrency (15 threads)
Comparing blocking io_uring vs Virtual Threads:

| Buffer Size | JUring Blocking (ops/ms) | Virtual Threads (ops/ms) | **Improvement** |
|-------------|--------------------------|--------------------------|-----------------|
| 512 bytes   | 1,002                    | 954                      | **+5%**         |
| 4KB         | 990                      | 727                      | **+36%**        |
| 16KB        | 789                      | 354                      | **+123%**       |
| 64KB        | 311                      | 121                      | **+157%**       |


### When to Use JUring

**JUring excels in scenarios with:**
- High-throughput file I/O operations (thousands of ops/ms)
- Multiple concurrent threads performing file operations
- Applications that can pre-register files for optimal performance
- Workloads with small to medium buffer sizes (512B - 16KB)
- Linux environments where io_uring is available

**Consider standard FileChannel for:**
- Single-threaded applications with low I/O volume
- Applications requiring broad platform compatibility
- Simple, occasional file operations

## Benchmark Methodology

The benchmarks use JMH (Java Microbenchmark Harness) with the following configuration:

- **Operations per test**: 2,211 operations per invocation
- **Queue depth**: 2,500 for io_uring operations
- **Access pattern**: Random offsets within files
- **Thread counts**: 15 and 20 concurrent threads
- **Buffer sizes**: 512 bytes, 4KB, 16KB, 64KB
- **Warmup**: Ring initialization performed outside benchmark timing

### Benchmark Categories

- **`libUring`**: Async io_uring operations with completion polling
- **`libUringBlocking`**: io_uring with blocking API for easier integration
- **`readUsingFileChannel`**: Standard Java NIO FileChannel operations
- **`readUsingFileChannelVirtualThreads`**: FileChannel with Virtual Threads
- **`registeredFiles`**: io_uring with pre-registered file descriptors (optimal)
- **`preOpenedFileChannels`**: FileChannel with pre-opened file handles

For complete benchmark source code and detailed methodology, see [BenchMarkLibUring.java](https://github.com/davidtos/JUring/tree/master/src/test/java/bench).

## Requirements

- Linux kernel 5.1 or higher
- liburing installed
- Java 22 or higher (for Foreign Function & Memory API)

## Current Limitations and Future Improvements

### Known Limitations
- **Initialization overhead**: Creating JUring instances takes a few milliseconds
- **Platform dependency**: Linux-only due to io_uring requirement

### Planned Improvements
- Ring pooling for reduced initialization costs
- Additional io_uring features (file modes, flags, sockets)
- Enhanced blocking API performance
- Improved memory cleanup strategies
- Encoding support
- Socket operation support

# The Raw benchmarks:

Local file performance @ 15 threads:
```text
Benchmark                                                       (bufferSize)   Mode  Cnt      Score     Error   Units
newRead.RandomReadBenchMark.libUring                                     512  thrpt    5   1384.275 ± 108.173  ops/ms
newRead.RandomReadBenchMark.libUring                                    4096  thrpt    5   1442.466 ±  29.685  ops/ms
newRead.RandomReadBenchMark.libUring                                   16386  thrpt    5    861.184 ±  66.293  ops/ms
newRead.RandomReadBenchMark.libUring                                   65536  thrpt    5    168.592 ±   5.346  ops/ms
newRead.RandomReadBenchMark.libUringBlocking                             512  thrpt    5   1002.261 ±   5.140  ops/ms
newRead.RandomReadBenchMark.libUringBlocking                            4096  thrpt    5    989.660 ±  10.160  ops/ms
newRead.RandomReadBenchMark.libUringBlocking                           16386  thrpt    5    789.057 ±   3.613  ops/ms
newRead.RandomReadBenchMark.libUringBlocking                           65536  thrpt    5    310.700 ±   6.862  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels                        512  thrpt    5  13924.068 ± 189.409  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels                       4096  thrpt    5   2419.721 ±  15.415  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels                      16386  thrpt    5    667.343 ±   3.461  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels                      65536  thrpt    5    160.559 ±  17.863  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannel                         512  thrpt    5    919.792 ±   5.197  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannel                        4096  thrpt    5    834.812 ±  17.268  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannel                       16386  thrpt    5    471.024 ±  25.449  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannel                       65536  thrpt    5    148.505 ±  10.632  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannelVirtualThreads           512  thrpt    5    954.275 ±  14.139  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannelVirtualThreads          4096  thrpt    5    727.027 ±  11.896  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannelVirtualThreads         16386  thrpt    5    353.569 ±   3.969  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannelVirtualThreads         65536  thrpt    5    120.678 ±   1.531  ops/ms
newRead.RandomReadBenchMark.registeredFiles                              512  thrpt    5  17300.852 ± 197.928  ops/ms
newRead.RandomReadBenchMark.registeredFiles                             4096  thrpt    5   8883.068 ±  48.118  ops/ms
newRead.RandomReadBenchMark.registeredFiles                            16386  thrpt    5   1162.406 ±  92.625  ops/ms
newRead.RandomReadBenchMark.registeredFiles                            65536  thrpt    5    171.881 ±   3.747  ops/ms
```

Performance @ 20 threads

```text
Benchmark                                          (bufferSize)   Mode  Cnt      Score     Error   Units
newRead.RandomReadBenchMark.preOpenedFileChannels           512  thrpt    5  15754.551 ± 298.588  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels          4096  thrpt    5   2336.877 ±  11.428  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels         16386  thrpt    5    596.423 ±  35.493  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels         65536  thrpt    5    133.934 ±   3.941  ops/ms

newRead.RandomReadBenchMark.registeredFiles                 512  thrpt    5  21043.345 ± 154.932  ops/ms
newRead.RandomReadBenchMark.registeredFiles                4096  thrpt    5  10320.438 ± 329.342  ops/ms
newRead.RandomReadBenchMark.registeredFiles               16386  thrpt    5    824.543 ±  39.173  ops/ms
newRead.RandomReadBenchMark.registeredFiles               65536  thrpt    5    159.181 ±  17.876  ops/ms
```


