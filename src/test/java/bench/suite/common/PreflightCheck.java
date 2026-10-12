package bench.suite.common;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.IoUringOptions;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import com.davidvlijmincx.lio.api.Result;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Preflight environment checker validating Linux OS, kernel version, NVMe sector alignment,
 * RLIMIT_MEMLOCK pinned memory limits, and test data readiness before running benchmarks.
 */
public class PreflightCheck {

    public static class Report {
        private final List<String> info = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        public boolean isSuccess() {
            return errors.isEmpty();
        }

        public List<String> getInfo() {
            return info;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public List<String> getErrors() {
            return errors;
        }

        public void printReport() {
            System.out.println("================================================================================");
            System.out.println("                     JUring Benchmark Suite Preflight Check                     ");
            System.out.println("================================================================================");

            for (String line : info) {
                System.out.println(" [OK] " + line);
            }

            for (String warn : warnings) {
                System.out.println(" [WARN] " + warn);
            }

            for (String err : errors) {
                System.out.println(" [FAIL] " + err);
            }

            System.out.println("--------------------------------------------------------------------------------");
            if (isSuccess()) {
                System.out.println("Preflight status: READY FOR BENCHMARKING");
            } else {
                System.out.println("Preflight status: FAILED - Please resolve errors above before benchmarking");
            }
            System.out.println("================================================================================");
        }
    }

    public static Report runChecks() {
        Report report = new Report();

        // 1. Operating System
        String osName = System.getProperty("os.name", "unknown");
        if (osName.toLowerCase().contains("linux")) {
            report.info.add("Operating System: " + osName);
        } else {
            report.errors.add("Non-Linux OS detected (" + osName + "). io_uring and O_DIRECT require Linux.");
        }

        // 2. Kernel Version
        String kernel = getKernelVersion();
        report.info.add("Linux Kernel: " + kernel);

        // 3. RLIMIT_MEMLOCK
        long memlock = IoHelper.detectMemlockLimit();
        if (memlock == Long.MAX_VALUE) {
            report.info.add("RLIMIT_MEMLOCK: Unlimited (Optimal for multi-threaded registered buffer scaling)");
        } else {
            long memlockKiB = memlock / 1024;
            report.warnings.add("RLIMIT_MEMLOCK is restricted to " + memlockKiB + " KiB (" + (memlock / (1024 * 1024)) + " MB).");
            report.warnings.add("  -> Adaptive buffer pool sizing will keep per-thread pools bounded to avoid ENOMEM.");
            report.warnings.add("  -> To unlock full multi-thread buffer scale: run 'ulimit -l unlimited' or configure /etc/security/limits.d/");
        }

        // 4. Sector Alignment & 4KB Direct Buffer Allocation
        try {
            ByteBuffer aligned = IoHelper.allocateAligned(4096, 4096);
            long addr = IoHelper.getAddress(aligned);
            if (IoHelper.checkAlignment(addr, 4096)) {
                report.info.add("Sector Alignment: 4096-byte aligned direct buffers verified (addr 0x" + Long.toHexString(addr) + ")");
            } else {
                report.errors.add("Direct buffer address is not 4KB-aligned: 0x" + Long.toHexString(addr));
            }
        } catch (Throwable t) {
            report.errors.add("Failed to allocate sector-aligned direct buffer: " + t.getMessage());
        }

        // 5. Test Data Availability & Alignment Check
        Path readDir = DatasetManager.DEFAULT_READ_DIR;
        Path writeDir = DatasetManager.DEFAULT_WRITE_DIR;

        checkDataDirectory(readDir, "Read test dataset", report);
        checkDataDirectory(writeDir, "Write test dataset", report);

        // 6. Direct I/O Readiness Probe (FileChannel ExtendedOpenOption.DIRECT & JUring O_DIRECT)
        OpenOption directOption = IoHelper.getDirectOpenOption();
        if (directOption != null) {
            report.info.add("JDK Direct I/O Option: ExtendedOpenOption.DIRECT is available");
        } else {
            report.warnings.add("ExtendedOpenOption.DIRECT not found in com.sun.nio.file. Direct FileChannel reads will fail.");
        }

        probeDirectIoRead(readDir, report);

        return report;
    }

