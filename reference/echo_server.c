#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <liburing.h>

#define PORT 8080
#define QUEUE_DEPTH 64
#define BUFFER_SIZE 1024

enum {
    ACCEPT,
    READ,
    WRITE,
};

typedef struct conn_info {
    int fd;
    int type;
    char buffer[BUFFER_SIZE];
    int bytes_read;
} conn_info;

int setup_listening_socket() {
    int sock_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (sock_fd < 0) {
        perror("socket");
        return -1;
    }

    int enable = 1;
    if (setsockopt(sock_fd, SOL_SOCKET, SO_REUSEADDR, &enable, sizeof(enable)) < 0) {
        perror("setsockopt");
        close(sock_fd);
        return -1;
    }

    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(PORT);

    if (bind(sock_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind");
        close(sock_fd);
        return -1;
    }

    if (listen(sock_fd, 10) < 0) {
        perror("listen");
        close(sock_fd);
        return -1;
    }

    return sock_fd;
}

void add_accept(struct io_uring *ring, int server_fd) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    conn_info *conn = malloc(sizeof(*conn));
    conn->fd = server_fd;
    conn->type = ACCEPT;

    io_uring_prep_accept(sqe, server_fd, NULL, NULL, 0);
    io_uring_sqe_set_data(sqe, conn);
}

void add_read(struct io_uring *ring, int client_fd) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    conn_info *conn = malloc(sizeof(*conn));
    conn->fd = client_fd;
    conn->type = READ;

    io_uring_prep_recv(sqe, client_fd, conn->buffer, BUFFER_SIZE, 0);
    io_uring_sqe_set_data(sqe, conn);
}

void add_write(struct io_uring *ring, int client_fd, char *buffer, int bytes) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    conn_info *conn = malloc(sizeof(*conn));
    conn->fd = client_fd;
    conn->type = WRITE;
    conn->bytes_read = bytes;
    memcpy(conn->buffer, buffer, bytes);

    io_uring_prep_send(sqe, client_fd, conn->buffer, bytes, 0);
    io_uring_sqe_set_data(sqe, conn);
}

int main() {
    struct io_uring ring;
    struct io_uring_cqe *cqe;

    // Initialize io_uring
    if (io_uring_queue_init(QUEUE_DEPTH, &ring, 0) < 0) {
        perror("io_uring_queue_init");
        return 1;
    }

    // Setup listening socket
    int server_fd = setup_listening_socket();
    if (server_fd < 0) {
        return 1;
    }

    printf("Echo server listening on port %d\n", PORT);

    // Add initial accept operation
    add_accept(&ring, server_fd);
    io_uring_submit(&ring);

    // Main event loop
    while (1) {
        int ret = io_uring_wait_cqe(&ring, &cqe);
        if (ret < 0) {
            perror("io_uring_wait_cqe");
            break;
        }

        conn_info *conn = (conn_info*)io_uring_cqe_get_data(cqe);
        int result = cqe->res;

        switch (conn->type) {
            case ACCEPT: {
                if (result >= 0) {
                    int client_fd = result;
                    printf("New client connected: fd=%d\n", client_fd);

                    // Add read operation for new client
                    add_read(&ring, client_fd);

                    // Add another accept operation
                    add_accept(&ring, server_fd);

                    io_uring_submit(&ring);
                } else {
                    perror("accept");
                }
                break;
            }

            case READ: {
                if (result > 0) {
                    printf("Received %d bytes from fd=%d\n", result, conn->fd);

                    // Echo back the data
                    add_write(&ring, conn->fd, conn->buffer, result);
                    io_uring_submit(&ring);
                } else if (result == 0) {
                    // Client disconnected
                    printf("Client disconnected: fd=%d\n", conn->fd);
                    close(conn->fd);
                } else {
                    perror("read");
                    close(conn->fd);
                }
                break;
            }

            case WRITE: {
                if (result >= 0) {
                    printf("Echoed %d bytes to fd=%d\n", result, conn->fd);

                    // Continue reading from this client
                    add_read(&ring, conn->fd);
                    io_uring_submit(&ring);
                } else {
                    perror("write");
                    close(conn->fd);
                }
                break;
            }
        }

        free(conn);
        io_uring_cqe_seen(&ring, cqe);
    }

    close(server_fd);
    io_uring_queue_exit(&ring);
    return 0;
}