package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code new JUringBlocking(RingGroup)} parity with the existing
 * constructors, ownership and constructor validation. Existing {@code JUringBlockingTest}
 * stays untouched.
 */
class BlockingGroupConstructorTest {

    private static final String CONTENT = "Hello, World!"; // 13 bytes

    @TempDir
    Path tempDir;

    private static RingGroup newGroup(int shards) {
        return RingGroup.create(shards, ShardOptions.defaults()
                .withName("blocking-group")
                .withRingEntries(64)
                .withRingOptions(IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN));
    }

    private Path contentFile() throws Exception {
        Path path = tempDir.resolve("read_file");
        Files.write(path, CONTENT.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static String readString(MemorySegment buffer, long bytesRead) {
        return new String(buffer.asSlice(0, bytesRead).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /** Same operation sequence as the existing constructor; returns the observable results. */
    private static long[] exercise(JUringBlocking blocking, Path readPath, Path writePath) throws Exception {
        long[] results = new long[6];
        try (FileDescriptor readFd = new FileDescriptor(readPath, READ, 0)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(14);
                results[0] = blocking.read(readFd, buffer, 14, 0);
                assertEquals(CONTENT, readString(buffer, results[0]));
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(6);
                results[1] = blocking.read(readFd, buffer, 6, 7);
                assertEquals("World!", readString(buffer, results[1]));
            }

            try (FileDescriptor writeFd = new FileDescriptor(writePath, WRITE, 0)) {
                byte[] payload = "parity ok".getBytes(StandardCharsets.UTF_8);
                results[2] = blocking.write(writeFd, payload, 0);
                assertEquals(payload.length, results[2]);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(9);
                try (FileDescriptor writeFd = new FileDescriptor(writePath, READ, 0)) {
                    results[3] = blocking.read(writeFd, buffer, 9, 0);
                }
                assertEquals("parity ok", readString(buffer, results[3]));
            }

            assertEquals(0, blocking.registerFiles(readFd));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(14);
                results[4] = blocking.read(0, buffer, 14, 0);
                assertEquals(CONTENT, readString(buffer, results[4]));
            }

            MemorySegment[] buffers = blocking.registerBuffers(32, 1);
            assertEquals(1, buffers.length);
            results[5] = blocking.readFixed(readFd, 13, 0, 0);
            assertEquals(CONTENT, readString(buffers[0], results[5]));
        }
        return results;
    }

    @Test
    void groupConstructorParityWithExistingConstructor() throws Exception {
        Path readPath = contentFile();
        Path oldWrite = tempDir.resolve("old_write_file");
        Path newWrite = tempDir.resolve("new_write_file");
        Files.write(oldWrite, new byte[0]);
        Files.write(newWrite, new byte[0]);

        long[] oldResults;
        try (JUringBlocking blocking = new JUringBlocking(64,
                IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN)) {
            oldResults = exercise(blocking, readPath, oldWrite);
        }

        RingGroup group = newGroup(2);
        JUringBlocking blocking = new JUringBlocking(group);
        long[] newResults;
        try {
            assertEquals(2, blocking.workerCount(), "group.size() must be the worker count");
            newResults = exercise(blocking, readPath, newWrite);
        } finally {
            blocking.close();
        }
        assertArrayEquals(oldResults, newResults);
        assertTrue(group.isClosed(), "JUringBlocking must own (and close) the supplied group");
    }

    @Test
    void groupCloseIsIdempotentAndRejectsOperations() throws Exception {
        Path readPath = contentFile();
        RingGroup group = newGroup(2);
        JUringBlocking blocking = new JUringBlocking(group);
        try (FileDescriptor fd = new FileDescriptor(readPath, READ, 0)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(14);
                assertEquals(13L, blocking.read(fd, buffer, 14, 0));
            }
            blocking.close();
            assertTrue(group.isClosed());
            blocking.close(); // idempotent, no throw
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(14);
                assertThrows(IllegalStateException.class, () -> blocking.read(fd, buffer, 14, 0));
            }
        }
    }

    @Test
    void constructorValidation() {
        RingGroup started = newGroup(2);
        started.start();
        try {
            assertThrows(IllegalStateException.class, () -> new JUringBlocking(started));
        } finally {
            started.close();
        }

        RingGroup closed = newGroup(2);
        closed.close();
        assertThrows(IllegalStateException.class, () -> new JUringBlocking(closed));

        assertThrows(NullPointerException.class, () -> new JUringBlocking((RingGroup) null));
    }

    @Test
    void oldConstructorWithoutFlagsStillWorks() throws Exception {
        Path readPath = contentFile();
        try (JUringBlocking blocking = new JUringBlocking(10)) {
            try (FileDescriptor fd = new FileDescriptor(readPath, READ, 0);
                 Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(14);
                assertEquals(13L, blocking.read(fd, buffer, 14, 0));
                assertEquals(CONTENT, readString(buffer, 13));
            }
        }
    }

    @Test
    void oldConstructorForcedWorkerCountPreserved() {
        try (JUringBlocking blocking = new JUringBlocking(16, 4, IORING_SETUP_SINGLE_ISSUER)) {
            assertEquals(4, blocking.workerCount());
        }
    }
}
