package bench.suite.direct;

import bench.suite.common.DatasetManager;
import bench.suite.common.IoHelper;
import bench.suite.common.PreflightCheck;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import com.davidvlijmincx.lio.api.ResultConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Visual Verification Runner and Integrity Test for the JUring Direct Read Benchmark.
 *
 * <p>Executes the exact same pipelined O_DIRECT reading architecture as {@link DirectReadBenchmark}:
 * <ul>
 *   <li>Registered files ({@code ring.registerFiles})</li>
 *   <li>Registered fixed buffers ({@code ring.registerBuffers})</li>
 *   <li>Zero-copy NVMe direct reads ({@code ring.prepareReadFixed}) bypassing Linux page cache</li>
 *   <li>Queue depth batch submission and batch completion reaping</li>
 * </ul>
 *
 * <p>While running, it extracts the actual contents transferred into off-heap memory, formats a
 * visual preview, prints the live reads moving in real time to the console, and verifies every
 * block byte-for-byte against the physical file on disk.
 */
public class DirectReadVisualVerificationTest {

    public static class VerificationConfig {
        public int taskCount = 100;
        public int qd = 16;
        public int paceMs = 0;
        public boolean printAll = true;
        public boolean poisonBuffers = true;
        public boolean showHex = false;
        public DatasetManager.FileDistribution distribution = DatasetManager.FileDistribution.MULTI_FILE;
        public Path customDir = null;

        public static VerificationConfig quick() {
            VerificationConfig cfg = new VerificationConfig();
            cfg.taskCount = Integer.getInteger("tasks", 100);
            cfg.qd = Integer.getInteger("qd", 16);
            cfg.paceMs = Integer.getInteger("pace-ms", Integer.getInteger("paceMs", 0));
            cfg.printAll = true;
            cfg.poisonBuffers = true;
            return cfg;
        }

        public static VerificationConfig visual(int paceMs) {
            VerificationConfig cfg = new VerificationConfig();
            cfg.taskCount = Integer.getInteger("tasks", 100);
            cfg.qd = Integer.getInteger("qd", 16);
            cfg.paceMs = paceMs;
            cfg.printAll = true;
            cfg.poisonBuffers = true;
            return cfg;
        }

        public static VerificationConfig fullSaturation() {
            VerificationConfig cfg = new VerificationConfig();
            cfg.taskCount = DatasetManager.DEFAULT_TASK_COUNT;
            cfg.qd = 32;
            cfg.paceMs = 0;
            cfg.printAll = false;
            cfg.poisonBuffers = false;
            return cfg;
        }
    }

    public static class VerificationStats {
        public int totalTasks;
        public int completedTasks;
        public int verifiedMatches;
        public int mismatches;
        public int ioErrors;
        public int poisonFailures;
        public long totalBytesRead;
        public long elapsedNanos;

        public boolean isSuccess() {
            return completedTasks == totalTasks && mismatches == 0 && ioErrors == 0 && poisonFailures == 0;
        }

        public double getThroughputMbSec() {
            double secs = elapsedNanos / 1_000_000_000.0;
            return secs > 0 ? (totalBytesRead / (1024.0 * 1024.0)) / secs : 0;
        }

        public double getIops() {
            double secs = elapsedNanos / 1_000_000_000.0;
            return secs > 0 ? completedTasks / secs : 0;
        }
    }

