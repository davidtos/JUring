package com.davidvlijmincx.lio.api;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;


public class Main {

    private static final int PORT    = 8080;
    private static final int BACKLOG = 128;
    // Queue depth: enough headroom for concurrent in-flight recv/send ops.
    private static final int QUEUE_DEPTH = 256;
    // Max bytes we read from a single HTTP request — we don't parse it.
    private static final int RECV_BUF_SIZE = 4096;

    private static final String RESPONSE_BODY = """
            <!DOCTYPE html>
            <html><head><title>JUring</title></head>
            <body><h1>Hello World</h1><p>Served by JUring io_uring HTTP server.</p></body>
            </html>""";

    private static final byte[] HTTP_RESPONSE = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: " + RESPONSE_BODY.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            RESPONSE_BODY
    ).getBytes(StandardCharsets.UTF_8);

    // Visible to the shutdown hook thread; the main loop reads it to decide whether to re-arm.
    private static volatile boolean running = true;

    public static void main(String[] args) {
        try (JUring ring = new JUring(QUEUE_DEPTH)) {
            int serverFd = ring.createServerSocket(PORT, BACKLOG);

            // One multishot accept SQE drives all incoming connections.
            long acceptOpId = ring.prepareMultishotAccept(serverFd);
            ring.submit();

            long finalAcceptOpId = acceptOpId;
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
                System.out.println("Server shutting down...");

                // 1. Tell the event loop not to re-arm the multishot accept.
                running = false;

                // 2. Submit a cancel SQE for the multishot accept so waitForResult() unblocks.
                //    io_uring_prep_cancel64 matches by user_data value; flags=0 cancels first match.
                ring.prepareCancel(finalAcceptOpId);
                ring.submit();

                // 3. Close the server socket so no new connections are accepted while shutting down.
                NativeDispatcher.C.close(serverFd);
            }));

            System.out.println("Server listening on http://localhost:" + PORT);

            // Track which client fd is associated with each pending recv or send operation.
            // Key: operation id returned by prepareRecv / prepareSend.
            // Value: client socket fd.
            Map<Long, Integer> opToClientFd = new HashMap<>();

            while (true) {
                Result result = ring.waitForResult();

                switch (result) {
                    case AcceptResult accept -> {
                        int clientFd = accept.fd();
                        if (clientFd < 0) {
                            // Negative result: error or cancellation of the multishot SQE.
                            // On graceful shutdown (-ECANCELED = -125) we exit; otherwise re-arm.
                            if (!running) {
                                // Cancellation was requested — exit the event loop cleanly.
                                break;
                            }
                            System.err.println("accept error (result=" + clientFd + "), re-arming multishot accept");
                            acceptOpId = ring.prepareMultishotAccept(serverFd);
                            ring.submit();
                            break;
                        }

                        // Submit recv for the new client — we drain the request but don't parse it.
                        long recvOpId = ring.prepareRecv(clientFd, RECV_BUF_SIZE);
                        opToClientFd.put(recvOpId, clientFd);
                        ring.submit();
                    }

                    case RecvResult recv -> {
                        Integer clientFd = opToClientFd.remove(recv.id());
                        // Always free the recv buffer — we don't need the request content.
                        recv.freeBuffer();

                        if (clientFd == null) {
                            // Unexpected: no mapping found; nothing we can do.
                            System.err.println("RecvResult with unknown id=" + recv.id());
                            break;
                        }

                        if (recv.bytesRead() <= 0) {
                            // Client closed connection before sending anything, or an error.
                            NativeDispatcher.C.close(clientFd);
                            break;
                        }

                        // Send the HTTP response.
                        long sendOpId = ring.prepareSend(clientFd, HTTP_RESPONSE);
                        opToClientFd.put(sendOpId, clientFd);
                        ring.submit();
                    }

                    case SendResult send -> {
                        Integer clientFd = opToClientFd.remove(send.id());

                        if (clientFd == null) {
                            System.err.println("SendResult with unknown id=" + send.id());
                            break;
                        }

                        // Done with this connection — close synchronously via libc.
                        NativeDispatcher.C.close(clientFd);
                    }

                    default -> {
                        // Ignore CloseResult (from cancel SQE CQE) and any other result types.
                    }
                }

                // Exit the event loop after the cancellation CQE for the multishot accept
                // has been processed (running was set false and accept.fd() < 0 above).
                // We use a label-less break inside the AcceptResult case, so we need a
                // second check here to propagate the exit out of the while loop.
                if (!running && result instanceof AcceptResult accept && accept.fd() < 0) {
                    break;
                }
            }

            System.out.println("Server stopped.");
        }
    }
}
