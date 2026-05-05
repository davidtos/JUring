package com.davidvlijmincx.lio.api;

public sealed interface Result permits AcceptResult, CloseResult, ConnectResult, OpenResult, ReadResult, ReadResultFixed, ReadvResult, RecvResult, SendResult, WriteResult {
    long id();
}
