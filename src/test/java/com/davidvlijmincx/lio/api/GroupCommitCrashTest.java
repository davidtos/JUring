package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Group-commit crash/restart test (frozen contract).
 *
 * <p>A child JVM opens a fresh file, queues deterministic records in a
 * {@link GroupCommit} ({@link DurabilityMode#FDATASYNC}), calls {@link GroupCommit#commit()}
 * exactly once, prints a {@code DURABLE} marker with the batch statistics and then dies
 * abruptly with {@link Runtime#halt(int)}{@code (137)} so no stream/file close or flush ever
 * runs. This JUnit (parent) process then reopens the path with a <strong>fresh
 * {@link JUring} + {@link JuringFile}</strong> and verifies every record byte-for-byte and
 * the file length.
 *
 * <p><b>Disclosure:</b> the test file lives on a {@code @TempDir} (tmpfs), so this proves
 * <strong>process-restart visibility</strong> of the committed bytes, not media durability
 * after a power loss (explicit non-goal). The "one fsync for the batch" claim is proven by the
 * counters in {@link GroupCommitTest}; the device-backed fresh-process re-read lives in the
 * group-commit benchmark ({@code --verify-only}). The test only skips via an explicit
 * {@link Assumptions#assumeTrue} when the environment cannot spawn a JVM.
 */
class GroupCommitCrashTest {

    private static final int BLOCK = 4096;
    private static final int RECORDS = 6;
    private static final int RECORD_SIZE = BLOCK;

    @Test
    void committedBatchIsVisibleAfterAbruptChildProcessDeath(@TempDir Path dir) throws Exception {
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        Assumptions.assumeTrue(Files.isExecutable(javaBinary),
                "no java launcher available to spawn the child JVM: " + javaBinary);

        Path path = dir.resolve("crash-child.bin");
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary.toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", classpath,
                CrashWriter.class.getName(),
                path.toString(),
                Integer.toString(RECORDS),
                Integer.toString(RECORD_SIZE));
        builder.redirectErrorStream(true);
        Process child = builder.start();

        boolean exited = child.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            child.destroyForcibly();
            fail("child JVM did not exit within 60 s");
        }
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(child.exitValue())
                .as("child must die via Runtime.halt(137) right after the committed batch; output:\n%s", output)
                .isEqualTo(137);

        long expectedLength = (long) RECORDS * RECORD_SIZE;
        String marker = markerLine(output);
        assertThat(markerValue(marker, "bytes")).as("committed byte length").isEqualTo(expectedLength);
        assertThat(markerValue(marker, "writes")).as("writes in the committed batch").isEqualTo(RECORDS);
        assertThat(markerValue(marker, "batches")).as("one commit").isEqualTo(1);
        assertThat(markerValue(marker, "syncs"))
                .as("one fdatasync for the whole batch (stats.syncs == stats.batches)")
                .isEqualTo(markerValue(marker, "batches"));
        assertThat(markerValue(marker, "committedWrites")).isEqualTo(RECORDS);

        // Fresh ring + file in this (parent) process: the bytes must be visible after the
        // child process died without ever closing its file or stream.
        try (JUring freshRing = new JUring(64, IORING_SETUP_SINGLE_ISSUER);
             JuringFile file = JuringFile.open(freshRing, path, READ)) {
            assertThat(file.length()).as("file length after restart").isEqualTo(expectedLength);
            try (JuringInputStream in = JuringInputStream.open(file)) {
                byte[] actual = new byte[(int) expectedLength];
                int read = in.read(actual, 0, actual.length);
                assertThat(read).as("all committed bytes readable after restart").isEqualTo(actual.length);
                for (int i = 0; i < actual.length; i++) {
                    if (actual[i] != pattern(i)) {
                        fail("byte mismatch at offset " + i + " after process restart: expected "
                                + pattern(i) + " but was " + actual[i]);
                    }
                }
            }
        }
    }

    // ==================================================================
    // child JVM entry point
    // ==================================================================

    /**
     * Child-process entry point: writes {@code records} deterministic records through one
     * {@link GroupCommit} batch, commits once, prints the durable marker and halts abruptly.
     * Never closes the group, the file, the ring or the arena on purpose.
     */
    static final class CrashWriter {

        private CrashWriter() {
        }

        public static void main(String[] args) throws Exception {
            Path path = Path.of(args[0]);
            int records = Integer.parseInt(args[1]);
            int recordSize = Integer.parseInt(args[2]);
            long recordBytes = (long) records * recordSize;

            JUring ring = new JUring(64, IORING_SETUP_SINGLE_ISSUER);
            JuringFile file = JuringFile.open(ring, path, 0644, RDWR, CREATE, TRUNCATE);
            Arena arena = Arena.ofConfined();
            MemorySegment source = arena.allocate(recordBytes, BLOCK);
            for (int i = 0; i < recordBytes; i++) {
                source.set(JAVA_BYTE, i, pattern(i));
            }
            GroupCommit group = GroupCommit.open(file,
                    GroupCommitOptions.defaults().withMaxBatchSize(records));
            for (int r = 0; r < records; r++) {
                group.write((long) r * recordSize, source.asSlice((long) r * recordSize, recordSize));
            }
            int writes = group.commit();
            GroupCommitStats stats = group.stats();
            System.out.println("DURABLE bytes=" + recordBytes
                    + " writes=" + writes
                    + " batches=" + stats.batches()
                    + " syncs=" + stats.syncs()
                    + " committedWrites=" + stats.committedWrites());
            System.out.flush();
            Runtime.getRuntime().halt(137);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static String markerLine(String output) {
        for (String line : output.split("\\R")) {
            if (line.startsWith("DURABLE ")) {
                return line;
            }
        }
        fail("child output has no DURABLE marker:\n" + output);
        return null; // unreachable
    }

    private static long markerValue(String marker, String key) {
        String prefix = "DURABLE ";
        for (String part : marker.substring(prefix.length()).split(" ")) {
            int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).equals(key)) {
                return Long.parseLong(part.substring(equals + 1));
            }
        }
        fail("marker has no '" + key + "' field: " + marker);
        return -1L; // unreachable
    }

    private static byte pattern(long offset) {
        return (byte) ((offset * 31 + (offset >>> 12) + 7) & 0xFF);
    }
}
