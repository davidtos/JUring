package com.davidvlijmincx.lio.api;


import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JUringBlocking implements AutoCloseable {

    public final int pollingInterval;
    private final Map<Long, BlockingResult> requests;
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
                    BlockingResult request = requests.remove(result.getId());
                    request.setResult(result);
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

    public BlockingReadResult prepareRead(FileDescriptor fd, int size, long offset) {
        long id = jUring.prepareRead(fd, size, offset);
        BlockingReadResult result = new BlockingReadResult(id);
        requests.put(id, result);
        return result;
    }

    public BlockingWriteResult prepareWrite(FileDescriptor fd, byte[] bytes, long offset) {
        long id = jUring.prepareWrite(fd, bytes, offset);
        BlockingWriteResult result = new BlockingWriteResult(id);
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
