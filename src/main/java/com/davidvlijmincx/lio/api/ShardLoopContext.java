package com.davidvlijmincx.lio.api;

/**
 * Package-private shard-thread-confined context handed to a {@link ShardBody}
 * (B4 design §9). All accessors are valid only on the shard thread.
 */
final class ShardLoopContext {

    private final IoShard shard;

    ShardLoopContext(IoShard shard) {
        this.shard = shard;
    }

    int shardId() {
        return shard.id();
    }

    /** The shard's ring (created on, and confined to, the shard thread). */
    JUring ring() {
        return shard.requireRing();
    }

    /** The {@link IoUringDispatcher} underlying {@link #ring()} (shard-thread confined). */
    IoUringDispatcher dispatcher() {
        return shard.requireRing().dispatcher();
    }

    /** Volatile read of the shard's stopping flag; becomes {@code true} before any stop wake. */
    boolean stopRequested() {
        return shard.isStopping();
    }

    /** Optional stats hook: counts one ring submit that actually flushed SQEs. */
    void countRingSubmit() {
        shard.recordRingSubmit();
    }
}
