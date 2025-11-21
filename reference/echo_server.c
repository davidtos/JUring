#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <liburing.h>

#define PORT 8080
#define BACKLOG 128
#define BUFFER_SIZE 4096

// Event types to identify what operation completed
enum {
    EVENT_ACCEPT,
    EVENT_READ,
    EVENT_WRITE
};

// Data attached to each io_uring operation
typedef struct {
    int event_type;
    int client_fd;
    char buffer[BUFFER_SIZE];
    size_t len;
} conn_info;

int setup_listening_socket(int port) {
    int sock_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (sock_fd < 0) {
        perror("socket");
        exit(1);
    }

    // Allow reuse of address (helpful for quick restarts)
    int enable = 1;
    setsockopt(sock_fd, SOL_SOCKET, SO_REUSEADDR, &enable, sizeof(enable));

    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port = htons(port),
        .sin_addr.s_addr = INADDR_ANY
    };

    if (bind(sock_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind");
        exit(1);
    }

    if (listen(sock_fd, BACKLOG) < 0) {
        perror("listen");
        exit(1);
    }

    return sock_fd;
}

void add_accept(struct io_uring *ring, int server_fd) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);

    conn_info *info = malloc(sizeof(conn_info));
    info->event_type = EVENT_ACCEPT;

    // Multishot accept - automatically rearms for multiple accepts
    io_uring_prep_multishot_accept(sqe, server_fd, NULL, NULL, 0);
    io_uring_sqe_set_data(sqe, info);
}

void add_read(struct io_uring *ring, int client_fd) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);

    conn_info *info = malloc(sizeof(conn_info));
    info->event_type = EVENT_READ;
    info->client_fd = client_fd;

    io_uring_prep_recv(sqe, client_fd, info->buffer, BUFFER_SIZE, 0);
    io_uring_sqe_set_data(sqe, info);
}

void add_write(struct io_uring *ring, conn_info *info) {
    struct io_uring_sqe *sqe = io_uring_get_sqe(ring);

    info->event_type = EVENT_WRITE;

    io_uring_prep_send(sqe, info->client_fd, info->buffer, info->len, 0);
    io_uring_sqe_set_data(sqe, info);
}

int main() {
    struct io_uring ring;
    struct io_uring_cqe *cqe;

    // Initialize io_uring with 256 entries
    if (io_uring_queue_init(256, &ring, 0) < 0) {
        perror("io_uring_queue_init");
        return 1;
    }

    int server_fd = setup_listening_socket(PORT);
    printf("Echo server listening on port %d\n", PORT);

    // Start accepting connections
    add_accept(&ring, server_fd);
    io_uring_submit(&ring);

    // Main event loop
    while (1) {
        // Wait for at least one completion
        io_uring_wait_cqe(&ring, &cqe);

        conn_info *info = (conn_info*)io_uring_cqe_get_data(cqe);
        int result = cqe->res;

        if (result < 0) {
            fprintf(stderr, "Operation failed: %s\n", strerror(-result));
            free(info);
            io_uring_cqe_seen(&ring, cqe);
            continue;
        }

        switch (info->event_type) {
            case EVENT_ACCEPT: {
                // New client connected (result is the client fd)
                int client_fd = result;
                printf("New connection: fd=%d\n", client_fd);

                // Start reading from this client
                add_read(&ring, client_fd);
                io_uring_submit(&ring);

                // Don't free info - multishot accept reuses it
                break;
            }

            case EVENT_READ: {
                if (result == 0) {
                    // Client closed connection
                    printf("Connection closed: fd=%d\n", info->client_fd);
                    close(info->client_fd);
                    free(info);
                } else {
                    // Echo the data back
                    info->len = result;
                    printf("Received %d bytes from fd=%d\n", result, info->client_fd);

                    add_write(&ring, info);
                    io_uring_submit(&ring);
                }
                break;
            }

            case EVENT_WRITE: {
                printf("Sent %d bytes to fd=%d\n", result, info->client_fd);

                // Continue reading from this client
                add_read(&ring, info->client_fd);
                io_uring_submit(&ring);
                free(info);
                break;
            }
        }

        io_uring_cqe_seen(&ring, cqe);
    }

    io_uring_queue_exit(&ring);
    close(server_fd);
    return 0;
}