package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the registration lifecycle on the raw {@link JUring}: the sparse fixed-file table
 * ({@code registerFileAdd}/{@code unregisterFileSlot}/{@code initFileTable}), ring-level
 * {@code unregisterFiles}/{@code unregisterBuffers}, and the best-effort
 * {@code IORING_REGISTER_BUFFERS_UPDATE} path.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JUringRegistrationLifecycleTest {

    private static final int EBADF = 9;

    @TempDir
    Path tempDir;

    private JUring ring;

    @BeforeEach
    void setUp() {
        ring = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close();
        }
    }

    private Path writeFile(String name, int size) throws IOException {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) i;
        }
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    @Test
    void sparseFileAddRemoveAndReuse() throws IOException {
        Path file = writeFile("sparse.bin", 16);
        try (FileDescriptor fd1 = new FileDescriptor(file, READ);
             FileDescriptor fd2 = new FileDescriptor(file, READ);
             FileDescriptor fd3 = new FileDescriptor(file, READ)) {

            int slot1 = ring.registerFileAdd(fd1);
            int slot2 = ring.registerFileAdd(fd2);
            assertEquals(0, slot1, "first add fills the first empty slot");
            assertEquals(1, slot2, "second add fills the next empty slot");

            ring.unregisterFileSlot(slot1);

            // The freed slot must reject fixed-file operations until it is filled again.
            long failedHandle = ring.prepareRead(slot1, 4, 0);
            ring.submit();
            Result failed = ring.waitForResult();
            assertEquals(failedHandle, failed.id());
            assertFalse(failed.isSuccess(), "read from an empty fixed-file slot must fail");
            assertEquals(-EBADF, failed.result());
            failed.freeBuffer();

            int reused = ring.registerFileAdd(fd3);
            assertEquals(slot1, reused, "the freed slot must be reused by the next add");
        }
    }

    @Test
    void initFileTableRespectsCapacityAndRejectsDoubleInit() throws IOException {
        Path file = writeFile("capacity.bin", 4);
        List<FileDescriptor> fds = new ArrayList<>();
        try {
            ring.initFileTable(2);
            for (int i = 0; i < 2; i++) {
                FileDescriptor fd = new FileDescriptor(file, READ);
                fds.add(fd);
                assertEquals(i, ring.registerFileAdd(fd));
            }
            FileDescriptor extra = new FileDescriptor(file, READ);
            fds.add(extra);
            assertThrows(IllegalStateException.class, () -> ring.registerFileAdd(extra),
                    "adding beyond the fixed capacity must fail");
            assertThrows(IllegalStateException.class, () -> ring.initFileTable(4),
                    "the table must only be initialised once");
        } finally {
            for (FileDescriptor fd : fds) {
                fd.close();
            }
        }
    }

    @Test
    void registerFilesPopulatesSparseTracking() throws IOException {
        Path file = writeFile("register-files.bin", 4);
        try (FileDescriptor fd1 = new FileDescriptor(file, READ);
             FileDescriptor fd2 = new FileDescriptor(file, READ);
             FileDescriptor fd3 = new FileDescriptor(file, READ)) {

            assertEquals(0, ring.registerFiles(fd1, fd2), "IORING_REGISTER_FILES returns 0 on success");
            assertThrows(IllegalStateException.class, () -> ring.registerFileAdd(fd3),
                    "registerFiles must mark all slots as used (capacity = count)");

            ring.unregisterFileSlot(0);
            assertEquals(0, ring.registerFileAdd(fd3), "a slot freed from a registerFiles table is reusable");
        }
    }

    @Test
    void unregisterFilesThenReRegister() throws IOException {
        Path file = writeFile("unregister-files.bin", 128);
        try (FileDescriptor fd1 = new FileDescriptor(file, READ);
             FileDescriptor fd2 = new FileDescriptor(file, READ)) {

            int slot = ring.registerFileAdd(fd1);
            assertEquals(0, slot);

            ring.unregisterFiles();

            // No registered file table any more: fixed-file ops fail with EBADF.
            long failedHandle = ring.prepareRead(slot, 4, 0);
            ring.submit();
            Result failed = ring.waitForResult();
            assertEquals(failedHandle, failed.id());
            assertFalse(failed.isSuccess());
            assertEquals(-EBADF, failed.result());
            failed.freeBuffer();

            // The table re-initialises lazily, restarting from slot 0.
            assertEquals(0, ring.registerFileAdd(fd2));
        }
    }

    @Test
    void unregisterFilesIsNoOpWhenNothingIsRegistered() {
        assertDoesNotThrow(() -> ring.unregisterFiles());
    }

    @Test
    void unregisterBuffersThenReRegisterAndUse() throws IOException {
        Path file = tempDir.resolve("unregister-buffers.bin");

        ring.registerBuffers(64, 2);
        ring.unregisterBuffers();

        MemorySegment[] buffers = ring.registerBuffers(32, 1);
        assertEquals(1, buffers.length, "re-registering after unregisterBuffers must succeed");

        MemorySegment registered = ring.getRegisteredBuffer(0);
        for (int i = 0; i < 32; i++) {
            registered.set(JAVA_BYTE, i, (byte) ('a' + (i % 26)));
        }

        try (FileDescriptor fd = new FileDescriptor(file, RDWR, CREATE, TRUNCATE)) {
            long handle = ring.prepareWriteFixed(fd, registered, 0, 0);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(handle, result.id());
            assertTrue(result.isSuccess(), "fixed-buffer write after re-register failed: " + result.result());
            assertEquals(32, result.result());
        }

        byte[] expected = new byte[32];
        for (int i = 0; i < 32; i++) {
            expected[i] = (byte) ('a' + (i % 26));
        }
        assertArrayEquals(expected, Files.readAllBytes(file));
    }

    @Test
    void unregisterBuffersIsNoOpWhenNothingIsRegistered() {
        assertDoesNotThrow(() -> ring.unregisterBuffers());
    }

    @Test
    void updateRegisteredBuffersUsesNewMemoryBestEffort() throws IOException {
        Path file = tempDir.resolve("buffers-update.bin");

        ring.registerBuffers(64, 2);

        try (FileDescriptor fd = new FileDescriptor(file, RDWR, CREATE, TRUNCATE);
             Arena arena = Arena.ofConfined()) {

            MemorySegment replacement = arena.allocate(64L);
            byte[] payload = "BUFFERS_UPDATE".getBytes(StandardCharsets.UTF_8);
            MemorySegment.copy(payload, 0, replacement, JAVA_BYTE, 0, payload.length);

            try {
                ring.updateRegisteredBuffers(0, replacement);
            } catch (UnsupportedOperationException e) {
                // Kernel-dependent: skip on kernels without BUFFERS_UPDATE.
                Assumptions.assumeTrue(false, "kernel does not support IORING_REGISTER_BUFFERS_UPDATE: " + e.getMessage());
            }

            assertEquals(replacement.address(), ring.getRegisteredBuffer(0).address(),
                    "tracking must point at the replacement segment after a successful update");

            long handle = ring.prepareWriteFixed(fd, replacement, 0, 0);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(handle, result.id());
            assertTrue(result.isSuccess(), "fixed-buffer write after buffer update failed: " + result.result());
            assertEquals(64, result.result());
        }

        byte[] written = Files.readAllBytes(file);
        assertEquals(64, written.length);
        assertArrayEquals("BUFFERS_UPDATE".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOf(written, "BUFFERS_UPDATE".length()));
    }

    @Test
    void updateRegisteredBuffersValidatesArguments() {
        assertThrows(IllegalStateException.class, () -> ring.updateRegisteredBuffers(0, MemorySegment.NULL),
                "updating buffers without a registration must fail");

        ring.registerBuffers(64, 2);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(64L);
            assertThrows(IllegalArgumentException.class, () -> ring.updateRegisteredBuffers(-1, buffer));
            assertThrows(IllegalArgumentException.class,
                    () -> ring.updateRegisteredBuffers(1, buffer, buffer));
            assertThrows(IllegalArgumentException.class,
                    () -> ring.updateRegisteredBuffers(0, MemorySegment.ofArray(new byte[64])));
            assertThrows(NullPointerException.class, () -> ring.updateRegisteredBuffers(0, (MemorySegment) null));
        }
    }

    @Test
    void isClosedReflectsRingState() {
        JUring dedicated = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
        try {
            assertFalse(dedicated.isClosed());
        } finally {
            dedicated.close();
        }
        assertTrue(dedicated.isClosed());
        assertDoesNotThrow(() -> dedicated.close(), "close must be idempotent");
        assertTrue(dedicated.isClosed());
    }
}
