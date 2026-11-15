package com.davidvlijmincx.lio.api;

/**
 * Handler invoked on a shard thread when an io_uring request registered with
 * {@link ShardContext#onCompletion(long, CompletionHandler)} completes.
 *
 * <p><b>Do not retain the {@link Result}.</b> The reaping loop hands every handler the same
 * reusable {@code Result} instance; copy the fields you need (or the bytes of
 * {@link Result#validBuffer()}) inside the handler and let it go. Retaining it past the
 * handler return is a use-after-reuse bug.
 *
 * <p>Handlers run in completion-queue order on the shard thread, inline with the loop.
 * Exceptions are caught, counted in {@link ShardStats#completionFailures()} and never kill
 * the shard.
 */
@FunctionalInterface
public interface CompletionHandler {
    /**
     * Handles one completion on the shard thread.
     *
     * @param result the completion to handle; revalidated before the next handler runs
     */
    void completed(Result result);
}
