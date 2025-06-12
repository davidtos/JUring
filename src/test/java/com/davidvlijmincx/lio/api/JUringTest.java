package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
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

            long id = jUring.prepareRead(0, 14, 0);
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

    @Test
    void writeToRegisteredFile() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try(FileDescriptor fd = new FileDescriptor(path, Flag.WRITE, 0)) {
            int[] fileDescriptors = {fd.getFd()};
            int result = jUring.registerFiles(fileDescriptors);
            assertEquals(0, result);

            long id = jUring.prepareWrite(0, inputBytes, 0);
            jUring.submit();
            Result writeResult = jUring.waitForResult();

            if (writeResult instanceof WriteResult write) {
                assertEquals(id, write.getId());
                assertEquals(inputBytes.length, write.getResult());
            } else {
                fail("Result is not a WriteResult");
            }

            String writtenContent = Files.readString(Path.of(path));
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void updateRegisteredFile() {
        try(FileDescriptor readFd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0);
            FileDescriptor secondReadFd = new FileDescriptor("src/test/resources/second_read_file", Flag.READ, 0);
            FileDescriptor thirdReadFd = new FileDescriptor("src/test/resources/third_read_file", Flag.READ, 0)) {
            
            // Register initial files: read_file and second_read_file
            int[] initialFiles = {readFd.getFd(), secondReadFd.getFd()};
            int result = jUring.registerFiles(initialFiles);
            assertEquals(0, result);

            // Update index 1 to point to third_read_file
            int[] updateFiles = {thirdReadFd.getFd()};
            int updateResult = jUring.registerFilesUpdate(0, updateFiles);
            assertEquals(1, updateResult);

            // Test reading from index 1 (now third_read_file) after update
            long id2 = jUring.prepareRead(0, 20, 0);
            jUring.submit();
            Result readResult2 = jUring.waitForResult();

            if (readResult2 instanceof ReadResult read) {
                assertEquals(id2, read.getId());
                read.getBuffer().set(JAVA_BYTE, (int)read.getResult(), (byte) 0);
                String content = read.getBuffer().getString(0);
                read.freeBuffer();
                assertEquals("third file content", content);
            } else {
                fail("Result is not a ReadResult");
            }

            // Test reading from index 1 (second_read_file) before update
            long id1 = jUring.prepareRead(1, 20, 0);
            jUring.submit();
            Result readResult1 = jUring.waitForResult();

            if (readResult1 instanceof ReadResult read) {
                assertEquals(id1, read.getId());
                read.getBuffer().set(JAVA_BYTE, (int)read.getResult(), (byte) 0);
                String content = read.getBuffer().getString(0);
                read.freeBuffer();
                assertEquals("second file content", content);
            } else {
                fail("Result is not a ReadResult");
            }
        }
    }

    @Test
    void prepareReadFixedWithRegisteredBuffer() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {
            MemorySegment[] registerResult = jUring.registerBuffers(30, 2);
            assertEquals(2, registerResult.length);
            
            long id = jUring.prepareReadFixed(fd, 13, 0, 0);
            jUring.submit();
            Result result = jUring.waitForResult();
            
            if (result instanceof ReadResult readResult) {
                assertEquals(id, readResult.getId());
                assertEquals(13, readResult.getResult());
                
                readResult.getBuffer().set(JAVA_BYTE, readResult.getResult(), (byte) 0);
                String string = readResult.getBuffer().getString(0);
                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }
        }
    }

    @Test
    void prepareReadFixedWithRegisteredFileAndBuffer() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", Flag.READ, 0)) {
            int[] fileDescriptors = {fd.getFd()};
            jUring.registerFiles(fileDescriptors);

            MemorySegment[] bufferRegisterResult = jUring.registerBuffers(20, 1);
            assertEquals(1, bufferRegisterResult.length);
            
            long id = jUring.prepareReadFixed(0, 13, 0, 0);
            jUring.submit();
            Result result = jUring.waitForResult();
            
            if (result instanceof ReadResult readResult) {
                assertEquals(id, readResult.getId());
                assertEquals(13, readResult.getResult());
                
                readResult.getBuffer().set(JAVA_BYTE, readResult.getResult(), (byte) 0);
                String string = readResult.getBuffer().getString(0);
                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }

        }
    }
}