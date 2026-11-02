package com.davidvlijmincx.lio.api.functions;

/**
 * Downcall interface for {@code eventfd(2)}.
 */
public interface Eventfd {

    int eventfd(int initval, int flags);
}
