package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.*;

public final class ZeroGcUserData {

    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS
            .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE));

    static final GroupLayout LAYOUT = MemoryLayout.structLayout(
            JAVA_LONG.withName("id"),
            C_POINTER.withName("buffer"),
            JAVA_INT.withName("fd"),
            JAVA_INT.withName("type")
    ).withName("UserData");

    private static final long OFF_ID = LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("id"));
    private static final long OFF_BUFFER = LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("buffer"));
    private static final long OFF_FD = LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("fd"));
    private static final long OFF_TYPE = LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("type"));


    private static final VarHandle VH_LONG = JAVA_LONG.varHandle();
    private static final VarHandle VH_INT = JAVA_INT.varHandle();
    private static final VarHandle VH_ADDR = ADDRESS.varHandle();
    private static final VarHandle VH_POINTER = C_POINTER.varHandle();

    private static final MemorySegment GLOBAL_MEMORY = MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE);


    public static long getByteSize() {
        return LAYOUT.byteSize();
    }

    public static long createUserData(long id, int fd, OperationType type, MemorySegment buffer) {
        long address = NativeDispatcher.C.mallocAddress(LAYOUT.byteSize());

        VH_LONG.set(GLOBAL_MEMORY, address + OFF_ID, id);
        VH_INT.set(GLOBAL_MEMORY, address + OFF_FD, fd);
        VH_INT.set(GLOBAL_MEMORY, address + OFF_TYPE, type.getIndex());
        VH_ADDR.set(GLOBAL_MEMORY, address + OFF_BUFFER, buffer);

        return address;
    }

    public static long createUserData(long id, int fd, OperationType type, long buffer) {
        long address = NativeDispatcher.C.mallocAddress(LAYOUT.byteSize());

        VH_LONG.set(GLOBAL_MEMORY, address + OFF_ID, id);
        VH_INT.set(GLOBAL_MEMORY, address + OFF_FD, fd);
        VH_INT.set(GLOBAL_MEMORY, address + OFF_TYPE, type.getIndex());
        VH_LONG.set(GLOBAL_MEMORY, address + OFF_BUFFER, buffer);

        return address;
    }

    public static long getId(long address) {
        return (long) VH_LONG.get(GLOBAL_MEMORY, address + OFF_ID);
    }

    public static int getFd(long address) {
        return (int) VH_INT.get(GLOBAL_MEMORY, address + OFF_FD);
    }

    public static OperationType getType(long address) {
        int typeIndex = (int) VH_INT.get(GLOBAL_MEMORY, address + OFF_TYPE);
        return OperationType.valueOf(typeIndex);
    }

    public static long getBufferAddress(long address) {
        return (long) VH_LONG.get(GLOBAL_MEMORY, address + OFF_BUFFER);
    }

    public static MemorySegment getBufferSegment(long address) {
        return (MemorySegment) VH_POINTER.get(GLOBAL_MEMORY, address + OFF_BUFFER);
    }

    public static void write(long address, long id, int fd, OperationType type, MemorySegment buffer) {
        VH_LONG.set(GLOBAL_MEMORY, address + OFF_ID, id);
        VH_INT.set(GLOBAL_MEMORY, address + OFF_FD, fd);
        VH_INT.set(GLOBAL_MEMORY, address + OFF_TYPE, type.getIndex());
        VH_ADDR.set(GLOBAL_MEMORY, address + OFF_BUFFER, buffer);
    }

    public static void write(long address, long id, int fd, OperationType type, long buffer) {
        VH_LONG.set(GLOBAL_MEMORY, address + OFF_ID, id);
        VH_INT.set(GLOBAL_MEMORY, address + OFF_FD, fd);
        VH_INT.set(GLOBAL_MEMORY, address + OFF_TYPE, type.getIndex());
        VH_LONG.set(GLOBAL_MEMORY, address + OFF_BUFFER, buffer);
    }

}