package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

    private final LibUringDispatcher ioUring;
    private final List<MemorySegment> registeredBuffers;

    public JUring(int queueDepth, IoUringflags... ioUringflags) {
        ioUring = NativeDispatcher.getUringInstance(queueDepth, ioUringflags);
        registeredBuffers = new ArrayList<>();
    }

    public long prepareRead(FileDescriptor fd, int readSize, long offset) {
        return prepareReadInternal(fd.getFd(), readSize, offset, false);
    }

    public long prepareRead(int indexFD, int readSize, long offset) {
        return prepareReadInternal(indexFD, readSize, offset, true);
    }

    public long prepareReadFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex) {
        return prepareReadFixedInternal(fd.getFd(), readSize, offset, bufferIndex, false);
    }

    public long prepareReadFixed(int indexFD, int readSize, long offset, int bufferIndex) {
        return prepareReadFixedInternal(indexFD, readSize, offset, bufferIndex, true);
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, false);
    }

    public long prepareWrite(int indexFD, byte[] bytes, long offset) {
        return prepareWriteInternal(indexFD, bytes, offset, true);
    }

    public long prepareWriteFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, false);
    }

    public long prepareWriteFixed(int indexFD, byte[] bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, true);
    }

    public long prepareOpen(String filePath, int flags, int mode) {
        MemorySegment pathBuffer = NativeDispatcher.C.calloc(filePath.getBytes().length + 1);
        MemorySegment.copy(filePath.getBytes(), 0, pathBuffer, JAVA_BYTE, 0, filePath.getBytes().length);

        long id = pathBuffer.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, -1, OperationType.OPEN, pathBuffer);

        MemorySegment sqe = ioUring.getSqe();
        ioUring.prepareOpenAt(sqe, pathBuffer, flags, mode);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex) {
        MemorySegment pathBuffer = NativeDispatcher.C.alloc(filePath.getBytes().length + 1);
        MemorySegment.copy(filePath.getBytes(), 0, pathBuffer, JAVA_BYTE, 0, filePath.getBytes().length);
        pathBuffer.set(JAVA_BYTE, filePath.getBytes().length, (byte) 0);
        
        long id = pathBuffer.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fileIndex, OperationType.OPEN, pathBuffer);

        MemorySegment sqe = ioUring.getSqe();
        ioUring.prepareOpenDirectAt(sqe, pathBuffer, flags, mode, fileIndex);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareClose(FileDescriptor fd) {
        return prepareCloseInternal(fd.getFd());
    }

    public long prepareCloseDirect(int fileIndex) {
        long id = ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fileIndex, OperationType.CLOSE, MemorySegment.NULL);

        MemorySegment sqe = ioUring.getSqe();
        ioUring.prepareCloseDirect(sqe, fileIndex);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    private long prepareReadInternal(int fdOrIndex, int readSize, long offset, boolean isFixed) {
        MemorySegment buff = NativeDispatcher.C.malloc(readSize);
        long id = buff.address();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.READ, buff);

        MemorySegment sqe = ioUring.getSqe();
        if (isFixed) {
            ioUring.fixedFile(sqe);
        }
        ioUring.prepareRead(sqe, fdOrIndex, buff, offset);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    private long prepareWriteInternal(int fdOrIndex, byte[] bytes, long offset, boolean isFixed) {
        MemorySegment sqe = ioUring.getSqe();
        MemorySegment buff = NativeDispatcher.C.alloc(bytes.length);
        long id = buff.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.WRITE, buff);

        if (isFixed) {
            ioUring.fixedFile(sqe);
        }
        ioUring.setUserData(sqe, userData.address());
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        ioUring.prepareWrite(sqe, fdOrIndex, buff, offset);

        return id;
    }

    private long prepareReadFixedInternal(int fdOrIndex, int readSize, long offset, int bufferIndex, boolean isFixed) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffers.size()) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }
        
        MemorySegment registeredBuffer = registeredBuffers.get(bufferIndex);
        if (readSize > registeredBuffer.byteSize()) {
            throw new IllegalArgumentException("Read size exceeds registered buffer size");
        }
        
        long id = registeredBuffer.address();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.READ, registeredBuffer);

        MemorySegment sqe = ioUring.getSqe();
        if (isFixed) {
            ioUring.fixedFile(sqe);
        }
        ioUring.prepareReadFixed(sqe, fdOrIndex, registeredBuffer, offset, bufferIndex);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, byte[] bytes, long offset, int bufferIndex, boolean isFixed) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffers.size()) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }
        
        MemorySegment registeredBuffer = registeredBuffers.get(bufferIndex);
        if (bytes.length > registeredBuffer.byteSize()) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }
        
        long id = registeredBuffer.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.WRITE_FIXED, registeredBuffer);

        MemorySegment sqe = ioUring.getSqe();
        if (isFixed) {
            ioUring.fixedFile(sqe);
        }
        ioUring.setUserData(sqe, userData.address());
        MemorySegment.copy(bytes, 0, registeredBuffer, JAVA_BYTE, 0, bytes.length);
        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBuffer,bytes.length, offset, bufferIndex);

        return id;
    }

    private long prepareCloseInternal(int fdOrIndex) {
        long id = ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.CLOSE, MemorySegment.NULL);

        MemorySegment sqe = ioUring.getSqe();
        ioUring.prepareClose(sqe, fdOrIndex);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public void submit() {
        ioUring.submit();
    }

    public List<Result> peekForBatchResult(int batchSize) {
        return ioUring.peekForBatchResult(batchSize);
    }

    public Result waitForResult() {
        return ioUring.waitForResult();
    }

    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        MemorySegment[] result = ioUring.registerBuffers(size, nrOfBuffers);
        registeredBuffers.clear();
        registeredBuffers.addAll(Arrays.asList(result));
        return result;
    }

    public int registerFiles(FileDescriptor... fileDescriptors) {
        int[] fds = Arrays.stream(fileDescriptors).mapToInt(FileDescriptor::getFd).toArray();
        return ioUring.registerFiles(fds);
    }

    public int registerFilesUpdate(int offset, int[] fileDescriptors) {
        return ioUring.registerFilesUpdate(offset, fileDescriptors);
    }

    @Override
    public void close() {
        ioUring.close();
    }
}