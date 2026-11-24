package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Virtual-thread concurrency on the group constructor. */
@Timeout(30)
class BlockingVtConcurrencyTest {

    @TempDir
    Path tempDir;

    private static RingGroup newGroup() {
        return RingGroup.create(2, ShardOptions.defaults()
                .withName("blocking-vt")
                .withRingEntries(64)
                .withRingOptions(IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN));
    }

    @Test
    void virtualThreadConcurrencyOnGroup() throws Exception {
        Path readPath = tempDir.resolve("read_file");
        Files.write(readPath, "Hello, World!".getBytes(StandardCharsets.UTF_8));

        RingGroup group = newGroup();
        try (JUringBlocking blocking = new JUringBlocking(group);
             FileDescriptor fd = new FileDescriptor(readPath, READ, 0);
             var vts = Executors.newVirtualThreadPerTaskExecutor()) {
            int tasks = 128;
            List<Future<Long>> futures = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                futures.add(vts.submit(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment buffer = arena.allocate(14);
                        long bytesRead = blocking.read(fd, buffer, 14, 0);
                        assertEquals(13L, bytesRead);
                        return bytesRead;
                    }
                }));
            }
            for (Future<Long> future : futures) {
                assertEquals(13L, future.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentWritesAndReadsAcrossShards() throws Exception {
        int tasks = 64;
        Path writePath = tempDir.resolve("write_file");
        Files.write(writePath, new byte[tasks * 16]);

        RingGroup group = newGroup();
        try (JUringBlocking blocking = new JUringBlocking(group);
             FileDescriptor writeFd = new FileDescriptor(writePath, RDWR, 0);
             var vts = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> writes = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                int index = i;
                writes.add(vts.submit(() -> {
                    byte[] payload = payload(index);
                    return blocking.write(writeFd, payload, (long) index * 16);
                }));
            }
            for (Future<Long> write : writes) {
                assertEquals(16L, write.get(10, TimeUnit.SECONDS));
            }

            List<Future<Long>> reads = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                int index = i;
                reads.add(vts.submit(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment buffer = arena.allocate(16);
                        long bytesRead = blocking.read(writeFd, buffer, 16, (long) index * 16);
                        assertEquals(16L, bytesRead);
                        assertEquals(new String(payload(index), StandardCharsets.UTF_8),
                                new String(buffer.toArray(JAVA_BYTE), StandardCharsets.UTF_8));
                        return bytesRead;
                    }
                }));
            }
            for (Future<Long> read : reads) {
                assertEquals(16L, read.get(10, TimeUnit.SECONDS));
            }
        }
    }

    private static byte[] payload(int index) {
        String value = String.format("vt-%013d", index);
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
