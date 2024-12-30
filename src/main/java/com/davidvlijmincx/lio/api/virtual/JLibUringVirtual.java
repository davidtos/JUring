package com.davidvlijmincx.lio.api.virtual;


import com.davidvlijmincx.lio.api.*;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class JLibUringVirtual implements AutoCloseable {

    private final Map<Long, BlockingResult> requests = new ConcurrentHashMap<>();
    private final JUring jUring;

    public JLibUringVirtual(int queueDepth, boolean polling) {
        this.jUring = new JUring(queueDepth, polling);
        startPoller();
    }

    void startPoller() {
        Thread.ofPlatform().daemon(true).start(() -> {
            while (true) {
                final Result result = jUring.waitForResult();

                BlockingResult request = requests.get(result.getId());
                while (request == null) {
                    request = requests.get(result.getId());
                }

                request.setResult(result);

                requests.remove(result.getId());

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
        try{
            jUring.close();
        } catch (Exception ignored) {
            // TODO: close arena nicely
        }
    }
}
