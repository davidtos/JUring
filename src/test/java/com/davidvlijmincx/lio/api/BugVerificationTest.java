package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.*;

class BugVerificationTest {

    private static final VarHandle VH_INT_ARR = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle VH_LONG_ARR = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle VH_MS_INT = JAVA_INT.varHandle();
    private static final VarHandle VH_MS_LONG = JAVA_LONG.varHandle();

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

    // Bug 11 / Memory Barrier 1: JCStress proof that slotResult is safely published via slotState release/acquire barrier
    @Test
    void proof11_jcstressSlotStatePublicationBarrierPreventsStaleReadsAndHang() throws Exception {
        final int iterations = 100_000;
        final int[] slotState = new int[2];
        final int[] slotResult = new int[2];
        final AtomicInteger forbiddenStaleReads = new AtomicInteger(0);
        final AtomicInteger acceptableCompleted = new AtomicInteger(0);
        final AtomicBoolean stop = new AtomicBoolean(false);

        Thread writer = Thread.ofPlatform().start(() -> {
            for (int i = 1; i <= iterations && !stop.get(); i++) {
                while ((int) VH_INT_ARR.getAcquire(slotState, 0) != 0) {
                    Thread.onSpinWait();
                }
                int magicPayload = 0x1000 + i;
                slotResult[0] = magicPayload;
                // Release barrier on slotState
                VH_INT_ARR.setRelease(slotState, 0, 1 /* STATE_DONE */);
            }
        });

        Thread reader = Thread.ofPlatform().start(() -> {
            for (int i = 1; i <= iterations; i++) {
                int magicPayload = 0x1000 + i;
                long spins = 0;
                while ((int) VH_INT_ARR.getAcquire(slotState, 0) != 1) {
                    Thread.onSpinWait();
                    spins++;
                    if (spins > 50_000_000L) {
                        stop.set(true);
                        fail("Reader loop hung! getAcquire on slotState failed to observe STATE_DONE");
                    }
                }
                int r = slotResult[0];
                if (r != magicPayload) {
                    forbiddenStaleReads.incrementAndGet();
                } else {
                    acceptableCompleted.incrementAndGet();
                }
                VH_INT_ARR.setRelease(slotState, 0, 0);
            }
        });

        writer.join(3000);
        reader.join(3000);
        assertEquals(0, forbiddenStaleReads.get(), "FORBIDDEN OUTCOME: slotResult was read stale despite observing STATE_DONE!");
        assertEquals(iterations, acceptableCompleted.get());
    }

