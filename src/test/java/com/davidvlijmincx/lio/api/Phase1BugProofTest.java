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
            try (Result r = ring.waitForResult()) {
                assertEquals("Hello, World!", r.getString());
                ring.checkInReadBuffer(r);
            }

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
    void step1_1_throwingConsumerAdvancesHeadAndAvoidsDoubleFree() throws Exception {
        java.util.List<Long> freedAddrs = new java.util.ArrayList<>();
        LibCDispatcher.FREE_HOOK = freedAddrs::add;
        try (JUring ring = new JUring(16, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor fd = new FileDescriptor("src/test/resources/write_file", LinuxOpenOptions.WRITE, 0)) {

            for (int i = 0; i < 8; i++) {
                byte[] data = ("write-chunk-" + i + "\n").getBytes();
                ring.prepareWrite(fd, data, i * 64L);
            }
            ring.submitAndWait(8);

            java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
            assertThrows(RuntimeException.class, () -> {
                ring.peekForBatchResult(8, result -> {
                    if (count.incrementAndGet() == 3) {
                        throw new RuntimeException("Simulated failure on 3rd CQE");
                    }
                });
            });

            // The consumer threw on the 3rd CQE.
            // CQEs 0, 1, and 2 should have been retired from the ring.
            // The next reap must return ONLY the remaining 5 CQEs.
            java.util.List<Long> remainingTokens = new java.util.ArrayList<>();
            int reaped = ring.peekForBatchResult(8, result -> remainingTokens.add(result.id()));

            assertEquals(5, reaped, "Next reap should only return the remaining 5 CQEs");
            assertEquals(5, remainingTokens.size());
            assertEquals(8, freedAddrs.size(), "Each write buffer must be freed exactly once (no double-free)");
        } finally {
            LibCDispatcher.FREE_HOOK = null;
        }
    }

    @Test
    void step1_2_rejectNonNativeAndReadOnlySegments() throws Exception {
        try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER);
             JUringBlocking blocking = new JUringBlocking(8, IORING_SETUP_SINGLE_ISSUER);
             FileDescriptor readFd = new FileDescriptor("src/test/resources/read_file", LinuxOpenOptions.READ, 0);
             FileDescriptor writeFd = new FileDescriptor("src/test/resources/write_file", LinuxOpenOptions.WRITE, 0);
             java.lang.foreign.Arena confinedArena = java.lang.foreign.Arena.ofConfined()) {

            MemorySegment heapSegment = MemorySegment.ofArray(new byte[64]);
            MemorySegment nativeSegment = confinedArena.allocate(64);
            MemorySegment readOnlySegment = nativeSegment.asReadOnly();

            // 1. Async JUring.prepareWrite rejects non-native heap segment
            assertThrows(IllegalArgumentException.class, () ->
                    ring.prepareWrite(writeFd, heapSegment, 0));

            // 2. JUringBlocking.write rejects non-native heap segment
            assertThrows(IllegalArgumentException.class, () ->
                    blocking.write(writeFd, heapSegment, 0));

            // 3. JUringBlocking.read rejects non-native heap segment
            assertThrows(IllegalArgumentException.class, () ->
                    blocking.read(readFd, heapSegment, 0));

            // 4. JUringBlocking.read rejects read-only native segment
            assertThrows(IllegalArgumentException.class, () ->
                    blocking.read(readFd, readOnlySegment, 0));

            // 5. Closed arena segment throws IllegalStateException
            java.lang.foreign.Arena closedArena = java.lang.foreign.Arena.ofConfined();
            MemorySegment closedSegment = closedArena.allocate(64);
            closedArena.close();
            assertThrows(IllegalStateException.class, () ->
                    ring.prepareWrite(writeFd, closedSegment, 0));
            assertThrows(IllegalStateException.class, () ->
                    blocking.read(readFd, closedSegment, 0));

            // 6. Heap segment on prepareWriteFixed STILL WORKS (copied into registered buffer)
            ring.registerBuffers(64, 1);
            byte[] testData = "heap-data-copy-test".getBytes();
            MemorySegment heapWriteBuf = MemorySegment.ofArray(testData);
            long token = ring.prepareWriteFixed(writeFd, heapWriteBuf, 0, 0);
            assertTrue(token != 0);
            ring.submit();
            try (Result r = ring.waitForResult()) {
                assertEquals(testData.length, r.result());
            }
        }
    }

    @Test
    void step1_4_exportedPackagePublicApiEncapsulation() throws Exception {
        java.util.Set<String> allowedPublicClasses = java.util.Set.of(
                "FileDescriptor",
                "IoUringOptions",
                "JUring",
                "JUringBlocking",
                "LinuxOpenOptions",
                "OperationType",
                "Result",
                "ResultConsumer",
                "SqeOptions"
        );

        // Scan all classes in com.davidvlijmincx.lio.api package
        java.io.File packageDir = new java.io.File("src/main/java/com/davidvlijmincx/lio/api");
        java.io.File[] javaFiles = packageDir.listFiles((dir, name) -> name.endsWith(".java"));
        assertNotNull(javaFiles);

        java.util.List<String> disallowedPublicClasses = new java.util.ArrayList<>();
        for (java.io.File file : javaFiles) {
            String className = file.getName().replace(".java", "");
            Class<?> clazz = Class.forName("com.davidvlijmincx.lio.api." + className);
            if (java.lang.reflect.Modifier.isPublic(clazz.getModifiers())) {
                if (!allowedPublicClasses.contains(className)) {
                    disallowedPublicClasses.add(className);
                }
            }
        }

        assertTrue(disallowedPublicClasses.isEmpty(),
                "Found internal classes that are unexpectedly public in exported package: " + disallowedPublicClasses);

        // Verify Result full constructor and set() methods are not public
        for (java.lang.reflect.Constructor<?> c : Result.class.getDeclaredConstructors()) {
            if (c.getParameterCount() > 1) {
                assertFalse(java.lang.reflect.Modifier.isPublic(c.getModifiers()),
                        "Result full constructor must be package-private, but was public: " + c);
            }
        }
        for (java.lang.reflect.Method m : Result.class.getDeclaredMethods()) {
            if (m.getName().equals("set")) {
                assertFalse(java.lang.reflect.Modifier.isPublic(m.getModifiers()),
                        "Result.set() must be package-private, but was public: " + m);
            }
        }

        // Verify JUring does not expose checkInReadBuffer(long) publicly
        for (java.lang.reflect.Method m : JUring.class.getDeclaredMethods()) {
            if (m.getName().equals("checkInReadBuffer") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == long.class) {
                assertFalse(java.lang.reflect.Modifier.isPublic(m.getModifiers()),
                        "JUring.checkInReadBuffer(long) must be package-private, but was public: " + m);
            }
        }
    }

    @Test
    void step1_5_registerRingFdOnlyWithSingleIssuer() throws Exception {
        // 1. Non-single-issuer ring should NOT register ring fd
        try (JUring ring = new JUring(8)) {
            assertFalse(ring.isRingFdRegistered(), "Non-single-issuer ring must not register ring fd");
            int fd = ring.ringFd();
            assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("/proc/self/fd/" + fd)));

            // Use from two different platform threads
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> {
                    ring.prepareNop();
                    ring.submit();
                    try (Result r = ring.waitForResult()) {
                        assertTrue(r.isSuccess());
                    }
                }).get();
                pool.submit(() -> {
                    ring.prepareNop();
                    ring.submit();
                    try (Result r = ring.waitForResult()) {
                        assertTrue(r.isSuccess());
                    }
                }).get();
            } finally {
                pool.shutdown();
            }

            assertFalse(ring.isRingFdRegistered(), "Non-single-issuer ring must still not register ring fd after multi-thread use");
            ring.close();
            assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("/proc/self/fd/" + fd)),
                    "/proc/self/fd must not hold the ring after close");
        }

        // 2. Single-issuer ring registers once and unregisters on close
        int singleIssuerFd;
        try (JUring ring = new JUring(8, IORING_SETUP_SINGLE_ISSUER)) {
            singleIssuerFd = ring.ringFd();
            assertTrue(ring.isRingFdRegistered(), "Single-issuer ring must register ring fd");
            assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("/proc/self/fd/" + singleIssuerFd)));
            ring.prepareNop();
            ring.submit();
            try (Result r = ring.waitForResult()) {
                assertTrue(r.isSuccess());
            }
        }
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("/proc/self/fd/" + singleIssuerFd)),
                "Single-issuer ring must unregister and close fd cleanly");
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
