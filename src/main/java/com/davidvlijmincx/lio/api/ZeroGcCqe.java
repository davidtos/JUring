package com.davidvlijmincx.lio.api;

import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public class ZeroGcCqe {

    static final GroupLayout CQE_LAYOUT = MemoryLayout.structLayout(
            JAVA_LONG.withName("user_data"),
            JAVA_INT.withName("res"),
            JAVA_INT.withName("flags"),
            MemoryLayout.sequenceLayout(0, JAVA_LONG).withName("big_cqe")
    ).withName("io_uring_cqe");

    private static final VarHandle VH_USER_DATA = CQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("user_data"));
    private static final VarHandle VH_RES = CQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("res"));
    private static final VarHandle VH_FLAGS = CQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flags"));

    private static final MemorySegment GLOBAL_MEMORY = MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE);

    static long getUserData(long cqeAddress){
        return (long) VH_USER_DATA.get(GLOBAL_MEMORY, cqeAddress);
    }

    static int getRes(long cqeAddress){
        return (int) VH_RES.get(GLOBAL_MEMORY, cqeAddress);
    }

    static int getFlags(long cqeAddress){
        return (int) VH_FLAGS.get(GLOBAL_MEMORY, cqeAddress);
    }

}