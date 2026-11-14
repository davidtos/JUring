package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 raw-engine cancellation contract: cancel by {@code user_data}, by fd (plain and fixed
 * slot), ring-wide bulk cancel, statuses ({@code 0}/{@code -ENOENT}), and the guarantee that
 * both the target and the cancel request get exactly one completion. Semantics verified by
 * {@code scratch/b3/Probe.java} E1-E6/H1.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JUringCancellationTest {

    private static final int ETIME = 62;
    private static final int ENOENT = 2;
    private static final int ECANCELED = 125;

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
    void cancelInFlightTimeoutByUserData() {
        try (Arena arena = Arena.ofConfined()) {
            long target = ring.prepareTimeout(relative(arena, 60_000_000_000L), 0, 0);
            ring.submit();
            long cancel = ring.prepareCancelByUserData(target);
            ring.submit();

            List<Result> results = reap(2);
            Result targetResult = byId(results, target);
            Result cancelResult = byId(results, cancel);
            assertEquals(OperationType.CANCEL, cancelResult.type());
            assertEquals(0, (int) cancelResult.result(), "a winning cancel completes with 0");
            assertEquals(-ECANCELED, (int) targetResult.result(),
                    "the cancelled request completes -ECANCELED");
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void cancelUnknownUserDataCompletesEnoent() {
        long cancel = ring.prepareCancelByUserData(0xDEADBEEFL);
        ring.submit();
        Result result = ring.waitForResult();
        assertEquals(cancel, result.id());
        assertEquals(OperationType.CANCEL, result.type());
        assertEquals(-ENOENT, (int) result.result(), "cancel of an unknown request completes -ENOENT");
        assertEquals(0, ring.inFlightCount());
    }

    @Test
    void cancelAlreadyCompletedRequestCompletesEnoentAndDoesNotRedeliverTarget() {
        try (Arena arena = Arena.ofConfined()) {
            long target = ring.prepareTimeout(relative(arena, 10_000_000L));
            ring.submit();
            Result completed = ring.waitForResult();
            assertEquals(target, completed.id());
            assertEquals(-ETIME, (int) completed.result());

            long cancel = ring.prepareCancelByUserData(target);
            ring.submit();
            Result cancelResult = ring.waitForResult();
            assertEquals(cancel, cancelResult.id());
            assertEquals(-ENOENT, (int) cancelResult.result(),
                    "cancel of an already-completed request completes -ENOENT on this kernel");
            assertEquals(0, ring.inFlightCount(), "no extra target completion is posted");
        }
    }

    @Test
    void cancelQueuedInTheSameBatchAsItsTarget() {
        try (Arena arena = Arena.ofConfined()) {
            long target = ring.prepareTimeout(relative(arena, 60_000_000_000L), 0, 0);
            long cancel = ring.prepareCancelByUserData(target);
            ring.submit();

            List<Result> results = reap(2);
            assertEquals(-ECANCELED, (int) byId(results, target).result());
            assertEquals(0, (int) byId(results, cancel).result());
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void bulkCancelByPlainFileDescriptor() {
        int efd = NativeDispatcher.C.eventfd(0, 0);
        try {
            FileDescriptor fd = new FileDescriptor(efd);
            long read = ring.prepareRead(fd, 8, 0); // blocking read on an empty eventfd
            ring.submit();
            long cancel = ring.prepareCancelFile(efd, false);
            ring.submit();

            List<Result> results = reap(2);
            Result readResult = byId(results, read);
            assertEquals(-ECANCELED, (int) readResult.result());
            readResult.freeBuffer();
            Result cancelResult = byId(results, cancel);
            assertEquals(1, (int) cancelResult.result(), "one file request was cancelled");
            assertEquals(0, ring.inFlightCount());
        } finally {
            NativeDispatcher.C.close(efd);
        }
    }

    @Test
    void bulkCancelByFixedFileSlot() {
        int efd = NativeDispatcher.C.eventfd(0, 0);
        try {
            FileDescriptor fd = new FileDescriptor(efd);
            assertEquals(0, ring.registerFiles(fd));
            long read = ring.prepareRead(0, 8, 0); // fixed-file blocking read on empty eventfd
            ring.submit();
            long cancel = ring.prepareCancelFile(0, true);
            ring.submit();

            List<Result> results = reap(2);
            Result readResult = byId(results, read);
            assertEquals(-ECANCELED, (int) readResult.result());
            readResult.freeBuffer();
            assertEquals(1, (int) byId(results, cancel).result());
            assertEquals(0, ring.inFlightCount());
        } finally {
            NativeDispatcher.C.close(efd);
        }
    }

    @Test
    void ringWideBulkCancelReturnsCancelledCount() {
        try (Arena arena = Arena.ofConfined()) {
            int n = 16;
            long[] targets = new long[n];
            MemorySegment ts = relative(arena, 60_000_000_000L);
            for (int i = 0; i < n; i++) {
                targets[i] = ring.prepareTimeout(ts, 0, 0);
            }
            ring.submit();
            assertEquals(n, ring.inFlightCount());

            long cancel = ring.prepareCancelAny();
            ring.submit();
            List<Result> results = reap(n + 1);
            Result cancelResult = byId(results, cancel);
            assertEquals(n, (int) cancelResult.result(),
                    "IORING_ASYNC_CANCEL_ALL|ANY reports the number of cancelled requests");
            for (long target : targets) {
                assertEquals(-ECANCELED, (int) byId(results, target).result(),
                        "every matched request completes exactly once with -ECANCELED");
            }
            assertEquals(0, ring.inFlightCount());
        }
    }

    @Test
    void deprecatedPrepareCancelAliasTargetsUserData() {
        try (Arena arena = Arena.ofConfined()) {
            long target = ring.prepareTimeout(relative(arena, 60_000_000_000L), 0, 0);
            ring.submit();
            long cancel = ring.prepareCancel(target); // deprecated alias of prepareCancelByUserData
            ring.submit();

            List<Result> results = reap(2);
            assertEquals(0, (int) byId(results, cancel).result());
            assertEquals(-ECANCELED, (int) byId(results, target).result());
        }
    }

    @Test
    void cancelOfLinkedTimeoutRequestIsNotSupported() {
        // Frozen contract: a linked timeout cannot be cancelled by its own user_data; the
        // target is the cancellable request. The kernel reports -ENOENT for the companion.
        try (Arena arena = Arena.ofConfined()) {
            long target = ring.prepareTimeout(relative(arena, 60_000_000_000L), 0, 0, SqeOptions.IOSQE_IO_LINK);
            long companion = ring.prepareLinkTimeout(relative(arena, 60_000_000_000L), 0);
            ring.submit();

            long cancel = ring.prepareCancelByUserData(companion);
            ring.submit();
            Result cancelResult = ring.waitForResult();
            assertEquals(cancel, cancelResult.id());
            assertNotEquals(0, (int) cancelResult.result(),
                    "the kernel does not cancel a linked timeout by user_data");
            assertEquals(-ENOENT, (int) cancelResult.result());

            long cancelTarget = ring.prepareCancelByUserData(target);
            ring.submit();
            List<Result> results = reap(3);
            assertEquals(0, (int) byId(results, cancelTarget).result());
            assertEquals(-ECANCELED, (int) byId(results, target).result());
            assertEquals(-ECANCELED, (int) byId(results, companion).result(),
                    "cancelling the target disarms the linked timeout with -ECANCELED");
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
