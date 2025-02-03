# Base image - Dockerfile.base
FROM arm64v8/ubuntu:25.04

ARG JDK_VERSION=24

# Install prerequisites
RUN apt-get update && apt-get install -y \
    wget \
    git \
    liburing-dev \
    maven \
    && rm -rf /var/lib/apt/lists/*

RUN wget "https://download.java.net/java/early_access/jdk24/33/GPL/openjdk-24-ea+33_linux-aarch64_bin.tar.gz" -O jdk.tar.gz && \
    mkdir -p /usr/lib/jvm && \
    tar -xzf jdk.tar.gz -C /usr/lib/jvm && \
    rm jdk.tar.gz

ENV JAVA_HOME=/usr/lib/jvm/jdk-${JDK_VERSION} \
    PATH=$JAVA_HOME/bin:$PATH