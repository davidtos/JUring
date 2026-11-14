package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §12.1: shard lifecycle (create/start/execute/close, idempotency, misuse,
 * stats before/after, {@code call} before/after start and after close).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class B4LifecycleTest {

    @Test
    void createStartExecuteCloseAndStats() throws Exception {
        IoShard shard = IoShard.create(7, ShardOptions.defaults().withName("lifecycle"));
        try {
            assertThat(shard.id()).isEqualTo(7);
            assertThat(shard.isStarted()).isFalse();
            assertThat(shard.isClosed()).isFalse();
            assertThat(shard.shardThread()).isNull();
            assertThat(shard.inShardThread()).isFalse();
            ShardStats before = shard.stats();
            assertThat(before.started()).isFalse();
            assertThat(before.closed()).isFalse();
            assertThat(before.threadName()).isNull();
            assertThat(before.inFlightRequests()).isZero();
            assertThat(before.requestedCpu()).isEqualTo(ShardOptions.NO_CPU_AFFINITY);

            shard.start();
            assertThat(shard.isStarted()).isTrue();
            assertThat(shard.shardThread()).isNotNull();
            assertThat(shard.shardThread().isDaemon()).isTrue();
            assertThat(shard.shardThread().getName()).isEqualTo("juring-shard-7-lifecycle");
            assertThat(shard.stats().threadName()).isEqualTo("juring-shard-7-lifecycle");

            CountDownLatch ran = new CountDownLatch(1);
            AtomicReference<Thread> taskThread = new AtomicReference<>();
            AtomicReference<JUring> taskRing = new AtomicReference<>();
            shard.execute(ctx -> {
                taskThread.set(Thread.currentThread());
                taskRing.set(ctx.ring());
                assertThat(ctx.shardId()).isEqualTo(7);
                assertThat(shard.inShardThread()).isTrue();
                assertThat(ctx.stopping()).isFalse();
                ran.countDown();
            });
            assertTrue(ran.await(1, TimeUnit.SECONDS), "task did not run");
            assertThat(taskThread.get()).isSameAs(shard.shardThread());
            assertThat(taskRing.get()).isNotNull();
            assertThat(shard.stats().tasksSubmitted()).isEqualTo(1);
            assertThat(shard.stats().tasksExecuted()).isEqualTo(1);
        } finally {
            shard.close();
        }
        ShardStats after = shard.stats();
        assertThat(after.closed()).isTrue();
        assertThat(after.started()).isFalse();
        assertThat(after.inFlightRequests()).isZero();
        assertThat(shard.isClosed()).isTrue();
        assertThat(shard.isStarted()).isFalse();
        // Stats stay readable after close and across further close calls.
        shard.close();
        shard.close();
        assertThat(shard.stats().closed()).isTrue();
    }

    @Test
    void executeAfterCloseThrowsAndStartAfterCloseThrows() {
        IoShard shard = IoShard.create(0, ShardOptions.defaults());
        shard.start();
        shard.close();
        assertThatThrownBy(() -> shard.execute(ctx -> {
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> shard.handoff(() -> {
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> shard.call(() -> 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(shard::start).isInstanceOf(IllegalStateException.class);
        assertThat(shard.stats().tasksRejected()).isPositive();
    }

    @Test
    void closeFromShardThreadThrowsIllegalState() throws Exception {
        IoShard shard = IoShard.create(1, ShardOptions.defaults());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        shard.execute(ctx -> {
            try {
                shard.close();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                ran.countDown();
            }
        });
        assertTrue(ran.await(1, TimeUnit.SECONDS));
        assertThat(thrown.get()).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.get()).hasMessageContaining("own thread");
        shard.close();
        assertThat(shard.isClosed()).isTrue();
    }

    @Test
    void closeBeforeStartIsNoOpAndStartAfterwardThrows() {
        IoShard shard = IoShard.create(2, ShardOptions.defaults());
        shard.close();
        assertThat(shard.isClosed()).isTrue();
        assertThat(shard.stats().closed()).isTrue();
        assertThatThrownBy(shard::start).isInstanceOf(IllegalStateException.class);
        shard.close();
    }

    @Test
    void callRunsOnShardThreadBeforeAndAfterStartAndFailsAfterClose() {
        IoShard shard = IoShard.create(3, ShardOptions.defaults());
        try {
            // call auto-starts the shard
            Thread thread = shard.call(() -> Thread.currentThread());
            assertThat(thread).isSameAs(shard.shardThread());
            assertThat(shard.call(() -> 42)).isEqualTo(42);
            // call from the shard thread itself runs inline
            assertThat(shard.call(() -> shard.call(() -> 7))).isEqualTo(7);
        } finally {
            shard.close();
        }
        assertThatThrownBy(() -> shard.call(() -> 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidOptionsAndArgumentsAreRejected() {
        assertThatThrownBy(() -> IoShard.create(-1, ShardOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IoShard.create(0, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withRingEntries(7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withInboundCapacity(3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withInboundCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withTaskBatch(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withCqeBatch(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withSpinIterations(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withShutdownTimeoutMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withCpuAffinity(-2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardOptions.defaults().withName(""))
                .isInstanceOf(IllegalArgumentException.class);

        IoShard shard = IoShard.create(0, ShardOptions.defaults());
        assertThatThrownBy(() -> shard.close(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> shard.awaitTermination(-1)).isInstanceOf(IllegalArgumentException.class);
        shard.close();
    }

    @Test
    void optionsCopySemanticsMatchFrozenContract() {
        ShardOptions defaults = ShardOptions.defaults();
        assertThat(defaults.ringEntries()).isEqualTo(ShardOptions.DEFAULT_RING_ENTRIES);
        assertThat(defaults.taskBatch()).isEqualTo(ShardOptions.DEFAULT_TASK_BATCH);
        assertThat(defaults.cqeBatch()).isEqualTo(ShardOptions.DEFAULT_CQE_BATCH);
        assertThat(defaults.spinIterations()).isEqualTo(ShardOptions.DEFAULT_SPIN_ITERATIONS);
        assertThat(defaults.inboundCapacity()).isEqualTo(ShardOptions.DEFAULT_INBOUND_CAPACITY);
        assertThat(defaults.shutdownTimeoutMillis()).isEqualTo(ShardOptions.DEFAULT_SHUTDOWN_TIMEOUT_MILLIS);
        assertThat(defaults.ringOptions()).containsExactly(
                IoUringOptions.IORING_SETUP_SINGLE_ISSUER,
                IoUringOptions.IORING_SETUP_COOP_TASKRUN,
                IoUringOptions.IORING_SETUP_DEFER_TASKRUN);

        // defensive copy of the ring-options array
        IoUringOptions[] fromGetter = defaults.ringOptions();
        fromGetter[0] = IoUringOptions.IORING_SETUP_IOPOLL;
        assertThat(defaults.ringOptions()[0]).isEqualTo(IoUringOptions.IORING_SETUP_SINGLE_ISSUER);

        // withRingOptions replaces; zero varargs means "no flags"
        assertThat(defaults.withRingOptions().ringOptions()).isEmpty();
        assertThat(defaults.withRingOptions(IoUringOptions.IORING_SETUP_SINGLE_ISSUER).ringOptions())
                .containsExactly(IoUringOptions.IORING_SETUP_SINGLE_ISSUER);
        // immutability
        ShardOptions modified = defaults.withName("x").withRingEntries(512).withCpuAffinity(2);
        assertThat(defaults.name()).isEqualTo("shard");
        assertThat(defaults.ringEntries()).isEqualTo(ShardOptions.DEFAULT_RING_ENTRIES);
        assertThat(modified.name()).isEqualTo("x");
        assertThat(modified.ringEntries()).isEqualTo(512);
        assertThat(modified.cpuAffinity()).isEqualTo(2);
    }
}
