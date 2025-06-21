package com.davidvlijmincx.lio.api.functions;

public interface Listen {
    int listen(int sockfd, int backlog);
}