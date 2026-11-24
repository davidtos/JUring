package com.davidvlijmincx.lio.api;

/**
 * Functional callback interface for zero-allocation completion event processing.
 */
@FunctionalInterface
public interface ResultConsumer {
    /**
     * Consumes one reaped completion.
     *
     * <p>The same reusable {@link Result} instance is passed to every invocation, so the
     * result must not be retained; copy the fields (or the bytes of
     * {@link Result#validBuffer()}) that are needed beyond the call.
     *
     * @param result the completion to consume
     */
    void accept(Result result);
}
