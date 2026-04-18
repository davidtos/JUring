package com.davidvlijmincx.lio.api;

public sealed interface Result permits ReadResult, WriteResult, OpenResult, CloseResult, ReadvResult, AcceptResult, ConnectResult, RecvResult, SendResult {
    long id();
}
