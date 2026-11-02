package bench.suite.common;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages test datasets for both Direct I/O and Buffered I/O benchmarks.
 *
 * Precomputes 4KB-aligned tasks into primitive arrays (taskFileIndices[] and taskOffsets[])
 * to eliminate HashMap lookups, string allocations, and path conversions on the benchmark hot path
 * for BOTH FileChannel and JUring.
 */
public class DatasetManager {

    public static Path resolveBaseDataDir() {
        String sysProp = System.getProperty("juring.data.dir");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }
        String env = System.getenv("JURING_DATA_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path benchData = Path.of("./bench-data");
        if (Files.exists(benchData)) {
            return benchData;
        }
        Path localTestData = Path.of("/home/david/testData");
        if (Files.exists(localTestData)) {
            return localTestData;
        }
        Path mntTestData = Path.of("/mnt/TestData");
        if (Files.exists(mntTestData)) {
            return mntTestData;
        }
        return benchData;
    }

    public static Path resolveReadDir(Path base) {
        if (Files.isDirectory(base.resolve("text_files"))) {
            return base.resolve("text_files");
        }
        if (Files.isDirectory(base.resolve("read"))) {
            return base.resolve("read");
        }
        if (Files.isDirectory(base.resolve("multi_file"))) {
            return base.resolve("multi_file");
        }
        return base;
    }

    public static Path resolveWriteDir(Path base) {
        if (Files.isDirectory(base.resolve("write_files"))) {
            return base.resolve("write_files");
        }
        if (Files.isDirectory(base.resolve("write"))) {
            return base.resolve("write");
        }
        return base;
    }

    public static final Path DEFAULT_DATA_DIR = resolveBaseDataDir();
    public static final Path DEFAULT_READ_DIR = resolveReadDir(DEFAULT_DATA_DIR);
    public static final Path DEFAULT_WRITE_DIR = resolveWriteDir(DEFAULT_DATA_DIR);
    public static final String FILE_EXTENSION = ".bin";
    public static final int DEFAULT_BUFFER_SIZE = 4096;
    public static final int DEFAULT_TASK_COUNT = 2048;

    public enum WorkloadType {
        READ,
        WRITE
    }

    public enum FileDistribution {
        MULTI_FILE,
        SINGLE_FILE
    }

    private final WorkloadType workloadType;
    private final FileDistribution distribution;
    private final Path baseDir;
    private final int bufferSize;
    private final int taskCount;

    private final Path[] uniquePaths;
    private final long[] fileSizes;
    private final int[] taskFileIndices;
    private final long[] taskOffsets;
    private final AtomicInteger taskCursor = new AtomicInteger(0);

    public DatasetManager(WorkloadType workloadType, FileDistribution distribution) {
        this(workloadType, distribution, DEFAULT_TASK_COUNT);
    }

    public DatasetManager(WorkloadType workloadType, FileDistribution distribution, int taskCount) {
        this(workloadType, distribution,
                workloadType == WorkloadType.READ ? resolveReadDir(resolveBaseDataDir()) : resolveWriteDir(resolveBaseDataDir()),
                DEFAULT_BUFFER_SIZE, taskCount);
    }

    public static final String SINGLE_BENCH_FILE = "single_file_bench.bin";
    public static final long DEFAULT_SINGLE_FILE_SIZE = Long.getLong("juring.bench.singleFileSize", 10L * 1024 * 1024 * 1024); // 10 GB

    public static final int MAX_MULTI_FILES = 2211;

