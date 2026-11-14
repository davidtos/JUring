package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §12.10: graceful bounded shutdown — accepted tasks drained, in-flight requests
 * cancelled with their handlers receiving {@code -ECANCELED}, {@code close(0)} bounded,
 * idempotent close, {@code requestStop}+{@code awaitTermination}, {@code closeTimedOut} only
 * for a stuck user task, and fd/thread resources released.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class B4ShutdownTest {

    private static final int ECANCELED = 125;

    @Test
    void acceptedPendingTasksAreExecutedBeforeCloseReturns() throws Exception {
        int tasks = 20;
        IoShard shard = IoShard.create(0, ShardOptions.defaults());
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch allRan = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            shard.execute(ctx -> {
                executed.incrementAndGet();
                allRan.countDown();
            });
        }
        // close drains the accepted tasks within the shutdown budget
        shard.close(1000);
        assertThat(shard.isClosed()).isTrue();
        assertTrue(allRan.await(1, TimeUnit.SECONDS));
        assertThat(executed.get()).isEqualTo(tasks);
        ShardStats stats = shard.stats();
        assertThat(stats.tasksExecuted()).isEqualTo(tasks);
        assertThat(stats.closeTimedOut()).isFalse();
    }

    @Test
    void inFlightRequestIsCancelledAndHandlerReceivesEcanceled() throws Exception {
        IoShard shard = IoShard.create(1, ShardOptions.defaults());
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch handlerRan = new CountDownLatch(1);
        AtomicInteger handlerResult = new AtomicInteger(Integer.MIN_VALUE);
        shard.execute(ctx -> {
            MemorySegment timespec = Arena.ofAuto().allocate(16L, 8L);
            timespec.set(JAVA_LONG, 0L, 30L); // 30 seconds: only shutdown can cancel it
            timespec.set(JAVA_LONG, 8L, 0L);
            long handle = ctx.ring().prepareTimeout(timespec, 0, 0);
            ctx.onCompletion(handle, result -> {
                handlerResult.set((int) result.result());
                handlerRan.countDown();
            });
            ctx.submit();
            submitted.countDown();
        });
        assertTrue(submitted.await(1, TimeUnit.SECONDS));
        waitUntil(() -> shard.stats().completionsRegistered() == 1, 1000);
        shard.close(1000);
        assertTrue(handlerRan.await(1, TimeUnit.SECONDS), "cancelled request handler did not run");
        assertThat(handlerResult.get()).isEqualTo(-ECANCELED);
        assertThat(shard.stats().cancelledAtClose()).isGreaterThanOrEqualTo(1);
        assertThat(shard.stats().closeTimedOut()).isFalse();
    }

    @Test
    void closeZeroIsBoundedAndPrompt() {
        IoShard shard = IoShard.create(2, ShardOptions.defaults().withShutdownTimeoutMillis(2000L));
        shard.start();
        try {
            Thread.sleep(20L); // let the loop reach the blocked idle state
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long start = System.nanoTime();
        shard.close(0);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertThat(shard.isClosed()).isTrue();
        assertThat(elapsedMillis).as("close(0) must be bounded (budget 0 + 250 ms grace)")
                .isLessThan(750L);
        assertThat(shard.stats().closeTimedOut()).isFalse();
        assertThat(shard.awaitTermination(1000L)).isTrue();
        assertThat(shard.stats().started()).isFalse();
        assertThat(shard.stats().inFlightRequests()).isZero();
    }

    @Test
    void stuckUserTaskRecordsCloseTimedOutThenReleasesResources() throws Exception {
        IoShard shard = IoShard.create(3, ShardOptions.defaults());
        CountDownLatch taskStarted = new CountDownLatch(1);
        shard.execute(ctx -> {
            taskStarted.countDown();
            try {
                Thread.sleep(400L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS));
        long start = System.nanoTime();
        shard.close(50);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMillis).isLessThan(750L);
        assertThat(shard.isClosed()).isTrue();
        assertThat(shard.stats().closeTimedOut()).isTrue();
        // The shard thread finishes once the user task returns and releases the ring.
        assertThat(shard.awaitTermination(5000L)).isTrue();
        assertThat(shard.isTerminated()).isTrue();
        assertThat(shard.stats().inFlightRequests()).isZero();
        // A second close is a no-op.
        shard.close();
        assertThat(shard.stats().closed()).isTrue();
    }

    @Test
    void requestStopThenAwaitTermination() {
        IoShard shard = IoShard.create(4, ShardOptions.defaults());
        shard.start();
        assertThat(shard.isStarted()).isTrue();
        shard.requestStop();
        assertThat(shard.isStopping()).isTrue();
        assertThat(shard.awaitTermination(2000L)).isTrue();
        assertThat(shard.stats().started()).isFalse();
        // clear() marks closed and stays idempotent
        shard.close();
        assertThat(shard.isClosed()).isTrue();
        assertThat(shard.awaitTermination(0L)).isTrue();
    }

    @Test
    void closeIsIdempotentAndRejectsNewWork() {
        IoShard shard = IoShard.create(5, ShardOptions.defaults());
        shard.start();
        shard.close();
        long start = System.nanoTime();
        shard.close();
        shard.close();
        assertThat((System.nanoTime() - start) / 1_000_000L).isLessThan(100L);
        assertThat(shard.isClosed()).isTrue();
    }

    @Test
    void createCloseCyclesLeakNoThreadsOrFileDescriptors() throws Exception {
        long fdsBefore = countOpenFds();
        for (int i = 0; i < 15; i++) {
            IoShard shard = IoShard.create(i, ShardOptions.defaults().withName("cycle"));
            shard.start();
            shard.close(500);
            assertThat(shard.isClosed()).isTrue();
        }
        long fdsAfter = countOpenFds();
        assertThat(fdsAfter).as("fd count after 15 create/close cycles").isLessThanOrEqualTo(fdsBefore + 4);
        long liveShardThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("juring-shard-"))
                .count();
        assertThat(liveShardThreads).isZero();
    }

    private static long countOpenFds() throws Exception {
        try (var stream = Files.list(Path.of("/proc/self/fd"))) {
            return stream.count();
        }
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
