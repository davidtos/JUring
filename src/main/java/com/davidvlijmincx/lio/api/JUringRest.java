package com.davidvlijmincx.lio.api;

import sockets.rawcalls.in_addr;
import sockets.rawcalls.sockaddr_in;
import sockets.rawcalls.uring_syscalls_h;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUringRest implements AutoCloseable {

    private final LibUringDispatcher ioUring;
    private final List<MemorySegment> allocatedAddrs = new ArrayList<>();

    public JUringRest(int queueDepth, IoUringOptions... ioUringFlags) {
        this.ioUring = NativeDispatcher.getUringInstance(queueDepth, ioUringFlags);
    }

    public long prepareSocket(int domain, int type, int protocol, int flags, SqeOptions... sqeOptions) {
        long id = ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, -1, OperationType.SOCKET, MemorySegment.NULL);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareSocket(sqe, domain, type, protocol, flags);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareConnect(int sockFd, String ipv4, int port, SqeOptions... sqeOptions) {
        MemorySegment addr = NativeDispatcher.C.malloc(sockaddr_in.layout().byteSize());
        MemorySegment inAddr = NativeDispatcher.C.malloc(in_addr.layout().byteSize());

        int ip = ipv4ToInt(ipv4);
        in_addr.s_addr(inAddr, uring_syscalls_h.htonl(ip));

        sockaddr_in.sin_family(addr, (short) sockets.rawcalls.uring_syscalls_h.AF_INET());
        sockaddr_in.sin_port(addr, uring_syscalls_h.htons((short) port));
        sockaddr_in.sin_addr(addr, inAddr);

        allocatedAddrs.add(addr);
        allocatedAddrs.add(inAddr);

        long id = addr.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, sockFd, OperationType.CONNECT, addr);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareConnect(sqe, sockFd, addr, (int) sockaddr_in.layout().byteSize());
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareSend(int sockFd, byte[] payload, int flags, SqeOptions... sqeOptions) {
        MemorySegment buffer = NativeDispatcher.C.alloc(payload.length);
        MemorySegment.copy(payload, 0, buffer, JAVA_BYTE, 0, payload.length);

        long id = buffer.address() + ThreadLocalRandom.current().nextLong();
        MemorySegment userData = UserData.createUserData(id, sockFd, OperationType.SEND, buffer);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareSend(sqe, sockFd, buffer, payload.length, flags);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public long prepareRecv(int sockFd, int maxBytes, int flags, SqeOptions... sqeOptions) {
        MemorySegment buffer = NativeDispatcher.C.malloc(maxBytes);

        long id = buffer.address();
        MemorySegment userData = UserData.createUserData(id, sockFd, OperationType.RECV, buffer);

        MemorySegment sqe = getSqe(sqeOptions);
        ioUring.prepareRecv(sqe, sockFd, buffer, maxBytes, flags);
        ioUring.setUserData(sqe, userData.address());

        return id;
    }

    public void submit() {
        ioUring.submit();
    }

    public Result waitForResult() {
        return ioUring.waitForResult();
    }

    public List<Result> waitForBatchResult(int batchSize) {
        return ioUring.waitForBatchResult(batchSize);
    }

    public List<Result> peekForBatchResult(int batchSize) {
        return ioUring.peekForBatchResult(batchSize);
    }

    private MemorySegment getSqe(SqeOptions[] sqeOptions) {
        MemorySegment sqe = ioUring.getSqe();
        if (sqe != null) {
            ioUring.setSqeFlag(sqe, sqeOptions);
        }
        return sqe;
    }

    private int ipv4ToInt(String ipv4) {
        String[] parts = ipv4.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + ipv4);
        }
        return (Integer.parseInt(parts[0]) << 24)
                | (Integer.parseInt(parts[1]) << 16)
                | (Integer.parseInt(parts[2]) << 8)
                | Integer.parseInt(parts[3]);
    }

    @Override
    public void close() {
        allocatedAddrs.forEach(NativeDispatcher.C::free);
        ioUring.close();
    }
}
