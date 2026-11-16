package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.1.0 raw-read handle identity: the address-keyed {@code prepareRead(..., MemorySegment, ...)}
 * overloads cannot distinguish two concurrent reads into the same segment, while
 * {@link JUring#prepareReadUnique} uses a unique operation id (and therefore reports no buffer
 * on the completion).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RawReadUniqueHandleTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentReadsIntoSameSegmentGetDistinctHandles() throws Exception {
        Path file = tempDir.resolve("data.bin");
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        Files.write(file, payload);

        try (JUring ring = new JUring(16);
             FileDescriptor fd = new FileDescriptor(file, READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(payload.length);
            long first = ring.prepareReadUnique(fd, segment, 0);
            long second = ring.prepareReadUnique(fd, segment, 0);
            assertNotEquals(first, second, "same-segment reads must get distinct handles");
            ring.submit();

            try (Result r1 = ring.waitForResult();
                 Result r2 = ring.waitForResult()) {
                assertEquals(Set.of(first, second), Set.of(r1.id(), r2.id()),
                        "both unique handles must be reported exactly once");
                assertTrue(r1.isSuccess(), "completion 1 must succeed: " + r1.result());
                assertTrue(r2.isSuccess(), "completion 2 must succeed: " + r2.result());
                assertEquals(payload.length, r1.result());
                assertEquals(payload.length, r2.result());
                assertEquals(OperationType.READ_EXT, r1.type());
                assertEquals(OperationType.READ_EXT, r2.type());
                assertEquals(MemorySegment.NULL, r1.validBuffer(),
                        "a unique-id read must not claim buffer ownership");
                assertEquals(MemorySegment.NULL, r2.validBuffer(),
                        "a unique-id read must not claim buffer ownership");
            }
        }
    }

    @Test
    void addressKeyedOverloadsStillKeepTheirBufferPayload() throws Exception {
        Path file = tempDir.resolve("payload.bin");
        byte[] payload = "unique-vs-address".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, payload);

        try (JUring ring = new JUring(16);
             FileDescriptor fd = new FileDescriptor(file, READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(payload.length);
            ring.prepareRead(fd, segment, 0);
            ring.submit();
            try (Result result = ring.waitForResult()) {
                assertTrue(result.isSuccess());
                assertEquals(segment.address(), result.validBuffer().address(),
                        "the address-keyed overload must still reconstruct the caller's segment");
                assertEquals(payload.length, result.validBuffer().byteSize());
                assertEquals("unique-vs-address", result.getString());
            }
        }
    }
}
