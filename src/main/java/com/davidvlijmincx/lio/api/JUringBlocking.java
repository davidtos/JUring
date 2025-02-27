package com.davidvlijmincx.lio.api;


import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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
        this.requests = new ConcurrentHashMap<>(queueDepth * 6, 0.5f,  Runtime.getRuntime().availableProcessors());
        startPoller();
    }

    public JUringBlocking(int queueDepth, int cqPollerTimeoutInMillis) {
        this.jUring = new JUring(queueDepth);
        this.pollingInterval = cqPollerTimeoutInMillis;
        this.requests = new ConcurrentHashMap<>(queueDepth * 6, 0.5f,  Runtime.getRuntime().availableProcessors());
        startPoller();
    }

    private void startPoller() {
        pollerThread = Thread.ofPlatform().daemon(true).start(() -> {
            while (running) {
                final Optional<Result> result = jUring.peekForResult();

                if (result.isPresent()) {
                    BlockingResult request = requests.remove(result.get().getId());
                    while (request == null) {
                        request = requests.remove(result.get().getId());
                    }
                    request.setResult(result.get());

                }

                if (result.isEmpty()){
                    sleepInterval();
                }
            }
        });
    }

    private void sleepInterval() {
        if (pollingInterval >= 0) {
            try {
                Thread.sleep(Duration.ofMillis(pollingInterval));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void submit() {
        jUring.submit();
    }

    public BlockingReadResult prepareRead(FileDescriptor fd, int size, int offset) {
        long id = jUring.prepareRead(fd, size, offset);
        BlockingReadResult result = new BlockingReadResult(id);
        requests.put(id, result);
        return result;
    }

    public BlockingWriteResult prepareWrite(FileDescriptor fd, byte[] bytes, int offset) {
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
