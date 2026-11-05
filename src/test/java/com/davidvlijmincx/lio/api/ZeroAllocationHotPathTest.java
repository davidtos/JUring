package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;
import static org.junit.jupiter.api.Assertions.*;

class ZeroAllocationHotPathTest {

    private static final int BLOCK_SIZE = 4096;
    private static final int FILE_BLOCKS = 256; // 1 MB file
    private static final int BENCHMARK_OPS = 100_000;

    private static final Object THREAD_BEAN;
    private static final MethodHandle GET_ALLOCATED_BYTES_MH;

    static {
        try {
            Class<?> mfClass = Class.forName("java.lang.management.ManagementFactory");
            Method getThreadMXBean = mfClass.getMethod("getThreadMXBean");
            Object bean = getThreadMXBean.invoke(null);

            Class<?> sunThreadMXBeanClass = Class.forName("com.sun.management.ThreadMXBean");
            if (!sunThreadMXBeanClass.isInstance(bean)) {
                throw new UnsupportedOperationException("com.sun.management.ThreadMXBean not available");
            }

            Method isSupported = sunThreadMXBeanClass.getMethod("isThreadAllocatedMemorySupported");
            boolean supported = (boolean) isSupported.invoke(bean);
            if (!supported) {
                throw new UnsupportedOperationException("Thread allocated memory measurement is not supported");
            }

            Method isEnabled = sunThreadMXBeanClass.getMethod("isThreadAllocatedMemoryEnabled");
            boolean enabled = (boolean) isEnabled.invoke(bean);
            if (!enabled) {
                Method setEnabled = sunThreadMXBeanClass.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
                setEnabled.invoke(bean, true);
            }

            THREAD_BEAN = bean;
            Method getBytesMethod = sunThreadMXBeanClass.getMethod("getThreadAllocatedBytes", long.class);
            GET_ALLOCATED_BYTES_MH = MethodHandles.lookup().unreflect(getBytesMethod);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private JUring ring;

    @BeforeEach
    void setUp() {
        ring = new JUring(256,
                IORING_SETUP_SINGLE_ISSUER,
                IORING_SETUP_COOP_TASKRUN,
                IORING_SETUP_DEFER_TASKRUN);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close();
            ring = null;
        }
    }

    private static long getThreadAllocatedBytes(long threadId) {
        try {
            return (long) GET_ALLOCATED_BYTES_MH.invoke(THREAD_BEAN, threadId);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static class ReapingConsumer implements ResultConsumer {
        int count = 0;
        final JUring r;

        ReapingConsumer(JUring r) {
            this.r = r;
        }

        @Override
        public void accept(Result result) {
            if (result.result() < 0) {
                throw new IllegalStateException("Operation failed with errno: " + (-result.result()));
            }
            count++;
            int bufIdx = result.bufferIdx();
            if (bufIdx >= 0) {
                r.checkInBuffer(bufIdx);
            }
        }
    }

    @Test
    void testHotPathNopBatchedZeroAllocations() {
        final int qd = 64;
        final int submitBatch = 32;
        final ReapingConsumer consumer = new ReapingConsumer(ring);
        long threadId = Thread.currentThread().threadId();

        // Warmup until steady-state C2 compilation (0 allocations over batch)
        int zeroCount = 0;
        for (int w = 0; w < 30; w++) {
            consumer.count = 0;
            long b1 = getThreadAllocatedBytes(threadId);
            runNopPipeline(40_000, qd, submitBatch, consumer);
            long b2 = getThreadAllocatedBytes(threadId);
            if (b2 - b1 == 0L) {
                if (++zeroCount >= 3) break;
            } else {
                zeroCount = 0;
            }
        }
        assertTrue(zeroCount >= 3, "Warmup must achieve steady-state 0 allocations before benchmarking");
        consumer.count = 0;

        // Measurement phase
        long bytesBefore = getThreadAllocatedBytes(threadId);
        runNopPipeline(BENCHMARK_OPS, qd, submitBatch, consumer);
        long bytesAfter = getThreadAllocatedBytes(threadId);
        long allocated = bytesAfter - bytesBefore;

        assertEquals(BENCHMARK_OPS, consumer.count, "Must have reaped exactly all benchmark operations");
        assertEquals(0L, allocated,
                String.format("Hot path NOP pipeline must allocate 0 heap bytes, but allocated %,d bytes across %,d ops",
                        allocated, BENCHMARK_OPS));
    }

    @Test
    void testHotPathNopSubmitAndWaitZeroAllocations() {
        final int qd = 32;
        final ReapingConsumer consumer = new ReapingConsumer(ring);
        long threadId = Thread.currentThread().threadId();

        // Warmup until steady-state C2 compilation
        int zeroCount = 0;
        for (int w = 0; w < 30; w++) {
            consumer.count = 0;
            long b1 = getThreadAllocatedBytes(threadId);
            runNopSubmitAndWait(40_000, qd, consumer);
            long b2 = getThreadAllocatedBytes(threadId);
            if (b2 - b1 == 0L) {
                if (++zeroCount >= 3) break;
            } else {
                zeroCount = 0;
            }
        }
        assertTrue(zeroCount >= 3, "Warmup must achieve steady-state 0 allocations before benchmarking");
        consumer.count = 0;

        long bytesBefore = getThreadAllocatedBytes(threadId);
        runNopSubmitAndWait(BENCHMARK_OPS, qd, consumer);
        long bytesAfter = getThreadAllocatedBytes(threadId);
        long allocated = bytesAfter - bytesBefore;

        assertEquals(BENCHMARK_OPS, consumer.count, "Must have reaped exactly all benchmark operations");
        assertEquals(0L, allocated,
                String.format("Hot path NOP submitAndWait must allocate 0 heap bytes, but allocated %,d bytes across %,d ops",
                        allocated, BENCHMARK_OPS));
    }

    @Test
    void testHotPathDirectReadZeroAllocations(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("direct_read_zero_alloc.bin");
        prepareTestFile(testFile, FILE_BLOCKS * BLOCK_SIZE);

        final int qd = 64;
        ring.registerBuffers(BLOCK_SIZE, qd);

        try (FileDescriptor fd = new FileDescriptor(testFile, LinuxOpenOptions.READ_DIRECT)) {
            ring.registerFiles(fd);
            final ReapingConsumer consumer = new ReapingConsumer(ring);
            long threadId = Thread.currentThread().threadId();

            // Warm up until steady-state
            int zeroCount = 0;
            for (int w = 0; w < 30; w++) {
                consumer.count = 0;
                long b1 = getThreadAllocatedBytes(threadId);
                runReadPipeline(40_000, qd, consumer);
                long b2 = getThreadAllocatedBytes(threadId);
                if (b2 - b1 == 0L) {
                    if (++zeroCount >= 3) break;
                } else {
                    zeroCount = 0;
                }
            }
            assertTrue(zeroCount >= 3, "Warmup must achieve steady-state 0 allocations before benchmarking");
            consumer.count = 0;

            long bytesBefore = getThreadAllocatedBytes(threadId);
            runReadPipeline(BENCHMARK_OPS, qd, consumer);
            long bytesAfter = getThreadAllocatedBytes(threadId);
            long allocated = bytesAfter - bytesBefore;

            assertEquals(BENCHMARK_OPS, consumer.count, "Must have reaped exactly all benchmark operations");
            assertEquals(0L, allocated,
                    String.format("Hot path Direct Read must allocate 0 heap bytes, but allocated %,d bytes across %,d ops",
                            allocated, BENCHMARK_OPS));
        }
    }

    @Test
    void testHotPathDirectWriteZeroAllocations(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("direct_write_zero_alloc.bin");
        prepareTestFile(testFile, FILE_BLOCKS * BLOCK_SIZE);

        final int qd = 64;
        ring.registerBuffers(BLOCK_SIZE, qd);

        try (FileDescriptor fd = new FileDescriptor(testFile, LinuxOpenOptions.WRITE_DIRECT, LinuxOpenOptions.READ_DIRECT)) {
            ring.registerFiles(fd);
            final ReapingConsumer consumer = new ReapingConsumer(ring);
            long threadId = Thread.currentThread().threadId();

            // Warm up until steady-state
            int zeroCount = 0;
            for (int w = 0; w < 30; w++) {
                consumer.count = 0;
                long b1 = getThreadAllocatedBytes(threadId);
                runWritePipeline(40_000, qd, consumer);
                long b2 = getThreadAllocatedBytes(threadId);
                if (b2 - b1 == 0L) {
                    if (++zeroCount >= 3) break;
                } else {
                    zeroCount = 0;
                }
            }
            assertTrue(zeroCount >= 3, "Warmup must achieve steady-state 0 allocations before benchmarking");
            consumer.count = 0;

            long bytesBefore = getThreadAllocatedBytes(threadId);
            runWritePipeline(BENCHMARK_OPS, qd, consumer);
            long bytesAfter = getThreadAllocatedBytes(threadId);
            long allocated = bytesAfter - bytesBefore;

            assertEquals(BENCHMARK_OPS, consumer.count, "Must have reaped exactly all benchmark operations");
            assertEquals(0L, allocated,
                    String.format("Hot path Direct Write must allocate 0 heap bytes, but allocated %,d bytes across %,d ops",
                            allocated, BENCHMARK_OPS));
        }
    }

    @Test
    void testHotPathNopSingleWaitForResultZeroAllocations() {
        final Result reusableResult = new Result();
        long threadId = Thread.currentThread().threadId();

        int zeroCount = 0;
        for (int w = 0; w < 30; w++) {
            long b1 = getThreadAllocatedBytes(threadId);
            runNopSingle(40_000, reusableResult);
            long b2 = getThreadAllocatedBytes(threadId);
            if (b2 - b1 == 0L) {
                if (++zeroCount >= 3) break;
            } else {
                zeroCount = 0;
            }
        }
        assertTrue(zeroCount >= 3, "Warmup must achieve steady-state 0 allocations before benchmarking");

        long bytesBefore = getThreadAllocatedBytes(threadId);
        runNopSingle(BENCHMARK_OPS, reusableResult);
        long bytesAfter = getThreadAllocatedBytes(threadId);
        long allocated = bytesAfter - bytesBefore;

        assertEquals(0L, allocated,
                String.format("Hot path unbatched NOP must allocate 0 heap bytes, but allocated %,d bytes across %,d ops",
                        allocated, BENCHMARK_OPS));
    }

    @Test
    void testHotPathDirectReadSingleZeroAllocations(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("direct_read_single_zero_alloc.bin");
        prepareTestFile(testFile, FILE_BLOCKS * BLOCK_SIZE);

        ring.registerBuffers(BLOCK_SIZE, 1);

        try (FileDescriptor fd = new FileDescriptor(testFile, LinuxOpenOptions.READ_DIRECT)) {
            ring.registerFiles(fd);
            final Result reusableResult = new Result();
            long threadId = Thread.currentThread().threadId();

            int zeroCount = 0;
            for (int w = 0; w < 30; w++) {
                long b1 = getThreadAllocatedBytes(threadId);
                runReadSingle(40_000, reusableResult);
                long b2 = getThreadAllocatedBytes(threadId);
                if (b2 - b1 == 0L) {
                    if (++zeroCount >= 3) break;
                } else {
                    zeroCount = 0;
                }
            }
            assertTrue(zeroCount >= 3, "Warmup must achieve steady-state 0 allocations before benchmarking");

            long bytesBefore = getThreadAllocatedBytes(threadId);
            runReadSingle(BENCHMARK_OPS, reusableResult);
            long bytesAfter = getThreadAllocatedBytes(threadId);
            long allocated = bytesAfter - bytesBefore;

            assertEquals(0L, allocated,
                    String.format("Hot path unbatched Direct Read must allocate 0 heap bytes, but allocated %,d bytes across %,d ops",
                            allocated, BENCHMARK_OPS));
        }
    }

    @Test
    void testHotPathDirectWriteSingleZeroAllocations(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("direct_write_single_zero_alloc.bin");
        prepareTestFile(testFile, FILE_BLOCKS * BLOCK_SIZE);

        ring.registerBuffers(BLOCK_SIZE, 1);

        try (FileDescriptor fd = new FileDescriptor(testFile, LinuxOpenOptions.WRITE_DIRECT, LinuxOpenOptions.READ_DIRECT)) {
            ring.registerFiles(fd);
            final Result reusableResult = new Result();
            long threadId = Thread.currentThread().threadId();

            int zeroCount = 0;
            for (int w = 0; w < 30; w++) {
                long b1 = getThreadAllocatedBytes(threadId);
                runWriteSingle(40_000, reusableResult);
                long b2 = getThreadAllocatedBytes(threadId);
                if (b2 - b1 == 0L) {
                    if (++zeroCount >= 3) break;
                } else {
                    zeroCount = 0;
                }
            }
            assertTrue(zeroCount >= 3, "Warmup must achieve steady-state 0 allocations before benchmarking");

            long bytesBefore = getThreadAllocatedBytes(threadId);
            runWriteSingle(BENCHMARK_OPS, reusableResult);
            long bytesAfter = getThreadAllocatedBytes(threadId);
            long allocated = bytesAfter - bytesBefore;

            assertEquals(0L, allocated,
                    String.format("Hot path unbatched Direct Write must allocate 0 heap bytes, but allocated %,d bytes across %,d ops",
                            allocated, BENCHMARK_OPS));
        }
    }

    private void runNopPipeline(int totalTasks, int qd, int submitBatch, ReapingConsumer consumer) {
        int submitted = 0;
        int unsubmitted = 0;
        int initialCount = consumer.count;

        while ((consumer.count - initialCount) < totalTasks) {
            while ((submitted - (consumer.count - initialCount)) < qd && submitted < totalTasks) {
                ring.prepareNop();
                submitted++;
                unsubmitted++;

                if (unsubmitted >= submitBatch) {
                    ring.submit();
                    unsubmitted = 0;
                    ring.peekForBatchResult(qd, consumer);
                }
            }

            int reaped = ring.peekForBatchResult(qd, consumer);
            if (reaped == 0 && (consumer.count - initialCount) < totalTasks) {
                ring.submitAndWaitForBatchResult(1, qd, consumer);
                unsubmitted = 0;
            }
        }
    }

    private void runNopSubmitAndWait(int totalTasks, int batchSize, ReapingConsumer consumer) {
        int submitted = 0;
        int initialCount = consumer.count;

        while ((consumer.count - initialCount) < totalTasks) {
            int toSubmit = Math.min(batchSize, totalTasks - submitted);
            for (int i = 0; i < toSubmit; i++) {
                ring.prepareNop();
                submitted++;
            }
            ring.submitAndWaitForBatchResult(toSubmit, toSubmit, consumer);
        }
    }

    private void runReadPipeline(int totalTasks, int qd, ReapingConsumer consumer) {
        int submitted = 0;
        int inFlight = 0;
        int initialCount = consumer.count;

        while ((consumer.count - initialCount) < totalTasks) {
            while (inFlight < qd && submitted < totalTasks) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) {
                    break;
                }
                long offset = (long) (submitted % FILE_BLOCKS) * BLOCK_SIZE;
                ring.prepareReadFixed(0, BLOCK_SIZE, offset, bufIdx);
                submitted++;
                inFlight++;
            }

            if (inFlight > 0) {
                ring.submit();
            }

            int beforeReap = consumer.count;
            int reaped = ring.peekForBatchResult(qd, consumer);
            if (reaped == 0 && (consumer.count - initialCount) < totalTasks) {
                ring.submitAndWaitForBatchResult(1, qd, consumer);
            }
            int justReaped = consumer.count - beforeReap;
            inFlight -= justReaped;
        }
    }

    private void runWritePipeline(int totalTasks, int qd, ReapingConsumer consumer) {
        int submitted = 0;
        int inFlight = 0;
        int initialCount = consumer.count;

        while ((consumer.count - initialCount) < totalTasks) {
            while (inFlight < qd && submitted < totalTasks) {
                int bufIdx = ring.checkOutBuffer();
                if (bufIdx < 0) {
                    break;
                }
                long offset = (long) (submitted % FILE_BLOCKS) * BLOCK_SIZE;
                ring.prepareWriteFixed(0, BLOCK_SIZE, offset, bufIdx);
                submitted++;
                inFlight++;
            }

            if (inFlight > 0) {
                ring.submit();
            }

            int beforeReap = consumer.count;
            int reaped = ring.peekForBatchResult(qd, consumer);
            if (reaped == 0 && (consumer.count - initialCount) < totalTasks) {
                ring.submitAndWaitForBatchResult(1, qd, consumer);
            }
            int justReaped = consumer.count - beforeReap;
            inFlight -= justReaped;
        }
    }

    private void runNopSingle(int totalTasks, Result reusableResult) {
        for (int i = 0; i < totalTasks; i++) {
            ring.prepareNop();
            ring.submit();
            Result res = ring.waitForResult(reusableResult);
            if (res.result() != 0) {
                throw new IllegalStateException("NOP failed: " + res.result());
            }
        }
    }

    private void runReadSingle(int totalTasks, Result reusableResult) {
        for (int i = 0; i < totalTasks; i++) {
            long offset = (long) (i % FILE_BLOCKS) * BLOCK_SIZE;
            ring.prepareReadFixed(0, BLOCK_SIZE, offset, 0);
            ring.submit();
            Result res = ring.waitForResult(reusableResult);
            if (res.result() != BLOCK_SIZE) {
                throw new IllegalStateException("Direct read failed: " + res.result());
            }
        }
    }

    private void runWriteSingle(int totalTasks, Result reusableResult) {
        for (int i = 0; i < totalTasks; i++) {
            long offset = (long) (i % FILE_BLOCKS) * BLOCK_SIZE;
            ring.prepareWriteFixed(0, BLOCK_SIZE, offset, 0);
            ring.submit();
            Result res = ring.waitForResult(reusableResult);
            if (res.result() != BLOCK_SIZE) {
                throw new IllegalStateException("Direct write failed: " + res.result());
            }
        }
    }

    private static void prepareTestFile(Path path, int sizeBytes) throws IOException {
        byte[] chunk = new byte[BLOCK_SIZE];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i & 0x7F);
        }
        try (var out = Files.newOutputStream(path)) {
            int written = 0;
            while (written < sizeBytes) {
                out.write(chunk);
                written += chunk.length;
            }
        }
    }
}
