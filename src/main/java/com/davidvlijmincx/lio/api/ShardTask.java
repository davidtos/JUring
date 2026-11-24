package com.davidvlijmincx.lio.api;

/**
 * A unit of work executed on a shard's dedicated platform thread.
 *
 * <p>Tasks run on the {@link IoShard}'s event-loop thread: they may use {@link ShardContext#ring()},
 * prepare operations, register completion handlers and hand continuations to other shards.
 * They must not block that thread (e.g. wait on another shard) — a blocked task stalls the
 * whole shard. Exceptions thrown by a task are caught, counted in
 * {@link ShardStats#taskFailures()} and never kill the shard.
 */
@FunctionalInterface
public interface ShardTask {
    /**
     * Runs this unit of work on the shard thread.
     *
     * @param ctx the shard-thread-confined context of the executing shard
     */
    void run(ShardContext ctx);
}
