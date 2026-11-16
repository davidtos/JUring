package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

final class NativeDispatcher {

    static final LibCDispatcher C = LibCDispatcher.create();

    /** Cached result of the one-time io_uring availability probe. */
    private static volatile Boolean ioUringSupported;

    static IoUringDispatcher getUringInstance(int queueDepth, IoUringOptions... ioUringflags) {
        requireSupportedPlatform();
        return IoUringDispatcher.create(queueDepth, 0, ioUringflags);
    }

    static IoUringDispatcher getUringInstance(int queueDepth, int sqThreadIdleMs, IoUringOptions... ioUringflags) {
        requireSupportedPlatform();
        return IoUringDispatcher.create(queueDepth, sqThreadIdleMs, ioUringflags);
    }

    /**
     * Rejects unsupported operating systems/architectures before any native call, so callers get
     * an actionable {@link UnsupportedOperationException} instead of a link error or a wrong
     * syscall number.
     */
    private static void requireSupportedPlatform() {
        if (!LibCDispatcher.isSupportedPlatform()) {
            throw new UnsupportedOperationException("JUring requires Linux on x86-64 (amd64) or aarch64; running on "
                    + LibCDispatcher.platformDescription());
        }
    }

    /**
     * One-time check that this process can create an {@code io_uring}. Returns {@code false} on a
     * non-Linux/unsupported-arch JVM, an old kernel without io_uring, or when a container policy
     * (seccomp/AppArmor/{@code kernel.io_uring_disabled}) denies the syscall.
     *
     * @return whether a minimal {@code io_uring_setup} probe succeeded
     */
    static boolean isSupported() {
        if (!LibCDispatcher.isSupportedPlatform()) {
            return false;
        }
        Boolean cached = ioUringSupported;
        if (cached != null) {
            return cached;
        }
        synchronized (NativeDispatcher.class) {
            if (ioUringSupported != null) {
                return ioUringSupported;
            }
            boolean supported = probeIoUring();
            ioUringSupported = supported;
            return supported;
        }
    }

    private static boolean probeIoUring() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment params = arena.allocate(io_uring_params.layout().byteSize());
            params.fill((byte) 0);
            int fd = C.ioUringSetup(2, params);
            if (fd < 0) {
                return false;
            }
            try {
                return true;
            } finally {
                C.close(fd);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private NativeDispatcher() {
    }
}
