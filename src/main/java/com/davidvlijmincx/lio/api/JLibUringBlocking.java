package com.davidvlijmincx.lio.api;


import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


public class JLibUringBlocking implements AutoCloseable {

    public static final int MILLIS = 1;
    private final Map<Long, BlockingResult> requests = new ConcurrentHashMap<>();
    private final JUring jUring;
    private boolean running = true;
    private Thread pollerThread;

    public JLibUringBlocking(int queueDepth, boolean polling) {
        this.jUring = new JUring(queueDepth, polling);
        startPoller();
    }

    void startPoller() {
        pollerThread = Thread.ofPlatform().start(() -> {
            while (running) {
                final Optional<Result> result = jUring.peekForResult();

                if (result.isPresent()) {
                    BlockingResult request = requests.get(result.get().getId());
                    while (request == null) {
                        request = requests.get(result.get().getId());
                    }
                    request.setResult(result.get());
                    requests.remove(result.get().getId());
                }

                try {
                    Thread.sleep(Duration.ofMillis(MILLIS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public void submit() {
        jUring.submit();
    }

    public BlockingReadResult prepareRead(String path, int size, int offset) {
        long id = jUring.prepareRead(path, size, offset);
        BlockingReadResult result = new BlockingReadResult(id);
        requests.put(id, result);
        return result;
    }

    public BlockingWriteResult prepareWrite(String path, byte[] bytes, int offset) {
        long id = jUring.prepareWrite(path, bytes, offset);
        BlockingWriteResult result = new BlockingWriteResult(id);
        requests.put(id, result);
        return result;
    }

    public void freeReadBuffer(MemorySegment buffer) {
        jUring.freeReadBuffer(buffer);
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
