package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JUringBlockingTest {

    static JUringBlocking jUringBlocking;

    @BeforeEach
    void setUp() {
        jUringBlocking = new JUringBlocking(10, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        jUringBlocking.close();
    }

    private static String getString(MemorySegment buffer, long bytesRead) {
        return new String(buffer.asSlice(0, bytesRead).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    @Test
    void readFromFile() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(14);
            long bytesRead = jUringBlocking.read(fd, buf, 14, 0);
            assertEquals(13L, bytesRead);
            assertEquals("Hello, World!", getString(buf, bytesRead));
        }
    }

    @Test
    void multiReadFromFile() throws Exception {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             Arena arena = Arena.ofShared();
             var vts = Executors.newVirtualThreadPerTaskExecutor()) {

            MemorySegment buf1 = arena.allocate(14);
            MemorySegment buf2 = arena.allocate(5);
            MemorySegment buf3 = arena.allocate(7);

            Future<Long> f1 = vts.submit(() -> jUringBlocking.read(fd, buf1, 14, 0));
            Future<Long> f2 = vts.submit(() -> jUringBlocking.read(fd, buf2, 5, 0));
            Future<Long> f3 = vts.submit(() -> jUringBlocking.read(fd, buf3, 7, 7));

            assertEquals("Hello, World!", getString(buf1, f1.get()));
            assertEquals("Hello", getString(buf2, f2.get()));
            assertEquals("World!", getString(buf3, f3.get()));
        }
    }

    @Test
    void readFromFileAtOffset() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(6);
            long bytesRead = jUringBlocking.read(fd, buf, 6, 7);
            assertEquals(6L, bytesRead);
            assertEquals("World!", getString(buf, bytesRead));
        }
    }

    @Test
    void writeToFile() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content : ".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            long written = jUringBlocking.write(fd, inputBytes, 0);
            assertEquals(inputBytes.length, written);

            String writtenContent = Files.readString(path);
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void writeToFileAtOffset() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Big ".getBytes());

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            long written = jUringBlocking.write(fd, inputBytes, 4);
            assertEquals(inputBytes.length, written);

            String writtenContent = Files.readString(path);
            assertEquals("Big hello, from Java", writtenContent);
        }
    }

    @Test
    void multipleWrites() throws Exception {
        Files.write(Path.of("src/test/resources/write_file"), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/write_file", WRITE, 0);
             var vts = Executors.newVirtualThreadPerTaskExecutor()) {
            var future1 = vts.submit(() -> jUringBlocking.write(fd, inputBytes, 0));
            var future2 = vts.submit(() -> jUringBlocking.write(fd, inputBytes, 0));
            var future3 = vts.submit(() -> jUringBlocking.write(fd, inputBytes, 0));

            assertEquals((long) inputBytes.length, future1.get());
            assertEquals((long) inputBytes.length, future2.get());
            assertEquals((long) inputBytes.length, future3.get());
        }
    }

    @Test
    void mixedWriteAndReadFromFile() throws Exception {
        Path writePath = Path.of("src/test/resources/write_file");
        Path readPath = Path.of("src/test/resources/read_file");

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(readPath.toString(), READ, 0);
             FileDescriptor writeFd = new FileDescriptor(writePath.toString(), WRITE, 0);
             Arena arena = Arena.ofShared();
             var vts = Executors.newVirtualThreadPerTaskExecutor()) {

            MemorySegment b1 = arena.allocate(14);
            MemorySegment b2 = arena.allocate(5);
            MemorySegment b3 = arena.allocate(7);

            var readResult = vts.submit(() -> jUringBlocking.read(fd, b1, 14, 0));
            var writeResult = vts.submit(() -> jUringBlocking.write(writeFd, inputBytes, 4));
            var readResult1 = vts.submit(() -> jUringBlocking.read(fd, b2, 5, 0));
            var writeResult1 = vts.submit(() -> jUringBlocking.write(writeFd, inputBytes, 4));
            var readResult2 = vts.submit(() -> jUringBlocking.read(fd, b3, 7, 7));

            assertEquals(13L, readResult.get());
            assertEquals(5L, readResult1.get());
            assertEquals(6L, readResult2.get());

            assertEquals(16L, writeResult.get());
            assertEquals(16L, writeResult1.get());
        }
    }

    @Test
    void readFromRegisteredFile() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             Arena arena = Arena.ofConfined()) {
            int result = jUringBlocking.registerFiles(fd);
            assertEquals(0, result);

            MemorySegment buf = arena.allocate(14);
            long bytesRead = jUringBlocking.read(0, buf, 14, 0);
            assertEquals(13L, bytesRead);
            assertEquals("Hello, World!", getString(buf, bytesRead));
        }
    }

    @Test
    void writeToRegisteredFile() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            int result = jUringBlocking.registerFiles(fd);
            assertEquals(0, result);

            long written = jUringBlocking.write(0, inputBytes, 0);
            assertEquals(inputBytes.length, written);

            String writtenContent = Files.readString(path);
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void updateRegisteredFile() {
        try (FileDescriptor readFd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             FileDescriptor secondReadFd = new FileDescriptor("src/test/resources/second_read_file", READ, 0);
             FileDescriptor thirdReadFd = new FileDescriptor("src/test/resources/third_read_file", READ, 0);
             Arena arena = Arena.ofConfined()) {

            int result = jUringBlocking.registerFiles(readFd, secondReadFd);
            assertEquals(0, result);

            int[] updateFiles = {thirdReadFd.getFd()};
            int updateResult = jUringBlocking.registerFilesUpdate(0, updateFiles);
            assertEquals(1, updateResult);

            MemorySegment buf1 = arena.allocate(20);
            long r1 = jUringBlocking.read(0, buf1, 20, 0);
            assertEquals(18L, r1);
            assertEquals("third file content", getString(buf1, r1));

            MemorySegment buf2 = arena.allocate(20);
            long r2 = jUringBlocking.read(1, buf2, 20, 0);
            assertEquals(19L, r2);
            assertEquals("second file content", getString(buf2, r2));
        }
    }

    @Test
    void prepareReadFixed() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            MemorySegment[] registerResult = jUringBlocking.registerBuffers(30, 2);
            assertEquals(2, registerResult.length);

            long bytesRead = jUringBlocking.readFixed(fd, 13, 0, 0);
            assertEquals(13L, bytesRead);
            assertEquals("Hello, World!", getString(registerResult[0], bytesRead));
        }
    }

    @Test
    void prepareReadFixedWithRegisteredFileAndBuffer() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUringBlocking.registerFiles(fd);

            MemorySegment[] bufferRegisterResult = jUringBlocking.registerBuffers(20, 1);
            assertEquals(1, bufferRegisterResult.length);

            int bufIdx = jUringBlocking.checkOutBuffer();
            assertEquals(0, bufIdx);
            try {
                long bytesRead = jUringBlocking.readFixed(0, 13, 0, bufIdx);
                assertEquals(13L, bytesRead);
                assertEquals("Hello, World!", getString(jUringBlocking.getRegisteredBuffer(bufIdx), bytesRead));
            } finally {
                jUringBlocking.checkInBuffer(bufIdx);
            }
        }
    }

    @Test
    void prepareOpenAndRead() {
        try (Arena arena = Arena.ofConfined()) {
            FileDescriptor fd = jUringBlocking.openFile("src/test/resources/read_file", READ.getValue(), 0);

            MemorySegment buf = arena.allocate(14);
            long bytesRead = jUringBlocking.read(fd, buf, 14, 0);
            assertEquals(13L, bytesRead);
            assertEquals("Hello, World!", getString(buf, bytesRead));

            int closeResult = jUringBlocking.closeFd(fd);
            assertEquals(0, closeResult);
        }
    }

    @Test
    void prepareOpenDirectAndRead() {
        try (FileDescriptor placeholder = new FileDescriptor("src/test/resources/read_file", READ, 0);
             Arena arena = Arena.ofConfined()) {
            jUringBlocking.registerFiles(placeholder);

            int openRes = jUringBlocking.openDirect("src/test/resources/second_read_file", READ, 0, 0);
            assertEquals(0, openRes);

            MemorySegment buf = arena.allocate(20);
            long bytesRead = jUringBlocking.read(0, buf, 20, 0);
            assertEquals(19L, bytesRead);
            assertEquals("second file content", getString(buf, bytesRead));
        }
    }

    @Test
    void prepareCloseFileDescriptor() {
        try (Arena arena = Arena.ofConfined()) {
            FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
            MemorySegment buf = arena.allocate(14);

            long r1 = jUringBlocking.read(fd, buf, 14, 0);
            assertEquals(13L, r1);

            int closeResult = jUringBlocking.closeFd(fd);
            assertEquals(0, closeResult);

            long r2 = jUringBlocking.read(fd, buf, 14, 0);
            assertEquals(-9L, r2);
        }
    }

    @Test
    void prepareCloseDirectFileDescriptor() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             Arena arena = Arena.ofConfined()) {
            jUringBlocking.registerFiles(fd);
            MemorySegment buf = arena.allocate(14);

            long r1 = jUringBlocking.read(0, buf, 14, 0);
            assertEquals(13L, r1);

            int closeResult = jUringBlocking.closeDirect(0);
            assertEquals(0, closeResult);

            long r2 = jUringBlocking.read(0, buf, 14, 0);
            assertEquals(-9L, r2);
        }
    }

    @Test
    void prepareWriteFixed() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            MemorySegment[] registerResult = jUringBlocking.registerBuffers(30, 2);
            assertEquals(2, registerResult.length);

            long written = jUringBlocking.writeFixed(fd, inputBytes, 0, 0);
            assertEquals(inputBytes.length, written);

            String writtenContent = Files.readString(path);
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void prepareWriteFixedWithRegisteredFileAndBuffer() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            jUringBlocking.registerFiles(fd);

            MemorySegment[] bufferRegisterResult = jUringBlocking.registerBuffers(20, 1);
            assertEquals(1, bufferRegisterResult.length);

            long written = jUringBlocking.writeFixed(0, inputBytes, 0, 0);
            assertEquals(inputBytes.length, written);

            String writtenContent = Files.readString(path);
            assertEquals(input, writtenContent);
        }
    }
}
