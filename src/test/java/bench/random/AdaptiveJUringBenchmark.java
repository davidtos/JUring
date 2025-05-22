package bench.random;

import bench.random.write.RandomWriteTaskCreator;
import com.davidvlijmincx.lio.api.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.openjdk.jmh.annotations.Threads.MAX;

/**
 * A benchmark that adapts its strategy based on buffer size to achieve optimal performance
 * for both small and large buffer I/O operations.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2300)
@Fork(value = 3, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(MAX)
public class AdaptiveJUringBenchmark {

    /**
     * Execution state for adaptive JUring benchmark.
     */
    @State(Scope.Thread)
    public static class AdaptiveExecutionPlan {
        public JUring jUring;
        public boolean isRunning = true;
        public Thread pollerThread;
        public final Queue<IoResult> completionQueue = new ConcurrentLinkedQueue<>();
        public final CountDownLatch pollerInitialized = new CountDownLatch(1);
        public AtomicInteger pendingOperations = new AtomicInteger(0);

        @Setup
        public void setup() {
            jUring = new JUring(2500);
            // Start a dedicated poller thread
            startPoller();
            try {
                // Wait for poller to initialize
                pollerInitialized.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        private void startPoller() {
            pollerThread = Thread.ofPlatform().daemon(true).start(() -> {
                pollerInitialized.countDown();

                while (isRunning) {
                    if (pendingOperations.get() > 0) {
                        // Process completions in batches
                        List<IoResult> results = jUring.peekForBatchResult(100);
                        if (!results.isEmpty()) {
                            completionQueue.addAll(results);
                            pendingOperations.addAndGet(-results.size());
                        }
                    }

                    // Dynamic sleep based on pending operations
                    try {
                        if (pendingOperations.get() > 1000) {
                            // Poll more aggressively when many operations are pending
                            Thread.sleep(0, 100);
                        } else if (pendingOperations.get() > 0) {
                            Thread.sleep(0, 500);
                        } else {
                            Thread.sleep(1); // Sleep longer when idle
                        }
                    } catch (InterruptedException e) {
                        if (!isRunning) break;
                    }
                }
            });
        }

        @TearDown
        public void tearDown() {
            isRunning = false;
            try {
                pollerThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            jUring.close();
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(AdaptiveJUringBenchmark.class.getSimpleName())
                .forks(1)
                .shouldDoGC(false)
                .build();

        new Runner(opt).run();
    }

    /**
     * Adaptive JUring benchmark that uses different strategies based on buffer size.
     */
    @Benchmark
    public void adaptiveJUringWrite(AdaptiveExecutionPlan plan, RandomWriteTaskCreator taskCreator, Blackhole blackhole) {
        final var jUring = plan.jUring;
        final var writeTasks = taskCreator.getRandomWriteTasks();
        final int bufferSize = taskCreator.content.length;
        final List<FileDescriptor> openFiles = new ArrayList<>(writeTasks.length);

        // Determine optimal batch size based on buffer size
        final int batchSize = getBatchSizeForBuffer(bufferSize);
        final boolean isLargeBuffer = bufferSize > 8192;

        try {
            // Prepare operations
            int batchCounter = 0;
            int submittedOps = 0;

            for (int i = 0; i < writeTasks.length; i++) {
                FileDescriptor fd = new FileDescriptor(writeTasks[i].sPath(), Flag.WRITE, 0);
                openFiles.add(fd);

                long id = jUring.prepareWrite(fd, taskCreator.content, writeTasks[i].offset());
                batchCounter++;

                // For large buffers, submit more frequently to reduce memory pressure
                if (batchCounter >= batchSize) {
                    jUring.submit();
                    plan.pendingOperations.addAndGet(batchCounter);
                    submittedOps += batchCounter;
                    batchCounter = 0;

                    // For large buffers, wait for some completions to reduce memory pressure
                    if (isLargeBuffer) {
                        waitForSomeCompletions(plan, submittedOps / 4, blackhole);
                    }
                }
            }

            // Submit any remaining operations
            if (batchCounter > 0) {
                jUring.submit();
                plan.pendingOperations.addAndGet(batchCounter);
                submittedOps += batchCounter;
            }

            // Wait for all completions
            waitForAllCompletions(plan, submittedOps, blackhole);

        } finally {
            // Close file descriptors
            for (FileDescriptor fd : openFiles) {
                fd.close();
            }
        }
    }

    // Adaptive read benchmark
    @Benchmark
    public void adaptiveJUringRead(AdaptiveExecutionPlan plan, Blackhole blackhole,
                                   bench.random.read.RandomReadTaskCreator readTaskCreator) {
        final var jUring = plan.jUring;
        final var readTasks = readTaskCreator.getRandomReadTasks();
        final int bufferSize = bench.random.read.RandomReadTaskCreator.readSize;
        final int batchSize = getBatchSizeForBuffer(bufferSize);
        final boolean isLargeBuffer = bufferSize > 8192;
        final List<FileDescriptor> openFiles = new ArrayList<>(readTasks.length);

        try {
            // Prepare operations
            int batchCounter = 0;
            int submittedOps = 0;

            for (int i = 0; i < readTasks.length; i++) {
                FileDescriptor fd = new FileDescriptor(readTasks[i].sPath(), Flag.READ, 0);
                openFiles.add(fd);

                jUring.prepareRead(fd, readTasks[i].bufferSize(), readTasks[i].offset());
                batchCounter++;

                if (batchCounter >= batchSize) {
                    jUring.submit();
                    plan.pendingOperations.addAndGet(batchCounter);
                    submittedOps += batchCounter;
                    batchCounter = 0;

                    // For large buffers, process some completions to reduce memory pressure
                    if (isLargeBuffer) {
                        consumeReadResults(plan, submittedOps / 4, blackhole);
                    }
                }
            }

            // Submit any remaining operations
            if (batchCounter > 0) {
                jUring.submit();
                plan.pendingOperations.addAndGet(batchCounter);
                submittedOps += batchCounter;
            }

            // Process all completions
            consumeReadResults(plan, submittedOps, blackhole);

        } finally {
            // Close file descriptors
            for (FileDescriptor fd : openFiles) {
                fd.close();
            }
        }
    }

    /**
     * Determines the optimal batch size based on buffer size.
     */
    private int getBatchSizeForBuffer(int bufferSize) {
        // Larger batches for smaller buffers, smaller batches for larger buffers
        if (bufferSize <= 512) return 250;
        if (bufferSize <= 4096) return 150;
        if (bufferSize <= 16384) return 50;
        return 20; // For very large buffers
    }

    /**
     * Waits for some completions to reduce memory pressure.
     */
    private void waitForSomeCompletions(AdaptiveExecutionPlan plan, int count, Blackhole blackhole) {
        int processed = 0;

        // Try for a limited time to get the desired number of completions
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5);

        while (processed < count && System.nanoTime() < deadline) {
            IoResult result;
            while ((result = plan.completionQueue.poll()) != null && processed < count) {
                blackhole.consume(result.id());
                processed++;
            }

            if (processed < count) {
                Thread.onSpinWait(); // Efficient busy-wait
            }
        }
    }

    /**
     * Waits for all completions.
     */
    private void waitForAllCompletions(AdaptiveExecutionPlan plan, int expected, Blackhole blackhole) {
        int processed = 0;

        // First drain the completion queue
        IoResult result;
        while ((result = plan.completionQueue.poll()) != null) {
            blackhole.consume(result.id());
            processed++;
        }

        // If we haven't processed all operations, wait for them
        while (processed < expected) {
            // Poll until we get all completions
            if (plan.completionQueue.isEmpty() && plan.pendingOperations.get() > 0) {
                Thread.onSpinWait();
                continue;
            }

            while ((result = plan.completionQueue.poll()) != null) {
                blackhole.consume(result.id());
                processed++;
                if (processed >= expected) break;
            }
        }
    }

    /**
     * Processes read results and consumes buffers.
     */
    private void consumeReadResults(AdaptiveExecutionPlan plan, int count, Blackhole blackhole) {
        int processed = 0;

        // Try for a limited time to get the desired number of completions
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5);

        while (processed < count && System.nanoTime() < deadline) {
            IoResult result;
            while ((result = plan.completionQueue.poll()) != null && processed < count) {
                if (OperationType.READ.equals(result.type())) {
                    blackhole.consume(result.readBuffer());
                    result.freeBuffer();
                }
                processed++;
            }

            if (processed < count) {
                Thread.onSpinWait(); // Efficient busy-wait
            }
        }
    }
}
