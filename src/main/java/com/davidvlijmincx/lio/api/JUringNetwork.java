package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import java.net.InetSocketAddress;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUringNetwork implements AutoCloseable {
    private final LibUringWrapper libUringWrapper;
    private static final int MAX_MESSAGE_LEN = 4096;
    private static final int SERVER_PORT = 8080;

    private static final StructLayout connectionLayout;
    private static final VarHandle fdHandle;
    private static final VarHandle typeHandle;
    private static final AddressLayout C_POINTER;
    private static final VarHandle bufferHandle;

    static {
        C_POINTER = ValueLayout.ADDRESS
                .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE));

        connectionLayout = MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("fd"),
                ValueLayout.JAVA_INT.withName("type"),
                C_POINTER.withName("buffer"),
                C_POINTER.withName("response"),
                ValueLayout.JAVA_LONG.withName("response_len")
        ).withName("connection");

        fdHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("fd"));
        typeHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("type"));
        bufferHandle = connectionLayout.varHandle(MemoryLayout.PathElement.groupElement("buffer"));
    }

    public JUringNetwork(int queueDepth) {
        libUringWrapper = new LibUringWrapper(queueDepth);
    }

    int setupListener() {
        try {
            int sock = LibCWrapper.socket(2, 1, 0); // AF_INET, SOCK_STREAM
            if (sock < 0) {
                throw new RuntimeException("Failed to create socket");
            }

            InetSocketAddress addr = new InetSocketAddress("0.0.0.0", SERVER_PORT);
            LibCWrapper.bind(sock, addr);
            LibCWrapper.listen(sock, 128);

            return sock;
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup listener", e);
        }
    }

    public void prepareAccept(int serverSocket) {
        MemorySegment sqe = libUringWrapper.getSqe();
        MemorySegment conn = createConnection(serverSocket, 0, MemorySegment.NULL); // OP_ACCEPT = 0
        libUringWrapper.prepareAccept(sqe, serverSocket);
        libUringWrapper.setUserData(sqe, conn.address());
    }

    public long prepareRead(int clientSocket) {
        MemorySegment buffer = LibCWrapper.malloc(MAX_MESSAGE_LEN);
        MemorySegment conn = createConnection(clientSocket, 1, buffer); // OP_READ = 1

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareRecv(sqe, clientSocket, buffer, MAX_MESSAGE_LEN);
        libUringWrapper.setUserData(sqe, conn.address());

        return conn.address();
    }

    public long prepareWrite(int clientSocket, byte[] response) {
        MemorySegment buffer = LibCWrapper.malloc(response.length);
        MemorySegment conn = createConnection(clientSocket, 2, buffer); // OP_WRITE = 2
        MemorySegment.copy(response, 0, buffer, JAVA_BYTE, 0, response.length);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareSend(sqe, clientSocket, buffer, response.length);
        libUringWrapper.setUserData(sqe, conn.address());

        return conn.address();
    }

    private MemorySegment createConnection(int fd, int type, MemorySegment buffer) {
        MemorySegment conn = LibCWrapper.malloc(connectionLayout.byteSize());
        fdHandle.set(conn, 0L, fd);
        typeHandle.set(conn, 0L, type);
        bufferHandle.set(conn, 0L, buffer);
        return conn;
    }

    public void submit() {
        libUringWrapper.submit();
    }


    public NetworkResult waitForResult() {
        Cqe cqe = libUringWrapper.waitForResult();
        return getResultFromCqe(cqe);
    }

    private NetworkResult getResultFromCqe(Cqe cqe) {
        long address = cqe.UserData();
        MemorySegment conn = MemorySegment.ofAddress(address)
                .reinterpret(connectionLayout.byteSize());

        int fd = (int) fdHandle.get(conn, 0L);
        int type = (int) typeHandle.get(conn, 0L);
        MemorySegment buffer = (MemorySegment) bufferHandle.get(conn, 0L);

        libUringWrapper.freeMemory(conn);

        System.out.println("type = " + type);

        if (type == 2) {
            LibCWrapper.freeBuffer(buffer);
            LibCWrapper.closeFile(fd);
        }

        libUringWrapper.seen(cqe.cqePointer());
        return new NetworkResult(fd, type, cqe.result(), buffer);
    }

    @Override
    public void close() {
        libUringWrapper.close();
    }
}