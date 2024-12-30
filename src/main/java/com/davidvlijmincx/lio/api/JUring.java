package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

        MemorySegment sqe = libUringLayer.getSqe();
        libUringLayer.setUserData(sqe, buff.address());

        long id = buff.address();
        requests.put(id, new ReadRequest(id, fd, buff));
        libUringLayer.prepareRead(sqe, fd, buff, offset);

        // return an id
        return buff.address();
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

        long id = buff.address();
        requests.put(id, new WriteRequest(id, fd, buff));
        libUringLayer.prepareWrite(sqe, fd, buff, offset);

        // return an id
        return buff.address();
    }

    public void submit() {
        libUringLayer.submit();
    }

    public Result waitForResult(){
        Cqe cqe = libUringLayer.waitForResult();
        long id = cqe.UserData();
        Request request = requests.get(cqe.UserData());

        libUringLayer.closeFile(request.getFd());
        libUringLayer.seen(cqe.cqePointer());
        requests.remove(cqe.UserData());

        if (request instanceof WriteRequest wr) {
            libUringLayer.freeMemory(wr.getBuffer());
            return new WriteResult(id, cqe.result());
        }

        return new AsyncReadResult(id, request.getBuffer(), cqe.result());
    }


    @Override
    public void close() {
        libUringLayer.close();
    }
}