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

    public JUring(int queueDepth, IoUringOptions... ioUringFlags) {
        ioUring = NativeDispatcher.getUringInstance(queueDepth, ioUringFlags);
        registeredBuffers = new ArrayList<>();
    }

    public long prepareRead(FileDescriptor fd, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), readSize, offset, sqeOptions);
    }

    public long prepareRead(int indexFD, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, readSize, offset, addFixedFileFlag(sqeOptions));
    }

    public long prepareReadFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareReadFixedInternal(fd.getFd(), readSize, offset, bufferIndex, sqeOptions);
    }

    public long prepareReadFixed(int indexFD, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareReadFixedInternal(indexFD, readSize, offset, bufferIndex, addFixedFileFlag(sqeOptions));
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, sqeOptions);
    }

    public long prepareWrite(FileDescriptor fd, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, sqeOptions);
    }

    public long prepareWrite(int indexFD, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(indexFD, bytes, offset, addFixedFileFlag(sqeOptions));
    }

    public long prepareWrite(int indexFD, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(indexFD, bytes, offset, addFixedFileFlag(sqeOptions));
    }

    public long prepareWriteFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, sqeOptions);
    }

    public long prepareWriteFixed(int indexFD, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, addFixedFileFlag(sqeOptions));
    }

    private SqeOptions[] addFixedFileFlag(SqeOptions[] sqeOptions) {
        SqeOptions[] allFlags = new SqeOptions[sqeOptions.length + 1];
        allFlags[sqeOptions.length] = SqeOptions.IOSQE_FIXED_FILE;
        return allFlags;
    }

    public long prepareOpen(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        MemorySegment pathBuffer = NativeDispatcher.C.calloc(filePath.getBytes().length + 1);
        MemorySegment.copy(filePath.getBytes(), 0, pathBuffer, JAVA_BYTE, 0, filePath.getBytes().length);

        long id = pathBuffer.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, -1, OperationType.OPEN, pathBuffer);

        MemorySegment sqe = getSqe(sqeOptions);

        ioUring.prepareOpenAt(sqe, pathBuffer, flags, mode);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        MemorySegment pathBuffer = NativeDispatcher.C.alloc(filePath.getBytes().length + 1);
        MemorySegment.copy(filePath.getBytes(), 0, pathBuffer, JAVA_BYTE, 0, filePath.getBytes().length);
        pathBuffer.set(JAVA_BYTE, filePath.getBytes().length, (byte) 0);

        long id = pathBuffer.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fileIndex, OperationType.OPEN, pathBuffer);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareOpenDirectAt(sqe, pathBuffer, flags, mode, fileIndex);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareClose(FileDescriptor fd, SqeOptions... sqeOptions) {
        return prepareCloseInternal(fd.getFd(), sqeOptions);
    }

    public long prepareCloseDirect(int fileIndex, SqeOptions... sqeOptions) {
        long id = ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fileIndex, OperationType.CLOSE, MemorySegment.NULL);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareCloseDirect(sqe, fileIndex);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    private long prepareReadInternal(int fdOrIndex, int readSize, long offset, SqeOptions[] sqeOptions) {
        MemorySegment buff = NativeDispatcher.C.malloc(readSize);
        long id = buff.address();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.READ, buff);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareRead(sqe, fdOrIndex, buff, offset);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    private long prepareWriteInternal(int fdOrIndex, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        long id = bytes.address() + ThreadLocalRandom.current().nextLong();;
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.WRITE_FIXED, bytes);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareWrite(sqe, fdOrIndex, bytes, offset);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    private long prepareWriteInternal(int fdOrIndex, byte[] bytes, long offset, SqeOptions[] sqeOptions) {
        MemorySegment buff = NativeDispatcher.C.alloc(bytes.length);
        long id = buff.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.WRITE, buff);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.setUserData(sqe, userData.address());
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        ioUring.prepareWrite(sqe, fdOrIndex, buff, offset);

        return id;
    }

    private long prepareReadFixedInternal(int fdOrIndex, int readSize, long offset, int bufferIndex, SqeOptions[] sqeOptions) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffers.size()) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        MemorySegment registeredBuffer = registeredBuffers.get(bufferIndex);
        if (readSize > registeredBuffer.byteSize()) {
            throw new IllegalArgumentException("Read size exceeds registered buffer size");
        }

        long id = registeredBuffer.address();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.READ, registeredBuffer);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareReadFixed(sqe, fdOrIndex, registeredBuffer, offset, bufferIndex);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, byte[] bytes, long offset, int bufferIndex, SqeOptions[] sqeOptions) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffers.size()) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        MemorySegment registeredBuffer = registeredBuffers.get(bufferIndex);
        if (bytes.length > registeredBuffer.byteSize()) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        long id = registeredBuffer.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.WRITE_FIXED, registeredBuffer);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.setUserData(sqe, userData.address());
        MemorySegment.copy(bytes, 0, registeredBuffer, JAVA_BYTE, 0, bytes.length);
        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBuffer, bytes.length, offset, bufferIndex);

        return id;
    }

    private long prepareCloseInternal(int fdOrIndex, SqeOptions[] sqeOptions) {
        long id = ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, fdOrIndex, OperationType.CLOSE, MemorySegment.NULL);

        MemorySegment sqe = getSqe(sqeOptions);

        ioUring.prepareClose(sqe, fdOrIndex);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    private MemorySegment getSqe(SqeOptions[] sqeOptions) {
        MemorySegment sqe = ioUring.getSqe();
        if (sqe != null) {
            ioUring.setSqeFlag(sqe, sqeOptions);
        }
        return sqe;
    }

    public void submit() {
        ioUring.submit();
    }

    public List<Result> peekForBatchResult(int batchSize) {
        return ioUring.peekForBatchResult(batchSize);
    }

    public List<Result> waitForBatchResult(int batchSize) {
        return ioUring.waitForBatchResult(batchSize);
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