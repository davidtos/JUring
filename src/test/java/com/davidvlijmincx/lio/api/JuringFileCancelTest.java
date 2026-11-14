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

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 {@link JuringFile} cancellation contract: deterministic {@link JuringFile.CancelStatus}
 * matrix, await-after-cancel determinism, bulk {@code cancelAll()}, and counter consistency.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JuringFileCancelTest {

    @TempDir
    Path tempDir;

    private JUring ring;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        ring = new JUring(64, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN);
        file = tempDir.resolve("cancel.bin");
        Files.write(file, new byte[4096]);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close(1000);
        }
    }

    @Test
    void cancelInFlightTimerIsCancelledAndAwaitIsDeterministic() throws Exception {
        try (JuringFile handle = JuringFile.open(ring, file, READ)) {
            long timer = handle.prepareTimeout(60_000);
            ring.submit();
            assertEquals(1, handle.inFlightOperations());

            assertEquals(JuringFile.CancelStatus.CANCELLED, handle.cancel(timer));
            JuringException e = assertThrows(JuringException.class, () -> handle.await(timer));
            assertTrue(e.isCancelled(), "await after cancel must throw ECANCELED, got " + e.errno());
            assertEquals(0, handle.inFlightOperations());
            assertEquals(0, ring.inFlightCount());
            assertEquals(1, handle.cancelledOperations());
            assertEquals(1, handle.controlOperations());
            assertEquals(1, handle.issuedOperations());
            assertEquals(1, handle.completedOperations());
        }
    }

    @Test
    void cancelCompletedTimerReportsAlreadyCompletedAndRealResult() throws Exception {
        try (JuringFile handle = JuringFile.open(ring, file, READ)) {
            long timer = handle.prepareTimeout(1);
            ring.submit();
            Thread.sleep(50); // let the kernel fire the timer, completion not yet reaped by the ledger
            assertEquals(JuringFile.CancelStatus.ALREADY_COMPLETED, handle.cancel(timer));
            JuringException e = assertThrows(JuringException.class, () -> handle.await(timer));
            assertTrue(e.isTimedOut(), "the real result (ETIMEDOUT) is still delivered after ALREADY_COMPLETED");
            assertEquals(0, handle.cancelledOperations(), "a fired timer is not a cancellation");
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void cancelUnknownOrConsumedHandleReportsNotFound() throws Exception {
        try (JuringFile handle = JuringFile.open(ring, file, READ)) {
            assertEquals(JuringFile.CancelStatus.NOT_FOUND, handle.cancel(0xCAFEBABEL));

            long timer = handle.prepareTimeout(10);
            ring.submit();
            assertThrows(JuringException.class, () -> handle.await(timer)); // consumes the handle
            assertEquals(JuringFile.CancelStatus.NOT_FOUND, handle.cancel(timer),
                    "a consumed handle is unknown to the ledger");
            assertEquals(0, handle.controlOperations(), "NOT_FOUND must not issue a kernel cancel");
        }
    }

    @Test
    void cancelAllCancelsEveryInFlightTimer() throws Exception {
        try (JuringFile handle = JuringFile.open(ring, file, READ).withInFlightCapacity(64)) {
            int n = 12;
            long[] timers = new long[n];
            for (int i = 0; i < n; i++) {
                timers[i] = handle.prepareTimeout(60_000);
            }
            ring.submit();
            assertEquals(n, handle.inFlightOperations());

            int cancelled = handle.cancelAll();
            assertEquals(n, cancelled, "cancelAll reports one -ECANCELED completion per timer");
            assertEquals(0, handle.inFlightOperations());
            assertEquals(0, ring.inFlightCount());
            assertTrue(handle.controlOperations() >= 1, "cancelAll issues at least one control request");

            for (long timer : timers) {
                JuringException e = assertThrows(JuringException.class, () -> handle.await(timer));
                assertTrue(e.isCancelled(), "every cancelled timer awaits ECANCELED");
            }
            assertEquals(n, handle.cancelledOperations());
        }
    }

    @Test
    void cancelAllWithFileOperationsLeavesEveryHandleTerminal() throws Exception {
        try (JuringFile handle = JuringFile.open(ring, file, READ).withInFlightCapacity(64);
             Arena arena = Arena.ofConfined()) {
            int n = 16;
            long[] handles = new long[n];
            MemorySegment[] buffers = new MemorySegment[n];
            for (int i = 0; i < n; i++) {
                buffers[i] = arena.allocate(256);
                handles[i] = handle.prepareRead((long) i * 256, 256, buffers[i]);
            }
            // Submit first so some reads may win the race; cancelAll must still leave every
            // handle with exactly one terminal completion.
            ring.submit();
            handle.cancelAll();
            assertEquals(0, handle.inFlightOperations());
            assertEquals(0, ring.inFlightCount());

            int terminal = 0;
            for (long op : handles) {
                try {
                    assertEquals(256, handle.await(op));
                } catch (JuringException e) {
                    assertTrue(e.isCancelled(), "only ECANCELED is acceptable here, got " + e.errno());
                }
                terminal++;
            }
            assertEquals(n, terminal, "every handle must be awaitable after cancelAll");
        }
    }

    @Test
    void cancelWithoutSubmissionStillCancelsQueuedOperation() throws Exception {
        try (JuringFile handle = JuringFile.open(ring, file, READ)) {
            long timer = handle.prepareTimeout(60_000);
            // No explicit submit: cancel() must submit the queued SQEs itself.
            assertEquals(JuringFile.CancelStatus.CANCELLED, handle.cancel(timer));
            JuringException e = assertThrows(JuringException.class, () -> handle.await(timer));
            assertTrue(e.isCancelled());
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void cancelOnClosedHandleThrowsIllegalState() throws Exception {
        JuringFile handle = JuringFile.open(ring, file, READ);
        long timer = handle.prepareTimeout(60_000);
        ring.submit();
        handle.close(1000);
        assertThrows(IllegalStateException.class, () -> handle.cancel(timer));
        assertThrows(IllegalStateException.class, handle::cancelAll);
    }
}
