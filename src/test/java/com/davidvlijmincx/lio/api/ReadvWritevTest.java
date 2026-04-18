package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class ReadvWritevTest {

    JUring jUring;

    @BeforeEach
    void setUp() {
        jUring = new JUring(10, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        jUring.close();
    }

    @Test
    void readvSplitsFileIntoMultipleBuffers() {
        // read_file contains "Hello, World!" (13 bytes)
        // Split: first 7 bytes "Hello, " and next 6 bytes "World!"
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            long id = jUring.prepareReadv(fd, new int[]{7, 6}, 0);
            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof ReadvResult readvResult) {
                assertEquals(id, readvResult.id());
                // total bytes read should be 13
                assertEquals(13, readvResult.result());

                MemorySegment[] buffers = readvResult.buffers();
                assertEquals(2, buffers.length);

                String first = new String(buffers[0].toArray(JAVA_BYTE));
                String second = new String(buffers[1].toArray(JAVA_BYTE));

                readvResult.freeBuffers();

                assertEquals("Hello, ", first);
                assertEquals("World!", second);
            } else {
                fail("Result is not a ReadvResult: " + result);
            }
        }
    }

    @Test
    void writevCombinesMultipleBuffersToFile() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        byte[][] input = {
            "Hello, ".getBytes(),
            "World!".getBytes()
        };
        int expectedBytes = "Hello, ".length() + "World!".length(); // 13

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            long id = jUring.prepareWritev(fd, input, 0);
            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof WriteResult(long wId, long wResult)) {
                assertEquals(id, wId);
                assertEquals(expectedBytes, wResult);
            } else {
                fail("Result is not a WriteResult: " + result);
            }

            String writtenContent = Files.readString(path);
            assertEquals("Hello, World!", writtenContent);
        }
    }

    @Test
    void readvFixedUsesRegisteredBuffers() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUring.registerBuffers(7, 2);

            int b0 = jUring.checkOutBuffer();
            int b1 = jUring.checkOutBuffer();
            assertNotEquals(-1, b0);
            assertNotEquals(-1, b1);

            long id = jUring.prepareReadvFixed(fd, new int[]{b0, b1}, 0);
            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof ReadvResult readvResult) {
                assertEquals(id, readvResult.id());
                assertEquals(13, readvResult.result());

                MemorySegment[] buffers = readvResult.buffers();
                assertEquals(2, buffers.length);

                String first = new String(buffers[0].toArray(JAVA_BYTE));
                String second = new String(buffers[1].toArray(JAVA_BYTE), 0, (int)(readvResult.result() - 7));

                assertEquals("Hello, ", first);
                assertEquals("World!", second);
            } else {
                fail("Result is not a ReadvResult: " + result);
            }

            jUring.checkInBuffer(b0);
            jUring.checkInBuffer(b1);
        }
    }

    @Test
    void writevFixedUsesRegisteredBuffers() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            MemorySegment[] registered = jUring.registerBuffers(7, 2);

            int b0 = jUring.checkOutBuffer();
            int b1 = jUring.checkOutBuffer();
            assertNotEquals(-1, b0);
            assertNotEquals(-1, b1);

            registered[b0].copyFrom(MemorySegment.ofArray("Hello, ".getBytes()));
            registered[b1].copyFrom(MemorySegment.ofArray("World!".getBytes()));

            long id = jUring.prepareWritevFixed(fd, new int[]{b0, b1}, new long[]{7, 6}, 0);
            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof WriteResult(long wId, long wResult)) {
                assertEquals(id, wId);
                assertEquals(13, wResult);
            } else {
                fail("Result is not a WriteResult: " + result);
            }

            String writtenContent = Files.readString(path);
            assertEquals("Hello, World!", writtenContent);

            jUring.checkInBuffer(b0);
            jUring.checkInBuffer(b1);
        }
    }
}
