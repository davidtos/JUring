package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.1.0 shutdown hardening: blocking waits with nothing in flight fail fast instead of
 * blocking forever, and stream {@code close()} is hard-bounded (it abandons un-drainable
 * I/O with a counted, bounded leak instead of falling back to an unbounded wait).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BoundedCloseAndWaitTest {

    @TempDir
    Path tempDir;

    @Test
    void waitingWithNoInFlightWorkThrowsInsteadOfBlocking() {
        try (JUring ring = new JUring(8)) {
            IllegalStateException resultWait = assertThrows(IllegalStateException.class, ring::waitForResult);
            assertTrue(resultWait.getMessage().contains("no operations are in flight"),
                    "unexpected message: " + resultWait.getMessage());

            assertThrows(IllegalStateException.class, () -> ring.waitForBatchResult(1));
            assertThrows(IllegalStateException.class, () -> ring.waitForBatchResult(1, result -> { }));
            assertEquals(0, ring.inFlightCount(), "the rejected waits must not consume or leak work");
        }
    }

    @Test
    void normalInputCloseDoesNotAbandonReads() throws Exception {
        Path file = tempDir.resolve("small.bin");
        Files.write(file, new byte[32 * 1024]);
        try (JUring ring = new JUring(64);
             JuringFile handle = JuringFile.open(ring, file, READ);
             JuringInputStream in = JuringInputStream.open(handle,
                     InputStreamOptions.defaults().withReadAhead(2))) {
            MemorySegment chunk = in.readChunk();
            assertTrue(chunk.byteSize() > 0, "expected data from the input stream");
            in.close();
            assertEquals(0, in.abandonedAtClose(), "a normal close must reap every read");
        }
    }

    @Test
    void inputCloseAbandonsAReadThatMissesTheDeadline() throws Exception {
        Path file = tempDir.resolve("stuck.bin");
        Files.write(file, new byte[8192]);
        try (Arena arena = Arena.ofConfined();
             JUring ring = new JUring(64);
             JuringFile handle = JuringFile.open(ring, file, READ)) {
            JuringInputStream in = JuringInputStream.open(handle);
            // Inject one real but never-submitted read into the stream's prefetch FIFO: its
            // completion cannot arrive, so close() must abandon it at the hard deadline
            // instead of falling back to an unbounded wait.
            long readHandle = handle.prepareRead(0, 4096, arena.allocate(4096));
            injectPendingDescriptor(in, readHandle);

            long start = System.nanoTime();
            JuringException closeError = assertThrows(JuringException.class, in::close);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(closeError.isTimedOut(),
                    "bounded close must surface ETIMEDOUT, got " + closeError);
            assertEquals(1, in.abandonedAtClose(),
                    "the un-drainable read must be counted as abandoned");
            assertTrue(elapsedMs >= 1_500L && elapsedMs < 15_000L,
                    "close must be bounded by the deadline (took " + elapsedMs + " ms)");
        }
    }

    @Test
    void normalOutputCloseDoesNotAbandonWrites() throws Exception {
        Path file = tempDir.resolve("out.bin");
        try (Arena arena = Arena.ofConfined();
             JUring ring = new JUring(64);
             JuringFile handle = JuringFile.open(ring, file, WRITE, CREATE, TRUNCATE);
             JuringOutputStream out = JuringOutputStream.open(handle,
                     OutputStreamOptions.defaults().withBufferSize(8192).withWriteBehind(1))) {
            MemorySegment payload = arena.allocate(1000);
            for (int i = 0; i < 1000; i++) {
                payload.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) i);
            }
            out.write(payload);
            out.close();
            assertEquals(0, out.abandonedAtClose(), "a normal close must reap every write");
        }
    }

    /**
     * Test seam: places a prepared-but-not-submitted read at the head of the stream's prefetch
     * FIFO. The stream itself never issues a read that can stall on a regular file, so this is
     * the only deterministic way to exercise the bounded-close deadline.
     */
    private static void injectPendingDescriptor(JuringInputStream in, long readHandle) throws Exception {
        // PENDING == 0 in JuringInputStream
        ((byte[]) field(in, "state"))[0] = 0;
        ((long[]) field(in, "handles"))[0] = readHandle;
        ((int[]) field(in, "fifo"))[0] = 0;
        setInt(in, "fifoHead", 0);
        setInt(in, "fifoSize", 1);
    }

    private static Object field(JuringInputStream in, String name) throws Exception {
        Field field = JuringInputStream.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(in);
    }

    private static void setInt(JuringInputStream in, String name, int value) throws Exception {
        Field field = JuringInputStream.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(in, value);
    }
}
