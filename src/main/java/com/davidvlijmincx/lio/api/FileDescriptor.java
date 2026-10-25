package com.davidvlijmincx.lio.api;

import java.nio.file.Path;

/**
 * Safe wrapper around a native Linux file descriptor integer.
 * <p>
 * <b>Why this change was made (Fail-Fast Validation):</b>
 * Prevents invalid or negative descriptors (e.g. from failed syscalls or uninitialized state) from being passed
 * into kernel registration or SQE preparation methods, preventing silent corruption or misleading {@code EBADF}
 * kernel submission errors downstream.
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
     * Validates that the provided integer descriptor is non-negative before construction.
     *
     * @param fd native file descriptor (must be >= 0)
     * @throws IllegalArgumentException if fd < 0
     */
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
