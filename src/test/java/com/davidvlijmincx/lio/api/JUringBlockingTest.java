package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class JUringBlockingTest {

    JUringBlocking jUringBlocking;

    @BeforeEach
    void setUp() {
        jUringBlocking = new JUringBlocking(10);
    }

    @AfterEach
    void tearDown() {
        jUringBlocking.close();
    }

    @Test
    void readFromFile() {
        BlockingReadResult result = jUringBlocking.prepareRead("src/test/resources/read_file", 14, 0);

        jUringBlocking.submit();

        // make it valid UTF-8
        result.getBuffer().set(JAVA_BYTE, result.getResult(), (byte) 0);

        String string = result.getBuffer().getString(0);
        result.freeBuffer();
        assertEquals("Hello, World!", string);
    }

    @Test
    void multiReadFromFile() {
        BlockingReadResult result = jUringBlocking.prepareRead("src/test/resources/read_file", 14, 0);
        BlockingReadResult result1 = jUringBlocking.prepareRead("src/test/resources/read_file", 5, 0);
        BlockingReadResult result2 = jUringBlocking.prepareRead("src/test/resources/read_file", 7, 7);

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

    @Test
    void readFromFileAtOffset() {
        BlockingReadResult result = jUringBlocking.prepareRead("src/test/resources/read_file", 6, 7);

        jUringBlocking.submit();

        String string = result.getBuffer().getString(0);
        result.freeBuffer();
        assertEquals("World!", string);
    }

    @Test
    void writeToFile() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        BlockingWriteResult result = jUringBlocking.prepareWrite(path.toString(), inputBytes, 0);

        jUringBlocking.submit();

        assertEquals(inputBytes.length, result.getResult());

        String writtenContent = Files.readString(path);
        assertEquals(input, writtenContent);
    }

    @Test
    void writeToFileAtOffset() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Big ".getBytes());

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        BlockingWriteResult result = jUringBlocking.prepareWrite(path.toString(), inputBytes, 4);

        jUringBlocking.submit();

        assertEquals(inputBytes.length, result.getResult());

        String writtenContent = Files.readString(path);
        assertEquals("Big hello, from Java", writtenContent);

    }

    @Test
    void mixedWriteAndReadFromFile() {
        Path writePath = Path.of("src/test/resources/write_file");
        Path readPath = Path.of("src/test/resources/read_file");

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        BlockingReadResult readResult = jUringBlocking.prepareRead(readPath.toString(), 14, 0);
        BlockingWriteResult writeResult = jUringBlocking.prepareWrite(writePath.toString(), inputBytes, 4);
        BlockingReadResult readResult1 = jUringBlocking.prepareRead(readPath.toString(), 5, 0);
        BlockingWriteResult writeResult1 = jUringBlocking.prepareWrite(writePath.toString(), inputBytes, 4);
        BlockingReadResult readResult2 = jUringBlocking.prepareRead(readPath.toString(), 7, 7);

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