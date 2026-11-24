package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency tests for {@link JUringBlocking}'s registered-buffer publication and the
 * best-effort consistency of its multi-worker registration calls.
 *
 * <p>The publication tests deliberately race a reader against {@code registerBuffers()}: before
 * the fix, the buffer array was published before the free-list/ownership companions, so a racing
 * reader could pop an index and observe a null ledger. The tests therefore accept only the
 * "nothing registered yet" {@link IllegalStateException} while they wait, and treat any other
 * failure as a torn publication.
 *
 * <p>The rollback tests inject a per-ring failure through the package-private dispatcher (the
 * wrapper installs the same resource on every ring, so this is the deterministic way to make one
 * later ring reject it) and then prove the already-mutated rings were rolled back by retrying the
 * whole call after the injected state is removed.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JUringBlockingRegistrationConsistencyTest {

    private static final int BUFFER_SIZE = 64;
    private static final int EBADF = 9;

    // ------------------------------------------------------------------
    // publication
    // ------------------------------------------------------------------

    @Test
    void registrationIsVisibleToOtherThreads() throws Exception {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             JUringBlocking io = new JUringBlocking(16, 4, IORING_SETUP_SINGLE_ISSUER);
             ExecutorService readers = Executors.newFixedThreadPool(8)) {
            int bufferCount = 4;
            CountDownLatch published = new CountDownLatch(1);

            List<Future<?>> futures = new ArrayList<>();
            for (int reader = 0; reader < 8; reader++) {
                futures.add(readers.submit(() -> {
                    published.await();
                    // Every returned segment is fully initialised and consistent.
                    for (int i = 0; i < bufferCount; i++) {
                        MemorySegment buffer = io.getRegisteredBuffer(i);
                        assertNotNull(buffer);
                        assertEquals(BUFFER_SIZE, buffer.byteSize());
                    }
                    // The publication edge also covers the pool companions. Buffers are a
                    // scarce resource shared by the readers, so spin (no sleeps) until one is
                    // free rather than requiring a particular interleaving.
                    int index;
                    while ((index = io.checkOutBuffer()) < 0) {
                        Thread.onSpinWait();
                    }
                    try {
                        assertEquals(BUFFER_SIZE, io.getRegisteredBuffer(index).byteSize());
                        // Other (worker-selected) threads can use the registration immediately.
                        assertEquals(13L, io.readFixed(fd, 13, 0, index),
                                "fixed I/O through a worker ring must see the registration");
                        byte[] actual = new byte[13];
                        MemorySegment.copy(io.getRegisteredBuffer(index), 0,
                                MemorySegment.ofArray(actual), 0, actual.length);
                        assertEquals("Hello, World!", new String(actual, StandardCharsets.UTF_8));
                    } finally {
                        io.checkInBuffer(index);
                    }
                    return null;
                }));
            }

            MemorySegment[] buffers = io.registerBuffers(BUFFER_SIZE, bufferCount);
            published.countDown();
            assertEquals(bufferCount, buffers.length);

            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void concurrentReadersNeverObservePartiallyPublishedRegistration() throws Exception {
        try (JUringBlocking io = new JUringBlocking(16, 4, IORING_SETUP_SINGLE_ISSUER);
             ExecutorService readers = Executors.newFixedThreadPool(4)) {
            int bufferCount = 4;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int reader = 0; reader < bufferCount; reader++) {
                futures.add(readers.submit(() -> {
                    start.await();
                    // No sleeps: spin until the publication becomes visible.
                    for (int attempt = 0; attempt < 50_000_000; attempt++) {
                        int index;
                        try {
                            index = io.checkOutBuffer();
                        } catch (IllegalStateException notYetPublished) {
                            if (!notYetPublished.getMessage().contains("have not been registered")) {
                                // This is the torn-publication failure mode: the registration is
                                // observable but the pool state is not initialised.
                                throw notYetPublished;
                            }
                            Thread.onSpinWait();
                            continue;
                        }
                        assertTrue(index >= 0, "a published registration must not report exhaustion");
                        try {
                            assertEquals(BUFFER_SIZE, io.getRegisteredBuffer(index).byteSize(),
                                    "a checked-out index must map to a published buffer");
                        } finally {
                            io.checkInBuffer(index);
                        }
                        return null;
                    }
                    throw new AssertionError("registration never became visible to a concurrent reader");
                }));
            }

            start.countDown();
            assertEquals(bufferCount, io.registerBuffers(BUFFER_SIZE, bufferCount).length);
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void concurrentRegisterBuffersAllowsExactlyOneWinner() throws Exception {
        for (int round = 0; round < 4; round++) {
            try (JUringBlocking io = new JUringBlocking(8, 2, IORING_SETUP_SINGLE_ISSUER);
                 ExecutorService callers = Executors.newFixedThreadPool(2)) {
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<Object>> futures = new ArrayList<>();
                for (int caller = 0; caller < 2; caller++) {
                    futures.add(callers.submit(() -> {
                        start.await();
                        try {
                            return io.registerBuffers(BUFFER_SIZE, 2);
                        } catch (IllegalStateException alreadyRegistered) {
                            return alreadyRegistered;
                        }
                    }));
                }

                int winners = 0;
                int losers = 0;
                for (Future<Object> future : futures) {
                    Object outcome = future.get(20, TimeUnit.SECONDS);
                    if (outcome instanceof MemorySegment[]) {
                        winners++;
                    } else {
                        IllegalStateException rejected = assertInstanceOf(IllegalStateException.class, outcome,
                                "only an IllegalStateException may lose the registration race");
                        assertTrue(rejected.getMessage().contains("already registered"),
                                "the losing call must observe the published registration, got: "
                                        + rejected.getMessage());
                        losers++;
                    }
                }
                assertEquals(1, winners, "exactly one concurrent registration may install buffers");
                assertEquals(1, losers);

                int index = io.checkOutBuffer();
                assertTrue(index >= 0);
                io.checkInBuffer(index);
            }
        }
    }

    @Test
    void registrationIsRejectedAfterClose() {
        JUringBlocking io = new JUringBlocking(8, IORING_SETUP_SINGLE_ISSUER);
        io.close();
        // The lifecycle flag is safely published to threads that do not hold closeLock.
        assertThrows(IllegalStateException.class, () -> io.registerBuffers(BUFFER_SIZE, 1));
        assertThrows(IllegalStateException.class, () -> io.getRegisteredBuffer(0));
    }

    // ------------------------------------------------------------------
    // best-effort rollback on partial multi-worker failure
    // ------------------------------------------------------------------

    @Test
    void failedRegisterBuffersRollsBackEarlierWorkers() throws Exception {
        try (JUringBlocking io = new JUringBlocking(16, 2, IORING_SETUP_SINGLE_ISSUER)) {
            assertEquals(2, workerDispatchers(io).length);

            // Make the *last* ring reject the wrapper's registration so worker 0 is already
            // mutated when the failure happens (the wrapper installs the same buffers on all
            // rings, so this is the only deterministic way to produce a partial failure).
            // The injection must run on the shard thread: these rings are single-issuer, and the
            // kernel rejects resource registration from any other thread (EEXIST).
            registerBuffersOnWorker(io, 1, BUFFER_SIZE, 1);
            try {
                assertThrows(RuntimeException.class, () -> io.registerBuffers(BUFFER_SIZE, 2));
                // A failed registration publishes nothing.
                assertThrows(IllegalArgumentException.class, () -> io.getRegisteredBuffer(0));
            } finally {
                assertEquals(0, unregisterBuffersOnWorker(io, 1),
                        "the injected registration should unregister cleanly");
            }

            // Worker 0 was rolled back, so the retry can install the buffers on every ring.
            MemorySegment[] buffers = io.registerBuffers(BUFFER_SIZE, 2);
            assertEquals(2, buffers.length);
            int index = io.checkOutBuffer();
            assertTrue(index >= 0);
            io.checkInBuffer(index);
        }
    }

    @Test
    void failedRegisterFilesRollsBackEarlierWorkersAndKeepsUserDescriptorOpen() throws Exception {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0);
             JUringBlocking io = new JUringBlocking(16, 2, IORING_SETUP_SINGLE_ISSUER)) {
            assertEquals(2, workerDispatchers(io).length);

            registerFilesOnWorker(io, 1, fd.getFd());
            try {
                assertThrows(RuntimeException.class, () -> io.registerFiles(fd));
            } finally {
                assertEquals(0, unregisterFilesOnWorker(io, 1),
                        "the injected file table should unregister cleanly");
            }

            // The rollback must not have closed the caller's descriptor.
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(14);
                assertEquals(13L, io.read(fd, buffer, 14, 0),
                        "the user descriptor must survive the failed registration and its rollback");
            }

            // Worker 0 was rolled back, so the retry succeeds on every ring.
            assertEquals(0, io.registerFiles(fd));
        }
    }

    @Test
    void failedDirectOpenRollsBackEarlierWorkers() throws Exception {
        Path file = Files.createTempFile("juring-blocking-rollback", ".bin");
        Files.write(file, "rollback-me".getBytes(StandardCharsets.UTF_8));
        try (JUringBlocking io = new JUringBlocking(16, 2, IORING_SETUP_SINGLE_ISSUER)) {
            assertEquals(2, workerDispatchers(io).length);
            // Worker 0 has a two-slot table; worker 1 only one slot, so opening slot 1 succeeds
            // on worker 0 and fails on worker 1 (slot out of range) -> partial open.
            registerFilesOnWorker(io, 0, -1, -1);
            registerFilesOnWorker(io, 1, -1);
            try {
                int result = io.openDirect(file.toString(), READ.getValue(), 0, 1);
                assertTrue(result < 0, "the open must fail on the short-slot ring, got " + result);

                // The already-mutated worker 0 must have had the slot closed again: a fixed-file
                // read from that slot now reports EBADF instead of reading the opened file.
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(64);
                    assertEquals(-EBADF, readFixedOnWorker(io, 0, 1, buffer, 11, 0),
                            "the rollback must close the slot opened on worker 0");
                }
            } finally {
                assertEquals(0, unregisterFilesOnWorker(io, 0));
                assertEquals(0, unregisterFilesOnWorker(io, 1));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Object[] workers(JUringBlocking io) throws Exception {
        Field workersField = JUringBlocking.class.getDeclaredField("workers");
        workersField.setAccessible(true);
        Object[] workers = (Object[]) workersField.get(io);
        assertNotNull(workers);
        return workers;
    }

    /**
     * Test-only seam: exposes the per-worker dispatchers so a test can verify the rings exist.
     * The wrapper is not designed for this; it is only touched while the rings are idle.
     */
    private static IoUringDispatcher[] workerDispatchers(JUringBlocking io) throws Exception {
        Object[] workers = workers(io);
        IoUringDispatcher[] dispatchers = new IoUringDispatcher[workers.length];
        for (int i = 0; i < workers.length; i++) {
            dispatchers[i] = dispatcher(workers[i]);
            assertNotNull(dispatchers[i], "worker " + i + " has no dispatcher");
        }
        return dispatchers;
    }

    private static IoUringDispatcher dispatcher(Object worker) throws Exception {
        Field dispatcherField = worker.getClass().getDeclaredField("dispatcher");
        dispatcherField.setAccessible(true);
        return (IoUringDispatcher) dispatcherField.get(worker);
    }

    /**
     * Runs {@code action} on the worker's shard thread. The rings are
     * {@code IORING_SETUP_SINGLE_ISSUER} rings, so {@code io_uring_register} calls from any other
     * thread are rejected with {@code EEXIST} by the kernel; this mirrors what the wrapper's own
     * {@code runOnPoller} control path does.
     */
    private static Object callOnPoller(Object worker, Callable<Object> action) throws Exception {
        Method runOnPoller = worker.getClass().getDeclaredMethod("runOnPoller", Callable.class);
        runOnPoller.setAccessible(true);
        try {
            return runOnPoller.invoke(worker, action);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private static void registerBuffersOnWorker(JUringBlocking io, int workerIndex, int size, int count)
            throws Exception {
        Object worker = workers(io)[workerIndex];
        IoUringDispatcher shardDispatcher = dispatcher(worker);
        assertNotNull(callOnPoller(worker, () -> shardDispatcher.registerBuffers(size, count)));
    }

    private static int unregisterBuffersOnWorker(JUringBlocking io, int workerIndex) throws Exception {
        Object worker = workers(io)[workerIndex];
        IoUringDispatcher shardDispatcher = dispatcher(worker);
        return (int) callOnPoller(worker, shardDispatcher::unregisterBuffers);
    }

    private static void registerFilesOnWorker(JUringBlocking io, int workerIndex, int... fds) throws Exception {
        Object worker = workers(io)[workerIndex];
        IoUringDispatcher shardDispatcher = dispatcher(worker);
        assertNotNull(callOnPoller(worker, () -> shardDispatcher.registerFiles(fds)));
    }

    private static int unregisterFilesOnWorker(JUringBlocking io, int workerIndex) throws Exception {
        Object worker = workers(io)[workerIndex];
        IoUringDispatcher shardDispatcher = dispatcher(worker);
        return (int) callOnPoller(worker, shardDispatcher::unregisterFiles);
    }

    /**
     * Reads through a specific worker's ring with a fixed-file slot, without going through the
     * thread-to-worker hash of the public API, so a rollback on one ring can be observed.
     */
    private static long readFixedOnWorker(JUringBlocking io, int workerIndex, int indexFD,
                                          MemorySegment buffer, int length, long offset) throws Exception {
        Object worker = workers(io)[workerIndex];
        Method submit = worker.getClass().getDeclaredMethod("submitAndAwaitSlot",
                byte.class, byte.class, int.class, long.class, long.class, int.class, int.class,
                short.class, int.class, long.class);
        submit.setAccessible(true);
        try {
            return (long) submit.invoke(worker,
                    IoUringDispatcher.IORING_OP_READ,
                    SqeOptions.IOSQE_FIXED_FILE.value,
                    indexFD,
                    offset,
                    buffer.address(),
                    length,
                    0,
                    (short) 0,
                    0,
                    0L);
        } finally {
            Reference.reachabilityFence(buffer);
        }
    }
}
