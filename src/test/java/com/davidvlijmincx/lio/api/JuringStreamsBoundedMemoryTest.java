package com.davidvlijmincx.lio.api;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.RDWR;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounded-memory tests for {@link JuringInputStream} / {@link JuringOutputStream}.
 *
 * <p>With {@code A = alignUp(bufferSize, alignment)} the frozen bounds are
 * {@code bufferMemoryBytes() <= (readAhead + 1) * (A + alignment)} for input and
 * {@code bufferMemoryBytes() <= (writeBehind + 1) * A} for output. These tests perform
 * multi-hundred-MiB transfers, sample {@code bufferMemoryBytes()} throughout (it must stay
 * bounded and must not grow with bytes processed), and measure the owning thread's heap
 * allocation over a fixed baseline to show there is no per-chunk retained growth.
 */
class JuringStreamsBoundedMemoryTest {

    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;
    private static final int BLOCK = 4096;

    /** Constant allowance for FFM view objects and miscellaneous one-off allocations. */
    private static final long MAX_HEAP_GROWTH_BYTES = 8L * MIB;

    private static final ThreadMXBean THREAD_BEAN;

    static {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean sunBean)) {
            throw new UnsupportedOperationException("com.sun.management.ThreadMXBean not available");
        }
        if (!sunBean.isThreadAllocatedMemorySupported()) {
            throw new UnsupportedOperationException("Thread allocated memory measurement is not supported");
        }
        if (!sunBean.isThreadAllocatedMemoryEnabled()) {
            sunBean.setThreadAllocatedMemoryEnabled(true);
        }
        THREAD_BEAN = sunBean;
    }

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
    // input
    // ------------------------------------------------------------------

    @Test
    void inputBufferMemoryStaysBoundedAndDoesNotGrowOver200MiBRead(@TempDir Path dir) throws Exception {
        int fileSize = 200 * MIB;
        Path path = dir.resolve("bounded-input.bin");
        createZeroFile(path, fileSize);

        int bufferSize = 64 * KIB;
        int alignment = BLOCK;
        int readAhead = 4;
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(bufferSize)
                .withAlignment(alignment)
                .withReadAhead(readAhead);
        long alignedBuffer = alignUp(bufferSize, alignment);
        long bound = (readAhead + 1L) * (alignedBuffer + alignment);

        long threadId = Thread.currentThread().threadId();

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options)) {
            // Warm up until every configured prefetch buffer is certainly allocated/loaded.
            long consumed = 0;
            for (int i = 0; i < 64; i++) {
                MemorySegment chunk = in.readChunk();
                assertThat(chunk.byteSize()).as("warmup chunk " + i).isGreaterThan(0);
                consumed += chunk.byteSize();
            }
            long settledMemory = in.bufferMemoryBytes();
            assertThat(settledMemory).as("input stream allocates its prefetch buffers").isGreaterThan(0);
            assertThat(settledMemory).as("input bound: (readAhead+1)*(A+alignment)").isLessThanOrEqualTo(bound);

            long allocatedBefore = allocatedBytes(threadId);
            int samples = 0;
            MemorySegment chunk;
            while ((chunk = in.readChunk()).byteSize() > 0) {
                consumed += chunk.byteSize();
                if (++samples % 500 == 0) {
                    long current = in.bufferMemoryBytes();
                    assertThat(current).as("input buffer memory must stay within the frozen bounds")
                            .isLessThanOrEqualTo(bound);
                    assertThat(current).as("input buffer memory must not grow with bytes processed")
                            .isEqualTo(settledMemory);
                }
            }
            long allocated = allocatedBytes(threadId) - allocatedBefore;

            assertThat(consumed).isEqualTo(fileSize);
            assertThat(in.eof()).isTrue();
            assertThat(in.bufferMemoryBytes()).isEqualTo(settledMemory);
            assertThat(allocated)
                    .as("no per-chunk heap growth over a ~196 MiB measured read (baseline " + allocatedBefore + ")")
                    .isLessThan(MAX_HEAP_GROWTH_BYTES);

            in.close();
            assertThat(ring.inFlightCount()).as("close() drains stream-owned reads").isZero();
        }
    }

    @Test
    void adaptiveReadaheadNeverExceedsMemoryBound(@TempDir Path dir) throws Exception {
        int fileSize = 32 * MIB;
        Path path = dir.resolve("bounded-adaptive.bin");
        createZeroFile(path, fileSize);

        int bufferSize = 32 * KIB;
        int alignment = BLOCK;
        int readAhead = 3;
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(bufferSize)
                .withAlignment(alignment)
                .withReadAhead(readAhead)
                .withDynamicAdjustments(true);
        long bound = (readAhead + 1L) * (alignUp(bufferSize, alignment) + alignment);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options)) {
            long consumed = 0;
            MemorySegment chunk;
            while ((chunk = in.readChunk()).byteSize() > 0) {
                consumed += chunk.byteSize();
                assertThat(in.currentBufferSize()).isBetween(1, bufferSize);
                assertThat(in.currentReadAhead()).isBetween(0, readAhead);
                long memory = in.bufferMemoryBytes();
                assertThat(memory).as("adaptive readahead must respect the frozen bound")
                        .isGreaterThan(0)
                        .isLessThanOrEqualTo(bound);
            }
            assertThat(consumed).isEqualTo(fileSize);
            assertThat(in.eof()).isTrue();
        }
    }

    @Test
    void inputBufferMemoryUsesAlignUpForUnalignedBufferSize(@TempDir Path dir) throws Exception {
        int fileSize = 64 * KIB;
        Path path = dir.resolve("bounded-unaligned-input.bin");
        createZeroFile(path, fileSize);

        int bufferSize = 1000;
        int alignment = BLOCK;
        int readAhead = 2;
        InputStreamOptions options = InputStreamOptions.defaults()
                .withBufferSize(bufferSize)
                .withAlignment(alignment)
                .withReadAhead(readAhead);
        // Internal buffers are rounded up to the alignment; each also covers an unaligned trim.
        long bound = (readAhead + 1L) * (alignUp(bufferSize, alignment) + alignment);

        try (JuringFile file = JuringFile.open(ring, path, READ);
             JuringInputStream in = JuringInputStream.open(file, options)) {
            long consumed = 0;
            MemorySegment chunk;
            while ((chunk = in.readChunk()).byteSize() > 0) {
                assertThat(chunk.byteSize()).as("chunks are capped by the configured bufferSize")
                        .isLessThanOrEqualTo(bufferSize);
                consumed += chunk.byteSize();
            }
            assertThat(consumed).isEqualTo(fileSize);
            assertThat(in.bufferMemoryBytes()).isGreaterThan(0).isLessThanOrEqualTo(bound);
        }
    }

    // ------------------------------------------------------------------
    // output
    // ------------------------------------------------------------------

    @Test
    void outputBufferMemoryStaysBoundedAndDoesNotGrowOver200MiBWrite(@TempDir Path dir) throws Exception {
        int total = 200 * MIB;
        Path path = dir.resolve("bounded-output.bin");

        int bufferSize = 64 * KIB;
        int alignment = BLOCK;
        int writeBehind = 3;
        OutputStreamOptions options = OutputStreamOptions.defaults()
                .withBufferSize(bufferSize)
                .withAlignment(alignment)
                .withWriteBehind(writeBehind);
        long bound = (writeBehind + 1L) * alignUp(bufferSize, alignment);

        long threadId = Thread.currentThread().threadId();

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            try (JuringOutputStream out = JuringOutputStream.open(file, options)) {
                try (Arena arena = Arena.ofConfined()) {
                    int sourceSize = MIB;
                    MemorySegment source = arena.allocate(sourceSize, BLOCK);
                    fillPattern(source, 0, sourceSize);

                    // One whole MiB fills the write-behind window, so every buffer is allocated.
                    out.write(source);
                    long settledMemory = out.bufferMemoryBytes();
                    assertThat(settledMemory).as("output stream allocates its write-behind buffers")
                            .isGreaterThan(0);
                    assertThat(settledMemory).as("output bound: (writeBehind+1)*A")
                            .isLessThanOrEqualTo(bound);

                    long allocatedBefore = allocatedBytes(threadId);
                    int iterations = total / sourceSize;
                    for (int i = 1; i < iterations; i++) {
                        out.write(source);
                        if (i % 25 == 0) {
                            long current = out.bufferMemoryBytes();
                            assertThat(current).as("output buffer memory must stay within the frozen bounds")
                                    .isLessThanOrEqualTo(bound);
                            assertThat(current).as("output buffer memory must not grow with bytes processed")
                                    .isEqualTo(settledMemory);
                        }
                    }
                    long allocated = allocatedBytes(threadId) - allocatedBefore;

                    assertThat(out.position()).isEqualTo(total);
                    assertThat(out.bytesWritten()).isEqualTo(total);
                    assertThat(out.bufferMemoryBytes()).isEqualTo(settledMemory);
                    assertThat(allocated)
                            .as("no per-write heap growth over a ~199 MiB measured write (baseline "
                                    + allocatedBefore + ")")
                            .isLessThan(MAX_HEAP_GROWTH_BYTES);

                    out.flush();
                    assertThat(Files.size(path)).isEqualTo(total);
                    assertThat(out.bufferMemoryBytes()).isEqualTo(settledMemory);
                }

                out.close();
                assertThat(ring.inFlightCount()).as("close() drains stream-owned writes").isZero();
            }
            assertThat(file.length()).isEqualTo(total);
        }
        assertThat(Files.size(path)).isEqualTo(total);
    }

    @Test
    void outputBufferMemoryUsesAlignUpForUnalignedBufferSize(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("bounded-unaligned-output.bin");
        int bufferSize = 1000;
        int alignment = BLOCK;
        int writeBehind = 2;
        OutputStreamOptions options = OutputStreamOptions.defaults()
                .withBufferSize(bufferSize)
                .withAlignment(alignment)
                .withWriteBehind(writeBehind);
        long bound = (writeBehind + 1L) * alignUp(bufferSize, alignment);

        try (JuringFile file = JuringFile.open(ring, path, RDWR, CREATE, TRUNCATE)) {
            try (JuringOutputStream out = JuringOutputStream.open(file, options)) {
                byte[] data = new byte[10_000];
                for (int i = 0; i < data.length; i++) {
                    data[i] = (byte) (i * 31 + 7);
                }
                out.write(data, 0, data.length);
                assertThat(out.bufferMemoryBytes()).isGreaterThan(0).isLessThanOrEqualTo(bound);
                out.flush();
                assertThat(Files.size(path)).isEqualTo(data.length);
                assertThat(out.bufferMemoryBytes()).isLessThanOrEqualTo(bound);
            }
        }
        assertThat(Files.size(path)).isEqualTo(10_000);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static long allocatedBytes(long threadId) {
        return THREAD_BEAN.getThreadAllocatedBytes(threadId);
    }

    private static int alignUp(int value, int alignment) {
        return (int) (((long) value + alignment - 1) / alignment * alignment);
    }

    private static byte pattern(long offset) {
        return (byte) ((offset * 31 + (offset >>> 12) + 7) & 0xFF);
    }

    private static void fillPattern(MemorySegment segment, long fileOffset, int length) {
        for (int i = 0; i < length; i++) {
            segment.set(JAVA_BYTE, i, pattern(fileOffset + i));
        }
    }

    private static void createZeroFile(Path path, long size) throws IOException {
        byte[] zeros = new byte[MIB];
        try (OutputStream out = Files.newOutputStream(path)) {
            long remaining = size;
            while (remaining > 0) {
                int chunk = (int) Math.min(MIB, remaining);
                out.write(zeros, 0, chunk);
                remaining -= chunk;
            }
        }
    }
}
