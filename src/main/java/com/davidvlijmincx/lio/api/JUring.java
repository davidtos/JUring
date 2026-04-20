package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public class JUring implements AutoCloseable {

    private final LibUringDispatcher ioUring;
    private final List<MemorySegment> registeredBuffers;
    private int[] freeBufferStack;
    private int freeBufferTop;

    public JUring(int queueDepth, IoUringOptions... ioUringFlags) {
        ioUring = NativeDispatcher.getUringInstance(queueDepth, ioUringFlags);
        registeredBuffers = new ArrayList<>();
        freeBufferStack = new int[0];
        freeBufferTop = 0;
    }

    private JUring(LibUringDispatcher ioUring){
        this.ioUring = ioUring;
        registeredBuffers = new ArrayList<>();
        freeBufferStack = new int[0];
        freeBufferTop = 0;
    }

    public JUring getSharedWorkerRing(int queueDepth, IoUringOptions... ioUringOptions){
        var ring = this.ioUring.getSharedWorkerRing(queueDepth);
        return new JUring(ring);
    }

    public long prepareRead(FileDescriptor fd, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), readSize, offset, sqeOptions, false);
    }

    public long prepareRead(int indexFD, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, readSize, offset, sqeOptions, true);
    }

    public long prepareReadFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareReadFixedInternal(fd.getFd(), readSize, offset, bufferIndex, sqeOptions, false);
    }

    public long prepareReadFixed(int indexFD, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareReadFixedInternal(indexFD, readSize, offset, bufferIndex, sqeOptions, true);
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, sqeOptions, false);
    }

    public long prepareWrite(FileDescriptor fd, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, sqeOptions, false);
    }

    public long prepareWrite(int indexFD, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(indexFD, bytes, offset, sqeOptions, true);
    }

    public long prepareWrite(int indexFD, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(indexFD, bytes, offset, sqeOptions, true);
    }

    public long prepareWriteFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, sqeOptions, false);
    }

    public long prepareWriteFixed(int indexFD, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, sqeOptions, true);
    }

    public long prepareWriteFixed(FileDescriptor fd, MemorySegment bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, sqeOptions, false);
    }

    public long prepareWriteFixed(int indexFD, MemorySegment bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, sqeOptions, true);
    }

    public long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset, SqeOptions... sqeOptions) {
        int nrVecs = bufferSizes.length;
        if (nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " exceeds MAX_VECS " + IovecBlockPool.MAX_VECS);
        }

        long blockAddr = ioUring.allocateIovecBlock();
        ZeroGcIovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            MemorySegment dataBuf = NativeDispatcher.C.alloc(bufferSizes[i]);
            ZeroGcIovecBlock.setIovBase(blockAddr, i, dataBuf.address());
            ZeroGcIovecBlock.setIovLen(blockAddr, i, bufferSizes[i]);
        }

        long id = blockAddr + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fd.getFd(), OperationType.READV, blockAddr);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareReadvAddress(sqe, fd.getFd(), ZeroGcIovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareReadvFixed(FileDescriptor fd, int[] bufferIndices, long offset, SqeOptions... sqeOptions) {
        int nrVecs = bufferIndices.length;
        if (nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " exceeds MAX_VECS " + IovecBlockPool.MAX_VECS);
        }

        long blockAddr = ioUring.allocateIovecBlock();
        ZeroGcIovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            if (idx < 0 || idx >= registeredBuffers.size()) {
                ioUring.releaseIovecBlock(blockAddr);
                throw new IllegalArgumentException("Buffer index out of range: " + idx);
            }
            MemorySegment registeredBuffer = registeredBuffers.get(idx);
            ZeroGcIovecBlock.setIovBase(blockAddr, i, registeredBuffer.address());
            ZeroGcIovecBlock.setIovLen(blockAddr, i, registeredBuffer.byteSize());
        }

        long id = blockAddr + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fd.getFd(), OperationType.READV_FIXED, blockAddr);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareReadvAddress(sqe, fd.getFd(), ZeroGcIovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareWritevFixed(FileDescriptor fd, int[] bufferIndices, long[] lengths, long offset, SqeOptions... sqeOptions) {
        int nrVecs = bufferIndices.length;
        if (lengths.length != nrVecs) {
            throw new IllegalArgumentException("lengths array must have the same length as bufferIndices");
        }
        if (nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " exceeds MAX_VECS " + IovecBlockPool.MAX_VECS);
        }

        long blockAddr = ioUring.allocateIovecBlock();
        ZeroGcIovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            if (idx < 0 || idx >= registeredBuffers.size()) {
                ioUring.releaseIovecBlock(blockAddr);
                throw new IllegalArgumentException("Buffer index out of range: " + idx);
            }
            MemorySegment registeredBuffer = registeredBuffers.get(idx);
            if (lengths[i] > registeredBuffer.byteSize()) {
                ioUring.releaseIovecBlock(blockAddr);
                throw new IllegalArgumentException("Length " + lengths[i] + " exceeds registered buffer size for index " + idx);
            }
            ZeroGcIovecBlock.setIovBase(blockAddr, i, registeredBuffer.address());
            ZeroGcIovecBlock.setIovLen(blockAddr, i, lengths[i]);
        }

        long id = blockAddr + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fd.getFd(), OperationType.WRITEV_FIXED, blockAddr);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareWritevAddress(sqe, fd.getFd(), ZeroGcIovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareWritev(FileDescriptor fd, byte[][] buffers, long offset, SqeOptions... sqeOptions) {
        int nrVecs = buffers.length;
        if (nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " exceeds MAX_VECS " + IovecBlockPool.MAX_VECS);
        }

        long blockAddr = ioUring.allocateIovecBlock();
        ZeroGcIovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            MemorySegment dataBuf = NativeDispatcher.C.alloc(buffers[i].length);
            MemorySegment.copy(buffers[i], 0, dataBuf, JAVA_BYTE, 0, buffers[i].length);
            ZeroGcIovecBlock.setIovBase(blockAddr, i, dataBuf.address());
            ZeroGcIovecBlock.setIovLen(blockAddr, i, buffers[i].length);
        }

        long id = blockAddr + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fd.getFd(), OperationType.WRITEV, blockAddr);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareWritevAddress(sqe, fd.getFd(), ZeroGcIovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareOpen(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        byte[] pathBytes = filePath.getBytes();
        MemorySegment pathBuffer = NativeDispatcher.C.calloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathBuffer, JAVA_BYTE, 0, pathBytes.length);

        long id = pathBuffer.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, -1, OperationType.OPEN, pathBuffer);

        MemorySegment sqe = getSqe(sqeOptions, false);

        ioUring.prepareOpenAt(sqe, pathBuffer, flags, mode);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        byte[] pathBytes = filePath.getBytes();
        MemorySegment pathBuffer = NativeDispatcher.C.alloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathBuffer, JAVA_BYTE, 0, pathBytes.length);
        pathBuffer.set(JAVA_BYTE, pathBytes.length, (byte) 0);

        long id = pathBuffer.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fileIndex, OperationType.OPEN, pathBuffer);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareOpenDirectAt(sqe, pathBuffer, flags, mode, fileIndex);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareClose(FileDescriptor fd, SqeOptions... sqeOptions) {
        return prepareCloseInternal(fd.getFd(), sqeOptions);
    }

    public long prepareCloseDirect(int fileIndex, SqeOptions... sqeOptions) {
        long id = ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fileIndex, OperationType.CLOSE, MemorySegment.NULL);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareCloseDirect(sqe, fileIndex);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    private long prepareReadInternal(int fdOrIndex, int readSize, long offset, SqeOptions[] sqeOptions, boolean fixedFile) {
        long address = NativeDispatcher.C.mallocAddress(readSize);

        long userData = ioUring.allocateUserData(address, fdOrIndex, OperationType.READ, address);

        MemorySegment sqe = getSqe(sqeOptions, fixedFile);
        ioUring.prepareRead(sqe, fdOrIndex, address, readSize, offset);
        ioUring.setUserData(sqe, userData);

        return address;
    }

    private long prepareWriteInternal(int fdOrIndex, MemorySegment bytes, long offset, SqeOptions[] sqeOptions, boolean fixedFile) {
        long id = bytes.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fdOrIndex, OperationType.WRITE_FIXED, bytes);

        MemorySegment sqe = getSqe(sqeOptions, fixedFile);
        ioUring.prepareWrite(sqe, fdOrIndex, bytes, offset);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    private long prepareWriteInternal(int fdOrIndex, byte[] bytes, long offset, SqeOptions[] sqeOptions, boolean fixedFile) {
        MemorySegment buff = NativeDispatcher.C.alloc(bytes.length);
        long id = buff.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fdOrIndex, OperationType.WRITE, buff);

        MemorySegment sqe = getSqe(sqeOptions, fixedFile);
        ioUring.setUserData(sqe, userData);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);
        ioUring.prepareWrite(sqe, fdOrIndex, buff, offset);

        return id;
    }

    private long prepareReadFixedInternal(int fdOrIndex, int readSize, long offset, int bufferIndex, SqeOptions[] sqeOptions, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffers.size()) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        MemorySegment registeredBuffer = registeredBuffers.get(bufferIndex);
        if (readSize > registeredBuffer.byteSize()) {
            throw new IllegalArgumentException("Read size exceeds registered buffer size");
        }

        long id = registeredBuffer.address();
        long userData = ioUring.allocateUserData(id, fdOrIndex, OperationType.READ, registeredBuffer);

        MemorySegment sqe = getSqe(sqeOptions, fixedFile);
        ioUring.prepareReadFixed(sqe, fdOrIndex, registeredBuffer, readSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, byte[] bytes, long offset, int bufferIndex, SqeOptions[] sqeOptions, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffers.size()) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        MemorySegment registeredBuffer = registeredBuffers.get(bufferIndex);
        if (bytes.length > registeredBuffer.byteSize()) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        long id = registeredBuffer.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fdOrIndex, OperationType.WRITE_FIXED, registeredBuffer);

        MemorySegment sqe = getSqe(sqeOptions, fixedFile);
        ioUring.setUserData(sqe, userData);
        MemorySegment.copy(bytes, 0, registeredBuffer, JAVA_BYTE, 0, bytes.length);
        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBuffer, bytes.length, offset, bufferIndex);

        return id;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, MemorySegment bytes, long offset, int bufferIndex, SqeOptions[] sqeOptions, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffers.size()) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        MemorySegment registeredBuffer = registeredBuffers.get(bufferIndex);
        if (bytes.byteSize() > registeredBuffer.byteSize()) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        long id = registeredBuffer.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fdOrIndex, OperationType.WRITE_FIXED, registeredBuffer);

        MemorySegment sqe = getSqe(sqeOptions, fixedFile);
        ioUring.setUserData(sqe, userData);
        MemorySegment.copy(bytes, 0, registeredBuffer, 0, bytes.byteSize());
        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBuffer, bytes.byteSize(), offset, bufferIndex);

        return id;
    }

    private long prepareCloseInternal(int fdOrIndex, SqeOptions[] sqeOptions) {
        long id = ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fdOrIndex, OperationType.CLOSE, MemorySegment.NULL);

        MemorySegment sqe = getSqe(sqeOptions, false);

        ioUring.prepareClose(sqe, fdOrIndex);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public int createServerSocket(int port, int backlog) {
        int fd = NativeDispatcher.C.createSocket();
        NativeDispatcher.C.setReuseAddrAndPort(fd);
        NativeDispatcher.C.bindAndListen(fd, port, backlog);
        return fd;
    }


    public int createClientSocket() {
        return NativeDispatcher.C.createSocket();
    }

    public long prepareAccept(int serverFd, SqeOptions... sqeOptions) {
        // No per-operation buffer needed when not capturing the peer address.
        long id = ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, serverFd, OperationType.ACCEPT, MemorySegment.NULL);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareAccept(sqe, serverFd, MemorySegment.NULL, MemorySegment.NULL, 0);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareMultishotAccept(int serverFd, SqeOptions... sqeOptions) {
        long id = ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, serverFd, OperationType.MULTISHOT_ACCEPT, MemorySegment.NULL);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareMultishotAccept(sqe, serverFd, MemorySegment.NULL, MemorySegment.NULL, 0);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareConnect(int fd, String host, int port, SqeOptions... sqeOptions) {
        // malloc the sockaddr_in so it outlives this stack frame until CQE arrives
        MemorySegment addr = NativeDispatcher.C.allocSockaddrIn(host, port);

        long id = addr.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fd, OperationType.CONNECT, addr);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareConnect(sqe, fd, addr, (int) LibCDispatcher.SOCKADDR_IN_LAYOUT.byteSize());
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareRecv(int fd, int length, SqeOptions... sqeOptions) {
        MemorySegment buf = NativeDispatcher.C.alloc(length);

        long id = buf.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fd, OperationType.RECV, buf);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareRecv(sqe, fd, buf, length, 0);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareRecv(int fd, MemorySegment buffer, int length, SqeOptions... sqeOptions) {
        long id = buffer.address() + ThreadLocalRandom.current().nextLong();
        // RECV_EXT: buffer not freed on completion; caller owns it
        long userData = ioUring.allocateUserData(id, fd, OperationType.RECV_EXT, buffer);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareRecv(sqe, fd, buffer, length, 0);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareSend(int fd, byte[] bytes, SqeOptions... sqeOptions) {
        MemorySegment buf = NativeDispatcher.C.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buf, JAVA_BYTE, 0, bytes.length);

        long id = buf.address() + ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, fd, OperationType.SEND, buf);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareSend(sqe, fd, buf, bytes.length, 0);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareSend(int fd, MemorySegment buffer, int length, SqeOptions... sqeOptions) {
        long id = buffer.address() + ThreadLocalRandom.current().nextLong();
        // SEND_EXT: buffer not freed on completion; caller owns it
        long userData = ioUring.allocateUserData(id, fd, OperationType.SEND_EXT, buffer);

        MemorySegment sqe = getSqe(sqeOptions, false);
        ioUring.prepareSend(sqe, fd, buffer, length, 0);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    public long prepareCancel(long targetOpId) {
        long id = ThreadLocalRandom.current().nextLong();
        long userData = ioUring.allocateUserData(id, -1, OperationType.CANCEL, MemorySegment.NULL);

        MemorySegment sqe = getSqe(new SqeOptions[0], false);
        // flags = 0: cancel the first matching SQE with this user_data
        ioUring.prepareCancel(sqe, targetOpId, 0);
        ioUring.setUserData(sqe, userData);

        return id;
    }

    private MemorySegment getSqe(SqeOptions[] sqeOptions, boolean fixedFile) {
        MemorySegment sqe = ioUring.getSqe();
        if (sqe != null) {
            byte flags = SqeOptions.combineOptions(sqeOptions);
            if (fixedFile) flags |= SqeOptions.IOSQE_FIXED_FILE.value;
            ioUring.setSqeFlag(sqe, flags);
        }
        return sqe;
    }

    public void submit() {
        ioUring.submit();
    }

    public List<Result> peekForBatchResult(int batchSize) {
        return ioUring.peekForBatchResult(batchSize);
    }

    public List<Result> waitForBatchResult(int batchSize) {
        return ioUring.waitForBatchResult(batchSize);
    }

    public Result waitForResult() {
        return ioUring.waitForResult();
    }

    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        MemorySegment[] result = ioUring.registerBuffers(size, nrOfBuffers);
        registeredBuffers.clear();
        registeredBuffers.addAll(Arrays.asList(result));
        freeBufferStack = new int[nrOfBuffers];
        freeBufferTop = nrOfBuffers;
        for (int i = 0; i < nrOfBuffers; i++) {
            freeBufferStack[i] = i;
        }
        return result;
    }

    /**
     * Check out a registered buffer index from the pool.
     * Returns -1 if no buffers are available.
     */
    public int checkOutBuffer() {
        if (freeBufferTop == 0) {
            return -1;
        }
        return freeBufferStack[--freeBufferTop];
    }

    /**
     * Return a registered buffer index to the pool after use.
     */
    public void checkInBuffer(int bufferIndex) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffers.size()) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }
        freeBufferStack[freeBufferTop++] = bufferIndex;
    }

    public int registerFiles(FileDescriptor... fileDescriptors) {
        int[] fds = new int[fileDescriptors.length];
        for (int i = 0; i < fileDescriptors.length; i++) {
            fds[i] = fileDescriptors[i].getFd();
        }
        return ioUring.registerFiles(fds);
    }

    public int registerFilesUpdate(int offset, int[] fileDescriptors) {
        return ioUring.registerFilesUpdate(offset, fileDescriptors);
    }

    @Override
    public void close() {
        ioUring.close();
    }
}
