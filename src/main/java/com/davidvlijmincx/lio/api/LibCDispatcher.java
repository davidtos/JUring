package com.davidvlijmincx.lio.api;
import com.davidvlijmincx.lio.api.functions.*;

import java.lang.foreign.ValueLayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

record LibCDispatcher (Consumer<MemorySegment> free,
                       Open open,
                       IntConsumer close,
                       Malloc malloc,
                       Strerror strerror,
                       Calloc calloc,
                       Socket socket,
                       Bind bind,
                       Listen listen,
                       Setsockopt setsockopt) {

     void free(MemorySegment address) {
        free.accept(address);
    }

     int open(String path, int flags, int mode) {
        return open.open(MemorySegment.ofArray((path + "\0").getBytes()), flags, mode);
    }

     void close(int fd){ close.accept(fd); }

     MemorySegment malloc(long size) {
       return malloc.malloc(size).reinterpret(size);
    }

     String strerror(int errno) {
        return strerror.strerror(errno).reinterpret(Long.MAX_VALUE).getString(0);
    }

     MemorySegment calloc(long size){
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
    
    int socket(int domain, int type, int protocol) {
        return socket.socket(domain, type, protocol);
    }
    
    int bind(int sockfd, MemorySegment addr, int addrlen) {
        return bind.bind(sockfd, addr, addrlen);
    }
    
    int listen(int sockfd, int backlog) {
        return listen.listen(sockfd, backlog);
    }
    
    int setsockopt(int sockfd, int level, int optname, MemorySegment optval, int optlen) {
        return setsockopt.setsockopt(sockfd, level, optname, optval, optlen);
    }
    
    MemorySegment createSockaddrIn(Arena arena, short family, short port, int addr) {
        MemorySegment sockaddr = arena.allocate(16); // sizeof(struct sockaddr_in)
        sockaddr.set(ValueLayout.JAVA_SHORT, 0, family);      // sin_family
        sockaddr.set(ValueLayout.JAVA_SHORT, 2, Short.reverseBytes(port)); // sin_port (network byte order)
        sockaddr.set(ValueLayout.JAVA_INT, 4, addr);          // sin_addr
        return sockaddr;
    }
}

