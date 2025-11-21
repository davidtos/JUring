#ifndef URING_SYSCALLS_H
#define URING_SYSCALLS_H

#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>

#define IOURINGINLINE extern
#include <liburing.h>

#define BUFFER_SIZE 4096

enum {
    EVENT_ACCEPT,
    EVENT_READ,
    EVENT_WRITE
};

typedef struct {
    int event_type;
    int client_fd;
    char buffer[BUFFER_SIZE];
    size_t len;
} conn_info;

#endif // URING_SYSCALLS_H
