package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-owner-thread contract is documented on {@link JUring}; these tests pin that it is
 * actually enforced. Before the fix the contract was advisory: foreign-thread preparation could
 * corrupt an unvalidated ring, and only the optional kernel {@code SINGLE_ISSUER} check rejected
 * it, as an untargeted {@link RuntimeException} from {@code submit()}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JUringOwnerThreadTest {

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static Throwable errorOnOtherThread(ThrowingRunnable action) throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = Thread.ofPlatform()
                .name("juring-foreign")
                .start(() -> {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        error.set(t);
                    }
                });
        thread.join();
        return error.get();
    }

    @Test
    void dataPlaneCallsFromForeignThreadThrowWrongThreadException() throws Exception {
        try (JUring ring = new JUring(8)) {
            assertInstanceOf(WrongThreadException.class, errorOnOtherThread(ring::prepareNop));
            assertInstanceOf(WrongThreadException.class, errorOnOtherThread(ring::submit));
            assertInstanceOf(WrongThreadException.class, errorOnOtherThread(() -> ring.waitForResult()));
            assertInstanceOf(WrongThreadException.class, errorOnOtherThread(() -> ring.registerBuffers(4096, 1)));
            assertInstanceOf(WrongThreadException.class, errorOnOtherThread(() -> ring.setupReadBufferPool(4096, 1)));
        }
    }

    @Test
    void closeFromForeignThreadIsRejectedWhileOpen() throws Exception {
        JUring ring = new JUring(8);
        try {
            assertInstanceOf(WrongThreadException.class, errorOnOtherThread(ring::close),
                    "a foreign thread must not tear down the owner's ring");
            ring.close();
        } finally {
            ring.close(); // idempotent
        }
    }

    @Test
    void afterCloseForeignThreadSeesIllegalStateNotWrongThread() throws Exception {
        JUring ring = new JUring(8);
        ring.close();

        Throwable error = errorOnOtherThread(ring::prepareNop);
        assertInstanceOf(IllegalStateException.class, error);
        assertNull(error.getCause());
    }

    @Test
    void metricsRemainReadableFromForeignThreads() throws Exception {
        try (JUring ring = new JUring(8)) {
            AtomicReference<RingMetrics> metrics = new AtomicReference<>();
            Throwable error = errorOnOtherThread(() -> metrics.set(ring.ringMetrics()));
            assertNull(error);
            assertNotNull(metrics.get());
            assertTrue(metrics.get().cqEntries() > 0);

            // Metrics snapshot stays readable after close, too.
            ring.close();
            Throwable afterClose = errorOnOtherThread(() -> metrics.set(ring.ringMetrics()));
            assertNull(afterClose);
            assertNotNull(metrics.get());
            assertEquals(0L, metrics.get().cqReady());
        }
    }
}
