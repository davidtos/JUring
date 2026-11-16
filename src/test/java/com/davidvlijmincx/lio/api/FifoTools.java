package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Test support for the FIFO-based shutdown tests: a named pipe gives a deterministic blocking
 * read (writer open, no data) or blocking write (reader open, no drain).
 */
final class FifoTools {

    private FifoTools() {}

    static Path createFifo(Path dir, String name) throws Exception {
        Path fifo = dir.resolve(name);
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "mkfifo is not available: " + e);
            return fifo;
        }
        boolean exited = process.waitFor(5, TimeUnit.SECONDS);
        Assumptions.assumeTrue(exited && process.exitValue() == 0, "mkfifo failed");
        return fifo;
    }

    /** Opens the write end (unblocking the reader's open) and holds it open without writing. */
    static Thread holdWriteEndOpen(Path fifo) {
        Thread thread = new Thread(() -> {
            try (OutputStream ignored = Files.newOutputStream(fifo)) {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Reader side closed first; expected during teardown.
            }
        });
        thread.setDaemon(true);
        thread.setName("fifo-writer");
        thread.start();
        return thread;
    }

    /** Opens the read end (unblocking the writer's open) and never drains the pipe. */
    static Thread holdReadEndOpen(Path fifo) {
        Thread thread = new Thread(() -> {
            try (InputStream ignored = Files.newInputStream(fifo)) {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Writer side closed first; expected during teardown.
            }
        });
        thread.setDaemon(true);
        thread.setName("fifo-reader");
        thread.start();
        return thread;
    }

    static void stop(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }
}
