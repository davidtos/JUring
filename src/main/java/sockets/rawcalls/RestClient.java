package sockets.rawcalls;

import com.davidvlijmincx.lio.api.NativeDispatcher;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static sockets.rawcalls.uring_syscalls_h.*;

public class RestClient {

    private StringBuilder responseBuilder = new StringBuilder();

    private static final String HOST = "192.168.1.72";
    private static final int PORT = 80;
    private static final String PATH = "/hello/";
    
    private static final int EVENT_CONNECT = 0;
    private static final int EVENT_WRITE = 1;
    private static final int EVENT_READ = 2;

    public static void main(String[] args) {
        RestClient client = new RestClient();
        client.makeRequest();
    }

    void makeRequest() {
        int res;

        MemorySegment ring = NativeDispatcher.C.malloc(io_uring.layout().byteSize());
        res = io_uring_queue_init(256, ring, 0);
        errorPrint(res, "io_uring_queue_init");

        int sockFd = createSocket();
        if (sockFd < 0) {
            System.out.println("Failed to create socket");
            return;
        }

        // Start async connect
        addConnect(ring, sockFd);
        io_uring_submit(ring);

        MemorySegment cqePtr = NativeDispatcher.C.malloc(ValueLayout.ADDRESS.byteSize());

        while (true) {
            System.out.println("Waiting for completion...");

            io_uring_wait_cqe(ring, cqePtr);

            MemorySegment realCqeAddress = cqePtr.get(ValueLayout.ADDRESS, 0);
            MemorySegment cqe = realCqeAddress.reinterpret(io_uring_cqe.layout().byteSize());

            int result = io_uring_cqe.res(cqe);

            if (result < 0) {
                System.out.println("Async Error: " + result);
                io_uring_cqe_seen(ring, cqe);
                break;
            }

            MemorySegment infoAddress = io_uring_cqe_get_data(cqe);
            MemorySegment info = infoAddress.reinterpret(conn_info.layout().byteSize());

            int eventType = conn_info.event_type(info);

            switch (eventType) {
                case EVENT_CONNECT:
                    System.out.println("Connected to " + HOST + ":" + PORT);
                    addWriteRequest(ring, sockFd);
                    io_uring_submit(ring);
                    NativeDispatcher.C.free(info);
                    break;

                case EVENT_WRITE:
                    System.out.println("HTTP request sent (" + result + " bytes)");
                    addRead(ring, sockFd);
                    io_uring_submit(ring);
                    NativeDispatcher.C.free(info);
                    break;

                case EVENT_READ:
                    if (result == 0) {
                        System.out.println("Connection closed by server");

                        // Process the complete response
                        String completeResponse = responseBuilder.toString();

                        if (completeResponse.contains("\r\n\r\n")) {
                            String[] parts = completeResponse.split("\r\n\r\n", 2);

                            // Show headers
                            System.out.println("--- Response Headers ---");
                            System.out.println(parts[0]);
                            System.out.println("------------------------");

                            // Show HTML body
                            if (parts.length > 1) {
                                System.out.println("--- HTML Body ---");
                                System.out.println(parts[1]);
                                System.out.println("----------------");
                            }
                        } else {
                            // Fallback if no proper HTTP response structure
                            System.out.println("--- Complete Response ---");
                            System.out.println(completeResponse);
                            System.out.println("------------------------");
                        }

                        NativeDispatcher.C.free(info);
                        close(sockFd);
                        io_uring_queue_exit(ring);
                        return;
                    } else {
                        System.out.println("Received " + result + " bytes");
                        MemorySegment buffer = conn_info.buffer(info);
                        byte[] responseBytes = buffer.asSlice(0, result).toArray(ValueLayout.JAVA_BYTE);
                        String responseChunk = new String(responseBytes, StandardCharsets.UTF_8);

                        // Accumulate the response
                        responseBuilder.append(responseChunk);

                        // Continue reading
                        addRead(ring, sockFd);
                        io_uring_submit(ring);
                        NativeDispatcher.C.free(info);
                    }

            }

            io_uring_cqe_seen(ring, cqe);
        }
    }

    private int createSocket() {
        return uring_syscalls_h_1.socket(AF_INET(), SOCK_STREAM(), 0);
    }

    private void addConnect(MemorySegment ring, int sockFd) {
        var sqe = io_uring_get_sqe(ring);

        MemorySegment info = NativeDispatcher.C.malloc(conn_info.layout().byteSize());
        conn_info.event_type(info, EVENT_CONNECT);
        conn_info.client_fd(info, sockFd);

        // Set up server address
        MemorySegment addr = NativeDispatcher.C.malloc(sockaddr_in.layout().byteSize());
        MemorySegment inAddr = NativeDispatcher.C.malloc(in_addr.layout().byteSize());

        // Convert HOST IP to network byte order - Fixed to use actual HOST
        String[] parts = HOST.split("\\.");
        int ipAddr = (Integer.parseInt(parts[0]) << 24) |
                (Integer.parseInt(parts[1]) << 16) |
                (Integer.parseInt(parts[2]) << 8) |
                Integer.parseInt(parts[3]);
        in_addr.s_addr(inAddr, htonl(ipAddr));

        sockaddr_in.sin_family(addr, (short) AF_INET());
        sockaddr_in.sin_port(addr, htons((short) PORT));
        sockaddr_in.sin_addr(addr, inAddr);

        io_uring_prep_connect(sqe, sockFd, addr, (int) sockaddr_in.layout().byteSize());
        io_uring_sqe_set_data(sqe, info);
    }

    private void addWriteRequest(MemorySegment ring, int sockFd) {
        var sqe = io_uring_get_sqe(ring);

        MemorySegment info = NativeDispatcher.C.malloc(conn_info.layout().byteSize());
        conn_info.event_type(info, EVENT_WRITE);
        conn_info.client_fd(info, sockFd);

        // Build HTTP GET request
        String httpRequest = "GET " + PATH + " HTTP/1.1\r\n" +
                "Host: " + HOST + "\r\n" +
                "Connection: close\r\n" +
                "Accept: */*\r\n" +
                "\r\n";

        byte[] requestBytes = httpRequest.getBytes(StandardCharsets.UTF_8);
        
        // Copy request to buffer
        MemorySegment buffer = conn_info.buffer(info);
        MemorySegment.copy(requestBytes, 0, buffer, ValueLayout.JAVA_BYTE, 0, requestBytes.length);
        conn_info.len(info, requestBytes.length);

        io_uring_prep_send(sqe, sockFd, buffer, requestBytes.length, 0);
        io_uring_sqe_set_data(sqe, info);
    }

    private void addRead(MemorySegment ring, int sockFd) {
        var sqe = io_uring_get_sqe(ring);

        MemorySegment info = NativeDispatcher.C.malloc(conn_info.layout().byteSize());
        conn_info.event_type(info, EVENT_READ);
        conn_info.client_fd(info, sockFd);

        io_uring_prep_recv(sqe, sockFd, conn_info.buffer(info), conn_info.buffer(info).byteSize(), 0);
        io_uring_sqe_set_data(sqe, info);
    }

    private void errorPrint(int res, String error) {
        if (res < 0) {
            System.out.println(error + ": " + res);
        }
    }
}
