package com.davidvlijmincx.lio.api;

@FunctionalInterface
public interface ResultConsumer {
    void accept(Result result);
}
