package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class JUringBlockingTest {

    static JUringBlocking jUringBlocking;

    @BeforeEach
    void setUp() {
        jUringBlocking = new JUringBlocking(10, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        jUringBlocking.close();
    }

    @Test
    void readFromFile() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);) {
            Future<ReadResult> future = jUringBlocking.prepareRead(fd, 14, 0);

            jUringBlocking.submit();

            var result = future.get();

            String string = getString(result, result.result());
            result.freeBuffer();
            assertEquals("Hello, World!", string);
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void multiReadFromFile() throws ExecutionException, InterruptedException {

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);) {

            var future1 = jUringBlocking.prepareRead(fd, 14, 0);
            var future2 = jUringBlocking.prepareRead(fd, 5, 0);
            var future3 = jUringBlocking.prepareRead(fd, 7, 7);

            jUringBlocking.submit();

            ReadResult result = future1.get();
            ReadResult result1 = future2.get();
            ReadResult result2 = future3.get();

            result.buffer().set(JAVA_BYTE, result.result(), (byte) 0);
            result1.buffer().set(JAVA_BYTE, result1.result(), (byte) 0);
            result2.buffer().set(JAVA_BYTE, result2.result(), (byte) 0);

            assertEquals("Hello, World!", result.buffer().getString(0));
            assertEquals("Hello", result1.buffer().getString(0));
            assertEquals("World!", result2.buffer().getString(0));

            result.freeBuffer();
            result1.freeBuffer();
            result2.freeBuffer();
        }
    }

    @Test
    void readFromFileAtOffset() throws ExecutionException, InterruptedException {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {

            var result = jUringBlocking.prepareRead(fd, 6, 7);

            jUringBlocking.submit();

            String string = result.get().buffer().getString(0);
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

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {

            var future = jUringBlocking.prepareWrite(fd, inputBytes, 0);

            jUringBlocking.submit();

            assertEquals(inputBytes.length, future.get().result());

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

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {

            var result = jUringBlocking.prepareWrite(fd, inputBytes, 4);

            jUringBlocking.submit();

            assertEquals(inputBytes.length, result.get().result());

            String writtenContent = Files.readString(path);
            assertEquals("Big hello, from Java", writtenContent);

        }
    }

    @Test
    void multipleWrites() throws IOException, ExecutionException, InterruptedException {
        Files.write(Path.of("src/test/resources/write_file"), "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor("src/test/resources/write_file", WRITE, 0)) {
            var future1 = jUringBlocking.prepareWrite(fd, inputBytes, 0);
            var future2 = jUringBlocking.prepareWrite(fd, inputBytes, 0);
            var future3 = jUringBlocking.prepareWrite(fd, inputBytes, 0);

            jUringBlocking.submit();

            assertEquals(inputBytes.length, future1.get().result());
            assertEquals(inputBytes.length, future2.get().result());
            assertEquals(inputBytes.length, future3.get().result());
        }
    }

    @Test
    void mixedWriteAndReadFromFile() throws ExecutionException, InterruptedException {
        Path writePath = Path.of("src/test/resources/write_file");
        Path readPath = Path.of("src/test/resources/read_file");

        String input = "hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(readPath.toString(), READ, 0); FileDescriptor writeFd = new FileDescriptor(writePath.toString(), WRITE, 0)) {

            var readResult = jUringBlocking.prepareRead(fd, 14, 0);
            var writeResult = jUringBlocking.prepareWrite(writeFd, inputBytes, 4);
            var readResult1 = jUringBlocking.prepareRead(fd, 5, 0);
            var writeResult1 = jUringBlocking.prepareWrite(writeFd, inputBytes, 4);
            var readResult2 = jUringBlocking.prepareRead(fd, 7, 7);

            jUringBlocking.submit();

            assertEquals(13, readResult.get().result());
            assertEquals(5, readResult1.get().result());
            assertEquals(6, readResult2.get().result());

            assertEquals(16, writeResult.get().result());
            assertEquals(16, writeResult1.get().result());

            readResult.get().freeBuffer();
            readResult1.get().freeBuffer();
            readResult2.get().freeBuffer();

        }
    }

    @Test
    void readFromRegisteredFile() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            int result = jUringBlocking.registerFiles(fd);
            assertEquals(0, result);

            var future = jUringBlocking.prepareRead(0, 14, 0);
            jUringBlocking.submit();

            try( ReadResult readResult = future.get()){
                assertEquals(13, readResult.result());
                String string = getString(readResult, readResult.result());
                assertEquals("Hello, World!", string);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }
    }

    @Test
    void writeToRegisteredFile() throws IOException, ExecutionException, InterruptedException {
        Path path = Path.of( "src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            int result = jUringBlocking.registerFiles(fd);
            assertEquals(0, result);

            var future = jUringBlocking.prepareWrite(0, inputBytes, 0);
            jUringBlocking.submit();
            
            WriteResult writeResult = future.get();
            assertEquals(inputBytes.length, writeResult.result());

            String writtenContent = Files.readString(path);
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void updateRegisteredFile() throws ExecutionException, InterruptedException {
        try (FileDescriptor readFd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             FileDescriptor secondReadFd = new FileDescriptor("src/test/resources/second_read_file", READ, 0);
             FileDescriptor thirdReadFd = new FileDescriptor("src/test/resources/third_read_file", READ, 0)) {
            
            int result = jUringBlocking.registerFiles(readFd, secondReadFd);
            assertEquals(0, result);

            int[] updateFiles = {thirdReadFd.getFd()};
            int updateResult = jUringBlocking.registerFilesUpdate(0, updateFiles);
            assertEquals(1, updateResult);

            var future = jUringBlocking.prepareRead(0, 20, 0);
            jUringBlocking.submit();
            ReadResult readResult = future.get();

            assertEquals(18, readResult.result());
            String content = getString(readResult, (int) readResult.result());
            readResult.freeBuffer();
            assertEquals("third file content", content);

            var futureSecondFile = jUringBlocking.prepareRead(1, 20, 0);
            jUringBlocking.submit();
            ReadResult readResultSecondFile = futureSecondFile.get();

            assertEquals(19, readResultSecondFile.result());
            String content1 = getString(readResultSecondFile, (int) readResultSecondFile.result());
            readResultSecondFile.freeBuffer();
            assertEquals("second file content", content1);
        }
    }

    @Test
    void prepareReadFixed() throws ExecutionException, InterruptedException {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            MemorySegment[] registerResult = jUringBlocking.registerBuffers(30, 2);
            assertEquals(2, registerResult.length);
            
            var future = jUringBlocking.prepareReadFixed(fd, 13, 0, 0);
            jUringBlocking.submit();
            ReadResult result = future.get();
            
            assertEquals(13, result.result());

            registerResult[0].set(JAVA_BYTE, result.result(), (byte) 0);
            String readResult =  registerResult[0].getString(0);
            assertEquals("Hello, World!", readResult);
        }
    }

    private static String getString(ReadResult result, long endOfStringIndex) {
        result.buffer().set(JAVA_BYTE, endOfStringIndex, (byte) 0);
        return result.buffer().getString(0);
    }

    @Test
    void prepareReadFixedWithRegisteredFileAndBuffer() throws ExecutionException, InterruptedException {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUringBlocking.registerFiles(fd);

            MemorySegment[] bufferRegisterResult = jUringBlocking.registerBuffers(20, 1);
            assertEquals(1, bufferRegisterResult.length);
            
            var future = jUringBlocking.prepareReadFixed(0, 13, 0, 0);
            jUringBlocking.submit();
            ReadResult result = future.get();
            
            assertEquals(13, result.result());

            String string = getString(result, result.result());
            assertEquals("Hello, World!", string);
        }
    }

    @Test
    void prepareOpenAndRead() throws ExecutionException, InterruptedException {
        var openFuture = jUringBlocking.prepareOpen("src/test/resources/read_file", READ.getValue(), 0);
        jUringBlocking.submit();
        OpenResult openResult = openFuture.get();

        FileDescriptor fd = openResult.fileDescriptor();

        var readFuture = jUringBlocking.prepareRead(fd, 14, 0);
        jUringBlocking.submit();
        ReadResult readResult = readFuture.get();

        assertEquals(13, readResult.result());

        String content = getString(readResult, readResult.result());
        readResult.freeBuffer();
        assertEquals("Hello, World!", content);

        var closeFuture = jUringBlocking.prepareClose(fd);
        jUringBlocking.submit();
        CloseResult closeResult = closeFuture.get();

        assertEquals(0, closeResult.result());
    }

    @Test
    void prepareOpenDirectAndRead() throws ExecutionException, InterruptedException {
        try (FileDescriptor placeholder = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUringBlocking.registerFiles(placeholder);

            var openFuture = jUringBlocking.prepareOpenDirect("src/test/resources/second_read_file", READ, 0, 0);
            jUringBlocking.submit();
            OpenResult openResult = openFuture.get();

            var readFuture = jUringBlocking.prepareRead(0, 20, 0);
            jUringBlocking.submit();
            ReadResult readResult = readFuture.get();

            assertEquals(19, readResult.result());

            String content = getString(readResult, readResult.result());
            readResult.freeBuffer();
            assertEquals("second file content", content);
        }
    }

    @Test
    void prepareCloseFileDescriptor() throws ExecutionException, InterruptedException {
        FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
        
        var readFuture1 = jUringBlocking.prepareRead(fd, 14, 0);
        jUringBlocking.submit();
        ReadResult readResult1 = readFuture1.get();
        
        assertEquals(13, readResult1.result());
        readResult1.freeBuffer();
        
        var closeFuture = jUringBlocking.prepareClose(fd);
        jUringBlocking.submit();
        CloseResult closeResult = closeFuture.get();

        assertEquals(0, closeResult.result());
        
        var readFuture2 = jUringBlocking.prepareRead(fd, 14, 0);
        jUringBlocking.submit();
        ReadResult readResult2 = readFuture2.get();
        
        assertEquals(-9, readResult2.result());
        readResult2.freeBuffer();
    }

    @Test
    void prepareCloseDirectFileDescriptor() throws ExecutionException, InterruptedException {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            jUringBlocking.registerFiles(fd);
            
            var readFuture = jUringBlocking.prepareRead(0, 14, 0);
            jUringBlocking.submit();
            ReadResult readResult = readFuture.get();
            
            assertEquals(13, readResult.result());
            readResult.freeBuffer();
            
            var closeFuture = jUringBlocking.prepareCloseDirect(0);
            jUringBlocking.submit();
            CloseResult closeResult = closeFuture.get();

            assertEquals(0, closeResult.result());
            
            var readFuture2 = jUringBlocking.prepareRead(0, 14, 0);
            jUringBlocking.submit();
            ReadResult readResult2 = readFuture2.get();
            
            assertEquals(-9, readResult2.result());
            readResult2.freeBuffer();
        }
    }

    @Test
    void prepareWriteFixed() throws IOException, ExecutionException, InterruptedException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            MemorySegment[] registerResult = jUringBlocking.registerBuffers(30, 2);
            assertEquals(2, registerResult.length);
            
            var future = jUringBlocking.prepareWriteFixed(fd, inputBytes, 0, 0);
            jUringBlocking.submit();
            WriteResult writeResult = future.get();
            
            assertEquals(inputBytes.length, writeResult.result());

            String writtenContent = Files.readString(path);
            assertEquals(input, writtenContent);
        }
    }

    @Test
    void prepareWriteFixedWithRegisteredFileAndBuffer() throws IOException, ExecutionException, InterruptedException {
        Path path = Path.of("src/test/resources/write_file");
        Files.write(path, "Clean content".getBytes());

        String input = "Hello, from Java";
        var inputBytes = input.getBytes();

        try (FileDescriptor fd = new FileDescriptor(path.toString(), WRITE, 0)) {
            jUringBlocking.registerFiles(fd);

            MemorySegment[] bufferRegisterResult = jUringBlocking.registerBuffers(20, 1);
            assertEquals(1, bufferRegisterResult.length);
            
            var future = jUringBlocking.prepareWriteFixed(0, inputBytes, 0, 0);
            jUringBlocking.submit();
            WriteResult result = future.get();
            
            assertEquals(inputBytes.length, result.result());

            String writtenContent = Files.readString(path);
            assertEquals(input, writtenContent);
        }
    }
}