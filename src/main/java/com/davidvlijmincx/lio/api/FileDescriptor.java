package com.davidvlijmincx.lio.api;

public class FileDescriptor implements AutoCloseable {

    private final int fd;

    public FileDescriptor(String path, LinuxOpenOptions flags, int mode) {
        int opened = NativeDispatcher.C.open(path, flags.getValue(), mode);
        if (opened < 0) {
            throw new RuntimeException("Failed to open '" + path + "': fd=" + opened);
        }
        this.fd = opened;
    }

    FileDescriptor(int fd) {
        if (fd < 0) {
            throw new IllegalArgumentException("Invalid file descriptor: " + fd);
        }
        this.fd = fd;
    }

    public int getFd() {
        return fd;
    }

    @Override
    public void close() {
        NativeDispatcher.C.close(fd);
    }

}
