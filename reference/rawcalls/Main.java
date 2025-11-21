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



        main.bigArena.close();
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

        ret = listen(sock_fd,BACKLOG);

        return sock_fd;
    }

    void add_accept(MemorySegment ring, int serverFd) {
        var sqe = io_uring_get_sqe(ring);
        bigArena.allocate(conn_info)
    }

}
