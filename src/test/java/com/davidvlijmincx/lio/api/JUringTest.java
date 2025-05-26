package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

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
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {
            long id = jUring.prepareRead(fd, 14, 0);
            jUring.submit();
            IoResult result = jUring.waitForResult();

            if (OperationType.READ.equals(result.type())) {
                assertEquals(id, result.id());
                assertEquals(13, result.bytesTransferred());

                result.readBuffer().set(JAVA_BYTE, result.bytesTransferred(), (byte) 0);
                String string = result.readBuffer().getString(0);
                result.freeBuffer();
                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }

        }
    }

    @Test
    void fixedRead() {
        try (JUring jUringFixed = new JUring(10, 4096, 10);
             FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {

            long id = jUringFixed.prepareReadFixed(fd, 0, 0);
            jUringFixed.submit();
            IoResult result = jUringFixed.waitForResult();

            if (OperationType.READ.equals(result.type())) {
                assertEquals(id, result.id());
                assertEquals(13, result.bytesTransferred());

                result.readBuffer().set(JAVA_BYTE, result.bytesTransferred(), (byte) 0);
                String string = result.readBuffer().getString(0);
                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }

        }
    }

    @Test
    void prepareOpenAndClose() {
        try (JUring juring = new JUring(10, 4096, 10); var arena = Arena.ofConfined()) {


            juring.prepareOpen(arena.allocateFrom("src/test/resources/read_file"), Flag.READ, 0);
            juring.submit();
            IoResult openResult = juring.waitForResult();

            int fd = openResult.returnValue();

            juring.prepareRead(fd,4096, 0);
            juring.prepareClose(fd);

            juring.submit();

            IoResult readResult =  juring.waitForResult();
            IoResult closeResult =  juring.waitForResult();

            assertEquals(13, readResult.bytesTransferred());

            readResult.readBuffer().set(JAVA_BYTE, readResult.bytesTransferred(), (byte) 0);
            String string = readResult.readBuffer().getString(0);
            assertEquals("Hello, World!", string);

            readResult.freeBuffer();


            assertEquals(0, closeResult.returnValue());
        }
    }


    @Test
    void CompletableFutureTest() throws Throwable {
        try (JUring juring = new JUring(10, 4096, 10); var arena = Arena.ofConfined()) {

            CompletableFuture<IoResult> open = juring.open(arena.allocateFrom("src/test/resources/read_file"), Flag.READ, 0);
            juring.submit();

            Thread.sleep(100);
            juring.peekCompleteForBatchResult(10);

            int fd = open.get().returnValue();

            CompletableFuture<IoResult> read = juring.read(fd, 13, 0);
            CompletableFuture<IoResult> close = juring.close(fd);

            juring.submit();
            juring.peekCompleteForBatchResult(10);

            IoResult readResult = read.get();

            assertEquals(13, readResult.bytesTransferred());

            readResult.readBuffer().set(JAVA_BYTE, readResult.bytesTransferred(), (byte) 0);
            String string = readResult.readBuffer().getString(0);
            assertEquals("Hello, World!", string);

            readResult.freeBuffer();

            assertEquals(0, close.get().returnValue());
        }
    }


    @Test
    void multipleReads() {

        List<Long> ids = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {

            ids.add(jUring.prepareRead(fd, 14, 0));
            ids.add(jUring.prepareRead(fd, 14, 0));
            ids.add(jUring.prepareRead(fd, 14, 0));

            jUring.submit();

            for (int i = 0; i < ids.size(); i++) {
                IoResult result = jUring.waitForResult();
                completedIds.add(result.id());

                if (OperationType.READ.equals(result.type())) {
                    result.freeBuffer();
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

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0);
             FileDescriptor writeFd = new FileDescriptor("src/test/resources/write_file", Flag.WRITE, 0)) {


            ids.add(jUring.prepareRead(fd, 14, 0));
            ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));
            ids.add(jUring.prepareRead(fd, 14, 0));
            ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));
            ids.add(jUring.prepareRead(fd, 14, 0));
            ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));

            jUring.submit();

            for (int i = 0; i < ids.size(); i++) {
                IoResult result = jUring.waitForResult();
                completedIds.add(result.id());

                result.freeBuffer();
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

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/write_file", Flag.WRITE, 0)) {

            ids.add(jUring.prepareWrite(fd, inputBytes, 0));
            ids.add(jUring.prepareWrite(fd, inputBytes, 0));
            ids.add(jUring.prepareWrite(fd, inputBytes, 0));

            jUring.submit();

            for (int i = 0; i < ids.size(); i++) {
                IoResult result = jUring.waitForResult();
                completedIds.add(result.id());
                result.freeBuffer();
            }

            assertEquals(completedIds.size(), ids.size());
            assertThat(completedIds).containsAll(ids);

        }
    }

    @Test
    void readFromFileAtOffset() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {
            long id = jUring.prepareRead(fd, 6, 7);
            jUring.submit();
            IoResult result = jUring.waitForResult();

            if (OperationType.READ.equals(result.type())) {
                assertEquals(id, result.id());

                String string = result.readBuffer().getString(0);
                result.freeBuffer();
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

        try (FileDescriptor fd = new FileDescriptor(path, Flag.WRITE, 0)) {
            long id = jUring.prepareWrite(fd, inputBytes, 0);

            jUring.submit();
            IoResult result = jUring.waitForResult();

            if (OperationType.WRITE.equals(result.type())) {
                assertEquals(id, result.id());
                assertEquals(inputBytes.length, result.bytesTransferred());
                result.freeBuffer();
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

        try (FileDescriptor fd = new FileDescriptor(path, Flag.WRITE, 0)) {
            long id = jUring.prepareWrite(fd, inputBytes, 4);

            jUring.submit();
            IoResult result = jUring.waitForResult();

            if (OperationType.WRITE.equals(result.type())) {
                assertEquals(id, result.id());
                assertEquals(inputBytes.length, result.bytesTransferred());
                result.freeBuffer();
            } else {
                fail("Result is not a AsyncWriteResult");
            }

            String writtenContent = Files.readString(Path.of(path));
            assertEquals("Big hello, from Java", writtenContent);

        }
    }
}