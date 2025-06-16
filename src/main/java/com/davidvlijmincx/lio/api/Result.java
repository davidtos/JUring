package com.davidvlijmincx.lio.api;

public sealed interface Result permits ReadResult, WriteResult, OpenResult, CloseResult {
    long id();
}
