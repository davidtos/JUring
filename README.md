# JUring: File I/O for Java using IO_uring
JUring is a Java library that provides bindings to Linux's io_uring asynchronous I/O interface
using Java's Foreign Function & Memory API. The API Can be much faster, the same, or a bit slower than FileChannels. It all depends on your use case.

## Performance
The following benchmarks show the improvement of using io_uring over Java built-in I/O.
The test ran on a Linux machine with 32 cores, a nvme SSD, and a mounted remote directory.

Local file performance:
```text
Benchmark                                                       (bufferSize)   Mode  Cnt      Score    Error   Units
newRead.RandomReadBenchMark.libUring                                     512  thrpt    5   1416.143 ± 98.086  ops/ms
newRead.RandomReadBenchMark.libUring                                    4096  thrpt    5   1148.974 ± 91.534  ops/ms
newRead.RandomReadBenchMark.libUring                                   16386  thrpt    5    583.387 ± 11.332  ops/ms
newRead.RandomReadBenchMark.libUring                                   65536  thrpt    5    130.148 ±  4.805  ops/ms
newRead.RandomReadBenchMark.libUringBlocking                             512  thrpt    5   1004.981 ±  5.401  ops/ms
newRead.RandomReadBenchMark.libUringBlocking                            4096  thrpt    5    996.660 ±  5.346  ops/ms
newRead.RandomReadBenchMark.libUringBlocking                           16386  thrpt    5    808.690 ±  6.288  ops/ms
newRead.RandomReadBenchMark.libUringBlocking                           65536  thrpt    5    314.292 ±  5.219  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels                        512  thrpt    5  13839.422 ± 79.579  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels                       4096  thrpt    5   2417.945 ± 80.966  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels                      16386  thrpt    5    669.422 ±  9.774  ops/ms
newRead.RandomReadBenchMark.preOpenedFileChannels                      65536  thrpt    5    158.705 ± 14.872  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannel                         512  thrpt    5    958.616 ± 15.628  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannel                        4096  thrpt    5    860.071 ± 28.064  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannel                       16386  thrpt    5    475.750 ± 39.239  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannel                       65536  thrpt    5    147.494 ± 19.095  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannelVirtualThreads           512  thrpt    5    951.577 ± 20.137  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannelVirtualThreads          4096  thrpt    5    741.233 ± 13.796  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannelVirtualThreads         16386  thrpt    5    355.568 ±  3.997  ops/ms
newRead.RandomReadBenchMark.readUsingFileChannelVirtualThreads         65536  thrpt    5    120.626 ±  0.883  ops/ms
newRead.RandomReadBenchMark.registeredFiles                              512  thrpt    5  15092.160 ± 73.602  ops/ms
newRead.RandomReadBenchMark.registeredFiles                             4096  thrpt    5   2381.836 ± 50.406  ops/ms
newRead.RandomReadBenchMark.registeredFiles                            16386  thrpt    5    654.754 ±  5.912  ops/ms
newRead.RandomReadBenchMark.registeredFiles                            65536  thrpt    5    129.787 ±  9.773  ops/ms
```

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
