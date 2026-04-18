package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class SocketTest {

    @Test
    void echoServer() throws Exception {
        final int port = 19201;
        final String message = "Hello from JUring!";
        final byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverReady = new CountDownLatch(1);
        CountDownLatch serverDone = new CountDownLatch(1);

        // --- Server thread ---
        Thread serverThread = Thread.ofPlatform().start(() -> {
            try (JUring ring = new JUring(32, IORING_SETUP_SINGLE_ISSUER)) {
                int serverFd = ring.createServerSocket(port, 128);
                serverReady.countDown();

                try {
                    // Accept one connection
                    ring.prepareAccept(serverFd);
                    ring.submit();
                    Result acceptResult = ring.waitForResult();

                    assertInstanceOf(AcceptResult.class, acceptResult,
                            "Expected AcceptResult but got: " + acceptResult.getClass().getSimpleName());
                    int clientFd = ((AcceptResult) acceptResult).fd();
                    assertTrue(clientFd >= 0, "Accept returned error: " + clientFd);

                    // Recv the message
                    ring.prepareRecv(clientFd, messageBytes.length + 16);
                    ring.submit();
                    Result recvResult = ring.waitForResult();

                    assertInstanceOf(RecvResult.class, recvResult);
                    RecvResult recv = (RecvResult) recvResult;
                    assertTrue(recv.bytesRead() > 0, "Recv returned: " + recv.bytesRead());

                    // Echo back exactly what we received
                    int bytesRead = (int) recv.bytesRead();
                    byte[] echoBuf = recv.buffer().asSlice(0, bytesRead).toArray(JAVA_BYTE);
                    recv.freeBuffer();

                    ring.prepareSend(clientFd, echoBuf);
                    ring.submit();
                    Result sendResult = ring.waitForResult();

                    assertInstanceOf(SendResult.class, sendResult);
                    SendResult send = (SendResult) sendResult;
                    assertTrue(send.bytesSent() > 0, "Send returned: " + send.bytesSent());

                    NativeDispatcher.C.close(clientFd);
                } finally {
                    NativeDispatcher.C.close(serverFd);
                    serverDone.countDown();
                }
            } catch (Throwable t) {
                serverError.set(t);
                serverReady.countDown();
                serverDone.countDown();
            }
        });

        assertTrue(serverReady.await(5, TimeUnit.SECONDS), "Server did not start in time");
        assertNull(serverError.get(), "Server setup failed: " + serverError.get());

        // --- Client: connect, send, recv using io_uring ---
        try (JUring clientRing = new JUring(16, IORING_SETUP_SINGLE_ISSUER)) {
            int clientFd = clientRing.createClientSocket();

            try {
                // Connect
                clientRing.prepareConnect(clientFd, "127.0.0.1", port);
                clientRing.submit();
                Result connectResult = clientRing.waitForResult();

                assertInstanceOf(ConnectResult.class, connectResult);
                ConnectResult connect = (ConnectResult) connectResult;
                assertEquals(0, connect.result(), "Connect failed with errno: " + (-connect.result()));

                // Send
                clientRing.prepareSend(clientFd, messageBytes);
                clientRing.submit();
                Result sendResult = clientRing.waitForResult();

                assertInstanceOf(SendResult.class, sendResult);
                assertTrue(((SendResult) sendResult).bytesSent() > 0);

                // Recv echo
                clientRing.prepareRecv(clientFd, messageBytes.length + 16);
                clientRing.submit();
                Result recvResult = clientRing.waitForResult();

                assertInstanceOf(RecvResult.class, recvResult);
                RecvResult recv = (RecvResult) recvResult;
                assertTrue(recv.bytesRead() > 0);

                int bytesRead = (int) recv.bytesRead();
                byte[] echoedBytes = recv.buffer().asSlice(0, bytesRead).toArray(JAVA_BYTE);
                recv.freeBuffer();
                String echoed = new String(echoedBytes, StandardCharsets.UTF_8);

                assertEquals(message, echoed, "Echo mismatch");
            } finally {
                NativeDispatcher.C.close(clientFd);
            }
        }

        assertTrue(serverDone.await(5, TimeUnit.SECONDS), "Server did not finish in time");
        serverThread.join(5000);
        assertNull(serverError.get(), "Server error: " + serverError.get());
    }

    @Test
    void httpServer() throws Exception {
        final int port = 19202;
        final String responseBody = "<html><body><h1>Hello World</h1></body></html>";
        final String httpResponse =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + responseBody.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                responseBody;
        final byte[] responseBytes = httpResponse.getBytes(StandardCharsets.UTF_8);

        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverReady = new CountDownLatch(1);
        CountDownLatch serverDone = new CountDownLatch(1);

        Thread serverThread = Thread.ofPlatform().start(() -> {
            try (JUring ring = new JUring(32, IORING_SETUP_SINGLE_ISSUER)) {
                int serverFd = ring.createServerSocket(port, 128);
                serverReady.countDown();

                try {
                    // Accept one connection
                    ring.prepareAccept(serverFd);
                    ring.submit();
                    Result acceptResult = ring.waitForResult();

                    assertInstanceOf(AcceptResult.class, acceptResult);
                    int clientFd = ((AcceptResult) acceptResult).fd();
                    assertTrue(clientFd >= 0, "Accept returned error: " + clientFd);

                    // Read the HTTP request (we don't validate it, just drain it)
                    ring.prepareRecv(clientFd, 4096);
                    ring.submit();
                    Result recvResult = ring.waitForResult();

                    assertInstanceOf(RecvResult.class, recvResult);
                    RecvResult recv = (RecvResult) recvResult;
                    // Free the request buffer — we don't need the content
                    recv.freeBuffer();

                    // Send the HTTP response
                    ring.prepareSend(clientFd, responseBytes);
                    ring.submit();
                    Result sendResult = ring.waitForResult();

                    assertInstanceOf(SendResult.class, sendResult);
                    assertTrue(((SendResult) sendResult).bytesSent() > 0);

                    NativeDispatcher.C.close(clientFd);
                } finally {
                    NativeDispatcher.C.close(serverFd);
                    serverDone.countDown();
                }
            } catch (Throwable t) {
                serverError.set(t);
                serverReady.countDown();
                serverDone.countDown();
                assertNull(serverError.get(), "HTTP server setup failed: " + serverError.get());
            }
        });

        assertTrue(serverReady.await(5, TimeUnit.SECONDS), "HTTP server did not start in time");

        // --- Verify with a raw Socket to avoid HttpClient thread-pool/selector stalls ---
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            String httpRequest = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            out.write(httpRequest.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("Hello World"),
                    "Expected 'Hello World' in response, got: " + response);
        }


        assertTrue(serverDone.await(5, TimeUnit.SECONDS), "HTTP server did not finish in time");
        serverThread.join(5000);
        assertNull(serverError.get(), "HTTP server error: " + serverError.get());
    }

    @Test
    void connectTest() throws Exception {
        final int port = 19203;
        final String greeting = "WELCOME";
        final byte[] greetingBytes = greeting.getBytes(StandardCharsets.UTF_8);

        CountDownLatch serverReady = new CountDownLatch(1);
        CountDownLatch serverDone = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        // --- Simple server: accept, send a greeting, close ---
        Thread serverThread = Thread.ofPlatform().start(() -> {
            try (JUring ring = new JUring(16, IORING_SETUP_SINGLE_ISSUER)) {
                int serverFd = ring.createServerSocket(port, 128);
                serverReady.countDown();

                try {
                    ring.prepareAccept(serverFd);
                    ring.submit();
                    Result acceptResult = ring.waitForResult();

                    assertInstanceOf(AcceptResult.class, acceptResult);
                    int clientFd = ((AcceptResult) acceptResult).fd();
                    assertTrue(clientFd >= 0);

                    ring.prepareSend(clientFd, greetingBytes);
                    ring.submit();
                    ring.waitForResult();

                    NativeDispatcher.C.close(clientFd);
                } finally {
                    NativeDispatcher.C.close(serverFd);
                    serverDone.countDown();
                }
            } catch (Throwable t) {
                serverError.set(t);
                serverReady.countDown();
                serverDone.countDown();
            }
        });

        assertTrue(serverReady.await(5, TimeUnit.SECONDS));
        assertNull(serverError.get());

        // --- Client: connect and receive greeting ---
        try (JUring clientRing = new JUring(16, IORING_SETUP_SINGLE_ISSUER)) {
            int clientFd = clientRing.createClientSocket();

            try {
                clientRing.prepareConnect(clientFd, "127.0.0.1", port);
                clientRing.submit();
                Result connectResult = clientRing.waitForResult();

                assertInstanceOf(ConnectResult.class, connectResult,
                        "Expected ConnectResult, got: " + connectResult);
                ConnectResult connect = (ConnectResult) connectResult;
                assertEquals(0, connect.result(),
                        "Connect failed with errno " + (-connect.result()));

                // Receive the greeting
                clientRing.prepareRecv(clientFd, 64);
                clientRing.submit();
                Result recvResult = clientRing.waitForResult();

                assertInstanceOf(RecvResult.class, recvResult);
                RecvResult recv = (RecvResult) recvResult;
                assertTrue(recv.bytesRead() > 0);

                int bytesRead = (int) recv.bytesRead();
                String received = new String(recv.buffer().asSlice(0, bytesRead).toArray(JAVA_BYTE),
                        StandardCharsets.UTF_8);
                recv.freeBuffer();

                assertEquals(greeting, received);
            } finally {
                NativeDispatcher.C.close(clientFd);
            }
        }

        assertTrue(serverDone.await(5, TimeUnit.SECONDS));
        serverThread.join(5000);
        assertNull(serverError.get());
    }

    @Test
    void multishotAccept() throws Exception {
        final int port = 19204;
        final int numClients = 3;

        CountDownLatch serverReady = new CountDownLatch(1);
        CountDownLatch serverDone = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        Thread serverThread = Thread.ofPlatform().start(() -> {
            try (JUring ring = new JUring(32, IORING_SETUP_SINGLE_ISSUER)) {
                int serverFd = ring.createServerSocket(port, 128);
                serverReady.countDown();

                try {
                    // Submit ONE multishot accept — it will keep producing CQEs
                    ring.prepareMultishotAccept(serverFd);
                    ring.submit();

                    for (int i = 0; i < numClients; i++) {
                        // Each waitForResult returns an AcceptResult for one connection
                        Result result = ring.waitForResult();

                        assertInstanceOf(AcceptResult.class, result,
                                "Expected AcceptResult, got: " + result.getClass().getSimpleName());
                        int clientFd = ((AcceptResult) result).fd();
                        assertTrue(clientFd >= 0, "Accept returned error: " + clientFd);

                        // Close the accepted client immediately (we just verify accept works)
                        NativeDispatcher.C.close(clientFd);
                    }

                    // Cancel the multishot accept by closing the server fd.
                    // The cancellation CQE (negative result) will arrive; ignore it.
                } finally {
                    NativeDispatcher.C.close(serverFd);
                    serverDone.countDown();
                }
            } catch (Throwable t) {
                serverError.set(t);
                serverReady.countDown();
                serverDone.countDown();
            }
        });

        assertTrue(serverReady.await(5, TimeUnit.SECONDS), "Server did not start in time");
        assertNull(serverError.get(), "Server setup failed");

        // Connect numClients clients sequentially
        for (int i = 0; i < numClients; i++) {
            try (JUring clientRing = new JUring(8, IORING_SETUP_SINGLE_ISSUER)) {
                int clientFd = clientRing.createClientSocket();
                try {
                    clientRing.prepareConnect(clientFd, "127.0.0.1", port);
                    clientRing.submit();
                    Result connectResult = clientRing.waitForResult();

                    assertInstanceOf(ConnectResult.class, connectResult);
                    assertEquals(0, ((ConnectResult) connectResult).result(),
                            "Client " + i + " connect failed");
                } finally {
                    NativeDispatcher.C.close(clientFd);
                }
            }
        }

        assertTrue(serverDone.await(5, TimeUnit.SECONDS), "Server did not finish in time");
        serverThread.join(5000);
        assertNull(serverError.get(), "Server error: " + serverError.get());
    }
}
