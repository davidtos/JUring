package com.davidvlijmincx.lio.api;

public record SocketResult(long id, int fileDescriptor) implements Result { }
