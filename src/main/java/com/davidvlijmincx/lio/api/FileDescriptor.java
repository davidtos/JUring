package com.davidvlijmincx.lio.api;

import java.nio.file.Path;

/**
 * Linux file descriptor owned by JUring, with convenience constructors that open a path.
 *
 * <p>A descriptor is a plain {@code int} handle to a file. All operations that select a
 * file take a {@code FileDescriptor}; the descriptor must stay open for the whole lifetime
 * of the operations that use it. Instances are cheap, are not thread-safe, and closing is
 * idempotent. A {@code JuringFile} or stream owns its descriptor; application code usually
 * closes the higher-level object instead of the descriptor.
 *
 * <p>Opening a file with the {@code O_DIRECT} flags ({@link LinuxOpenOptions#DIRECT},
 * {@link LinuxOpenOptions#READ_DIRECT}, {@link LinuxOpenOptions#WRITE_DIRECT}) bypasses
 * the page cache and requires sector-aligned buffers and offsets.
 */
public class FileDescriptor implements AutoCloseable {

    private final int fd;
    private boolean closed;

    /**
     * Opens {@code path} with the given open flags and creation mode.
     *
     * @param path  file system path to open
     * @param flags open flags
     * @param mode  permission bits used when the file is created (for example {@code 0644})
     * @throws RuntimeException when the operating system rejects the open (the message
     *                          carries the errno and its {@code strerror} text)
     */
    public FileDescriptor(String path, LinuxOpenOptions flags, int mode) {
        int opened = NativeDispatcher.C.open(path, flags.getValue(), mode);
        if (opened < 0) {
            int err = -opened;
            throw new RuntimeException("Failed to open '" + path + "': " + NativeDispatcher.C.strerror(err) + " (errno " + err + ")");
        }
        this.fd = opened;
    }

    /**
     * Opens {@code path} with the given open flags and creation mode.
     *
     * @param path  file system path to open
     * @param flags open flags
     * @param mode  permission bits used when the file is created
     * @throws RuntimeException when the operating system rejects the open
     */
    public FileDescriptor(Path path, LinuxOpenOptions flags, int mode) {
        this(path.toString(), flags, mode);
    }

    /**
     * Opens {@code path} with the given creation mode and open flags.
     *
     * @param path  file system path to open
     * @param mode  permission bits used when the file is created
     * @param flags open flags, combined with bitwise OR
     * @throws RuntimeException when the operating system rejects the open
     */
    public FileDescriptor(Path path, int mode, LinuxOpenOptions... flags) {
        this(path.toString(), mode, flags);
    }

    /**
     * Opens {@code path} with the given creation mode and open flags.
     *
     * @param path  file system path to open
     * @param mode  permission bits used when the file is created
     * @param flags open flags, combined with bitwise OR
     * @throws RuntimeException when the operating system rejects the open (the message
     *                          carries the errno and its {@code strerror} text)
     */
    public FileDescriptor(String path, int mode, LinuxOpenOptions... flags) {
        int opened = NativeDispatcher.C.open(path, LinuxOpenOptions.combineOptions(flags), mode);
        if (opened < 0) {
            int err = -opened;
            throw new RuntimeException("Failed to open '" + path + "': " + NativeDispatcher.C.strerror(err) + " (errno " + err + ")");
        }
        this.fd = opened;
    }

    /**
     * Opens {@code path} with the given open flags and mode {@code 0644}.
     *
     * @param path  file system path to open
     * @param flags open flags, combined with bitwise OR
     * @throws RuntimeException when the operating system rejects the open
     */
    public FileDescriptor(Path path, LinuxOpenOptions... flags) {
        this(path.toString(), 0644, flags);
    }

    FileDescriptor(int fd) {
        if (fd < 0) {
            throw new IllegalArgumentException("Invalid file descriptor: " + fd);
        }
        this.fd = fd;
    }

    /**
     * Returns the underlying Linux file descriptor.
     *
     * @return the native file descriptor number
     */
    public int getFd() {
        return fd;
    }

    /**
     * Closes the underlying file descriptor; calling it more than once has no effect.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            NativeDispatcher.C.close(fd);
        }
    }

}
