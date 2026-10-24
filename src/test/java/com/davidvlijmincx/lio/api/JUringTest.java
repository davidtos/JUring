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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

            assertEquals(id, result.id());
            assertEquals(13, result.result());

            result.buffer().set(JAVA_BYTE, result.result(), (byte) 0);
            String string = result.buffer().getString(0);
            result.freeBuffer();
            assertEquals("Hello, World!", string);

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

            assertEquals(id, result.id());
            assertEquals(13, result.result());

            result.buffer().set(JAVA_BYTE, result.result(), (byte) 0);
            String string = result.buffer().getString(0);
            result.freeBuffer();
            assertEquals("Hello, World!", string);

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
                result.freeBuffer();
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

            if (result.type() == OperationType.READ) {
               result.freeBuffer();
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

            assertEquals(id, result.id());

            String string = result.buffer().getString(0);
            result.freeBuffer();
            assertEquals("World!", string);

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

            assertEquals(id, result.id());
            assertEquals(inputBytes.length, result.result());

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

            assertEquals(id, result.id());
            assertEquals(inputBytes.length, result.result());

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

            try (readResult) {
                assertEquals(id, readResult.id());
                assertEquals(13, readResult.result());

                readResult.buffer().set(JAVA_BYTE, readResult.result(), (byte) 0);
                String string = readResult.buffer().getString(0);
                assertEquals("Hello, World!", string);
            } catch (Exception e) {
                throw new RuntimeException(e);
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

            assertEquals(id, writeResult.id());
            assertEquals(inputBytes.length, writeResult.result());

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

            assertEquals(id2, readResult2.id());
            readResult2.buffer().set(JAVA_BYTE, (int)readResult2.result(), (byte) 0);
            String content = readResult2.buffer().getString(0);
            readResult2.freeBuffer();
            assertEquals("third file content", content);

            // Test reading from index 1 (second_read_file) before update
            long id1 = jUring.prepareRead(1, 20, 0);
            jUring.submit();
            Result readResult1 = jUring.waitForResult();

            assertEquals(id1, readResult1.id());
            readResult1.buffer().set(JAVA_BYTE, (int)readResult1.result(), (byte) 0);
            content = readResult1.buffer().getString(0);
            readResult1.freeBuffer();
            assertEquals("second file content", content);
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
            
            assertEquals(id, result.id());
            assertEquals(13, result.result());
            
            result.buffer().set(JAVA_BYTE, result.result(), (byte) 0);
            String string = result.buffer().getString(0);
            assertEquals("Hello, World!", string);
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
            
            assertEquals(id, result.id());
            assertEquals(13, result.result());
            
            result.buffer().set(JAVA_BYTE, result.result(), (byte) 0);
            String string = result.buffer().getString(0);
            assertEquals("Hello, World!", string);

        }
    }

    @Test
    void prepareOpenAndRead() {
        long openId = jUring.prepareOpen("src/test/resources/read_file", READ.getValue(), 0);
        jUring.submit();
        Result openResult = jUring.waitForResult();

        assertEquals(openId, openResult.id());
        FileDescriptor fd = openResult.fileDescriptor();

        long readId = jUring.prepareRead(fd, 14, 0);
        jUring.submit();
        Result readResult = jUring.waitForResult();

        assertEquals(readId, readResult.id());
        assertEquals(13, readResult.result());

        readResult.buffer().set(JAVA_BYTE, readResult.result(), (byte) 0);
        String content = readResult.buffer().getString(0);
        readResult.freeBuffer();
        assertEquals("Hello, World!", content);

        long closeId = jUring.prepareClose(fd);
        jUring.submit();
        Result closeResult = jUring.waitForResult();

        assertEquals(closeId, closeResult.id());
        assertEquals(0, closeResult.result());
    }

    @Test
    void prepareOpenDirectAndRead() {
        try(FileDescriptor placeholder = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUring.registerFiles(placeholder);

            long openId = jUring.prepareOpenDirect("src/test/resources/second_read_file", READ.getValue(), 0, 0);
            jUring.submit();
            Result openResult = jUring.waitForResult();

            assertEquals(openId, openResult.id());

            long readId = jUring.prepareRead(0, 20, 0);
            jUring.submit();
            Result readResult = jUring.waitForResult();

            assertEquals(readId, readResult.id());
            assertEquals(19, readResult.result());

            readResult.buffer().set(JAVA_BYTE, readResult.result(), (byte) 0);
            String content = readResult.buffer().getString(0);
            readResult.freeBuffer();
            assertEquals("second file content", content);
        }
    }

    @Test
    void prepareCloseFileDescriptor() {
        FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
        
        // First, verify we can read from the file
        long readId1 = jUring.prepareRead(fd, 14, 0);
        jUring.submit();
        Result readResult1 = jUring.waitForResult();
        
        assertEquals(13, readResult1.result());
        readResult1.freeBuffer();
        
        // Now close the file descriptor
        long closeId = jUring.prepareClose(fd);
        jUring.submit();
        Result closeResult = jUring.waitForResult();

        assertEquals(closeId, closeResult.id());
        assertEquals(0, closeResult.result());
        
        // Verify the file descriptor is actually closed by trying to read from it
        // This should fail with a bad file descriptor error
        long readId2 = jUring.prepareRead(fd, 14, 0);
        jUring.submit();
        Result readResult2 = jUring.waitForResult();
        
        // Should get EBADF (Bad file descriptor) error, which is -9
        assertEquals(-9, readResult2.result());
        readResult2.freeBuffer();
    }

    @Test
    void prepareCloseDirectFileDescriptor() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUring.registerFiles(fd);
            
            // First, verify we can read from the registered file
            jUring.prepareRead(0, 14, 0);
            jUring.submit();
            Result readResult1 = jUring.waitForResult();
            
            assertEquals(13, readResult1.result());
            readResult1.freeBuffer();
            
            // Now close the registered file using direct close
            long closeId = jUring.prepareCloseDirect(0);
            jUring.submit();
            Result closeResult = jUring.waitForResult();

            assertEquals(closeId, closeResult.id());
            assertEquals(0, closeResult.result());
            
            // Verify the registered file descriptor is actually closed by trying to read from it
            // This should fail with a bad file descriptor error
            jUring.prepareRead(0, 14, 0);
            jUring.submit();
            Result readResult2 = jUring.waitForResult();
            
            // Should get EBADF (Bad file descriptor) error, which is -9
            assertEquals(-9, readResult2.result());
            readResult2.freeBuffer();
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
            
            assertEquals(id, writeResult.id());
            assertEquals(inputBytes.length, writeResult.result());

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

            assertEquals(id, result.id());
            assertEquals(inputBytes.length, result.result());

            String writtenContent = Files.readString(Path.of(path));
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void prepareWriteFixedWithMemorySegmentAndRegisteredBuffer() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try(FileDescriptor fd = new FileDescriptor(path, WRITE, 0)) {
            jUring.registerBuffers(30, 1);

            ByteBuffer bb = ByteBuffer.allocateDirect(inputBytes.length);
            bb.put(inputBytes);
            bb.flip();
            MemorySegment src = MemorySegment.ofBuffer(bb);

            long id = jUring.prepareWriteFixed(fd, src, 0, 0);
            jUring.submit();
            Result result = jUring.waitForResult();

            assertEquals(id, result.id());
            assertEquals(inputBytes.length, result.result());

            String writtenContent = Files.readString(Path.of(path));
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void prepareReadFixedReadsExactRequestedBytes() {
        try(FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUring.registerBuffers(30, 1);

            long id = jUring.prepareReadFixed(fd, 7, 0, 0);
            jUring.submit();
            Result result = jUring.waitForResult();

            assertEquals(id, result.id());
            assertEquals(7, result.result());

            result.buffer().set(JAVA_BYTE, result.result(), (byte) 0);
            assertEquals("Hello, ", result.buffer().getString(0));
        }
    }

    @Test
    void bufferPoolExhaustionAndRecovery() {
        jUring.registerBuffers(64, 2);

        int first = jUring.checkOutBuffer();
        int second = jUring.checkOutBuffer();
        assertNotEquals(-1, first);
        assertNotEquals(-1, second);

        // Pool is now exhausted
        assertEquals(-1, jUring.checkOutBuffer());

        // Return one buffer and verify it is reusable
        jUring.checkInBuffer(first);
        int reused = jUring.checkOutBuffer();
        assertNotEquals(-1, reused);
        assertEquals(first, reused);

        jUring.checkInBuffer(reused);
        jUring.checkInBuffer(second);
    }

    @Test
    void readWithCheckedOutBuffer() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUring.registerBuffers(30, 2);

            int bufIdx = jUring.checkOutBuffer();
            assertNotEquals(-1, bufIdx);

            long id = jUring.prepareReadFixed(fd, 13, 0, bufIdx);
            jUring.submit();
            Result result = jUring.waitForResult();

            assertEquals(id, result.id());
            assertEquals(13, result.result());
            assertEquals(bufIdx, result.bufferIdx());

            result.buffer().set(JAVA_BYTE, result.result(), (byte) 0);
            assertEquals("Hello, World!", result.buffer().getString(0));

            jUring.checkInBuffer(bufIdx);
        }
    }

    @Test
    void writeWithCheckedOutBuffer() throws IOException {
        String path = "src/test/resources/write_file";
        Files.write(Path.of(path), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path, WRITE, 0)) {
            jUring.registerBuffers(30, 2);

            int bufIdx = jUring.checkOutBuffer();
            assertNotEquals(-1, bufIdx);

            long id = jUring.prepareWriteFixed(fd, inputBytes, 0, bufIdx);
            jUring.submit();
            Result result = jUring.waitForResult();

            assertEquals(id, result.id());
            assertEquals(inputBytes.length, result.result());

            String writtenContent = Files.readString(Path.of(path));
            assertEquals(input, writtenContent);

            jUring.checkInBuffer(bufIdx);
        }
    }

    @Test
    void nopCompletesWithZeroResult() {
        long id1 = jUring.prepareNop();
        long id2 = jUring.prepareNop();
        jUring.submit();

        List<Result> results = jUring.waitForBatchResult(2);
        assertEquals(2, results.size());
        assertEquals(id1, results.get(0).id());
        assertEquals(0L, results.get(0).result());
        assertEquals(id2, results.get(1).id());
        assertEquals(0L, results.get(1).result());
    }

    @Test
    void submitAndWaitAndBatchConsumerReapsAllAvailable() {
        long id1 = jUring.prepareNop();
        long id2 = jUring.prepareNop();
        long id3 = jUring.prepareNop();
        long id4 = jUring.prepareNop();

        List<Long> completedIds = new ArrayList<>();
        int reaped = jUring.submitAndWaitForBatchResult(1, 4, result -> {
            assertEquals(0L, result.result());
            completedIds.add(result.id());
        });

        assertEquals(4, reaped);
        assertThat(completedIds).containsExactlyInAnyOrder(id1, id2, id3, id4);
    }

    @Test
    void waitForBatchResultRespectsMaxReap() {
        long id1 = jUring.prepareNop();
        long id2 = jUring.prepareNop();
        long id3 = jUring.prepareNop();

        int submitted = jUring.submitAndWait(3);
        assertEquals(3, submitted);

        List<Long> completedIds = new ArrayList<>();
        int firstReap = jUring.waitForBatchResult(1, 2, result -> {
            assertEquals(0L, result.result());
            completedIds.add(result.id());
        });
        assertEquals(2, firstReap);

        int secondReap = jUring.waitForBatchResult(1, 2, result -> {
            assertEquals(0L, result.result());
            completedIds.add(result.id());
        });
        assertEquals(1, secondReap);
        assertThat(completedIds).containsExactlyInAnyOrder(id1, id2, id3);
    }

    @Test
    void prepareWriteFixedZeroCopyOverloads() throws IOException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String firstInput = "Zero-copy FD write!";
        byte[] firstBytes = firstInput.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            jUring.registerFiles(fd);
            jUring.registerBuffers(64, 2);

            // 1. Zero-copy write via FileDescriptor
            int bufIdx0 = jUring.checkOutBuffer();
            MemorySegment buf0 = jUring.getRegisteredBuffer(bufIdx0);
            MemorySegment.copy(MemorySegment.ofArray(firstBytes), 0, buf0, 0, firstBytes.length);

            long id1 = jUring.prepareWriteFixed(fd, firstBytes.length, 0, bufIdx0);
            List<Long> completed = new ArrayList<>();
            int reaped1 = jUring.submitAndWaitForBatchResult(1, result -> {
                assertEquals(firstBytes.length, result.result());
                completed.add(result.id());
            });
            assertEquals(1, reaped1);
            assertEquals(List.of(id1), completed);
            assertEquals(firstInput, Files.readString(path));
            jUring.checkInBuffer(bufIdx0);

            // 2. Zero-copy write via registered file index
            String secondInput = "Registered";
            byte[] secondBytes = secondInput.getBytes();
            int bufIdx1 = jUring.checkOutBuffer();
            MemorySegment buf1 = jUring.getRegisteredBuffer(bufIdx1);
            MemorySegment.copy(MemorySegment.ofArray(secondBytes), 0, buf1, 0, secondBytes.length);

            long id2 = jUring.prepareWriteFixed(0, secondBytes.length, 0, bufIdx1);
            completed.clear();
            int reaped2 = jUring.submitAndWaitForBatchResult(1, result -> {
                assertEquals(secondBytes.length, result.result());
                completed.add(result.id());
            });
            assertEquals(1, reaped2);
            assertEquals(List.of(id2), completed);
            assertEquals("RegisteredFD write!", Files.readString(path));
            jUring.checkInBuffer(bufIdx1);
        }
    }

    @Test
    void registeredBuffersArePageAlignedAndContiguous() {
        MemorySegment[] buffers = jUring.registerBuffers(4096, 4);
        assertEquals(4, buffers.length);

        long baseAddr = buffers[0].address();
        assertEquals(0L, baseAddr & 4095L, "First 4KB registered buffer must be 4096-byte page aligned");

        for (int i = 0; i < buffers.length; i++) {
            assertEquals(4096L, buffers[i].byteSize());
            assertEquals(0L, buffers[i].address() & 4095L, "Registered buffer " + i + " must be 4096-byte page aligned");
            assertEquals(baseAddr + (long) i * 4096L, buffers[i].address(), "Registered buffers must form a contiguous slab");
        }
    }
}