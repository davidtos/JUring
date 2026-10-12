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
        return aligned;
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
        // Reserve memory for ring structures and registered file tables (~384KB per thread)
        long ringOverhead = 384 * 1024L * activeThreads;
        long usableLimit = Math.max(0, limit - ringOverhead);
        // Reserve 75% of usable limit across all threads, leave 25% safety margin
        long safeTotalBudget = (usableLimit * 3) / 4;
        long budgetPerThread = safeTotalBudget / activeThreads;
        int maxBuffers = (int) (budgetPerThread / bufferSize);

        // Clamp between minimum viable queue depth (16) and recommended maximum (256)
        return Math.max(16, Math.min(256, maxBuffers));
    }
}
