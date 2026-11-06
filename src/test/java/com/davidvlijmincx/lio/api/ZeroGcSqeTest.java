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
        sqeSegment.fill((byte) 0xFF);
        for (long i = 0; i < ZeroGcSqe.SQE_SIZE; i++) {
            assertEquals((byte) 0xFF, sqeSegment.get(ValueLayout.JAVA_BYTE, i));
        }

        ZeroGcSqe.zero(sqeAddress);

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
    void testAbsoluteOffsetsSqe() {
        assertEquals(64, ZeroGcSqe.SQE_SIZE, "io_uring_sqe must be exactly 64 bytes");

        sqeSegment.fill((byte) 0);

        sqeSegment.set(ValueLayout.JAVA_BYTE, 0, (byte) 22);
        assertEquals((byte) 22, ZeroGcSqe.getOpcode(sqeAddress));
        ZeroGcSqe.setOpcode(sqeAddress, (byte) 23);
        assertEquals((byte) 23, sqeSegment.get(ValueLayout.JAVA_BYTE, 0));

        sqeSegment.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x05);
        assertEquals((byte) 0x05, ZeroGcSqe.getFlags(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_SHORT, 2, (short) 100);
        assertEquals((short) 100, ZeroGcSqe.getIoprio(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_INT, 4, 1234);
        assertEquals(1234, ZeroGcSqe.getFd(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_LONG, 8, 0x1122334455667788L);
        assertEquals(0x1122334455667788L, ZeroGcSqe.getOff(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_LONG, 16, 0x8877665544332211L);
        assertEquals(0x8877665544332211L, ZeroGcSqe.getAddr(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_INT, 24, 4096);
        assertEquals(4096, ZeroGcSqe.getLen(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_INT, 28, 0x07);
        assertEquals(0x07, ZeroGcSqe.getRwFlags(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_LONG, 32, 0xCAFEBABE12345678L);
        assertEquals(0xCAFEBABE12345678L, ZeroGcSqe.getUserData(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_SHORT, 40, (short) 42);
        assertEquals((short) 42, ZeroGcSqe.getBufIndex(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_INT, 44, 99);
        assertEquals(99, ZeroGcSqe.getFileIndex(sqeAddress));
    }

    @Test
    void testAbsoluteOffsetsCqe() {
        assertEquals(16, ZeroGcCqe.CQE_LAYOUT.byteSize(), "io_uring_cqe must be exactly 16 bytes");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cqe = a.allocate(16);
            long addr = cqe.address();

            cqe.set(ValueLayout.JAVA_LONG, 0, 0x123456789ABCDEF0L);
            assertEquals(0x123456789ABCDEF0L, ZeroGcCqe.getUserData(addr));

            cqe.set(ValueLayout.JAVA_INT, 8, 4096);
            assertEquals(4096, ZeroGcCqe.getRes(addr));

            cqe.set(ValueLayout.JAVA_INT, 12, 0x02);
            assertEquals(0x02, ZeroGcCqe.getFlags(addr));
        }
    }

    @Test
    void testAbsoluteOffsetsParams() {
        assertEquals(120, io_uring_params.layout().byteSize(), "io_uring_params must be exactly 120 bytes");
        assertEquals(0, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("sq_entries")));
        assertEquals(4, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("cq_entries")));
        assertEquals(8, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("flags")));
        assertEquals(12, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("sq_thread_cpu")));
        assertEquals(16, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("sq_thread_idle")));
        assertEquals(20, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("features")));
        assertEquals(24, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("wq_fd")));
        assertEquals(40, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("sq_off")));
        assertEquals(80, io_uring_params.layout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("cq_off")));
    }

    @Test
    void testZeroGcUserDataEncoding() {
        long ptr = 0x00007FFFF7DC1234L;
        long encoded = ZeroGcUserData.encode(OperationType.READ, ptr);

        assertEquals(OperationType.READ, ZeroGcUserData.getType(encoded));
        assertEquals(ptr, ZeroGcUserData.getPayload(encoded));

        int bufIdx = 42;
        long opId = 987654321L;
        long encodedFixed = ZeroGcUserData.encodeFixed(OperationType.READ_FIXED, bufIdx, opId);

        assertEquals(OperationType.READ_FIXED, ZeroGcUserData.getType(encodedFixed));
        assertEquals(bufIdx, ZeroGcUserData.getBufferIndex(encodedFixed));
        assertEquals(opId, ZeroGcUserData.getFixedOpId(encodedFixed));
    }

    @Test
    void testZeroGcUserDataEdgeCases() {
        long maxPayload = 0x00FFFFFFFFFFFFFFL;
        long encodedMax = ZeroGcUserData.encode(OperationType.MULTISHOT_ACCEPT, maxPayload);
        assertEquals(OperationType.MULTISHOT_ACCEPT, ZeroGcUserData.getType(encodedMax));
        assertEquals(maxPayload, ZeroGcUserData.getPayload(encodedMax));

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

            Result reusable = new Result();
            Result returned = ring.waitForResult(reusable);

            assertSame(reusable, returned);
            assertEquals(id, reusable.id());
            assertEquals(13, reusable.result());
            assertEquals(OperationType.READ, reusable.type());
            reusable.freeBuffer();

            long id2 = ring.prepareRead(fd, 14, 0);
            ring.submit();

            int[] consumedCount = new int[1];
            long[] consumedId = new long[1];

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
