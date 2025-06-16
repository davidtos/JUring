package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

final class UserData {

    private static final StructLayout requestLayout;
    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS
            .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE));;

    private static final VarHandle idHandle;
    private static final VarHandle fdHandle;
    private static final VarHandle typeHandle;
    private static final VarHandle bufferHandle;

    static {

        requestLayout = MemoryLayout.structLayout(
                        ValueLayout.JAVA_LONG.withName("id"),
                        C_POINTER.withName("buffer"),
                        ValueLayout.JAVA_INT.withName("fd"),
                        ValueLayout.JAVA_INT.withName("type"))
                .withName("UserData");

        idHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("id"));
        fdHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("fd"));
        typeHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("type"));
        bufferHandle = requestLayout.varHandle(MemoryLayout.PathElement.groupElement("buffer"));

    }

    static MemorySegment createUserData(long id, int fd, OperationType type, MemorySegment buffer) {
        MemorySegment segment = NativeDispatcher.C.malloc(requestLayout.byteSize());

        idHandle.set(segment, 0L, id);
        fdHandle.set(segment, 0L, fd);
        typeHandle.set(segment, 0L, type.getIndex());
        bufferHandle.set(segment, 0L, buffer);

        return segment;
    }

    static long getByteSize() {
        return requestLayout.byteSize();
    }

    static MemorySegment getBuffer(MemorySegment segment) {
        return (MemorySegment) bufferHandle.get(segment, 0L);
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


}
