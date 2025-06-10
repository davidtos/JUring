package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

    private final LibUringWrapper libUringWrapper;

    public JUring(int queueDepth) {
        libUringWrapper = new LibUringWrapper(queueDepth);
    }

    public long prepareRead(FileDescriptor fd, int readSize, long offset) {
        MemorySegment buff = LibCWrapper.malloc(readSize);

        long id = buff.address();
        MemorySegment userData = UserData.createUserData(id, fd.getFd(), OperationType.READ, buff);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareRead(sqe, fd.getFd(), buff, offset);
        libUringWrapper.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset) {
        MemorySegment sqe = libUringWrapper.getSqe();
        MemorySegment buff = LibCWrapper.malloc(bytes.length);

        long id = buff.address() + ThreadLocalRandom.current().nextLong();

        MemorySegment userData = UserData.createUserData(id, fd.getFd(), OperationType.WRITE, buff);

        libUringWrapper.setUserData(sqe, userData.address());

        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);

        libUringWrapper.prepareWrite(sqe, fd.getFd(), buff, offset);

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

    @Override
    public void close() {
        libUringWrapper.close();
    }
}