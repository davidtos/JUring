package sockets.rawcalls;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static sockets.rawcalls.uring_syscalls_h.*;
import static sockets.rawcalls.uring_syscalls_h_2.SOL_SOCKET;
import static sockets.rawcalls.uring_syscalls_h_2.SO_REUSEADDR;

public class Main {

    int PORT = 8080;
    int BACKLOG = 128;
    int BUFFERSIZE = 4096;

    Arena bigArena = Arena.ofConfined();


    public static void main(String[] args) {
        Main main = new Main();
        main.eventLoop();
        main.bigArena.close();
    }

    int eventLoop(){
        MemorySegment ring = bigArena.allocate(io_uring.layout());
        MemorySegment sqe;

        return 0;
    }


    int setup_listening_socket(int port){
        int ret = 0;

        int sock_fd = uring_syscalls_h_1.socket(AF_INET(), SOCK_STREAM(), 0);

        int enable = 1;
        MemorySegment enableSegment = bigArena.allocateFrom(ValueLayout.JAVA_INT, enable);
        uring_syscalls_h_1.setsockopt(sock_fd,SOL_SOCKET(),SO_REUSEADDR(),enableSegment, Math.toIntExact(enableSegment.byteSize()));

        MemorySegment addr = bigArena.allocate(sockaddr_in.layout());

        MemorySegment inAddr = bigArena.allocate(in_addr.layout());
        in_addr.s_addr(inAddr,INADDR_ANY());

        sockaddr_in.sin_family(addr, (short) AF_INET());
        sockaddr_in.sin_port(addr, htons((short) port));
        sockaddr_in.sin_addr(addr, inAddr);

        ret = bind(sock_fd, addr, Math.toIntExact(addr.byteSize()));
        if (ret < 0) {
            System.out.println("bind failed");
        }

        ret = listen(sock_fd,BACKLOG);


        return sock_fd;
    }

    void add_accept(MemorySegment ring, int serverFd) {
        var sqe = io_uring_get_sqe(ring);
        MemorySegment info = bigArena.allocate(conn_info.layout().byteSize());
        conn_info.event_type(info,EVENT_ACCEPT());

        uring_syscalls_h.io_uring_prep_multishot_accept(sqe,serverFd,MemorySegment.NULL,MemorySegment.NULL, 0);
        io_uring_sqe_set_data(sqe,info);
    }

    void add_read(MemorySegment ring, int clientFd){
        var sqe = io_uring_get_sqe(ring);

        MemorySegment info = bigArena.allocate(conn_info.layout().byteSize());
        conn_info.event_type(info, EVENT_READ());
        conn_info.client_fd(info, clientFd);

        io_uring_prep_recv(sqe,clientFd,conn_info.buffer(info),conn_info.buffer(info).byteSize(),0);
        io_uring_sqe_set_data(sqe, info);
    }

    void add_write(MemorySegment ring, MemorySegment info){
        var sqe = io_uring_get_sqe(ring);

        conn_info.event_type(info, EVENT_WRITE());
        io_uring_prep_send(sqe,conn_info.client_fd(info),conn_info.buffer(info),conn_info.buffer(info).byteSize(),0);
        io_uring_sqe_set_data(sqe, info);
    }

}
