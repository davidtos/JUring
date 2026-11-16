package com.davidvlijmincx.lio.examples;

import com.davidvlijmincx.lio.api.DurabilityMode;
import com.davidvlijmincx.lio.api.InputStreamOptions;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.JuringFile;
import com.davidvlijmincx.lio.api.JuringInputStream;
import com.davidvlijmincx.lio.api.JuringOutputStream;
import com.davidvlijmincx.lio.api.OutputStreamOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DIRECT;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example E2 — stream copy with read-ahead and write-behind.
 *
 * <p><b>seastar mapping:</b> {@code seastar::fstream}. seastar's {@code file_input_stream}
 * keeps {@code readAhead + 1} read requests in flight (growing adaptively), and
 * {@code file_output_stream} keeps up to {@code writeBehind} writes in flight before a
 * write waits; {@code flush()} waits for queued writes and {@code close()} completes the
 * stream. A typical seastar copy is a future chain: {@code fstream::read()} then
 * {@code fstream::write()} repeated, with the reactor scheduling the continuations.
 *
 * <p><b>JUring ↔ seastar difference:</b> JUring has no reactor and no futures. Both
 * streams are synchronous-style wrappers over a {@link JuringFile}: {@code read} returns
 * the bytes it copied into the caller's buffer and drives the prefetch pipeline itself,
 * while {@code write} copies into internal buffers and may leave write-behind operations
 * in flight until {@code flush()}. Each stream owns a fixed, option-bounded native buffer
 * set and never closes the underlying {@link JuringFile}. Like seastar's single reactor,
 * several file-based handles can share one ring: since B10a the reader and writer are
 * multiplexed on the same {@link JUring}, with each completion routed to the handle that
 * issued it, so this example opens one ring for both directions.
 *
 * <p>The test copies a 128 KiB pattern file and asserts: the destination is byte-identical
 * to the source, the stream metadata reports the configured options, the native buffer
 * memory is exactly the option-bounded amount while open (and zero after close), and a
 * {@code flush()} makes the file length equal the logical stream position. It uses a JUnit
 * {@link TempDir} and makes no timing or throughput assertion.
 */
@Timeout(120)
class StreamCopyExampleTest {

    private static final int BLOCK = 4096;
    private static final int BUFFER = 16 * 1024;
    private static final int READ_AHEAD = 2;
    private static final int WRITE_BEHIND = 2;
    private static final int SIZE = 128 * 1024;

    @Test
    void copyFileWithStreamReadAheadAndWriteBehind(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("stream-copy-source.bin");
        Path target = dir.resolve("stream-copy-target.bin");
        byte[] expected = pattern(SIZE);
        Files.write(source, expected);

        try (JUring ring = new JUring(64);
             JuringFile sourceFile = JuringFile.open(ring, source, READ, DIRECT);
             JuringFile targetFile = JuringFile.open(ring, target, 0644, RDWR, DIRECT, CREATE, TRUNCATE)) {

            InputStreamOptions inputOptions = InputStreamOptions.defaults()
                    .withBufferSize(BUFFER)
                    .withReadAhead(READ_AHEAD)
                    .withAlignment(BLOCK);
            OutputStreamOptions outputOptions = OutputStreamOptions.defaults()
                    .withBufferSize(BUFFER)
                    .withWriteBehind(WRITE_BEHIND)
                    .withAlignment(BLOCK);

            try (JuringInputStream in = JuringInputStream.open(sourceFile, inputOptions);
                 JuringOutputStream out = JuringOutputStream.open(targetFile, outputOptions)) {

                assertEquals(READ_AHEAD, in.readAhead());
                assertEquals(WRITE_BEHIND, out.writeBehind());
                assertEquals(DurabilityMode.WRITEBACK, out.durability());

                // Bounded memory: (readAhead + 1) buffers of (alignUp(bufferSize, alignment) +
                // alignment) bytes for the reader, (writeBehind + 1) aligned buffers for the
                // writer. Both are allocated once at open and sized only by the options.
                long inputMemory = in.bufferMemoryBytes();
                long outputMemory = out.bufferMemoryBytes();
                assertEquals((long) (READ_AHEAD + 1) * (BUFFER + BLOCK), inputMemory);
                assertEquals((long) (WRITE_BEHIND + 1) * BUFFER, outputMemory);
                assertTrue(inputMemory + outputMemory <= 1024L * 1024L,
                        "stream buffer memory is option-bounded, not file-size-bounded");

                byte[] chunk = new byte[BUFFER];
                long copied = 0L;
                int read;
                while ((read = in.read(chunk, 0, chunk.length)) > 0) {
                    out.write(chunk, 0, read);
                    copied += read;
                }
                assertEquals(SIZE, copied, "the copy loop must consume the whole source");
                assertEquals(0, in.read(chunk, 0, chunk.length), "the stream reports EOF with a zero read");
                assertTrue(in.eof());

                out.flush();
                assertEquals(copied, out.position());
                assertEquals(copied, out.bytesWritten());
                assertEquals(out.position(), targetFile.length(),
                        "after flush() every buffered byte has reached the file");

                in.close();
                out.close();
                assertEquals(0L, in.bufferMemoryBytes(), "close() releases the reader's native buffers");
                assertEquals(0L, out.bufferMemoryBytes(), "close() releases the writer's native buffers");
                assertTrue(sourceFile.isOpen(), "the stream must not close the underlying JuringFile");
                assertTrue(targetFile.isOpen(), "the stream must not close the underlying JuringFile");
            }

            assertEquals((long) SIZE, targetFile.length());
            assertEquals((long) SIZE, Files.size(target));
        }

        assertArrayEquals(expected, Files.readAllBytes(target),
                "the stream copy must be byte-identical to the source");
    }

    private static byte[] pattern(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 13 + (i >>> 8));
        }
        return data;
    }
}
