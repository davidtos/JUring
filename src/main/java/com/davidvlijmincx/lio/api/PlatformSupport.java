package com.davidvlijmincx.lio.api;

/**
 * OS/architecture gate that is safe to evaluate before any FFM/libc class initialisation.
 *
 * <p>{@link LibCDispatcher} binds Linux libc symbols and Linux-specific syscall numbers during
 * static initialisation; touching it on a non-Linux JVM produces an {@code ExceptionInInitializer}
 * rather than a useful answer. Everything that needs to answer "is this platform supported?"
 * before loading the native layer (notably {@link JUring#isSupported()}) goes through this class.
 */
final class PlatformSupport {

    private PlatformSupport() {
    }

    static boolean isSupportedPlatform() {
        String os = System.getProperty("os.name", "");
        if (!"Linux".equals(os)) {
            return false;
        }
        String arch = System.getProperty("os.arch", "");
        return "amd64".equals(arch) || "x86_64".equals(arch) || "aarch64".equals(arch);
    }

    static String description() {
        return System.getProperty("os.name", "?") + "/" + System.getProperty("os.arch", "?");
    }

    static void requireSupportedPlatform() {
        if (!isSupportedPlatform()) {
            throw new UnsupportedOperationException("JUring requires Linux on x86-64 (amd64) or aarch64; running on "
                    + description());
        }
    }
}
