package com.davidvlijmincx.lio.api;

public record SendResult(long id, long bytesSent) implements Result {
}