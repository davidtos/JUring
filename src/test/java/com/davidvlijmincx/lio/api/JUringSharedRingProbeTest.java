package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-regression: a ring created with {@code IORING_SETUP_ATTACH_WQ} must probe its own optional
 * opcode support. The dispatcher's support cache is per ring, so before the fix an attached ring
 * reported every optional opcode as unsupported and {@code prepareTimeout}/{@code statx}/
 * {@code fallocate}/{@code ftruncate} threw {@link UnsupportedOperationException} on a kernel
 * that supports them on the parent.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JUringSharedRingProbeTest {

    private static final int ETIME = 62;

    @Test
    void attachedRingProbesOptionalOpcodesAndRunsTimeout() {
        try (JUring parent = new JUring(16,
                IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN);
             JUring shared = parent.getSharedWorkerRing(16,
                     IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN)) {

            assertEquals(parent.supportsTimeout(), shared.supportsTimeout(),
                    "attached-ring timeout probe must match the parent");
            assertEquals(parent.supportsStatx(), shared.supportsStatx());
            assertEquals(parent.supportsFallocate(), shared.supportsFallocate());
            assertEquals(parent.supportsFtruncate(), shared.supportsFtruncate());
            assertEquals(parent.supportsLinkTimeout(), shared.supportsLinkTimeout());

            Assumptions.assumeTrue(shared.supportsTimeout(), "kernel does not advertise IORING_OP_TIMEOUT");

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment timespec = arena.allocate(JUring.TIMESPEC_BYTES, 8);
                timespec.set(JAVA_LONG, 0L, 0L);
                timespec.set(JAVA_LONG, 8L, 5_000_000L); // 5 ms

                shared.prepareTimeout(timespec);
                shared.submit();
                try (Result result = shared.waitForResult()) {
                    assertEquals(-ETIME, (int) result.result(),
                            "the attached ring must actually execute the probed timeout opcode");
                }
            }
            assertTrue(shared.ringMetrics().inFlight() == 0L);
        }
    }
}
