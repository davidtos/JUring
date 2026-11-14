package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 {@code user_data}/Result safety audit tests: timeout/cancel/linked-timeout completions
 * own no user buffer; a cancelled legacy read still frees its ring buffer exactly once;
 * caller-provided segments and registered fixed buffers are never freed by a {@code Result}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JuringResultSafetyTest {

    private static final int ETIME = 62;
    private static final int ECANCELED = 125;

    @TempDir
    Path tempDir;

    private JUring ring;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        ring = new JUring(32, IORING_SETUP_SINGLE_ISSUER);
        file = tempDir.resolve("result-safety.bin");
        byte[] content = new byte[4096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 7);
        }
        Files.write(file, content);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close(1000);
        }
    }

    @Test
    void timeoutCompletionOwnsNoUserBuffer() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ts = relative(arena, 1_000_000L);
            ring.prepareTimeout(ts);
            ring.submit();
            Result result = ring.waitForResult();
            assertEquals(OperationType.TIMEOUT, result.type());
            assertEquals(-ETIME, (int) result.result());
            assertFalse(result.isSuccess());
            assertEquals(0L, result.bufferAddress(), "a timeout completion owns no buffer");
            assertEquals(MemorySegment.NULL, result.buffer());
            // Freeing/consuming a buffer-less completion must be a no-op, repeatedly.
            result.freeBuffer();
            result.freeBuffers();
            result.close();
            result.close();
            assertEquals(0L, result.bufferAddress());
            assertEquals(MemorySegment.NULL, result.buffer());
        }
    }

    @Test
    void linkedTimeoutCompletionOwnsNoUserBuffer() {
        try (Arena arena = Arena.ofConfined()) {
            long target = ring.prepareNop(SqeOptions.IOSQE_IO_LINK);
            long companion = ring.prepareLinkTimeout(relative(arena, 5_000_000_000L), 0);
            ring.submit();
            List<Result> results = reap(2);
            Result link = byId(results, companion);
            assertEquals(OperationType.LINK_TIMEOUT, link.type());
            assertEquals(-ECANCELED, (int) link.result());
            assertEquals(0L, link.bufferAddress());
            assertEquals(MemorySegment.NULL, link.buffer());
            link.freeBuffer();
            link.freeBuffers();
            link.close();
            assertEquals(0, (int) byId(results, target).result());
        }
    }

    @Test
    void cancelCompletionOwnsNoUserBuffer() {
        long cancel = ring.prepareCancelByUserData(0xABCDEFL);
        ring.submit();
        Result result = ring.waitForResult();
        assertEquals(cancel, result.id());
        assertEquals(OperationType.CANCEL, result.type());
        assertEquals(0L, result.bufferAddress(), "a cancel completion owns no buffer");
        assertEquals(MemorySegment.NULL, result.buffer());
        result.freeBuffer();
        result.freeBuffers();
        result.close();
    }

    @Test
    void cancelledLegacyReadFreesItsRingBufferExactlyOnce() {
        int efd = NativeDispatcher.C.eventfd(0, 0);
        try {
            FileDescriptor fd = new FileDescriptor(efd);
            long read = ring.prepareRead(fd, 8, 0); // legacy READ: ring-owned malloc buffer
            ring.submit();
            long cancel = ring.prepareCancelFile(efd, false);
            ring.submit();

            List<Result> results = reap(2);
            Result readResult = byId(results, read);
            assertEquals(OperationType.READ, readResult.type());
            assertEquals(-ECANCELED, (int) readResult.result());
            assertEquals(1, (int) byId(results, cancel).result());
            assertFalse(readResult.bufferAddress() == 0L,
                    "a cancelled legacy read still carries its ring buffer address");
            // Exactly the owner frees it; a second call must not double-free.
            readResult.freeBuffer();
            assertEquals(0L, readResult.bufferAddress());
            readResult.freeBuffer();
            readResult.close();
        } finally {
            NativeDispatcher.C.close(efd);
        }
    }

    @Test
    void legacyByteArrayWriteFreesItsTempBufferAtCompletion() throws IOException {
        Path target = tempDir.resolve("write-target.bin");
        Files.write(target, new byte[16]);
        byte[] payload = "payload".getBytes();
        try (FileDescriptor fd = new FileDescriptor(target, WRITE)) {
            long write = ring.prepareWrite(fd, payload, 0);
            ring.submit();
            Result result = ring.waitForResult();
            assertEquals(write, result.id());
            assertEquals(OperationType.WRITE, result.type());
            assertEquals(payload.length, (int) result.result());
            assertEquals(0L, result.bufferAddress(),
                    "the temporary byte[] buffer is freed eagerly in populateResultFromCqe");
            result.freeBuffer();
            result.close();
        }
    }

    @Test
    void callerProvidedReadSegmentIsNeverFreedByResult() {
        try (FileDescriptor fd = new FileDescriptor(file, READ, 0);
             Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(1024);
            long read = ring.prepareRead(fd, buffer, 0);
            ring.submit();
            Result result = ring.waitForResult();
            assertEquals(read, result.id());
            assertEquals(OperationType.READ_EXT, result.type());
            assertEquals(1024, (int) result.result());
            assertEquals(buffer.address(), result.bufferAddress());

            result.freeBuffer(); // must only clear the Result fields, never free the segment
            result.close();

            // The caller's segment is still alive and usable.
            buffer.set(JAVA_BYTE, 0L, (byte) 42);
            assertEquals((byte) 42, buffer.get(JAVA_BYTE, 0L));
            assertTrue(buffer.scope().isAlive());
        }
    }

    @Test
    void fixedBufferCompletionNeverFreesRegisteredBuffer() {
        MemorySegment[] buffers = ring.registerBuffers(512, 1);
        MemorySegment registered = buffers[0];
        try (FileDescriptor fd = new FileDescriptor(file, READ, 0)) {
            long read = ring.prepareReadFixed(fd, 64, 0, 0);
            ring.submit();
            Result result = ring.waitForResult();
            assertEquals(read, result.id());
            assertEquals(OperationType.READ_FIXED, result.type());
            assertEquals(64, (int) result.result());
            assertEquals(registered.address(), result.bufferAddress(),
                    "READ_FIXED reports the registered buffer address");
            result.freeBuffer();
            assertEquals(0L, result.bufferAddress(),
                    "freeBuffer must clear the field without freeing the registered buffer");
            result.close();

            // Registered buffer is still valid and readable afterwards.
            assertEquals(0, registered.get(JAVA_BYTE, 0L));
            assertEquals((byte) (7), registered.get(JAVA_BYTE, 1L));
        }
    }

    // ------------------------------------------------------------------

    private List<Result> reap(int count) {
        List<Result> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            results.add(ring.waitForResult());
        }
        return results;
    }

    private static Result byId(List<Result> results, long handle) {
        return results.stream()
                .filter(r -> r.id() == handle)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing completion for handle " + handle + " in " + results));
    }

    private static MemorySegment relative(Arena arena, long nanos) {
        MemorySegment ts = arena.allocate(JUring.TIMESPEC_BYTES, 8);
        ts.set(JAVA_LONG, 0L, nanos / 1_000_000_000L);
        ts.set(JAVA_LONG, 8L, nanos % 1_000_000_000L);
        return ts;
    }
}
