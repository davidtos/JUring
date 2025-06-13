package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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
            Future<ReadResult> future = jUringBlocking.prepareRead(fd, 14, 0);

            jUringBlocking.submit();

            var result = future.get();

            // make it valid UTF-8
            result.getBuffer().set(JAVA_BYTE, result.getResult(), (byte) 0);

            String string = result.getBuffer().getString(0);
            result.freeBuffer();
            assertEquals("Hello, World!", string);
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void multiReadFromFile() throws ExecutionException, InterruptedException {

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0);) {

            var future1 = jUringBlocking.prepareRead(fd, 14, 0);
            var future2 = jUringBlocking.prepareRead(fd, 5, 0);
            var future3 = jUringBlocking.prepareRead(fd, 7, 7);

            jUringBlocking.submit();

            ReadResult result = future1.get();
            ReadResult result1 = future2.get();
            ReadResult result2 = future3.get();

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
    void readFromFileAtOffset() throws ExecutionException, InterruptedException {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {

            var result = jUringBlocking.prepareRead(fd, 6, 7);

            jUringBlocking.submit();

            String string = result.get().getBuffer().getString(0);
            result.get().freeBuffer();

            assertEquals("World!", string);

        }
    }

    @Test
    void writeToFile() throws IOException, ExecutionException, InterruptedException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content : ".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), Flag.WRITE, 0)) {

            var future = jUringBlocking.prepareWrite(fd, inputBytes, 0);

            jUringBlocking.submit();

            assertEquals(inputBytes.length, future.get().getResult());

            String writtenContent = Files.readString(path);
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void writeToFileAtOffset() throws IOException, ExecutionException, InterruptedException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Big ".getBytes());

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), Flag.WRITE, 0)) {

            var result = jUringBlocking.prepareWrite(fd, inputBytes, 4);

            jUringBlocking.submit();

            assertEquals(inputBytes.length, result.get().getResult());

            String writtenContent = Files.readString(path);
            assertEquals("Big hello, from Java", writtenContent);

        }
    }

    @Test
    void mixedWriteAndReadFromFile() throws ExecutionException, InterruptedException {
        Path writePath = Path.of("src/test/resources/write_file");
        Path readPath = Path.of("src/test/resources/read_file");

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(readPath.toString(), Flag.READ, 0); FileDescriptor writeFd = new FileDescriptor(writePath.toString(), Flag.WRITE, 0)) {

            var readResult = jUringBlocking.prepareRead(fd, 14, 0);
            var writeResult = jUringBlocking.prepareWrite(writeFd, inputBytes, 4);
            var readResult1 = jUringBlocking.prepareRead(fd, 5, 0);
            var writeResult1 = jUringBlocking.prepareWrite(writeFd, inputBytes, 4);
            var readResult2 = jUringBlocking.prepareRead(fd, 7, 7);

            jUringBlocking.submit();

            assertEquals(13, readResult.get().getResult());
            assertEquals(5, readResult1.get().getResult());
            assertEquals(6, readResult2.get().getResult());

            assertEquals(16, writeResult.get().getResult());
            assertEquals(16, writeResult1.get().getResult());

            readResult.get().freeBuffer();
            readResult1.get().freeBuffer();
            readResult2.get().freeBuffer();

        }
    }
}