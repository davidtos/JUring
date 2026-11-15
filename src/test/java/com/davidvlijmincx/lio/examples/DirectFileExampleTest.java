package com.davidvlijmincx.lio.examples;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.DIRECT;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example E1 — direct file I/O with a registered file, fixed buffers and one batched
 * submit/reap.
 *
 * <p><b>seastar mapping:</b> {@code open_file_dma} + {@code file::dma_read} /
 * {@code file::dma_write}. seastar opens a file with {@code O_DIRECT} support, keeps the
 * descriptor in the reactor's stable file table, and issues aligned DMA reads/writes that
 * complete as futures; the caller keeps the aligned buffers alive until the future
 * resolves.
 *
 * <p><b>JUring ↔ seastar difference:</b> JUring is a plain library with no reactor. The
 * application explicitly registers the descriptor in the ring's fixed-file table
 * ({@link JUring#registerFiles}) and a pool of page-aligned fixed buffers
 * ({@link JUring#registerBuffers}), then prepares every SQE itself and drives submission
 * and completion reaping from the calling thread. Buffer ownership is explicit:
 * {@link JUring#checkOutBuffer()} hands out an index and {@link JUring#checkInBuffer(int)}
 * returns it; the kernel never allocates or selects a buffer on this path (no
 * provided-buffer rings). The batch is one {@link JUring#submitAndWaitForBatchResult}
 * call, which is the library equivalent of a seastar promise/continuation pair on a
 * batch of futures.
 *
 * <p>The test writes {@value #BLOCKS} aligned blocks through the registered-file +
 * fixed-buffer fast path, reaps all completions in one batch, checks buffer-pool
 * accounting (the whole pool is owned while the batch is in flight and is fully returned
 * afterwards), then reads the same blocks back and compares them byte for byte. It uses
 * only JUnit's {@link TempDir}: no benchmark device path is needed, and the test makes no
 * timing or throughput assertion.
 */
@Timeout(60)
class DirectFileExampleTest {

    private static final int BLOCK = 4096;
    private static final int BLOCKS = 8;

    @Test
    void directWriteThenReadBackThroughRegisteredFileAndFixedBuffers(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("direct-example.bin");

        try (JUring ring = new JUring(16);
             FileDescriptor fd = new FileDescriptor(path, 0644, RDWR, DIRECT, CREATE, TRUNCATE)) {

            // Register the descriptor once; slot 0 then selects it with IOSQE_FIXED_FILE.
            assertEquals(0, ring.registerFiles(fd), "registering the file must succeed");
            ring.registerBuffers(BLOCK, BLOCKS);

            byte[][] expectedByBlock = new byte[BLOCKS][];
            byte[][] expectedByWriteBuffer = new byte[BLOCKS][];

            // Prepare all writes before submitting: one batch, BLOCKS SQEs in flight.
            for (int block = 0; block < BLOCKS; block++) {
                int bufferIndex = ring.checkOutBuffer();
                assertTrue(bufferIndex >= 0, "a fixed buffer must be available for block " + block);
                expectedByBlock[block] = content(block);
                expectedByWriteBuffer[bufferIndex] = expectedByBlock[block];
                ring.getRegisteredBuffer(bufferIndex)
                        .copyFrom(MemorySegment.ofArray(expectedByBlock[block]));
                long handle = ring.prepareWriteFixed(0, BLOCK, (long) block * BLOCK, bufferIndex);
                assertTrue(handle > 0, "prepareWriteFixed must return a handle");
            }
            assertEquals(-1, ring.checkOutBuffer(),
                    "the whole fixed-buffer pool is checked out while the write batch is in flight");

            int[] writeCompletions = new int[2];
            int reapedWrites = ring.submitAndWaitForBatchResult(BLOCKS, BLOCKS, result -> {
                writeCompletions[0]++;
                if (result.result() != BLOCK) {
                    writeCompletions[1]++;
                }
                ring.checkInBuffer(result.bufferIdx());
            });
            assertEquals(BLOCKS, reapedWrites, "one batch must reap every write completion");
            assertEquals(BLOCKS, writeCompletions[0], "every completions callback must run");
            assertEquals(0, writeCompletions[1], "every direct write must transfer a full block");

            // Every buffer is checked in again, so the pool is reusable and holds the
            // written bytes (bounded, deterministic ownership accounting).
            boolean[] seen = new boolean[BLOCKS];
            for (int i = 0; i < BLOCKS; i++) {
                int bufferIndex = ring.checkOutBuffer();
                assertTrue(bufferIndex >= 0, "the buffer pool must be fully available again");
                assertFalse(seen[bufferIndex], "a returned buffer must not be handed out twice");
                seen[bufferIndex] = true;
                assertArrayEquals(expectedByWriteBuffer[bufferIndex],
                        ring.getRegisteredBuffer(bufferIndex).toArray(JAVA_BYTE),
                        "the registered buffer must still hold the written block");
            }
            assertEquals(-1, ring.checkOutBuffer(), "the pool is exhausted again at the capacity bound");
            for (int i = 0; i < BLOCKS; i++) {
                ring.checkInBuffer(i);
            }

            // Read the blocks back through the same registered file + fixed buffers.
            int[] bufferForBlock = new int[BLOCKS];
            for (int block = 0; block < BLOCKS; block++) {
                int bufferIndex = ring.checkOutBuffer();
                assertTrue(bufferIndex >= 0);
                bufferForBlock[block] = bufferIndex;
                long handle = ring.prepareReadFixed(0, BLOCK, (long) block * BLOCK, bufferIndex);
                assertTrue(handle > 0);
            }
            assertEquals(-1, ring.checkOutBuffer(),
                    "the whole fixed-buffer pool is checked out while the read batch is in flight");

            byte[][] readBack = new byte[BLOCKS][];
            int[] readCompletions = new int[2];
            int reapedReads = ring.submitAndWaitForBatchResult(BLOCKS, BLOCKS, result -> {
                readCompletions[0]++;
                if (result.result() != BLOCK) {
                    readCompletions[1]++;
                } else {
                    int bufferIndex = result.bufferIdx();
                    int block = indexOf(bufferForBlock, bufferIndex);
                    readBack[block] = ring.getRegisteredBuffer(bufferIndex).toArray(JAVA_BYTE);
                }
                ring.checkInBuffer(result.bufferIdx());
            });
            assertEquals(BLOCKS, reapedReads, "one batch must reap every read completion");
            assertEquals(BLOCKS, readCompletions[0]);
            assertEquals(0, readCompletions[1], "every direct read must transfer a full block");

            for (int block = 0; block < BLOCKS; block++) {
                assertArrayEquals(expectedByBlock[block], readBack[block],
                        "direct read-back of block " + block + " must be byte-identical");
            }
        }

        assertEquals((long) BLOCKS * BLOCK, Files.size(path));
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        throw new AssertionError("buffer index " + value + " does not belong to the batch");
    }

    private static byte[] content(int block) {
        byte[] data = new byte[BLOCK];
        byte[] header = ("direct-block-" + block).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, data, 0, header.length);
        for (int i = header.length; i < data.length; i++) {
            data[i] = (byte) (block * 31 + i);
        }
        return data;
    }
}
