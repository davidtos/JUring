# JUring: File I/O for Java using IO_uring
JUring is a high-performance Java library that provides bindings to Linux's io_uring asynchronous I/O interface
using Java's Foreign Function & Memory API. Doing Random reads JUring achieves 37.86% better random read performance than Java NIO FileChannel
operations for local files and 60% better performance for random write operations.

## Performance
The following benchmarks show the improvement of using io_uring over Java built-in I/O.
The test ran on a Linux machine with 32 cores, a nvme SSD, and a mounted remote directory.

Local file performance:
```text
Benchmark                                                           (readSize)  (writeSize)   Mode  Cnt     Score     Error   Units
b.r.read.RandomReadBenchMark.libUring                                      512          N/A  thrpt    5  1332.440 ± 213.308  ops/ms
b.r.read.RandomReadBenchMark.libUring                                     4096          N/A  thrpt    5  1323.459 ±  93.749  ops/ms
b.r.read.RandomReadBenchMark.libUring                                    16386          N/A  thrpt    5  1340.537 ± 190.627  ops/ms
b.r.read.RandomReadBenchMark.libUring                                    65536          N/A  thrpt    5  1329.904 ± 176.533  ops/ms
b.r.read.RandomReadBenchMark.libUringBlocking                              512          N/A  thrpt    5  1018.328 ±   4.611  ops/ms
b.r.read.RandomReadBenchMark.libUringBlocking                             4096          N/A  thrpt    5  1018.496 ±   6.833  ops/ms
b.r.read.RandomReadBenchMark.libUringBlocking                            16386          N/A  thrpt    5  1011.150 ±   5.034  ops/ms
b.r.read.RandomReadBenchMark.libUringBlocking                            65536          N/A  thrpt    5  1014.375 ±   3.168  ops/ms
b.r.read.RandomReadBenchMark.readUsingFileChannel                          512          N/A  thrpt    5   946.615 ±   9.223  ops/ms
b.r.read.RandomReadBenchMark.readUsingFileChannel                         4096          N/A  thrpt    5   966.616 ±   4.471  ops/ms
b.r.read.RandomReadBenchMark.readUsingFileChannel                        16386          N/A  thrpt    5   972.344 ±   8.253  ops/ms
b.r.read.RandomReadBenchMark.readUsingFileChannel                        65536          N/A  thrpt    5   966.744 ±  14.707  ops/ms
b.r.read.RandomReadBenchMark.readUsingFileChannelVirtualThreads            512          N/A  thrpt    5   988.161 ±  12.255  ops/ms
b.r.read.RandomReadBenchMark.readUsingFileChannelVirtualThreads           4096          N/A  thrpt    5   986.658 ±  15.267  ops/ms
b.r.read.RandomReadBenchMark.readUsingFileChannelVirtualThreads          16386          N/A  thrpt    5   963.168 ±  20.832  ops/ms
b.r.read.RandomReadBenchMark.readUsingFileChannelVirtualThreads          65536          N/A  thrpt    5   977.285 ±   8.513  ops/ms
b.r.read.RandomReadBenchMark.readUsingRandomAccessFile                     512          N/A  thrpt    5   956.338 ±   3.577  ops/ms
b.r.read.RandomReadBenchMark.readUsingRandomAccessFile                    4096          N/A  thrpt    5   917.699 ±  19.362  ops/ms
b.r.read.RandomReadBenchMark.readUsingRandomAccessFile                   16386          N/A  thrpt    5   915.716 ±  10.454  ops/ms
b.r.read.RandomReadBenchMark.readUsingRandomAccessFile                   65536          N/A  thrpt    5   912.750 ±  10.079  ops/ms

b.r.write.RandomWriteBenchMark.libUring                                    N/A          512  thrpt    5  1234.283 ±   9.916  ops/ms
b.r.write.RandomWriteBenchMark.libUring                                    N/A         4096  thrpt    5  1099.632 ±   5.064  ops/ms
b.r.write.RandomWriteBenchMark.libUring                                    N/A        16386  thrpt    5   666.189 ±  11.487  ops/ms
b.r.write.RandomWriteBenchMark.libUring                                    N/A        65536  thrpt    5   183.988 ±   0.635  ops/ms
b.r.write.RandomWriteBenchMark.libUringBlocking                            N/A          512  thrpt    5   914.569 ±   8.853  ops/ms
b.r.write.RandomWriteBenchMark.libUringBlocking                            N/A         4096  thrpt    5   908.182 ±   4.156  ops/ms
b.r.write.RandomWriteBenchMark.libUringBlocking                            N/A        16386  thrpt    5   885.863 ±  18.603  ops/ms
b.r.write.RandomWriteBenchMark.libUringBlocking                            N/A        65536  thrpt    5   501.706 ±  11.968  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingFileChannel                       N/A          512  thrpt    5   930.162 ±   4.029  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingFileChannel                       N/A         4096  thrpt    5   843.667 ±  18.093  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingFileChannel                       N/A        16386  thrpt    5   661.805 ±  36.755  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingFileChannel                       N/A        65536  thrpt    5   313.467 ±  17.602  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingFileChannelVirtualThreads         N/A          512  thrpt    5   931.536 ±  18.257  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingFileChannelVirtualThreads         N/A         4096  thrpt    5   854.390 ±  19.249  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingFileChannelVirtualThreads         N/A        16386  thrpt    5   563.341 ±   3.725  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingFileChannelVirtualThreads         N/A        65536  thrpt    5   168.824 ±   8.129  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingRandomAccessFile                  N/A          512  thrpt    5   898.822 ±  13.111  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingRandomAccessFile                  N/A         4096  thrpt    5   827.981 ±  14.188  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingRandomAccessFile                  N/A        16386  thrpt    5   656.546 ±  26.878  ops/ms
b.r.write.RandomWriteBenchMark.writeUsingRandomAccessFile                  N/A        65536  thrpt    5   310.930 ±  30.227  ops/ms
```
Uring achieves 37.86% better random read performance than Java NIO FileChannel operations for local files and 60% better performance for random write operations.


