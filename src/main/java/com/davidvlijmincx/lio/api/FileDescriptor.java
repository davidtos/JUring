package com.davidvlijmincx.lio.api;

import java.nio.file.Path;

public class FileDescriptor implements AutoCloseable {

    private final int fd;
    private boolean closed;

    public FileDescriptor(String path, LinuxOpenOptions flags, int mode) {
        int opened = NativeDispatcher.C.open(path, flags.getValue(), mode);
        if (opened < 0) {
            int err = -opened;
            throw new RuntimeException("Failed to open '" + path + "': " + NativeDispatcher.C.strerror(err) + " (errno " + err + ")");
        }
        this.fd = opened;
    }

    public FileDescriptor(Path path, LinuxOpenOptions flags, int mode) {
        this(path.toString(), flags, mode);
    }

    public FileDescriptor(Path path, int mode, LinuxOpenOptions... flags) {
        this(path.toString(), mode, flags);
    }

    public FileDescriptor(String path, int mode, LinuxOpenOptions... flags) {
        int opened = NativeDispatcher.C.open(path, LinuxOpenOptions.combineOptions(flags), mode);
        if (opened < 0) {
            int err = -opened;
            throw new RuntimeException("Failed to open '" + path + "': " + NativeDispatcher.C.strerror(err) + " (errno " + err + ")");
        }
        this.fd = opened;
    }

    public FileDescriptor(Path path, LinuxOpenOptions... flags) {
        this(path.toString(), 0644, flags);
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
        if (!closed) {
            closed = true;
            NativeDispatcher.C.close(fd);
        }
    }

}
