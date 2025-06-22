package com.davidvlijmincx.lio.api;

public class FileDescriptor implements AutoCloseable {

    private final int fd;
    private boolean closed = false;

    public FileDescriptor(String path, LinuxOpenOptions flags, int mode) {
        this.fd = NativeDispatcher.C.open(path, flags.getValue(), mode);
    }

    FileDescriptor(int fd) {
        this.fd = fd;
    }

    int getFd() {
        if (closed) {
            throw new IllegalStateException("File descriptor has been closed");
        }
        return fd;
    }

    @Override
    public void close() {
        if (!closed) {
            NativeDispatcher.C.close(fd);
            closed = true;
        }
    }

}
