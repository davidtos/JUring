package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 verifier regression: removal from a full {@link CompletionTable} must be bounded and
 * correct (the previous cluster re-insertion implementation could cycle forever when the
 * table was full and the next probe slot was occupied), plus the IoShard-level scenario where
 * completions are dispatched while the handler table is exactly full.
 *
 * <p>Design contract (§4/§5/§9.3 amended 2026-11-14): table-full registration is
 * {@link IllegalStateException}; earlier-prepared-but-unregistered SQEs may still complete as
 * counted {@code completionsUnattributed}; handler-table probes are bounded and always
 * terminate.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class B4CompletionTableTest {

    private static CompletionHandler newHandler() {
        return new CompletionHandler() {
            @Override
            public void completed(Result result) {
            }
        };
    }

    private static long keyFor(int index) {
        // Spread keys so clusters and wrap-around probes occur.
        return 0x1000_0000_0000L + index * 0x9E3779B97F4A7C15L;
    }

    @Test
    void directChurnMatchesShadowModel() {
        int capacity = 64;
        int distinctKeys = 4 * capacity;
        int operations = 100_000;
        CompletionTable table = new CompletionTable(capacity);
        Map<Long, CompletionHandler> shadow = new HashMap<>();
        Random random = new Random(42L);
        int duplicatesRejected = 0;
        int missingRemoves = 0;
        int fullRejections = 0;
        for (int i = 0; i < operations; i++) {
            long key = keyFor(random.nextInt(distinctKeys));
            if (random.nextBoolean()) {
                if (shadow.containsKey(key)) {
                    int sizeBefore = table.size();
                    assertThatThrownBy(() -> table.put(key, newHandler()))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(table.size()).isEqualTo(sizeBefore);
                    duplicatesRejected++;
                } else if (shadow.size() >= capacity) {
                    int sizeBefore = table.size();
                    assertThatThrownBy(() -> table.put(key, newHandler()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("full");
                    assertThat(table.size()).isEqualTo(sizeBefore);
                    fullRejections++;
                } else {
                    CompletionHandler handler = newHandler();
                    table.put(key, handler);
                    shadow.put(key, handler);
                }
            } else {
                CompletionHandler expected = shadow.remove(key);
                CompletionHandler actual = table.remove(key);
                if (expected == null) {
                    missingRemoves++;
                    assertThat(actual).isNull();
                } else {
                    assertThat(actual).isSameAs(expected);
                }
            }
            if ((i & 1023) == 0) {
                assertThat(table.size()).isEqualTo(shadow.size());
            }
        }
        assertThat(table.size()).isEqualTo(shadow.size());
        assertThat(duplicatesRejected).isPositive();
        assertThat(missingRemoves).isPositive();
        assertThat(fullRejections).isPositive();
    }

    @Test
    void fillToCapacityThenRemoveAllAndReinsert() {
        int capacity = 64;
        CompletionTable table = new CompletionTable(capacity);
        Map<Long, CompletionHandler> shadow = new HashMap<>();
        for (int i = 0; i < capacity; i++) {
            CompletionHandler handler = newHandler();
            table.put(keyFor(i), handler);
            shadow.put(keyFor(i), handler);
        }
        assertThat(table.size()).isEqualTo(capacity);
        assertThat(table.isFull()).isTrue();
        assertThatThrownBy(() -> table.put(keyFor(capacity), newHandler()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(table.remove(keyFor(capacity))).isNull();

        // Remove in an adversarial order: the old implementation could cycle on the first
        // removal from a full table.
        for (int i = 0; i < capacity; i++) {
            int keyIndex = (i * 37) % capacity;
            CompletionHandler expected = shadow.remove(keyFor(keyIndex));
            CompletionHandler actual = table.remove(keyFor(keyIndex));
            assertThat(actual).isSameAs(expected);
        }
        assertThat(table.size()).isZero();

        // Re-insert everything and verify every key is still findable exactly once.
        for (int i = 0; i < capacity; i++) {
            CompletionHandler handler = newHandler();
            table.put(keyFor(i), handler);
            shadow.put(keyFor(i), handler);
        }
        for (int i = 0; i < capacity; i++) {
            assertThat(table.remove(keyFor(i))).isSameAs(shadow.remove(keyFor(i)));
        }
        assertThat(table.size()).isZero();
    }

    @Test
    void fullTableRemovalIsBoundedAndCorrect() {
        int capacity = 64;
        CompletionTable table = new CompletionTable(capacity);
        for (int i = 0; i < capacity; i++) {
            table.put(keyFor(i), newHandler());
        }
        assertThat(table.size()).isEqualTo(capacity);
        long start = System.nanoTime();
        for (int i = 0; i < capacity; i++) {
            assertThat(table.remove(keyFor(i))).isNotNull();
        }
        assertThat(table.size()).isZero();
        assertThat((System.nanoTime() - start) / 1_000_000L).isLessThan(1000L);
    }

    @Test
    void putRemovePutSameKeyRepeatedlyStaysConsistent() {
        int capacity = 32;
        CompletionTable table = new CompletionTable(capacity);
        for (int round = 0; round < 10_000; round++) {
            long key = keyFor(round % capacity);
            CompletionHandler handler = newHandler();
            table.put(key, handler);
            assertThat(table.size()).isEqualTo(1);
            assertThat(table.remove(key)).isSameAs(handler);
            assertThat(table.size()).isZero();
            assertThat(table.remove(key)).isNull();
        }
    }

    // ------------------------------------------------------------------
    // IoShard-level regression (verifier F1 scenario, small scale)
    // ------------------------------------------------------------------

    private static ShardOptions tableFullOptions() {
        return ShardOptions.defaults()
                .withRingEntries(512)   // completion table = nextPow2(max(1024, 2*512)) = 1024
                .withTaskBatch(8)
                .withCqeBatch(64)
                .withSpinIterations(64);
    }

    /**
     * Fills the shard's 1024-slot handler table exactly to capacity with 1024 in-flight NOPs
     * and lets the loop dispatch them. Before the fix the first dispatch (a removal from a
     * full table) spun forever on the shard thread.
     */
    @Test
    void shardDoesNotWedgeWhenCompletionsRemoveFromFullTable() throws Exception {
        int operations = 1024; // == completion-table capacity for ringEntries=512
        IoShard shard = IoShard.create(0, tableFullOptions());
        try {
            CountDownLatch done = new CountDownLatch(operations);
            AtomicInteger dispatched = new AtomicInteger();
            CountDownLatch taskReturned = new CountDownLatch(1);
            shard.execute(ctx -> {
                for (int i = 0; i < operations; i++) {
                    long handle = ctx.ring().prepareNop();
                    ctx.onCompletion(handle, result -> {
                        dispatched.incrementAndGet();
                        done.countDown();
                    });
                    if (i == operations / 2 - 1) {
                        ctx.submit();
                    }
                }
                ctx.submit();
                taskReturned.countDown();
            });
            assertTrue(taskReturned.await(2, TimeUnit.SECONDS));
            assertTrue(done.await(5, TimeUnit.SECONDS), "shard wedged: full-table removals did not complete");
            assertThat(dispatched.get()).isEqualTo(operations);
            assertThat(shard.stats().completionsUnattributed()).isZero();
            // The loop is still healthy: a subsequent task runs and close is bounded.
            CountDownLatch after = new CountDownLatch(1);
            shard.execute(ctx -> after.countDown());
            assertTrue(after.await(1, TimeUnit.SECONDS), "loop did not process a task after full-table dispatch");
        } finally {
            shard.close(500);
        }
        assertThat(shard.stats().closeTimedOut()).isFalse();
        assertThat(shard.awaitTermination(1000L)).isTrue();
        assertThat(shard.stats().inFlightRequests()).isZero();
    }

    /**
     * A table-full registration is an ISE (before further submit), and the already-prepared
     * SQE still completes and is counted in {@code completionsUnattributed}; the shard loop
     * survives and close remains bounded.
     */
    @Test
    void tableFullRegistrationThrowsIseAndPreparedSqeCompletesUnattributed() throws Exception {
        int dedicated = 1024; // fills the handler table exactly
        IoShard shard = IoShard.create(1, tableFullOptions());
        try {
            AtomicBoolean registrationRejected = new AtomicBoolean();
            String[] rejectionMessage = new String[1];
            CountDownLatch taskReturned = new CountDownLatch(1);
            CountDownLatch dedicatedDone = new CountDownLatch(dedicated);
            shard.execute(ctx -> {
                for (int i = 0; i < dedicated; i++) {
                    long handle = ctx.ring().prepareNop();
                    ctx.onCompletion(handle, result -> dedicatedDone.countDown());
                    if (i == dedicated / 2 - 1) {
                        ctx.submit();
                    }
                }
                ctx.submit();
                // Table is now full: prepare one more request, and registration must be ISE.
                long extra = ctx.ring().prepareNop();
                try {
                    ctx.onCompletion(extra, result -> {
                    });
                } catch (IllegalStateException e) {
                    registrationRejected.set(true);
                    rejectionMessage[0] = e.getMessage();
                }
                ctx.submit();
                taskReturned.countDown();
            });
            assertTrue(taskReturned.await(2, TimeUnit.SECONDS));
            assertTrue(dedicatedDone.await(5, TimeUnit.SECONDS), "dedicated completions did not dispatch");
            assertThat(registrationRejected.get()).isTrue();
            assertThat(rejectionMessage[0]).contains("full");
            waitUntil(() -> shard.stats().completionsUnattributed() >= 1, 2000);
            assertThat(shard.stats().completionsUnattributed()).isGreaterThanOrEqualTo(1);
            assertThat(shard.stats().completionsDispatched()).isEqualTo(dedicated);
            CountDownLatch after = new CountDownLatch(1);
            shard.execute(ctx -> after.countDown());
            assertTrue(after.await(1, TimeUnit.SECONDS), "loop did not survive the table-full scenario");
        } finally {
            shard.close(500);
        }
        assertThat(shard.stats().closeTimedOut()).isFalse();
        assertThat(shard.awaitTermination(1000L)).isTrue();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(1L);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition not met within " + timeoutMillis + " ms");
        }
    }
}
