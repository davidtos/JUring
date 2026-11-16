package com.davidvlijmincx.lio.api;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the {@code JUringBlocking} allocation claim.
 *
 * <p>The blocking read/write methods used to end in a {@code SqeOptions...} parameter, so every
 * call allocated an empty {@code SqeOptions[]} at the call site (measured: 16 bytes/op on the
 * caller thread). The exact-arity overloads delegate with a shared empty array; this test pins
 * the caller-thread allocation of the no-option hot path below 4 bytes/op.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JUringBlockingZeroAllocationTest {

    private static final int WARMUP_OPS = 20_000;
    private static final int MEASURED_OPS = 20_000;

    private static final ThreadMXBean THREAD_BEAN;

    static {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean sunBean)) {
            throw new UnsupportedOperationException("com.sun.management.ThreadMXBean not available");
        }
        if (!sunBean.isThreadAllocatedMemorySupported()) {
            throw new UnsupportedOperationException("Thread allocated memory measurement is not supported");
        }
        if (!sunBean.isThreadAllocatedMemoryEnabled()) {
            sunBean.setThreadAllocatedMemoryEnabled(true);
        }
        THREAD_BEAN = sunBean;
    }

    @Test
    void noOptionBlockingReadDoesNotAllocate(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("zero-alloc-read.bin");
        Files.write(file, new byte[1 << 20]);

        try (Arena arena = Arena.ofConfined();
             FileDescriptor fd = new FileDescriptor(file, READ);
             JUringBlocking io = new JUringBlocking(64, 1)) {
            MemorySegment buffer = arena.allocate(4096, 4096);

            for (int i = 0; i < WARMUP_OPS; i++) {
                io.read(fd, buffer, 0);
            }

            long threadId = Thread.currentThread().threadId();
            long before = THREAD_BEAN.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < MEASURED_OPS; i++) {
                io.read(fd, buffer, 0);
            }
            long after = THREAD_BEAN.getThreadAllocatedBytes(threadId);

            double bytesPerOp = (after - before) / (double) MEASURED_OPS;
            assertTrue(bytesPerOp < 4.0,
                    () -> "no-option blocking read must be allocation-free, measured " + bytesPerOp + " bytes/op");
        }
    }

    @Test
    void noOptionBlockingFixedReadDoesNotAllocate(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("zero-alloc-fixed-read.bin");
        Files.write(file, new byte[1 << 20]);

        try (FileDescriptor fd = new FileDescriptor(file, READ);
             JUringBlocking io = new JUringBlocking(64, 1)) {
            io.registerBuffers(4096, 1);

            for (int i = 0; i < WARMUP_OPS; i++) {
                io.readFixed(fd, 4096, 0, 0);
            }

            long threadId = Thread.currentThread().threadId();
            long before = THREAD_BEAN.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < MEASURED_OPS; i++) {
                io.readFixed(fd, 4096, 0, 0);
            }
            long after = THREAD_BEAN.getThreadAllocatedBytes(threadId);

            double bytesPerOp = (after - before) / (double) MEASURED_OPS;
            assertTrue(bytesPerOp < 4.0,
                    () -> "no-option blocking fixed read must be allocation-free, measured " + bytesPerOp + " bytes/op");
        }
    }
}
