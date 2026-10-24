package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class JUring implements AutoCloseable {

    private final IoUringDispatcher ioUring;
    private final List<MemorySegment> registeredBuffers;
    private MemorySegment[] registeredBuffersArray = new MemorySegment[0];
    private long[] registeredBufferAddresses = new long[0];
    private long[] registeredBufferSizes = new long[0];
    private int[] freeBufferStack;
    private int freeBufferTop;
    private ReadBufferPool readBufferPool;
    private long opSeq = 1;

    public JUring(int queueDepth, IoUringOptions... ioUringFlags) {
        ioUring = NativeDispatcher.getUringInstance(queueDepth, ioUringFlags);
        registeredBuffers = new ArrayList<>();
        freeBufferStack = new int[0];
        freeBufferTop = 0;
    }

    private JUring(IoUringDispatcher ioUring) {
        this.ioUring = ioUring;
        registeredBuffers = new ArrayList<>();
        freeBufferStack = new int[0];
        freeBufferTop = 0;
    }

    public JUring getSharedWorkerRing(int queueDepth, IoUringOptions... ioUringOptions) {
        var ring = this.ioUring.getSharedWorkerRing(queueDepth);
        return new JUring(ring);
    }

    public long prepareRead(FileDescriptor fd, int readSize, long offset) {
        return prepareReadInternal(fd.getFd(), readSize, offset, (byte) 0, false);
    }

    public long prepareRead(FileDescriptor fd, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), readSize, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareRead(int indexFD, int readSize, long offset) {
        return prepareReadInternal(indexFD, readSize, offset, (byte) 0, true);
    }

    public long prepareRead(int indexFD, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, readSize, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    public void setupReadBufferPool(int bufferSize, int capacity) {
        readBufferPool = new ReadBufferPool(capacity, bufferSize);
    }

    public long prepareReadPooled(FileDescriptor fd, int readSize, long offset) {
        return prepareReadPooledInternal(fd.getFd(), readSize, offset, (byte) 0, false);
    }

    public long prepareReadPooled(FileDescriptor fd, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadPooledInternal(fd.getFd(), readSize, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareReadPooled(int indexFD, int readSize, long offset) {
        return prepareReadPooledInternal(indexFD, readSize, offset, (byte) 0, true);
    }

    public long prepareReadPooled(int indexFD, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadPooledInternal(indexFD, readSize, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    public void checkInReadBuffer(long address) {
        readBufferPool.checkIn(address);
    }

    public long prepareReadFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex) {
        return prepareReadFixedInternal(fd.getFd(), readSize, offset, bufferIndex, (byte) 0, false);
    }

    public long prepareReadFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareReadFixedInternal(fd.getFd(), readSize, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareReadFixed(int indexFD, int readSize, long offset, int bufferIndex) {
        return prepareReadFixedInternal(indexFD, readSize, offset, bufferIndex, (byte) 0, true);
    }

    public long prepareReadFixed(int indexFD, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareReadFixedInternal(indexFD, readSize, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), true);
    }

    /**
     * Prepares a no-op ({@code IORING_OP_NOP}) submission queue entry.
     * Useful for testing ring round-trip overhead and waking a ring without I/O.
     *
     * @return the userData token identifying this operation
     */
    public long prepareNop() {
        return prepareNopInternal((byte) 0);
    }

    /**
     * Prepares a no-op ({@code IORING_OP_NOP}) submission queue entry with SQE flags.
     *
     * @param sqeOptions SQE flags to set on the entry
     * @return the userData token identifying this operation
     */
    public long prepareNop(SqeOptions... sqeOptions) {
        return prepareNopInternal(SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareNopInternal(byte flags) {
        long opId = opSeq++;
        long userData = ZeroGcUserData.encode(OperationType.NOP, opId);
        long sqe = getSqe(flags, false);
        ioUring.prepareNop(sqe);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, (byte) 0, false);
    }

    public long prepareWrite(FileDescriptor fd, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareWrite(FileDescriptor fd, MemorySegment bytes, long offset) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, (byte) 0, false);
    }

    public long prepareWrite(FileDescriptor fd, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(fd.getFd(), bytes, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareWrite(int indexFD, byte[] bytes, long offset) {
        return prepareWriteInternal(indexFD, bytes, offset, (byte) 0, true);
    }

    public long prepareWrite(int indexFD, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(indexFD, bytes, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    public long prepareWrite(int indexFD, MemorySegment bytes, long offset) {
        return prepareWriteInternal(indexFD, bytes, offset, (byte) 0, true);
    }

    public long prepareWrite(int indexFD, MemorySegment bytes, long offset, SqeOptions... sqeOptions) {
        return prepareWriteInternal(indexFD, bytes, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    public long prepareWriteFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, (byte) 0, false);
    }

    public long prepareWriteFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareWriteFixed(int indexFD, byte[] bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, (byte) 0, true);
    }

    public long prepareWriteFixed(int indexFD, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), true);
    }

    public long prepareWriteFixed(FileDescriptor fd, MemorySegment bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, (byte) 0, false);
    }

    public long prepareWriteFixed(FileDescriptor fd, MemorySegment bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(fd.getFd(), bytes, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareWriteFixed(int indexFD, MemorySegment bytes, long offset, int bufferIndex) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, (byte) 0, true);
    }

    public long prepareWriteFixed(int indexFD, MemorySegment bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedInternal(indexFD, bytes, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), true);
    }

    public long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset) {
        return prepareReadv(fd, bufferSizes, offset, (byte) 0);
    }

    public long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset, SqeOptions... sqeOptions) {
        return prepareReadv(fd, bufferSizes, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset, byte flags) {
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

        long userData = ZeroGcUserData.encode(OperationType.READV, blockAddr);

        long sqe = getSqe(flags, false);
        ioUring.prepareReadvAddress(sqe, fd.getFd(), ZeroGcIovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareReadvFixed(FileDescriptor fd, int[] bufferIndices, long offset) {
        return prepareReadvFixed(fd, bufferIndices, offset, (byte) 0);
    }

    public long prepareReadvFixed(FileDescriptor fd, int[] bufferIndices, long offset, SqeOptions... sqeOptions) {
        return prepareReadvFixed(fd, bufferIndices, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareReadvFixed(FileDescriptor fd, int[] bufferIndices, long offset, byte flags) {
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

        long userData = ZeroGcUserData.encode(OperationType.READV_FIXED, blockAddr);

        long sqe = getSqe(flags, false);
        ioUring.prepareReadvAddress(sqe, fd.getFd(), ZeroGcIovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareWritevFixed(FileDescriptor fd, int[] bufferIndices, long[] lengths, long offset) {
        return prepareWritevFixed(fd, bufferIndices, lengths, offset, (byte) 0);
    }

    public long prepareWritevFixed(FileDescriptor fd, int[] bufferIndices, long[] lengths, long offset, SqeOptions... sqeOptions) {
        return prepareWritevFixed(fd, bufferIndices, lengths, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareWritevFixed(FileDescriptor fd, int[] bufferIndices, long[] lengths, long offset, byte flags) {
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
            if (idx < 0 || idx >= registeredBufferAddresses.length) {
                ioUring.releaseIovecBlock(blockAddr);
                throw new IllegalArgumentException("Buffer index out of range: " + idx);
            }
            if (lengths[i] > registeredBufferSizes[idx]) {
                ioUring.releaseIovecBlock(blockAddr);
                throw new IllegalArgumentException("Length " + lengths[i] + " exceeds registered buffer size for index " + idx);
            }
            ZeroGcIovecBlock.setIovBase(blockAddr, i, registeredBufferAddresses[idx]);
            ZeroGcIovecBlock.setIovLen(blockAddr, i, lengths[i]);
        }

        long userData = ZeroGcUserData.encode(OperationType.WRITEV_FIXED, blockAddr);

        long sqe = getSqe(flags, false);
        ioUring.prepareWritevAddress(sqe, fd.getFd(), ZeroGcIovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareWritev(FileDescriptor fd, byte[][] buffers, long offset) {
        return prepareWritev(fd, buffers, offset, (byte) 0);
    }

    public long prepareWritev(FileDescriptor fd, byte[][] buffers, long offset, SqeOptions... sqeOptions) {
        return prepareWritev(fd, buffers, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareWritev(FileDescriptor fd, byte[][] buffers, long offset, byte flags) {
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

        long userData = ZeroGcUserData.encode(OperationType.WRITEV, blockAddr);

        long sqe = getSqe(flags, false);
        ioUring.prepareWritevAddress(sqe, fd.getFd(), ZeroGcIovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareOpen(String filePath, int flags, int mode) {
        return prepareOpen(filePath, flags, mode, (byte) 0);
    }

    public long prepareOpen(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        return prepareOpen(filePath, flags, mode, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareOpen(String filePath, int flags, int mode, byte sqeFlags) {
        byte[] pathBytes = filePath.getBytes();
        MemorySegment pathBuffer = NativeDispatcher.C.calloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathBuffer, JAVA_BYTE, 0, pathBytes.length);

        long userData = ZeroGcUserData.encode(OperationType.OPEN, pathBuffer.address());

        long sqe = getSqe(sqeFlags, false);
        ioUring.prepareOpenAt(sqe, pathBuffer.address(), flags, mode);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex) {
        return prepareOpenDirect(filePath, flags, mode, fileIndex, (byte) 0);
    }

    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        return prepareOpenDirect(filePath, flags, mode, fileIndex, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex, byte sqeFlags) {
        byte[] pathBytes = filePath.getBytes();
        MemorySegment pathBuffer = NativeDispatcher.C.alloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathBuffer, JAVA_BYTE, 0, pathBytes.length);
        pathBuffer.set(JAVA_BYTE, pathBytes.length, (byte) 0);

        long userData = ZeroGcUserData.encode(OperationType.OPEN, pathBuffer.address());

        long sqe = getSqe(sqeFlags, false);
        ioUring.prepareOpenDirectAt(sqe, pathBuffer.address(), flags, mode, fileIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareClose(FileDescriptor fd) {
        return prepareCloseInternal(fd.getFd(), (byte) 0);
    }

    public long prepareClose(FileDescriptor fd, SqeOptions... sqeOptions) {
        return prepareCloseInternal(fd.getFd(), SqeOptions.combineOptions(sqeOptions));
    }

    public long prepareCloseDirect(int fileIndex) {
        return prepareCloseDirect(fileIndex, (byte) 0);
    }

    public long prepareCloseDirect(int fileIndex, SqeOptions... sqeOptions) {
        return prepareCloseDirect(fileIndex, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareCloseDirect(int fileIndex, byte flags) {
        long opId = opSeq++;
        long userData = ZeroGcUserData.encode(OperationType.CLOSE, opId);

        long sqe = getSqe(flags, false);
        ioUring.prepareCloseDirect(sqe, fileIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadInternal(int fdOrIndex, int readSize, long offset, byte flags, boolean fixedFile) {
        long address = NativeDispatcher.C.mallocAddress(readSize + 1);
        MemorySegment.ofAddress(address).reinterpret(readSize + 1).set(JAVA_BYTE, readSize, (byte) 0);
        long userData = ZeroGcUserData.encode(OperationType.READ, address);

        long sqe = getSqe(flags, fixedFile);
        ioUring.prepareRead(sqe, fdOrIndex, address, readSize, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadPooledInternal(int fdOrIndex, int readSize, long offset, byte flags, boolean fixedFile) {
        long address = readBufferPool.checkOut();
        long userData = ZeroGcUserData.encode(OperationType.READ_POOLED, address);

        long sqe = getSqe(flags, fixedFile);
        ioUring.prepareRead(sqe, fdOrIndex, address, readSize, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteInternal(int fdOrIndex, MemorySegment bytes, long offset, byte flags, boolean fixedFile) {
        long opId = opSeq++;
        long userData = ZeroGcUserData.encode(OperationType.WRITE_FIXED, opId);

        long sqe = getSqe(flags, fixedFile);
        ioUring.prepareWrite(sqe, fdOrIndex, bytes.address(), bytes.byteSize(), offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteInternal(int fdOrIndex, byte[] bytes, long offset, byte flags, boolean fixedFile) {
        MemorySegment buff = NativeDispatcher.C.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);

        long userData = ZeroGcUserData.encode(OperationType.WRITE, buff.address());

        long sqe = getSqe(flags, fixedFile);
        ioUring.prepareWrite(sqe, fdOrIndex, buff.address(), bytes.length, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadFixedInternal(int fdOrIndex, int readSize, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        if (readSize > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Read size exceeds registered buffer size");
        }

        long opId = opSeq++;
        long userData = ZeroGcUserData.encodeFixed(OperationType.READ_FIXED, bufferIndex, opId);

        long sqe = getSqe(flags, fixedFile);
        ioUring.prepareReadFixed(sqe, fdOrIndex, registeredBufferAddresses[bufferIndex], readSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, byte[] bytes, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        if (bytes.length > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        MemorySegment.copy(bytes, 0, registeredBuffersArray[bufferIndex], JAVA_BYTE, 0, bytes.length);
        long opId = opSeq++;
        long userData = ZeroGcUserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);

        long sqe = getSqe(flags, fixedFile);
        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBufferAddresses[bufferIndex], bytes.length, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, MemorySegment bytes, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        long byteSize = bytes.byteSize();
        if (byteSize > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        MemorySegment.copy(bytes, 0, registeredBuffersArray[bufferIndex], 0, byteSize);
        long opId = opSeq++;
        long userData = ZeroGcUserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);

        long sqe = getSqe(flags, fixedFile);
        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBufferAddresses[bufferIndex], byteSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareCloseInternal(int fdOrIndex, byte flags) {
        long opId = opSeq++;
        long userData = ZeroGcUserData.encode(OperationType.CLOSE, opId);

        long sqe = getSqe(flags, false);
        ioUring.prepareClose(sqe, fdOrIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
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

    public long prepareAccept(int serverFd) {
        return prepareAccept(serverFd, (byte) 0);
    }

    public long prepareAccept(int serverFd, SqeOptions... sqeOptions) {
        return prepareAccept(serverFd, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareAccept(int serverFd, byte flags) {
        long opId = opSeq++;
        long userData = ZeroGcUserData.encode(OperationType.ACCEPT, opId);

        long sqe = getSqe(flags, false);
        ioUring.prepareAccept(sqe, serverFd, 0L, 0L, 0);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareMultishotAccept(int serverFd) {
        return prepareMultishotAccept(serverFd, (byte) 0);
    }

    public long prepareMultishotAccept(int serverFd, SqeOptions... sqeOptions) {
        return prepareMultishotAccept(serverFd, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareMultishotAccept(int serverFd, byte flags) {
        long opId = opSeq++;
        long userData = ZeroGcUserData.encode(OperationType.MULTISHOT_ACCEPT, opId);

        long sqe = getSqe(flags, false);
        ioUring.prepareMultishotAccept(sqe, serverFd, 0L, 0L, 0);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareConnect(int fd, String host, int port) {
        return prepareConnect(fd, host, port, (byte) 0);
    }

    public long prepareConnect(int fd, String host, int port, SqeOptions... sqeOptions) {
        return prepareConnect(fd, host, port, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareConnect(int fd, String host, int port, byte flags) {
        MemorySegment addr = NativeDispatcher.C.allocSockaddrIn(host, port);
        long userData = ZeroGcUserData.encode(OperationType.CONNECT, addr.address());

        long sqe = getSqe(flags, false);
        ioUring.prepareConnect(sqe, fd, addr.address(), (int) LibCDispatcher.SOCKADDR_IN_LAYOUT.byteSize());
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareRecv(int fd, int length) {
        return prepareRecv(fd, length, (byte) 0);
    }

    public long prepareRecv(int fd, int length, SqeOptions... sqeOptions) {
        return prepareRecv(fd, length, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareRecv(int fd, int length, byte flags) {
        MemorySegment buf = NativeDispatcher.C.alloc(length);
        long userData = ZeroGcUserData.encode(OperationType.RECV, buf.address());

        long sqe = getSqe(flags, false);
        ioUring.prepareRecv(sqe, fd, buf.address(), length, 0);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareRecv(int fd, MemorySegment buffer, int length) {
        return prepareRecv(fd, buffer, length, (byte) 0);
    }

    public long prepareRecv(int fd, MemorySegment buffer, int length, SqeOptions... sqeOptions) {
        return prepareRecv(fd, buffer, length, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareRecv(int fd, MemorySegment buffer, int length, byte flags) {
        long userData = ZeroGcUserData.encode(OperationType.RECV_EXT, buffer.address());

        long sqe = getSqe(flags, false);
        ioUring.prepareRecv(sqe, fd, buffer.address(), length, 0);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareSend(int fd, byte[] bytes) {
        return prepareSend(fd, bytes, (byte) 0);
    }

    public long prepareSend(int fd, byte[] bytes, SqeOptions... sqeOptions) {
        return prepareSend(fd, bytes, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareSend(int fd, byte[] bytes, byte flags) {
        MemorySegment buf = NativeDispatcher.C.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buf, JAVA_BYTE, 0, bytes.length);

        long userData = ZeroGcUserData.encode(OperationType.SEND, buf.address());

        long sqe = getSqe(flags, false);
        ioUring.prepareSend(sqe, fd, buf.address(), bytes.length, 0);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareSend(int fd, MemorySegment buffer, int length) {
        return prepareSend(fd, buffer, length, (byte) 0);
    }

    public long prepareSend(int fd, MemorySegment buffer, int length, SqeOptions... sqeOptions) {
        return prepareSend(fd, buffer, length, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareSend(int fd, MemorySegment buffer, int length, byte flags) {
        long opId = opSeq++;
        long userData = ZeroGcUserData.encode(OperationType.SEND_EXT, opId);

        long sqe = getSqe(flags, false);
        ioUring.prepareSend(sqe, fd, buffer.address(), length, 0);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareCancel(long targetOpId) {
        long opId = opSeq++;
        long userData = ZeroGcUserData.encode(OperationType.CANCEL, opId);

        long sqe = getSqe((byte) 0, false);
        ioUring.prepareCancel(sqe, targetOpId, 0);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long getSqe(byte flags, boolean fixedFile) {
        long sqe = ioUring.getSqe();
        if (sqe != 0L) {
            if (fixedFile) flags |= SqeOptions.IOSQE_FIXED_FILE.value;
            if (flags != 0) {
                ioUring.setSqeFlag(sqe, flags);
            }
        }
        return sqe;
    }

    public void submit() {
        ioUring.submit();
    }

    public int forEachBatchResult(int batchSize, ResultConsumer consumer) {
        return ioUring.peekForBatchResult(batchSize, consumer);
    }

    public int peekForBatchResult(int batchSize, ResultConsumer consumer) {
        return ioUring.peekForBatchResult(batchSize, consumer);
    }

    public List<Result> peekForBatchResult(int batchSize) {
        return ioUring.peekForBatchResult(batchSize);
    }

    public List<Result> waitForBatchResult(int batchSize) {
        return ioUring.waitForBatchResult(batchSize);
    }

    public int waitForBatchResult(int batchSize, ResultConsumer consumer) {
        return ioUring.waitForBatchResult(batchSize, consumer);
    }

    public Result waitForResult() {
        return ioUring.waitForResult();
    }

    public Result waitForResult(Result target) {
        return ioUring.waitForResult(target);
    }

    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        MemorySegment[] result = ioUring.registerBuffers(size, nrOfBuffers);
        registeredBuffers.clear();
        registeredBuffers.addAll(Arrays.asList(result));
        this.registeredBuffersArray = result;
        this.registeredBufferAddresses = new long[result.length];
        this.registeredBufferSizes = new long[result.length];
        for (int i = 0; i < result.length; i++) {
            this.registeredBufferAddresses[i] = result[i].address();
            this.registeredBufferSizes[i] = result[i].byteSize();
        }
        ioUring.setRegisteredBuffers(result);
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

    /**
     * Returns and resets the number of {@code io_uring_enter} syscalls issued on this ring
     * when {@code -Djuring.countEnters=true} is enabled (0 otherwise).
     */
    public int takeEnterCount() {
        return ioUring.takeEnterCount();
    }

    @Override
    public void close() {
        if (readBufferPool != null) {
            readBufferPool.close();
        }
        ioUring.close();
    }
}
