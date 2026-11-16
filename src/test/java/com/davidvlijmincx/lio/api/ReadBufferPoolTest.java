package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ownership-ledger tests for {@link ReadBufferPool} and the public pooled-read path.
 *
 * <p>Before the fix the pool only tracked a free-stack top: a second check-in of the same
 * completion was accepted, and a full pool freed the same native address twice. The ledger now
 * rejects foreign addresses and double check-ins and refuses to grow past its capacity.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReadBufferPoolTest {

    @Test
    void exhaustsAtCapacityAndReusesReturnedSlot() {
        ReadBufferPool pool = new ReadBufferPool(2, 64);
        long first = pool.checkOut();
        long second = pool.checkOut();
        assertTrue(first != 0L && second != 0L);
        assertEquals(2, pool.outstanding());
        pool.checkIn(first);
        assertEquals(1, pool.outstanding());
        assertEquals(first, pool.checkOut(), "the returned address must be reusable");
        pool.checkIn(first);
        pool.checkIn(second);
        assertEquals(0, pool.outstanding());
        pool.close();
    }

    @Test
    void exhaustionThrowsInsteadOfAllocatingOutsideCapacity() {
        ReadBufferPool pool = new ReadBufferPool(1, 64);
        pool.checkOut();
        assertThrows(IllegalStateException.class, pool::checkOut,
                "an exhausted pool must not silently malloc more memory");
        pool.close();
    }

    @Test
    void doubleCheckInIsRejected() {
        ReadBufferPool pool = new ReadBufferPool(2, 64);
        long address = pool.checkOut();
        pool.checkIn(address);
        assertThrows(IllegalStateException.class, () -> pool.checkIn(address),
                "a second check-in must not be enqueued twice");
        pool.close();
    }

    @Test
    void foreignAddressIsRejected() {
        ReadBufferPool pool = new ReadBufferPool(1, 64);
        assertThrows(IllegalArgumentException.class, () -> pool.checkIn(0xDEADBEEFL));
        pool.close();
    }

    @Test
    void closeIsIdempotent() {
        ReadBufferPool pool = new ReadBufferPool(2, 64);
        pool.close();
        assertDoesNotThrow(pool::close);
        assertThrows(IllegalStateException.class, pool::checkOut, "a closed pool must not hand out buffers");
    }

    @Test
    void pooledReadDoubleCheckInIsRejectedThroughPublicApi(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("pooled-read.bin");
        Files.write(file, new byte[4096]);

        try (JUring ring = new JUring(8);
             FileDescriptor fd = new FileDescriptor(file, READ)) {
            ring.setupReadBufferPool(4096, 1);
            ring.prepareReadPooled(fd, 4096, 0);
            ring.submit();

            boolean[] secondCheckInRejected = {false};
            int[] reaped = {0};
            ring.submitAndWaitForBatchResult(1, 1, result -> {
                reaped[0]++;
                ring.checkInReadBuffer(result);
                try {
                    ring.checkInReadBuffer(result);
                } catch (IllegalStateException expected) {
                    secondCheckInRejected[0] = true;
                }
            });

            assertEquals(1, reaped[0]);
            assertTrue(secondCheckInRejected[0],
                    "the public check-in path must reject a double check-in");
        }
    }
}
