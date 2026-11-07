# JUring: Java io_uring via Project Panama

An `io_uring` implementation in Java built on Project Panama's Foreign Function and Memory API (JEP 454).

File I/O operations are synchronous and blocking. When using Virtual Threads, file operations issue blocking system calls that prevent unmounting from carrier threads. JUring talks directly to the Linux `io_uring` kernel interface from Java without JNI, C wrappers, or `liburing.so`!

- Only Java FFM downcalls and VarHandles.
- Zero heap alloc on the hot path after setup.
- Virtual thread support using LockSupport with JUringBlocking (wrapper for JUring).
- Focus on random direct I/O, registered files, and fixed buffers to drive high queue depths.

> The focus of this project for now is random direct I/O on Linux (x86_64). This seemed a fun and challenging place to start, from which to build upon.

## Benchmark Results

The benchmarks below compare JUring against `fio` (`ioengine=io_uring`) and `FileChannel`.

Keep in mind that `FileChannel` is synchronous and drives a queue depth of 1 on a single thread. It cannot keep modern storage saturated on its own. JUring can submit batches of asynchronous requests on a single thread using the queue depth of the storage medium.

#### 4KB Random Direct Read (`randread`, QD=64, 10 GB File, `O_DIRECT`, Fixed Buffers, Registered Files)

| Engine                     | Throughput (ops/s)   | Bandwidth (MB/s)    | Delta vs Best fio | Configuration              |
|----------------------------|----------------------|---------------------|-------------------|----------------------------|
| `fio` (default settings)   | 310,609.8 ± 12,688.8 | 1,213.3 ± 49.6 MB/s | -                 | Default submission / reap  |
| `fio` (best batching: B=8) | 322,908.4 ± 13,644.2 | 1,261.4 ± 53.3 MB/s | baseline          | `--iodepth_batch_submit=8` |
| `FileChannel.read`         | 11,437.3 ± 21.9      | 44.7 ± 0.1 MB/s     | -96.5%            |                            |
| JUring (JMH)               | 325,676.1 ± 9,495.2  | 1,272.2 ± 37.1 MB/s | +0.9%             | Batched `submitAndWait(1)` |

#### 4KB Random Direct Write (`randwrite`, QD=64, 10 GB File, `O_DIRECT`, Fixed Buffers, Registered Files)

| Engine                      | Throughput (ops/s)   | Bandwidth (MB/s)   | Delta vs Best fio | Configuration               |
|-----------------------------|----------------------|--------------------|-------------------|-----------------------------|
| `fio` (default settings)    | 214,941.3 ± 33,471.8 | 839.6 ± 130.7 MB/s | -                 | Default submission / reap   |
| `fio` (best batching: B=16) | 205,278.8 ± 64,687.3 | 801.9 ± 252.7 MB/s | baseline          | `--iodepth_batch_submit=16` |
| `FileChannel.write`         | 72,409.4  ± 1,577.1  | 282.8 ± 6.2 MB/s   | -64.7%            | QD=1                        |
| JUring (JMH)                | 193,768.7 ± 1,678.7  | 756.9 ± 6.6 MB/s   | -5.6%             | Batched `submitAndWait(1)`  |

## Virtual Thread Performance

When a virtual thread performs direct I/O with `FileChannel`, the carrier thread blocks and additional carrier threads can be created to prevent a performance degradation (up to 256 by default). To compare both approaches, both are given an equal budget of platform threads. `JUringBlocking` runs dedicated poller threads that submit requests and wake virtual threads via `LockSupport.unpark()` when completions arrive.

The following results run 1,000 virtual threads under a fixed platform thread budget:

| Platform Thread Budget | Engine         | Thread Breakdown          | Throughput (ops/s)    | Bandwidth            | Speedup  |
|------------------------|----------------|---------------------------|-----------------------|----------------------|----------|
| 1–2 Threads (Min)      | FileChannel    | 1 carrier                 | 11,433.8 ± 31.5       | 44.7 ± 0.1 MB/s      | baseline |
| 1–2 Threads (Min)      | JUringBlocking | 1 carrier + 1 poller (2T) | 399,108.7 ± 89,551.6  | 1,559.0 ± 349.8 MB/s | 34.91x   |
| 4 Threads              | FileChannel    | 4 carriers                | 43,080.4 ± 220.6      | 168.3 ± 0.9 MB/s     | baseline |
| 4 Threads              | JUringBlocking | 2 carriers + 2 pollers    | 452,192.8 ± 1,262.9   | 1,766.4 ± 4.9 MB/s   | 10.50x   |
| 8 Threads              | FileChannel    | 8 carriers                | 73,003.5 ± 23,547.5   | 285.2 ± 92.0 MB/s    | baseline |
| 8 Threads              | JUringBlocking | 4 carriers + 4 pollers    | 389,340.6 ± 158,626.3 | 1,520.9 ± 619.6 MB/s | 5.33x    |
| 16 Threads             | FileChannel    | 16 carriers               | 137,326.1 ± 32,534.5  | 536.4 ± 127.1 MB/s   | baseline |
| 16 Threads             | JUringBlocking | 8 carriers + 8 pollers    | 443,592.3 ± 40,401.8  | 1,732.8 ± 157.8 MB/s | 3.23x    |

`JUringBlocking` uses dedicated poller threads to submit queue entries and wake parked virtual threads when operations complete. Full details and multiple workload datasets are documented in [BENCHMARK.md](BENCHMARK.md).

## Examples

### Direct Read with Registered Files and Fixed Buffers

In this example we register both the file descriptor and a pool of direct memory buffers with the kernel. This avoids buffer mapping overhead per read:

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

### Direct Write

Writing data using registered files and fixed buffers:

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
                     LinuxOpenOptions.CREATE, LinuxOpenOptions.WRITE_DIRECT)) {

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

### Pipeline Loop (Fused Submit & Reap)

Here is how you can build an asynchronous pipeline that keeps the queue full while reaping completions in batches:

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

### Buffered I/O with MemorySegments and Byte Arrays

You can also perform standard buffered reads and writes using off-heap `MemorySegment`s or heap byte arrays:

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
             FileDescriptor fd = new FileDescriptor(Path.of("output.txt"), LinuxOpenOptions.CREATE, LinuxOpenOptions.TRUNCATE, LinuxOpenOptions.WRITE);
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

### Virtual Threads with JUringBlocking

If you want a synchronous-style blocking API that works cleanly with Virtual Threads, use `JUringBlocking`. The virtual thread unmounts while waiting for kernel completions, letting its carrier thread execute other tasks:

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

                    long bytesRead = blockingRing.read(fd, buf, 0);
                    System.out.println("Virtual thread read " + bytesRead + " bytes");
                }
            });
            vt.join();
        }
    }
}
```