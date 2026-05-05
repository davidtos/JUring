package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

record LibCDispatcher(FreeAddress freeLong,
        Consumer<MemorySegment> free,
                      Open open,
                      IntConsumer close,
                      Malloc malloc,
                      MallocAddress mallocAddress,
                      Strerror strerror,
                      Calloc calloc,
                      Socket socket,
                      Bind bind,
                      Listen listen,
                      SetSockOpt setsockopt) {

    // sockaddr_in layout (Linux x86-64):
    //   uint16_t sin_family  @ offset 0  (2 bytes)
    //   uint16_t sin_port    @ offset 2  (2 bytes, network byte order)
    //   uint32_t sin_addr    @ offset 4  (4 bytes, network byte order)
    //   char     sin_zero[8] @ offset 8  (8 bytes padding)
    // total: 16 bytes
    static final GroupLayout SOCKADDR_IN_LAYOUT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("sin_family"),
            JAVA_SHORT.withName("sin_port"),
            JAVA_INT.withName("sin_addr"),
            MemoryLayout.sequenceLayout(8, JAVA_BYTE).withName("sin_zero")
    ).withName("sockaddr_in");

    // AF_INET = 2 (Linux)
    static final short AF_INET = 2;
    // SOL_SOCKET = 1
    static final int SOL_SOCKET = 1;
    // SO_REUSEADDR = 2
    static final int SO_REUSEADDR = 2;
    // SO_REUSEPORT = 15
    static final int SO_REUSEPORT = 15;
    // SOCK_STREAM = 1
    static final int SOCK_STREAM = 1;

    private static final Linker linker = Linker.nativeLinker();

    static LibCDispatcher create() {
        return new LibCDispatcher(
                link(FreeAddress.class, "free", FunctionDescriptor.ofVoid(JAVA_LONG), true),
                link(Consumer.class, "free", FunctionDescriptor.ofVoid(ADDRESS), true),
                link(Open.class, "open", FunctionDescriptor.of(ValueLayout.JAVA_INT, ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), true),
                link(IntConsumer.class, "close", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT), true),
                link(Malloc.class, "malloc", FunctionDescriptor.of(ADDRESS, ValueLayout.JAVA_LONG), true),
                link(MallocAddress.class, "malloc", FunctionDescriptor.of(JAVA_LONG, ValueLayout.JAVA_LONG), true),
                link(Strerror.class, "strerror", FunctionDescriptor.of(ADDRESS, ValueLayout.JAVA_INT), false),
                link(Calloc.class, "calloc", FunctionDescriptor.of(ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), true),
                link(Socket.class, "socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), true),
                link(Bind.class, "bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), true),
                link(Listen.class, "listen", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), true),
                link(SetSockOpt.class, "setsockopt", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), true)
        );
    }

    private static <T> T link(Class<T> type, String name, FunctionDescriptor descriptor, boolean critical) {
        MemorySegment symbol = linker.defaultLookup().findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.critical(critical));
        return MethodHandleProxies.asInterfaceInstance(type, handle);
    }

    void free(long address) {
        freeLong.free(address);
    }

    void free(MemorySegment address) {
        free.accept(address);
    }

    int open(String path, int flags, int mode) {
        return open.open(MemorySegment.ofArray((path + "\0").getBytes()), flags, mode);
    }

    void close(int fd) {
        close.accept(fd);
    }

    MemorySegment malloc(long size) {
        return malloc.malloc(size).reinterpret(size);
    }

    long mallocAddress(long size) {
        return mallocAddress.malloc(size);
    }

    String strerror(int errno) {
        return strerror.strerror(errno).reinterpret(Long.MAX_VALUE).getString(0);
    }

    MemorySegment calloc(long size) {
        return calloc.calloc(1L, size).reinterpret(size);
    }

    IovecStructure allocateIovec(Arena arena, long bufferSize, long nrIovecs) {
        MemorySegment iovecArray = Iovec.allocateArray(nrIovecs, arena);
        MemorySegment[] buffers = new MemorySegment[(int) nrIovecs];

        for (int i = 0; i < nrIovecs; i++) {
            MemorySegment nthIovec = Iovec.asSlice(iovecArray, i);
            MemorySegment buffer = malloc(bufferSize);

            Iovec.iov_base(nthIovec, buffer);
            Iovec.iov_len(nthIovec, buffer.byteSize());
            buffers[i] = buffer;
        }

        return new IovecStructure(iovecArray, buffers);
    }

    record IovecStructure(MemorySegment iovecArray, MemorySegment[] buffers) {
    }

    MemorySegment alloc(long size) {

        if (size >= 4000) {
            return calloc(size);
        }

        try {
            return malloc(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int createSocket() {
        int fd = socket.socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) {
            throw new RuntimeException("socket() failed (returned " + fd + ")");
        }
        return fd;
    }

    void setReuseAddrAndPort(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment optval = arena.allocate(JAVA_INT);
            optval.set(JAVA_INT, 0, 1);
            int r1 = setsockopt.setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, optval, (int) JAVA_INT.byteSize());
            if (r1 < 0) {
                throw new RuntimeException("setsockopt(SO_REUSEADDR) failed (returned " + r1 + ")");
            }
            int r2 = setsockopt.setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, optval, (int) JAVA_INT.byteSize());
            if (r2 < 0) {
                throw new RuntimeException("setsockopt(SO_REUSEPORT) failed (returned " + r2 + ")");
            }
        }
    }

    void bindAndListen(int fd, int port, int backlog) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addr = arena.allocate(SOCKADDR_IN_LAYOUT);
            // zero the struct (sin_zero padding must be zero)
            addr.fill((byte) 0);
            addr.set(JAVA_SHORT, 0, AF_INET);
            // htons: reverse bytes of the port (network byte order)
            addr.set(JAVA_SHORT, 2, Short.reverseBytes((short) port));
            // INADDR_ANY = 0, already zeroed
            addr.set(JAVA_INT, 4, 0);

            int r = bind.bind(fd, addr, (int) SOCKADDR_IN_LAYOUT.byteSize());
            if (r < 0) {
                throw new RuntimeException("bind() failed on port " + port + " (returned " + r + ")");
            }

            r = listen.listen(fd, backlog);
            if (r < 0) {
                throw new RuntimeException("listen() failed (returned " + r + ")");
            }
        }
    }

    MemorySegment allocSockaddrIn(String host, int port) {
        MemorySegment addr = malloc(SOCKADDR_IN_LAYOUT.byteSize());
        addr.fill((byte) 0);
        addr.set(JAVA_SHORT, 0, AF_INET);
        addr.set(JAVA_SHORT, 2, Short.reverseBytes((short) port));
        addr.set(JAVA_INT, 4, Integer.reverseBytes(parseIpv4(host)));
        return addr;
    }

    private static int parseIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + host);
        }
        int result = 0;
        for (String part : parts) {
            result = (result << 8) | (Integer.parseInt(part) & 0xFF);
        }
        return result;
    }
}

