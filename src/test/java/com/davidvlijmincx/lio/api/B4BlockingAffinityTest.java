package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_COOP_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_DEFER_TASKRUN;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §12.19 — {@code JUringBlocking(RingGroup)} inherits the group's CPU pinning.
 * Affinity is best-effort (§3.5/§11.6), so the hard assertions are the requested CPU and the
 * running/closed lifecycle; {@code currentCpu == requestedCpu} is asserted when the kernel
 * accepted the affinity mask.
 */
@Timeout(30)
class B4BlockingAffinityTest {

    private static final int BASE_CPU = 2;

    @TempDir
    Path tempDir;

    @Test
    void affinityInheritedFromGroup() throws Exception {
        Path file = tempDir.resolve("read_file");
        Files.write(file, "Hello, World!".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        RingGroup group = RingGroup.create(2, ShardOptions.defaults()
                .withName("b4-blocking-affinity")
                .withRingEntries(64)
                .withRingOptions(IORING_SETUP_SINGLE_ISSUER,
                        IORING_SETUP_COOP_TASKRUN, IORING_SETUP_DEFER_TASKRUN)
                .withCpuAffinity(BASE_CPU));
        JUringBlocking blocking = new JUringBlocking(group);
        try (FileDescriptor fd = new FileDescriptor(file, READ, 0);
             Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(14);
            assertEquals(13L, blocking.read(fd, buffer, 14, 0));

            List<ShardStats> stats = group.stats();
            assertEquals(2, stats.size());
            for (int i = 0; i < stats.size(); i++) {
                ShardStats shard = stats.get(i);
                assertTrue(shard.started(), "shard " + i + " must be running while the blocking façade is open");
                assertEquals(BASE_CPU + i, shard.requestedCpu(),
                        "blocking poller " + i + " must inherit the group's base CPU pinning");
                if (shard.affinityApplied()) {
                    assertEquals(BASE_CPU + i, shard.currentCpu(),
                            "shard " + i + " reports an applied affinity but runs on the wrong CPU");
                }
                assertTrue(shard.threadName().startsWith("juring-shard-"),
                        "worker must be hosted by a shard thread: " + shard.threadName());
            }
        } finally {
            blocking.close();
        }
        assertTrue(group.isClosed());
        for (ShardStats shard : group.stats()) {
            assertTrue(shard.closed(), "group stats must remain readable and report closed after close()");
        }
    }
}
