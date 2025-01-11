package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class JUringTest {

    JUring jUring;

    @BeforeEach
    void setUp() {
        jUring = new JUring(10);
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
            assertEquals(id, readResult.getId());
            assertEquals(13, readResult.getResult());

            readResult.getBuffer().set(JAVA_BYTE, readResult.getResult(), (byte) 0);
            String string = readResult.getBuffer().getString(0);
            readResult.freeBuffer();
            assertEquals("Hello, World!", string);
        } else {
            fail("Result is not a ReadResult");
        }

    }

    @Test
    void multipleReads() {

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        ids.add(jUring.prepareRead("src/test/resources/read_file", 14, 0));
        ids.add(jUring.prepareRead("src/test/resources/read_file", 14, 0));
        ids.add(jUring.prepareRead("src/test/resources/read_file", 14, 0));

        jUring.submit();

        for (int i = 0; i < ids.size(); i++) {
            Result result = jUring.waitForResult();
            completedIds.add(result.getId());

            if (result instanceof ReadResult readResult) {
                readResult.freeBuffer();
            } else {
                fail("Result is not a ReadResult");
            }
        }

        assertEquals(completedIds.size(), ids.size());
        assertThat(completedIds).containsAll(ids);

    }

    @Test
    void multipleWrites() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        ids.add(jUring.prepareRead("src/test/resources/read_file", 14, 0));
        ids.add(jUring.prepareWrite(path, inputBytes, 0));
        ids.add(jUring.prepareRead("src/test/resources/read_file", 14, 0));
        ids.add(jUring.prepareWrite(path, inputBytes, 0));
        ids.add(jUring.prepareRead("src/test/resources/read_file", 14, 0));
        ids.add(jUring.prepareWrite(path, inputBytes, 0));

        jUring.submit();

        for (int i = 0; i < ids.size(); i++) {
            Result result = jUring.waitForResult();
            completedIds.add(result.getId());

            if (result instanceof ReadResult readResult) {
               readResult.freeBuffer();
            }
        }

        assertEquals(completedIds.size(), ids.size());
        assertThat(completedIds).containsAll(ids);

    }

    @Test
    void mixedReadAndWrite() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        ids.add(jUring.prepareWrite(path, inputBytes, 0));
        ids.add(jUring.prepareWrite(path, inputBytes, 0));
        ids.add(jUring.prepareWrite(path, inputBytes, 0));

        jUring.submit();

        for (int i = 0; i < ids.size(); i++) {
            Result result = jUring.waitForResult();
            completedIds.add(result.getId());
        }

        assertEquals(completedIds.size(), ids.size());
        assertThat(completedIds).containsAll(ids);
    }

    @Test
    void readFromFileAtOffset() {
        long id = jUring.prepareRead("src/test/resources/read_file", 6, 7);
        jUring.submit();
        Result result = jUring.waitForResult();

        if (result instanceof ReadResult readResult) {
            assertEquals(id, readResult.getId());

            String string = readResult.getBuffer().getString(0);
            readResult.freeBuffer();
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
            assertEquals(id, writeResult.getId());
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
            assertEquals(id, writeResult.getId());
            assertEquals(inputBytes.length, writeResult.getResult());
        } else {
            fail("Result is not a AsyncWriteResult");
        }

        String writtenContent = Files.readString(Path.of(path));
        assertEquals("Big hello, from Java", writtenContent);
    }


}