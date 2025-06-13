package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUringSocket implements AutoCloseable {

    private final LibUringWrapper libUringWrapper;
    private final Arena arena;
    private static final int READ_SIZE = 1024;

    public JUringSocket(int queueDepth) {
        libUringWrapper = new LibUringWrapper(queueDepth);
        arena = Arena.ofShared();
    }

    public long prepareAccept(int serverSocket, MemorySegment clientAddr, MemorySegment clientLen) {
        MemorySegment sqe = libUringWrapper.getSqe();
        long id = ThreadLocalRandom.current().nextLong();

        MemorySegment connectionInfo = ConnectionInfo.createConnectionInfo(
                arena, id, serverSocket, OperationType.ACCEPT, MemorySegment.NULL, MemorySegment.NULL, 0);

        libUringWrapper.prepareAccept(sqe, serverSocket, clientAddr, clientLen);
        libUringWrapper.setUserData(sqe, connectionInfo.address());

        return id;
    }

    public long prepareRecv(int clientSocket) {
        MemorySegment sqe = libUringWrapper.getSqe();
        long id = ThreadLocalRandom.current().nextLong();

        MemorySegment readBuffer = LibCWrapper.malloc(READ_SIZE);
        MemorySegment connectionInfo = ConnectionInfo.createConnectionInfo(
                arena, id, clientSocket, OperationType.RECV, readBuffer, MemorySegment.NULL, 0);

        libUringWrapper.prepareRecv(sqe, clientSocket, readBuffer, 0);
        libUringWrapper.setUserData(sqe, connectionInfo.address());

        return id;
    }

    public long prepareSend(int clientSocket, byte[] data) {
        MemorySegment sqe = libUringWrapper.getSqe();
        long id = ThreadLocalRandom.current().nextLong();

        MemorySegment writeBuffer = LibCWrapper.malloc(data.length);
        MemorySegment.copy(data, 0, writeBuffer, JAVA_BYTE, 0, data.length);

        MemorySegment connectionInfo = ConnectionInfo.createConnectionInfo(
                arena, id, clientSocket, OperationType.SEND, MemorySegment.NULL, writeBuffer, data.length);

        libUringWrapper.prepareSend(sqe, clientSocket, writeBuffer, data.length, 0);
        libUringWrapper.setUserData(sqe, connectionInfo.address());

        return id;
    }

    public void submit() {
        libUringWrapper.submit();
    }

    public SocketResult waitForResult() {
        com.davidvlijmincx.lio.api.SocketResult result = (com.davidvlijmincx.lio.api.SocketResult) libUringWrapper.waitForResult();
        return convertToSocketResult(result);
    }

    private SocketResult convertToSocketResult(com.davidvlijmincx.lio.api.SocketResult result) {
        long address = result.getId();
        
        try {
            MemorySegment connectionInfo = MemorySegment.ofAddress(address).reinterpret(ConnectionInfo.getByteSize());
            
            long id = ConnectionInfo.getId(connectionInfo);
            int fd = ConnectionInfo.getFd(connectionInfo);
            OperationType type = ConnectionInfo.getType(connectionInfo);
            MemorySegment readBuffer = ConnectionInfo.getReadBuffer(connectionInfo);
            MemorySegment writeBuffer = ConnectionInfo.getWriteBuffer(connectionInfo);

            if (OperationType.ACCEPT.equals(type)) {
                return new SocketResult(id, type, fd, (int) result.getResult(), null, 0);
            } else if (OperationType.RECV.equals(type)) {
                byte[] data = null;
                int bytesRead = (int) result.getResult();
                if (bytesRead > 0 && !readBuffer.equals(MemorySegment.NULL)) {
                    data = readBuffer.asSlice(0, bytesRead).toArray(JAVA_BYTE);
                }
                if (!readBuffer.equals(MemorySegment.NULL)) {
                    LibCWrapper.freeBuffer(readBuffer);
                }
                return new SocketResult(id, type, fd, bytesRead, data, 0);
            } else if (OperationType.SEND.equals(type)) {
                int bytesSent = (int) result.getResult();
                if (!writeBuffer.equals(MemorySegment.NULL)) {
                    LibCWrapper.freeBuffer(writeBuffer);
                }
                return new SocketResult(id, type, fd, bytesSent, null, 0);
            }

            return new SocketResult(id, type, fd, (int) result.getResult(), null, 0);
        } catch (Exception e) {
            return new SocketResult(address, OperationType.RECV, 0, (int) result.getResult(), null, 0);
        }
    }

    @Override
    public void close() {
        libUringWrapper.close();
        arena.close();
    }

    public static class SocketResult {
        private final long id;
        private final OperationType type;
        private final int fd;
        private final int result;
        private final byte[] data;
        private final int writeLen;

        public SocketResult(long id, OperationType type, int fd, int result, byte[] data, int writeLen) {
            this.id = id;
            this.type = type;
            this.fd = fd;
            this.result = result;
            this.data = data;
            this.writeLen = writeLen;
        }

        public long getId() { return id; }
        public OperationType getType() { return type; }
        public int getFd() { return fd; }
        public int getResult() { return result; }
        public byte[] getData() { return data; }
        public int getWriteLen() { return writeLen; }
    }
}