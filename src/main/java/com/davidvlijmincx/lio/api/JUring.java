package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

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

    private final LibUringLayer libUringLayer;

    public JUring(int queueDepth, boolean polling) {
        libUringLayer = new LibUringLayer(queueDepth, polling);
    }

    private MemorySegment createUserData(long id, int fd, boolean read, MemorySegment buffer) {
        MemorySegment segment = libUringLayer.malloc((int)requestLayout.byteSize());

        idHandle.set(segment, 0L, id);
        fdHandle.set(segment, 0L, fd);
        readHandle.set(segment, 0L, read);
        bufferHandle.set(segment, 0L, buffer);

        return segment;
    }

    public long prepareRead(String path, int readSize, int offset) {
        int fd = libUringLayer.openFile(path, 0, 0);

        MemorySegment buff = libUringLayer.malloc(readSize);

        // TODO: more unique
        long id = buff.address() + ThreadLocalRandom.current().nextLong();

        MemorySegment sqe = libUringLayer.getSqe();

        MemorySegment userData = createUserData(id, fd, true, buff);

        libUringLayer.setUserData(sqe, userData.address());

        libUringLayer.prepareRead(sqe, fd, buff, offset);

        return id;
    }

    public void freeReadBuffer(MemorySegment buffer) {
        libUringLayer.freeMemory(buffer);
    }

    public long prepareWrite(String path, byte[] bytes, int offset) {
        int fd = libUringLayer.openFile(path, 2, 0);

        MemorySegment sqe = libUringLayer.getSqe();
        MemorySegment buff = libUringLayer.malloc(bytes.length);

        long id = buff.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = createUserData(id, fd, false, buff);

        libUringLayer.setUserData(sqe, userData.address());

        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);

        libUringLayer.prepareWrite(sqe, fd, buff, offset);

        return id;
    }

    public void submit() {
        libUringLayer.submit();
    }

    public Result waitForResult() {
        Cqe cqe = libUringLayer.waitForResult();
        long address = cqe.UserData();
        MemorySegment result = MemorySegment.ofAddress(address).reinterpret(requestLayout.byteSize());

        libUringLayer.closeFile((int) fdHandle.get(result, 0L));
        libUringLayer.seen(cqe.cqePointer());

        boolean readResult = (boolean) readHandle.get(result, 0L);

        long idResult = (long) idHandle.get(result, 0L);
        MemorySegment bufferResult = (MemorySegment) bufferHandle.get(result, 0L);
        libUringLayer.freeMemory(result);

        if (!readResult) {
            libUringLayer.freeMemory(bufferResult);
            return new AsyncWriteResult(idResult, cqe.result());
        }

        return new AsyncReadResult(idResult, bufferResult, cqe.result());
    }


    @Override
    public void close() {
        libUringLayer.close();
    }
}