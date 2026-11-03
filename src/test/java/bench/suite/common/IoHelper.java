package bench.suite.common;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared I/O utility providing sector alignment, direct buffer allocation,
 * reflective access to ExtendedOpenOption.DIRECT, and OS RLIMIT_MEMLOCK inspection.
 */
public final class IoHelper {

    public static final int DEFAULT_SECTOR_SIZE = 4096;

    private static final OpenOption DIRECT_OPEN_OPTION;

    static {
        OpenOption direct = null;
        try {
            Class<?> clazz = Class.forName("com.sun.nio.file.ExtendedOpenOption");
            for (Object constant : clazz.getEnumConstants()) {
                if ("DIRECT".equals(constant.toString())) {
                    direct = (OpenOption) constant;
                    break;
                }
            }
        } catch (Throwable t) {
            System.err.println("Warning: ExtendedOpenOption.DIRECT not available: " + t.getMessage());
        }
        DIRECT_OPEN_OPTION = direct;
    }

    private IoHelper() {}

    /**
     * Allocates a direct ByteBuffer aligned to the specified alignment boundary (typically 4096 bytes).
     *
     * @param capacity  the usable capacity in bytes
     * @param alignment the alignment boundary in bytes (e.g. 4096)
     * @return an aligned direct ByteBuffer whose position is 0 and limit is capacity
     */
    public static ByteBuffer allocateAligned(int capacity, int alignment) {
        if (alignment <= 0) {
            alignment = DEFAULT_SECTOR_SIZE;
        }
        ByteBuffer raw = ByteBuffer.allocateDirect(capacity + alignment);
        ByteBuffer aligned = raw.alignedSlice(alignment);
        aligned.limit(capacity);
        return aligned.slice(0, capacity);
    }

    /**
     * Validates that a native memory address is strictly aligned to the given boundary.
     *
     * @param address   the native memory address
     * @param alignment the required alignment in bytes
     * @return true if aligned, false otherwise
     */
    public static boolean checkAlignment(long address, int alignment) {
        if (alignment <= 0) {
            alignment = DEFAULT_SECTOR_SIZE;
        }
        return (address % alignment) == 0;
    }

    /**
     * Validates that a file offset is strictly aligned to the given boundary.
     *
     * @param offset    the file offset in bytes
     * @param alignment the required alignment in bytes
     * @return true if aligned, false otherwise
     */
    public static boolean checkOffsetAlignment(long offset, int alignment) {
        if (alignment <= 0) {
            alignment = DEFAULT_SECTOR_SIZE;
        }
        return (offset % alignment) == 0;
    }

    /**
     * Retrieves com.sun.nio.file.ExtendedOpenOption.DIRECT via reflection,
     * avoiding module system export errors while enabling true NVMe O_DIRECT access.
     *
     * @return the OpenOption for ExtendedOpenOption.DIRECT, or null if unavailable
     */
    public static OpenOption getDirectOpenOption() {
        return DIRECT_OPEN_OPTION;
    }

    /**
     * Retrieves the native address of a direct ByteBuffer using the Foreign Function & Memory API.
     *
     * @param buffer direct ByteBuffer
     * @return native starting address
     */
    public static long getAddress(ByteBuffer buffer) {
        return MemorySegment.ofBuffer(buffer).address();
    }

