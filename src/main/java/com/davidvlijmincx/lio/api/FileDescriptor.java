package com.davidvlijmincx.lio.api;

import java.nio.file.Path;

/**
 * Safe wrapper around a native Linux file descriptor integer.
 */
public class FileDescriptor implements AutoCloseable {

    private final int fd;
    private boolean closed;

    public FileDescriptor(String path, LinuxOpenOptions flags, int mode) {
        int opened = NativeDispatcher.C.open(path, flags.getValue(), mode);
        if (opened < 0) {
            throw new RuntimeException("Failed to open '" + path + "': fd=" + opened);
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
            throw new RuntimeException("Failed to open '" + path + "': fd=" + opened);
        }
        this.fd = opened;
    }

    public FileDescriptor(Path path, LinuxOpenOptions... flags) {
        this(path.toString(), 0644, flags);
    }

    /**
     * Creates a file descriptor from a native descriptor value.
     *
     * @param fd native file descriptor
     * @throws IllegalArgumentException if fd is negative
     */
    FileDescriptor(int fd) {
        if (fd < 0) {
            throw new IllegalArgumentException("Invalid file descriptor: " + fd);
        }
        this.fd = fd;
    }

    /**
     * Returns the native file descriptor integer.
     *
     * @return the file descriptor
     */
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
