package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectIoTest {

    private static final int BLOCK_SIZE = 4096;
    private JUring jUring;

    @BeforeEach
    void setUp() {
        jUring = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        if (jUring != null) {
            jUring.close();
        }
    }

    /**
     * Generates 4096 bytes of text (64 lines of 64 bytes each).
     */
    private static String generateBlockData() {
        StringBuilder sb = new StringBuilder(BLOCK_SIZE);
        for (int i = 1; i <= 64; i++) {
            sb.append(String.format(Locale.ROOT, "Line %02d: %s\n", i, "x".repeat(54)));
        }
        return sb.toString();
    }

    @Test
    void testDirectIoWrite(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("direct_io_write_test.txt");

        String expectedText = generateBlockData();
        byte[] expectedBytes = expectedText.getBytes(StandardCharsets.UTF_8);
        assertEquals(BLOCK_SIZE, expectedBytes.length);

        // Register 4096-byte aligned buffer in JUring
        jUring.registerBuffers(BLOCK_SIZE, 2);

        // Open with O_DIRECT | O_WRONLY | O_CREAT
        try (FileDescriptor fd = new FileDescriptor(testFile, 0644, WRITE_DIRECT, CREATE, TRUNCATE)) {
            long writeId = jUring.prepareWriteFixed(fd, expectedBytes, 0, 0);
            jUring.submit();

            Result result = jUring.waitForResult();
            assertEquals(writeId, result.id(), "Completed operation ID must match submitted ID");
            assertEquals(BLOCK_SIZE, result.result(), "Direct I/O write must write exactly 4096 bytes");
        }

        // Verify the file content on disk
        String writtenText = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(expectedText, writtenText);
        assertEquals(BLOCK_SIZE, Files.size(testFile));
    }

    @Test
    void testDirectIoRead(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("direct_io_read_test.txt");

        String expectedText = generateBlockData();
        byte[] expectedBytes = expectedText.getBytes(StandardCharsets.UTF_8);
        assertEquals(BLOCK_SIZE, expectedBytes.length);

        // Prepare test file
        Files.write(testFile, expectedBytes);
        assertEquals(BLOCK_SIZE, Files.size(testFile));

        // Register 4096-byte aligned buffer in JUring
        jUring.registerBuffers(BLOCK_SIZE, 2);

        // Open with O_DIRECT | O_RDONLY
        try (FileDescriptor fd = new FileDescriptor(testFile.toString(), READ_DIRECT, 0)) {
            long readId = jUring.prepareReadFixed(fd, BLOCK_SIZE, 0, 0);
            jUring.submit();

            Result result = jUring.waitForResult();
            assertEquals(readId, result.id(), "Completed operation ID must match submitted ID");
            assertEquals(BLOCK_SIZE, result.result(), "Direct I/O read must read exactly 4096 bytes");

            // Verify read content matches expected content
            String actualText = result.getString(StandardCharsets.UTF_8);
            assertEquals(expectedText, actualText);
            assertTrue(actualText.startsWith("Line 01: " + "x".repeat(54) + "\n"));
            assertTrue(actualText.endsWith("Line 64: " + "x".repeat(54) + "\n"));
        }
    }

    @Test
    void testDirectIoZeroCopyWrite(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("direct_io_zero_copy_write_test.txt");

        String expectedText = generateBlockData();
        byte[] expectedBytes = expectedText.getBytes(StandardCharsets.UTF_8);

        // Register 4096-byte aligned buffer in JUring
        jUring.registerBuffers(BLOCK_SIZE, 2);

        // Check out buffer 0 and copy data directly into registered memory segment
        int bufIdx = jUring.checkOutBuffer();
        assertTrue(bufIdx >= 0, "A registered buffer should be successfully checked out");
        MemorySegment registeredBuffer = jUring.getRegisteredBuffer(bufIdx);
        MemorySegment.copy(MemorySegment.ofArray(expectedBytes), 0, registeredBuffer, 0, BLOCK_SIZE);

        try (FileDescriptor fd = new FileDescriptor(testFile, 0644, WRITE_DIRECT, CREATE, TRUNCATE)) {
            // Zero-copy overload: does not re-copy into the buffer
            long writeId = jUring.prepareWriteFixed(fd, BLOCK_SIZE, 0, bufIdx);
            jUring.submit();

            Result result = jUring.waitForResult();
            assertEquals(writeId, result.id());
            assertEquals(BLOCK_SIZE, result.result());
        } finally {
            jUring.checkInBuffer(bufIdx);
        }

        String writtenText = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(expectedText, writtenText);
        assertEquals(BLOCK_SIZE, Files.size(testFile));
    }

    @Test
    void testDirectIoWriteAndReadRoundTrip(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("direct_io_roundtrip_test.txt");

        String expectedText = generateBlockData();
        byte[] expectedBytes = expectedText.getBytes(StandardCharsets.UTF_8);

        // Register 4096-byte aligned buffer in JUring
        jUring.registerBuffers(BLOCK_SIZE, 2);

        // 1. Direct I/O Write
        try (FileDescriptor writeFd = new FileDescriptor(testFile, 0644, WRITE_DIRECT, CREATE, TRUNCATE)) {
            long writeId = jUring.prepareWriteFixed(writeFd, expectedBytes, 0, 0);
            jUring.submit();

            Result writeResult = jUring.waitForResult();
            assertEquals(writeId, writeResult.id());
            assertEquals(BLOCK_SIZE, writeResult.result());
        }

        // 2. Direct I/O Read back
        try (FileDescriptor readFd = new FileDescriptor(testFile.toString(), READ_DIRECT, 0)) {
            long readId = jUring.prepareReadFixed(readFd, BLOCK_SIZE, 0, 1);
            jUring.submit();

            Result readResult = jUring.waitForResult();
            assertEquals(readId, readResult.id());
            assertEquals(BLOCK_SIZE, readResult.result());

            String actualText = readResult.getString(StandardCharsets.UTF_8);
            assertEquals(expectedText, actualText);
        }
    }
}
