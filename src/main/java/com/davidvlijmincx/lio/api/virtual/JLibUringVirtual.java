package com.davidvlijmincx.lio.api.virtual;


import com.davidvlijmincx.lio.api.*;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class JLibUringVirtual implements AutoCloseable {

    private final Map<Long, BlockingReadResult> requests = new ConcurrentHashMap<>();
    private final JUring jUring;
    private int userData = 0;


    public JLibUringVirtual(int queueDepth, boolean polling) {
        this.jUring = new JUring(queueDepth, polling);
        startPoller();
    }

    void startPoller() {
        Thread.ofPlatform().daemon(true).start(() -> {
            while (true) {
                final Result result = jUring.waitForResult();

                BlockingReadResult request = requests.get(result.getId());
                while (request == null) {
                    request = requests.get(result.getId());
                }

                if (result instanceof AsyncReadResult r) {
                    request.setResult(r);
                }


                requests.remove(userData);

            }
        });
    }

    public void submit() {
        jUring.submit();
    }

    public BlockingReadResult submitRead(String path, int size, int offset) {
        long id = jUring.prepareRead(path, size, offset);
        BlockingReadResult result = new BlockingReadResult(id);
        requests.put(id, result);
        return result;
    }

    public void freeReadBuffer(MemorySegment buffer) {
        jUring.freeReadBuffer(buffer);
    }

    @Override
    public void close() {
        jUring.close();
    }
}
