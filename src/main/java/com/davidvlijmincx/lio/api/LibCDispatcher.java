package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Dispatcher for native C standard library and Linux system calls using direct MethodHandles.
 */
final class LibCDispatcher {

    // eventfd(2) flags
    static final int EFD_CLOEXEC = 0x80000;
    static final int EFD_NONBLOCK = 0x800;

    // mmap(2) constants (Linux x86-64 / aarch64)
    static final int PROT_READ = 0x1;
    static final int PROT_WRITE = 0x2;
    static final int MAP_SHARED = 0x01;
    static final int MAP_POPULATE = 0x8000;
    static final long MAP_FAILED = -1L;

    static final StructLayout CAPTURE_STATE_LAYOUT = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO_HANDLE = CAPTURE_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    static final GroupLayout SOCKADDR_IN_LAYOUT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("sin_family"),
            JAVA_SHORT.withName("sin_port"),
            JAVA_INT.withName("sin_addr"),
            MemoryLayout.sequenceLayout(8, JAVA_BYTE).withName("sin_zero")
    ).withName("sockaddr_in");

    static final short AF_INET = 2;
    static final int SOL_SOCKET = 1;
    static final int SO_REUSEADDR = 2;
    static final int SO_REUSEPORT = 15;
    static final int SOCK_STREAM = 1;

    private static final Linker linker = Linker.nativeLinker();

    static final MethodHandle MH_FREE_LONG;
    static final MethodHandle MH_FREE_SEGMENT;
    static final MethodHandle MH_OPEN;
    static final MethodHandle MH_CLOSE;
    static final MethodHandle MH_MALLOC;
    static final MethodHandle MH_MALLOC_ADDRESS;
    static final MethodHandle MH_STRERROR;
    static final MethodHandle MH_CALLOC;
    static final MethodHandle MH_SOCKET;
    static final MethodHandle MH_BIND;
    static final MethodHandle MH_LISTEN;
    static final MethodHandle MH_SETSOCKOPT;
    static final MethodHandle MH_MMAP;
    static final MethodHandle MH_MUNMAP;
    static final MethodHandle MH_IO_URING_SETUP;
    static final MethodHandle MH_IO_URING_ENTER;
    static final MethodHandle MH_IO_URING_REGISTER;
    static final MethodHandle MH_EVENTFD;
    static final MethodHandle MH_WRITE;
    static final MethodHandle MH_FCNTL;

    static {
        SymbolLookup lookup = linker.defaultLookup();

        MH_FREE_LONG = linker.downcallHandle(
                lookup.findOrThrow("free"),
                FunctionDescriptor.ofVoid(JAVA_LONG),
                Linker.Option.critical(false)
        );

        MH_FREE_SEGMENT = linker.downcallHandle(
                lookup.findOrThrow("free"),
                FunctionDescriptor.ofVoid(ADDRESS),
                Linker.Option.critical(false)
        );

        MH_OPEN = linker.downcallHandle(
                lookup.findOrThrow("open"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                Linker.Option.captureCallState("errno")
        );

        MH_CLOSE = linker.downcallHandle(
                lookup.findOrThrow("close"),
                FunctionDescriptor.ofVoid(JAVA_INT)
        );

        MH_MALLOC = linker.downcallHandle(
                lookup.findOrThrow("malloc"),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG),
                Linker.Option.critical(false)
        );

        MH_MALLOC_ADDRESS = linker.downcallHandle(
                lookup.findOrThrow("malloc"),
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
                Linker.Option.critical(false)
        );

        MH_STRERROR = linker.downcallHandle(
                lookup.findOrThrow("strerror"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT)
        );

        MH_CALLOC = linker.downcallHandle(
                lookup.findOrThrow("calloc"),
                FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG),
                Linker.Option.critical(false)
        );

        MH_SOCKET = linker.downcallHandle(
                lookup.findOrThrow("socket"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        );

        MH_BIND = linker.downcallHandle(
                lookup.findOrThrow("bind"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );

        MH_LISTEN = linker.downcallHandle(
                lookup.findOrThrow("listen"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)
        );

        MH_SETSOCKOPT = linker.downcallHandle(
                lookup.findOrThrow("setsockopt"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );

        MH_MMAP = linker.downcallHandle(
                lookup.findOrThrow("mmap"),
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG),
                Linker.Option.captureCallState("errno")
        );

        MH_MUNMAP = linker.downcallHandle(
                lookup.findOrThrow("munmap"),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG)
        );

        MethodHandle setupSyscall = lookup.find("syscall")
                .map(sym -> linker.downcallHandle(sym,
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS),
                        Linker.Option.captureCallState("errno"),
                        Linker.Option.firstVariadicArg(1)))
                .orElseThrow();
        MH_IO_URING_SETUP = MethodHandles.insertArguments(setupSyscall, 1, 425L);

        MethodHandle enterSyscall = lookup.find("syscall")
                .map(sym -> linker.downcallHandle(sym,
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG),
                        Linker.Option.captureCallState("errno"),
                        Linker.Option.firstVariadicArg(1)))
                .orElseThrow();
        MH_IO_URING_ENTER = MethodHandles.insertArguments(enterSyscall, 1, 426L);

        MethodHandle registerSyscall = lookup.find("syscall")
                .map(sym -> linker.downcallHandle(sym,
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG),
                        Linker.Option.captureCallState("errno"),
                        Linker.Option.firstVariadicArg(1)))
                .orElseThrow();
        MH_IO_URING_REGISTER = MethodHandles.insertArguments(registerSyscall, 1, 427L);

        MH_EVENTFD = linker.downcallHandle(
                lookup.findOrThrow("eventfd"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)
        );

        MH_WRITE = linker.downcallHandle(
                lookup.findOrThrow("write"),
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG)
        );

        MH_FCNTL = linker.downcallHandle(
                lookup.findOrThrow("fcntl"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT),
                Linker.Option.firstVariadicArg(2)
        );
    }

    private static final LibCDispatcher INSTANCE = new LibCDispatcher();

    static LibCDispatcher create() {
        return INSTANCE;
    }

    private LibCDispatcher() {
    }

    static int errno(MemorySegment callState) {
        return (int) ERRNO_HANDLE.get(callState, 0L);
    }

    static void setErrno(MemorySegment callState, int err) {
        ERRNO_HANDLE.set(callState, 0L, err);
    }

    static final Charset PATH_CHARSET;
    static {
        String enc = System.getProperty("sun.jnu.encoding");
        Charset cs;
        try {
            cs = (enc != null) ? Charset.forName(enc) : StandardCharsets.UTF_8;
        } catch (Exception e) {
            cs = StandardCharsets.UTF_8;
        }
        PATH_CHARSET = cs;
    }

    static byte[] encodePath(String path) {
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Path contains embedded NUL character: " + path);
        }
        return path.getBytes(PATH_CHARSET);
    }

    int fcntl(int fd, int cmd) {
        try {
            return (int) MH_FCNTL.invokeExact(fd, cmd);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void free(long address) {
        try {
            MH_FREE_LONG.invokeExact(address);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void free(MemorySegment address) {
        try {
            MH_FREE_SEGMENT.invokeExact(address);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int open(String path, int flags, int mode) {
        byte[] pathBytes = encodePath(path);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment callState = arena.allocate(CAPTURE_STATE_LAYOUT);
            MemorySegment cPath = arena.allocate(pathBytes.length + 1);
            MemorySegment.copy(pathBytes, 0, cPath, JAVA_BYTE, 0, pathBytes.length);
            cPath.set(JAVA_BYTE, pathBytes.length, (byte) 0);
            int fd = (int) MH_OPEN.invokeExact(callState, cPath, flags | 0x80000 /* O_CLOEXEC */, mode);
            if (fd < 0) {
                return -errno(callState);
            }
            return fd;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    void close(int fd) {
        try {
            MH_CLOSE.invokeExact(fd);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    MemorySegment malloc(long size) {
        try {
            return ((MemorySegment) MH_MALLOC.invokeExact(size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    long mallocAddress(long size) {
        try {
            return (long) MH_MALLOC_ADDRESS.invokeExact(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    String strerror(int errno) {
        try {
            return ((MemorySegment) MH_STRERROR.invokeExact(Math.abs(errno))).reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    MemorySegment calloc(long size) {
        try {
            return ((MemorySegment) MH_CALLOC.invokeExact(1L, size)).reinterpret(size);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    long mmap(long length, int prot, int flags, int fd, long offset) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment callState = callArena.allocate(CAPTURE_STATE_LAYOUT);
            long address = (long) MH_MMAP.invokeExact(callState, 0L, length, prot, flags, fd, offset);
            if (address == MAP_FAILED) {
                return -errno(callState);
            }
            return address;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int munmap(long address, long length) {
        try {
            return (int) MH_MUNMAP.invokeExact(address, length);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int ioUringSetup(MemorySegment callState, int entries, MemorySegment params) {
        try {
            long res = (long) MH_IO_URING_SETUP.invokeExact(callState, (long) entries, params);
            if (res < 0) {
                return -errno(callState);
            }
            return (int) res;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int ioUringSetup(int entries, MemorySegment params) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment callState = callArena.allocate(CAPTURE_STATE_LAYOUT);
            return ioUringSetup(callState, entries, params);
        }
    }

    int ioUringEnter(MemorySegment callState, int fd, int toSubmit, int minComplete, int flags, MemorySegment arg, long argSize) {
        try {
            long res = (long) MH_IO_URING_ENTER.invokeExact(callState, (long) fd, (long) toSubmit, (long) minComplete, (long) flags, arg, argSize);
            if (res < 0) {
                return -errno(callState);
            }
            return (int) res;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int ioUringEnter(int fd, int toSubmit, int minComplete, int flags, MemorySegment arg, long argSize) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment callState = callArena.allocate(CAPTURE_STATE_LAYOUT);
            return ioUringEnter(callState, fd, toSubmit, minComplete, flags, arg, argSize);
        }
    }

    int ioUringRegister(MemorySegment callState, int fd, int opcode, MemorySegment arg, int nrArgs) {
        try {
            long res = (long) MH_IO_URING_REGISTER.invokeExact(callState, (long) fd, (long) opcode, arg, (long) nrArgs);
            if (res < 0) {
                return -errno(callState);
            }
            return (int) res;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int ioUringRegister(int fd, int opcode, MemorySegment arg, int nrArgs) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment callState = callArena.allocate(CAPTURE_STATE_LAYOUT);
            return ioUringRegister(callState, fd, opcode, arg, nrArgs);
        }
    }

    int eventfd(int initval, int flags) {
        try {
            return (int) MH_EVENTFD.invokeExact(initval, flags);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    long write(int fd, long buf, long count) {
        try {
            return (long) MH_WRITE.invokeExact(fd, buf, count);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int socket(int domain, int type, int protocol) {
        try {
            return (int) MH_SOCKET.invokeExact(domain, type, protocol);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int bind(int fd, MemorySegment addr, int addrlen) {
        try {
            return (int) MH_BIND.invokeExact(fd, addr, addrlen);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int listen(int fd, int backlog) {
        try {
            return (int) MH_LISTEN.invokeExact(fd, backlog);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    int setsockopt(int fd, int level, int optname, MemorySegment optval, int optlen) {
        try {
            return (int) MH_SETSOCKOPT.invokeExact(fd, level, optname, optval, optlen);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    IovecStructure allocateIovec(Arena arena, long bufferSize, long nrIovecs) {
        MemorySegment iovecArray = Iovec.allocateArray(nrIovecs, arena);
        MemorySegment[] buffers = new MemorySegment[(int) nrIovecs];
        boolean sectorAligned = bufferSize >= 512 && (bufferSize & 511L) == 0;
        long alignment = (bufferSize >= 4096 && (bufferSize & 4095L) == 0) ? 4096L
                : sectorAligned ? 512L : 64L;
        long stride = sectorAligned ? bufferSize : Math.addExact(bufferSize, 1L);
        MemorySegment slab = arena.allocate(Math.addExact(Math.multiplyExact(stride, nrIovecs), sectorAligned ? 1L : 0L), alignment);

        for (int i = 0; i < nrIovecs; i++) {
            MemorySegment nthIovec = Iovec.asSlice(iovecArray, i);
            MemorySegment buffer = slab.asSlice((long) i * stride, bufferSize);

            Iovec.iov_base(nthIovec, buffer);
            Iovec.iov_len(nthIovec, bufferSize);
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
        return malloc(size);
    }

    int createSocket() {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) {
            throw new RuntimeException("socket() failed (returned " + fd + ")");
        }
        return fd;
    }

    void setReuseAddrAndPort(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment optval = arena.allocate(JAVA_INT);
            optval.set(JAVA_INT, 0, 1);
            int r1 = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, optval, (int) JAVA_INT.byteSize());
            if (r1 < 0) {
                throw new RuntimeException("setsockopt(SO_REUSEADDR) failed (returned " + r1 + ")");
            }
            int r2 = setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, optval, (int) JAVA_INT.byteSize());
            if (r2 < 0) {
                throw new RuntimeException("setsockopt(SO_REUSEPORT) failed (returned " + r2 + ")");
            }
        }
    }

    void bindAndListen(int fd, int port, int backlog) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addr = arena.allocate(SOCKADDR_IN_LAYOUT);
            addr.fill((byte) 0);
            addr.set(JAVA_SHORT, 0, AF_INET);
            addr.set(JAVA_SHORT, 2, Short.reverseBytes((short) port));
            addr.set(JAVA_INT, 4, 0);

            int r = bind(fd, addr, (int) SOCKADDR_IN_LAYOUT.byteSize());
            if (r < 0) {
                throw new RuntimeException("bind() failed on port " + port + " (returned " + r + ")");
            }

            r = listen(fd, backlog);
            if (r < 0) {
                throw new RuntimeException("listen() failed (returned " + r + ")");
            }
        }
    }

    MemorySegment allocSockaddrIn(String host, int port) {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        int ip = parseIpv4(host);
        MemorySegment addr = malloc(SOCKADDR_IN_LAYOUT.byteSize());
        addr.fill((byte) 0);
        addr.set(JAVA_SHORT, 0, AF_INET);
        addr.set(JAVA_SHORT, 2, Short.reverseBytes((short) port));
        addr.set(JAVA_INT, 4, Integer.reverseBytes(ip));
        return addr;
    }

    private static int parseIpv4(String host) {
        if (host == null) {
            throw new IllegalArgumentException("Invalid IPv4 address: null");
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + host);
        }
        int result = 0;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid IPv4 address: " + host, e);
            }
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("Invalid IPv4 address: " + host);
            }
            result = (result << 8) | octet;
        }
        return result;
    }
}
