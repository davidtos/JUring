package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.davidvlijmincx.lio.api.LinuxOpenOptions.CREATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.READ;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.TRUNCATE;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4 design §12.13: a {@link JuringFile} created, used and closed inside one shard task
 * (single owner) reads and writes correctly.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class B4JuringFileTaskTest {

    @TempDir
    Path tempDir;

    @Test
    void juringFileReadsWritesAndClosesInsideOneShardTask() throws Exception {
        Path source = tempDir.resolve("source.bin");
        Path target = tempDir.resolve("target.bin");
        byte[] sourceContent = new byte[4096];
        for (int i = 0; i < sourceContent.length; i++) {
            sourceContent[i] = (byte) (i * 17 + 3);
        }
        Files.write(source, sourceContent);
        byte[] writeContent = new byte[1024];
        for (int i = 0; i < writeContent.length; i++) {
            writeContent[i] = (byte) (255 - i);
        }

        IoShard shard = IoShard.create(0, ShardOptions.defaults());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try {
            shard.execute(ctx -> {
                try {
                    assertThat(shard.stats().wakeupArmed())
                            .as("blocking JuringFile helpers require the internal wakeup read to be unarmed")
                            .isFalse();
                    try (JuringFile in = JuringFile.open(ctx.ring(), source, READ)) {
                        MemorySegment buffer = Arena.ofAuto().allocate(4096L);
                        int read = in.read(0L, buffer);
                        assertThat(read).isEqualTo(4096);
                        assertThat(buffer.asSlice(0L, 4096L).toArray(JAVA_BYTE)).isEqualTo(sourceContent);
                        assertThat(in.length()).isEqualTo(4096L);
                    }
                    try (JuringFile out = JuringFile.open(ctx.ring(), target, 0644, CREATE, WRITE, TRUNCATE)) {
                        MemorySegment buffer = Arena.ofAuto().allocate(1024L);
                        MemorySegment.copy(writeContent, 0, buffer, JAVA_BYTE, 0L, writeContent.length);
                        int written = out.write(0L, buffer);
                        assertThat(written).isEqualTo(1024);
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS), "JuringFile shard task did not finish");
            assertThat(failure.get()).isNull();
            assertThat(Files.readAllBytes(target)).isEqualTo(writeContent);
            assertThat(Files.readAllBytes(source)).isEqualTo(sourceContent);
        } finally {
            shard.close();
        }
        assertThat(shard.stats().closed()).isTrue();
        assertThat(shard.stats().inFlightRequests()).isZero();
    }

    @Test
    void juringFileWrittenFileIsReadableByAnotherTask() throws Exception {
        Path file = tempDir.resolve("roundtrip.bin");
        byte[] content = new byte[512];
        Arrays.fill(content, (byte) 0x5A);

        IoShard shard = IoShard.create(1, ShardOptions.defaults());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try {
            shard.execute(ctx -> {
                try (JuringFile fileHandle = JuringFile.open(ctx.ring(), file, 0644, CREATE, WRITE, TRUNCATE)) {
                    MemorySegment buffer = Arena.ofAuto().allocate(content.length);
                    MemorySegment.copy(content, 0, buffer, JAVA_BYTE, 0L, content.length);
                    fileHandle.write(0L, buffer);
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertThat(failure.get()).isNull();
            assertThat(Files.readAllBytes(file)).isEqualTo(content);
        } finally {
            shard.close();
        }
    }
}
