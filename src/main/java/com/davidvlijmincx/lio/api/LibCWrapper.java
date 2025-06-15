package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.Calloc;
import com.davidvlijmincx.lio.api.functions.Malloc;
import com.davidvlijmincx.lio.api.functions.Open;
import com.davidvlijmincx.lio.api.functions.Strerror;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static java.lang.foreign.ValueLayout.*;

class LibCWrapper {

    private static final Linker linker = Linker.nativeLinker();

    static final LibCDispatcher C_DISPATCHER = new LibCDispatcher(
            link(Consumer.class, "free", FunctionDescriptor.ofVoid(ADDRESS),true),
            link(Open.class, "open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), true),
            link(IntConsumer.class, "close", FunctionDescriptor.ofVoid(JAVA_INT),true),
            link(Malloc.class, "malloc",  FunctionDescriptor.of(ADDRESS, JAVA_LONG), true),
            link(Strerror.class, "strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT), false),
            link(Calloc.class, "calloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG), true)
    );

    static <T> T link(Class<T> type,
                      String name,
                      FunctionDescriptor descriptor,
                      boolean critical){

        MemorySegment symbol = linker.defaultLookup().findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.critical(critical));
        return MethodHandleProxies.asInterfaceInstance (type, handle);
    }

    private LibCWrapper() {
    }
}
