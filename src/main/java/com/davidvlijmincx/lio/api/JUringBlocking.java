package com.davidvlijmincx.lio.api;


import java.io.Closeable;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;


public class JUringBlocking implements AutoCloseable {

    public final int pollingInterval;
    private final Map<Long, BlockingResult> requests = new ConcurrentHashMap<>();
    private final JUring jUring;
    private boolean running = true;
    private Thread pollerThread;

    public JUringBlocking(int queueDepth) {
        this.jUring = new JUring(queueDepth);
        this.pollingInterval = -1;
        startPoller();
    }

    public JUringBlocking(int queueDepth, int cqPollerTimeoutInMillis) {
        this.jUring = new JUring(queueDepth);
        this.pollingInterval = cqPollerTimeoutInMillis;
        startPoller();
    }

    private void startPoller() {
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

    public BlockingReadResult prepareRead(String path, int size, int offset, Function<Long, MemorySegment> alloc) {
        long id = jUring.prepareRead(path, size, offset, alloc);
        BlockingReadResult result = new BlockingReadResult(id);
        requests.put(id, result);
        return result;
    }

    public BlockingReadResult prepareRead(String path, int size, int offset) {
        return prepareRead(path, size, offset, LibCWrapper::alloc);
    }

    public BlockingWriteResult prepareWrite(String path, byte[] bytes, int offset) {
        long id = jUring.prepareWrite(path, bytes, offset);
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

    public AllocScope allocScope() {
        return new AllocScope(this);
    }

    public final class AllocScope implements Closeable {

        private final JUringBlocking jUringBlocking;
        private final AllocArena allocArena;

        AllocScope(JUringBlocking jUringBlocking) {
            this.jUringBlocking = jUringBlocking;
            allocArena = new AllocArena();
        }

        public BlockingReadResult prepareRead(String path, int size, int offset){
            return jUringBlocking.prepareRead(path, size, offset,  allocArena::allocate);
        }

        @Override
        public void close() {
            allocArena.close();
        }
    }
}
