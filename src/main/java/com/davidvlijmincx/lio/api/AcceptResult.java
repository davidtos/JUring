package com.davidvlijmincx.lio.api;

public record AcceptResult(long id, int clientFd) implements Result {
}