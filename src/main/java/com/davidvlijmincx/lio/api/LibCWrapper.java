package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.net.InetSocketAddress;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.*;

class LibCWrapper {

    private static final MethodHandle free;
    private static final MethodHandle open;
    private static final MethodHandle close;
    private static final MethodHandle malloc;
    private static final MethodHandle calloc;

    private static final MethodHandle socket;
    private static final MethodHandle bind;
    private static final MethodHandle listen;

    static {
        Linker linker = Linker.nativeLinker();

        free = linker.downcallHandle(
                linker.defaultLookup().find("free").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS),
                Linker.Option.critical(true)
        );

        open = linker.downcallHandle(
                linker.defaultLookup().find("open").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                Linker.Option.critical(true)
        );


        malloc = linker.downcallHandle(
                linker.defaultLookup().find("malloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG),
                Linker.Option.critical(true)
        );

        calloc = linker.downcallHandle(
                linker.defaultLookup().find("calloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG),
                Linker.Option.critical(true)
        );

        close = linker.downcallHandle(
                linker.defaultLookup().find("close").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT),
                Linker.Option.critical(true)
        );

        socket = linker.downcallHandle(
                linker.defaultLookup().find("socket").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        );

        bind = linker.downcallHandle(
                linker.defaultLookup().find("bind").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );

        listen = linker.downcallHandle(
                linker.defaultLookup().find("listen").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)
        );
    }

    private LibCWrapper() {
    }

    static int OpenFile(String filePath, int flags, int mode) {
        try {
            int fd = (int) open.invokeExact(MemorySegment.ofArray((filePath + "\0").getBytes()), flags, mode);
            if (fd < 0) {
                throw new RuntimeException("Failed to open file fd=" + fd);
            }
            return fd;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }


    static void freeBuffer(MemorySegment buffer) {
        try {
            free.invokeExact(buffer);
        } catch (Throwable e) {
            throw new RuntimeException("Could not free memory", e);
        }
    }

    static void closeFile(int fd) {
        try {
            close.invokeExact(fd);
        } catch (Throwable e) {
            throw new RuntimeException("Could not close file with FD:" + fd, e);
        }
    }

    static MemorySegment malloc(int size) {
        return malloc((long) size);
    }

    static MemorySegment malloc(long size) {

        if (size >= 4000) {
            return calloc(size);
        }

        try {
            return ((MemorySegment) malloc.invokeExact(size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }


    static MemorySegment calloc(long size) {
        try {
            return ((MemorySegment) calloc.invokeExact(1L, size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static int socket(int domain, int type, int protocol) {
        try {
            return (int) socket.invokeExact(domain, type, protocol);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static void bind(int fd, InetSocketAddress addr) {
        try {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sockaddr = arena.allocate(16);
                sockaddr.set(JAVA_SHORT, 0, (short) 2); // AF_INET
                sockaddr.set(JAVA_SHORT, 2, (short) (addr.getPort() << 8 | addr.getPort() >> 8));
                sockaddr.set(JAVA_INT, 4, 0); // INADDR_ANY

                int result = (int) bind.invokeExact(fd, sockaddr, 16);
                if (result < 0) {
                    throw new RuntimeException("Bind failed");
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static void listen(int fd, int backlog) {
        try {
            int result = (int) listen.invokeExact(fd, backlog);
            if (result < 0) {
                throw new RuntimeException("Listen failed");
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
