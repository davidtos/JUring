package com.davidvlijmincx.lio.blackpanda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackPandaConcurrencyTest {

    @Test
    void test500ConcurrentVirtualThreads(@TempDir Path tempDir) throws Exception {
        int threadCount = 500;
        int msgsPerThread = 100;
        int totalMessages = threadCount * msgsPerThread;

        long segmentSize = 512L * 1024L * 1024L; // 512 MB
        try (UringPartitionLog log = new UringPartitionLog(tempDir, segmentSize, 4096, 65536, 4 * 1024 * 1024)) {

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger failureCount = new AtomicInteger(0);

            ConcurrentSkipListSet<Long> committedOffsets = new ConcurrentSkipListSet<>();

            // Launch 512 concurrent Virtual Threads
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                Thread.ofVirtual().name("vt-producer-" + threadId).start(() -> {
                    try {
                        startLatch.await();
                        try (Arena threadArena = Arena.ofConfined()) {
                            MemorySegment payload = threadArena.allocate(1024);
                            for (int m = 0; m < msgsPerThread; m++) {
                                payload.set(ValueLayout.JAVA_INT, 0, threadId);
                                payload.set(ValueLayout.JAVA_INT, 4, m);
                                long offset = log.append(payload);
                                committedOffsets.add(offset);
                            }
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        failureCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Also launch concurrent tailing consumers
            AtomicLong consumedCount = new AtomicLong(0);
            AtomicInteger consumerRunning = new AtomicInteger(1);
            Thread consumerThread = Thread.ofVirtual().name("vt-tail-consumer").start(() -> {
                try (Arena consumerArena = Arena.ofConfined()) {
                    MemorySegment dst = consumerArena.allocate(1024);
                    long readOffset = 0L;
                    while (consumerRunning.get() == 1 || readOffset < totalMessages) {
                        int bytesRead = log.readTail(readOffset, dst);
                        if (bytesRead > 0) {
                            consumedCount.incrementAndGet();
                            readOffset++;
                        } else if (bytesRead == 0) {
                            Thread.yield();
                        } else {
                            // Lagging signal, advance
                            readOffset++;
                        }
                    }
                }
            });

            long startTime = System.nanoTime();
            startLatch.countDown();

            Thread.ofPlatform().daemon().start(() -> {
                while (doneLatch.getCount() > 0) {
                    System.out.printf("[Progress] Remaining threads: %d, Flushed: %d, HighWatermark: %d%n",
                            doneLatch.getCount(), log.getFlushedPosition(), log.getHighWatermark());
                    try { Thread.sleep(1000); } catch (Exception ignored) {}
                }
            });

            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000L;
            consumerRunning.set(0);
            consumerThread.join(2000L);

            assertTrue(finished, "All 512 Virtual Threads should complete within 30 seconds");
            assertEquals(0, failureCount.get(), "No thread failures occurred");
            assertEquals(totalMessages, committedOffsets.size(), "All " + totalMessages + " unique offsets were committed");
            assertEquals(0L, committedOffsets.first(), "First offset is 0");
            assertEquals(totalMessages - 1L, committedOffsets.last(), "Last offset is totalMessages - 1");

            System.out.printf("[BlackPandaConcurrencyTest] 512 Virtual Threads committed %,d messages in %,d ms (%,.0f msg/s)%n",
                    totalMessages, elapsedMs, (totalMessages * 1000.0 / elapsedMs));

            // Verify with historic reader
            try (UringHistoricReader historicReader = log.createHistoricReader(64)) {
                historicReader.seek(0L);
                AtomicInteger historicReadCount = new AtomicInteger(0);
                int read = historicReader.readHistoric(totalMessages, (offset, timestamp, payload) -> {
                    historicReadCount.incrementAndGet();
                });
                assertEquals(totalMessages, read, "Historic reader should read back all records from NVMe disk");
            }
        }
    }
}