    private static void checkDataDirectory(Path dir, String label, Report report) {
        if (!Files.exists(dir)) {
            report.warnings.add(label + " directory (" + dir + ") does not exist.");
            return;
        }

        try (var stream = Files.walk(dir, 1)) {
            Path[] files = stream
                    .filter(p -> p.getFileName().toString().endsWith(DatasetManager.FILE_EXTENSION))
                    .limit(10)
                    .toArray(Path[]::new);

            if (files.length == 0) {
                report.warnings.add(label + " (" + dir + ") contains no " + DatasetManager.FILE_EXTENSION + " files.");
            } else {
                boolean allAligned = true;
                for (Path f : files) {
                    long size = Files.size(f);
                    if (!IoHelper.checkOffsetAlignment(size, 4096)) {
                        allAligned = false;
                        report.errors.add("File " + f.getFileName() + " size (" + size + " B) is NOT 4KB-aligned!");
                    }
                }
                if (allAligned) {
                    report.info.add(label + " (" + dir + "): Sector alignment verified across files.");
                }
            }
        } catch (Exception e) {
            report.warnings.add("Failed to inspect " + label + ": " + e.getMessage());
        }
    }

    private static void probeDirectIoRead(Path readDir, Report report) {
        try (var stream = Files.walk(readDir, 1)) {
            Path sampleFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(DatasetManager.FILE_EXTENSION))
                    .findFirst()
                    .orElse(null);

            if (sampleFile == null) {
                return;
            }

            // Test FileChannel DIRECT
            OpenOption direct = IoHelper.getDirectOpenOption();
            if (direct != null) {
                try (FileChannel fc = FileChannel.open(sampleFile, StandardOpenOption.READ, direct)) {
                    ByteBuffer buf = IoHelper.allocateAligned(4096, 4096);
                    int n = fc.read(buf, 0);
                    if (n == 4096) {
                        report.info.add("Direct I/O Probe: FileChannel O_DIRECT read succeeded on " + sampleFile.getFileName());
                    } else {
                        report.warnings.add("Direct I/O Probe: FileChannel read returned " + n + " bytes (expected 4096)");
                    }
                } catch (Exception e) {
                    report.warnings.add("Direct I/O Probe: FileChannel O_DIRECT read failed: " + e.getMessage());
                }
            }

            // Test JUring READ_DIRECT
            try (JUring ring = new JUring(8, IoUringOptions.IORING_SETUP_SINGLE_ISSUER);
                 FileDescriptor fd = new FileDescriptor(sampleFile.toString(), LinuxOpenOptions.READ_DIRECT, 0)) {
                ring.registerFiles(fd);
                ring.registerBuffers(4096, 2);
                ring.prepareReadFixed(0, 4096, 0, 0);
                ring.submit();
                Result r = ring.waitForResult();
                if (r.result() == 4096) {
                    report.info.add("Direct I/O Probe: JUring READ_DIRECT succeeded on " + sampleFile.getFileName());
                } else {
                    report.warnings.add("Direct I/O Probe: JUring read returned code " + r.result());
                }
            } catch (Exception e) {
                report.warnings.add("Direct I/O Probe: JUring READ_DIRECT failed: " + e.getMessage());
            }

        } catch (Exception ignored) {
        }
    }

    private static String getKernelVersion() {
        try {
            Path releasePath = Path.of("/proc/sys/kernel/osrelease");
            if (Files.exists(releasePath)) {
                return Files.readString(releasePath).trim();
            }
            Process p = new ProcessBuilder("uname", "-r").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return reader.readLine().trim();
            }
        } catch (Exception e) {
            return "Unknown (" + e.getMessage() + ")";
        }
    }

    public static void main(String[] args) {
        Report report = runChecks();
        report.printReport();
    }
}
