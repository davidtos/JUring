package com.davidvlijmincx.lio.api;

public class FileDescriptor implements AutoCloseable {

    private final int fd;

    public FileDescriptor(String path, LinuxOpenOptions flags, int mode) {
        this.fd = NativeDispatcher.C.open(path, flags.getValue(), mode);
    }

    FileDescriptor(int fd) {
        this.fd = fd;
    }

    int getFd() {
        return fd;
    }

    @Override
    public void close() {
        NativeDispatcher.C.close(fd);
    }

}
