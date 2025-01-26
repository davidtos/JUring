# Base image - Dockerfile.base
FROM arm64v8/ubuntu:25.04 AS base

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

# Development image - Dockerfile
FROM base AS dev
RUN mkdir -p /app/test_files
VOLUME /Users/david/test_files:/app/test_files
WORKDIR /app
CMD ["/opt/java/jdk-24/bin/java", "-version"]
## docker build -f Dockerfile.base --target dev -t myapp-dev .

# Test image - Dockerfile.test
FROM base AS test
RUN apt-get update && apt-get install -y \
    maven \
    && rm -rf /var/lib/apt/lists/*

RUN wget https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar -P /app/lib/

WORKDIR /app
CMD ["mvn", "verify"]

## docker build -f Dockerfile.base --target test -t myapp-test .
## docker run --rm -v $(pwd):/app -v ~/.m2:/root/.m2 myapp-test
