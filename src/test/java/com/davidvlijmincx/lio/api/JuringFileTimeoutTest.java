package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 high-level timed operations on {@link JuringFile}: kernel linked-timeout reads
 * (deterministic {@code ETIMEDOUT} vs explicit-cancel {@code ECANCELED}), the documented
 * difference to caller-side {@code await(handle, timeout)} (operation stays in flight), and
 * {@code prepareTimeout} timers. A named pipe (FIFO) provides a controllable blocking read.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JuringFileTimeoutTest {

    @TempDir
    Path tempDir;

    private JUring ring;

    @BeforeEach
    void setUp() {
        ring = new JUring(64, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close(1000);
        }
    }

    @Test
    void timedReadCompletesBeforeKernelTimeout() throws Exception {
        Path file = tempDir.resolve("data.bin");
        byte[] content = new byte[4096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Files.write(file, content);

        try (JuringFile handle = JuringFile.open(ring, file, READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(4096);
            int read = handle.read(0, buffer, 5000);
            assertEquals(4096, read);
            byte[] actual = buffer.asSlice(0, read).toArray(JAVA_BYTE);
            assertArrayEquals(content, actual);
            assertEquals(0, handle.inFlightOperations());
            assertEquals(0, handle.cancelledOperations(), "a successful timed read is not a cancellation");
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void timedWriteCompletesBeforeKernelTimeout() throws Exception {
        Path file = tempDir.resolve("timed-write.bin");
        Files.write(file, new byte[16]);
        byte[] payload = "hello-b3-timed".getBytes();
        try (JuringFile handle = JuringFile.open(ring, file, RDWR, CREATE);
             Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(payload.length);
            MemorySegment.copy(payload, 0, buffer, JAVA_BYTE, 0, payload.length);
            int written = handle.write(0, buffer, 5000);
            assertEquals(payload.length, written);
            // A write does not truncate: the pre-existing 16-byte file keeps its length.
            assertEquals(16, handle.length());
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void kernelTimeoutFiresAndReadIsCanceled() throws Exception {
        Path fifo = createFifo("timed-read-timeout.fifo");
        FifoWriter writer = startFifoWriter(fifo, 1500, new byte[]{1, 2, 3, 4});
        try {
            try (JuringFile handle = JuringFile.open(ring, fifo, READ);
                 Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(8);
                long start = System.nanoTime();
                JuringException e = assertThrows(JuringException.class,
                        () -> handle.read(0, buffer, 150));
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

                assertTrue(e.isTimedOut(), "kernel timeout must map to ETIMEDOUT, got " + e.errno() + " " + e.strerror());
                assertTrue(elapsedMs >= 100L && elapsedMs < 1500L,
                        "kernel timeout should fire near 150 ms, took " + elapsedMs + " ms");
                assertEquals(0, handle.inFlightOperations(), "both linked completions must be reconciled");
                assertEquals(0, ring.inFlightCount(), "ring must have no in-flight work after the timeout");
                assertEquals(1, handle.cancelledOperations(),
                        "the target was canceled by the kernel timeout (linked-timeout disarm not counted)");
            }
        } finally {
            writer.close();
        }
    }

    @Test
    void explicitCancelOfTimedReadThrowsEcanceledNotTimedOut() throws Exception {
        Path fifo = createFifo("timed-read-cancel.fifo");
        FifoWriter writer = startFifoWriter(fifo, 1500, new byte[]{9, 9, 9, 9});
        try {
            try (JuringFile handle = JuringFile.open(ring, fifo, READ);
                 Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(8);
                long readHandle = handle.prepareRead(0, 8, buffer, 60_000);
                ring.submit();
                assertEquals(2, handle.inFlightOperations(), "timed operation = target + linked timeout");

                JuringFile.CancelStatus status = handle.cancel(readHandle);
                assertEquals(JuringFile.CancelStatus.CANCELLED, status);

                JuringException e = assertThrows(JuringException.class, () -> handle.await(readHandle));
                assertTrue(e.isCancelled(), "an explicitly cancelled timed read must throw ECANCELED, got "
                        + e.errno() + " " + e.strerror());
                assertEquals(0, handle.inFlightOperations());
                assertEquals(0, ring.inFlightCount());
            }
        } finally {
            writer.close();
        }
    }

    @Test
    void callerSideAwaitTimeoutLeavesOperationInFlightAndDeliversLateResult() throws Exception {
        Path fifo = createFifo("caller-side-timeout.fifo");
        byte[] payload = new byte[]{5, 6, 7, 8};
        FifoWriter writer = startFifoWriter(fifo, 400, payload);
        try {
            try (JuringFile handle = JuringFile.open(ring, fifo, READ);
                 Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(8);
                long readHandle = handle.prepareRead(0, 8, buffer);
                ring.submit();

                long start = System.nanoTime();
                JuringException e = assertThrows(JuringException.class,
                        () -> handle.await(readHandle, 100));
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                assertTrue(e.isTimedOut());
                assertTrue(elapsedMs >= 80L && elapsedMs < 1000L);
                assertEquals(1, handle.inFlightOperations(),
                        "caller-side await timeout must NOT cancel the operation");

                // The writer opens the pipe after ~400 ms; the late completion is still delivered.
                assertEquals(payload.length, handle.await(readHandle));
                assertEquals(0, handle.inFlightOperations());
                assertEquals(0, ring.inFlightCount());
            }
        } finally {
            writer.close();
        }
    }

    @Test
    void prepareTimeoutFiresAndMapsToEtimeout() {
        try (JuringFile handle = JuringFile.open(ring, tempDir.resolve("timer.bin"), RDWR, CREATE)) {
            long timer = handle.prepareTimeout(20);
            ring.submit();
            long start = System.nanoTime();
            JuringException e = assertThrows(JuringException.class, () -> handle.await(timer));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(e.isTimedOut(), "a fired IORING_OP_TIMEOUT maps to ETIMEDOUT, got " + e.errno());
            assertTrue(elapsedMs >= 15L, "timer fired too early: " + elapsedMs + " ms");
            assertEquals(0, handle.inFlightOperations());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void timedOperationValidationRejectsBadArgumentsAndNeedsTwoLedgerSlots() throws Exception {
        Path file = tempDir.resolve("validation.bin");
        Files.write(file, new byte[4096]);
        try (JuringFile handle = JuringFile.open(ring, file, READ).withInFlightCapacity(1);
             Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(16);

            assertThrows(IllegalArgumentException.class, () -> handle.read(0, buffer, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> handle.prepareRead(0, 8, buffer, -5));
            assertEquals(0, ring.inFlightCount(), "rejected prepares must not consume SQEs");
            // A timed operation needs target + linked timeout = 2 ledger slots.
            assertThrows(IllegalStateException.class, () -> handle.prepareRead(0, 8, buffer, 1000));
            assertEquals(0, ring.inFlightCount());
        }
    }

    // ------------------------------------------------------------------

    private Path createFifo(String name) throws Exception {
        Path fifo = tempDir.resolve(name);
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "mkfifo is not available: " + e);
            return fifo;
        }
        boolean exited = process.waitFor(5, TimeUnit.SECONDS);
        Assumptions.assumeTrue(exited && process.exitValue() == 0, "mkfifo failed");
        return fifo;
    }

    /**
     * Starts the FIFO writer thread. The writer's blocking {@code open(O_WRONLY)} and the
     * handle's blocking {@code open(O_RDONLY)} pair up: the caller must open the FIFO (the
     * {@code try (JuringFile ...)} resource) after this call returns.
     */
    private static FifoWriter startFifoWriter(Path fifo, long delayMs, byte[] payload) {
        FifoWriter writer = new FifoWriter(fifo, delayMs, payload);
        writer.start();
        return writer;
    }

    private static final class FifoWriter extends Thread implements AutoCloseable {
        private final Path fifo;
        private final long delayMs;
        private final byte[] payload;
        private final CountDownLatch opened = new CountDownLatch(1);

        FifoWriter(Path fifo, long delayMs, byte[] payload) {
            this.fifo = fifo;
            this.delayMs = delayMs;
            this.payload = payload;
            setName("b3-fifo-writer");
            setDaemon(true);
        }

        @Override
        public void run() {
            try (OutputStream out = Files.newOutputStream(fifo)) {
                opened.countDown();
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                out.write(payload);
                out.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The reader side may close before the delayed write happens; that is expected
                // for the timeout/cancel tests.
            }
        }

        @Override
        public void close() {
            interrupt();
            try {
                join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
