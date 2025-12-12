package sockets.rawcalls;

import com.davidvlijmincx.lio.api.NativeDispatcher;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static sockets.rawcalls.uring_syscalls_h.*;
import static sockets.rawcalls.uring_syscalls_h_2.SOL_SOCKET;
import static sockets.rawcalls.uring_syscalls_h_2.SO_REUSEADDR;

public class Main {

    int PORT = 8080;
    int BACKLOG = 128;

    public static void main(String[] args) {
        Main main = new Main();
        main.eventLoop();
    }

    int eventLoop(){
        int res = 0;

        MemorySegment ring = NativeDispatcher.C.malloc(io_uring.layout().byteSize());
        MemorySegment cqe;

        res = io_uring_queue_init(256,ring,0);
        errorPrint(res,"io_uring_queue_init");

        int serverFd = setup_listening_socket(PORT);
        System.out.println("Echo server listening on port = " + PORT);

        add_accept(ring,serverFd);
        io_uring_submit(ring);

        // 1. Allocate a 'holder' for the pointer (size of an address, usually 8 bytes)
        MemorySegment cqePtr = NativeDispatcher.C.malloc(ValueLayout.ADDRESS.byteSize());

        while(true) {
            System.out.println("waiting for completion...");

            // 2. Pass the POINTER to the function
            // C signature: io_uring_wait_cqe(ring, struct io_uring_cqe **cqe_ptr)
            io_uring_wait_cqe(ring, cqePtr);

            // 3. DEREFERENCE: Get the address stored inside your holder
            MemorySegment realCqeAddress = cqePtr.get(ValueLayout.ADDRESS, 0);

            // 4. REINTERPRET: Tell Java this address is actually a CQE struct of size 16
            cqe = realCqeAddress.reinterpret(io_uring_cqe.layout().byteSize());

            // 5. NOW you can read the fields safely from the Real CQE
            int result = io_uring_cqe.res(cqe);
            System.out.println("result = " + result);

            // Check for errors
            if (result < 0) {
                System.out.println("Async Error: " + result);
                io_uring_cqe_seen(ring, cqe);
                continue;
            }

            // 6. Get the user_data (your conn_info struct)
            // Note: get_data returns a raw pointer, we must reinterpret it to our struct size
            MemorySegment infoAddress = io_uring_cqe_get_data(cqe);
            MemorySegment info = infoAddress.reinterpret(conn_info.layout().byteSize());

            // 7. Read your event type
            int eventType = conn_info.event_type(info);
            System.out.println("Correct Event Type: " + eventType);

            switch (eventType){
                case 0: // ACCEPT
                    int clientFd = result;
                    System.out.println("clientFd = " + clientFd);

                    add_read(ring, clientFd);
                    io_uring_submit(ring);
                    break;
                case 1: // READ
                    if(result == 0){
                        System.out.println("Connection closed: " + conn_info.client_fd(info));
                        close(conn_info.client_fd(info));
                        NativeDispatcher.C.free(info);

                        // diffi - close here?
                        io_uring_queue_exit(ring);
                        close(serverFd);
                        return 0;
                    } else {
                        conn_info.len(info, result);
                        System.out.println("Received "+result+" bytes from fd " + conn_info.client_fd(info));
                        add_write(ring, info);
                        io_uring_submit(ring);
                    }
                    break;
                case 2: // WRITE
                    System.out.println("Sent " + result + " bytes to fd " + conn_info.client_fd(info));

                    add_read(ring, conn_info.client_fd(info));
                    io_uring_submit(ring);
                    NativeDispatcher.C.free(info);
                    break;
            }

            io_uring_cqe_seen(ring, cqe);

        }

    }
    private void errorPrint(int res, String error){
        if (res < 0) {
            System.out.println(error + ": " + res);
        }
    }


    int setup_listening_socket(int port){
        int ret = 0;

        int sock_fd = uring_syscalls_h_1.socket(AF_INET(), SOCK_STREAM(), 0);

        int enable = 1;
        MemorySegment enableSegment = NativeDispatcher.C.malloc(JAVA_INT.byteSize());
        enableSegment.set(JAVA_INT, 0,enable);

        uring_syscalls_h_1.setsockopt(sock_fd,SOL_SOCKET(),SO_REUSEADDR(),enableSegment, Math.toIntExact(enableSegment.byteSize()));

        MemorySegment addr = NativeDispatcher.C.malloc(sockaddr_in.layout().byteSize());

        MemorySegment inAddr = NativeDispatcher.C.malloc(in_addr.layout().byteSize());
        in_addr.s_addr(inAddr,INADDR_ANY());

        sockaddr_in.sin_family(addr, (short) AF_INET());
        sockaddr_in.sin_port(addr, htons((short) port));
        sockaddr_in.sin_addr(addr, inAddr);

        ret = bind(sock_fd, addr, Math.toIntExact(addr.byteSize()));
        if (ret < 0) {
            System.out.println("bind failed");
        }

        ret = listen(sock_fd,BACKLOG);
        errorPrint(ret,"listen");


        return sock_fd;
    }

    void add_accept(MemorySegment ring, int serverFd) {
        var sqe = io_uring_get_sqe(ring);
        MemorySegment info = NativeDispatcher.C.malloc(conn_info.layout().byteSize());
        conn_info.event_type(info,EVENT_ACCEPT());

        uring_syscalls_h.io_uring_prep_multishot_accept(sqe,serverFd,MemorySegment.NULL,MemorySegment.NULL, 0);
        io_uring_sqe_set_data(sqe,info);
    }

    void add_read(MemorySegment ring, int clientFd){
        var sqe = io_uring_get_sqe(ring);

        MemorySegment info = NativeDispatcher.C.malloc(conn_info.layout().byteSize());
        conn_info.event_type(info, EVENT_READ());
        conn_info.client_fd(info, clientFd);

        io_uring_prep_recv(sqe,clientFd,conn_info.buffer(info),conn_info.buffer(info).byteSize(),0);
        io_uring_sqe_set_data(sqe, info);
    }

    void add_write(MemorySegment ring, MemorySegment info){
        var sqe = io_uring_get_sqe(ring);

        conn_info.event_type(info, EVENT_WRITE());
        io_uring_prep_send(sqe,conn_info.client_fd(info),conn_info.buffer(info),conn_info.len(info),0);
        io_uring_sqe_set_data(sqe, info);
    }

}
