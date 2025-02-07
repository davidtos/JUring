package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.net.InetSocketAddress;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.*;

public class JUringNetwork implements AutoCloseable {
    private final LibUringWrapper libUringWrapper;
    private static final int MAX_MESSAGE_LEN = 4096;
    private static final int SERVER_PORT = 8080;

    private static final StructLayout connectionLayout;
    private static final VarHandle fdHandle;
    private static final VarHandle typeHandle;
    private static final AddressLayout C_POINTER;

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

    public long prepareAccept(int serverSocket) {
        MemorySegment conn = createConnection(serverSocket, 0); // OP_ACCEPT = 0

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareAccept(sqe, serverSocket);
        libUringWrapper.setUserData(sqe, conn.address());

        return conn.address();
    }

    public long prepareRead(int clientSocket) {
        MemorySegment conn = createConnection(clientSocket, 1); // OP_READ = 1
        MemorySegment buffer = LibCWrapper.malloc(MAX_MESSAGE_LEN);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareRecv(sqe, clientSocket, buffer, MAX_MESSAGE_LEN);
        libUringWrapper.setUserData(sqe, conn.address());

        return conn.address();
    }

    public long prepareWrite(int clientSocket, byte[] response) {
        MemorySegment conn = createConnection(clientSocket, 2); // OP_WRITE = 2
        MemorySegment buffer = LibCWrapper.malloc(response.length);
        MemorySegment.copy(response, 0, buffer, JAVA_BYTE, 0, response.length);

        MemorySegment sqe = libUringWrapper.getSqe();
        libUringWrapper.prepareSend(sqe, clientSocket, buffer, response.length);
        libUringWrapper.setUserData(sqe, conn.address());

        return conn.address();
    }

    private MemorySegment createConnection(int fd, int type) {
        MemorySegment conn = LibCWrapper.malloc(connectionLayout.byteSize());
        fdHandle.set(conn, 0L, fd);
        typeHandle.set(conn, 0L, type);
        return conn;
    }

    public void submit() {
        libUringWrapper.submit();
    }

    public Optional<NetworkResult> peekForResult() {
        Optional<Cqe> cqe = libUringWrapper.peekForResult();
        return cqe.map(this::getResultFromCqe);
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

        libUringWrapper.freeMemory(conn);

        return new NetworkResult(fd, type, cqe.result());
    }

    public long writeHttpResponse(int clientSocket, HttpResponse response) {
        return prepareWrite(clientSocket, response.toBytes());
    }

    @Override
    public void close() {
        libUringWrapper.close();
    }
}