    /**
     * Inspects /proc/self/limits to determine the process soft RLIMIT_MEMLOCK limit.
     *
     * @return maximum locked memory in bytes, or Long.MAX_VALUE if unlimited
     */
    public static long detectMemlockLimit() {
        Path limitsPath = Path.of("/proc/self/limits");
        if (!Files.exists(limitsPath)) {
            return Long.MAX_VALUE;
        }

        try {
            List<String> lines = Files.readAllLines(limitsPath);
            for (String line : lines) {
                if (line.startsWith("Max locked memory")) {
                    String[] tokens = line.substring("Max locked memory".length()).trim().split("\\s+");
                    if (tokens.length > 0) {
                        String softLimitStr = tokens[0].trim();
                        if ("unlimited".equalsIgnoreCase(softLimitStr)) {
                            return Long.MAX_VALUE;
                        }
                        return Long.parseLong(softLimitStr);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: failed to read /proc/self/limits: " + e.getMessage());
        }

        return Long.MAX_VALUE;
    }

    /**
     * Calculates a safe number of registered buffers per thread so that multi-thread ring setups
     * do not exceed RLIMIT_MEMLOCK and trigger ENOMEM (errno 12).
     *
     * @param bufferSize size of each buffer in bytes (e.g. 4096)
     * @param threads    number of concurrent benchmark threads
     * @return safe buffer count per thread (bounded between 16 and 512)
     */
    public static int getSafeBufferCount(int bufferSize, int threads) {
        long limit = detectMemlockLimit();
        if (limit == Long.MAX_VALUE) {
            return 256;
        }

        int activeThreads = Math.max(1, threads);
        // Reserve memory for ring structures (~64KB per thread; a 256-entry ring is ~24KB)
        long ringOverhead = 64 * 1024L * activeThreads;
        long usableLimit = Math.max(0, limit - ringOverhead);
        // Reserve 75% of usable limit across all threads, leave 25% safety margin
        long safeTotalBudget = (usableLimit * 3) / 4;
        long budgetPerThread = safeTotalBudget / activeThreads;
        int maxBuffers = (int) (budgetPerThread / bufferSize);

        // Clamp between minimum viable queue depth (16) and recommended maximum (256)
        return Math.max(16, Math.min(256, maxBuffers));
    }

    public static final int POSIX_FADV_DONTNEED = 4;
    private static final java.lang.invoke.MethodHandle POSIX_FADVISE_MH;
    private static final java.lang.invoke.MethodHandle MINCORE_MH;

    static {
        java.lang.invoke.MethodHandle fadvise = null;
        java.lang.invoke.MethodHandle mincore = null;
        try {
            java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker();
            java.lang.foreign.SymbolLookup stdlib = linker.defaultLookup();
            var fadviseOpt = stdlib.find("posix_fadvise");
            if (fadviseOpt.isPresent()) {
                var desc = java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_LONG,
                        java.lang.foreign.ValueLayout.JAVA_LONG, java.lang.foreign.ValueLayout.JAVA_INT);
                fadvise = linker.downcallHandle(fadviseOpt.get(), desc);
            }
            var mincoreOpt = stdlib.find("mincore");
            if (mincoreOpt.isPresent()) {
                var desc = java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_LONG,
                        java.lang.foreign.ValueLayout.ADDRESS);
                mincore = linker.downcallHandle(mincoreOpt.get(), desc);
            }
        } catch (Throwable t) {
            System.err.println("Notice: native FFM downcalls for fadvise/mincore not available: " + t.getMessage());
        }
        POSIX_FADVISE_MH = fadvise;
        MINCORE_MH = mincore;
    }

    public static int posixFadvise(int fd, long offset, long len, int advice) {
        if (POSIX_FADVISE_MH != null) {
            try {
                return (int) POSIX_FADVISE_MH.invokeExact(fd, offset, len, advice);
            } catch (Throwable t) {
                return -1;
            }
        }
        return -1;
    }

    public static int posixFadvise(Path path, long offset, long len, int advice) {
        int fd = com.davidvlijmincx.lio.api.NativeDispatcher.C.open(path.toString(), 0, 0);
        if (fd < 0) {
            return -1;
        }
        try {
            return posixFadvise(fd, offset, len, advice);
        } finally {
            com.davidvlijmincx.lio.api.NativeDispatcher.C.close(fd);
        }
    }

    public static void dropPageCache(Path... paths) {
        if (paths == null) return;
        for (Path p : paths) {
            if (p != null && Files.exists(p)) {
                posixFadvise(p, 0L, 0L, POSIX_FADV_DONTNEED);
            }
        }
    }

    public static int mincore(MemorySegment addr, long length, MemorySegment vec) {
        if (MINCORE_MH != null) {
            try {
                return (int) MINCORE_MH.invokeExact(addr, length, vec);
            } catch (Throwable t) {
                return -1;
            }
        }
        return -1;
    }

    public static boolean isPageResident(MemorySegment mappedSegment, long pageOffset) {
        long pages = (mappedSegment.byteSize() + 4095) / 4096;
        long targetPage = pageOffset / 4096;
        if (targetPage < 0 || targetPage >= pages) {
            return false;
        }
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment vec = arena.allocate((int) pages);
            int ret = mincore(mappedSegment, mappedSegment.byteSize(), vec);
            if (ret != 0) {
                return false;
            }
            byte b = vec.get(java.lang.foreign.ValueLayout.JAVA_BYTE, targetPage);
            return (b & 1) != 0;
        }
    }

    public static long parseSectorsRead(String statLine) {
        if (statLine == null || statLine.isBlank()) {
            return 0L;
        }
        String[] tokens = statLine.trim().split("\\s+");
        if (tokens.length < 3) {
            return 0L;
        }
        try {
            return Long.parseLong(tokens[2]);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static Path resolveBlockStatPath() {
        Path p = Path.of("/sys/block/nvme1n1/stat");
        if (Files.exists(p)) return p;
        try (var s = Files.list(Path.of("/sys/block"))) {
            for (Path dev : s.toList()) {
                Path stat = dev.resolve("stat");
                if (Files.exists(stat)) {
                    return stat;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static long readDeviceSectors() {
        Path statPath = resolveBlockStatPath();
        if (statPath == null || !Files.exists(statPath)) {
            return 0L;
        }
        try {
            String content = Files.readString(statPath);
            return parseSectorsRead(content);
        } catch (Exception e) {
            return 0L;
        }
    }
}
