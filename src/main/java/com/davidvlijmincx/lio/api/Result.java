package com.davidvlijmincx.lio.api;

public sealed interface Result permits AcceptResult, CloseResult, OpenResult, ReadResult, RecvResult, SendResult, WriteResult {
    long id();
}
