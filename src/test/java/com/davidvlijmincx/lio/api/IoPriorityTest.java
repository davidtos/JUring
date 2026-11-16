package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B10b tests: {@link IoPriority} encoding, ring/handle wiring into the SQE, and kernel
 * acceptance. No latency/throughput assertions are made: the host NVMe device runs the
 * {@code none} scheduler, where {@code ioprio} is accepted but has no ordering effect
 * (see {@code docs/b10-design.md}).
 */
class IoPriorityTest {

    private static final int BLOCK = 4096;

    private JUring ring;

    @BeforeEach
    void setUp() {
        ring = new JUring(64, IORING_SETUP_SINGLE_ISSUER);
    }

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close();
            ring = null;
        }
    }

    // ------------------------------------------------------------------
    // encoding / validation
    // ------------------------------------------------------------------

    @Test
    void encodingAndValidation() {
        assertEquals((short) 0, IoPriority.NONE);
        assertEquals((short) 0, IoPriority.ioprio(IoPriority.CLASS_NONE, 0));
        assertEquals((short) 0x4000, IoPriority.bestEffort(0));
        assertEquals((short) 0x4000, IoPriority.ioprio(IoPriority.CLASS_BE, 0));
        assertEquals((short) 0x4007, IoPriority.bestEffort(7));
        assertEquals((short) 0x2000, IoPriority.realTime(0));
        assertEquals((short) 0x2002, IoPriority.realTime(2));
        assertEquals((short) 0x6000, IoPriority.idle());
        assertEquals(13, IoPriority.CLASS_SHIFT);
        assertEquals(7, IoPriority.MAX_LEVEL);

        assertThrows(IllegalArgumentException.class, () -> IoPriority.ioprio(4, 0));
        assertThrows(IllegalArgumentException.class, () -> IoPriority.ioprio(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> IoPriority.bestEffort(8));
        assertThrows(IllegalArgumentException.class, () -> IoPriority.bestEffort(-1));
        assertThrows(IllegalArgumentException.class, () -> IoPriority.ioprio(IoPriority.CLASS_NONE, 1),
                "class NONE carrying data is rejected by the kernel (ioprio_check_cap)");
        assertThrows(IllegalArgumentException.class, () -> IoPriority.ioprio(IoPriority.CLASS_IDLE, 1));
    }

    @Test
    void validityMatchesKernelEncodingRules() {
        assertTrue(IoPriority.isValid((short) 0));
        assertTrue(IoPriority.isValid(IoPriority.bestEffort(0)));
        assertTrue(IoPriority.isValid(IoPriority.bestEffort(7)));
        assertTrue(IoPriority.isValid(IoPriority.realTime(7)));
        assertTrue(IoPriority.isValid(IoPriority.idle()));
        assertFalse(IoPriority.isValid((short) 0x4008), "BE level 8 is out of range");
        assertFalse(IoPriority.isValid((short) 0x6001), "IDLE carries no data");
        assertFalse(IoPriority.isValid((short) 0x8000), "class 4 is invalid");
        assertFalse(IoPriority.isValid((short) 0x0001), "class NONE carrying data is invalid");
    }

    @Test
    void invalidValuesAreRejectedByTheSetters(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("priority-setter.bin");
        Files.write(path, pattern(BLOCK));

        assertThrows(IllegalArgumentException.class, () -> ring.setDefaultIoprio((short) 0x8000));
        assertThrows(IllegalArgumentException.class, () -> ring.setLastSqeIoprio((short) 0x4008));
        // no SQE prepared yet on this ring
        assertThrows(IllegalStateException.class, () -> ring.setLastSqeIoprio(IoPriority.NONE));

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            assertThrows(IllegalArgumentException.class, () -> file.withIoPriority((short) 0x0001));
            assertEquals((short) 0, file.ioPriority());
        }

        // the raw escape hatch rejects opcodes that cannot carry ioprio
        long nop = ring.prepareNop();
        assertThrows(IllegalArgumentException.class, () -> ring.setLastSqeIoprio(IoPriority.bestEffort(1)));
        ring.submit();
        assertEquals(0, ring.waitForResult().result());
    }

    @Test
    void realTimeClassRequiresPrivilegeOrSucceedsWhenGranted(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("priority-rt.bin");
        Files.write(path, pattern(BLOCK));

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            file.withIoPriority(IoPriority.realTime(1));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                long handle = file.prepareRead(0, BLOCK, buffer);
                try {
                    assertEquals(BLOCK, file.await(handle));
                } catch (JuringException e) {
                    assertEquals(1, e.errno(),
                            "without CAP_SYS_ADMIN/CAP_SYS_NICE the RT class must fail with EPERM");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // SQE wiring
    // ------------------------------------------------------------------

    @Test
    void defaultPriorityIsWrittenIntoReadWriteSqesOnly(@TempDir Path dir) throws Exception {
        ring.setDefaultIoprio(IoPriority.bestEffort(3));

        // NOP does not carry ioprio: the kernel rejects the request if the field is set
        // (verified: -EINVAL), so the ring default must not be written into it.
        long nop = ring.prepareNop();
        assertEquals((short) 0, Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                "a non-read/write opcode must not be tagged with the ring default");
        ring.submit();
        Result nopResult = ring.waitForResult();
        assertEquals(nop, nopResult.id());
        assertEquals(0, nopResult.result());

        Path path = dir.resolve("default-priority.bin");
        Files.write(path, pattern(BLOCK));
        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                long handle = file.prepareRead(0, BLOCK, buffer);
                assertEquals(IoPriority.bestEffort(3), Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                        "the ring default must be written into a prepared read SQE");
                assertEquals(BLOCK, file.await(handle));
            }
        }
    }

    @Test
    void lastSqePriorityOverridesTheRingDefault(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("last-sqe-override.bin");
        Files.write(path, pattern(BLOCK));

        ring.setDefaultIoprio(IoPriority.bestEffort(0));
        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                long handle = file.prepareRead(0, BLOCK, buffer);
                assertEquals(IoPriority.bestEffort(0), Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                        "the ring default is applied first");
                ring.setLastSqeIoprio(IoPriority.idle());
                assertEquals(IoPriority.idle(), Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                        "the raw per-SQE setter overrides the ring default");
                assertEquals(BLOCK, file.await(handle));
            }
        }
    }

    @Test
    void handlePriorityIsAppliedOnEveryPrepareSite(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("priority-sites.bin");

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            file.withIoPriority(IoPriority.bestEffort(2));
            assertEquals(IoPriority.bestEffort(2), file.ioPriority());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                for (int i = 0; i < BLOCK; i++) {
                    buffer.set(JAVA_BYTE, i, (byte) i);
                }

                long writeHandle = file.prepareWrite(0, buffer);
                assertEquals(IoPriority.bestEffort(2), Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                        "prepareWrite must carry the handle priority");
                assertEquals(BLOCK, file.await(writeHandle));

                long syncHandle = file.prepareFsync(true);
                assertEquals((short) 0, Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                        "fsync does not carry ioprio; the handle priority must not be applied");
                assertEquals(0, file.await(syncHandle));

                long truncateHandle = file.prepareTruncate(BLOCK);
                assertEquals((short) 0, Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                        "ftruncate does not carry ioprio; the handle priority must not be applied");
                assertEquals(0, file.await(truncateHandle));

                long fallocateHandle = file.prepareFallocate(0, BLOCK, 0);
                assertEquals((short) 0, Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                        "fallocate does not carry ioprio; the handle priority must not be applied");
                assertEquals(0, file.await(fallocateHandle));

                long readHandle = file.prepareRead(0, BLOCK, buffer);
                assertEquals(IoPriority.bestEffort(2), Sqe.getIoprio(ring.lastPreparedSqeAddress()),
                        "prepareRead must carry the handle priority");
                assertEquals(BLOCK, file.await(readHandle));
            }

            // a handle priority of zero leaves the SQE field unset (kernel inherits the thread)
            file.withIoPriority(IoPriority.NONE);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                long handle = file.prepareRead(0, BLOCK, buffer);
                assertEquals((short) 0, Sqe.getIoprio(ring.lastPreparedSqeAddress()));
                assertEquals(BLOCK, file.await(handle));
            }
        }
    }

    @Test
    void timedOperationTagsTheTargetButNotTheLinkTimeoutCompanion(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("priority-timed.bin");
        Files.write(path, pattern(BLOCK));

        try (JuringFile file = JuringFile.open(ring, path, READ)) {
            file.withIoPriority(IoPriority.bestEffort(1));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                long target = file.prepareRead(0, BLOCK, buffer, 1_000);

                long targetSqe = ring.previousPreparedSqeAddress();
                long companionSqe = ring.lastPreparedSqeAddress();
                assertNotEquals(0L, targetSqe);
                assertNotEquals(0L, companionSqe);
                assertNotEquals(targetSqe, companionSqe);
                assertEquals(IoPriority.bestEffort(1), Sqe.getIoprio(targetSqe),
                        "the target SQE must carry the handle priority");
                assertEquals((short) 0, Sqe.getIoprio(companionSqe),
                        "the LINK_TIMEOUT companion must not be tagged");

                assertEquals(BLOCK, file.await(target));
                assertEquals((byte) 7, buffer.get(JAVA_BYTE, 0));
            }
        }
    }

    // ------------------------------------------------------------------
    // end to end (acceptance only, no effect claim)
    // ------------------------------------------------------------------

    @Test
    void prioritizedReadWriteAndSyncComplete(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("priority-end-to-end.bin");
        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            file.withIoPriority(IoPriority.bestEffort(1));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(BLOCK);
                for (int i = 0; i < BLOCK; i++) {
                    buffer.set(JAVA_BYTE, i, (byte) i);
                }
                long write = file.prepareWrite(0, buffer);
                assertEquals(BLOCK, file.await(write));

                long sync = file.prepareFsync(true);
                assertEquals(0, file.await(sync));

                MemorySegment readBack = arena.allocate(BLOCK);
                long read = file.prepareRead(0, BLOCK, readBack);
                assertEquals(BLOCK, file.await(read));
                for (int i = 0; i < BLOCK; i++) {
                    assertEquals((byte) i, readBack.get(JAVA_BYTE, i), "byte " + i);
                }
            }
        }
    }

    private static byte[] pattern(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }
}
