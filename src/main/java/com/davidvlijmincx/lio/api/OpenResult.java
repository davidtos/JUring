package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;

public final class OpenResult extends Result {

    FileDescriptor fileDescriptor;

    OpenResult(long id, int fd) {
        super(id);
        this.fileDescriptor = new FileDescriptor(fd);
    }

    public FileDescriptor getFileDescriptor() {
        return fileDescriptor;
    }
}
