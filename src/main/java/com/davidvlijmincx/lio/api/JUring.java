package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

    private final LibUringLayer libUringLayer;
    private final Map<Long, Request> requests = new ConcurrentHashMap<>();

    public JUring(int queueDepth, boolean polling) {
        libUringLayer = new LibUringLayer(queueDepth, polling);
    }

    public long prepareRead(String path, int readSize, int offset) {
        int fd = libUringLayer.openFile(path, 0, 0);

        MemorySegment buff = libUringLayer.malloc(readSize);

        // TODO: more unique
        long id = buff.address() + ThreadLocalRandom.current().nextLong();

        MemorySegment sqe = libUringLayer.getSqe();
        libUringLayer.setUserData(sqe, id);

        requests.put(id, new ReadRequest(id, fd, buff));
        libUringLayer.prepareRead(sqe, fd, buff, offset);

        return id;
    }

    public void freeReadBuffer(MemorySegment buffer) {
        libUringLayer.freeMemory(buffer);
    }

    public long prepareWrite(String path, byte[] bytes, int offset) {
        int fd = libUringLayer.openFile(path, 2, 0);

        MemorySegment sqe = libUringLayer.getSqe();
        MemorySegment buff = libUringLayer.malloc(bytes.length);
        libUringLayer.setUserData(sqe, buff.address());
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);

        long id = buff.address() + ThreadLocalRandom.current().nextLong();

        requests.put(id, new WriteRequest(id, fd, buff));
        libUringLayer.prepareWrite(sqe, fd, buff, offset);

        return buff.address();
    }

    public void submit() {
        libUringLayer.submit();
    }

    public Result waitForResult(){
        Cqe cqe = libUringLayer.waitForResult();
        long id = cqe.UserData();
        Request result = requests.get(id);

        libUringLayer.closeFile(result.getFd());
        libUringLayer.seen(cqe.cqePointer());
        requests.remove(cqe.UserData());

        if (result instanceof WriteRequest wr) {
            libUringLayer.freeMemory(wr.getBuffer());
            return new AsyncWriteResult(id, cqe.result());
        }

        return new AsyncReadResult(id, result.getBuffer(), cqe.result());
    }


    @Override
    public void close() {
        libUringLayer.close();
    }
}