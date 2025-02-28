package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

    private final LibUringWrapper libUringWrapper;

    private static final StructLayout requestLayout;
    private static final AddressLayout C_POINTER;

    private static final VarHandle idHandle;
    private static final VarHandle fdHandle;
    private static final VarHandle readHandle;
    private static final VarHandle bufferHandle;
    private static final VarHandle hasArenaHandle;

    static {
        C_POINTER = ValueLayout.ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE));

        requestLayout = MemoryLayout.structLayout(
                        ValueLayout.JAVA_LONG.withName("id"),
                        C_POINTER.withName("buffer"),
                        ValueLayout.JAVA_INT.withName("fd"),
                        ValueLayout.JAVA_BOOLEAN.withName("read"),
                        ValueLayout.JAVA_BOOLEAN.withName("hasArena"))
                .withName("request");

        idHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("id"));
        fdHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("fd"));
        readHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("read"));
        bufferHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("buffer"));
        hasArenaHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("hasArena"));
    }

    public JUring(int queueDepth) {
        libUringWrapper = new LibUringWrapper(queueDepth);
    }

    private MemorySegment createUserData(long id, int fd, boolean read, MemorySegment buffer, boolean hasArena) {
        MemorySegment segment = LibCWrapper.malloc(requestLayout.byteSize());

        idHandle.set(segment, 0L, id);
        fdHandle.set(segment, 0L, fd);
        readHandle.set(segment, 0L, read);
        bufferHandle.set(segment, 0L, buffer);
        hasArenaHandle.set(segment, 0L, hasArena);

        return segment;
    }

    public long prepareRead(FileDescriptor fd, int readSize, int offset) {
        MemorySegment buff = LibCWrapper.malloc(readSize);
        MemorySegment userData = createUserData(buff.address(), fd.getFd(), true, buff, false);

        return prepareRead(fd, buff, offset, userData);
    }

    public long prepareRead(FileDescriptor fd, MemorySegment buff, int offset) {
        var id = buff.address();
        MemorySegment userData = createUserData(id, fd.getFd(), true, buff, true);

        return prepareRead(fd, buff, offset, userData);
    }

    private long prepareRead(FileDescriptor fd, MemorySegment buff, int offset, MemorySegment userData) {
        var id = buff.address();
        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareRead(sqe, fd.getFd(), buff, offset);
        libUringWrapper.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, int offset) {
        MemorySegment buff = LibCWrapper.malloc(bytes.length);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        MemorySegment userData = createUserData(buff.address(), fd.getFd(), false, buff, false);

        return prepareWrite(fd, buff, offset, userData);
    }

    public long prepareWrite(FileDescriptor fd, MemorySegment buff, int offset) {
        MemorySegment sqe = libUringWrapper.getSqe();

        MemorySegment userData = createUserData(buff.address(), fd.getFd(), false, buff, true);

        return prepareWrite(fd, buff, offset, userData);
    }

    public long prepareWrite(FileDescriptor fd, MemorySegment buff, int offset, MemorySegment userData) {
        MemorySegment sqe = libUringWrapper.getSqe();

        long id = buff.address();

        libUringWrapper.setUserData(sqe, userData.address());
        libUringWrapper.prepareWrite(sqe, fd.getFd(), buff, offset);

        return id;
    }

    public void submit() {
        libUringWrapper.submit();
    }

    public Result peekForResult() {
        Cqe cqe = libUringWrapper.peekForResult();
        if (cqe != null) {
            return getResultFromCqe(cqe);
        }
        return null;
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
        boolean hasArena = (boolean) hasArenaHandle.get(nativeUserData, 0L);
        long idResult = (long) idHandle.get(nativeUserData, 0L);
        MemorySegment bufferResult = (MemorySegment) bufferHandle.get(nativeUserData, 0L);

        LibCWrapper.freeBuffer(nativeUserData);

        if (!readResult) {
            if (!hasArena) {
                LibCWrapper.freeBuffer(bufferResult);
            }
            return new AsyncWriteResult(idResult, cqe.result());
        }

        return new AsyncReadResult(idResult, bufferResult, cqe.result(), hasArena);
    }

    @Override
    public void close() {
        libUringWrapper.close();
    }
}