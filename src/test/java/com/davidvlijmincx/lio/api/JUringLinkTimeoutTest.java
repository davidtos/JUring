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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 raw-engine linked-timeout contract ({@code IORING_OP_LINK_TIMEOUT}, opcode 15) on the
 * live kernel: both CQE orders, exactly one completion per request, ABS flag behaviour,
 * target-failure case, prepare validation. Semantics verified by {@code scratch/b3/Probe.java}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JUringLinkTimeoutTest {

    private static final int ETIME = 62;
    private static final int ECANCELED = 125;
    private static final int EBADF = 9;

    @TempDir
    Path tempDir;

    private JUring ring;

    @BeforeEach
    void setUp() {
        ring = new JUring(32, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN);
        Assumptions.assumeTrue(ring.supportsLinkTimeout(), "kernel does not advertise IORING_OP_LINK_TIMEOUT");
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close(1000);
        }
    }

    @Test
    void linkTimeoutOpcodeIsAdvertised() {
        assertTrue(ring.supportsLinkTimeout());
        assertTrue(ring.supportsTimeout());
    }

    @Test
    void timeoutFiresFirstThenTargetGetsCanceledAndLinkGetsEtime() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slowTarget = relative(arena, 5_000_000_000L);
            MemorySegment link = relative(arena, 100_000_000L);
            long target = ring.prepareTimeout(slowTarget, 0, 0, SqeOptions.IOSQE_IO_LINK);
            long companion = ring.prepareLinkTimeout(link, 0);
            long start = System.nanoTime();
            ring.submit();

            List<Result> results = reap(2);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            Result targetResult = byId(results, target);
            Result linkResult = byId(results, companion);
            assertEquals(OperationType.TIMEOUT, targetResult.type());
            assertEquals(-ECANCELED, (int) targetResult.result(),
                    "target must complete -ECANCELED when the linked timeout fires first");
            assertEquals(OperationType.LINK_TIMEOUT, linkResult.type());
            assertEquals(-ETIME, (int) linkResult.result(),
                    "the linked timeout must complete -ETIME when it fires");
            assertTrue(elapsedMs >= 70L && elapsedMs < 2000L,
                    "link timeout fired at " + elapsedMs + " ms (expected ~100 ms)");
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void targetCompletesFirstThenLinkTimeoutIsCanceledWithEcanceled() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment link = relative(arena, 5_000_000_000L);
            long target = ring.prepareNop(SqeOptions.IOSQE_IO_LINK);
            long companion = ring.prepareLinkTimeout(link, 0);
            long start = System.nanoTime();
            ring.submit();

            List<Result> results = reap(2);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            Result targetResult = byId(results, target);
            Result linkResult = byId(results, companion);
            assertEquals(0, (int) targetResult.result(), "the winning target keeps its real result");
            assertEquals(OperationType.LINK_TIMEOUT, linkResult.type());
            assertEquals(-ECANCELED, (int) linkResult.result(),
                    "a disarmed linked timeout completes -ECANCELED (not 0)");
            assertTrue(elapsedMs < 2000L, "a disarmed linked timeout must not fire");
        }
    }

    @Test
    void absoluteLinkTimeoutFiresAtDeadline() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slowTarget = relative(arena, 5_000_000_000L);
            long deadline = System.nanoTime() + 100_000_000L;
            MemorySegment absLink = arena.allocate(JUring.TIMESPEC_BYTES, 8);
            absLink.set(JAVA_LONG, 0L, deadline / 1_000_000_000L);
            absLink.set(JAVA_LONG, 8L, deadline % 1_000_000_000L);
            long target = ring.prepareTimeout(slowTarget, 0, 0, SqeOptions.IOSQE_IO_LINK);
            long companion = ring.prepareLinkTimeout(absLink, JUring.IORING_TIMEOUT_ABS);
            long start = System.nanoTime();
            ring.submit();

            List<Result> results = reap(2);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertEquals(-ECANCELED, (int) byId(results, target).result());
            assertEquals(-ETIME, (int) byId(results, companion).result());
            assertTrue(elapsedMs >= 70L && elapsedMs < 2000L,
                    "ABS link timeout fired at " + elapsedMs + " ms (expected ~100 ms)");
        }
    }

    @Test
    void targetFailureKeepsRealErrnoAndLinkTimeoutIsCanceled() throws IOException {
        Path file = tempDir.resolve("bad-fd.txt");
        Files.write(file, new byte[]{1});
        // A descriptor number that is not open: the kernel fails the read with -EBADF immediately.
        FileDescriptor closedFd = new FileDescriptor(999_999);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment link = relative(arena, 5_000_000_000L);
            long target = ring.prepareRead(closedFd, 8, 0, SqeOptions.IOSQE_IO_LINK);
            long companion = ring.prepareLinkTimeout(link, 0);
            ring.submit();

            List<Result> results = reap(2);
            Result targetResult = byId(results, target);
            Result linkResult = byId(results, companion);
            assertEquals(-EBADF, (int) targetResult.result(), "the failing target keeps its real errno");
            targetResult.freeBuffer();
            assertEquals(-ECANCELED, (int) linkResult.result(),
                    "the linked timeout is disarmed when the target fails first");
        }
    }

    @Test
    void exactlyOneCompletionPerRequestAcrossRepeatedRuns() {
        try (Arena shared = Arena.ofConfined()) {
            MemorySegment targetTs = relative(shared, 3_000_000_000L);
            for (int i = 0; i < 20; i++) {
                MemorySegment linkTs = relative(shared, 1_000_000L);
                long target = ring.prepareTimeout(targetTs, 0, 0, SqeOptions.IOSQE_IO_LINK);
                long companion = ring.prepareLinkTimeout(linkTs, 0);
                ring.submit();
                List<Result> results = reap(2);
                assertEquals(0, ring.inFlightCount(),
                        "iteration " + i + ": ring in-flight must return to 0");
                assertNotEquals(results.get(0).id(), results.get(1).id(),
                        "iteration " + i + ": two distinct completions");
                assertEquals(2, results.stream().map(Result::id).distinct().count());
                assertEquals(-ECANCELED, (int) byId(results, target).result());
                assertEquals(-ETIME, (int) byId(results, companion).result());
            }
        }
    }

    @Test
    void prepareLinkTimeoutRequiresTargetInSameBatchWithIoLink() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ts = relative(arena, 1_000_000L);

            // No preceding SQE at all.
            assertThrows(IllegalStateException.class, () -> ring.prepareLinkTimeout(ts, 0));
            assertEquals(0, ring.inFlightCount(), "rejected prepare must not consume an SQE");

            // Preceding SQE exists but has no IOSQE_IO_LINK.
            ring.prepareNop();
            assertThrows(IllegalStateException.class, () -> ring.prepareLinkTimeout(ts, 0));
            assertEquals(1, ring.inFlightCount(), "only the NOP SQE is queued");

            // The NOP will complete normally at submit; clear it.
            ring.submit();
            ring.waitForResult().close();
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void prepareLinkTimeoutRejectsHeapTimespecBadFlagsAndLinkTimeoutTargets() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeTs = relative(arena, 1_000_000L);

            ring.prepareNop(SqeOptions.IOSQE_IO_LINK);
            assertThrows(IllegalArgumentException.class,
                    () -> ring.prepareLinkTimeout(MemorySegment.ofArray(new byte[16]), 0));
            assertThrows(IllegalArgumentException.class,
                    () -> ring.prepareLinkTimeout(arena.allocate(8L), 0));
            assertThrows(IllegalArgumentException.class,
                    () -> ring.prepareLinkTimeout(nativeTs, JUring.IORING_TIMEOUT_MULTISHOT));
            assertThrows(IllegalArgumentException.class,
                    () -> ring.prepareLinkTimeout(nativeTs, JUring.IORING_TIMEOUT_UPDATE));
            assertEquals(1, ring.inFlightCount(), "rejected prepares must not consume SQEs");

            // First linked timeout is valid; a second one directly after it must be rejected
            // (a linked timeout cannot be the target of another linked timeout).
            long first = ring.prepareLinkTimeout(nativeTs, 0);
            assertTrue(first != 0L);
            assertThrows(IllegalStateException.class, () -> ring.prepareLinkTimeout(nativeTs, 0));

            ring.submit();
            List<Result> results = reap(2);
            assertEquals(-ECANCELED, (int) byId(results, first).result());
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
