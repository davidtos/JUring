package com.davidvlijmincx.lio.api;


import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public class JUringBlocking implements AutoCloseable {

    public final Duration timeout;
    private final Map<Long, CompletableFuture<? extends Result>> requests;
    private final JUring jUring;
    private boolean running = true;
    private Thread pollerThread;

    public JUringBlocking(int queueDepth, IoUringOptions... ioUringFlags) {
        this(queueDepth, Duration.ofMillis(-1), ioUringFlags);
    }

    public JUringBlocking(int queueDepth, Duration timeout, IoUringOptions... ioUringFlags) {
        this.jUring = new JUring(queueDepth, ioUringFlags);
        this.timeout = timeout;
        this.requests = new ConcurrentHashMap<>(queueDepth * 6, 0.5f);
        startPoller();
    }

    private void startPoller() {
        pollerThread = Thread.ofPlatform().daemon(true).start(() -> {
            while (running) {
                jUring.peekForBatchResult(100).forEach(result -> {
                    var request = requests.remove(result.id());
                    switch (result) {
                        case ReadResult r -> ((CompletableFuture<ReadResult>) request).complete(r);
                        case WriteResult r -> ((CompletableFuture<WriteResult>) request).complete(r);
                        case OpenResult r -> ((CompletableFuture<OpenResult>) request).complete(r);
                        case CloseResult r -> ((CompletableFuture<CloseResult>) request).complete(r);
                    }
                });
                sleepInterval();
            }
        });
    }

    private void sleepInterval() {
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void submit() {
        jUring.submit();
    }

    public Future<ReadResult> prepareRead(FileDescriptor fd, int size, long offset) {
        long id = jUring.prepareRead(fd, size, offset);
        CompletableFuture<ReadResult> result = new CompletableFuture<>();
        requests.put(id, result);
        return result;
    }

    public Future<WriteResult> prepareWrite(FileDescriptor fd, byte[] bytes, long offset) {
        long id = jUring.prepareWrite(fd, bytes, offset);
        CompletableFuture<WriteResult> result = new CompletableFuture<>();
        requests.put(id, result);
        return result;
    }

    @Override
    public void close() {
        running = false;
        try {
            pollerThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        jUring.close();
    }
}
