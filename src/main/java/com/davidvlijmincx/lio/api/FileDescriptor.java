package com.davidvlijmincx.lio.api;

public class FileDescriptor implements AutoCloseable {

    private final int fd;
    private final JUring jUring;
    private boolean closed = false;

    protected FileDescriptor(int fd, JUring jUring) {
        this.fd = fd;
        this.jUring = jUring;
    }

    protected int getFd() {
        if (closed) {
            throw new IllegalStateException("File descriptor has been closed");
        }
        return fd;
    }

    @Override
    public void close(){
        if (!closed) {
            jUring.closeFile(this);
            closed = true;
        }
    }
}
