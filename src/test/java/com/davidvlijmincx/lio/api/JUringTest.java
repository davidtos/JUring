package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import java.nio.file.Files;


import static org.junit.jupiter.api.Assertions.*;

class JUringTest {

    JUring jUring;

    @BeforeEach
    void setUp() {
        jUring = new JUring(10, false);
    }


    @AfterEach
    void tearDown() {
        jUring.close();
    }

    @Test
    void readFromFile() {
        long id = jUring.prepareRead("src/test/resources/read_file", 14, 0);
        jUring.submit();
        Result result = jUring.waitForResult();

        if (result instanceof ReadResult readResult) {
            assertEquals(id, readResult.getId(), "id mismatch between prepareRead and result");

            String string = readResult.getBuffer().getString(0);
            jUring.freeReadBuffer(readResult.getBuffer());
            assertEquals("Hello, World!", string);
        } else {
            fail("Result is not a ReadResult");
        }

    }

    @Test
    void readFromFileAtOffset() {
        long id = jUring.prepareRead("src/test/resources/read_file", 6, 7);
        jUring.submit();
        Result result = jUring.waitForResult();

        if (result instanceof ReadResult readResult) {
            assertEquals(id, readResult.getId(), "id mismatch between prepareRead and result");

            String string = readResult.getBuffer().getString(0);
            jUring.freeReadBuffer(readResult.getBuffer());
            assertEquals("World!", string);
        } else {
            fail("Result is not a ReadResult");
        }

    }

    @Test
    void writeToFile() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        long id = jUring.prepareWrite(path, inputBytes, 0);

        jUring.submit();
        Result result = jUring.waitForResult();

        if (result instanceof AsyncWriteResult writeResult) {
            assertEquals(id, writeResult.getId(), "id mismatch between prepareRead and result");
            assertEquals(inputBytes.length, writeResult.getResult());
        } else {
            fail("Result is not a AsyncWriteResult");
        }

        String writtenContent = Files.readString(Path.of(path));
        assertEquals(input, writtenContent);
    }

    @Test
    void writeToFileAtOffset() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Big ".getBytes());

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        long id = jUring.prepareWrite(path, inputBytes, 4);

        jUring.submit();
        Result result = jUring.waitForResult();

        if (result instanceof AsyncWriteResult writeResult) {
            assertEquals(id, writeResult.getId(), "id mismatch between prepareRead and result");
            assertEquals(inputBytes.length, writeResult.getResult());
        } else {
            fail("Result is not a AsyncWriteResult");
        }

        String writtenContent = Files.readString(Path.of(path));
        assertEquals("Big hello, from Java", writtenContent);
    }


}