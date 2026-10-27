package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof tests verifying that {@link JUringBlocking} supports hundreds of concurrent Virtual Threads
 * with {@code IORING_SETUP_SINGLE_ISSUER}, registered buffers, and sockets without race conditions
 * or single-issuer kernel errors.
 */
class Phase2VirtualThreadProofTest {

    @Test
    void proof1_concurrentVirtualThreadsWithSingleIssuerSucceeds() throws Exception {
        try (JUringBlocking blocking = new JUringBlocking(128, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor(Path.of("src/test/resources/read_file"), READ);
             Arena arena = Arena.ofShared();
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {

            int tasks = 200;
            CyclicBarrier barrier = new CyclicBarrier(tasks);
            List<Future<String>> results = new ArrayList<>(tasks);

            for (int i = 0; i < tasks; i++) {
                MemorySegment buf = arena.allocate(16);
                results.add(vts.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    long bytesRead = blocking.read(fd, buf, 13, 0);
                    assertEquals(13L, bytesRead);
                    return new String(buf.asSlice(0, bytesRead).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
                }));
            }

            for (Future<String> f : results) {
                assertEquals("Hello, World!", f.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void proof2_concurrentVirtualThreadsWithRegisteredFilesAndFixedBuffersSucceeds() throws Exception {
        try (JUringBlocking blocking = new JUringBlocking(64, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor(Path.of("src/test/resources/read_file"), READ);
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {

            blocking.registerFiles(fd);
            blocking.registerBuffers(32, 32);

            int tasks = 256;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>(tasks);

            for (int i = 0; i < tasks; i++) {
                results.add(vts.submit(() -> {
                    start.await();
                    int bufIdx;
                    while ((bufIdx = blocking.checkOutBuffer()) < 0) {
                        LockSupport.parkNanos(50_000L);
                    }
                    try {
                        long bytesRead = blocking.readFixed(0, 13, 0, bufIdx);
                        assertEquals(13L, bytesRead);
                        MemorySegment seg = blocking.getRegisteredBuffer(bufIdx);
                        return new String(seg.asSlice(0, bytesRead).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
                    } finally {
                        blocking.checkInBuffer(bufIdx);
                    }
                }));
            }
            start.countDown();

            for (Future<String> f : results) {
                assertEquals("Hello, World!", f.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void proof3_virtualThreadSocketEchoServerAndClients() throws Exception {
        int port = 19876;
        int clients = 16;

        try (JUringBlocking blocking = new JUringBlocking(128, IORING_SETUP_SINGLE_ISSUER);
             Arena arena = Arena.ofShared();
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {

            int serverFd = blocking.createServerSocket(port, 64);
            try {
                // Server accept loop on a Virtual Thread
                Future<?> serverTask = vts.submit(() -> {
                    for (int i = 0; i < clients; i++) {
                        int clientConnFd = blocking.accept(serverFd);
                        assertTrue(clientConnFd >= 0);
                        vts.submit(() -> {
                            try {
                                MemorySegment buf = arena.allocate(64);
                                long n = blocking.recv(clientConnFd, buf, 64);
                                if (n > 0) {
                                    blocking.send(clientConnFd, buf, (int) n);
                                }
                            } finally {
                                blocking.closeFd(clientConnFd);
                            }
                        });
                    }
                });

                List<Future<String>> clientReplies = new ArrayList<>(clients);
                for (int i = 0; i < clients; i++) {
                    final int idx = i;
                    clientReplies.add(vts.submit(() -> {
                        int cfd = blocking.createClientSocket();
                        try {
                            int rc = blocking.connect(cfd, "127.0.0.1", port);
                            assertEquals(0, rc);
                            String msg = "ping-" + idx;
                            byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                            long sent = blocking.send(cfd, bytes);
                            assertEquals(bytes.length, sent);

                            MemorySegment recvBuf = arena.allocate(64);
                            long recvd = blocking.recv(cfd, recvBuf, 64);
                            assertEquals(bytes.length, recvd);
                            return new String(recvBuf.asSlice(0, recvd).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
                        } finally {
                            blocking.closeFd(cfd);
                        }
                    }));
                }

                for (int i = 0; i < clients; i++) {
                    assertEquals("ping-" + i, clientReplies.get(i).get(5, TimeUnit.SECONDS));
                }
                serverTask.get(5, TimeUnit.SECONDS);
            } finally {
                blocking.closeFd(serverFd);
            }
        }
    }

    @Test
    void proof4_highConcurrencyContentionUnderRingBackpressure() throws Exception {
        // Force high submission backpressure: ring capacity 32 with 1,000 concurrent Virtual Threads
        try (JUringBlocking blocking = new JUringBlocking(32, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor(Path.of("src/test/resources/read_file"), READ);
             Arena arena = Arena.ofShared();
             ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {

            int tasks = 1_000;
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>(tasks);

            for (int i = 0; i < tasks; i++) {
                MemorySegment buf = arena.allocate(16);
                results.add(vts.submit(() -> {
                    latch.await();
                    long bytesRead = blocking.read(fd, buf, 13, 0);
                    assertEquals(13L, bytesRead);
                    return new String(buf.asSlice(0, bytesRead).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
                }));
            }

            latch.countDown();

            for (Future<String> f : results) {
                assertEquals("Hello, World!", f.get(10, TimeUnit.SECONDS));
            }
        }
    }
}
