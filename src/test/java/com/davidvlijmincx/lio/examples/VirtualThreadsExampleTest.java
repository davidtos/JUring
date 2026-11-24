package com.davidvlijmincx.lio.examples;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUringBlocking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Virtual threads blocked on one shared {@link JUringBlocking}.
 *
 * <p><b>seastar mapping:</b> the seastar futures + reactor model, from the caller's point
 * of view. seastar user code is single-threaded per shard: async work returns a
 * {@code future<T>} and continuations run on the reactor; code never parks a thread.
 * JUring offers the opposite ergonomics for ordinary blocking code: {@code read} and
 * {@code write} look synchronous, but the waiting virtual thread unmounts from its
 * carrier while dedicated poller threads submit SQEs and reap CQEs.
 *
 * <p><b>JUring ↔ seastar difference:</b> {@code JUringBlocking} is not a futures runtime.
 * Each blocking call is an ordinary method call that parks the calling virtual thread
 * (via {@code LockSupport.park}) until that request's completion arrives; there is no
 * {@code .then()} chain, no coroutine scheduler and no task graph. The platform-thread
 * budget is explicit and bounded: {@code new JUringBlocking(64, 1)} creates one poller
 * thread (plus the JDK's virtual-thread carrier pool), regardless of how many virtual
 * threads call it — here {@value #VIRTUAL_THREADS} virtual threads issue concurrent
 * asynchronous I/O through that one poller. seastar instead fixes one reactor thread per
 * shard and expresses concurrency in futures, which it multiplexes cooperatively; a
 * blocking call is an anti-pattern there, while here it is the supported surface.
 *
 * <p>The test starts the eight virtual threads behind a barrier so their requests are
 * actually concurrent, has each virtual thread write a distinct record at its own offset
 * and then read it back through the same blocking ring, and asserts that every virtual
 * thread observed its own bytes and that the final file is the concatenation of all
 * records. It is deterministic and bounded: no timing assertion, no unbounded thread or
 * buffer growth, and everything uses a JUnit {@link TempDir}.
 */
@Timeout(120)
class VirtualThreadsExampleTest {

    private static final int VIRTUAL_THREADS = 8;
    private static final int RECORD = 32;

    @Test
    void eightVirtualThreadsBlockOnOneRing(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("virtual-threads-example.bin");

        try (JUringBlocking blocking = new JUringBlocking(64, 1);
             FileDescriptor fd = new FileDescriptor(path, 0644, RDWR, CREATE, TRUNCATE);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            CountDownLatch ready = new CountDownLatch(VIRTUAL_THREADS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<byte[]>> futures = new ArrayList<>(VIRTUAL_THREADS);

            for (int index = 0; index < VIRTUAL_THREADS; index++) {
                int record = index;
                futures.add(executor.submit(() -> {
                    assertTrue(Thread.currentThread().isVirtual(),
                            "the example targets virtual threads");
                    byte[] payload = payload(record);
                    ready.countDown();
                    start.await();
                    try (Arena arena = Arena.ofConfined()) {
                        long written = blocking.write(fd, payload, (long) record * RECORD);
                        assertEquals(RECORD, written,
                                "record " + record + " must be written completely");

                        MemorySegment buffer = arena.allocate(RECORD);
                        long read = blocking.read(fd, buffer, RECORD, (long) record * RECORD);
                        assertEquals(RECORD, read,
                                "record " + record + " must be read back completely");
                        byte[] observed = buffer.toArray(JAVA_BYTE);
                        assertArrayEquals(payload, observed,
                                "record " + record + " must round-trip through the blocking ring");

                        // Returning the data lets the test assert the file layout afterwards.
                        return observed;
                    }
                }));
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS),
                    "all virtual threads must reach the I/O barrier");
            start.countDown();

            byte[][] observed = new byte[VIRTUAL_THREADS][];
            for (int index = 0; index < VIRTUAL_THREADS; index++) {
                observed[index] = futures.get(index).get(60, TimeUnit.SECONDS);
                assertArrayEquals(payload(index), observed[index],
                        "virtual thread " + index + " must complete with its own data");
            }

            assertEquals((long) VIRTUAL_THREADS * RECORD, Files.size(path));
            assertArrayEquals(concatenatedPayloads(), Files.readAllBytes(path),
                    "the file must contain every virtual thread's record at its own offset");
        }
    }

    private static byte[] payload(int record) {
        byte[] data = new byte[RECORD];
        byte[] tag = ("vt-" + record).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, data, 0, tag.length);
        for (int i = tag.length; i < RECORD; i++) {
            data[i] = (byte) (record * 17 + i);
        }
        assertNotEquals(0, data.length);
        return data;
    }

    private static byte[] concatenatedPayloads() {
        byte[] all = new byte[VIRTUAL_THREADS * RECORD];
        for (int record = 0; record < VIRTUAL_THREADS; record++) {
            System.arraycopy(payload(record), 0, all, record * RECORD, RECORD);
        }
        return all;
    }
}
