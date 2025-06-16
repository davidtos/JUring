package com.davidvlijmincx.lio.api;

public record OpenResult(long id, FileDescriptor fileDescriptor) implements Result {
    OpenResult(long id, int fd) {
        this(id, new FileDescriptor(fd));
    }
}
