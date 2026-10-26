package com.davidvlijmincx.lio.api;

/**
 * Functional callback interface for zero-allocation completion event processing.
 */
@FunctionalInterface
public interface ResultConsumer {
    void accept(Result result);
}
