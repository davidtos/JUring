package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import sockets.rawcalls.uring_syscalls_h;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JUringRestTest {

    private static final String HOST = "192.168.1.72";
    private static final int PORT = 80;
    private static final String PATH = "/hello/";

    @Test
    void getRequestReturnsHttpResponse() {
        try (JUringRest rest = new JUringRest(32, IoUringOptions.IORING_SETUP_SINGLE_ISSUER)) {

            long socketId = rest.prepareSocket(
                    uring_syscalls_h.AF_INET(),
                    uring_syscalls_h.SOCK_STREAM(),
                    0,
                    0
            );
            rest.submit();
            Result socketResult = rest.waitForResult();
            assertInstanceOf(SocketResult.class, socketResult);
            int sockFd = ((SocketResult) socketResult).fileDescriptor();
            assertTrue(sockFd > 0);

            long connectId = rest.prepareConnect(sockFd, HOST, PORT);
            rest.submit();
            Result connectResult = rest.waitForResult();
            assertInstanceOf(ConnectResult.class, connectResult);
            assertEquals(connectId, connectResult.id());
            assertEquals(0, ((ConnectResult) connectResult).result());

            String httpRequest = "GET " + PATH + " HTTP/1.1\r\n" +
                    "Host: " + HOST + "\r\n" +
                    "Connection: close\r\n" +
                    "Accept: */*\r\n" +
                    "\r\n";
            byte[] requestBytes = httpRequest.getBytes(StandardCharsets.UTF_8);

            long sendId = rest.prepareSend(sockFd, requestBytes, 0);
            rest.submit();
            Result sendResult = rest.waitForResult();
            assertInstanceOf(SendResult.class, sendResult);
            assertEquals(sendId, sendResult.id());
            assertEquals(requestBytes.length, ((SendResult) sendResult).result());

            List<Byte> responseBytes = new ArrayList<>();
            while (true) {
                long recvId = rest.prepareRecv(sockFd, 8192, 0);
                rest.submit();
                Result recvResult = rest.waitForResult();

                if (recvResult instanceof ReadResult readResult) {
                    if (readResult.result() <= 0) {
                        readResult.freeBuffer();
                        break;
                    }

                    for (byte b : readResult.buffer().asSlice(0, readResult.result()).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)) {
                        responseBytes.add(b);
                    }
                    readResult.freeBuffer();
                }
            }

            byte[] respArray = new byte[responseBytes.size()];
            for (int i = 0; i < responseBytes.size(); i++) {
                respArray[i] = responseBytes.get(i);
            }
            String response = new String(respArray, StandardCharsets.UTF_8);

            System.out.println("response = " + response);

            assertTrue(response.startsWith("HTTP/1.0"), "Response should start with HTTP status line");
            assertTrue(response.contains("hello"));

            uring_syscalls_h.close(sockFd);
        }
    }
}
