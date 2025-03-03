package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class JUringBlockingTest {

    static JUringBlocking jUringBlocking;

    @BeforeAll
    static void setUp() {
        jUringBlocking = new JUringBlocking(10);
    }

    @AfterAll
    static void tearDown() {
        jUringBlocking.close();
    }

    @Test
    void readFromFile() {

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0);) {
            BlockingReadResult result = jUringBlocking.prepareRead(fd, 14, 0);

            jUringBlocking.submit();

            // make it valid UTF-8
            result.getBuffer().set(JAVA_BYTE, result.getResult(), (byte) 0);

            String string = result.getBuffer().getString(0);
            result.freeBuffer();
            assertEquals("Hello, World!", string);
        }
    }

    @Test
    void multiReadFromFile() {

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0);) {

            BlockingReadResult result = jUringBlocking.prepareRead(fd, 14, 0);
            BlockingReadResult result1 = jUringBlocking.prepareRead(fd, 5, 0);
            BlockingReadResult result2 = jUringBlocking.prepareRead(fd, 7, 7);

            jUringBlocking.submit();

            // make it valid UTF-8
            result.getBuffer().set(JAVA_BYTE, result.getResult(), (byte) 0);
            result1.getBuffer().set(JAVA_BYTE, result1.getResult(), (byte) 0);
            result2.getBuffer().set(JAVA_BYTE, result2.getResult(), (byte) 0);

            assertEquals("Hello, World!", result.getBuffer().getString(0));
            assertEquals("Hello", result1.getBuffer().getString(0));
            assertEquals("World!", result2.getBuffer().getString(0));

            result.freeBuffer();
            result1.freeBuffer();
            result2.freeBuffer();
        }
    }

    @Test
    void readFromFileAtOffset() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {

            BlockingReadResult result = jUringBlocking.prepareRead(fd, 6, 7);

            jUringBlocking.submit();

            String string = result.getBuffer().getString(0);
            result.freeBuffer();

            assertEquals("World!", string);

        }
    }

    @Test
    void writeToFile() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content : ".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), Flag.WRITE, 0)) {

            BlockingWriteResult result = jUringBlocking.prepareWrite(fd, inputBytes, 0);

            jUringBlocking.submit();

            assertEquals(inputBytes.length, result.getResult());

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

        try (FileDescriptor fd = new FileDescriptor(path.toString(), Flag.WRITE, 0)) {

            BlockingWriteResult result = jUringBlocking.prepareWrite(fd, inputBytes, 4);

            jUringBlocking.submit();

            assertEquals(inputBytes.length, result.getResult());

            String writtenContent = Files.readString(path);
            assertEquals("Big hello, from Java", writtenContent);

        }
    }

    @Test
    void mixedWriteAndReadFromFile() {
        Path writePath = Path.of("src/test/resources/write_file");
        Path readPath = Path.of("src/test/resources/read_file");

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(readPath.toString(), Flag.READ, 0); FileDescriptor writeFd = new FileDescriptor(writePath.toString(), Flag.WRITE, 0)) {

            BlockingReadResult readResult = jUringBlocking.prepareRead(fd, 14, 0);
            BlockingWriteResult writeResult = jUringBlocking.prepareWrite(writeFd, inputBytes, 4);
            BlockingReadResult readResult1 = jUringBlocking.prepareRead(fd, 5, 0);
            BlockingWriteResult writeResult1 = jUringBlocking.prepareWrite(writeFd, inputBytes, 4);
            BlockingReadResult readResult2 = jUringBlocking.prepareRead(fd, 7, 7);

            jUringBlocking.submit();

            assertEquals(13, readResult.getResult());
            assertEquals(5, readResult1.getResult());
            assertEquals(6, readResult2.getResult());

            assertEquals(16, writeResult.getResult());
            assertEquals(16, writeResult1.getResult());

            readResult.freeBuffer();
            readResult1.freeBuffer();
            readResult2.freeBuffer();

        }
    }
}