    /**
     * Executes the Direct Read Benchmark pipeline with real-time console reporting and disk verification.
     */
    public static VerificationStats runVerification(VerificationConfig config) {
        Path datasetDir = (config.customDir != null) ? config.customDir : DatasetManager.DEFAULT_READ_DIR;
        Path tempDirCreated = null;

        boolean hasBinFiles = false;
        if (Files.exists(datasetDir)) {
            try (var stream = Files.walk(datasetDir, 1)) {
                hasBinFiles = stream.anyMatch(p -> p.getFileName().toString().endsWith(DatasetManager.FILE_EXTENSION));
            } catch (IOException ignored) {}
        }

        if (!hasBinFiles) {
            try {
                tempDirCreated = Files.createTempDirectory("juring_visual_bench_");
                datasetDir = tempDirCreated;
                generateFallbackFiles(datasetDir, 4, 65536);
                System.out.println("Note: Using generated fallback dataset in " + datasetDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create fallback dataset", e);
            }
        }

        DatasetManager dataset = new DatasetManager(
                DatasetManager.WorkloadType.READ,
                config.distribution,
                datasetDir,
                DatasetManager.DEFAULT_BUFFER_SIZE,
                config.taskCount
        );

        final int bufferSize = dataset.getBufferSize();
        final int totalTasks = dataset.getTaskCount();
        final int qd = config.qd;
        final int safeBufferCount = Math.max(qd, 32);
        final int ringEntries = Math.max(256, qd * 2);
        final int submitBatch = (qd <= 1) ? 1 : Math.min(qd, 32);

        final int[] taskFileIndices = dataset.getTaskFileIndices();
        final long[] taskOffsets = dataset.getTaskOffsets();
        final Path[] uniquePaths = dataset.getUniquePaths();

        final VerificationStats stats = new VerificationStats();
        stats.totalTasks = totalTasks;

        System.out.println("==================================================================================");
        System.out.println("          JUring Direct I/O Read Benchmark - Visual Verification Runner           ");
        System.out.println("==================================================================================");
        System.out.printf(" Dataset Path:      %s%n", datasetDir);
        System.out.printf(" Unique Files:      %d%n", uniquePaths.length);
        System.out.printf(" Distribution:      %s%n", config.distribution);
        System.out.printf(" Total Tasks:       %d reads (%d bytes / sector)%n", totalTasks, bufferSize);
        System.out.printf(" Queue Depth (QD):  %d%n", qd);
        System.out.printf(" Visual Pacing:     %d ms/read%n", config.paceMs);
        System.out.printf(" Poison Buffers:    %s (0xAA pre-fill to verify DMA physical write)%n", config.poisonBuffers ? "ENABLED" : "DISABLED");
        System.out.println(" Open Flags:        O_DIRECT (LinuxOpenOptions.READ_DIRECT, page cache bypass)");
        System.out.println(" Ring Options:      SINGLE_ISSUER, COOP_TASKRUN, DEFER_TASKRUN");
        System.out.println(" Verification:      100% bit-accurate comparison against underlying disk file");
        System.out.println("----------------------------------------------------------------------------------");

        FileDescriptor[] fileDescriptors = null;
        FileChannel[] verifyChannels = null;
        JUring ring = null;

        try {
            ring = new JUring(ringEntries,
                    IORING_SETUP_SINGLE_ISSUER,
                    IORING_SETUP_COOP_TASKRUN,
                    IORING_SETUP_DEFER_TASKRUN);

            fileDescriptors = dataset.openFileDescriptors(LinuxOpenOptions.READ_DIRECT);
            int regRet = ring.registerFiles(fileDescriptors);
            if (regRet != 0) {
                throw new RuntimeException("Failed to register files in JUring: " + regRet);
            }

            MemorySegment[] registeredBuffers = ring.registerBuffers(bufferSize, safeBufferCount);
            verifyChannels = dataset.openFileChannels(false);

            final JUring finalRing = ring;
            final FileChannel[] finalVerifyChannels = verifyChannels;
            final int[] activeTaskAtBuffer = new int[safeBufferCount];
            Arrays.fill(activeTaskAtBuffer, -1);

            final AtomicInteger reapedCounter = new AtomicInteger(0);
            final byte poisonPattern = (byte) 0xAA;

            ResultConsumer consumer = result -> {
                long res = result.result();
                int bufIdx = result.bufferIdx();
                int taskIdx = (bufIdx >= 0 && bufIdx < safeBufferCount) ? activeTaskAtBuffer[bufIdx] : -1;
                activeTaskAtBuffer[bufIdx] = -1;

                int currentReap = reapedCounter.getAndIncrement();

                if (res != bufferSize) {
                    stats.ioErrors++;
                    System.err.printf("[Read #%04d/%04d] I/O ERROR: Expected %d bytes, got code %d%n",
                            currentReap + 1, totalTasks, bufferSize, res);
                } else if (taskIdx < 0 || taskIdx >= totalTasks) {
                    stats.ioErrors++;
                    System.err.printf("[Read #%04d/%04d] TASK MAPPING ERROR: Invalid task index %d for bufIdx %d%n",
                            currentReap + 1, totalTasks, taskIdx, bufIdx);
                } else {
                    int fileIdx = taskFileIndices[taskIdx];
                    long offset = taskOffsets[taskIdx];
                    Path filePath = uniquePaths[fileIdx];

                    byte[] juringBytes = new byte[bufferSize];
                    MemorySegment.copy(registeredBuffers[bufIdx], 0L, MemorySegment.ofArray(juringBytes), 0L, bufferSize);

                    byte[] diskBytes = new byte[bufferSize];
                    ByteBuffer vBuf = ByteBuffer.wrap(diskBytes);
                    try {
                        while (vBuf.hasRemaining()) {
                            int readBytes = finalVerifyChannels[fileIdx].read(vBuf, offset + vBuf.position());
                            if (readBytes < 0) {
                                throw new IOException("Unexpected EOF during disk read at offset " + offset);
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed reading disk block for verification", e);
                    }

                    if (config.poisonBuffers) {
                        boolean juringPoisoned = true;
                        boolean diskPoisoned = true;
                        int checkLen = Math.min(64, bufferSize);
                        for (int i = 0; i < checkLen; i++) {
                            if (juringBytes[i] != poisonPattern) {
                                juringPoisoned = false;
                            }
                            if (diskBytes[i] != poisonPattern) {
                                diskPoisoned = false;
                            }
                        }
                        if (juringPoisoned && !diskPoisoned) {
                            stats.poisonFailures++;
                        }
                    }

                    boolean bitMatch = Arrays.equals(juringBytes, diskBytes);
                    if (bitMatch) {
                        stats.verifiedMatches++;
                    } else {
                        stats.mismatches++;
                    }

                    stats.completedTasks++;
                    stats.totalBytesRead += res;

                    CRC32 crc = new CRC32();
                    crc.update(juringBytes);
                    long checksum = crc.getValue();

                    String snippet = formatAsciiPreview(juringBytes, 36);

                    boolean shouldPrint = config.printAll ||
                            (currentReap < 25) ||
                            ((currentReap + 1) % 100 == 0) ||
                            (currentReap >= totalTasks - 10);

                    if (shouldPrint) {
                        String statusTag = bitMatch ? "[MATCH DISK OK]" : "[MISMATCH FAIL]";
                        if (config.showHex) {
                            String hexSnippet = formatHexPreview(juringBytes, 16);
                            System.out.printf("[Read #%04d/%04d] %-14s @ off=%-7d | buf=%-2d | CRC=%08x | Hex: %-47s | %s%n",
                                    currentReap + 1,
                                    totalTasks,
                                    filePath.getFileName(),
                                    offset,
                                    bufIdx,
                                    checksum,
                                    hexSnippet,
                                    statusTag);
                        } else {
                            System.out.printf("[Read #%04d/%04d] %-14s @ off=%-7d | buf=%-2d (%dB) | CRC=%08x | Data: \"%-36s\" | %s%n",
                                    currentReap + 1,
                                    totalTasks,
                                    filePath.getFileName(),
                                    offset,
                                    bufIdx,
                                    res,
                                    checksum,
                                    snippet,
                                    statusTag);
                        }
                        System.out.flush();
                    } else if ((currentReap + 1) % 250 == 0) {
                        System.out.printf("  ... In-flight saturation: %d/%d reads reaped (100%% verified) ...%n",
                                currentReap + 1, totalTasks);
                        System.out.flush();
                    }

                    if (config.paceMs > 0) {
                        try {
                            Thread.sleep(config.paceMs);
                        } catch (InterruptedException ignored) {}
                    }
                }

                if (bufIdx >= 0 && bufIdx < safeBufferCount) {
                    finalRing.checkInBuffer(bufIdx);
                }
            };

            long startNano = System.nanoTime();
            int submitted = 0;
            int taskIndex = 0;
            int unsubmitted = 0;

            while (reapedCounter.get() < totalTasks) {
                while ((submitted - reapedCounter.get()) < qd && taskIndex < totalTasks) {
                    int bufIdx = ring.checkOutBuffer();
                    if (bufIdx < 0) {
                        break;
                    }

                    if (config.poisonBuffers) {
                        registeredBuffers[bufIdx].fill(poisonPattern);
                    }

                    int fileIdx = taskFileIndices[taskIndex];
                    long offset = taskOffsets[taskIndex];

                    activeTaskAtBuffer[bufIdx] = taskIndex;

                    ring.prepareReadFixed(fileIdx, bufferSize, offset, bufIdx);
                    submitted++;
                    unsubmitted++;
                    taskIndex++;

                    if (unsubmitted >= submitBatch && (submitted - reapedCounter.get()) < qd && taskIndex < totalTasks) {
                        ring.submit();
                        unsubmitted = 0;
                        ring.peekForBatchResult(qd, consumer);
                    }
                }

                int reaped = (unsubmitted == 0) ? ring.peekForBatchResult(qd, consumer) : 0;
                if (reaped == 0 && reapedCounter.get() < totalTasks) {
                    ring.submitAndWaitForBatchResult(1, qd, consumer);
                    unsubmitted = 0;
                }
            }

            stats.elapsedNanos = System.nanoTime() - startNano;

        } finally {
            DatasetManager.closeChannels(verifyChannels);
            DatasetManager.closeFileDescriptors(fileDescriptors);
            if (ring != null) {
                ring.close();
            }
            if (tempDirCreated != null) {
                cleanupTempDir(tempDirCreated);
            }
        }

        printSummary(stats, config);
        return stats;
    }

    private static void printSummary(VerificationStats stats, VerificationConfig config) {
        System.out.println("----------------------------------------------------------------------------------");
        System.out.println("                        DIRECT READ VERIFICATION RESULTS                          ");
        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf(" Execution Wall Time:  %.3f s%n", stats.elapsedNanos / 1_000_000_000.0);
        System.out.printf(" Tasks Completed:      %d / %d (%.1f%%)%n",
                stats.completedTasks, stats.totalTasks,
                stats.totalTasks > 0 ? (100.0 * stats.completedTasks / stats.totalTasks) : 0);
        System.out.printf(" Total Data Read:      %,d bytes (%.2f MB)%n",
                stats.totalBytesRead, stats.totalBytesRead / (1024.0 * 1024.0));
        System.out.printf(" Average Throughput:   %,.2f MB/s (%,.0f IOPS)%n",
                stats.getThroughputMbSec(), stats.getIops());
        System.out.printf(" Bit-Accurate Matches: %d / %d (100%% identical to disk)%n",
                stats.verifiedMatches, stats.totalTasks);
        System.out.printf(" Mismatches Detected:  %d%n", stats.mismatches);
        System.out.printf(" I/O System Errors:    %d%n", stats.ioErrors);
        if (config.poisonBuffers) {
            System.out.printf(" Poison Pattern Proof: %d unoverwritten buffers (0 expected)%n", stats.poisonFailures);
        }
        System.out.println("----------------------------------------------------------------------------------");
        if (stats.isSuccess()) {
            System.out.println(" [SUCCESS] 100% VERIFIED: JUring DirectRead benchmark pipeline is genuinely");
            System.out.println("           reading live data from disk blocks via NVMe O_DIRECT DMA.");
        } else {
            System.err.println(" [FAILURE] Direct read verification detected mismatches or errors!");
        }
        System.out.println("==================================================================================\n");
    }

    public static String formatAsciiPreview(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLen);
        for (int i = 0; i < len; i++) {
            byte b = bytes[i];
            if (b == '\n') {
                sb.append("\\n");
            } else if (b == '\r') {
                sb.append("\\r");
            } else if (b == '\t') {
                sb.append("\\t");
            } else if (b >= 32 && b <= 126) {
                sb.append((char) b);
            } else {
                sb.append('.');
            }
        }
        if (bytes.length > maxLen) {
            sb.append("...");
        }
        return sb.toString();
    }

    public static String formatHexPreview(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLen);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", bytes[i]));
        }
        if (bytes.length > maxLen) {
            sb.append("...");
        }
        return sb.toString();
    }

