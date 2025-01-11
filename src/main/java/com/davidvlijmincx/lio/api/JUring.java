package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

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
        MemorySegment segment = LibCWrapper.malloc((int)requestLayout.byteSize());

        idHandle.set(segment, 0L, id);
        fdHandle.set(segment, 0L, fd);
        readHandle.set(segment, 0L, read);
        bufferHandle.set(segment, 0L, buffer);

        return segment;
    }

    public long prepareRead(String path, int readSize, int offset) {
        int fd = libUringWrapper.openFile(path, 0, 0);
        MemorySegment buff = LibCWrapper.malloc(readSize);

        long id = buff.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = createUserData(id, fd, true, buff);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.setUserData(sqe, userData.address());
        libUringWrapper.prepareRead(sqe, fd, buff, offset);

        return id;
    }

    void freeReadBuffer(MemorySegment buffer) {
        libUringWrapper.freeMemory(buffer);
    }

    public long prepareWrite(String path, byte[] bytes, int offset) {
        int fd = libUringWrapper.openFile(path, 2, 0);

        MemorySegment sqe = libUringWrapper.getSqe();
        MemorySegment buff = LibCWrapper.malloc(bytes.length);

        long id = buff.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = createUserData(id, fd, false, buff);

        libUringWrapper.setUserData(sqe, userData.address());

        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);

        libUringWrapper.prepareWrite(sqe, fd, buff, offset);

        return id;
    }

    public void submit() {
        libUringWrapper.submit();
    }

    public Optional<Result> peekForResult(){
        Optional<Cqe> cqe = libUringWrapper.peekForResult();
        return cqe.map(this::getResultFromCqe);
    }

    public Result waitForResult() {
        Cqe cqe = libUringWrapper.waitForResult();
        return getResultFromCqe(cqe);
    }

    private Result getResultFromCqe(Cqe cqe) {
        long address = cqe.UserData();
        MemorySegment result = MemorySegment.ofAddress(address).reinterpret(requestLayout.byteSize());

        LibCWrapper.closeFile((int) fdHandle.get(result, 0L));
        libUringWrapper.seen(cqe.cqePointer());

        boolean readResult = (boolean) readHandle.get(result, 0L);

        long idResult = (long) idHandle.get(result, 0L);
        MemorySegment bufferResult = (MemorySegment) bufferHandle.get(result, 0L);
        libUringWrapper.freeMemory(result);

        if (!readResult) {
            libUringWrapper.freeMemory(bufferResult);
            return new AsyncWriteResult(idResult, cqe.result());
        }

        return new AsyncReadResult(idResult, bufferResult, cqe.result());
    }


    @Override
    public void close() {
        libUringWrapper.close();
    }
}