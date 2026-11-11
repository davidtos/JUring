package com.davidvlijmincx.lio.blackpanda;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackPandaZeroAllocationTest {

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

    private static long getThreadAllocatedBytes(long threadId) {
        return THREAD_BEAN.getThreadAllocatedBytes(threadId);
    }

    @Test
    void testZeroAllocationTailReadHotPath() {
        int capacity = 1024;
        try (PartitionTailBuffer tailBuffer = new PartitionTailBuffer(capacity)) {
            try (Arena arena = Arena.ofShared()) {
                MemorySegment src = arena.allocate(1024);
                MemorySegment dst = arena.allocate(1024);

                for (int i = 0; i < 500; i++) {
                    src.set(ValueLayout.JAVA_INT, 0, i);
                    tailBuffer.publish((long) i, 1, 1000L + i, src, 0L, 1024);
                }

                long threadId = Thread.currentThread().threadId();
                int zeroCount = 0;

                // JIT warmup and verification
                for (int w = 0; w < 30; w++) {
                    long b1 = getThreadAllocatedBytes(threadId);
                    for (int i = 0; i < 20_000; i++) {
                        long target = 100 + (i % 200);
                        int read = tailBuffer.read(target, dst);
                        if (read <= 0) {
                            throw new IllegalStateException("Failed to read offset " + target);
                        }
                    }
                    long b2 = getThreadAllocatedBytes(threadId);
                    long allocated = b2 - b1;
                    if (allocated == 0L) {
                        if (++zeroCount >= 3) break;
                    } else {
                        zeroCount = 0;
                    }
                }

                assertTrue(zeroCount >= 3, "Tail-read path must achieve 0.000 heap bytes allocated");

                // Final assertion on zero allocation over 50,000 reads
                long before = getThreadAllocatedBytes(threadId);
                for (int i = 0; i < 50_000; i++) {
                    long target = 100 + (i % 200);
                    tailBuffer.read(target, dst);
                }
                long after = getThreadAllocatedBytes(threadId);

                assertEquals(0L, after - before, "Strict 0 heap bytes allocated during 50,000 tail-reads");
            }
        }
    }

    @Test
    void testZeroAllocationAppendHotPath(@TempDir Path tempDir) {
        // Test zero allocation on the append path
        long segmentSize = 512L * 1024L * 1024L; // 512 MB
        try (UringPartitionLog log = new UringPartitionLog(tempDir, segmentSize, 4096, 16384, 4 * 1024 * 1024)) {
            try (Arena arena = Arena.ofShared()) {
                MemorySegment payload = arena.allocate(1024);
                payload.fill((byte) 0x7E);

                long threadId = Thread.currentThread().threadId();
                int zeroCount = 0;

                // JIT warmup
                for (int w = 0; w < 30; w++) {
                    long b1 = getThreadAllocatedBytes(threadId);
                    for (int i = 0; i < 2000; i++) {
                        log.append(payload, 1000L);
                    }
                    long b2 = getThreadAllocatedBytes(threadId);
                    long allocated = b2 - b1;
                    if (allocated == 0L) {
                        if (++zeroCount >= 3) break;
                    } else {
                        zeroCount = 0;
                    }
                }

                assertTrue(zeroCount >= 3, "Append path must achieve 0.000 heap bytes allocated");

                // Final verification
                long before = getThreadAllocatedBytes(threadId);
                for (int i = 0; i < 5000; i++) {
                    log.append(payload, 1000L);
                }
                long after = getThreadAllocatedBytes(threadId);

                assertEquals(0L, after - before, "Strict 0 heap bytes allocated during append hot path");
            }
        }
    }
}
