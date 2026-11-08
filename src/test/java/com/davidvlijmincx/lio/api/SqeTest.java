package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

class SqeTest {

    private Arena arena;
    private MemorySegment sqeSegment;
    private long sqeAddress;

    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
        sqeSegment = arena.allocate(Sqe.SQE_SIZE);
        sqeAddress = sqeSegment.address();
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    @Test
    void testZeroClearsAll64Bytes() {
        sqeSegment.fill((byte) 0xFF);
        for (long i = 0; i < Sqe.SQE_SIZE; i++) {
            assertEquals((byte) 0xFF, sqeSegment.get(ValueLayout.JAVA_BYTE, i));
        }

        Sqe.zero(sqeAddress);

        for (long i = 0; i < Sqe.SQE_SIZE; i++) {
            assertEquals((byte) 0, sqeSegment.get(ValueLayout.JAVA_BYTE, i), "Byte at offset " + i + " was not zeroed");
        }
    }

    @Test
    void testGettersAndSetters() {
        Sqe.zero(sqeAddress);

        Sqe.setOpcode(sqeAddress, (byte) 22);
        assertEquals((byte) 22, Sqe.getOpcode(sqeAddress));

        Sqe.setFlags(sqeAddress, (byte) 0x02);
        assertEquals((byte) 0x02, Sqe.getFlags(sqeAddress));

        Sqe.addFlags(sqeAddress, (byte) 0x04);
        assertEquals((byte) 0x06, Sqe.getFlags(sqeAddress));

        Sqe.setIoprio(sqeAddress, (short) 123);
        assertEquals((short) 123, Sqe.getIoprio(sqeAddress));

        Sqe.setFd(sqeAddress, 42);
        assertEquals(42, Sqe.getFd(sqeAddress));

        Sqe.setOff(sqeAddress, 1024L);
        assertEquals(1024L, Sqe.getOff(sqeAddress));

        Sqe.setAddr(sqeAddress, 0x123456789ABCDEFL);
        assertEquals(0x123456789ABCDEFL, Sqe.getAddr(sqeAddress));

        Sqe.setLen(sqeAddress, 4096);
        assertEquals(4096, Sqe.getLen(sqeAddress));

        Sqe.setUserData(sqeAddress, 0xFEEDFACEDEADBEEFL);
        assertEquals(0xFEEDFACEDEADBEEFL, Sqe.getUserData(sqeAddress));

        Sqe.setFileIndex(sqeAddress, 5);
        assertEquals(5, Sqe.getFileIndex(sqeAddress));
    }

    @Test
    void testAbsoluteOffsetsSqe() {
        assertEquals(64, Sqe.SQE_SIZE, "io_uring_sqe must be exactly 64 bytes");

        sqeSegment.fill((byte) 0);

        sqeSegment.set(ValueLayout.JAVA_BYTE, 0, (byte) 22);
        assertEquals((byte) 22, Sqe.getOpcode(sqeAddress));
        Sqe.setOpcode(sqeAddress, (byte) 23);
        assertEquals((byte) 23, sqeSegment.get(ValueLayout.JAVA_BYTE, 0));

        sqeSegment.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x05);
        assertEquals((byte) 0x05, Sqe.getFlags(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_SHORT, 2, (short) 100);
        assertEquals((short) 100, Sqe.getIoprio(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_INT, 4, 1234);
        assertEquals(1234, Sqe.getFd(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_LONG, 8, 0x1122334455667788L);
        assertEquals(0x1122334455667788L, Sqe.getOff(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_LONG, 16, 0x8877665544332211L);
        assertEquals(0x8877665544332211L, Sqe.getAddr(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_INT, 24, 4096);
        assertEquals(4096, Sqe.getLen(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_INT, 28, 0x07);
        assertEquals(0x07, Sqe.getRwFlags(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_LONG, 32, 0xCAFEBABE12345678L);
        assertEquals(0xCAFEBABE12345678L, Sqe.getUserData(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_SHORT, 40, (short) 42);
        assertEquals((short) 42, Sqe.getBufIndex(sqeAddress));

        sqeSegment.set(ValueLayout.JAVA_INT, 44, 99);
        assertEquals(99, Sqe.getFileIndex(sqeAddress));
    }

    @Test
    void testAbsoluteOffsetsCqe() {
        assertEquals(16, Cqe.CQE_LAYOUT.byteSize(), "io_uring_cqe must be exactly 16 bytes");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cqe = a.allocate(16);
            long addr = cqe.address();

            cqe.set(ValueLayout.JAVA_LONG, 0, 0x123456789ABCDEF0L);
            assertEquals(0x123456789ABCDEF0L, Cqe.getUserData(addr));

            cqe.set(ValueLayout.JAVA_INT, 8, 4096);
            assertEquals(4096, Cqe.getRes(addr));

            cqe.set(ValueLayout.JAVA_INT, 12, 0x02);
            assertEquals(0x02, Cqe.getFlags(addr));
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
    void testUserDataEncoding() {
        long ptr = 0x00007FFFF7DC1234L;
        long encoded = UserData.encode(OperationType.READ, ptr);

        assertEquals(OperationType.READ, UserData.getType(encoded));
        assertEquals(ptr, UserData.getPayload(encoded));

        int bufIdx = 42;
        long opId = 987654321L;
        long encodedFixed = UserData.encodeFixed(OperationType.READ_FIXED, bufIdx, opId);

        assertEquals(OperationType.READ_FIXED, UserData.getType(encodedFixed));
        assertEquals(bufIdx, UserData.getBufferIndex(encodedFixed));
        assertEquals(opId, UserData.getFixedOpId(encodedFixed));
    }

    @Test
    void testUserDataEdgeCases() {
        long maxPayload = 0x00FFFFFFFFFFFFFFL;
        long encodedMax = UserData.encode(OperationType.WRITE_EXT, maxPayload);
        assertEquals(OperationType.WRITE_EXT, UserData.getType(encodedMax));
        assertEquals(maxPayload, UserData.getPayload(encodedMax));

        int maxBufIdx = 0xFFFF;
        long maxFixedOpId = 0xFFFFFFFFFFL;
        long encodedFixedMax = UserData.encodeFixed(OperationType.WRITE_FIXED, maxBufIdx, maxFixedOpId);
        assertEquals(OperationType.WRITE_FIXED, UserData.getType(encodedFixedMax));
        assertEquals(maxBufIdx, UserData.getBufferIndex(encodedFixedMax));
        assertEquals(maxFixedOpId, UserData.getFixedOpId(encodedFixedMax));
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
