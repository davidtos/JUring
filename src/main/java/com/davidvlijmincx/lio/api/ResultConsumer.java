package com.davidvlijmincx.lio.api;

/**
 * Functional callback interface for zero-allocation completion event processing.
 * <p>
 * <b>Why this change was made:</b>
 * Standard list-based polling methods (e.g. {@code List<Result> peekForBatchResult()}) allocate an {@link java.util.ArrayList}
 * and instantiate a new {@link Result} for every completion. {@code ResultConsumer} enables in-place CQE reaping:
 * the engine iterates directly over the completion ring and notifies the consumer with a reusable {@link Result} instance,
 * achieving true {@code 0 B/op} heap allocation during sustained I/O.
 * <p>
 * <b>Usage note:</b>
 * Because the passed {@link Result} is reused across loop iterations, consumers that need to retain completion state
 * across invocations must copy needed primitive values or create a snapshot via {@code new Result(result)}.
 */
@FunctionalInterface
public interface ResultConsumer {
    void accept(Result result);
}