    public DatasetManager(WorkloadType workloadType, FileDistribution distribution, Path baseDir, int bufferSize, int taskCount) {
        this.workloadType = workloadType;
        this.distribution = distribution;
        this.baseDir = baseDir;
        this.bufferSize = bufferSize;
        this.taskCount = taskCount;

        if (distribution == FileDistribution.SINGLE_FILE) {
            Path singleFile = null;
            Path resolvedWriteDir = resolveWriteDir(resolveBaseDataDir());
            Path resolvedReadDir = resolveReadDir(resolveBaseDataDir());
            if (workloadType == WorkloadType.WRITE) {
                singleFile = ensureSingleBenchFile(baseDir, DEFAULT_SINGLE_FILE_SIZE);
            } else if (Files.exists(baseDir.resolve(SINGLE_BENCH_FILE))) {
                singleFile = baseDir.resolve(SINGLE_BENCH_FILE);
            } else if (Files.exists(resolvedWriteDir.resolve(SINGLE_BENCH_FILE))) {
                singleFile = resolvedWriteDir.resolve(SINGLE_BENCH_FILE);
            } else if (Files.exists(resolvedReadDir.resolve(SINGLE_BENCH_FILE))) {
                singleFile = resolvedReadDir.resolve(SINGLE_BENCH_FILE);
            }

            if (singleFile != null && Files.exists(singleFile)) {
                this.uniquePaths = new Path[]{ singleFile };
            } else {
                this.uniquePaths = discoverFiles(baseDir, 1);
            }
        } else {
            this.uniquePaths = discoverFiles(baseDir, MAX_MULTI_FILES);
        }

        if (this.uniquePaths.length == 0) {
            throw new IllegalStateException("No valid benchmark files (" + FILE_EXTENSION + ") found in " + baseDir);
        }

        this.fileSizes = new long[this.uniquePaths.length];
        for (int i = 0; i < this.uniquePaths.length; i++) {
            try {
                this.fileSizes[i] = Files.size(this.uniquePaths[i]);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read size for: " + this.uniquePaths[i], e);
            }
        }

        this.taskFileIndices = new int[taskCount];
        this.taskOffsets = new long[taskCount];
        generateTasks();
    }

    public static Path ensureSingleBenchFile(Path dir, long targetSize) {
        Path filePath = dir.resolve(SINGLE_BENCH_FILE);
        try {
            if (Files.exists(filePath) && Files.size(filePath) >= targetSize) {
                return filePath;
            }
            Files.createDirectories(dir);
            System.out.printf("Pre-allocating %s (%,d MB) to prevent ext4 extent lock contention...%n",
                    filePath.getFileName(), targetSize / (1024 * 1024));
            long start = System.currentTimeMillis();
            try (FileChannel fc = FileChannel.open(filePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ)) {
                int chunkSize = 4 * 1024 * 1024; // 4MB chunks
                ByteBuffer chunk = ByteBuffer.allocateDirect(chunkSize);
                byte[] pattern = new byte[chunkSize];
                for (int i = 0; i < chunkSize; i++) {
                    pattern[i] = (byte) (i & 0xFF);
                }
                chunk.put(pattern);
                long written = 0;
                while (written < targetSize) {
                    chunk.position(0);
                    long toWrite = Math.min(chunkSize, targetSize - written);
                    chunk.limit((int) toWrite);
                    fc.write(chunk, written);
                    written += toWrite;
                }
                fc.force(true);
            }
            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("Successfully pre-allocated %s in %d ms (%,.1f MB/s)%n",
                    filePath.getFileName(), elapsed, (targetSize / (1024.0 * 1024.0)) / (elapsed / 1000.0));
            return filePath;
        } catch (Exception e) {
            System.err.println("Notice: Could not pre-allocate " + SINGLE_BENCH_FILE + ": " + e.getMessage() + ". Falling back to existing files.");
            try {
                Files.deleteIfExists(filePath);
            } catch (Exception ignored) {}
            return null;
        }
    }

