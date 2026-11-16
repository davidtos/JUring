package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the multi-worker registered-buffer lifetime: the iovec table used to be
 * allocated in worker-0's dispatcher arena while being registered on every worker ring. Closing
 * the group tears workers down in order, so worker-0's memory could be freed while sibling rings
 * still had registered-buffer I/O in flight (use-after-free at shutdown).
 *
 * <p>The buffers now live in a dedicated {@link java.lang.foreign.Arena} owned by the wrapper and
 * freed only after every shard terminated. This test closes the wrapper while several virtual
 * threads are issuing fixed-buffer reads on four pollers.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JUringBlockingBufferLifetimeTest {

    @Test
    void closeWithInFlightRegisteredBufferIoDoesNotCrash(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("lifetime.bin");
        byte[] data = new byte[64 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xff);
        }
        Files.write(file, data);

        JUringBlocking io = new JUringBlocking(64, 4);
        io.registerBuffers(4096, 4);

        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(8);
        CountDownLatch finished = new CountDownLatch(8);

        try (FileDescriptor fd = new FileDescriptor(file, READ)) {
            for (int t = 0; t < 8; t++) {
                final int bufferIndex = t % 4;
                Thread.startVirtualThread(() -> {
                    started.countDown();
                    try {
                        for (int i = 0; i < 2_000; i++) {
                            long result = io.readFixed(fd, 4096, (long) (i % 16) * 4096, bufferIndex);
                            if (result < 0) {
                                // -ECANCELED / -EBADF are expected once close() cancels the I/O.
                                break;
                            }
                        }
                    } catch (IllegalStateException closed) {
                        // Expected: the wrapper stopped accepting operations mid-flight.
                    } catch (Throwable t1) {
                        unexpected.compareAndSet(null, t1);
                    } finally {
                        finished.countDown();
                    }
                });
            }

            assertTrue(started.await(10, TimeUnit.SECONDS), "readers must start");
            Thread.sleep(50); // let fixed-buffer I/O reach the worker rings
            io.close(1_000);
        } finally {
            // Close must be idempotent even after the in-flight close above.
            io.close(1_000);
        }

        assertTrue(finished.await(10, TimeUnit.SECONDS), "parked readers must be unblocked by close");
        assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());
        assertEquals(0, finished.getCount());
    }
}
