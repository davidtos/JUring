package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ownership-ledger tests for the {@link JUringBlocking} registered-buffer pool.
 *
 * <p>{@code checkInBuffer} previously accepted a double check-in and duplicated the index in
 * the lock-free free queue, so two callers could check out and write the same registered
 * buffer. The ledger now rejects double/foreign check-ins and preserves pool invariants under
 * concurrency.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JUringBlockingBufferLedgerTest {

    @Test
    void doubleCheckInIsRejectedAndPoolRemainsConsistent() {
        try (JUringBlocking io = new JUringBlocking(64, 1)) {
            io.registerBuffers(4096, 2);

            int first = io.checkOutBuffer();
            int second = io.checkOutBuffer();
            assertEquals(-1, io.checkOutBuffer(), "both buffers are checked out");

            io.checkInBuffer(first);
            assertThrows(IllegalStateException.class, () -> io.checkInBuffer(first),
                    "a double check-in must not duplicate the index in the free queue");
            assertThrows(IllegalArgumentException.class, () -> io.checkInBuffer(99));

            // The rejected double check-in must not have disturbed ownership: exactly one
            // buffer (second) is still checked out, so the next checkout returns first.
            int reused = io.checkOutBuffer();
            assertEquals(first, reused);
            assertEquals(-1, io.checkOutBuffer());

            io.checkInBuffer(reused);
            io.checkInBuffer(second);
            int availableAgain = io.checkOutBuffer();
            assertTrue(availableAgain >= 0, "both buffers must be available again");
            io.checkInBuffer(availableAgain);
        }
    }

    @Test
    void concurrentCheckoutCheckinKeepsEveryIndexUnique() throws Exception {
        int buffers = 8;
        try (JUringBlocking io = new JUringBlocking(64, 1)) {
            io.registerBuffers(4096, buffers);

            int threads = 4;
            int iterations = 2_000;
            AtomicInteger failures = new AtomicInteger();
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                workers[t] = Thread.ofPlatform().start(() -> {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            int index = io.checkOutBuffer();
                            if (index < 0) {
                                continue; // pool temporarily exhausted
                            }
                            if (index >= buffers) {
                                failures.incrementAndGet();
                            }
                            io.checkInBuffer(index);
                        }
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                    }
                });
            }
            for (Thread worker : workers) {
                worker.join();
            }
            assertEquals(0, failures.get(), "concurrent check-out/check-in must never corrupt the pool");
        }
    }
}