## Benchmark Methodology
The benchmarks are conducted using JMH (Java Microbenchmark Harness) with the following parameters:

- Each test performs 2300 operations per invocation
- Tests using local files ran with 15 threads
- Tests using remote files ran with 5 threads (Linux threw errors when using more threads to run the FileChannel and io_uring example)
- Queue depth of 2500 for io_uring operations
- Random offsets within files
- Initializing the rings is done outside the benchmark

The benchmark includes three main scenarios:

- Non-blocking io_uring (libUring): Direct io_uring operations
- Blocking io_uring (libUringBlocking): io_uring with a blocking API
- FileChannel (readUsingFileChannel): Standard Java NIO file operations


For full benchmark details and methodology, see [BenchMarkLibUring.java](https://github.com/davidtos/JUring/tree/master/src/test/java/bench) in the source code.

## Requirements

- Linux kernel 5.1 or higher
- liburing installed
- Java 22 or higher (for Foreign Function & Memory API)

## Quickstart
There are two ways to use JUring, there is the direct and blocking API. The direct API lets you prepare entries that you
match with results based on id. The blocking API is built with virtual threads in mind, blocking/unmounting them while they wait for a result.

Reading from a file:
```java
// Blocking API Example
try (JUringBlocking io = new JUringBlocking(32)) {
    FileDescriptor fd = new FileDescriptor("input.txt", Flag.READ, 0);
    // Read file
    BlockingReadResult result = io.prepareRead(fd, 1024, 0);
    io.submit();

    MemorySegment buffer = result.getBuffer();
    // Process buffer...
    result.freeBuffer();
    fd.close();
}

// Non-blocking API Example
try (JUring io = new JUring(32)) {
    FileDescriptor fd = new FileDescriptor("input.txt", Flag.READ, 0);
    long id = io.prepareRead(fd, 1024, 0);

    io.submit();

    Result result = io.waitForResult();
    if (result instanceof ReadResult r) {
        MemorySegment buffer = r.getBuffer();
        long resultId = r.getId();

        // Process buffer...
        r.freeBuffer();
    }

    fd.close();
}
```

Write to a file
```java
// Blocking API Example
try (JUringBlocking io = new JUringBlocking(32)) {
    FileDescriptor fd = new FileDescriptor("output.txt", Flag.WRITE, 0);
    byte[] data = "Hello, World!".getBytes();

    BlockingWriteResult writeResult = io.prepareWrite(fd, data, 0);

    io.submit();

    long bytesWritten = writeResult.getResult();
    System.out.println("Wrote " + bytesWritten + " bytes");

    fd.close();
}

// Non-blocking API Example
try (JUring io = new JUring(32)) {

    byte[] data = "Hello, World!".getBytes();
    FileDescriptor fd = new FileDescriptor("output.txt", Flag.WRITE, 0);
    long id = io.prepareWrite(fd, data, 0);

    io.submit();

    Result result = io.waitForResult();
    if (result instanceof WriteResult w) {
        long bytesWritten = w.getResult();
        System.out.println("Wrote " + bytesWritten + " bytes from opartion with id: " + result.getId());
    }

    fd.close();
}
```

### The steps explained
Both APIs follow a similar pattern of operations:

1. **Initialization**: Create an io_uring instance with a specified queue depth. The queue depth determines how big the submission and completion queue can be.
```java
try (JUringBlocking io = new JUringBlocking(32)) {}
```
2. **Opening a File**: Open a file you want to perform the operations on. The file has to stay open for the entire duration of the operation. `FileDescriptor` implements the autocloseable interface.
```java
FileDescriptor fd = new FileDescriptor("output.txt", Flag.WRITE, 0);
```

2. **Prepare Operation**: Tell io_uring what operation you want to perform. This will add it to the submission queue.
```java
BlockingReadResult result = io.prepareRead(fd, 1024, 0);
```

3. **Submit**: tell IO_Uring to start working on the prepared entries.
```java
io.submit();
```

4. **Getting results**: Get operations results
```java
// Blocking
MemorySegment buffer = result.getBuffer();

// non-blocking
Result result = io.waitForResult();
```

5. **Cleanup buffers**: Free read buffer

For read operations it is necessary to free the buffer that lives inside the result. The buffers are created using malloc and are not managed by an arena. They are MemorySegments, so it is possible to
have them cleaned up when an area closes.
```java
result.freeBuffer();
```
Freeing buffers is not necessary for write operations, these buffers are automatically freed when the operation is seen in the completion queue by JUring.

6. **Cleanup File descriptors**: After performing all the operations you need to close the file descriptors. It implements the `AutoCloseable` interface to use it with the try-with-resource statement

```java
fd.close();
```

## Thread Safety
JURing is not thread safe, from what I read about io_uring there should only be one instance per thread. I want to copy this behaviour to
not deviate too much from how io_works. The completion and submission queue used by io_uring don't support multiple threads writing to them at the same time. Preparing operations or waiting
for completions should be done by a single thread. Processing the results/buffers is thread safe.

## Current Limitations and Future Improvements

### Creation cost of JUring instances
- Creating an instance takes a few of milliseconds, I am working on minimizing this creation time.

### Memory Usage
- The current implementation has higher memory usage than ideal. This is a known issue that I'm actively working on improving.

## Future improvements planned:

- Pooling of rings.
- Adding more io_uring features
- File modes and flags
- Adding a blocking-api for local files
- Improved memory cleanup strategies (smart MemorySegments)
- Encoding support
- Support for sockets
