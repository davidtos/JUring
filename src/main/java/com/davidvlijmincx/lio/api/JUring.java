package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

    private final LibUringWrapper libUringWrapper;
    private final List<MemorySegment> registeredBuffers;

    public JUring(int queueDepth) {
        libUringWrapper = new LibUringWrapper(queueDepth);
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

    private long prepareReadInternal(int fdOrIndex, int readSize, long offset, boolean isFixed) {
        MemorySegment buff = LibCWrapper.malloc(readSize);
        long id = buff.address();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.READ, buff);

        MemorySegment sqe = libUringWrapper.getSqe();
        if (isFixed) {
            libUringWrapper.fixedFile(sqe);
        }
        libUringWrapper.prepareRead(sqe, fdOrIndex, buff, offset);
        libUringWrapper.setUserData(sqe, userData.address());

        return id;
    }

    private long prepareWriteInternal(int fdOrIndex, byte[] bytes, long offset, boolean isFixed) {
        MemorySegment sqe = libUringWrapper.getSqe();
        MemorySegment buff = LibCWrapper.malloc(bytes.length);
        long id = buff.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.WRITE, buff);

        if (isFixed) {
            libUringWrapper.fixedFile(sqe);
        }
        libUringWrapper.setUserData(sqe, userData.address());
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        libUringWrapper.prepareWrite(sqe, fdOrIndex, buff, offset);

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

        MemorySegment sqe = libUringWrapper.getSqe();
        if (isFixed) {
            libUringWrapper.fixedFile(sqe);
        }
        libUringWrapper.prepareReadFixed(sqe, fdOrIndex, registeredBuffer, offset, bufferIndex);
        libUringWrapper.setUserData(sqe, userData.address());

        return id;
    }

    public void submit() {
        libUringWrapper.submit();
    }

    public List<Result> peekForBatchResult(int batchSize) {
        return libUringWrapper.peekForBatchResult(batchSize);
    }

    public Result waitForResult() {
        return libUringWrapper.waitForResult();
    }

    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        MemorySegment[] result = libUringWrapper.registerBuffers(size, nrOfBuffers);
        registeredBuffers.clear();
        registeredBuffers.addAll(Arrays.asList(result));
        return result;
    }

    public int registerFiles(FileDescriptor... fileDescriptors) {
        int[] fds = Arrays.stream(fileDescriptors).mapToInt(FileDescriptor::getFd).toArray();
        return libUringWrapper.registerFiles(fds);
    }

    public int registerFilesUpdate(int offset, int[] fileDescriptors) {
        return libUringWrapper.registerFilesUpdate(offset, fileDescriptors);
    }

    @Override
    public void close() {
        libUringWrapper.close();
    }
}