package com.davidvlijmincx.lio.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

public class SocketServer {
    
    private static final int PORT = 8080;
    private static final int QUEUE_DEPTH = 256;
    
    private static final String HTTP_RESPONSE = 
        "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/html\r\n" +
        "Content-Length: 28\r\n" +
        "Connection: close\r\n" +
        "\r\n" +
        "<html>Hello, io_uring</html>";

    public static void main(String[] args) {
        try (JUringSocket juringSocket = new JUringSocket(QUEUE_DEPTH);
             Arena arena = Arena.ofShared()) {
            
            int serverSocket = setupListeningSocket(PORT);
            System.out.println("HTTP server listening on port " + PORT);
            
            MemorySegment clientAddr = arena.allocate(16);
            MemorySegment clientLen = arena.allocate(JAVA_INT);
            clientLen.set(JAVA_INT, 0, 16);
            
            juringSocket.prepareAccept(serverSocket, clientAddr, clientLen);
            juringSocket.submit();
            
            while (true) {
                JUringSocket.SocketResult result = juringSocket.waitForResult();
                
                if (result.getResult() < 0) {
                    System.err.println("Async operation failed: " + result.getResult());
                    continue;
                }
                
                switch (result.getType()) {
                    case ACCEPT -> {
                        int clientSocket = result.getResult();
                        System.out.println("New connection accepted (fd=" + clientSocket + ")");
                        
                        juringSocket.prepareAccept(serverSocket, clientAddr, clientLen);
                        juringSocket.prepareRecv(clientSocket);
                        juringSocket.submit();
                    }
                    
                    case RECV -> {
                        if (result.getResult() == 0) {
                            System.out.println("Connection closed (fd=" + result.getFd() + ")");
                            closeSocket(result.getFd());
                        } else {
                            System.out.println("Received " + result.getResult() + " bytes from fd=" + result.getFd());
                            juringSocket.prepareSend(result.getFd(), HTTP_RESPONSE.getBytes());
                            juringSocket.submit();
                        }
                    }
                    
                    case SEND -> {
                        System.out.println("Sent response to fd=" + result.getFd());
                        closeSocket(result.getFd());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static int setupListeningSocket(int port) {
        try {
            return LibCWrapper.createServerSocket(port);
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup listening socket", e);
        }
    }
    
    private static void closeSocket(int fd) {
        try {
            LibCWrapper.closeSocket(fd);
        } catch (Exception e) {
            System.err.println("Error closing socket: " + e.getMessage());
        }
    }
}