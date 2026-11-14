package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §9 (revised): package-private loop-body seam used by the {@code JUringBlocking}
 * integration. A body runs on the shard thread with ring/dispatcher access, IoShard keeps
 * ownership of ring creation, bounded close and stats, and generic-loop entry points are
 * rejected for body-hosted shards.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class B4BodySeamTest {

    @Test
    void ringGroupHostsOneBodyPerUnstartedShard() throws Exception {
        RingGroup group = RingGroup.create(1, ShardOptions.defaults());
        AtomicReference<Thread> bodyThread = new AtomicReference<>();
        AtomicReference<JUring> bodyRing = new AtomicReference<>();
        AtomicReference<IoUringDispatcher> bodyDispatcher = new AtomicReference<>();
        AtomicReference<Integer> bodyShardId = new AtomicReference<>();
        AtomicBoolean bodySawStop = new AtomicBoolean();
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch bodyExited = new CountDownLatch(1);
        group.installBody(0, new ShardBody() {
            @Override
            public void run(ShardLoopContext ctx) {
                bodyThread.set(Thread.currentThread());
                bodyRing.set(ctx.ring());
                bodyDispatcher.set(ctx.dispatcher());
                bodyShardId.set(ctx.shardId());
                ctx.countRingSubmit();
                bodyStarted.countDown();
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!ctx.stopRequested() && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(500_000L);
                }
                bodySawStop.set(ctx.stopRequested());
                bodyExited.countDown();
            }
        });
        group.start();
        try {
            assertTrue(bodyStarted.await(1, TimeUnit.SECONDS), "body did not start");
            assertThat(bodyThread.get()).isSameAs(group.shard(0).shardThread());
            assertThat(bodyRing.get()).isNotNull();
            assertThat(bodyRing.get().isClosed()).isFalse();
            assertThat(bodyDispatcher.get()).isNotNull();
            assertThat(bodyDispatcher.get().sqRingEntries()).isGreaterThan(0);
            assertThat(bodyShardId.get()).isZero();
            assertThat(group.shard(0).isStarted()).isTrue();
            assertThatThrownBy(() -> group.shard(0).setBody(ctx -> {
            })).isInstanceOf(IllegalStateException.class);

            long start = System.nanoTime();
            group.close(500);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(bodyExited.await(1, TimeUnit.SECONDS), "body was not stopped");
            assertThat(bodySawStop.get()).isTrue();
            assertThat(elapsedMillis).isLessThan(1500L);
            assertThat(group.shard(0).isTerminated()).isTrue();
            assertThat(group.shard(0).isLoopExited()).isTrue();
            assertThat(group.shard(0).stats().closed()).isTrue();
            assertThat(group.shard(0).stats().ringSubmits()).isEqualTo(1);
            assertThat(group.shard(0).stats().inFlightRequests()).isZero();
            // Generic-loop entry points are rejected on a body-hosted shard.
            assertThatThrownBy(() -> group.shard(0).execute(ctx -> {
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("body-hosted");
        } finally {
            group.close(500);
        }
    }

    @Test
    void standaloneShardStartWithBodyAndBodyLifecycle() throws Exception {
        IoShard shard = IoShard.create(0, ShardOptions.defaults());
        CountDownLatch bodyRan = new CountDownLatch(1);
        shard.startWithBody(ctx -> {
            bodyRan.countDown();
            while (!ctx.stopRequested()) {
                LockSupport.parkNanos(500_000L);
            }
        });
        try {
            assertTrue(bodyRan.await(1, TimeUnit.SECONDS));
            assertThat(shard.isStarted()).isTrue();
            assertThat(shard.stats().started()).isTrue();
        } finally {
            shard.close(500);
        }
        assertThat(shard.isTerminated()).isTrue();
        assertThat(shard.stats().closed()).isTrue();
        assertThat(shard.isClosed()).isTrue();
    }

    @Test
    void bodyCannotBeInstalledAfterStartAndStartWithBodyRequiresNonNull() {
        IoShard shard = IoShard.create(0, ShardOptions.defaults());
        shard.start();
        try {
            assertThatThrownBy(() -> shard.setBody(ctx -> {
            })).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> shard.startWithBody(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> shard.setBody(null)).isInstanceOf(NullPointerException.class);
        } finally {
            shard.close();
        }
    }
}
