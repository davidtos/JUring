package com.davidvlijmincx.lio.examples;

import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.JuringFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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
 * Example E3 — one ring driving many open files in a single batched submission.
 *
 * <p><b>seastar mapping:</b> the seastar many-open-{@code file} pattern. An application
 * keeps many small files (segments, partition logs, spool files) open at once and issues
 * {@code dma_read}/{@code dma_write} on all of them from one reactor, relying on
 * {@code io_uring} multi-file batching instead of one thread per file.
 *
 * <p><b>JUring ↔ seastar difference:</b> JUring has one submission/completion ring owned
 * by the calling thread; many {@link JuringFile} handles can live on that ring and the
 * application decides the batch. Each handle is opened and registered as a fixed-file
 * slot (the descriptor then costs no SQE field per operation), and the whole cross-file
 * batch is prepared with the raw {@link JUring} API and submitted once. Since B10a those handles can also be
 * driven through their blocking helpers on the same ring — completions are routed to the
 * issuing handle — but this example deliberately keeps the batch at the raw
 * {@code prepare*} + ring-level reaping level to show the lowest-overhead batch submission;
 * the raw batch is a choice here, not a restriction. seastar expresses the same
 * one-ring/many-files pattern through its reactor, where every file object participates.
 *
 * <p>The test opens {@value #FILES} independent direct-I/O files on one ring, registers
 * them, and issues {@value #FILES} × {@value #BLOCKS_PER_FILE} writes (two offsets per
 * file) in one batch; it then issues the same number of reads in one batch and compares
 * every byte. It also checks the fixed-buffer pool capacity bound: the pool is fully
 * owned while a batch is in flight and is fully returned afterwards. No timing or
 * throughput assertion is made.
 */
@Timeout(120)
class ManyFilesExampleTest {

    private static final int FILES = 8;
    private static final int BLOCKS_PER_FILE = 2;
    private static final int BLOCK = 4096;
    private static final int BATCH = FILES * BLOCKS_PER_FILE;

    @Test
    void oneRingDrivesManyFilesInFlight(@TempDir Path dir) throws Exception {
        Path[] paths = new Path[FILES];
        for (int file = 0; file < FILES; file++) {
            paths[file] = dir.resolve("many-files-" + file + ".bin");
        }

        try (JUring ring = new JUring(64)) {
            ring.registerBuffers(BLOCK, BATCH);

            JuringFile[] handles = new JuringFile[FILES];
            try {
                for (int file = 0; file < FILES; file++) {
                    handles[file] = JuringFile.open(ring, paths[file], 0644,
                            RDWR, DIRECT, CREATE, TRUNCATE);
                    int slot = handles[file].register();
                    assertTrue(slot >= 0, "file " + file + " must get a fixed-file slot");
                    assertEquals(slot, handles[file].registeredSlot());
                }

                byte[][] expectedByBlock = new byte[BATCH][];
                byte[][] expectedByWriteBuffer = new byte[BATCH][];

                // Prepare two aligned blocks per file: the batch spans all eight files.
                for (int file = 0; file < FILES; file++) {
                    for (int block = 0; block < BLOCKS_PER_FILE; block++) {
                        int op = file * BLOCKS_PER_FILE + block;
                        int bufferIndex = ring.checkOutBuffer();
                        assertTrue(bufferIndex >= 0, "a fixed buffer must be available for op " + op);
                        expectedByBlock[op] = content(file, block);
                        expectedByWriteBuffer[bufferIndex] = expectedByBlock[op];
                        ring.getRegisteredBuffer(bufferIndex)
                                .copyFrom(java.lang.foreign.MemorySegment.ofArray(expectedByBlock[op]));
                        long handle = ring.prepareWriteFixed(handles[file].registeredSlot(), BLOCK,
                                (long) block * BLOCK, bufferIndex);
                        assertTrue(handle > 0);
                    }
                }
                assertEquals(-1, ring.checkOutBuffer(),
                        "the whole fixed-buffer pool is checked out while the cross-file write batch is in flight");

                int[] writeCompletions = new int[2];
                int reapedWrites = ring.submitAndWaitForBatchResult(BATCH, BATCH, result -> {
                    writeCompletions[0]++;
                    if (result.result() != BLOCK) {
                        writeCompletions[1]++;
                    }
                    ring.checkInBuffer(result.bufferIdx());
                });
                assertEquals(BATCH, reapedWrites, "one batch must reap every cross-file write completion");
                assertEquals(BATCH, writeCompletions[0]);
                assertEquals(0, writeCompletions[1]);
                assertPoolRestored(ring, BATCH);

                // Read the same two blocks of every file back in one cross-file batch.
                int[] bufferForOp = new int[BATCH];
                for (int file = 0; file < FILES; file++) {
                    for (int block = 0; block < BLOCKS_PER_FILE; block++) {
                        int op = file * BLOCKS_PER_FILE + block;
                        int bufferIndex = ring.checkOutBuffer();
                        assertTrue(bufferIndex >= 0);
                        bufferForOp[op] = bufferIndex;
                        long handle = ring.prepareReadFixed(handles[file].registeredSlot(), BLOCK,
                                (long) block * BLOCK, bufferIndex);
                        assertTrue(handle > 0);
                    }
                }
                assertEquals(-1, ring.checkOutBuffer());

                byte[][] readBack = new byte[BATCH][];
                int[] readCompletions = new int[2];
                int reapedReads = ring.submitAndWaitForBatchResult(BATCH, BATCH, result -> {
                    readCompletions[0]++;
                    if (result.result() != BLOCK) {
                        readCompletions[1]++;
                    } else {
                        int op = indexOf(bufferForOp, result.bufferIdx());
                        readBack[op] = ring.getRegisteredBuffer(result.bufferIdx()).toArray(JAVA_BYTE);
                    }
                    ring.checkInBuffer(result.bufferIdx());
                });
                assertEquals(BATCH, reapedReads, "one batch must reap every cross-file read completion");
                assertEquals(BATCH, readCompletions[0]);
                assertEquals(0, readCompletions[1]);
                assertPoolRestored(ring, BATCH);

                for (int file = 0; file < FILES; file++) {
                    for (int block = 0; block < BLOCKS_PER_FILE; block++) {
                        int op = file * BLOCKS_PER_FILE + block;
                        assertArrayEquals(expectedByBlock[op], readBack[op],
                                "file " + file + " block " + block + " must round-trip independently");
                    }
                }

                for (int file = 0; file < FILES; file++) {
                    assertEquals((long) BLOCKS_PER_FILE * BLOCK, Files.size(paths[file]),
                            "file " + file + " must contain exactly the bytes written through it");
                    assertTrue(handles[file].isOpen());
                }
            } finally {
                for (JuringFile handle : handles) {
                    if (handle != null) {
                        handle.close();
                    }
                }
            }

            for (int file = 0; file < FILES; file++) {
                assertEquals((long) BLOCKS_PER_FILE * BLOCK, Files.size(paths[file]));
            }
        }
    }

    private static void assertPoolRestored(JUring ring, int capacity) {
        boolean[] seen = new boolean[capacity];
        for (int i = 0; i < capacity; i++) {
            int bufferIndex = ring.checkOutBuffer();
            assertTrue(bufferIndex >= 0, "buffer " + i + " must be available again after the batch");
            assertFalse(seen[bufferIndex], "a returned buffer must not be handed out twice");
            seen[bufferIndex] = true;
        }
        assertEquals(-1, ring.checkOutBuffer(), "the pool capacity bound must be restored");
        for (int i = 0; i < capacity; i++) {
            ring.checkInBuffer(i);
        }
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        throw new AssertionError("buffer index " + value + " does not belong to the batch");
    }

    private static byte[] content(int file, int block) {
        byte[] data = new byte[BLOCK];
        byte[] header = ("file-" + file + "-block-" + block).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, data, 0, header.length);
        for (int i = header.length; i < data.length; i++) {
            data[i] = (byte) (file * 37 + block * 11 + i);
        }
        return data;
    }
}
