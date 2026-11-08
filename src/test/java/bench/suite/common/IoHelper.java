package bench.suite.common;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;

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

    public static ByteBuffer allocateAligned(int capacity, int alignment) {
        if (alignment <= 0) {
            alignment = DEFAULT_SECTOR_SIZE;
        }
        var raw = ByteBuffer.allocateDirect(capacity + alignment);
        var aligned = raw.alignedSlice(alignment);
        aligned.limit(capacity);
        return aligned.slice(0, capacity);
    }

    public static boolean checkAlignment(long address, int alignment) {
        if (alignment <= 0) {
            alignment = DEFAULT_SECTOR_SIZE;
        }
        return (address % alignment) == 0;
    }

    public static boolean checkOffsetAlignment(long offset, int alignment) {
        if (alignment <= 0) {
            alignment = DEFAULT_SECTOR_SIZE;
        }
        return (offset % alignment) == 0;
    }

    public static OpenOption getDirectOpenOption() {
        return DIRECT_OPEN_OPTION;
    }

    public static long getAddress(ByteBuffer buffer) {
        return MemorySegment.ofBuffer(buffer).address();
    }

    public static long detectMemlockLimit() {
        var limitsPath = Path.of("/proc/self/limits");
        if (!Files.exists(limitsPath)) {
            return Long.MAX_VALUE;
        }

        try {
            List<String> lines = Files.readAllLines(limitsPath);
            for (String line : lines) {
                if (line.startsWith("Max locked memory")) {
                    String[] tokens = line.substring("Max locked memory".length()).trim().split("\\s+");
                    if (tokens.length > 0) {
                        var softLimitStr = tokens[0].trim();
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

    public static int getSafeBufferCount(int bufferSize, int threads) {
        long limit = detectMemlockLimit();
        if (limit == Long.MAX_VALUE) {
            return 256;
        }

        int activeThreads = Math.max(1, threads);
        long ringOverhead = 64 * 1024L * activeThreads;
        long usableLimit = Math.max(0, limit - ringOverhead);
        long safeTotalBudget = (usableLimit * 3) / 4;
        long budgetPerThread = safeTotalBudget / activeThreads;
        int maxBuffers = (int) (budgetPerThread / bufferSize);
        return Math.clamp(maxBuffers, 16, 256);
    }

    public static final int POSIX_FADV_DONTNEED = 4;
    private static final MethodHandle POSIX_FADVISE_MH;
    private static final MethodHandle MINCORE_MH;

    static {
        MethodHandle fadvise = null;
        MethodHandle mincore = null;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup stdlib = linker.defaultLookup();
            var fadviseOpt = stdlib.find("posix_fadvise");
            if (fadviseOpt.isPresent()) {
                var desc = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
                fadvise = linker.downcallHandle(fadviseOpt.get(), desc);
            }
            var mincoreOpt = stdlib.find("mincore");
            if (mincoreOpt.isPresent()) {
                var desc = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS);
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
        try (var fd = new FileDescriptor(path, LinuxOpenOptions.READ)) {
            return posixFadvise(fd.getFd(), offset, len, advice);
        } catch (Exception e) {
            return -1;
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
        try (var arena = Arena.ofConfined()) {
            MemorySegment vec = arena.allocate((int) pages);
            int ret = mincore(mappedSegment, mappedSegment.byteSize(), vec);
            if (ret != 0) {
                return false;
            }
            byte b = vec.get(ValueLayout.JAVA_BYTE, targetPage);
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
