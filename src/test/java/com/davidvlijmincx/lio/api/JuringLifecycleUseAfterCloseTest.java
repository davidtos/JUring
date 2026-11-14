package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 use-after-close hardening: every public entry point that touches the native ring must
 * throw {@link IllegalStateException} after close instead of dereferencing freed memory.
 * Regression test for the verifier's baseline SIGSEGV in {@code IoUringDispatcher.getSqe()}
 * ({@code JUring.prepareNop()} after {@code close()}).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JuringLifecycleUseAfterCloseTest {

    @TempDir
    Path tempDir;

    @Test
    void ringPreparePathsThrowAfterCloseInsteadOfSegfaulting() throws IOException {
        Path file = tempDir.resolve("data.bin");
        Files.write(file, new byte[64]);
        JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
        ring.close();

        try (FileDescriptor fd = new FileDescriptor(file, READ, 0);
             Arena arena = Arena.ofConfined()) {
            MemorySegment ts = arena.allocate(JUring.TIMESPEC_BYTES, 8);
            ts.set(JAVA_LONG, 0L, 1L);
            MemorySegment buffer = arena.allocate(16);

            assertThrows(IllegalStateException.class, ring::prepareNop);
            assertThrows(IllegalStateException.class, () -> ring.prepareRead(fd, 8, 0));
            assertThrows(IllegalStateException.class, () -> ring.prepareRead(fd, buffer, 0));
            assertThrows(IllegalStateException.class, () -> ring.prepareWrite(fd, buffer, 0));
            assertThrows(IllegalStateException.class, () -> ring.prepareFsync(fd, 0));
            assertThrows(IllegalStateException.class, () -> ring.prepareTimeout(ts));
            assertThrows(IllegalStateException.class, () -> ring.prepareLinkTimeout(ts));
            assertThrows(IllegalStateException.class, () -> ring.prepareCancelByUserData(1L));
            assertThrows(IllegalStateException.class, () -> ring.prepareCancelFile(fd.getFd(), false));
            assertThrows(IllegalStateException.class, ring::prepareCancelAny);
            assertThrows(IllegalStateException.class, () -> ring.prepareFallocate(fd, 0, 0, 4096));
            assertThrows(IllegalStateException.class, () -> ring.prepareFtruncate(fd, 0));
            assertThrows(IllegalStateException.class, () -> ring.prepareClose(fd));
            assertThrows(IllegalStateException.class, () -> ring.prepareOpen("x", 0, 0));
        }
        assertTrue(ring.isClosed());
    }

    @Test
    void ringSubmitAndReapPathsThrowAfterClose() {
        JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
        ring.close();
        assertThrows(IllegalStateException.class, ring::submit);
        assertThrows(IllegalStateException.class, () -> ring.submitAndWait(0));
        assertThrows(IllegalStateException.class, () -> ring.submitAndWaitForBatchResult(0, r -> { }));
        assertThrows(IllegalStateException.class, () -> ring.forEachBatchResult(1, r -> { }));
        assertThrows(IllegalStateException.class, () -> ring.peekForBatchResult(1, r -> { }));
        assertThrows(IllegalStateException.class, () -> ring.peekForBatchResult(1));
        assertThrows(IllegalStateException.class, () -> ring.waitForBatchResult(1));
        assertThrows(IllegalStateException.class, () -> ring.waitForBatchResult(1, r -> { }));
        assertThrows(IllegalStateException.class, ring::waitForResult);
        assertThrows(IllegalStateException.class, () -> ring.waitForResult(new Result()));
        // close is idempotent and does not throw
        ring.close();
        ring.close(0);
    }

    @Test
    void ringRegistrationPathsThrowAfterClose() throws IOException {
        Path file = tempDir.resolve("reg.bin");
        Files.write(file, new byte[64]);
        JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
        ring.close();
        try (FileDescriptor fd = new FileDescriptor(file, READ, 0);
             Arena arena = Arena.ofConfined()) {
            assertThrows(IllegalStateException.class, () -> ring.registerBuffers(16, 1));
            assertThrows(IllegalStateException.class, () -> ring.registerFiles(fd));
            assertThrows(IllegalStateException.class, () -> ring.registerFileAdd(fd));
            assertThrows(IllegalStateException.class, () -> ring.registerFilesUpdate(0, new int[]{fd.getFd()}));
            assertThrows(IllegalStateException.class, ring::unregisterFiles);
            assertThrows(IllegalStateException.class, ring::unregisterBuffers);
            assertThrows(IllegalStateException.class, () -> ring.updateRegisteredBuffers(0, arena.allocate(16)));
            assertThrows(IllegalStateException.class, ring::checkOutBuffer);
            assertThrows(IllegalStateException.class, () -> ring.getSharedWorkerRing(8));
            assertThrows(IllegalStateException.class, () -> ring.getRegisteredBuffer(0));
            assertThrows(IllegalStateException.class, ring::inFlightCount);
        }
    }

    @Test
    void filePathsThrowAfterFileClose() throws IOException {
        Path file = tempDir.resolve("file-close.bin");
        Files.write(file, new byte[64]);
        JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
        try {
            JuringFile handle = JuringFile.open(ring, file, READ);
            handle.close();
            assertFalse(handle.isOpen());
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(16);
                assertThrows(IllegalStateException.class, () -> handle.prepareRead(0, 16, buffer));
                assertThrows(IllegalStateException.class, () -> handle.prepareWrite(0, buffer));
                assertThrows(IllegalStateException.class, () -> handle.prepareRead(0, 16, buffer, 100));
                assertThrows(IllegalStateException.class, () -> handle.read(0, buffer));
                assertThrows(IllegalStateException.class, () -> handle.read(0, buffer, 100));
                assertThrows(IllegalStateException.class, () -> handle.await(1L));
                assertThrows(IllegalStateException.class, () -> handle.cancel(1L));
                assertThrows(IllegalStateException.class, handle::cancelAll);
                assertThrows(IllegalStateException.class, handle::length);
                assertThrows(IllegalStateException.class, () -> handle.truncate(0));
                assertThrows(IllegalStateException.class, handle::register);
                assertThrows(IllegalStateException.class, () -> handle.prepareTimeout(10));
            }
            // close is idempotent
            handle.close();
            handle.close(0);
        } finally {
            ring.close();
        }
    }

    @Test
    void prepareLinkTimeoutAfterRingCloseThrowsIllegalStateNotSegfault() {
        // Verifier regression (hs_err pid6/42/46/80/116/274): prepareLinkTimeout's same-batch
        // validation used to read the SQ ring through lastQueuedSqe() before checking closed,
        // SIGSEGV-ing on the unmapped SQ mmap. Every call now fails fast.
        JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ts = arena.allocate(JUring.TIMESPEC_BYTES, 8);
            ts.set(JAVA_LONG, 0L, 1L);
            ring.close();
            for (int i = 0; i < 1000; i++) {
                assertThrows(IllegalStateException.class, () -> ring.prepareLinkTimeout(ts));
            }
            assertThrows(IllegalStateException.class, () -> ring.prepareLinkTimeout(ts, 0));
            ring.close(); // idempotent
        }
        assertTrue(ring.isClosed());
    }

    @Test
    void queueFullThrowsIllegalStateBeforeConsumingAnSqe() {
        JUring small = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
        try {
            // Fill the SQ ring the kernel actually created (io_uring may round the depth up).
            int capacity = 0;
            while (true) {
                try {
                    small.prepareNop();
                    capacity++;
                } catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("queue ring is full"),
                            "unexpected queue-full message: " + expected.getMessage());
                    break;
                }
                assertTrue(capacity < 4096, "the SQ ring never reported full");
            }
            assertTrue(capacity > 0);
            assertEquals(capacity, small.inFlightCount(),
                    "a rejected prepare must not consume an SQE or change the in-flight count");

            small.submit();
            for (int i = 0; i < capacity; i++) {
                small.waitForResult().close();
            }
            assertEquals(0, small.inFlightCount(), "reaping everything frees SQ space");
            small.prepareNop(); // works again after submit + reap
        } finally {
            small.close(1000);
        }
    }

    @Test
    void ringCloseAfterFileCloseAndFileCloseAfterRingCloseAreSafe() throws IOException {
        Path file = tempDir.resolve("order.bin");
        Files.write(file, new byte[16]);

        JUring ringA = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
        JuringFile fileA = JuringFile.open(ringA, file, READ);
        fileA.prepareTimeout(60_000);
        fileA.close(500);
        assertThrows(IllegalStateException.class, () -> fileA.prepareRead(0, 8, MemorySegment.NULL));
        ringA.close(1000);
        assertTrue(ringA.isClosed());

        JUring ringB = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
        JuringFile fileB = JuringFile.open(ringB, file, READ);
        fileB.prepareTimeout(60_000);
        ringB.close(1000);
        fileB.close(100); // must not throw/hang even though the ring is gone
        assertFalse(fileB.isOpen());
    }
}
