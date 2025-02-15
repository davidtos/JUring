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
        FileDescriptor fd = jUring.openFile("src/test/resources/read_file");
        long id = jUring.prepareRead(fd, 14, 0);
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

        jUring.closeFile(fd);
    }

    @Test
    void multipleReads() {

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        FileDescriptor fd = jUring.openFile("src/test/resources/read_file");

        ids.add(jUring.prepareRead(fd, 14, 0));
        ids.add(jUring.prepareRead(fd, 14, 0));
        ids.add(jUring.prepareRead(fd, 14, 0));

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

        jUring.closeFile(fd);

    }

    @Test
    void multipleWrites() throws IOException {
        Files.write(Path.of("src/test/resources/write_file"), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        FileDescriptor fd = jUring.openFile("src/test/resources/read_file");
        FileDescriptor writeFd = jUring.openFile("src/test/resources/write_file");

        ids.add(jUring.prepareRead(fd, 14, 0));
        ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));
        ids.add(jUring.prepareRead(fd, 14, 0));
        ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));
        ids.add(jUring.prepareRead(fd, 14, 0));
        ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));

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

        jUring.closeFile(fd);
    }

    @Test
    void mixedReadAndWrite() throws IOException {
        Files.write(Path.of("src/test/resources/write_file"), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        FileDescriptor fd = jUring.openFile("src/test/resources/write_file");

        ids.add(jUring.prepareWrite(fd, inputBytes, 0));
        ids.add(jUring.prepareWrite(fd, inputBytes, 0));
        ids.add(jUring.prepareWrite(fd, inputBytes, 0));

        jUring.submit();

        for (int i = 0; i < ids.size(); i++) {
            Result result = jUring.waitForResult();
            completedIds.add(result.getId());
        }

        assertEquals(completedIds.size(), ids.size());
        assertThat(completedIds).containsAll(ids);

        jUring.closeFile(fd);
    }

    @Test
    void readFromFileAtOffset() {
        FileDescriptor fd = jUring.openFile("src/test/resources/read_file");
        long id = jUring.prepareRead(fd, 6, 7);
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
        Files.write(Path.of(path), "Clean content : ".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        FileDescriptor fd = jUring.openFile("src/test/resources/write_file");
        long id = jUring.prepareWrite(fd, inputBytes, 0);

        jUring.submit();
        Result result = jUring.waitForResult();

        if (result instanceof AsyncWriteResult writeResult) {
            assertEquals(id, writeResult.getId());
            assertEquals(inputBytes.length, writeResult.getResult());
        } else {
            fail("Result is not a AsyncWriteResult");
        }

        String writtenContent = Files.readString(Path.of(path));
        assertEquals("Clean content : " + input, writtenContent);

        jUring.closeFile(fd);
    }

    @Test
    void writeToFileAtOffset() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Big ".getBytes());

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        FileDescriptor fd = jUring.openFile(path);
        long id = jUring.prepareWrite(fd, inputBytes, 4);

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

        jUring.closeFile(fd);
    }
}