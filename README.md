# JUring: File I/O for Java using IO_uring
JUring is a high-performance Java library that provides bindings to Linux's io_uring asynchronous I/O interface
using Java's Foreign Function & Memory API. Doing Random reads it achieves 33% better performance than Java NIO FileChannel
operations for local files and 78% better performance for remote files.

## Performance 
The following benchmarks show the improvement of using IO_uring over Java built-in I/O.
The test ran on a linux machine with 32 cores, a nvme SSD, and a mounted remote directory.

Local file performance:
```text
Benchmark                                Mode  Cnt  Score      Error    Units
BenchMarkLibUring.libUring              thrpt    5  1127.286 ± 153.142  ops/ms
BenchMarkLibUring.libUringBlocking      thrpt    5  838.727  ± 35.353   ops/ms
BenchMarkLibUring.readUsingFileChannel  thrpt    5  847.292  ± 8.200    ops/ms
```
JUring achieves 33% higher throughput compared to using FileChannel.

### Local vs Remote File Performance
When testing with remote files (network mounted storage), IO_uring peroformances 78% better than FileChannels.

```text
Benchmark                                              Mode  Cnt  Score   Error   Units
BenchMarkLibUring.libUring                            thrpt    5  1.729 ± 1.201  ops/ms
BenchMarkLibUring.libUringBlocking                    thrpt    5  1.920 ± 0.168  ops/ms
BenchMarkLibUring.readUsingFileChannelVirtualThreads  thrpt    5  1.078 ± 0.990  ops/ms
```
The remote machine uses HDD and is connected with a Cat 5E cable to the machine running the benchmarks. The benchmarks were run 
using a maximum of 5 threads, using more threads opened to many file descriptors. 

## Benchmark Methodology
The benchmarks are conducted using JMH (Java Microbenchmark Harness) with the following parameters:

- Each test performs 2300 operations per invocation 
- Tests using local files ran with 32 threads
- Tests using remote files ran with 5 threads
- Queue depth of 2500 for io_uring operations
- Fixed read size of 4KB (4096 bytes)
- Random offsets within files

The benchmark includes three main scenarios:

- Non-blocking io_uring (libUring): Direct io_uring operations
- Blocking io_uring (libUringBlocking): io_uring with a blocking API
- FileChannel (readUsingFileChannel): Standard Java NIO file operations


For full benchmark details and methodology, see [BenchMarkLibUring.java](https://github.com/davidtos/JUring/tree/master/src/test/java/bench) in the source code.

## Requirements

- Linux kernel 5.1 or higher
- liburing installed
- Java 21 or higher (for Foreign Function & Memory API)

## Quickstart
There are two ways to use JUring, there is the direct and blocking API. The direct API lets you prepare entries that you
match with results based on id. The blocking API is built with virtual threads in mind, blocking/unmounting them while they wait for a result.

Reading from a file
```java
// Blocking API Example
try (JUringBlocking io = new JUringBlocking(32)) {
    // Read file
    BlockingReadResult result = io.prepareRead("input.txt", 1024, 0);
    
    io.submit();
    
    MemorySegment buffer = result.getBuffer();
    // Process buffer...
    result.freeBuffer();
}

// Non-blocking API Example
try (JUring io = new JUring(32)) {
    long id = io.prepareRead("input.txt", 1024, 0);
    
    io.submit();
    
    Result result = io.waitForResult();
    if (result instanceof ReadResult r) {
        MemorySegment buffer = r.getBuffer();
        // Process buffer...
        r.freeBuffer();
    }
}
```

Write to a file
```java
// Blocking API Example
try (JUringBlocking io = new JUringBlocking(32)) {
    byte[] data = "Hello, World!".getBytes();
    BlockingWriteResult writeResult = io.prepareWrite("output.txt", data, 0);
    
    io.submit();
    
    long bytesWritten = writeResult.getResult();
    System.out.println("Wrote " + bytesWritten + " bytes");
}

// Non-blocking API Example
try (JUring io = new JUring(32)) {
    byte[] data = "Hello, World!".getBytes();
    long id = io.prepareWrite("output.txt", data, 0);
    
    io.submit();
    
    Result result = io.waitForResult();
    if (result instanceof WriteResult w) {
        long bytesWritten = w.getResult();
        System.out.println("Wrote " + bytesWritten + " bytes");
    }
}
```

### The steps explained
Both APIs follow a similar pattern of operations:

1. **Initialization**: Create an io_uring instance with a specified queue depth. The queue depth determines how big the submission and completion queue can be.
```java
try (JUringBlocking io = new JUringBlocking(32)) {}
```

2. **Prepare Operation**: Tell io_uring what operation you want to perform. This will add it to the submission queue.
```java
BlockingReadResult result = io.prepareRead("input.txt", 1024, 0);
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

5. **Cleanup**: Free read buffer
For read operations you need to free the buffer when you are done. These buffer live off heap and are not managed by an Arena.
```java
result.freeBuffer();
```
This is not necessary for write operations, these are automatically freed by when the operation is seen in the completion queue.

## Thread Safety
JURing is not thread safe. The completion and submission queue used by IO_uring don't support multiple thread. preparing operations or waiting 
for completions should be done with only with a single thread. Ideally each thread should have its own ring. processing the results/buffers is thread safe.k 

## Current Limitations and Future Improvements

### Memory Usage 
- The current implementation has higher memory usage than ideal. This is a known issue that I'm actively working on improving.

## Future improvements planned:

- Adding more IO_uring features
- File modes and flags
- Adding a blocking-api for local files
- Better memory usage 
- Improved memory cleanup strategies (smart MemorySegments) 
- Encoding support