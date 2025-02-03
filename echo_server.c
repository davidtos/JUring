#include <liburing.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>

#define QUEUE_DEPTH 256
#define MAX_MESSAGE_LEN 2048
#define SERVER_PORT 8888

enum { OP_ACCEPT, OP_READ, OP_WRITE };

struct connection_data {
    int fd;
    char buffer[MAX_MESSAGE_LEN];
    int type;
};

int setup_listener() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        perror("socket");
        exit(1);
    }

    struct sockaddr_in saddr = {
        .sin_family = AF_INET,
        .sin_addr.s_addr = htonl(INADDR_ANY),
        .sin_port = htons(SERVER_PORT)
    };

    int opt = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    if (bind(sock, (struct sockaddr *)&saddr, sizeof(saddr)) < 0) {
        perror("bind");
        exit(1);
    }

    if (listen(sock, SOMAXCONN) < 0) {
        perror("listen");
        exit(1);
    }

    return sock;
}

void add_accept_request(struct io_uring *ring, int server_socket) {
    struct connection_data *conn = malloc(sizeof(*conn));
    conn->type = OP_ACCEPT;

    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    io_uring_prep_accept(sqe, server_socket, NULL, NULL, 0);
    io_uring_sqe_set_data(sqe, conn);
}

void add_read_request(struct io_uring *ring, struct connection_data *conn) {
    conn->type = OP_READ;
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    io_uring_prep_recv(sqe, conn->fd, conn->buffer, MAX_MESSAGE_LEN, 0);
    io_uring_sqe_set_data(sqe, conn);
}

void add_write_request(struct io_uring *ring, struct connection_data *conn, size_t len) {
    conn->type = OP_WRITE;
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    io_uring_prep_send(sqe, conn->fd, conn->buffer, len, 0);
    io_uring_sqe_set_data(sqe, conn);
}

int main() {
    struct io_uring ring;
    io_uring_queue_init(QUEUE_DEPTH, &ring, 0);

    int server_socket = setup_listener();
    printf("Echo server listening on port %d\n", SERVER_PORT);

    add_accept_request(&ring, server_socket);

    while (1) {
        io_uring_submit(&ring);

        struct io_uring_cqe *cqe;
        io_uring_wait_cqe(&ring, &cqe);

        struct connection_data *conn = io_uring_cqe_get_data(cqe);
        int res = cqe->res;

        if (res >= 0) {
            switch (conn->type) {
                case OP_ACCEPT:
                    add_accept_request(&ring, server_socket);
                    conn->fd = res;
                    add_read_request(&ring, conn);
                    break;

                case OP_READ:
                    if (res == 0) {
                        close(conn->fd);
                        free(conn);
                    } else {
                        add_write_request(&ring, conn, res);
                    }
                    break;

                case OP_WRITE:
                    add_read_request(&ring, conn);
                    break;
            }
        } else {
            if (conn->type != OP_ACCEPT) {
                close(conn->fd);
            }
            free(conn);
        }

        io_uring_cqe_seen(&ring, cqe);
    }

    io_uring_queue_exit(&ring);
    close(server_socket);
    return 0;
}