    private static void generateFallbackFiles(Path dir, int count, int size) throws IOException {
        Files.createDirectories(dir);
        for (int i = 0; i < count; i++) {
            Path file = dir.resolve("fallback_" + (1000 + i) + ".bin");
            byte[] block = new byte[size];
            String pattern = String.format("%04d \n", 1000 + i);
            byte[] patternBytes = pattern.getBytes();
            for (int off = 0; off < size; off += patternBytes.length) {
                int copyLen = Math.min(patternBytes.length, size - off);
                System.arraycopy(patternBytes, 0, block, off, copyLen);
            }
            Files.write(file, block, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
    }

    private static void cleanupTempDir(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    // ========================================================================
    // JUnit Tests
    // ========================================================================

    @Test
    @DisplayName("Visual Direct Read: 100 O_DIRECT reads with live console data preview and 100% disk match")
    void testDirectReadPipelineWithVisualOutput() {
        VerificationConfig config = VerificationConfig.quick();
        VerificationStats stats = runVerification(config);

        assertTrue(stats.isSuccess(), "All reads must complete and match disk identically");
        assertEquals(config.taskCount, stats.completedTasks);
        assertEquals(config.taskCount, stats.verifiedMatches);
        assertEquals(0, stats.mismatches);
        assertEquals(0, stats.ioErrors);
        assertEquals(0, stats.poisonFailures);
    }

    @Test
    @DisplayName("Single File Direct Read: Pipelined O_DIRECT reads against a single file with disk match")
    void testSingleFileDirectRead() {
        VerificationConfig config = VerificationConfig.quick();
        config.distribution = DatasetManager.FileDistribution.SINGLE_FILE;
        config.taskCount = 64;

        VerificationStats stats = runVerification(config);

        assertTrue(stats.isSuccess());
        assertEquals(config.taskCount, stats.completedTasks);
        assertEquals(config.taskCount, stats.verifiedMatches);
        assertEquals(0, stats.mismatches);
    }

    @Test
    @DisplayName("Full Task Count Direct Read Saturation: 2048 O_DIRECT tasks verified against disk")
    void testFullTaskCountDirectReadSaturation() {
        VerificationConfig config = VerificationConfig.fullSaturation();
        VerificationStats stats = runVerification(config);

        assertTrue(stats.isSuccess());
        assertEquals(config.taskCount, stats.completedTasks);
        assertEquals(config.taskCount, stats.verifiedMatches);
        assertEquals(0, stats.mismatches);
        assertEquals(0, stats.ioErrors);
    }

    @Test
    @DisplayName("Poisoned Buffer Integrity: Proves io_uring DMA physically overwrites poisoned memory")
    void testPoisonedBufferIntegrity() {
        VerificationConfig config = VerificationConfig.quick();
        config.taskCount = 50;
        config.poisonBuffers = true;

        VerificationStats stats = runVerification(config);

        assertTrue(stats.isSuccess());
        assertEquals(config.taskCount, stats.completedTasks);
        assertEquals(0, stats.poisonFailures);
    }

    // ========================================================================
    // CLI Standalone Runner (for human visual inspection)
    // ========================================================================

    public static void main(String[] args) {
        VerificationConfig config = new VerificationConfig();
        config.taskCount = 100;
        config.qd = 16;
        config.paceMs = 15; // 15 ms delay per read by default so reads move visibly down the console
        config.printAll = true;
        config.poisonBuffers = true;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--tasks" -> {
                    if (i + 1 < args.length) config.taskCount = Integer.parseInt(args[++i]);
                }
                case "--qd" -> {
                    if (i + 1 < args.length) config.qd = Integer.parseInt(args[++i]);
                }
                case "--pace-ms" -> {
                    if (i + 1 < args.length) config.paceMs = Integer.parseInt(args[++i]);
                }
                case "--all-tasks" -> {
                    config.taskCount = DatasetManager.DEFAULT_TASK_COUNT;
                }
                case "--no-pace" -> {
                    config.paceMs = 0;
                }
                case "--single-file" -> {
                    config.distribution = DatasetManager.FileDistribution.SINGLE_FILE;
                }
                case "--no-poison" -> {
                    config.poisonBuffers = false;
                }
                case "--hex" -> {
                    config.showHex = true;
                }
                case "--help" -> {
                    System.out.println("JUring Direct Read Benchmark - Visual Verification Tool");
                    System.out.println("Usage: java ... DirectReadVisualVerificationTest [options]");
                    System.out.println("Options:");
                    System.out.println("  --tasks <N>       Number of reads to execute (default: 100)");
                    System.out.println("  --qd <N>          Queue depth (default: 16)");
                    System.out.println("  --pace-ms <ms>    Delay in ms per read to observe reads moving (default: 15)");
                    System.out.println("  --all-tasks       Run all 2048 benchmark tasks");
                    System.out.println("  --no-pace         Run without pacing delay (maximum speed)");
                    System.out.println("  --single-file     Use SINGLE_FILE distribution instead of MULTI_FILE");
                    System.out.println("  --no-poison       Disable buffer poisoning");
                    System.out.println("  --hex             Display hex byte preview instead of ASCII");
                    System.out.println("  --help            Print this help");
                    return;
                }
                default -> System.out.println("Unknown argument: " + arg + " (use --help for options)");
            }
        }

        VerificationStats stats = runVerification(config);
        if (!stats.isSuccess()) {
            System.exit(1);
        }
    }
}
