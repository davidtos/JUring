package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
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
                      SetSockOpt setsockopt,
                      Mmap mmap,
                      Munmap munmap,
                      IoUringSetup ioUringSetup,
                      IoUringEnter ioUringEnter,
                      IoUringRegister ioUringRegister) {

    // mmap(2) constants (Linux x86-64 / aarch64)
    static final int PROT_READ = 0x1;
    static final int PROT_WRITE = 0x2;
    static final int MAP_SHARED = 0x01;
    static final int MAP_POPULATE = 0x8000;
    static final long MAP_FAILED = -1L;

    // Layout of the call state captured with Linker.Option.captureCallState("errno")
    static final StructLayout CAPTURE_STATE_LAYOUT = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO_HANDLE = CAPTURE_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

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
                link(SetSockOpt.class, "setsockopt", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), true),
                linkCaptureErrno(Mmap.class, "mmap", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)),
                link(Munmap.class, "munmap", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG), false),
                linkSyscallCaptureErrno(IoUringSetup.class, 425L, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS)),
                linkSyscallCaptureErrno(IoUringEnter.class, 426L, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG)),
                linkSyscallCaptureErrno(IoUringRegister.class, 427L, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
        );
    }

    private static <T> T link(Class<T> type, String name, FunctionDescriptor descriptor, boolean critical) {
        MemorySegment symbol = linker.defaultLookup().findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.critical(critical));
        return MethodHandleProxies.asInterfaceInstance(type, handle);
    }

    // The resulting function takes the captured call state segment as its first parameter
    private static <T> T linkCaptureErrno(Class<T> type, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = linker.defaultLookup().findOrThrow(name);
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.captureCallState("errno"));
        return MethodHandleProxies.asInterfaceInstance(type, handle);
    }

    private static <T> T linkSyscallCaptureErrno(Class<T> type, long sysno, FunctionDescriptor descriptor) {
        MemorySegment symbol = linker.defaultLookup().findOrThrow("syscall");
        MethodHandle handle = linker.downcallHandle(symbol, descriptor, Linker.Option.captureCallState("errno"));
        MethodHandle bound = MethodHandles.insertArguments(handle, 1, sysno);
        return MethodHandleProxies.asInterfaceInstance(type, bound);
    }

    static int errno(MemorySegment callState) {
        return (int) ERRNO_HANDLE.get(callState, 0L);
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

    /**
     * Maps {@code length} bytes of {@code fd} at {@code offset}.
     *
     * @return the address of the mapping, or {@code -errno} when the mapping failed
     */
    long mmap(long length, int prot, int flags, int fd, long offset) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment callState = callArena.allocate(CAPTURE_STATE_LAYOUT);
            long address = mmap.mmap(callState, 0L, length, prot, flags, fd, offset);
            if (address == MAP_FAILED) {
                return -errno(callState);
            }
            return address;
        }
    }

    int munmap(long address, long length) {
        return munmap.munmap(address, length);
    }

    int ioUringSetup(MemorySegment callState, int entries, MemorySegment params) {
        long res = ioUringSetup.ioUringSetup(callState, entries, params);
        if (res < 0) {
            return -errno(callState);
        }
        return (int) res;
    }

    int ioUringSetup(int entries, MemorySegment params) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment callState = callArena.allocate(CAPTURE_STATE_LAYOUT);
            return ioUringSetup(callState, entries, params);
        }
    }

    int ioUringEnter(MemorySegment callState, int fd, int toSubmit, int minComplete, int flags, MemorySegment arg, long argSize) {
        long res = ioUringEnter.ioUringEnter(callState, fd, toSubmit, minComplete, flags, arg, argSize);
        if (res < 0) {
            return -errno(callState);
        }
        return (int) res;
    }

    int ioUringEnter(int fd, int toSubmit, int minComplete, int flags, MemorySegment arg, long argSize) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment callState = callArena.allocate(CAPTURE_STATE_LAYOUT);
            return ioUringEnter(callState, fd, toSubmit, minComplete, flags, arg, argSize);
        }
    }

    int ioUringRegister(MemorySegment callState, int fd, int opcode, MemorySegment arg, int nrArgs) {
        long res = ioUringRegister.ioUringRegister(callState, fd, opcode, arg, nrArgs);
        if (res < 0) {
            return -errno(callState);
        }
        return (int) res;
    }

    int ioUringRegister(int fd, int opcode, MemorySegment arg, int nrArgs) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment callState = callArena.allocate(CAPTURE_STATE_LAYOUT);
            return ioUringRegister(callState, fd, opcode, arg, nrArgs);
        }
    }

    /**
     * Allocates an array of {@code iovec} structures backed by a contiguous, page-aligned buffer slab.
     * <p>
     * <b>Why this change was made (Performance & Kernel Correctness):</b>
     * Previously, each registered buffer was allocated via individual {@code malloc(bufferSize)} calls.
     * Glibc's {@code malloc(4096)} returns a 16-byte aligned pointer preceded by an allocator chunk header.
     * Consequently, <i>every single 4,096-byte buffer straddled two separate 4,096-byte physical memory pages</i>!
     * This caused three critical issues:
     * <ol>
     *   <li><b>RLIMIT_MEMLOCK Exhaustion:</b> Linux pinned two physical pages (8 KB) for every 4 KB buffer registered
     *       with {@code io_uring_register(IORING_REGISTER_BUFFERS)}. At 16 concurrent threads with 64 buffers each,
     *       this required 8 MB of locked memory, exhausting default {@code ulimit -l} and failing with
     *       {@code ENOMEM (errno -12: Cannot allocate memory)}.</li>
     *   <li><b>NVMe DMA Page Splitting:</b> For direct NVMe I/O ({@code O_DIRECT}), crossing a physical page boundary
     *       forced the Linux NVMe driver to generate 2 Physical Region Page (PRP) entries instead of a single
     *       direct PRP entry for every 4 KB block transfer, degrading hardware DMA efficiency.</li>
     *   <li><b>Memory Leaks:</b> Unmanaged {@code malloc} buffers were never explicitly freed upon ring shutdown.</li>
     * </ol>
     * <p>
     * <b>How it works:</b>
     * This method calculates the appropriate power-of-two alignment (4,096 bytes for standard pages, 512 bytes for
     * sector-aligned I/O, or 64 bytes for cache-line alignment) and allocates a single contiguous slab via
     * {@link Arena#allocate(long, long)}. Each {@code iovec} is then assigned a sliced view of this page-aligned slab.
     * All buffers fit within exact physical page boundaries, halving {@code RLIMIT_MEMLOCK} usage, eliminating PRP splits,
     * and ensuring deterministic deallocation when the enclosing {@link Arena} is closed.
     *
     * @param arena      the memory arena governing the lifecycle of the iovec array and buffer slab
     * @param bufferSize the size of each buffer in bytes
     * @param nrIovecs   the number of iovec buffers to allocate
     * @return an {@link IovecStructure} containing the native iovec array and individual buffer segments
     */
    IovecStructure allocateIovec(Arena arena, long bufferSize, long nrIovecs) {
        MemorySegment iovecArray = Iovec.allocateArray(nrIovecs, arena);
        MemorySegment[] buffers = new MemorySegment[(int) nrIovecs];
        // Enforce 4096-byte hardware page alignment for 4KB buffers (or 512-byte block alignment)
        long alignment = (bufferSize >= 4096 && (bufferSize & 4095L) == 0) ? 4096L
                : (bufferSize >= 512 && (bufferSize & 511L) == 0) ? 512L : 64L;
        MemorySegment slab = arena.allocate(Math.multiplyExact(bufferSize, nrIovecs), alignment);

        for (int i = 0; i < nrIovecs; i++) {
            MemorySegment nthIovec = Iovec.asSlice(iovecArray, i);
            MemorySegment buffer = slab.asSlice((long) i * bufferSize, bufferSize);

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

