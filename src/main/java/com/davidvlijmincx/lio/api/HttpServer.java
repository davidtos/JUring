package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.*;

public class HttpServer {
    private static final int PORT = 8081;
    private static final int QUEUE_DEPTH = 64;
    private static final int BUFFER_SIZE = 1024;
    
    private static final String HTTP_RESPONSE =
        "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/plain\r\n" +
        "Content-Length: 12\r\n" +
        "Connection: close\r\n" +
        "\r\n" +
        "Hello World!";
    
    private final LibUringDispatcher dispatcher;
    private final Arena arena;
    private final AtomicLong operationId = new AtomicLong(0);
    private final Map<Long, Integer> operationToFd = new HashMap<>();
    
    public HttpServer() {
        this.arena = Arena.ofShared();
        this.dispatcher = NativeDispatcher.getUringInstance(QUEUE_DEPTH);
    }
    
    private int setupListeningSocket() throws Exception {
        // Socket constants
        final int AF_INET = 2;
        final int SOCK_STREAM = 1;
        final int SOL_SOCKET = 1;
        final int SO_REUSEADDR = 2;
        final int INADDR_ANY = 0;
        
        // Create socket
        int sockFd = NativeDispatcher.C.socket(AF_INET, SOCK_STREAM, 0);
        if (sockFd < 0) {
            throw new RuntimeException("Failed to create socket: " + NativeDispatcher.C.strerror(-sockFd));
        }
        
        // Set SO_REUSEADDR
        MemorySegment enable = arena.allocate(4);
        enable.set(JAVA_INT, 0, 1);
        int ret = NativeDispatcher.C.setsockopt(sockFd, SOL_SOCKET, SO_REUSEADDR, enable, 4);
        if (ret < 0) {
            NativeDispatcher.C.close(sockFd);
            throw new RuntimeException("Failed to set SO_REUSEADDR: " + NativeDispatcher.C.strerror(-ret));
        }
        
        // Create sockaddr_in structure
        MemorySegment addr = NativeDispatcher.C.createSockaddrIn(arena, (short) AF_INET, (short) PORT, INADDR_ANY);
        
        // Bind socket
        ret = NativeDispatcher.C.bind(sockFd, addr, 16);
        if (ret < 0) {
            NativeDispatcher.C.close(sockFd);
            throw new RuntimeException("Failed to bind socket: " + NativeDispatcher.C.strerror(-ret));
        }
        
        // Listen
        ret = NativeDispatcher.C.listen(sockFd, 10);
        if (ret < 0) {
            NativeDispatcher.C.close(sockFd);
            throw new RuntimeException("Failed to listen on socket: " + NativeDispatcher.C.strerror(-ret));
        }
        
        return sockFd;
    }


    private void addAccept(int serverFd) {
        MemorySegment sqe = dispatcher.getSqe();
        long id = operationId.incrementAndGet();
        
        MemorySegment userData = UserData.createUserData(id, serverFd, OperationType.ACCEPT, MemorySegment.NULL);
        operationToFd.put(id, serverFd);
        
        dispatcher.prepareAccept(sqe, serverFd);
        dispatcher.setUserData(sqe, userData.address());
    }
    
    private void addRecv(int clientFd) {
        MemorySegment sqe = dispatcher.getSqe();
        long id = operationId.incrementAndGet();
        
        MemorySegment buffer = arena.allocate(BUFFER_SIZE);
        MemorySegment userData = UserData.createUserData(id, clientFd, OperationType.RECV, buffer);
        operationToFd.put(id, clientFd);
        
        dispatcher.prepareRecv(sqe, clientFd, buffer);
        dispatcher.setUserData(sqe, userData.address());
    }
    
    private void addSend(int clientFd) {
        MemorySegment sqe = dispatcher.getSqe();
        long id = operationId.incrementAndGet();
        
        MemorySegment buffer = arena.allocateFrom(HTTP_RESPONSE);
        MemorySegment userData = UserData.createUserData(id, clientFd, OperationType.SEND, buffer);
        operationToFd.put(id, clientFd);
        
        dispatcher.prepareSend(sqe, clientFd, buffer);
        dispatcher.setUserData(sqe, userData.address());
    }
    
    public void start() throws Exception {
        int serverFd = setupListeningSocket();
        System.out.printf("HTTP server listening on http://localhost:%d%n", PORT);
        
        addAccept(serverFd);
        dispatcher.submit();
        
        while (true) {
            Result result = dispatcher.waitForResult();
            
            switch (result) {
                case AcceptResult acceptResult -> {
                    if (acceptResult.clientFd() >= 0) {
                        int clientFd = acceptResult.clientFd();
                        System.out.printf("New HTTP request: fd=%d%n", clientFd);
                        
                        addRecv(clientFd);
                        addAccept(serverFd);
                        dispatcher.submit();
                    } else {
                        System.err.println("Accept failed");
                    }
                }
                
                case RecvResult recvResult -> {
                    if (recvResult.bytesReceived() > 0) {
                        int clientFd = getFdFromResult(recvResult.id());
                        System.out.printf("Received HTTP request (%d bytes) from fd=%d%n", 
                                        recvResult.bytesReceived(), clientFd);
                        
                        addSend(clientFd);
                        dispatcher.submit();
                    } else if (recvResult.bytesReceived() == 0) {
                        int clientFd = getFdFromResult(recvResult.id());
                        System.out.printf("Client disconnected: fd=%d%n", clientFd);
                        NativeDispatcher.C.close(clientFd);
                    } else {
                        System.err.println("Recv failed");
                    }

                    recvResult.close();
                }
                
                case SendResult sendResult -> {
                    int clientFd = getFdFromResult(sendResult.id());
                    if (sendResult.bytesSent() >= 0) {
                        System.out.printf("Sent HTTP response (%d bytes) to fd=%d%n", 
                                        sendResult.bytesSent(), clientFd);
                    } else {
                        System.err.println("Send failed");
                    }
                    
                    NativeDispatcher.C.close(clientFd);
                }
                
                default -> System.err.println("Unexpected result type: " + result.getClass().getSimpleName());
            }
        }
    }
    
    private int getFdFromResult(long id) {
        return operationToFd.getOrDefault(id, -1);
    }
    
    public void stop() {
        dispatcher.close();
        arena.close();
    }
    
    public static void main(String[] args) {
        HttpServer server = new HttpServer();
        
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        
        try {
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}