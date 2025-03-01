package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

    private final LibUringWrapper libUringWrapper;

    private static final StructLayout requestLayout;
    private static final AddressLayout C_POINTER;

    private static final VarHandle idHandle;
    private static final VarHandle fdHandle;
    private static final VarHandle readHandle;
    private static final VarHandle bufferHandle;

    static {
        C_POINTER = ValueLayout.ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE));

        requestLayout = MemoryLayout.structLayout(
                        ValueLayout.JAVA_LONG.withName("id"),
                        C_POINTER.withName("buffer"),
                        ValueLayout.JAVA_INT.withName("fd"),
                        ValueLayout.JAVA_BOOLEAN.withName("read"))
                .withName("request");

        idHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("id"));
        fdHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("fd"));
        readHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("read"));
        bufferHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("buffer"));
    }

    public JUring(int queueDepth) {
        libUringWrapper = new LibUringWrapper(queueDepth);
    }

    private MemorySegment createUserData(long id, int fd, boolean read, MemorySegment buffer) {
        MemorySegment segment = LibCWrapper.malloc(requestLayout.byteSize());

        idHandle.set(segment, 0L, id);
        fdHandle.set(segment, 0L, fd);
        readHandle.set(segment, 0L, read);
        bufferHandle.set(segment, 0L, buffer);

        return segment;
    }

    public long prepareRead(FileDescriptor fd, int readSize, int offset) {
        MemorySegment buff = LibCWrapper.malloc(readSize);

        long id = buff.address();
        MemorySegment userData = createUserData(id, fd.getFd(), true, buff);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareRead(sqe, fd.getFd(), buff, offset);
        libUringWrapper.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, int offset) {
        MemorySegment sqe = libUringWrapper.getSqe();
        MemorySegment buff = LibCWrapper.malloc(bytes.length);

        long id = buff.address();

        MemorySegment userData = createUserData(id, fd.getFd(), false, buff);

        libUringWrapper.setUserData(sqe, userData.address());

        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);

        libUringWrapper.prepareWrite(sqe, fd.getFd(), buff, offset);

        return id;
    }

    public void submit() {
        libUringWrapper.submit();
    }

    public Result peekForResult(){
        Cqe cqe = libUringWrapper.peekForResult();
        if (cqe != null) {
            return getResultFromCqe(cqe);
        }
        return null;
    }

    public List<Result> peekForBatchResult(int batchSize) {
        List<Cqe> cqes = libUringWrapper.peekForBatchResult(batchSize);
        if (cqes == null) {
            return null;
        }
        return cqes.stream().map(this::getResultFromCqe).toList();
    }

    public Result waitForResult() {
        Cqe cqe = libUringWrapper.waitForResult();
        return getResultFromCqe(cqe);
    }

    private Result getResultFromCqe(Cqe cqe) {
        long address = cqe.UserData();
        MemorySegment nativeUserData = MemorySegment.ofAddress(address).reinterpret(requestLayout.byteSize());

        libUringWrapper.seen(cqe.cqePointer());

        boolean readResult = (boolean) readHandle.get(nativeUserData, 0L);
        long idResult = (long) idHandle.get(nativeUserData, 0L);
        MemorySegment bufferResult = (MemorySegment) bufferHandle.get(nativeUserData, 0L);

        LibCWrapper.freeBuffer(nativeUserData);

        if (!readResult) {
            LibCWrapper.freeBuffer(bufferResult);
            return new AsyncWriteResult(idResult, cqe.result());
        }

        return new AsyncReadResult(idResult, bufferResult, cqe.result());
    }

    @Override
    public void close() {
        libUringWrapper.close();
    }
}