package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class JLibUringBlockingTest {

    JLibUringBlocking jLibUringBlocking;

    @BeforeEach
    void setUp() {
        jLibUringBlocking = new JLibUringBlocking(10, false);
    }

    @AfterEach
    void tearDown() {
        jLibUringBlocking.close();
    }

    @Test
    void readFromFile() {
        BlockingReadResult result = jLibUringBlocking.prepareRead("src/test/resources/read_file", 14, 0);

        jLibUringBlocking.submit();

        // make it valid UTF-8
        result.getBuffer().set(JAVA_BYTE, result.getResult(), (byte) 0);

        String string = result.getBuffer().getString(0);
        jLibUringBlocking.freeReadBuffer(result.getBuffer());
        assertEquals("Hello, World!", string);
    }

    @Test
    void readFromFileAtOffset() {
        BlockingReadResult result = jLibUringBlocking.prepareRead("src/test/resources/read_file", 6, 7);

        jLibUringBlocking.submit();

        String string = result.getBuffer().getString(0);
        jLibUringBlocking.freeReadBuffer(result.getBuffer());
        assertEquals("World!", string);
    }

    @Test
    void writeToFile() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        BlockingWriteResult result = jLibUringBlocking.prepareWrite(path.toString(), inputBytes, 0);

        jLibUringBlocking.submit();

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

        BlockingWriteResult result = jLibUringBlocking.prepareWrite(path.toString(), inputBytes, 4);

        jLibUringBlocking.submit();

        assertEquals(inputBytes.length, result.getResult());

        String writtenContent = Files.readString(path);
        assertEquals("Big hello, from Java", writtenContent);

    }
}