package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §12.5/§12.6/§12.11: task and handler exception isolation, completion dispatch
 * (exactly once, shard thread, CQ order, unattributed accounting) and batched reaping.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class B4CompletionTest {

    @TempDir
    Path tempDir;

    private static ShardOptions fastOptions() {
        return ShardOptions.defaults()
                .withRingEntries(256)
                .withRingOptions(IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN)
                .withSpinIterations(64);
    }

    @Test
    void readsDispatchExactlyOneHandlerPerOperationOnShardThread() throws Exception {
        Path file = tempDir.resolve("reads.bin");
        byte[] content = new byte[4096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31 + 7);
        }
        Files.write(file, content);

        int operations = 32;
        int readSize = 128;
        IoShard shard = IoShard.create(0, fastOptions().withCqeBatch(8).withTaskBatch(16));
        FileDescriptor fd = new FileDescriptor(file, READ);
        try {
            CountDownLatch done = new CountDownLatch(operations);
            AtomicInteger dispatches = new AtomicInteger();
            AtomicInteger wrongThread = new AtomicInteger();
            AtomicInteger wrongBytes = new AtomicInteger();
            AtomicReference<Throwable> handlerError = new AtomicReference<>();

            shard.execute(ctx -> {
                assertThat(shard.stats().wakeupArmed()).isFalse();
                MemorySegment buffers = Arena.ofAuto().allocate((long) operations * readSize, 64);
                long[] handles = new long[operations];
                for (int i = 0; i < operations; i++) {
                    MemorySegment buffer = buffers.asSlice((long) i * readSize, readSize);
                    handles[i] = ctx.ring().prepareRead(fd, buffer, (long) i * readSize);
                }
                for (int i = 0; i < operations; i++) {
                    final int index = i;
                    ctx.onCompletion(handles[i], result -> {
                        try {
                            dispatches.incrementAndGet();
                            if (Thread.currentThread() != shard.shardThread()) {
                                wrongThread.incrementAndGet();
                            }
                            if (!result.isSuccess() || result.result() != readSize) {
                                wrongBytes.incrementAndGet();
                            }
                            MemorySegment data = result.validBuffer();
                            if (data.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L) != content[index * readSize]) {
                                wrongBytes.incrementAndGet();
                            }
                        } catch (Throwable t) {
                            handlerError.compareAndSet(null, t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                ctx.submit();
            });
            assertTrue(done.await(2, TimeUnit.SECONDS), "not all read completions dispatched");
            assertThat(handlerError.get()).isNull();
            assertThat(wrongThread.get()).isZero();
            assertThat(wrongBytes.get()).isZero();
            assertThat(dispatches.get()).isEqualTo(operations);
            waitUntil(() -> shard.stats().inFlightRequests() == 0, 1000);
            ShardStats stats = shard.stats();
            assertThat(stats.completionsRegistered()).isEqualTo(operations);
            assertThat(stats.completionsDispatched()).isEqualTo(operations);
            assertThat(stats.completionsUnattributed()).isZero();
            assertThat(stats.completionFailures()).isZero();
        } finally {
            shard.close();
            fd.close();
        }
    }

    @Test
    void unregisteredCompletionIsCountedUnattributedNotFatal() throws Exception {
        IoShard shard = IoShard.create(0, fastOptions());
        try {
            CountDownLatch submitted = new CountDownLatch(1);
            shard.execute(ctx -> {
                ctx.ring().prepareNop(); // no onCompletion: its CQE is unattributed
                ctx.submit();
                submitted.countDown();
            });
            assertTrue(submitted.await(1, TimeUnit.SECONDS));
            waitUntil(() -> shard.stats().completionsUnattributed() >= 1, 1000);
            assertThat(shard.stats().completionsUnattributed()).isGreaterThanOrEqualTo(1);
            // The shard is still alive: a following task still runs.
            CountDownLatch after = new CountDownLatch(1);
            shard.execute(ctx -> after.countDown());
            assertTrue(after.await(1, TimeUnit.SECONDS), "loop survived the unattributed completion");
        } finally {
            shard.close();
        }
    }

    @Test
    void taskAndHandlerExceptionsAreIsolatedAndCounted() throws Exception {
        IoShard shard = IoShard.create(1, fastOptions());
        try {
            CountDownLatch taskFailed = new CountDownLatch(1);
            CountDownLatch nextTaskRan = new CountDownLatch(1);
            shard.execute(ctx -> {
                throw new RuntimeException("boom-task");
            });
            shard.execute(ctx -> nextTaskRan.countDown());
            assertTrue(nextTaskRan.await(1, TimeUnit.SECONDS), "loop survived the throwing task");
            waitUntil(() -> shard.stats().taskFailures() >= 1, 1000);
            assertThat(shard.stats().taskFailures()).isGreaterThanOrEqualTo(1);

            CountDownLatch handlerDone = new CountDownLatch(1);
            CountDownLatch secondHandlerDone = new CountDownLatch(1);
            shard.execute(ctx -> {
                long first = ctx.ring().prepareNop();
                long second = ctx.ring().prepareNop();
                ctx.onCompletion(first, result -> {
                    handlerDone.countDown();
                    throw new IllegalStateException("boom-handler");
                });
                ctx.onCompletion(second, result -> secondHandlerDone.countDown());
                ctx.submit();
            });
            assertTrue(handlerDone.await(1, TimeUnit.SECONDS));
            assertTrue(secondHandlerDone.await(1, TimeUnit.SECONDS), "loop survived the throwing handler");
            waitUntil(() -> shard.stats().completionFailures() >= 1, 1000);
            assertThat(shard.stats().completionFailures()).isGreaterThanOrEqualTo(1);
            assertThat(shard.stats().completionsDispatched()).isEqualTo(2);
        } finally {
            shard.close();
        }
    }

    @Test
    void completionsAreServicedBeforeTheNextTaskBatch() throws Exception {
        IoShard shard = IoShard.create(0, fastOptions().withTaskBatch(1));
        try {
            AtomicInteger dispatchedBeforeNextTask = new AtomicInteger(-1);
            AtomicInteger dispatchCount = new AtomicInteger();
            CountDownLatch ran = new CountDownLatch(1);
            shard.execute(ctx -> {
                long handle = ctx.ring().prepareNop();
                ctx.onCompletion(handle, result -> dispatchCount.incrementAndGet());
                ctx.submit();
            });
            shard.execute(ctx -> {
                dispatchedBeforeNextTask.set(dispatchCount.get());
                ran.countDown();
            });
            assertTrue(ran.await(1, TimeUnit.SECONDS));
            assertThat(dispatchedBeforeNextTask.get()).isEqualTo(1);
        } finally {
            shard.close();
        }
    }

    @Test
    void cqeBatchOfOneStillCompletesBurst() throws Exception {
        int operations = 64;
        IoShard shard = IoShard.create(0, fastOptions().withCqeBatch(1));
        try {
            CountDownLatch done = new CountDownLatch(operations);
            AtomicInteger dispatches = new AtomicInteger();
            shard.execute(ctx -> {
                for (int i = 0; i < operations; i++) {
                    long handle = ctx.ring().prepareNop();
                    ctx.onCompletion(handle, result -> {
                        dispatches.incrementAndGet();
                        done.countDown();
                    });
                }
                ctx.submit();
            });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertThat(dispatches.get()).isEqualTo(operations);
            waitUntil(() -> shard.stats().inFlightRequests() == 0, 1000);
            assertThat(shard.stats().inFlightRequests()).isZero();
        } finally {
            shard.close();
        }
    }

    @Test
    void largeBurstOfFourThousandNinetySixOpsCompletesExactlyOnce() throws Exception {
        int operations = 4096;
        IoShard shard = IoShard.create(0, ShardOptions.defaults()
                .withRingEntries(4096)
                .withCqeBatch(256)
                .withTaskBatch(64)
                .withSpinIterations(64));
        try {
            CountDownLatch done = new CountDownLatch(operations);
            AtomicInteger dispatches = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            shard.execute(ctx -> {
                for (int i = 0; i < operations; i++) {
                    long handle = ctx.ring().prepareNop();
                    ctx.onCompletion(handle, result -> {
                        dispatches.incrementAndGet();
                        done.countDown();
                    });
                }
                ctx.submit();
            });
            assertTrue(done.await(10, TimeUnit.SECONDS), "large burst did not complete");
            waitUntil(() -> shard.stats().inFlightRequests() == 0, 2000);
            assertThat(failure.get()).isNull();
            assertThat(dispatches.get()).isEqualTo(operations);
            assertThat(shard.stats().completionsDispatched()).isEqualTo(operations);
            assertThat(shard.stats().ringSubmits()).isGreaterThanOrEqualTo(1);
        } finally {
            shard.close();
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
