package com.davidvlijmincx.lio.api;


import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public class JUringBlocking implements AutoCloseable {

    public final int pollingInterval;
    private final Map<Long, CompletableFuture<? extends Result>> requests;
    private final JUring jUring;
    private boolean running = true;
    private Thread pollerThread;

    public JUringBlocking(int queueDepth) {
        this.jUring = new JUring(queueDepth);
        this.pollingInterval = -1;
        this.requests = new ConcurrentHashMap<>(queueDepth * 6, 0.5f);
        startPoller();
    }

    public JUringBlocking(int queueDepth, int cqPollerTimeoutInMillis) {
        this.jUring = new JUring(queueDepth);
        this.pollingInterval = cqPollerTimeoutInMillis;
        this.requests = new ConcurrentHashMap<>(queueDepth * 6, 0.5f);
        startPoller();
    }

    private void startPoller() {
        pollerThread = Thread.ofPlatform().daemon(true).start(() -> {

            while (running) {
                final List<Result> results = jUring.peekForBatchResult(100);

                results.forEach(result -> {
                    CompletableFuture<? extends Result> request = requests.remove(result.getId());

                    if(result instanceof ReadResult r) {
                        ((CompletableFuture<ReadResult>)request).complete(r);
                    }
                    else if(result instanceof WriteResult r) {
                        ((CompletableFuture<WriteResult>)request).complete(r);
                    }

                });

                sleepInterval();

            }
        });
    }

    private void sleepInterval() {
        try {
            Thread.sleep(Duration.ofMillis(pollingInterval));
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
