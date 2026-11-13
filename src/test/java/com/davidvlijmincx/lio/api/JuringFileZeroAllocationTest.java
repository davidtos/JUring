package com.davidvlijmincx.lio.api;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-allocation hot-path test for {@link JuringFile}: registered file +
 * registered fixed buffer, {@code prepareReadFixed}/{@code prepareWriteFixed} +
 * {@code await}. Mirrors {@link ZeroAllocationHotPathTest}: warm up until three
 * consecutive rounds allocate exactly 0 bytes, then run 100_000 measured operations
 * and require exactly 0 heap bytes allocated on the owning thread.
 */
class JuringFileZeroAllocationTest {

    private static final int BLOCK_SIZE = 4096;
    private static final int FILE_BLOCKS = 256; // 1 MiB file
    private static final int WARMUP_OPS = 10_000;
    private static final int MEASURED_OPS = 100_000;

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

    private JUring ring;
    private JuringFile file;

    @BeforeEach
    void setUp() {
        ring = new JUring(64,
                IORING_SETUP_SINGLE_ISSUER,
                IORING_SETUP_COOP_TASKRUN,
                IORING_SETUP_DEFER_TASKRUN);
    }

    @AfterEach
    void tearDown() {
        if (file != null) {
            file.close();
            file = null;
        }
        if (ring != null) {
            ring.close();
            ring = null;
        }
    }

    private static long getThreadAllocatedBytes(long threadId) {
        return THREAD_BEAN.getThreadAllocatedBytes(threadId);
    }

    @Test
    void testReadFixedHotPathZeroAllocations(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("juringfile_read_zero_alloc.bin");
        Files.write(path, expected(0, FILE_BLOCKS * BLOCK_SIZE));

        ring.registerBuffers(BLOCK_SIZE, 16);
        file = JuringFile.open(ring, path, READ);
        assertTrue(file.register() >= 0, "register() must provide the fixed-file fast path");

        long threadId = Thread.currentThread().threadId();
        int zeroCount = 0;
        for (int w = 0; w < 30; w++) {
            long before = getThreadAllocatedBytes(threadId);
            runReadFixed(WARMUP_OPS);
            long after = getThreadAllocatedBytes(threadId);
            if (after - before == 0L) {
                if (++zeroCount >= 3) {
                    break;
                }
            } else {
                zeroCount = 0;
            }
        }
        assertTrue(zeroCount >= 3, "warmup must reach 3 consecutive 0-allocation rounds before measuring");

        long bytesBefore = getThreadAllocatedBytes(threadId);
        long bytesRead = runReadFixed(MEASURED_OPS);
        long bytesAfter = getThreadAllocatedBytes(threadId);
        long allocated = bytesAfter - bytesBefore;

        assertEquals(0L, allocated, "JuringFile read fixed hot path allocated " + allocated + " bytes");
        assertEquals((long) MEASURED_OPS * BLOCK_SIZE, bytesRead);

        // the last operation read block ((MEASURED_OPS-1) % FILE_BLOCKS): verify its content
        long lastOffset = (long) ((MEASURED_OPS - 1) % FILE_BLOCKS) * BLOCK_SIZE;
        MemorySegment buffer = ring.getRegisteredBuffer(0);
        for (int i = 0; i < 256; i++) {
            assertEquals(pattern(lastOffset + i), buffer.get(JAVA_BYTE, i),
                    "content mismatch at read offset " + lastOffset + ", byte " + i);
        }
    }

    @Test
    void testWriteFixedHotPathZeroAllocations(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("juringfile_write_zero_alloc.bin");
        Files.write(path, new byte[FILE_BLOCKS * BLOCK_SIZE]);

        ring.registerBuffers(BLOCK_SIZE, 16);
        file = JuringFile.open(ring, path, RDWR);
        assertTrue(file.register() >= 0, "register() must provide the fixed-file fast path");

        MemorySegment buffer = ring.getRegisteredBuffer(0);
        for (int i = 0; i < BLOCK_SIZE; i++) {
            buffer.set(JAVA_BYTE, i, pattern(i));
        }

        long threadId = Thread.currentThread().threadId();
        int zeroCount = 0;
        for (int w = 0; w < 30; w++) {
            long before = getThreadAllocatedBytes(threadId);
            runWriteFixed(WARMUP_OPS);
            long after = getThreadAllocatedBytes(threadId);
            if (after - before == 0L) {
                if (++zeroCount >= 3) {
                    break;
                }
            } else {
                zeroCount = 0;
            }
        }
        assertTrue(zeroCount >= 3, "warmup must reach 3 consecutive 0-allocation rounds before measuring");

        long bytesBefore = getThreadAllocatedBytes(threadId);
        long bytesWritten = runWriteFixed(MEASURED_OPS);
        long bytesAfter = getThreadAllocatedBytes(threadId);
        long allocated = bytesAfter - bytesBefore;

        assertEquals(0L, allocated, "JuringFile write fixed hot path allocated " + allocated + " bytes");
        assertEquals((long) MEASURED_OPS * BLOCK_SIZE, bytesWritten);

        // the last operation wrote block ((MEASURED_OPS-1) % FILE_BLOCKS) with the fixed pattern
        long lastOffset = (long) ((MEASURED_OPS - 1) % FILE_BLOCKS) * BLOCK_SIZE;
        byte[] fileBytes = Files.readAllBytes(path);
        byte[] lastBlock = new byte[BLOCK_SIZE];
        System.arraycopy(fileBytes, (int) lastOffset, lastBlock, 0, BLOCK_SIZE);
        for (int i = 0; i < BLOCK_SIZE; i++) {
            assertEquals(pattern(i), lastBlock[i],
                    "content mismatch at written offset " + lastOffset + ", byte " + i);
        }
    }

    /** Sequential fixed-buffer reads over the 1 MiB file; returns total bytes read. */
    private long runReadFixed(int ops) throws JuringException {
        long total = 0;
        for (int i = 0; i < ops; i++) {
            long offset = (long) (i % FILE_BLOCKS) * BLOCK_SIZE;
            long handle = file.prepareReadFixed(offset, BLOCK_SIZE, 0);
            int read = file.await(handle);
            if (read != BLOCK_SIZE) {
                throw new IllegalStateException("short fixed read: " + read + " at offset " + offset);
            }
            total += read;
        }
        return total;
    }

    /** Sequential fixed-buffer writes over the 1 MiB file; returns total bytes written. */
    private long runWriteFixed(int ops) throws JuringException {
        long total = 0;
        for (int i = 0; i < ops; i++) {
            long offset = (long) (i % FILE_BLOCKS) * BLOCK_SIZE;
            long handle = file.prepareWriteFixed(offset, BLOCK_SIZE, 0);
            int written = file.await(handle);
            if (written != BLOCK_SIZE) {
                throw new IllegalStateException("short fixed write: " + written + " at offset " + offset);
            }
            total += written;
        }
        return total;
    }

    private static byte pattern(long offset) {
        return (byte) ((offset * 31 + (offset >>> 12) + 7) & 0xFF);
    }

    private static byte[] expected(long offset, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = pattern(offset + i);
        }
        return bytes;
    }
}
