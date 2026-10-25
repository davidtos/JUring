package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class BugVerificationTest {

    // Bug 1: JUring.getSharedWorkerRing silently drops ioUringOptions
    @Test
    void sharedWorkerRingRespectsPassedOptions(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("shared_worker_opts.txt");
        Files.writeString(file, "hello");

        try (JUring parent = new JUring(8);
             // IORING_SETUP_R_DISABLED starts the ring in disabled state; submitting must fail with -77 (EBADFD)
             JUring worker = parent.getSharedWorkerRing(8, IORING_SETUP_R_DISABLED);
             FileDescriptor fd = new FileDescriptor(file, READ, 0)) {

            worker.prepareRead(fd, 5, 0);
            RuntimeException ex = assertThrows(RuntimeException.class, worker::submit,
                    "Expected worker ring created with IORING_SETUP_R_DISABLED to reject submit()");
            assertTrue(ex.getMessage().contains("File descriptor in bad state"),
                    "Expected EBADFD ('File descriptor in bad state') when ring is created with IORING_SETUP_R_DISABLED, got: " + ex.getMessage());
        }
    }

    // Bug 2: Double close() on JUring / IoUringDispatcher / FileDescriptor
    @Test
    void closeIsIdempotentOnJUringAndFileDescriptor(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("idempotent_close.txt");
        Files.writeString(file, "data");

        try (JUring ring = new JUring(8)) {
            FileDescriptor fd = new FileDescriptor(file, READ, 0);
            int rawFd = fd.getFd();
            fd.close();
            // Open another FileDescriptor which on Linux will reuse rawFd
            try (FileDescriptor fd2 = new FileDescriptor(file, READ, 0)) {
                // Calling fd.close() again must NOT close fd2's newly assigned OS file descriptor!
                fd.close();
                ring.prepareRead(fd2, 4, 0);
                ring.submit();
                try (Result res = ring.waitForResult()) {
                    assertEquals(4, res.result(), "Second close on old FileDescriptor must not close reused OS fd (was " + rawFd + ")");
                }
            }
        }

        JUring jUring = new JUring(8);
        jUring.setupReadBufferPool(64, 4);
        jUring.close();
        // Second close must be a safe no-op, not throw IllegalStateException or double-free native memory
        assertDoesNotThrow(jUring::close);
    }

    // Bug 3: IORING_SETUP_SQE128 and IORING_SETUP_CQE32 corrupt SQE/CQE ring indexing
    @Test
    void sqe128AndCqe32IndexCorrectlyForMultipleOperations(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("sqe128_cqe32.txt");
        Files.writeString(file, "AAAABBBBCCCCDDDD");

        try (JUring ring = new JUring(8, IORING_SETUP_SQE128, IORING_SETUP_CQE32);
             FileDescriptor fd = new FileDescriptor(file, READ, 0)) {

            long id1 = ring.prepareRead(fd, 4, 0);
            long id2 = ring.prepareRead(fd, 4, 4);
            long id3 = ring.prepareRead(fd, 4, 8);
            ring.submit();

            List<Result> results = ring.waitForBatchResult(3);
            assertEquals(3, results.size());
            for (Result r : results) {
                try (r) {
                    assertEquals(4, r.result());
                    String s = r.buffer().getString(0);
                    if (r.id() == id1) {
                        assertEquals("AAAA", s);
                    } else if (r.id() == id2) {
                        assertEquals("BBBB", s);
                    } else if (r.id() == id3) {
                        assertEquals("CCCC", s);
                    } else {
                        fail("Unexpected completion id: " + r.id());
                    }
                }
            }
        }
    }

    // Bug 4: prepareWrite(fd, MemorySegment, offset) misreports OperationType.WRITE_FIXED and bufferIdx = 0
    @Test
    void prepareWriteWithMemorySegmentReportsWriteTypeAndNegativeBufferIdx(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("write_segment_type.txt");
        Files.writeString(file, "");

        try (JUring ring = new JUring(8);
             FileDescriptor fd = new FileDescriptor(file, WRITE, 0);
             Arena arena = Arena.ofConfined()) {

            MemorySegment seg = arena.allocateFrom("hello");
            long id = ring.prepareWrite(fd, seg, 0);
            assertEquals(OperationType.WRITE, ZeroGcUserData.getType(id));

            ring.submit();
            Result res = ring.waitForResult();
            assertEquals(id, res.id());
            assertEquals(OperationType.WRITE, res.type());
            assertEquals(-1, res.bufferIdx());
            assertEquals(6, res.result());
        }
    }

    // Bug 5: Per-thread IORING_REGISTER_RING_FDS slot collision across threads
    @Test
    void crossThreadRingUsageDoesNotCollideOnRegisteredRingSlot(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("cross_thread_ring.txt");
        Files.writeString(file, "hello world");

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            CompletableFuture<JUring> ringFromThreadA = CompletableFuture.supplyAsync(() -> new JUring(8));
            try (JUring ring1OnCurrentThread = new JUring(8);
                 JUring ring2FromOtherThread = ringFromThreadA.get(2, TimeUnit.SECONDS);
                 FileDescriptor fd = new FileDescriptor(file, READ, 0)) {

                // ring1OnCurrentThread occupies registered slot 0 on the current thread.
                // ring2FromOtherThread was registered at slot 0 on Thread A (so enterRingFd == 0).
                // Using ring2FromOtherThread on the current thread must submit/wait on ring2FromOtherThread, not ring1OnCurrentThread!
                ring2FromOtherThread.prepareRead(fd, 5, 0);
                ring2FromOtherThread.submit();

                try (Result res = ring2FromOtherThread.waitForResult()) {
                    assertEquals(5, res.result());
                    assertEquals("hello", res.buffer().getString(0));
                }
            }
        });
    }

    // Bug 6: JUringBlocking deadlocks when >= sqEntries socket operations are waiting or with IORING_SETUP_DEFER_TASKRUN
    @Test
    void jUringBlockingDoesNotDeadlockWithFullSocketWaitersOrDeferTaskrun() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            // Test 1: sqEntries=4 with 4 concurrent recv operations waiting before send arrives
            try (JUringBlocking blocking = new JUringBlocking(4)) {
                int[] fds = createLoopbackPair(19401);
                int serverConn = fds[0];
                int clientConn = fds[1];
                try (Arena arena = Arena.ofShared()) {
                    MemorySegment r1 = arena.allocate(4);
                    MemorySegment r2 = arena.allocate(4);
                    MemorySegment r3 = arena.allocate(4);
                    MemorySegment r4 = arena.allocate(4);

                    CountDownLatch started = new CountDownLatch(4);
                    var f1 = CompletableFuture.supplyAsync(() -> { started.countDown(); return blocking.recv(serverConn, r1, 4); });
                    var f2 = CompletableFuture.supplyAsync(() -> { started.countDown(); return blocking.recv(serverConn, r2, 4); });
                    var f3 = CompletableFuture.supplyAsync(() -> { started.countDown(); return blocking.recv(serverConn, r3, 4); });
                    var f4 = CompletableFuture.supplyAsync(() -> { started.countDown(); return blocking.recv(serverConn, r4, 4); });

                    assertTrue(started.await(2, TimeUnit.SECONDS));
                    // Give poller time to submit the 4 recvs
                    Thread.sleep(50);

                    // Now send 16 bytes so all 4 recvs can complete; poller must not be blocked from draining this send!
                    MemorySegment sendBuf = arena.allocate(16);
                    sendBuf.copyFrom(MemorySegment.ofArray("1111222233334444".getBytes(StandardCharsets.UTF_8)));
                    long sent = blocking.send(clientConn, sendBuf, 16);
                    assertEquals(16, sent);

                    assertEquals(4L, f1.get(2, TimeUnit.SECONDS));
                    assertEquals(4L, f2.get(2, TimeUnit.SECONDS));
                    assertEquals(4L, f3.get(2, TimeUnit.SECONDS));
                    assertEquals(4L, f4.get(2, TimeUnit.SECONDS));
                } finally {
                    NativeDispatcher.C.close(serverConn);
                    NativeDispatcher.C.close(clientConn);
                }
            }

            // Test 2: JUringBlocking with IORING_SETUP_DEFER_TASKRUN and socket operations
            try (JUringBlocking blocking = new JUringBlocking(8, Duration.ofMillis(2000), IORING_SETUP_SUBMIT_ALL, IORING_SETUP_COOP_TASKRUN, IORING_SETUP_SINGLE_ISSUER, IORING_SETUP_DEFER_TASKRUN)) {
                int[] fds = createLoopbackPair(19402);
                int serverConn = fds[0];
                int clientConn = fds[1];
                try (Arena arena = Arena.ofShared()) {
                    MemorySegment recvBuf = arena.allocate(5);
                    var recvFuture = CompletableFuture.supplyAsync(() -> blocking.recv(serverConn, recvBuf, 5));
                    Thread.sleep(50);
                    byte[] msg = "hello".getBytes(StandardCharsets.UTF_8);
                    long sent = blocking.send(clientConn, msg);
                    assertEquals(5L, sent);
                    assertEquals(5L, recvFuture.get(2, TimeUnit.SECONDS));
                } finally {
                    NativeDispatcher.C.close(serverConn);
                    NativeDispatcher.C.close(clientConn);
                }
            }
        });
    }

    private static int[] createLoopbackPair(int port) {
        try (JUring setupRing = new JUring(8)) {
            int listenFd = setupRing.createServerSocket(port, 16);
            int clientFd = setupRing.createClientSocket();
            setupRing.prepareAccept(listenFd);
            setupRing.prepareConnect(clientFd, "127.0.0.1", port);
            setupRing.submit();
            List<Result> results = setupRing.waitForBatchResult(2);
            int serverConn = -1;
            for (Result r : results) {
                if (r.type() == OperationType.ACCEPT) {
                    serverConn = (int) r.result();
                }
            }
            NativeDispatcher.C.close(listenFd);
            return new int[]{serverConn, clientFd};
        }
    }

    // Bug 7: prepareOpenDirect and prepareCloseDirect with negative fileIndex
    @Test
    void directOpenAndCloseRejectNegativeFileIndex(@TempDir Path tempDir) {
        Path file = tempDir.resolve("negative_index.txt");
        try (JUring ring = new JUring(8)) {
            assertThrows(IllegalArgumentException.class, () -> ring.prepareOpenDirect(file.toString(), READ.getValue(), 0, -1));
            assertThrows(IllegalArgumentException.class, () -> ring.prepareCloseDirect(-1));
            // Verify all 8 SQE slots are still available (no phantom SQEs were left in the ring)
            for (int i = 0; i < 8; i++) {
                ring.prepareNop();
            }
        }
    }

    // Bug 8: Validation failures in prepare* must not leave phantom NOP SQEs or leak/overflow buffers
    @Test
    void failedPrepareDoesNotLeavePhantomSqeAndValidatesBounds(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("validation.txt");
        Files.writeString(file, "hello");

        try (JUring ring = new JUring(8);
             FileDescriptor fd = new FileDescriptor(file, READ, 0);
             Arena arena = Arena.ofConfined()) {

            // Calling prepareReadPooled before setupReadBufferPool must throw IllegalStateException
            assertThrows(IllegalStateException.class, () -> ring.prepareReadPooled(fd, 4, 0));

            ring.setupReadBufferPool(16, 2);
            // Calling prepareReadPooled with readSize > pool bufferSize must throw IllegalArgumentException
            assertThrows(IllegalArgumentException.class, () -> ring.prepareReadPooled(fd, 32, 0));
            assertThrows(IllegalArgumentException.class, () -> ring.prepareReadPooled(fd, -1, 0));

            // Negative readSize on prepareRead
            assertThrows(IllegalArgumentException.class, () -> ring.prepareRead(fd, -1, 0));

            // Invalid prepareReadv / prepareWritev
            assertThrows(IllegalArgumentException.class, () -> ring.prepareReadv(fd, new int[0], 0));
            assertThrows(IllegalArgumentException.class, () -> ring.prepareReadv(fd, new int[]{-1}, 0));
            assertThrows(IllegalArgumentException.class, () -> ring.prepareWritev(fd, new byte[0][], 0));

            // Invalid prepareConnect IPv4 address / port
            assertThrows(IllegalArgumentException.class, () -> ring.prepareConnect(fd.getFd(), "999.0.0.1", 80));
            assertThrows(IllegalArgumentException.class, () -> ring.prepareConnect(fd.getFd(), "127.0.0.1.", 80));
            assertThrows(IllegalArgumentException.class, () -> ring.prepareConnect(fd.getFd(), "127.0.0.1", 70000));

            // Invalid prepareRecv / prepareSend bounds
            MemorySegment smallBuf = arena.allocate(4);
            assertThrows(IllegalArgumentException.class, () -> ring.prepareRecv(fd.getFd(), -1));
            assertThrows(IllegalArgumentException.class, () -> ring.prepareRecv(fd.getFd(), smallBuf, 8));
            assertThrows(IllegalArgumentException.class, () -> ring.prepareSend(fd.getFd(), smallBuf, 8));

            // None of the failed prepare calls above may leave a phantom NOP SQE in the 8-entry ring!
            for (int i = 0; i < 8; i++) {
                ring.prepareNop();
            }
        }
    }

    // Bug 9: Result.buffer() on READ at EOF (result == 0) or partial read
    @Test
    void readResultBufferAtEofAndPartialReadIsNullTerminatedAndSafe(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("eof_read.txt");
        Files.writeString(file, "abc");

        try (JUring ring = new JUring(8);
             FileDescriptor fd = new FileDescriptor(file, READ, 0)) {

            // Read at EOF (offset 3, readSize 10 -> result == 0)
            ring.prepareRead(fd, 10, 3);
            // Partial read (offset 0, readSize 10 -> result == 3)
            ring.prepareRead(fd, 10, 0);
            ring.submit();

            List<Result> results = ring.waitForBatchResult(2);
            for (Result r : results) {
                try (r) {
                    if (r.result() == 0) {
                        assertTrue(r.buffer().byteSize() >= 1, "EOF READ buffer should have at least 1 byte for null terminator");
                        assertEquals("", r.buffer().getString(0));
                    } else {
                        assertEquals(3, r.result());
                        assertEquals("abc", r.buffer().getString(0));
                    }
                }
            }
        }
    }

    // Bug 10: READV_FIXED out-of-bounds across contiguous registered buffers & READV partial read null-termination
    @Test
    void readvFixedInAscendingOrderAndPartialReadvAreProperlyBounded(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("readv_fixed_order.txt");
        Files.writeString(file, "HelloWorld");

        try (JUring ring = new JUring(8);
             FileDescriptor fd = new FileDescriptor(file, READ, 0)) {

            ring.registerBuffers(5, 2);
            // Pass registered buffer indices in ascending order {0, 1} so buffer 0 is immediately followed in memory by buffer 1
            ring.prepareReadvFixed(fd, new int[]{0, 1}, 0);
            ring.submit();

            try (Result r = ring.waitForResult()) {
                assertEquals(10, r.result());
                MemorySegment[] bufs = r.buffers();
                assertEquals(2, bufs.length);
                assertEquals("Hello", bufs[0].getString(0));
                assertEquals("World", bufs[1].getString(0));
            }
        }

        // Also test partial READV where file has 7 bytes ("HelloWo") and iovecs request {5, 5}
        Path shortFile = tempDir.resolve("readv_short.txt");
        Files.writeString(shortFile, "HelloWo");
        try (JUring ring = new JUring(8);
             FileDescriptor fd = new FileDescriptor(shortFile, READ, 0)) {

            ring.prepareReadv(fd, new int[]{5, 5}, 0);
            ring.submit();

            try (Result r = ring.waitForResult()) {
                assertEquals(7, r.result());
                MemorySegment[] bufs = r.buffers();
                assertEquals("Hello", bufs[0].getString(0));
                assertEquals("Wo", bufs[1].getString(0));
            }
        }
    }
}
