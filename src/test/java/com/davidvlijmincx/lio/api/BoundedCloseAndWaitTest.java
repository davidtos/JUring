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
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.1.0 shutdown hardening: waits that can never be satisfied fail fast instead of blocking
 * forever, and stream {@code close()} is hard-bounded (it abandons un-drainable I/O with a
 * counted, bounded leak instead of falling back to an unbounded wait).
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
    void preparedButNotSubmittedWaitFailsFast() throws Exception {
        Path file = tempDir.resolve("unsubmitted.bin");
        Files.write(file, new byte[8]);
        try (Arena arena = Arena.ofConfined();
             JUring ring = new JUring(16);
             FileDescriptor fd = new FileDescriptor(file, READ)) {
            ring.prepareRead(fd, arena.allocate(8), 0); // prepared, never submitted
            IllegalStateException error = assertThrows(IllegalStateException.class, ring::waitForResult);
            assertTrue(error.getMessage().contains("submitted"),
                    "the error must point at the missing submit(): " + error.getMessage());
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
            assertEquals(0L, in.bufferMemoryBytes(), "a normal close must release the buffers");
        }
    }

    @Test
    void inputCloseAbandonsARealBlockedReadAndKeepsItsBufferAlive() throws Exception {
        Path fifo = FifoTools.createFifo(tempDir, "stuck-read.fifo");
        Thread writer = FifoTools.holdWriteEndOpen(fifo);
        try (JUring ring = new JUring(64);
             JuringFile handle = JuringFile.open(ring, fifo, READ)) {
            JuringInputStream in = JuringInputStream.open(handle,
                    InputStreamOptions.defaults().withReadAhead(0));
            MemorySegment[] buffers = streamBuffers(JuringInputStream.class, in, "buffers");
            // A real read submitted against the stream's own buffer; the pipe has no data, so
            // it blocks in the kernel. Injecting it into the prefetch FIFO makes close() drain
            // it, which must give up at the deadline and keep the stream's arena alive.
            long readHandle = handle.prepareRead(0, 8192, buffers[0]);
            ring.submit();
            injectPendingDescriptor(in, readHandle);

            long start = System.nanoTime();
            JuringException closeError = assertThrows(JuringException.class, in::close);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(closeError.isTimedOut(),
                    "bounded close must surface ETIMEDOUT, got " + closeError);
            assertEquals(1, in.abandonedAtClose(),
                    "the un-drainable read must be counted as abandoned");
            assertTrue(elapsedMs >= 1_500L && elapsedMs < 8_000L,
                    "close must be bounded by the deadline (took " + elapsedMs + " ms)");
            assertTrue(in.bufferMemoryBytes() > 0,
                    "an abandoned close retains the stream buffers");
            assertDoesNotThrow(() -> buffers[0].get(JAVA_BYTE, 0),
                    "the kernel may still read into the stream buffer: it must stay alive");
            in.close(); // idempotent, must not throw or double count
            assertEquals(1, in.abandonedAtClose());
        } finally {
            FifoTools.stop(writer);
        }
    }

    @Test
    void outputCloseAbandonsARealBlockedWriteAndKeepsItsBufferAlive() throws Exception {
        Path fifo = FifoTools.createFifo(tempDir, "stuck-write.fifo");
        Thread reader = FifoTools.holdReadEndOpen(fifo);
        try (Arena arena = Arena.ofConfined();
             JUring ring = new JUring(64);
             JuringFile handle = JuringFile.open(ring, fifo, WRITE)) {
            JuringOutputStream out = JuringOutputStream.open(handle,
                    OutputStreamOptions.defaults()
                            .withBufferSize(65536)
                            .withWriteBehind(1)
                            .withAlignment(4096)
                            .withDurabilityMode(DurabilityMode.WRITEBACK));
            MemorySegment chunk = arena.allocate(65536);
            out.write(chunk);
            out.write(chunk); // fills the pipe; the next write blocks in the kernel

            long start = System.nanoTime();
            JuringException closeError = assertThrows(JuringException.class, out::close);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(closeError.isTimedOut(),
                    "bounded close must surface ETIMEDOUT, got " + closeError);
            assertEquals(1, out.abandonedAtClose(),
                    "the blocked write must be counted as abandoned");
            assertTrue(elapsedMs >= 1_500L && elapsedMs < 8_000L,
                    "close must be bounded by the deadline (took " + elapsedMs + " ms)");
            assertTrue(out.bufferMemoryBytes() > 0,
                    "an abandoned close retains the stream buffers");
            MemorySegment[] buffers = streamBuffers(JuringOutputStream.class, out, "buffers");
            assertDoesNotThrow(() -> buffers[0].get(JAVA_BYTE, 0),
                    "the kernel may still write into the stream buffer: it must stay alive");
            out.close(); // idempotent, must not throw or double count
            assertEquals(1, out.abandonedAtClose());
        } finally {
            FifoTools.stop(reader);
        }
    }

    @Test
    void normalOutputCloseDoesNotAbandonWrites() throws Exception {
        Path file = tempDir.resolve("out.bin");
        byte[] payload = new byte[1000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        try (Arena arena = Arena.ofConfined();
             JUring ring = new JUring(64);
             JuringFile handle = JuringFile.open(ring, file, WRITE, CREATE, TRUNCATE);
             JuringOutputStream out = JuringOutputStream.open(handle,
                     OutputStreamOptions.defaults().withBufferSize(8192).withWriteBehind(1)
                             .withDurabilityMode(DurabilityMode.FDATASYNC))) {
            MemorySegment nativePayload = arena.allocate(payload.length);
            MemorySegment.copy(payload, 0, nativePayload, JAVA_BYTE, 0, payload.length);
            out.write(nativePayload);
            out.close();
            assertEquals(0, out.abandonedAtClose(), "a normal close must reap every write");
            assertEquals(0L, out.bufferMemoryBytes(), "a normal close must release the buffers");
            assertEquals(1L, out.syncs(), "FDATASYNC close performs exactly one data sync");
        }
        assertArrayEquals(payload, Files.readAllBytes(file),
                "the padded partial buffer must be truncated back to the logical length");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static MemorySegment[] streamBuffers(Class<?> type, Object stream, String fieldName)
            throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (MemorySegment[]) field.get(stream);
    }

    /**
     * Test seam: places a submitted-but-blocked read at the head of the stream's prefetch FIFO.
     * The stream only issues reads that cannot stall on a regular file, so this is the
     * deterministic way to exercise the bounded-close deadline with a real blocked request.
     */
    private static void injectPendingDescriptor(JuringInputStream in, long readHandle) throws Exception {
        ((byte[]) field(in, "state"))[0] = 0; // PENDING
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
