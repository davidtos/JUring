package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class JUringTest {

    JUring jUring;

    @BeforeEach
    void setUp() {
        jUring = new JUring(10, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        jUring.close();
    }

    @Test
    void sharedWorkerRingCreation(){
        var mainRing = new JUring(10, IORING_SETUP_SINGLE_ISSUER);

        var sharedRing = mainRing.getSharedWorkerRing(10,IORING_SETUP_SINGLE_ISSUER);

        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            long id = sharedRing.prepareRead(fd, 14, 0);
            sharedRing.submit();
            Result result = sharedRing.waitForResult();

            if (result instanceof ReadResult readResult) {
                assertEquals(id, readResult.id());
                assertEquals(13, readResult.result());

                readResult.buffer().set(JAVA_BYTE, readResult.result(), (byte) 0);
                String string = readResult.buffer().getString(0);
                readResult.freeBuffer();
                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }

        }

        sharedRing.close();
        mainRing.close();
    }

    @Test
    void readFromFile() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            long id = jUring.prepareRead(fd, 14, 0);
            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof ReadResult readResult) {
                assertEquals(id, readResult.id());
                assertEquals(13, readResult.result());

                readResult.buffer().set(JAVA_BYTE, readResult.result(), (byte) 0);
                String string = readResult.buffer().getString(0);
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

        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {

            ids.add(jUring.prepareRead(fd, 14, 0));
            ids.add(jUring.prepareRead(fd, 14, 0));
            ids.add(jUring.prepareRead(fd, 14, 0));

            jUring.submit();

            for (int i = 0; i < ids.size(); i++) {
                Result result = jUring.waitForResult();
                completedIds.add(Long.valueOf(result.id()));

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

        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
            FileDescriptor writeFd = new FileDescriptor("src/test/resources/write_file", WRITE, 0)) {


        ids.add(jUring.prepareRead(fd, 14, 0));
        ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));
        ids.add(jUring.prepareRead(fd, 14, 0));
        ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));
        ids.add(jUring.prepareRead(fd, 14, 0));
        ids.add(jUring.prepareWrite(writeFd, inputBytes, 0));

        jUring.submit();

        for (int i = 0; i < ids.size(); i++) {
            Result result = jUring.waitForResult();
            completedIds.add(result.id());

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

        try(FileDescriptor fd = new FileDescriptor("src/test/resources/write_file", WRITE, 0)) {

            ids.add(jUring.prepareWrite(fd, inputBytes, 0));
            ids.add(jUring.prepareWrite(fd, inputBytes, 0));
            ids.add(jUring.prepareWrite(fd, inputBytes, 0));

            jUring.submit();

            for (int i = 0; i < ids.size(); i++) {
                Result result = jUring.waitForResult();
                completedIds.add(result.id());
            }

            assertEquals(completedIds.size(), ids.size());
            assertThat(completedIds).containsAll(ids);

        }
    }

    @Test
    void readFromFileAtOffset() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            long id = jUring.prepareRead(fd, 6, 7);
            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof ReadResult readResult) {
                assertEquals(id, readResult.id());

                String string = readResult.buffer().getString(0);
                readResult.freeBuffer();
                assertEquals("World!", string);
            } else {
                fail("Result is not a ReadResult");
            }

        }

    }

    @Test
    void writeToFile() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content : ".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try(FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {

            ByteBuffer bb = ByteBuffer.allocateDirect(inputBytes.length);
            bb.put(inputBytes);
            bb.flip();

            long id = jUring.prepareWrite(fd, MemorySegment.ofBuffer(bb), 0);

            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof WriteResult(long wId, long wResult)) {
                assertEquals(id, wId);
                assertEquals(inputBytes.length, wResult);
            } else {
                fail("Result is not a AsyncWriteResult");
            }

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

        try(FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            long id = jUring.prepareWrite(fd, inputBytes, 4);

            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof WriteResult(long wId, long wResult)) {
                assertEquals(id, wId);
                assertEquals(inputBytes.length, wResult);
            } else {
                fail("Result is not a AsyncWriteResult");
            }

            String writtenContent = Files.readString(path);
            assertEquals("Big hello, from Java", writtenContent);

        }
    }

    @Test
    void readFromRegisteredFile() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            int result = jUring.registerFiles(fd);
            assertEquals(0, result);

            long id = jUring.prepareRead(0, 14, 0);
            jUring.submit();
            Result readResult = jUring.waitForResult();

            if (readResult instanceof ReadResult read) {
                try(read){
                    assertEquals(id, read.id());
                    assertEquals(13, read.result());

                    read.buffer().set(JAVA_BYTE, read.result(), (byte) 0);
                    String string = read.buffer().getString(0);
                    assertEquals("Hello, World!", string);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
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

        try(FileDescriptor fd = new FileDescriptor(path, WRITE, 0)) {
            int result = jUring.registerFiles(fd);
            assertEquals(0, result);

            long id = jUring.prepareWrite(0, inputBytes, 0);
            jUring.submit();
            Result writeResult = jUring.waitForResult();

            if (writeResult instanceof WriteResult(long wId, long wResult)) {
                assertEquals(id, wId);
                assertEquals(inputBytes.length, wResult);
            } else {
                fail("Result is not a WriteResult");
            }

            String writtenContent = Files.readString(Path.of(path));
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void updateRegisteredFile() {
        try(FileDescriptor readFd = new FileDescriptor("src/test/resources/read_file", READ, 0);
            FileDescriptor secondReadFd = new FileDescriptor("src/test/resources/second_read_file", READ, 0);
            FileDescriptor thirdReadFd = new FileDescriptor("src/test/resources/third_read_file", READ, 0)) {
            
            // Register initial files: read_file and second_read_file
            int result = jUring.registerFiles(readFd, secondReadFd);
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
                assertEquals(id2, read.id());
                read.buffer().set(JAVA_BYTE, (int)read.result(), (byte) 0);
                String content = read.buffer().getString(0);
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
                assertEquals(id1, read.id());
                read.buffer().set(JAVA_BYTE, (int)read.result(), (byte) 0);
                String content = read.buffer().getString(0);
                read.freeBuffer();
                assertEquals("second file content", content);
            } else {
                fail("Result is not a ReadResult");
            }
        }
    }

    @Test
    void prepareReadFixedWithRegisteredBuffer() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            MemorySegment[] registerResult = jUring.registerBuffers(30, 2);
            assertEquals(2, registerResult.length);
            
            long id = jUring.prepareReadFixed(fd, 13, 0, 0);
            jUring.submit();
            Result result = jUring.waitForResult();
            
            if (result instanceof ReadResult(long rId, MemorySegment buffer, long rResult)) {
                assertEquals(id, rId);
                assertEquals(13, rResult);
                
                buffer.set(JAVA_BYTE, rResult, (byte) 0);
                String string = buffer.getString(0);
                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }
        }
    }

    @Test
    void prepareReadFixedWithRegisteredFileAndBuffer() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUring.registerFiles(fd);

            MemorySegment[] bufferRegisterResult = jUring.registerBuffers(20, 1);
            assertEquals(1, bufferRegisterResult.length);
            
            long id = jUring.prepareReadFixed(0, 13, 0, 0);
            jUring.submit();
            Result result = jUring.waitForResult();
            
            if (result instanceof ReadResult(long rId, MemorySegment buffer, long rResult)) {
                assertEquals(id, rId);
                assertEquals(13, rResult);
                
                buffer.set(JAVA_BYTE, rResult, (byte) 0);
                String string = buffer.getString(0);
                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }

        }
    }

    @Test
    void prepareOpenAndRead() {
        long openId = jUring.prepareOpen("src/test/resources/read_file", READ.getValue(), 0);
        jUring.submit();
        Result openResult = jUring.waitForResult();

        if (openResult instanceof OpenResult result) {
            assertEquals(openId, result.id());
            FileDescriptor fd = result.fileDescriptor();

            long readId = jUring.prepareRead(fd, 14, 0);
            jUring.submit();
            Result readResult = jUring.waitForResult();

            if (readResult instanceof ReadResult read) {
                assertEquals(readId, read.id());
                assertEquals(13, read.result());

                read.buffer().set(JAVA_BYTE, read.result(), (byte) 0);
                String content = read.buffer().getString(0);
                read.freeBuffer();
                assertEquals("Hello, World!", content);

                long closeId = jUring.prepareClose(fd);
                jUring.submit();
                Result closeResult = jUring.waitForResult();

                if (closeResult instanceof CloseResult(long id, int wResult)) {
                    assertEquals(closeId, id);
                    assertEquals(0, wResult);
                } else {
                    fail("Close result is not a CloseResult");
                }
            } else {
                fail("Read result is not a ReadResult");
            }
        } else {
            fail("Open result is not an OpenResult");
        }
    }

    @Test
    void prepareOpenDirectAndRead() {
        try(FileDescriptor placeholder = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUring.registerFiles(placeholder);

            long openId = jUring.prepareOpenDirect("src/test/resources/second_read_file", READ.getValue(), 0, 0);
            jUring.submit();
            Result openResult = jUring.waitForResult();

            if (openResult instanceof OpenResult open) {
                assertEquals(openId, open.id());

                long readId = jUring.prepareRead(0, 20, 0);
                jUring.submit();
                Result readResult = jUring.waitForResult();

                if (readResult instanceof ReadResult read) {
                    assertEquals(readId, read.id());
                    assertEquals(19, read.result());

                    read.buffer().set(JAVA_BYTE, read.result(), (byte) 0);
                    String content = read.buffer().getString(0);
                    read.freeBuffer();
                    assertEquals("second file content", content);
                } else {
                    fail("Read result is not a ReadResult");
                }
            } else {
                fail("Open direct result is not an OpenResult");
            }
        }
    }

    @Test
    void prepareCloseFileDescriptor() {
        FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
        
        // First, verify we can read from the file
        long readId1 = jUring.prepareRead(fd, 14, 0);
        jUring.submit();
        Result readResult1 = jUring.waitForResult();
        
        if (readResult1 instanceof ReadResult read1) {
            assertEquals(13, read1.result());
            read1.freeBuffer();
        } else {
            fail("Initial read failed");
        }
        
        // Now close the file descriptor
        long closeId = jUring.prepareClose(fd);
        jUring.submit();
        Result closeResult = jUring.waitForResult();

        if (closeResult instanceof CloseResult close) {
            assertEquals(closeId, close.id());
            assertEquals(0, close.result());
            
            // Verify the file descriptor is actually closed by trying to read from it
            // This should fail with a bad file descriptor error
            long readId2 = jUring.prepareRead(fd, 14, 0);
            jUring.submit();
            Result readResult2 = jUring.waitForResult();
            
            if (readResult2 instanceof ReadResult read2) {
                // Should get EBADF (Bad file descriptor) error, which is -9
                assertEquals(-9, read2.result());
                read2.freeBuffer();
            } else {
                fail("Expected ReadResult after attempting to read from closed fd");
            }
        } else {
            fail("Close result is not a CloseResult");
        }
    }

    @Test
    void prepareCloseDirectFileDescriptor() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUring.registerFiles(fd);
            
            // First, verify we can read from the registered file
            jUring.prepareRead(0, 14, 0);
            jUring.submit();
            Result readResult1 = jUring.waitForResult();
            
            if (readResult1 instanceof ReadResult read1) {
                assertEquals(13, read1.result());
                read1.freeBuffer();
            } else {
                fail("Initial read from registered file failed");
            }
            
            // Now close the registered file using direct close
            long closeId = jUring.prepareCloseDirect(0);
            jUring.submit();
            Result closeResult = jUring.waitForResult();

            if (closeResult instanceof CloseResult(long id, int result)) {
                assertEquals(closeId, id);
                assertEquals(0, result);
                
                // Verify the registered file descriptor is actually closed by trying to read from it
                // This should fail with a bad file descriptor error
                jUring.prepareRead(0, 14, 0);
                jUring.submit();
                Result readResult2 = jUring.waitForResult();
                
                if (readResult2 instanceof ReadResult read2) {
                    // Should get EBADF (Bad file descriptor) error, which is -9
                    assertEquals(-9, read2.result());
                    read2.freeBuffer();
                } else {
                    fail("Expected ReadResult after attempting to read from closed registered fd");
                }
            } else {
                fail("Close direct result is not a CloseResult");
            }
        }
    }

    @Test
    void prepareWriteFixedWithRegisteredBuffer() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try(FileDescriptor fd = new FileDescriptor(path, WRITE, 0)) {
            MemorySegment[] registerResult = jUring.registerBuffers(30, 2);
            assertEquals(2, registerResult.length);
            
            long id = jUring.prepareWriteFixed(fd, inputBytes, 0, 0);
            jUring.submit();
            Result writeResult = jUring.waitForResult();
            
            if (writeResult instanceof WriteResult(long wId, long result)) {
                assertEquals(id, wId);
                assertEquals(inputBytes.length, result);
            } else {
                fail("Result is not a WriteResult");
            }

            String writtenContent = Files.readString(Path.of(path));
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void prepareWriteFixedWithRegisteredFileAndBuffer() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try(FileDescriptor fd = new FileDescriptor(path, WRITE, 0)) {
            jUring.registerFiles(fd);

            MemorySegment[] bufferRegisterResult = jUring.registerBuffers(20, 1);
            assertEquals(1, bufferRegisterResult.length);
            
            long id = jUring.prepareWriteFixed(0, inputBytes, 0, 0);
            jUring.submit();
            Result result = jUring.waitForResult();
            
            if (result instanceof WriteResult(long wId, long wResult)) {
                assertEquals(id, wId);
                assertEquals(inputBytes.length, wResult);
            } else {
                fail("Result is not a WriteResult");
            }

            String writtenContent = Files.readString(Path.of(path));
            assertEquals(input, writtenContent);
        }
    }
}