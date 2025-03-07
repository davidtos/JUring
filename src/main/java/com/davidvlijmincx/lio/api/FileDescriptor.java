package com.davidvlijmincx.lio.api;

public class FileDescriptor implements AutoCloseable {

    private final int fd;
    private boolean closed = false;

    public FileDescriptor(String path, Flag flags, int mode) {
        this.fd = LibCWrapper.OpenFile(path, flags.getValue(), mode);
    }

    int getFd() {
        if (closed) {
            throw new IllegalStateException("File descriptor has been closed");
        }
        return fd;
    }

    @Override
    public void close(){
        if (!closed) {
            LibCWrapper.closeFile(fd);
            closed = true;
        }
    }

}
