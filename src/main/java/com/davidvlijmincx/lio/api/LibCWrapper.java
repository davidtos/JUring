package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static java.lang.foreign.ValueLayout.*;

final class LibCWrapper {

    private static final Linker linker = Linker.nativeLinker();
    private static final SymbolLookup liburing = SymbolLookup.libraryLookup("liburing-ffi.so", Arena.ofAuto());
    private static final AddressLayout C_POINTER = ADDRESS
            .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));

    static final LibCDispatcher C_DISPATCHER = new LibCDispatcher(
            link(Consumer.class, "free", FunctionDescriptor.ofVoid(ADDRESS),true),
            link(Open.class, "open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), true),
            link(IntConsumer.class, "close", FunctionDescriptor.ofVoid(JAVA_INT),true),
            link(Malloc.class, "malloc",  FunctionDescriptor.of(ADDRESS, JAVA_LONG), true),
            link(Strerror.class, "strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT), false),
            link(Calloc.class, "calloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG), true)
    );

    static final LibUringDispatcher URING_DISPATCHER = new LibUringDispatcher(
            libLink(GetSqe.class, "io_uring_get_sqe", FunctionDescriptor.of(ADDRESS, ADDRESS), true),
            libLink(SetSqeFlag.class, "io_uring_sqe_set_flags", FunctionDescriptor.ofVoid(C_POINTER, JAVA_BYTE), true),
            libLink(PrepOpenAt.class, "io_uring_prep_openat", FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_INT, JAVA_INT), false),
            libLink(PrepareOpenDirect.class, "io_uring_prep_open_direct",FunctionDescriptor.ofVoid(C_POINTER, C_POINTER, JAVA_INT, JAVA_INT, JAVA_INT), false),
            libLink(PrepareClose.class, "io_uring_prep_close",FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT), false),
            libLink(PrepareCloseDirect.class, "io_uring_prep_close_direct",FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT), false),
            libLink(PrepareRead.class, "io_uring_prep_read",FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG), false),
            libLink(PrepareReadFixed.class, "io_uring_prep_read_fixed",FunctionDescriptor.ofVoid(C_POINTER, JAVA_INT, C_POINTER, JAVA_LONG, JAVA_LONG, JAVA_INT), false)
    );

    static <T> T link(Class<T> type,
                      String name,
                      FunctionDescriptor descriptor,
                      boolean critical){

        MemorySegment symbol = linker.defaultLookup().findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.critical(critical));
        return MethodHandleProxies.asInterfaceInstance (type, handle);
    }

    static <T> T libLink(Class<T> type,
                      String name,
                      FunctionDescriptor descriptor,
                      boolean critical){

        MemorySegment symbol = liburing.findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.critical(critical));
        return MethodHandleProxies.asInterfaceInstance (type, handle);
    }

    private LibCWrapper() {
    }
}
