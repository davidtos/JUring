package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

class ZeroGcSqeTest {

    private Arena arena;
    private MemorySegment sqeSegment;
    private long sqeAddress;

    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
        sqeSegment = arena.allocate(ZeroGcSqe.SQE_SIZE);
        sqeAddress = sqeSegment.address();
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    @Test
    void testZeroClearsAll64Bytes() {
        // Fill memory with non-zero bytes
        sqeSegment.fill((byte) 0xFF);
        for (long i = 0; i < ZeroGcSqe.SQE_SIZE; i++) {
            assertEquals((byte) 0xFF, sqeSegment.get(ValueLayout.JAVA_BYTE, i));
        }

        // Call zero
        ZeroGcSqe.zero(sqeAddress);

        // Verify all 64 bytes are 0
        for (long i = 0; i < ZeroGcSqe.SQE_SIZE; i++) {
            assertEquals((byte) 0, sqeSegment.get(ValueLayout.JAVA_BYTE, i), "Byte at offset " + i + " was not zeroed");
        }
    }

    @Test
    void testGettersAndSetters() {
        ZeroGcSqe.zero(sqeAddress);

        ZeroGcSqe.setOpcode(sqeAddress, (byte) 22);
        assertEquals((byte) 22, ZeroGcSqe.getOpcode(sqeAddress));

        ZeroGcSqe.setFlags(sqeAddress, (byte) 0x02);
        assertEquals((byte) 0x02, ZeroGcSqe.getFlags(sqeAddress));

        ZeroGcSqe.addFlags(sqeAddress, (byte) 0x04);
        assertEquals((byte) 0x06, ZeroGcSqe.getFlags(sqeAddress));

        ZeroGcSqe.setIoprio(sqeAddress, (short) 123);
        assertEquals((short) 123, ZeroGcSqe.getIoprio(sqeAddress));

        ZeroGcSqe.setFd(sqeAddress, 42);
        assertEquals(42, ZeroGcSqe.getFd(sqeAddress));

        ZeroGcSqe.setOff(sqeAddress, 1024L);
        assertEquals(1024L, ZeroGcSqe.getOff(sqeAddress));

        ZeroGcSqe.setAddr(sqeAddress, 0x123456789ABCDEFL);
        assertEquals(0x123456789ABCDEFL, ZeroGcSqe.getAddr(sqeAddress));

        ZeroGcSqe.setLen(sqeAddress, 4096);
        assertEquals(4096, ZeroGcSqe.getLen(sqeAddress));

        ZeroGcSqe.setUserData(sqeAddress, 0xFEEDFACEDEADBEEFL);
        assertEquals(0xFEEDFACEDEADBEEFL, ZeroGcSqe.getUserData(sqeAddress));

        ZeroGcSqe.setFileIndex(sqeAddress, 5);
        assertEquals(5, ZeroGcSqe.getFileIndex(sqeAddress));
    }

    @Test
    void testZeroGcUserDataEncoding() {
        long ptr = 0x00007FFFF7DC1234L;
        long encoded = ZeroGcUserData.encode(OperationType.READ, ptr);

        assertEquals(OperationType.READ, ZeroGcUserData.getType(encoded));
        assertEquals(ptr, ZeroGcUserData.getPayload(encoded));

        // Test fixed buffer encoding
        int bufIdx = 42;
        long opId = 987654321L;
        long encodedFixed = ZeroGcUserData.encodeFixed(OperationType.READ_FIXED, bufIdx, opId);

        assertEquals(OperationType.READ_FIXED, ZeroGcUserData.getType(encodedFixed));
        assertEquals(bufIdx, ZeroGcUserData.getBufferIndex(encodedFixed));
        assertEquals(opId, ZeroGcUserData.getFixedOpId(encodedFixed));
    }

    @Test
    void testZeroGcUserDataEdgeCases() {
        // High payload (56 bits max: 0x00FFFFFFFFFFFFFF)
        long maxPayload = 0x00FFFFFFFFFFFFFFL;
        long encodedMax = ZeroGcUserData.encode(OperationType.MULTISHOT_ACCEPT, maxPayload);
        assertEquals(OperationType.MULTISHOT_ACCEPT, ZeroGcUserData.getType(encodedMax));
        assertEquals(maxPayload, ZeroGcUserData.getPayload(encodedMax));

        // Max bufferIndex (16 bits: 65535) and max fixed opId (40 bits: 0xFFFFFFFFFF)
        int maxBufIdx = 0xFFFF;
        long maxFixedOpId = 0xFFFFFFFFFFL;
        long encodedFixedMax = ZeroGcUserData.encodeFixed(OperationType.WRITE_FIXED, maxBufIdx, maxFixedOpId);
        assertEquals(OperationType.WRITE_FIXED, ZeroGcUserData.getType(encodedFixedMax));
        assertEquals(maxBufIdx, ZeroGcUserData.getBufferIndex(encodedFixedMax));
        assertEquals(maxFixedOpId, ZeroGcUserData.getFixedOpId(encodedFixedMax));
    }

    @Test
    void testUnifiedResultReusableState() {
        Result result = new Result();
        assertEquals(0L, result.id());
        assertEquals(0L, result.result());
        assertNull(result.type());

        result.set(100L, 50L, OperationType.READ, 0L, null, -1, null);
        assertEquals(100L, result.id());
        assertEquals(50L, result.result());
        assertEquals(OperationType.READ, result.type());
        assertTrue(result.isSuccess());

        // Reusable reset
        result.set(101L, -9L, OperationType.CLOSE, 0L, null, -1, null);
        assertEquals(101L, result.id());
        assertEquals(-9L, result.result());
        assertEquals(OperationType.CLOSE, result.type());
        assertFalse(result.isSuccess());
    }

    @Test
    void testZeroAllocationRingPolling() {
        try (JUring ring = new JUring(8, IoUringOptions.IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", LinuxOpenOptions.READ, 0)) {

            long id = ring.prepareRead(fd, 14, 0);
            ring.submit();

            // Use a single pre-allocated reusable Result instance
            Result reusable = new Result();
            Result returned = ring.waitForResult(reusable);

            assertSame(reusable, returned);
            assertEquals(id, reusable.id());
            assertEquals(13, reusable.result());
            assertEquals(OperationType.READ, reusable.type());
            reusable.freeBuffer();

            // Submit another read and consume via peekForBatchResult(consumer)
            long id2 = ring.prepareRead(fd, 14, 0);
            ring.submit();

            int[] consumedCount = new int[1];
            long[] consumedId = new long[1];

            // Wait until cqe is available
            while (consumedCount[0] == 0) {
                ring.peekForBatchResult(1, r -> {
                    consumedCount[0]++;
                    consumedId[0] = r.id();
                    r.freeBuffer();
                });
                Thread.onSpinWait();
            }

            assertEquals(1, consumedCount[0]);
            assertEquals(id2, consumedId[0]);
        }
    }
}
