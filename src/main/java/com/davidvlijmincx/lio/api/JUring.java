package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

    private final LibUringWrapper libUringWrapper;
    private MemorySegment[] registeredBuffers;

    static final StructLayout requestLayout;
    private static final AddressLayout C_POINTER;

     static final VarHandle idHandle;
     static final VarHandle fdHandle;
     static final VarHandle typeHandle;
     static final VarHandle bufferHandle;

    static {
        C_POINTER = ValueLayout.ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE));

        requestLayout = MemoryLayout.structLayout(
                        ValueLayout.JAVA_LONG.withName("id"),
                        C_POINTER.withName("buffer"),
                        ValueLayout.JAVA_INT.withName("fd"),
                        ValueLayout.JAVA_INT.withName("type"))
                .withName("request");

        idHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("id"));
        fdHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("fd"));
        typeHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("type"));
        bufferHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("buffer"));
    }

    public JUring(int queueDepth) {
        libUringWrapper = new LibUringWrapper(queueDepth);
    }

    public JUring(int queueDepth, int bufferSize, int nrOfBuffers) {
        libUringWrapper = new LibUringWrapper(queueDepth);
        registeredBuffers = libUringWrapper.registerBuffers(bufferSize, nrOfBuffers);
    }

    private MemorySegment createUserData(long id, int fd, PrepareType type, MemorySegment buffer) {
        MemorySegment segment = LibCWrapper.allocate(requestLayout.byteSize());

        idHandle.set(segment, 0L, id);
        fdHandle.set(segment, 0L, fd);
        typeHandle.set(segment, 0L, type.getIndex());
        bufferHandle.set(segment, 0L, buffer);

        return segment;
    }

    public long prepareOpen(MemorySegment filePath, Flag flag, int mode){
        MemorySegment sqe = libUringWrapper.getSqe();

        MemorySegment userData = createUserData(filePath.address(), 0, PrepareType.OPEN, MemorySegment.NULL);

        libUringWrapper.prepareOpen(sqe, filePath, flag.getValue(), mode);

        libUringWrapper.setUserData(sqe, userData.address());

        return filePath.address();
    }

    public void prepareClose(int fd){
        MemorySegment sqe = libUringWrapper.getSqe();
        MemorySegment userData = createUserData(sqe.address(), fd, PrepareType.CLOSE, MemorySegment.NULL);
        libUringWrapper.prepareClose(sqe, fd);
        libUringWrapper.setUserData(sqe, userData.address());
    }


    public long prepareRead(int fd, int readSize, long offset) {
        MemorySegment buff = LibCWrapper.allocate(readSize);

        long id = buff.address();
        MemorySegment userData = createUserData(id, fd, PrepareType.READ, buff);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareRead(sqe, fd, buff, offset);
        libUringWrapper.setUserData(sqe, userData.address());

        return id;
    }


    public long prepareRead(FileDescriptor fd, int readSize, long offset) {
        MemorySegment buff = LibCWrapper.allocate(readSize);

        long id = buff.address();
        MemorySegment userData = createUserData(id, fd.getFd(), PrepareType.READ, buff);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareRead(sqe, fd.getFd(), buff, offset);
        libUringWrapper.setUserData(sqe, userData.address());

        return id;
    }


    public long prepareReadFixed(FileDescriptor fd, long offset, int index) {
        var buffer = registeredBuffers[index];

        long id = buffer.address();
        MemorySegment userData = createUserData(id, fd.getFd(), PrepareType.READ, buffer);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareReadFixed(sqe, fd.getFd(), buffer, offset, index);
        libUringWrapper.setUserData(sqe, userData.address());
        return id;
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset) {
        MemorySegment sqe = libUringWrapper.getSqe();
        MemorySegment buff = LibCWrapper.allocate(bytes.length);

        long id = buff.address() + ThreadLocalRandom.current().nextLong();

        MemorySegment userData = createUserData(id, fd.getFd(), PrepareType.WRITE, buff);

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