#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <liburing.h>

#define PORT 8080
#define QUEUE_DEPTH 256
#define READ_SIZE 1024

enum {
    EVENT_TYPE_ACCEPT,
    EVENT_TYPE_READ,
    EVENT_TYPE_WRITE,
};

struct conn_info {
    int fd;
    int type;
    char read_buf[READ_SIZE];
    char *write_buf;
    int write_len;
};

static const char *response = "HTTP/1.1 200 OK\r\n"
                             "Content-Type: text/html\r\n"
                             "Content-Length: 28\r\n"
                             "Connection: close\r\n"
                             "\r\n"
                             "<html>Hello, io_uring</html>";

int setup_listening_socket(int port) {
    int sock;
    struct sockaddr_in srv_addr;
    int enable = 1;

    sock = socket(PF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        perror("socket");
        return -1;
    }

    if (setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &enable, sizeof(int)) < 0) {
        perror("setsockopt");
        return -1;
    }

    memset(&srv_addr, 0, sizeof(srv_addr));
    srv_addr.sin_family = AF_INET;
    srv_addr.sin_port = htons(port);
    srv_addr.sin_addr.s_addr = htonl(INADDR_ANY);

    if (bind(sock, (const struct sockaddr *)&srv_addr, sizeof(srv_addr)) < 0) {
        perror("bind");
        return -1;
    }

    if (listen(sock, 10) < 0) {
        perror("listen");
        return -1;
    }

    return sock;
}

void add_accept(struct io_uring *ring, int server_socket, struct sockaddr_in *client_addr, socklen_t *client_len) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    struct conn_info *conn = malloc(sizeof(struct conn_info));

    conn->fd = server_socket;
    conn->type = EVENT_TYPE_ACCEPT;

    io_uring_prep_accept(sqe, server_socket, (struct sockaddr *)client_addr, client_len, 0);
    io_uring_sqe_set_data(sqe, conn);
}

void add_read(struct io_uring *ring, int client_socket) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    struct conn_info *conn = malloc(sizeof(struct conn_info));

    conn->fd = client_socket;
    conn->type = EVENT_TYPE_READ;

    io_uring_prep_recv(sqe, client_socket, conn->read_buf, READ_SIZE, 0);
    io_uring_sqe_set_data(sqe, conn);
}

void add_write(struct io_uring *ring, int client_socket) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);
    struct conn_info *conn = malloc(sizeof(struct conn_info));

    conn->fd = client_socket;
    conn->type = EVENT_TYPE_WRITE;
    conn->write_buf = (char *)response;
    conn->write_len = strlen(response);

    io_uring_prep_send(sqe, client_socket, conn->write_buf, conn->write_len, 0);
    io_uring_sqe_set_data(sqe, conn);
}

int main() {
    struct io_uring ring;
    struct io_uring_cqe *cqe;
    struct sockaddr_in client_addr;
    socklen_t client_len = sizeof(client_addr);
    int server_socket;

    // Setup listening socket
    server_socket = setup_listening_socket(PORT);
    if (server_socket < 0) {
        return 1;
    }

    // Initialize io_uring
    if (io_uring_queue_init(QUEUE_DEPTH, &ring, 0) < 0) {
        perror("io_uring_queue_init");
        return 1;
    }

    printf("HTTP server listening on port %d\n", PORT);

    // Add initial accept
    add_accept(&ring, server_socket, &client_addr, &client_len);
    io_uring_submit(&ring);

    while (1) {
        int ret = io_uring_wait_cqe(&ring, &cqe);
        if (ret < 0) {
            perror("io_uring_wait_cqe");
            return 1;
        }

        struct conn_info *conn = (struct conn_info *)io_uring_cqe_get_data(cqe);

        if (cqe->res < 0) {
            fprintf(stderr, "Async operation failed: %s\n", strerror(-cqe->res));
            io_uring_cqe_seen(&ring, cqe);

            if (conn->type != EVENT_TYPE_ACCEPT) {
                close(conn->fd);
            }
            free(conn);
            continue;
        }

        switch (conn->type) {
            case EVENT_TYPE_ACCEPT: {
                int client_socket = cqe->res;
                printf("New connection accepted (fd=%d)\n", client_socket);

                // Add new accept for next connection
                add_accept(&ring, server_socket, &client_addr, &client_len);

                // Add read for this client
                add_read(&ring, client_socket);

                io_uring_submit(&ring);
                break;
            }

            case EVENT_TYPE_READ: {
                if (cqe->res == 0) {
                    // Connection closed
                    printf("Connection closed (fd=%d)\n", conn->fd);
                    close(conn->fd);
                } else {
                    // We got data, send response
                    printf("Received %d bytes from fd=%d\n", cqe->res, conn->fd);
                    add_write(&ring, conn->fd);
                    io_uring_submit(&ring);
                }
                break;
            }

            case EVENT_TYPE_WRITE: {
                printf("Sent response to fd=%d\n", conn->fd);
                // Close connection after sending response
                close(conn->fd);
                break;
            }
        }

        free(conn);
        io_uring_cqe_seen(&ring, cqe);
    }

    close(server_socket);
    io_uring_queue_exit(&ring);
    return 0;
}