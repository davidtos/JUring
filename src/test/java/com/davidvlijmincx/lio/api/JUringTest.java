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
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {
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

        }
    }

    @Test
    void multipleReads() {

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {

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

        }

    }

    @Test
    void mixedReadAndWrite() throws IOException {
        Files.write(Path.of("src/test/resources/write_file"), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0);
                FileDescriptor writeFd = new FileDescriptor("src/test/resources/write_file", Flag.WRITE, 0)) {


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

        }
    }

    @Test
    void multipleWrites() throws IOException {
        Files.write(Path.of("src/test/resources/write_file"), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        try(FileDescriptor fd = new FileDescriptor("src/test/resources/write_file", Flag.WRITE, 0)) {

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

        }
    }

    @Test
    void readFromFileAtOffset() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {
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

    }

    @Test
    void writeToFile() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content : ".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try(FileDescriptor fd = new FileDescriptor(path, Flag.WRITE, 0)) {
            long id = jUring.prepareWrite(fd, inputBytes, 0);

            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof WriteResult writeResult) {
                assertEquals(id, writeResult.getId());
                assertEquals(inputBytes.length, writeResult.getResult());
            } else {
                fail("Result is not a AsyncWriteResult");
            }

            String writtenContent = Files.readString(Path.of(path));
            assertEquals(input, writtenContent);

        }
    }

    @Test
    void writeToFileAtOffset() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Big ".getBytes());

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        try(FileDescriptor fd = new FileDescriptor(path, Flag.WRITE, 0)) {
            long id = jUring.prepareWrite(fd, inputBytes, 4);

            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof WriteResult writeResult) {
                assertEquals(id, writeResult.getId());
                assertEquals(inputBytes.length, writeResult.getResult());
            } else {
                fail("Result is not a AsyncWriteResult");
            }

            String writtenContent = Files.readString(Path.of(path));
            assertEquals("Big hello, from Java", writtenContent);

        }
    }

    @Test
    void readFromRegisteredFile() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {
            int[] fileDescriptors = {fd.getFd()};
            int result = jUring.registerFiles(fileDescriptors);
            assertEquals(0, result);

            long id = jUring.prepareReadFixed(0, 14, 0);
            jUring.submit();
            Result readResult = jUring.waitForResult();

            if (readResult instanceof ReadResult read) {
                assertEquals(id, read.getId());
                assertEquals(13, read.getResult());

                read.getBuffer().set(JAVA_BYTE, read.getResult(), (byte) 0);
                String string = read.getBuffer().getString(0);
                read.freeBuffer();
                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }
        }
    }
}