    private static Path[] discoverFiles(Path dir, int maxFiles) {
        if (!Files.exists(dir)) {
            return new Path[0];
        }
        try (var stream = Files.walk(dir, 1)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(FILE_EXTENSION)
                            && !p.getFileName().toString().equals(SINGLE_BENCH_FILE))
                    .filter(p -> {
                        try {
                            return Files.size(p) >= DEFAULT_BUFFER_SIZE;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(maxFiles)
                    .toArray(Path[]::new);
        } catch (IOException e) {
            throw new RuntimeException("Error scanning dataset directory: " + dir, e);
        }
    }

    public record TaskPool(int[] fileIndices, long[] offsets) {}

    public TaskPool generateThreadTasks(int threadIndex, int threadCount, int count) {
        int[] indices = new int[count];
        long[] offsets = new long[count];
        Random random = new Random(42424242L + ((long) threadIndex * 31));

        if (distribution == FileDistribution.SINGLE_FILE) {
            long size = fileSizes[0];
            long maxBlocks = size / bufferSize;
            if (maxBlocks <= 0) {
                throw new IllegalStateException("File size (" + size + ") smaller than bufferSize (" + bufferSize + ") for: " + uniquePaths[0]);
            }
            int intMaxBlocks = (int) Math.min(Integer.MAX_VALUE, maxBlocks);
            for (int i = 0; i < count; i++) {
                long blockIndex = intMaxBlocks > 0 ? (long) random.nextInt(intMaxBlocks) : 0L;
                indices[i] = 0;
                offsets[i] = blockIndex * bufferSize;
            }
        } else {
            int numFiles = uniquePaths.length;
            int filesPerThread = Math.max(1, numFiles / Math.max(1, threadCount));
            int startFile = threadIndex * filesPerThread;
            int endFile = (threadIndex == threadCount - 1) ? numFiles : Math.min(numFiles, startFile + filesPerThread);
            int localNumFiles = Math.max(1, endFile - startFile);

            for (int i = 0; i < count; i++) {
                int fileIdx = startFile + random.nextInt(localNumFiles);
                long size = fileSizes[fileIdx];
                long maxBlocks = size / bufferSize;
                int intMaxBlocks = (int) Math.min(Integer.MAX_VALUE, maxBlocks);
                long blockIndex = intMaxBlocks > 0 ? (long) random.nextInt(intMaxBlocks) : 0L;
                indices[i] = fileIdx;
                offsets[i] = blockIndex * bufferSize;
            }
        }

        return new TaskPool(indices, offsets);
    }

    private void generateTasks() {
        TaskPool defaultPool = generateThreadTasks(0, 1, taskCount);
        System.arraycopy(defaultPool.fileIndices(), 0, this.taskFileIndices, 0, taskCount);
        System.arraycopy(defaultPool.offsets(), 0, this.taskOffsets, 0, taskCount);
    }

    public int getTaskCount() {
        return taskCount;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getUniqueFileCount() {
        return uniquePaths.length;
    }

    public Path[] getUniquePaths() {
        return uniquePaths;
    }

    public int[] getTaskFileIndices() {
        return taskFileIndices;
    }

    public long[] getTaskOffsets() {
        return taskOffsets;
    }

    public int nextTaskIndex() {
        return (taskCursor.getAndIncrement() & 0x7FFFFFFF) % taskCount;
    }

    /**
     * Opens FileChannels for all unique files in this dataset.
     *
     * @param direct whether to use ExtendedOpenOption.DIRECT
     * @return array of open FileChannels indexed by fileIndex
     */
    public FileChannel[] openFileChannels(boolean direct) {
        FileChannel[] channels = new FileChannel[uniquePaths.length];
        OpenOption accessOption = (workloadType == WorkloadType.READ) ? StandardOpenOption.READ : StandardOpenOption.WRITE;

        List<OpenOption> options = new ArrayList<>();
        options.add(accessOption);
        if (direct) {
            OpenOption directOption = IoHelper.getDirectOpenOption();
            if (directOption != null) {
                options.add(directOption);
            } else {
                throw new UnsupportedOperationException("ExtendedOpenOption.DIRECT is not available on this platform.");
            }
        }

        OpenOption[] opts = options.toArray(new OpenOption[0]);
        for (int i = 0; i < uniquePaths.length; i++) {
            try {
                channels[i] = FileChannel.open(uniquePaths[i], opts);
            } catch (IOException e) {
                // Clean up already opened channels
                for (int j = 0; j < i; j++) {
                    try { channels[j].close(); } catch (Exception ignored) {}
                }
                throw new RuntimeException("Failed to open FileChannel for: " + uniquePaths[i], e);
            }
        }
        return channels;
    }

    /**
     * Opens JUring FileDescriptors for all unique files in this dataset.
     *
     * @param options LinuxOpenOptions flags (e.g. READ, READ_DIRECT, WRITE, WRITE_DIRECT)
     * @return array of open FileDescriptors indexed by fileIndex
     */
    public FileDescriptor[] openFileDescriptors(LinuxOpenOptions options) {
        FileDescriptor[] fds = new FileDescriptor[uniquePaths.length];
        for (int i = 0; i < uniquePaths.length; i++) {
            try {
                fds[i] = new FileDescriptor(uniquePaths[i].toString(), options, 0);
            } catch (Exception e) {
                for (int j = 0; j < i; j++) {
                    try { fds[j].close(); } catch (Exception ignored) {}
                }
                throw new RuntimeException("Failed to open FileDescriptor for: " + uniquePaths[i], e);
            }
        }
        return fds;
    }

    public static void closeChannels(FileChannel[] channels) {
        if (channels == null) return;
        for (FileChannel ch : channels) {
            if (ch != null && ch.isOpen()) {
                try {
                    ch.close();
                } catch (IOException ignored) {}
            }
        }
    }

    public static void closeFileDescriptors(FileDescriptor[] fds) {
        if (fds == null) return;
        for (FileDescriptor fd : fds) {
            if (fd != null) {
                try {
                    fd.close();
                } catch (Exception ignored) {}
            }
        }
    }

    public static Path resolveSingleBenchFile() {
        Path base = resolveBaseDataDir();
        Path writeDir = resolveWriteDir(base);
        Path readDir = resolveReadDir(base);

        Path pWrite = writeDir.resolve(SINGLE_BENCH_FILE);
        try {
            if (Files.exists(pWrite) && Files.size(pWrite) >= DEFAULT_SINGLE_FILE_SIZE) {
                return pWrite;
            }
        } catch (IOException ignored) {}

        Path pRead = readDir.resolve(SINGLE_BENCH_FILE);
        try {
            if (Files.exists(pRead) && Files.size(pRead) >= DEFAULT_SINGLE_FILE_SIZE) {
                return pRead;
            }
        } catch (IOException ignored) {}

        Path pBase = base.resolve(SINGLE_BENCH_FILE);
        try {
            if (Files.exists(pBase) && Files.size(pBase) >= DEFAULT_SINGLE_FILE_SIZE) {
                return pBase;
            }
        } catch (IOException ignored) {}

        return ensureSingleBenchFile(writeDir, DEFAULT_SINGLE_FILE_SIZE);
    }

    /**
     * Ultra-fast, zero-allocation 64-bit XorShift PRNG generating uniform random
     * 4KB-aligned block offsets across the entire 10 GB file (2,621,440 blocks),
     * exactly mirroring fio's per-thread PRNG offset generator (e.g. tausworthe / lfsr).
     */
    public static final class FioPrng {
        private long state;

        public FioPrng(long seed) {
            this.state = seed == 0L ? 0x8a5cd789635d2dffL : seed;
        }

        public long nextBlockIndex(long maxBlocks) {
            if (maxBlocks <= 0) return 0L;
            long x = state;
            x ^= x >>> 12;
            x ^= x << 25;
            x ^= x >>> 27;
            state = x;
            long val = (x * 0x2545F4914F6CDD1DL) >>> 1;
            return val % maxBlocks;
        }

        public long nextOffset(long maxBlocks, int blockSize) {
            return nextBlockIndex(maxBlocks) * (long) blockSize;
        }
    }
}
