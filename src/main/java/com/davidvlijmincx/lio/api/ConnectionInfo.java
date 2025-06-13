package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

public class ConnectionInfo {

    private static final StructLayout connectionLayout;
    private static final AddressLayout C_POINTER;

    private static final VarHandle idHandle;
    private static final VarHandle fdHandle;
    private static final VarHandle typeHandle;
    private static final VarHandle readBufferHandle;
    private static final VarHandle writeBufferHandle;
    private static final VarHandle writeLenHandle;

    static {
        C_POINTER = ValueLayout.ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE));

        connectionLayout = MemoryLayout.structLayout(
                        ValueLayout.JAVA_LONG.withName("id"),
                        ValueLayout.JAVA_INT.withName("fd"),
                        ValueLayout.JAVA_INT.withName("type"),
                        C_POINTER.withName("readBuffer"),
                        C_POINTER.withName("writeBuffer"),
                        ValueLayout.JAVA_INT.withName("writeLen"),
                        MemoryLayout.paddingLayout(4))
                .withName("ConnectionInfo");

        idHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("id"));
        fdHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("fd"));
        typeHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("type"));
        readBufferHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("readBuffer"));
        writeBufferHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("writeBuffer"));
        writeLenHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("writeLen"));
    }

    static MemorySegment createConnectionInfo(Arena arena, long id, int fd, OperationType type, MemorySegment readBuffer, MemorySegment writeBuffer, int writeLen) {
        MemorySegment segment = arena.allocate(connectionLayout);

        idHandle.set(segment, 0L, id);
        fdHandle.set(segment, 0L, fd);
        typeHandle.set(segment, 0L, type.getIndex());
        readBufferHandle.set(segment, 0L, readBuffer);
        writeBufferHandle.set(segment, 0L, writeBuffer);
        writeLenHandle.set(segment, 0L, writeLen);

        return segment;
    }

    static long getByteSize() {
        return connectionLayout.byteSize();
    }

    static MemorySegment getReadBuffer(MemorySegment segment) {
        return (MemorySegment) readBufferHandle.get(segment, 0L);
    }

    static MemorySegment getWriteBuffer(MemorySegment segment) {
        return (MemorySegment) writeBufferHandle.get(segment, 0L);
    }

    static long getId(MemorySegment segment) {
        return (long) idHandle.get(segment, 0L);
    }

    static int getFd(MemorySegment segment) {
        return (int) fdHandle.get(segment, 0L);
    }

    static OperationType getType(MemorySegment segment) {
        return OperationType.valueOf((int) typeHandle.get(segment, 0L));
    }

    static int getWriteLen(MemorySegment segment) {
        return (int) writeLenHandle.get(segment, 0L);
    }
}