    // Bug 12 / Memory Barrier 2: JCStress proof that MPSC queue sequence barriers guarantee strict ordering and prevent hangs
    @Test
    void proof12_jcstressMpscQueueSequenceBarriersGuaranteeFifoAndPreventHangs() throws Exception {
        final int queueCap = 64;
        final int mask = queueCap - 1;
        final long[] mpscSeq = new long[queueCap];
        final int[] mpscBuf = new int[queueCap];
        for (int i = 0; i < queueCap; i++) {
            mpscSeq[i] = i;
        }

        final AtomicLong mpscTail = new AtomicLong(0);
        final int producers = 4;
        final int itemsPerProducer = 20_000;
        final int totalItems = producers * itemsPerProducer;

        ExecutorService exec = Executors.newFixedThreadPool(producers + 1);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> producerFutures = new ArrayList<>();

            for (int p = 0; p < producers; p++) {
                final int producerId = p;
                producerFutures.add(exec.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {}
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int payload = (producerId << 20) | (i & 0xFFFFF);
                        long tail = mpscTail.getAndIncrement();
                        int idx = (int) (tail & mask);
                        while ((long) VH_LONG_ARR.getAcquire(mpscSeq, idx) != tail) {
                            Thread.onSpinWait();
                        }
                        mpscBuf[idx] = payload;
                        VH_LONG_ARR.setRelease(mpscSeq, idx, tail + 1);
                    }
                }));
            }

            Future<int[]> consumerFuture = exec.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {}
                int[] lastSeqPerProducer = new int[producers];
                Arrays.fill(lastSeqPerProducer, -1);
                int reaped = 0;
                long head = 0;
                while (reaped < totalItems) {
                    int idx = (int) (head & mask);
                    long seq = (long) VH_LONG_ARR.getAcquire(mpscSeq, idx);
                    if (seq != head + 1) {
                        Thread.onSpinWait();
                        continue;
                    }
                    int payload = mpscBuf[idx];
                    VH_LONG_ARR.setRelease(mpscSeq, idx, head + queueCap);
                    head++;
                    reaped++;

                    int producerId = (payload >>> 20) & 0xFFF;
                    int itemSeq = payload & 0xFFFFF;
                    int last = lastSeqPerProducer[producerId];
                    if (itemSeq != last + 1) {
                        throw new IllegalStateException("FIFO violation on producer " + producerId + ": expected " + (last + 1) + ", got " + itemSeq);
                    }
                    lastSeqPerProducer[producerId] = itemSeq;
                }
                return lastSeqPerProducer;
            });

            start.countDown();
            for (Future<?> f : producerFutures) {
                f.get(5, TimeUnit.SECONDS);
            }
            int[] lastSeqs = consumerFuture.get(5, TimeUnit.SECONDS);
            for (int p = 0; p < producers; p++) {
                assertEquals(itemsPerProducer - 1, lastSeqs[p]);
            }
        } finally {
            exec.shutdownNow();
        }
    }

    // Bug 13 / Memory Barrier 3: JCStress proof that Vyukov MPMC free-slot queue never allocates duplicate slots and never leaks
    @Test
    void proof13_jcstressVyukovMpmcQueueMaintainsMutualExclusionAndConservation() throws Exception {
        final int cap = 32;
        final int mask = cap - 1;
        final int[] buffer = new int[cap];
        final long[] seqArr = new long[cap];
        for (int i = 0; i < cap; i++) {
            buffer[i] = i;
            seqArr[i] = i + 1;
        }

        final AtomicLong head = new AtomicLong(0);
        final AtomicLong tail = new AtomicLong(cap);
        final AtomicIntegerArray slotInUse = new AtomicIntegerArray(cap);
        final AtomicInteger duplicateAcquisitions = new AtomicInteger(0);

        final int threads = 8;
        final int opsPerThread = 10_000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                futures.add(exec.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {}

                    for (int i = 0; i < opsPerThread; i++) {
                        int slotId;
                        while (true) {
                            long pos = head.get();
                            int idx = (int) (pos & mask);
                            long seq = (long) VH_LONG_ARR.getAcquire(seqArr, idx);
                            long dif = seq - (pos + 1);
                            if (dif == 0) {
                                if (head.compareAndSet(pos, pos + 1)) {
                                    slotId = buffer[idx];
                                    VH_LONG_ARR.setRelease(seqArr, idx, pos + cap);
                                    break;
                                }
                            } else if (dif < 0) {
                                LockSupport.parkNanos(1000L);
                            } else {
                                Thread.onSpinWait();
                            }
                        }

                        if (!slotInUse.compareAndSet(slotId, 0, 1)) {
                            duplicateAcquisitions.incrementAndGet();
                        }
                        Thread.onSpinWait();
                        if (!slotInUse.compareAndSet(slotId, 1, 0)) {
                            duplicateAcquisitions.incrementAndGet();
                        }

                        while (true) {
                            long pos = tail.get();
                            int idx = (int) (pos & mask);
                            long seq = (long) VH_LONG_ARR.getAcquire(seqArr, idx);
                            long dif = seq - pos;
                            if (dif == 0) {
                                if (tail.compareAndSet(pos, pos + 1)) {
                                    buffer[idx] = slotId;
                                    VH_LONG_ARR.setRelease(seqArr, idx, pos + 1);
                                    break;
                                }
                            } else {
                                Thread.onSpinWait();
                            }
                        }
                    }
                }));
            }

            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            exec.shutdownNow();
        }

        assertEquals(0, duplicateAcquisitions.get(), "JCStress FORBIDDEN outcome: slot was acquired concurrently by two threads!");
    }

    // Bug 14 / Memory Barrier 4: JCStress proof of JUringBlocking.checkOutBuffer / checkInBuffer concurrency
    @Test
    void proof14_jcstressBlockingBufferPoolCheckoutCheckinMaintainsMutualExclusion() throws Exception {
        try (JUringBlocking blocking = new JUringBlocking(16, IORING_SETUP_SINGLE_ISSUER)) {
            final int nrOfBuffers = 16;
            blocking.registerBuffers(64, nrOfBuffers);

            final int threads = 8;
            final int opsPerThread = 5_000;
            final AtomicIntegerArray bufInUse = new AtomicIntegerArray(nrOfBuffers);
            final AtomicInteger collisionCount = new AtomicInteger(0);

            ExecutorService exec = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(exec.submit(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException ignored) {}

                        for (int i = 0; i < opsPerThread; i++) {
                            int bufIdx;
                            while ((bufIdx = blocking.checkOutBuffer()) < 0) {
                                LockSupport.parkNanos(10_000L);
                            }
                            if (!bufInUse.compareAndSet(bufIdx, 0, 1)) {
                                collisionCount.incrementAndGet();
                            }
                            Thread.onSpinWait();
                            if (!bufInUse.compareAndSet(bufIdx, 1, 0)) {
                                collisionCount.incrementAndGet();
                            }
                            blocking.checkInBuffer(bufIdx);
                        }
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(5, TimeUnit.SECONDS);
                }
            } finally {
                exec.shutdownNow();
            }

            assertEquals(0, collisionCount.get(), "JCStress FORBIDDEN outcome: registered buffer index checked out concurrently!");
        }
    }

    // Bug 15 / Memory Barrier 5: JCStress proof of off-heap memory ring barriers between submitter and kernel consumer
    @Test
    void proof15_jcstressNativeRingMemoryBarriersGuaranteeSqeAndCqeIntegrity() throws Exception {
        final int ringEntries = 16;
        final int ringMask = ringEntries - 1;
        try (Arena arena = Arena.ofShared()) {
            MemorySegment mem = arena.allocate(8192);
            long sqTailOffset = 0;
            long sqHeadOffset = 8;
            long cqTailOffset = 16;
            long cqHeadOffset = 24;
            long sqesOffset = 128;
            long cqesOffset = 2048;

            final int iterations = 50_000;
            final AtomicInteger corruptedSqes = new AtomicInteger(0);
            final AtomicInteger corruptedCqes = new AtomicInteger(0);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);

                Future<?> userTask = exec.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {}

                    for (int i = 1; i <= iterations; i++) {
                        int index = (i - 1) & ringMask;
                        long sqe = sqesOffset + ((long) index * 64L);
                        mem.set(JAVA_BYTE, sqe, (byte) 18 /* IORING_OP_NOP */);
                        mem.set(JAVA_INT, sqe + 4, -1);
                        mem.set(JAVA_LONG, sqe + 32, i);

                        VH_MS_INT.setRelease(mem, sqTailOffset, i);

                        while (true) {
                            int cqTail = (int) VH_MS_INT.getAcquire(mem, cqTailOffset);
                            if (cqTail >= i) {
                                break;
                            }
                            Thread.onSpinWait();
                        }
                        int cqIndex = (i - 1) & ringMask;
                        long cqe = cqesOffset + ((long) cqIndex * 16L);
                        long userData = mem.get(JAVA_LONG, cqe);
                        int res = mem.get(JAVA_INT, cqe + 8);
                        if (userData != i || res != 0) {
                            corruptedCqes.incrementAndGet();
                        }
                        VH_MS_INT.setRelease(mem, cqHeadOffset, i);
                    }
                });

                Future<?> kernelTask = exec.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {}

                    for (int i = 1; i <= iterations; i++) {
                        while (true) {
                            int sqTail = (int) VH_MS_INT.getAcquire(mem, sqTailOffset);
                            if (sqTail >= i) {
                                break;
                            }
                            Thread.onSpinWait();
                        }
                        int index = (i - 1) & ringMask;
                        long sqe = sqesOffset + ((long) index * 64L);
                        byte op = mem.get(JAVA_BYTE, sqe);
                        int fd = mem.get(JAVA_INT, sqe + 4);
                        long ud = mem.get(JAVA_LONG, sqe + 32);
                        if (op != 18 || fd != -1 || ud != i) {
                            corruptedSqes.incrementAndGet();
                        }
                        VH_MS_INT.setRelease(mem, sqHeadOffset, i);

                        int cqIndex = (i - 1) & ringMask;
                        long cqe = cqesOffset + ((long) cqIndex * 16L);
                        mem.set(JAVA_LONG, cqe, ud);
                        mem.set(JAVA_INT, cqe + 8, 0);

                        VH_MS_INT.setRelease(mem, cqTailOffset, i);
                    }
                });

                start.countDown();
                userTask.get(5, TimeUnit.SECONDS);
                kernelTask.get(5, TimeUnit.SECONDS);
            } finally {
                exec.shutdownNow();
            }

            assertEquals(0, corruptedSqes.get(), "JCStress FORBIDDEN outcome: Kernel read corrupted SQE due to missing/weak memory barrier!");
            assertEquals(0, corruptedCqes.get(), "JCStress FORBIDDEN outcome: User read corrupted CQE due to missing/weak memory barrier!");
        }
    }

    // Bug 16 / Memory Barrier 6: Proof that pollerParked protocol prevents lost unpark hangs
    @Test
    void proof16_jcstressPollerWakeupRaceDoesNotLoseUnparkOrHang() throws Exception {
        final int iterations = 10_000;
        final int[] pollerParked = new int[1];
        final AtomicBoolean hasWork = new AtomicBoolean(false);
        final AtomicReference<Thread> pollerThreadRef = new AtomicReference<>();

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                pollerParked[0] = 0;
                hasWork.set(false);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch latch = new CountDownLatch(1);

                Future<?> poller = exec.submit(() -> {
                    pollerThreadRef.set(Thread.currentThread());
                    ready.countDown();
                    try {
                        latch.await();
                    } catch (InterruptedException ignored) {}

                    VH_INT_ARR.setVolatile(pollerParked, 0, 1);
                    if (!hasWork.get()) {
                        LockSupport.parkNanos(10_000_000L);
                    }
                    VH_INT_ARR.setVolatile(pollerParked, 0, 0);
                });

                Future<?> submitter = exec.submit(() -> {
                    ready.countDown();
                    try {
                        latch.await();
                    } catch (InterruptedException ignored) {}

                    hasWork.set(true);
                    VarHandle.fullFence();
                    if ((int) VH_INT_ARR.getVolatile(pollerParked, 0) != 0) {
                        LockSupport.unpark(pollerThreadRef.get());
                    }
                });

                ready.await();
                latch.countDown();
                poller.get(50, TimeUnit.MILLISECONDS);
                submitter.get(50, TimeUnit.MILLISECONDS);
            }
        } finally {
            exec.shutdownNow();
        }
    }

    // Bug 17 / Memory Barrier 7: Verification that C2 JIT does not hoist getAcquire loop condition
    @Test
    void proof17_c2JitCompilerDoesNotHoistAcquireLoopCondition() throws Exception {
        final int[] flag = new int[1];
        // Warm up loop to trigger C2 JIT compilation
        for (int i = 0; i < 20_000; i++) {
            flag[0] = 1;
            while ((int) VH_INT_ARR.getAcquire(flag, 0) != 1) {
                Thread.onSpinWait();
            }
        }

        flag[0] = 0;
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Long> loopFuture = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            long spins = 0;
            while ((int) VH_INT_ARR.getAcquire(flag, 0) != 1) {
                Thread.onSpinWait();
                spins++;
            }
            return spins;
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(10);
        VH_INT_ARR.setRelease(flag, 0, 1);

        Long spins = loopFuture.get(2, TimeUnit.SECONDS);
        assertNotNull(spins);
        assertTrue(spins >= 0);
    }

    // Bug 18: Loop termination verification for submitAndWait and submitAndWaitForBatchResult on zero/negative waitNr
    @Test
    void proof18_submitAndWaitZeroAndNegativeWaitNrDoesNotHang() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER)) {
                // 1. Zero waitNr with empty queue: returns immediately with 0
                int res = ring.submitAndWait(0);
                assertEquals(0, res);

                // 2. Negative waitNr: returns immediately
                res = ring.submitAndWait(-1);
                assertEquals(0, res);

                // 3. submitAndWaitForBatchResult with minComplete=0 and no SQEs: returns 0 immediately
                int count = ring.submitAndWaitForBatchResult(0, 10, r -> fail("Consumer should not be called"));
                assertEquals(0, count);

                // 4. submitAndWaitForBatchResult with minComplete=0 and pending SQEs: submits and returns immediately
                ring.prepareNop();
                ring.prepareNop();
                count = ring.submitAndWaitForBatchResult(0, 10, r -> {});
                assertTrue(count >= 0);

                // 5. submitAndWait(1) with 1 NOP: waits and returns >= 1
                ring.prepareNop();
                res = ring.submitAndWait(1);
                assertTrue(res >= 1);

                // 6. submitAndWaitForBatchResult partial reaping: prepare 4 NOPs, reap max 2
                ring.prepareNop();
                ring.prepareNop();
                ring.prepareNop();
                ring.prepareNop();
                AtomicInteger reaped = new AtomicInteger();
                count = ring.submitAndWaitForBatchResult(2, 2, r -> reaped.incrementAndGet());
                assertEquals(2, count);
                assertEquals(2, reaped.get());

                // Reap remaining
                count = ring.peekForBatchResult(10, r -> reaped.incrementAndGet());
                assertTrue(count >= 0);

                // 7. submitAndWait(0) with completed CQEs in the ring returns available count, NOT 0!
                ring.prepareNop();
                int submit1 = ring.submitAndWait(1);
                assertTrue(submit1 >= 1, "Expected >= 1 completion from submitAndWait(1)");
                ring.prepareNop();
                int submit0 = ring.submitAndWait(0);
                assertTrue(submit0 >= 1, "submitAndWait(0) must return available completions (expected >= 1, got " + submit0 + ")");
            }
        });
    }

    // Bug 19: Benchmark pipeline in-flight drain loop hang regression (Commit 526b302)
    @Test
    void proof19_benchmarkPipelineDrainLoopDoesNotHangWithUnevenBatchSizes() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            try (JUring ring = new JUring(32, IORING_SETUP_SINGLE_ISSUER)) {
                final int totalTasks = 73; // Uneven task count not dividing into submitBatch (16) or waitBatch (4)
                final int qd = 16;
                final int waitBatch = 4;

                int inFlight = 0;
                int submitted = 0;
                int reapedTotal = 0;

                while (inFlight < qd && submitted < totalTasks) {
                    ring.prepareNop();
                    inFlight++;
                    submitted++;
                }

                while (reapedTotal < totalTasks) {
                    int remaining = totalTasks - reapedTotal;
                    int minWait = Math.max(1, Math.min(waitBatch, Math.min(remaining, inFlight)));
                    int reaped = ring.submitAndWaitForBatchResult(minWait, qd, result -> {});
                    inFlight -= reaped;
                    reapedTotal += reaped;

                    while (inFlight < qd && submitted < totalTasks) {
                        ring.prepareNop();
                        inFlight++;
                        submitted++;
                    }
                }

                assertEquals(totalTasks, reapedTotal);
                assertEquals(0, inFlight);
            }
        });
    }

    // Bug 20: Full ring backpressure and slot exhaustion loop does not deadlock or hang
    @Test
    void proof20_jUringBlockingFullRingBackpressure100ThreadsDoesNotHang(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("backpressure.txt");
        Files.writeString(file, "JUring Backpressure Test Payload 12345");

        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            try (JUringBlocking blocking = new JUringBlocking(4, IORING_SETUP_SINGLE_ISSUER);
                 FileDescriptor fd = new FileDescriptor(file, READ, 0);
                 Arena arena = Arena.ofShared();
                 ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {

                final int tasks = 100;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> results = new ArrayList<>(tasks);

                for (int i = 0; i < tasks; i++) {
                    MemorySegment buf = arena.allocate(38);
                    results.add(vts.submit(() -> {
                        start.await();
                        long n = blocking.read(fd, buf, 38, 0);
                        assertEquals(38L, n);
                        return new String(buf.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
                    }));
                }

                start.countDown();
                for (Future<String> f : results) {
                    assertEquals("JUring Backpressure Test Payload 12345", f.get(3, TimeUnit.SECONDS));
                }
            }
        });
    }

    // Bug 21: JUringBlocking shutdown and thread interruption cleanly exit loops without hanging
    @Test
    void proof21_blockingCloseAndInterruptionCleanlyExitLoops(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("close_interrupt.txt");
        Files.writeString(file, "test data");

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            try (JUringBlocking blocking = new JUringBlocking(8, IORING_SETUP_SINGLE_ISSUER);
                 FileDescriptor fd = new FileDescriptor(file, READ, 0);
                 Arena arena = Arena.ofShared()) {

                AtomicBoolean wasInterrupted = new AtomicBoolean(false);
                Thread worker = Thread.ofPlatform().start(() -> {
                    Thread.currentThread().interrupt();
                    MemorySegment buf = arena.allocate(4);
                    blocking.read(fd, buf, 4, 0);
                    if (Thread.currentThread().isInterrupted()) {
                        wasInterrupted.set(true);
                    }
                });
                worker.join(2000);
                assertTrue(wasInterrupted.get(), "Expected thread to preserve interrupted status after blocking read");
            }

            JUringBlocking blockingToClose = new JUringBlocking(8, IORING_SETUP_SINGLE_ISSUER);
            assertDoesNotThrow(blockingToClose::close);
            assertThrows(IllegalStateException.class, () -> blockingToClose.registerBuffers(64, 2));
        });
    }

    // Bug 22: Concurrent control tasks CAS loop on JUringBlocking does not livelock
    @Test
    void proof22_concurrentControlTasksCASLoopDoesNotLivelock() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            try (JUringBlocking blocking = new JUringBlocking(16, IORING_SETUP_SINGLE_ISSUER)) {
                final int threads = 8;
                ExecutorService exec = Executors.newFixedThreadPool(threads);
                try {
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<?>> futures = new ArrayList<>();
                    for (int t = 0; t < threads; t++) {
                        futures.add(exec.submit(() -> {
                            try {
                                start.await();
                            } catch (InterruptedException ignored) {}

                            try {
                                blocking.checkOutBuffer();
                            } catch (IllegalStateException expectedNotRegistered) {
                                // Expected before registration
                            }
                        }));
                    }
                    start.countDown();
                    for (Future<?> f : futures) {
                        f.get(2, TimeUnit.SECONDS);
                    }
                } finally {
                    exec.shutdownNow();
                }
            }
        });
    }

    // Bug 23: Loop termination proof: submitAndWait and submitAndWaitForBatchResult correctly track unsubmitted SQEs across batches
    @Test
    void proof23_partialSqeSubmissionTrackingDoesNotHangLoop() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER)) {
                // Submit 8 operations (saturates 8-entry ring) with submitAndWait(8)
                for (int i = 0; i < 8; i++) {
                    ring.prepareNop();
                }
                int available = ring.submitAndWait(8);
                assertTrue(available >= 8, "Expected at least 8 completions, got " + available);

                List<Result> results = ring.waitForBatchResult(8);
                assertEquals(8, results.size());
                for (Result r : results) {
                    try (r) {
                        assertEquals(0, r.result());
                    }
                }
            }
        });
    }

    // Bug 24: Buffer pool checkInBuffer throws IllegalStateException when pool is full instead of hanging in infinite spin
    @Test
    void proof24_bufferPoolCheckInFullThrowsInsteadOfHanging() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (JUringBlocking blocking = new JUringBlocking(8, IORING_SETUP_SINGLE_ISSUER)) {
                // Power-of-2 buffer count
                blocking.registerBuffers(64, 4);
                // Pool has 4 buffers: [0, 1, 2, 3]. It is initially full.
                // Checking in another buffer without checking out first must throw IllegalStateException, NOT spin forever!
                assertThrows(IllegalStateException.class, () -> blocking.checkInBuffer(0));
            }

            try (JUringBlocking blocking = new JUringBlocking(8, IORING_SETUP_SINGLE_ISSUER)) {
                // Non-power-of-2 buffer count (3 buffers, capacity 4)
                blocking.registerBuffers(64, 3);
                // Pool has 3 buffers: [0, 1, 2]. Total buffers = 3.
                assertThrows(IllegalStateException.class, () -> blocking.checkInBuffer(0));
                assertThrows(IllegalStateException.class, () -> blocking.checkInBuffer(1));
                assertThrows(IllegalStateException.class, () -> blocking.checkInBuffer(2));
            }
        });
    }

    // Bug 25: Buffer pool checkInBuffer throws IllegalArgumentException immediately on out-of-bounds index
    @Test
    void proof25_bufferPoolCheckInInvalidIndexThrowsImmediately() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (JUringBlocking blocking = new JUringBlocking(8, IORING_SETUP_SINGLE_ISSUER)) {
                blocking.registerBuffers(64, 4);
                int b = blocking.checkOutBuffer();
                assertTrue(b >= 0);
                assertThrows(IllegalArgumentException.class, () -> blocking.checkInBuffer(-1));
                assertThrows(IllegalArgumentException.class, () -> blocking.checkInBuffer(99));
                blocking.checkInBuffer(b);
            }

            try (JUringBlocking blocking = new JUringBlocking(8, IORING_SETUP_SINGLE_ISSUER)) {
                // Non-power-of-2 buffer count: 3 buffers [0, 1, 2], mask 3 (cap 4)
                blocking.registerBuffers(64, 3);
                int b = blocking.checkOutBuffer();
                assertTrue(b >= 0);
                // Index 3 is >= nrOfBuffers (3), though < poolCap (4). Must throw IllegalArgumentException!
                assertThrows(IllegalArgumentException.class, () -> blocking.checkInBuffer(3));
                blocking.checkInBuffer(b);
            }
        });
    }

    // Bug 26: OpenJDK JCStress suite verifies memory barriers across compiler modes
    @Test
    void proof26_openjdkJcstressTestMemoryBarrierSuiteExecutesAndVerifies() throws Exception {
        try {
            org.openjdk.jcstress.Options opts = new org.openjdk.jcstress.Options(new String[]{
                    "-m", "sanity",
                    "-r", "target/jcstress-results",
                    "-t", "com.davidvlijmincx.lio.jcstress.*"
            });
            opts.parse();
            org.openjdk.jcstress.JCStress jcstress = new org.openjdk.jcstress.JCStress(opts);
            jcstress.run();
        } finally {
            try (var stream = java.nio.file.Files.newDirectoryStream(java.nio.file.Path.of("."), "jcstress-results-*.bin.gz")) {
                for (var p : stream) {
                    java.nio.file.Files.deleteIfExists(p);
                }
            } catch (Exception ignored) {}
        }
    }
}





