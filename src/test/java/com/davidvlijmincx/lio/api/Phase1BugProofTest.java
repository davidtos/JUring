package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.net.Socket;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Proof and regression tests for Phase 1 bug fixes and safety improvements.
 */
class Phase1BugProofTest {

    @Test
    void proof1a_tryWithResourcesOnReadFixedAndPooledDoesNotCrashOrDoubleFree() throws Exception {
        // 1. Subprocess verification (previously crashed with SIGABRT exitCode 134: "free(): invalid size")
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", classpath,
                Phase1BugProofTest.class.getName(),
                "crash-read-fixed-close"
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();

        assertEquals(0, exitCode, "Result.close() on READ_FIXED crashed the JVM (exitCode=" + exitCode + "): " + output);

        // 2. In-process verification across READ_FIXED, READ_POOLED, and READV_FIXED
        try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor(Path.of("src/test/resources/read_file"), READ)) {
            ring.registerBuffers(64, 2);
            ring.setupReadBufferPool(64, 2);

            // READ_FIXED on buffer index 1
            ring.prepareReadFixed(fd, 13, 0, 1);
            ring.submit();
            try (Result r = ring.waitForResult()) {
                assertEquals("Hello, World!", r.getString());
            }

            // READ_POOLED
            ring.prepareReadPooled(fd, 13, 0);
            ring.submit();
            long pooledAddr;
            try (Result r = ring.waitForResult()) {
                pooledAddr = r.bufferAddress();
                assertEquals("Hello, World!", r.getString());
            }
            ring.checkInReadBuffer(pooledAddr);

            // READV_FIXED
            ring.prepareReadvFixed(fd, new int[]{0, 1}, 0);
            ring.submit();
            try (Result r = ring.waitForResult()) {
                assertEquals(13L, r.result());
                assertNotNull(r.buffers());
            }
        }
    }

    @Test
    void proof1b_closeOnReadvFreesAndNullsBuffers() {
        try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            ring.prepareReadv(fd, new int[]{7, 6}, 0);
            ring.submit();
            Result res = ring.waitForResult();
            assertNotNull(res.buffers());
            res.close();
            assertNull(res.buffers(), "Result.close() should also free and null out READV buffers[]");
        }
    }

    @Test
    void proof2_sqRingOverflowThrowsIllegalStateExceptionAndRecoversAfterSubmit() throws Exception {
        // 1. Subprocess verification (previously crashed with SIGSEGV exitCode 134/139 in Unsafe_PutByte)
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", classpath,
                Phase1BugProofTest.class.getName(),
                "crash-sqe-overflow"
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();

        assertEquals(0, exitCode, "SQ ring overflow crashed the JVM with SIGSEGV (exitCode=" + exitCode + "): " + output);

        // 2. In-process verification of overflow exception and clean recovery after submit & reap
        try (JUring ring = new JUring(4, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            for (int i = 0; i < 4; i++) {
                ring.prepareRead(fd, 13, 0);
            }
            // 5th operation on a 4-entry SQ ring must fail fast before allocating buffers
            assertThrows(IllegalStateException.class, () -> ring.prepareRead(fd, 13, 0));

            // Submit and drain the 4 operations
            int reaped = ring.submitAndWaitForBatchResult(4, Result::close);
            assertEquals(4, reaped);

            // Ring is now free again and accepts new operations
            ring.prepareRead(fd, 13, 0);
            ring.submit();
            try (Result r = ring.waitForResult()) {
                assertEquals("Hello, World!", r.getString());
            }
        }
    }

    @Test
    void proof3_negativeErrnoInStrerrorProducesHumanReadableMessage() {
        try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER)) {
            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                    ring.registerFilesUpdate(0, new int[]{99999})
            );
            assertThat(ex.getMessage())
                    .doesNotContain("Unknown error -")
                    .contains("No such device or address");
        }
    }

    @Test
    void proof4_resultBufferIsBoundedAndProtectsAdjacentRegisteredBuffer() {
        try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
            MemorySegment[] slabs = ring.registerBuffers(13, 2);
            slabs[1].fill((byte) 'X');

            ring.prepareReadFixed(fd, 13, 0, 0);
            ring.submit();
            Result result = ring.waitForResult();

            assertEquals(13L, result.buffer().byteSize(),
                    "Result.buffer() should be bounded to actual read/buffer size, not Long.MAX_VALUE");
            assertEquals("Hello, World!", result.getString());
            assertArrayEquals("Hello, World!".getBytes(), result.toByteArray());

            // Writing at index 13 (past the end of the 13-byte buffer) must throw IndexOutOfBoundsException
            // rather than corrupting slabs[1]
            assertThrows(IndexOutOfBoundsException.class, () ->
                    result.buffer().set(JAVA_BYTE, result.result(), (byte) 0)
            );
            assertEquals((byte) 'X', slabs[1].get(JAVA_BYTE, 0), "Adjacent slab buffer was corrupted!");
        }
    }

    @Test
    void proof5_multishotAcceptPreservesCqeFlagsMore() throws Exception {
        final int port = 19299;

        try (JUring ring = new JUring(16, IORING_SETUP_SINGLE_ISSUER)) {
            int serverFd = ring.createServerSocket(port, 16);
            try {
                ring.prepareMultishotAccept(serverFd);
                ring.submit();

                Thread client = Thread.ofPlatform().start(() -> {
                    try (Socket s = new Socket("127.0.0.1", port)) {
                        // connected
                    } catch (Exception ignored) {
                    }
                });

                Result acceptRes = ring.waitForResult();
                client.join(2000);
                if (acceptRes.fd() >= 0) {
                    NativeDispatcher.C.close(acceptRes.fd());
                }

                assertTrue(acceptRes.isSuccess());
                assertTrue(acceptRes.hasMore(), "Multishot accept CQE should report hasMore() == true");
                assertNotEquals(0, acceptRes.cqeFlags() & IoUringDispatcher.IORING_CQE_F_MORE,
                        "IORING_CQE_F_MORE flag was dropped during CQE reaping");
            } finally {
                NativeDispatcher.C.close(serverFd);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) return;
        switch (args[0]) {
            case "crash-read-fixed-close" -> {
                try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
                     FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", READ, 0)) {
                    ring.registerBuffers(64, 2);
                    ring.prepareReadFixed(fd, 13, 0, 1);
                    ring.submit();
                    try (Result r = ring.waitForResult()) {
                        // Safe no-op close on READ_FIXED
                    }
                }
            }
            case "crash-sqe-overflow" -> {
                try (JUring ring = new JUring(4, IORING_SETUP_SINGLE_ISSUER)) {
                    try {
                        for (int i = 0; i < 10; i++) {
                            ring.prepareNop();
                        }
                    } catch (IllegalStateException expectedAfterFix) {
                        System.out.println("Caught expected IllegalStateException: " + expectedAfterFix.getMessage());
                    }
                }
            }
        }
    }
}
