package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 bounded-shutdown contract: {@link JuringFile#close(long)} and
 * {@link JUring#close(long)} must never hang, cancel and reconcile what they can, and count
 * (never silently drop) what they cannot. Wall-clock assertions use generous slack over the
 * documented bound ({@code timeoutMillis + 250 ms}).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JuringFileBoundedCloseTest {

    @TempDir
    Path tempDir;

    private final java.util.List<JUring> ringsToClose = new java.util.ArrayList<>();

    private JUring newRing(int depth) {
        JUring created = new JUring(depth, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN,
                IORING_SETUP_DEFER_TASKRUN);
        ringsToClose.add(created);
        return created;
    }

    @AfterEach
    void tearDown() {
        for (JUring ring : ringsToClose) {
            ring.close(1000);
        }
        ringsToClose.clear();
    }

    @Test
    void closeDrainsCompletedInFlightOperationsQuickly() throws Exception {
        Path file = tempDir.resolve("close-drain.bin");
        Files.write(file, new byte[64 * 4096]);
        JUring ring = newRing(128);
        JuringFile handle = JuringFile.open(ring, file, READ);
        try (Arena arena = Arena.ofConfined()) {
            for (int i = 0; i < 64; i++) {
                MemorySegment buffer = arena.allocate(4096);
                handle.prepareRead((long) i * 4096, 4096, buffer);
            }
            ring.submit(); // reads complete in the kernel before close()

            long start = System.nanoTime();
            handle.close(2000);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(elapsedMs < 800L,
                    "close() must short-circuit as soon as completions are visible, took " + elapsedMs + " ms");
            assertEquals(64, handle.issuedOperations());
            assertEquals(64, handle.completedOperations());
            assertEquals(0, handle.abandonedOperations());
            assertEquals(0, handle.inFlightOperations());
            assertEquals(0, ring.inFlightCount());
        }
        // A timed operation whose target wins is not affected by the close drain above.
        assertEquals(0, ring.abandonedAtClose());
    }

    @Test
    void closeWithManyLongTimersIsBoundedAndReconciled() throws Exception {
        Path file = tempDir.resolve("close-timers.bin");
        Files.write(file, new byte[16]);
        JUring ring = newRing(4096);
        JuringFile handle = JuringFile.open(ring, file, READ).withInFlightCapacity(2048);
        int n = 512;
        for (int i = 0; i < n; i++) {
            handle.prepareTimeout(60_000);
        }
        ring.submit();

        long start = System.nanoTime();
        handle.close(400);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < 400L + 250L + 400L,
                "close(400) with " + n + " in-flight timers took " + elapsedMs + " ms (bound ~650 ms)");
        assertEquals(n, handle.issuedOperations());
        assertEquals(n, handle.completedOperations(), "all cancelled timers must be reaped/reconciled");
        assertEquals(0, handle.abandonedOperations());
        assertEquals(n, handle.cancelledOperations());
        assertEquals(0, handle.inFlightOperations());
        assertEquals(0, ring.inFlightCount());
    }

    @Test
    void closeWithZeroTimeoutForcesBoundedCancellation() throws Exception {
        Path file = tempDir.resolve("close-zero.bin");
        Files.write(file, new byte[16]);
        JUring ring = newRing(512);
        JuringFile handle = JuringFile.open(ring, file, READ).withInFlightCapacity(128);
        for (int i = 0; i < 64; i++) {
            handle.prepareTimeout(60_000);
        }
        ring.submit();

        long start = System.nanoTime();
        handle.close(0);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < 700L, "close(0) must force-close within the grace bound, took " + elapsedMs + " ms");
        assertEquals(64, handle.completedOperations());
        assertEquals(0, handle.abandonedOperations());
        assertEquals(0, ring.inFlightCount());
    }

    @Test
    void closeWithForeignInFlightWorkAbandonsWithoutInflatingRingCounter() throws Exception {
        Path file = tempDir.resolve("foreign.bin");
        Files.write(file, new byte[16]);
        JUring ring = newRing(64);
        JuringFile handle = JuringFile.open(ring, file, READ).withInFlightCapacity(16);
        for (int i = 0; i < 4; i++) {
            handle.prepareTimeout(60_000);
        }
        // Foreign work: a raw ring timer that does not belong to the JuringFile. The caller-managed
        // __kernel_timespec must stay valid until the timeout completion is reaped (frozen lifetime
        // rule), so the arena stays open across submit/close.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ts = arena.allocate(JUring.TIMESPEC_BYTES, 8);
            ts.set(JAVA_LONG, 0L, 60L);
            ring.prepareTimeout(ts, 0, 0);
            ring.submit();
            assertEquals(5, ring.inFlightCount(), "4 file timers + 1 foreign timer");

            long start = System.nanoTime();
            handle.close(100);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(elapsedMs < 800L, "foreign-work close must be fast, took " + elapsedMs + " ms");
            assertEquals(4, handle.abandonedOperations(),
                    "the handle's 4 requests are abandoned and counted (foreign work cannot be reaped)");
            assertEquals(5, ring.inFlightCount(),
                    "B3 must NOT inflate the ring in-flight counter with unreapable cancel requests");

            // The ring still owns all 5 completions and reaps them in its own bounded close.
            ring.close(1000);
            assertEquals(5, ring.cancelledAtClose(), "the ring's bounded close cancels and reaps all 5");
            assertEquals(0, ring.abandonedAtClose());
        }
    }

    @Test
    void fileCloseAfterRingCloseIsFastAndCountsAbandoned() throws Exception {
        Path file = tempDir.resolve("ring-first.bin");
        Files.write(file, new byte[16]);
        JUring ring = newRing(64);
        JuringFile handle = JuringFile.open(ring, file, READ);
        for (int i = 0; i < 4; i++) {
            handle.prepareTimeout(60_000);
        }
        ring.submit();
        ring.close(1000); // ring cancels/reaps everything first
        assertTrue(ring.isClosed());

        long start = System.nanoTime();
        handle.close(100);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 300L, "file.close after ring.close must be immediate, took " + elapsedMs + " ms");
        assertEquals(4, handle.abandonedOperations(),
                "requests whose ring is already gone are counted as abandoned");
        assertFalse(handle.isOpen());
    }

    @Test
    void fileCloseIsIdempotent() throws Exception {
        Path file = tempDir.resolve("idempotent.bin");
        Files.write(file, new byte[16]);
        JUring ring = newRing(64);
        JuringFile handle = JuringFile.open(ring, file, READ);
        long timer = handle.prepareTimeout(60_000);
        ring.submit();
        handle.close(1000);
        long completedAfterFirst = handle.completedOperations();
        int abandonedAfterFirst = handle.abandonedOperations();
        long issuedAfterFirst = handle.issuedOperations();

        handle.close();
        handle.close(0);
        assertEquals(issuedAfterFirst, handle.issuedOperations());
        assertEquals(completedAfterFirst, handle.completedOperations());
        assertEquals(abandonedAfterFirst, handle.abandonedOperations());
        assertEquals(-1, handle.registeredSlot());
        assertEquals(0, ring.inFlightCount());
        try {
            handle.await(timer);
        } catch (IllegalStateException expected) {
            // use-after-close is the frozen contract
        }
    }

    @Test
    void ringCloseWithInFlightTimersIsBoundedAndCounted() throws Exception {
        JUring ring = newRing(2048);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ts = arena.allocate(JUring.TIMESPEC_BYTES, 8);
            ts.set(JAVA_LONG, 0L, 60L);
            int n = 512;
            for (int i = 0; i < n; i++) {
                ring.prepareTimeout(ts, 0, 0);
            }
            ring.submit();

            long start = System.nanoTime();
            ring.close(500);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(elapsedMs < 1500L, "JUring.close(500) took " + elapsedMs + " ms");
            assertEquals(n, ring.cancelledAtClose(), "every in-flight timer is cancelled and reaped");
            assertEquals(0, ring.abandonedAtClose());
            assertTrue(ring.isClosed());
            ring.close(0); // idempotent
        }
    }

    @Test
    void ringCloseWithZeroTimeoutIsBounded() throws Exception {
        JUring ring = newRing(256);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ts = arena.allocate(JUring.TIMESPEC_BYTES, 8);
            ts.set(JAVA_LONG, 0L, 60L);
            for (int i = 0; i < 64; i++) {
                ring.prepareTimeout(ts, 0, 0);
            }
            ring.submit();
            long start = System.nanoTime();
            ring.close(0);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMs < 700L, "JUring.close(0) took " + elapsedMs + " ms");
            assertEquals(64, ring.cancelledAtClose());
        }
    }

    @Test
    void ringCloseWithCompletingOpsDrainsWithoutCancelling() throws Exception {
        JUring ring = newRing(64);
        Path file = tempDir.resolve("ring-drain.bin");
        Files.write(file, new byte[4096]);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(4096);
            for (int i = 0; i < 8; i++) {
                ring.prepareNop();
            }
            ring.submit();
            ring.close(2000);
            assertEquals(0, ring.cancelledAtClose(), "completed NOPs are drained, not cancelled");
            assertEquals(0, ring.abandonedAtClose());
        }
    }
}
