package com.davidvlijmincx.lio.api;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java API for Linux {@code io_uring}.
 */
public class JUring implements AutoCloseable {

    public static final int IORING_FSYNC_DATASYNC = 1;

    /** Default capacity of the lazily initialised sparse registered-file table. */
    static final int DEFAULT_FILE_TABLE_CAPACITY = 64;

    /** {@code AT_EMPTY_PATH}: operate on the file referred to by the dirfd argument itself. */
    static final int AT_EMPTY_PATH = 0x1000;

    /** {@code STATX_BASIC_STATS}: the basic statx field set, including {@code stx_size}. */
    static final int STATX_BASIC_STATS = 0x7ff;

    private final IoUringDispatcher ioUring;
    private final List<MemorySegment> registeredBuffers;

    private MemorySegment[] registeredBuffersArray = new MemorySegment[0];
    private long[] registeredBufferAddresses = new long[0];
    private long[] registeredBufferSizes = new long[0];
    private boolean[] bufferInUse;

    private int[] freeBufferStack;
    private int freeBufferTop;
    private ReadBufferPool readBufferPool;

    // Sparse registered-file table tracking: fd per slot (-1 = empty slot).
    private int[] registeredFileTable = new int[0];

    private long opSeq = 1;
    private boolean closed;

    public JUring(int queueDepth, IoUringOptions... ioUringFlags) {
        this(queueDepth, 0, ioUringFlags);
    }

    public JUring(int queueDepth, int sqThreadIdleMs, IoUringOptions... ioUringFlags) {
        ioUring = NativeDispatcher.getUringInstance(queueDepth, sqThreadIdleMs, ioUringFlags);
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
        var ring = this.ioUring.getSharedWorkerRing(queueDepth, ioUringOptions);
        return new JUring(ring);
    }

    public long prepareRead(FileDescriptor fd, int readSize, long offset) {
        return prepareReadInternal(fd.getFd(), readSize, offset, (byte) 0, false);
    }

    public long prepareRead(FileDescriptor fd, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), readSize, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareRead(FileDescriptor fd, MemorySegment buffer, long offset) {
        return prepareReadInternal(fd.getFd(), buffer, Math.toIntExact(buffer.byteSize()), offset, (byte) 0, false);
    }

    public long prepareRead(FileDescriptor fd, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), buffer, Math.toIntExact(buffer.byteSize()), offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareRead(FileDescriptor fd, MemorySegment buffer, int readSize, long offset) {
        return prepareReadInternal(fd.getFd(), buffer, readSize, offset, (byte) 0, false);
    }

    public long prepareRead(FileDescriptor fd, MemorySegment buffer, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(fd.getFd(), buffer, readSize, offset, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareRead(int indexFD, int readSize, long offset) {
        return prepareReadInternal(indexFD, readSize, offset, (byte) 0, true);
    }

    public long prepareRead(int indexFD, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, readSize, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    public long prepareRead(int indexFD, MemorySegment buffer, long offset) {
        return prepareReadInternal(indexFD, buffer, Math.toIntExact(buffer.byteSize()), offset, (byte) 0, true);
    }

    public long prepareRead(int indexFD, MemorySegment buffer, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, buffer, Math.toIntExact(buffer.byteSize()), offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    public long prepareRead(int indexFD, MemorySegment buffer, int readSize, long offset) {
        return prepareReadInternal(indexFD, buffer, readSize, offset, (byte) 0, true);
    }

    public long prepareRead(int indexFD, MemorySegment buffer, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareReadInternal(indexFD, buffer, readSize, offset, SqeOptions.combineOptions(sqeOptions), true);
    }

    public void setupReadBufferPool(int bufferSize, int capacity) {
        if (bufferSize <= 0 || capacity < 0) {
            throw new IllegalArgumentException("Invalid read buffer pool configuration: bufferSize=" + bufferSize + ", capacity=" + capacity);
        }
        if (readBufferPool != null) {
            readBufferPool.close();
        }
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

    public void checkInReadBuffer(Result result) {
        if (result == null) {
            throw new IllegalArgumentException("Result cannot be null");
        }
        if (result.type() != OperationType.READ_POOLED) {
            throw new IllegalArgumentException("Result must be of type READ_POOLED");
        }
        long address = result.bufferAddress();
        if (address != 0L) {
            checkInReadBuffer(address);
        }
    }

    void checkInReadBuffer(long address) {
        if (readBufferPool != null) {
            readBufferPool.checkIn(address);
        }
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

    public long prepareNop() {
        return prepareNopInternal((byte) 0);
    }

    public long prepareNop(SqeOptions... sqeOptions) {
        return prepareNopInternal(SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareNopInternal(byte flags) {
        long sqe = getSqe(flags, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.NOP, opId);
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

    public long prepareWriteFixed(FileDescriptor fd, int writeSize, long offset, int bufferIndex) {
        return prepareWriteFixedZeroCopyInternal(fd.getFd(), writeSize, offset, bufferIndex, (byte) 0, false);
    }

    public long prepareWriteFixed(FileDescriptor fd, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedZeroCopyInternal(fd.getFd(), writeSize, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareWriteFixed(FileDescriptor fd, long bufferAddress, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        long sqe = getSqe(SqeOptions.combineOptions(sqeOptions), false);
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);
        ioUring.prepareWriteFixed(sqe, fd.getFd(), bufferAddress, writeSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    public long prepareWriteFixed(int indexFD, int writeSize, long offset, int bufferIndex) {
        return prepareWriteFixedZeroCopyInternal(indexFD, writeSize, offset, bufferIndex, (byte) 0, true);
    }

    public long prepareWriteFixed(int indexFD, int writeSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareWriteFixedZeroCopyInternal(indexFD, writeSize, offset, bufferIndex, SqeOptions.combineOptions(sqeOptions), true);
    }

    public MemorySegment getRegisteredBuffer(int bufferIndex) {
        if (bufferIndex < 0 || bufferIndex >= registeredBuffersArray.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }
        return registeredBuffersArray[bufferIndex];
    }

    public long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset) {
        return prepareReadv(fd, bufferSizes, offset, (byte) 0);
    }

    public long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset, SqeOptions... sqeOptions) {
        return prepareReadv(fd, bufferSizes, offset, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareReadv(FileDescriptor fd, int[] bufferSizes, long offset, byte flags) {
        int nrVecs = bufferSizes.length;
        if (nrVecs <= 0 || nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " out of range (1.." + IovecBlockPool.MAX_VECS + ")");
        }
        for (int i = 0; i < nrVecs; i++) {
            if (bufferSizes[i] < 0) {
                throw new IllegalArgumentException("Invalid negative buffer size at index " + i + ": " + bufferSizes[i]);
            }
        }

        long sqe = getSqe(flags, false);
        long blockAddr = ioUring.allocateIovecBlock();
        IovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            MemorySegment dataBuf = NativeDispatcher.C.alloc(Math.max(1, bufferSizes[i]));
            IovecBlock.setIovBase(blockAddr, i, dataBuf.address());
            IovecBlock.setIovLen(blockAddr, i, bufferSizes[i]);
        }

        long userData = UserData.encode(OperationType.READV, blockAddr);

        ioUring.prepareReadvAddress(sqe, fd.getFd(), IovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
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
        if (nrVecs <= 0 || nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " out of range (1.." + IovecBlockPool.MAX_VECS + ")");
        }
        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            if (idx < 0 || idx >= registeredBuffers.size()) {
                throw new IllegalArgumentException("Buffer index out of range: " + idx);
            }
        }

        long sqe = getSqe(flags, false);
        long blockAddr = ioUring.allocateIovecBlock();
        IovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            MemorySegment registeredBuffer = registeredBuffers.get(idx);
            IovecBlock.setIovBase(blockAddr, i, registeredBuffer.address());
            IovecBlock.setIovLen(blockAddr, i, registeredBuffer.byteSize());
        }

        long userData = UserData.encode(OperationType.READV_FIXED, blockAddr);

        ioUring.prepareReadvAddress(sqe, fd.getFd(), IovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
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
        if (nrVecs <= 0 || nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " out of range (1.." + IovecBlockPool.MAX_VECS + ")");
        }
        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            if (idx < 0 || idx >= registeredBufferAddresses.length) {
                throw new IllegalArgumentException("Buffer index out of range: " + idx);
            }
            if (lengths[i] < 0 || lengths[i] > registeredBufferSizes[idx]) {
                throw new IllegalArgumentException("Length " + lengths[i] + " exceeds registered buffer size for index " + idx);
            }
        }

        long sqe = getSqe(flags, false);
        long blockAddr = ioUring.allocateIovecBlock();
        IovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            int idx = bufferIndices[i];
            IovecBlock.setIovBase(blockAddr, i, registeredBufferAddresses[idx]);
            IovecBlock.setIovLen(blockAddr, i, lengths[i]);
        }

        long userData = UserData.encode(OperationType.WRITEV_FIXED, blockAddr);

        ioUring.prepareWritevAddress(sqe, fd.getFd(), IovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
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
        if (nrVecs <= 0 || nrVecs > IovecBlockPool.MAX_VECS) {
            throw new IllegalArgumentException("nrVecs " + nrVecs + " out of range (1.." + IovecBlockPool.MAX_VECS + ")");
        }
        for (int i = 0; i < nrVecs; i++) {
            if (buffers[i] == null) {
                throw new IllegalArgumentException("Null buffer at index " + i);
            }
        }

        long sqe = getSqe(flags, false);
        long blockAddr = ioUring.allocateIovecBlock();
        IovecBlock.setCount(blockAddr, nrVecs);

        for (int i = 0; i < nrVecs; i++) {
            MemorySegment dataBuf = NativeDispatcher.C.alloc(buffers[i].length);
            MemorySegment.copy(buffers[i], 0, dataBuf, JAVA_BYTE, 0, buffers[i].length);
            IovecBlock.setIovBase(blockAddr, i, dataBuf.address());
            IovecBlock.setIovLen(blockAddr, i, buffers[i].length);
        }

        long userData = UserData.encode(OperationType.WRITEV, blockAddr);

        ioUring.prepareWritevAddress(sqe, fd.getFd(), IovecBlock.iovecArrayAddress(blockAddr), nrVecs, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareOpen(String filePath, int flags, int mode) {
        return prepareOpen(filePath, flags, mode, (byte) 0);
    }

    public long prepareOpen(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        return prepareOpen(filePath, flags, mode, SqeOptions.combineOptions(sqeOptions));
    }

    public long prepareOpen(Path filePath, int mode, LinuxOpenOptions... openOptions) {
        return prepareOpen(filePath.toString(), LinuxOpenOptions.combineOptions(openOptions), mode, (byte) 0);
    }

    private long prepareOpen(String filePath, int flags, int mode, byte sqeFlags) {
        byte[] pathBytes = LibCDispatcher.encodePath(filePath);
        long sqe = getSqe(sqeFlags, false);
        MemorySegment pathBuffer = NativeDispatcher.C.calloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathBuffer, JAVA_BYTE, 0, pathBytes.length);

        long userData = UserData.encode(OperationType.OPEN, pathBuffer.address());

        ioUring.prepareOpenAt(sqe, pathBuffer.address(), flags | 0x80000 /* O_CLOEXEC */, mode);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex) {
        return prepareOpenDirect(filePath, flags, mode, fileIndex, (byte) 0);
    }

    public long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        return prepareOpenDirect(filePath, flags, mode, fileIndex, SqeOptions.combineOptions(sqeOptions));
    }

    public long prepareOpenDirect(Path filePath, int mode, int fileIndex, LinuxOpenOptions... openOptions) {
        return prepareOpenDirect(filePath.toString(), LinuxOpenOptions.combineOptions(openOptions), mode, fileIndex, (byte) 0);
    }

    private long prepareOpenDirect(String filePath, int flags, int mode, int fileIndex, byte sqeFlags) {
        if (fileIndex < 0) {
            throw new IllegalArgumentException("Invalid fileIndex: " + fileIndex);
        }
        byte[] pathBytes = LibCDispatcher.encodePath(filePath);
        long sqe = getSqe(sqeFlags, false);
        MemorySegment pathBuffer = NativeDispatcher.C.alloc(pathBytes.length + 1);
        MemorySegment.copy(pathBytes, 0, pathBuffer, JAVA_BYTE, 0, pathBytes.length);
        pathBuffer.set(JAVA_BYTE, pathBytes.length, (byte) 0);

        long userData = UserData.encode(OperationType.OPEN, pathBuffer.address());

        ioUring.prepareOpenDirectAt(sqe, pathBuffer.address(), flags & ~0x80000 /* O_CLOEXEC not allowed for direct */, mode, fileIndex);
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
        if (fileIndex < 0) {
            throw new IllegalArgumentException("Invalid fileIndex: " + fileIndex);
        }
        long sqe = getSqe(flags, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.CLOSE, opId);

        ioUring.prepareCloseDirect(sqe, fileIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadInternal(int fdOrIndex, int readSize, long offset, byte flags, boolean fixedFile) {
        if (readSize < 0) {
            throw new IllegalArgumentException("Invalid readSize: " + readSize);
        }
        long sqe = getSqe(flags, fixedFile);
        long address = NativeDispatcher.C.mallocAddress(Math.max(1L, readSize));
        long userData = UserData.encode(OperationType.READ, address);

        ioUring.prepareRead(sqe, fdOrIndex, address, readSize, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadInternal(int fdOrIndex, MemorySegment buffer, int readSize, long offset, byte flags, boolean fixedFile) {
        if (readSize < 0 || readSize > buffer.byteSize()) {
            throw new IllegalArgumentException("Invalid readSize: " + readSize);
        }
        validateNativeSegment(buffer, true);
        long sqe = getSqe(flags, fixedFile);
        long userData = UserData.encode(OperationType.READ_EXT, buffer.address());

        ioUring.prepareRead(sqe, fdOrIndex, buffer.address(), readSize, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadPooledInternal(int fdOrIndex, int readSize, long offset, byte flags, boolean fixedFile) {
        if (readBufferPool == null) {
            throw new IllegalStateException("Read buffer pool has not been initialized; call setupReadBufferPool() first");
        }
        if (readSize < 0 || readSize > readBufferPool.bufferSize()) {
            throw new IllegalArgumentException("Read size " + readSize + " out of range (0.." + readBufferPool.bufferSize() + ")");
        }
        long sqe = getSqe(flags, fixedFile);
        long address = readBufferPool.checkOut();
        long userData = UserData.encode(OperationType.READ_POOLED, address);

        ioUring.prepareRead(sqe, fdOrIndex, address, readSize, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    static void validateNativeSegment(MemorySegment segment, boolean forRead) {
        if (!segment.isNative()) {
            throw new IllegalArgumentException("Segment must be native memory");
        }
        if (forRead && segment.isReadOnly()) {
            throw new IllegalArgumentException("Segment must not be read-only for read operations");
        }
        if (!segment.scope().isAlive()) {
            throw new IllegalStateException("Segment scope is already closed");
        }
        if (!segment.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("Segment is confined to another thread");
        }
    }

    private long prepareWriteInternal(int fdOrIndex, MemorySegment bytes, long offset, byte flags, boolean fixedFile) {
        validateNativeSegment(bytes, false);
        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.WRITE_EXT, opId);

        ioUring.prepareWrite(sqe, fdOrIndex, bytes.address(), bytes.byteSize(), offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteInternal(int fdOrIndex, byte[] bytes, long offset, byte flags, boolean fixedFile) {
        Objects.requireNonNull(bytes, "bytes");
        long sqe = getSqe(flags, fixedFile);
        MemorySegment buff = NativeDispatcher.C.alloc(bytes.length);
        MemorySegment.copy(bytes, 0, buff, JAVA_BYTE, 0, bytes.length);

        long userData = UserData.encode(OperationType.WRITE, buff.address());

        ioUring.prepareWrite(sqe, fdOrIndex, buff.address(), bytes.length, offset);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareReadFixedInternal(int fdOrIndex, int readSize, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        if (readSize < 0 || readSize > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Read size exceeds registered buffer size");
        }

        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.READ_FIXED, bufferIndex, opId);

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

        long sqe = getSqe(flags, fixedFile);
        MemorySegment.copy(bytes, 0, registeredBuffersArray[bufferIndex], JAVA_BYTE, 0, bytes.length);
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);

        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBufferAddresses[bufferIndex], bytes.length, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteFixedInternal(int fdOrIndex, MemorySegment bytes, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        long byteSize = bytes.byteSize();
        if (byteSize < 0 || byteSize > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        if (!bytes.scope().isAlive()) {
            throw new IllegalStateException("Segment scope is already closed");
        }
        if (!bytes.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("Segment is confined to another thread");
        }

        long sqe = getSqe(flags, fixedFile);
        long targetAddr = registeredBufferAddresses[bufferIndex];
        if (!bytes.isNative() || bytes.address() != targetAddr) {
            MemorySegment.copy(bytes, 0, registeredBuffersArray[bufferIndex], 0, byteSize);
        }
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);

        ioUring.prepareWriteFixed(sqe, fdOrIndex, targetAddr, byteSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareWriteFixedZeroCopyInternal(int fdOrIndex, int writeSize, long offset, int bufferIndex, byte flags, boolean fixedFile) {
        if (bufferIndex < 0 || bufferIndex >= registeredBufferAddresses.length) {
            throw new IllegalArgumentException("Buffer index out of range: " + bufferIndex);
        }

        if (writeSize < 0 || writeSize > registeredBufferSizes[bufferIndex]) {
            throw new IllegalArgumentException("Write size exceeds registered buffer size");
        }

        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encodeFixed(OperationType.WRITE_FIXED, bufferIndex, opId);

        ioUring.prepareWriteFixed(sqe, fdOrIndex, registeredBufferAddresses[bufferIndex], writeSize, offset, bufferIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    private long prepareCloseInternal(int fdOrIndex, byte flags) {
        long sqe = getSqe(flags, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.CLOSE, opId);

        ioUring.prepareClose(sqe, fdOrIndex);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    public long prepareFsync(FileDescriptor fd) {
        return prepareFsync(fd, 0);
    }

    public long prepareFsync(FileDescriptor fd, int fsyncFlags) {
        return prepareFsyncInternal(fd.getFd(), fsyncFlags, (byte) 0, false);
    }

    public long prepareFsync(FileDescriptor fd, int fsyncFlags, SqeOptions... sqeOptions) {
        return prepareFsyncInternal(fd.getFd(), fsyncFlags, SqeOptions.combineOptions(sqeOptions), false);
    }

    public long prepareFsync(int indexFD, int fsyncFlags) {
        return prepareFsyncInternal(indexFD, fsyncFlags, (byte) 0, true);
    }

    public long prepareFsync(int indexFD, int fsyncFlags, SqeOptions... sqeOptions) {
        return prepareFsyncInternal(indexFD, fsyncFlags, SqeOptions.combineOptions(sqeOptions), true);
    }

    private long prepareFsyncInternal(int fdOrIndex, int fsyncFlags, byte flags, boolean fixedFile) {
        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.FSYNC, opId);
        ioUring.prepareFsync(sqe, fdOrIndex, fsyncFlags);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    public long prepareCancel(long targetOpId) {
        long sqe = getSqe((byte) 0, false);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.CANCEL, opId);

        ioUring.prepareCancel(sqe, targetOpId, 0);
        ioUring.setUserData(sqe, userData);

        return userData;
    }

    // ------------------------------------------------------------------
    // Optional metadata ops: fallocate, ftruncate, statx, timeout
    // ------------------------------------------------------------------

    private static final int EOPNOTSUPP = 95;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * Prepares an async {@code fallocate(2)} (opcode {@code IORING_OP_FALLOCATE}) on a plain fd.
     * Requires kernel support for {@code IORING_OP_FALLOCATE}; otherwise throws
     * {@link UnsupportedOperationException} before touching the submission queue.
     *
     * @param mode   fallocate mode flags (0 for plain allocation, or {@code FALLOC_FL_*} bits)
     * @param offset file offset in bytes
     * @param length byte length to allocate
     * @return the operation handle to pass to {@code waitForResult}/{@code await}
     */
    public long prepareFallocate(FileDescriptor fd, int mode, long offset, long length) {
        return prepareFallocateInternal(fd.getFd(), mode, offset, length, (byte) 0, false);
    }

    public long prepareFallocate(FileDescriptor fd, int mode, long offset, long length, SqeOptions... sqeOptions) {
        return prepareFallocateInternal(fd.getFd(), mode, offset, length, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares an async {@code fallocate(2)} on a slot of the registered-file table.
     *
     * @param indexFD slot index in the registered-file table (submitted with {@code IOSQE_FIXED_FILE})
     */
    public long prepareFallocate(int indexFD, int mode, long offset, long length) {
        return prepareFallocateInternal(indexFD, mode, offset, length, (byte) 0, true);
    }

    public long prepareFallocate(int indexFD, int mode, long offset, long length, SqeOptions... sqeOptions) {
        return prepareFallocateInternal(indexFD, mode, offset, length, SqeOptions.combineOptions(sqeOptions), true);
    }

    private long prepareFallocateInternal(int fdOrIndex, int mode, long offset, long length, byte flags, boolean fixedFile) {
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_FALLOCATE, "IORING_OP_FALLOCATE");
        if (mode < 0) {
            throw new IllegalArgumentException("Invalid fallocate mode: " + mode);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Invalid fallocate offset: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("Invalid fallocate length: " + length);
        }
        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.FALLOCATE, opId);
        ioUring.prepareFallocate(sqe, fdOrIndex, mode, offset, length);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /**
     * Prepares an async {@code ftruncate(2)} (opcode {@code IORING_OP_FTRUNCATE}) on a plain fd.
     * Requires kernel support for {@code IORING_OP_FTRUNCATE}.
     */
    public long prepareFtruncate(FileDescriptor fd, long newLength) {
        return prepareFtruncateInternal(fd.getFd(), newLength, (byte) 0, false);
    }

    public long prepareFtruncate(FileDescriptor fd, long newLength, SqeOptions... sqeOptions) {
        return prepareFtruncateInternal(fd.getFd(), newLength, SqeOptions.combineOptions(sqeOptions), false);
    }

    /** Prepares an async {@code ftruncate(2)} on a slot of the registered-file table. */
    public long prepareFtruncate(int indexFD, long newLength) {
        return prepareFtruncateInternal(indexFD, newLength, (byte) 0, true);
    }

    public long prepareFtruncate(int indexFD, long newLength, SqeOptions... sqeOptions) {
        return prepareFtruncateInternal(indexFD, newLength, SqeOptions.combineOptions(sqeOptions), true);
    }

    private long prepareFtruncateInternal(int fdOrIndex, long newLength, byte flags, boolean fixedFile) {
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_FTRUNCATE, "IORING_OP_FTRUNCATE");
        if (newLength < 0) {
            throw new IllegalArgumentException("Invalid truncate length: " + newLength);
        }
        long sqe = getSqe(flags, fixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.FTRUNCATE, opId);
        ioUring.prepareFtruncate(sqe, fdOrIndex, newLength);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /**
     * Prepares an async {@code statx(2)} with {@code AT_EMPTY_PATH} and {@code STATX_BASIC_STATS}
     * against a plain fd, writing the result into {@code statxBuffer}.
     * <p>
     * {@code statxBuffer} must be a live native segment of at least 256 bytes (the
     * {@code struct statx} size); the buffer must stay valid until the operation completes.
     * Requires kernel support for {@code IORING_OP_STATX}.
     *
     * @return the operation handle; the completion result is 0 on success
     */
    public long prepareStatx(FileDescriptor fd, MemorySegment statxBuffer) {
        return prepareStatxInternal(fd.getFd(), statxBuffer, (byte) 0, false);
    }

    public long prepareStatx(FileDescriptor fd, MemorySegment statxBuffer, SqeOptions... sqeOptions) {
        return prepareStatxInternal(fd.getFd(), statxBuffer, SqeOptions.combineOptions(sqeOptions), false);
    }

    /**
     * Prepares an async {@code statx(2)} with {@code AT_EMPTY_PATH} against a slot of the
     * registered-file table.
     * <p>
     * Kernel note: {@code IORING_OP_STATX} does not accept {@code IOSQE_FIXED_FILE} (the kernel's
     * statx prep returns {@code -EBADF} for fixed files). This method therefore resolves the slot
     * to the file descriptor tracked for it and submits a plain {@code AT_EMPTY_PATH} statx against
     * that descriptor; the observable result is the same. If the slot is empty/untracked, the
     * operation is submitted as a fixed-file statx and completes with {@code -EBADF}, matching the
     * other fixed-file operations.
     */
    public long prepareStatx(int indexFD, MemorySegment statxBuffer) {
        return prepareStatxInternal(indexFD, statxBuffer, (byte) 0, true);
    }

    public long prepareStatx(int indexFD, MemorySegment statxBuffer, SqeOptions... sqeOptions) {
        return prepareStatxInternal(indexFD, statxBuffer, SqeOptions.combineOptions(sqeOptions), true);
    }

    private long prepareStatxInternal(int fdOrIndex, MemorySegment statxBuffer, byte flags, boolean fixedFile) {
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_STATX, "IORING_OP_STATX");
        Objects.requireNonNull(statxBuffer, "statxBuffer");
        if (!statxBuffer.isNative()) {
            throw new IllegalArgumentException("statxBuffer must be native memory");
        }
        if (statxBuffer.byteSize() < 256L) {
            throw new IllegalArgumentException("statxBuffer must be at least 256 bytes (struct statx size), got " + statxBuffer.byteSize());
        }
        if (!statxBuffer.scope().isAlive()) {
            throw new IllegalStateException("statxBuffer scope is already closed");
        }
        if (!statxBuffer.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("statxBuffer is confined to another thread");
        }
        int dfd = fdOrIndex;
        boolean useFixedFile = fixedFile;
        if (fixedFile) {
            int registeredFd = registeredFileFd(fdOrIndex);
            if (registeredFd >= 0) {
                // IORING_OP_STATX rejects IOSQE_FIXED_FILE with -EBADF, so resolve the slot to the
                // tracked descriptor and issue a plain AT_EMPTY_PATH statx on the same file.
                dfd = registeredFd;
                useFixedFile = false;
            }
        }
        long sqe = getSqe(flags, useFixedFile);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.STATX, opId);
        ioUring.prepareStatx(sqe, dfd, ioUring.emptyPathAddress(), AT_EMPTY_PATH, STATX_BASIC_STATS, statxBuffer.address());
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    /** Returns the file descriptor tracked for a registered-file slot, or -1 when empty/unknown. */
    private int registeredFileFd(int slot) {
        if (slot < 0 || slot >= registeredFileTable.length) {
            return -1;
        }
        return registeredFileTable[slot];
    }

    /**
     * Prepares an {@code IORING_OP_TIMEOUT} for {@code timeoutNanos} nanoseconds with
     * {@code count=1} and {@code flags=0}. The completion result is {@code -ETIME} when the
     * timeout fires, or 0 when {@code count} completions arrive first.
     * <p>
     * Control-path API: allocates a 16-byte {@code __kernel_timespec} in the ring arena on every
     * call (ring-lifetime memory, not reclaimed until the ring is closed). Do not use in hot loops.
     *
     * @param timeoutNanos relative timeout in nanoseconds, {@code >= 0}
     * @return the operation handle
     */
    public long prepareTimeout(long timeoutNanos) {
        return prepareTimeoutInternal(timeoutNanos, 1, 0, (byte) 0);
    }

    public long prepareTimeout(long timeoutNanos, SqeOptions... sqeOptions) {
        return prepareTimeoutInternal(timeoutNanos, 1, 0, SqeOptions.combineOptions(sqeOptions));
    }

    /**
     * Prepares an {@code IORING_OP_TIMEOUT} with an explicit completion {@code count} and
     * {@code IORING_TIMEOUT_*} flags.
     *
     * @param count number of completions that complete the timeout early (0 = a plain timeout)
     * @param flags {@code IORING_TIMEOUT_*} flags ({@code ABS}, {@code BOOTTIME}, {@code ETIME})
     */
    public long prepareTimeout(long timeoutNanos, int count, int flags) {
        return prepareTimeoutInternal(timeoutNanos, count, flags, (byte) 0);
    }

    public long prepareTimeout(long timeoutNanos, int count, int flags, SqeOptions... sqeOptions) {
        return prepareTimeoutInternal(timeoutNanos, count, flags, SqeOptions.combineOptions(sqeOptions));
    }

    private long prepareTimeoutInternal(long timeoutNanos, int count, int flags, byte sqeFlags) {
        requireOpcodeSupported(IoUringDispatcher.IORING_OP_TIMEOUT, "IORING_OP_TIMEOUT");
        if (timeoutNanos < 0) {
            throw new IllegalArgumentException("Invalid timeoutNanos: " + timeoutNanos);
        }
        if (count < 0) {
            throw new IllegalArgumentException("Invalid timeout count: " + count);
        }
        long sqe = getSqe(sqeFlags, false);
        MemorySegment timespec = ioUring.arena().allocate(16L, 8L);
        timespec.set(JAVA_LONG, 0L, timeoutNanos / NANOS_PER_SECOND);
        timespec.set(JAVA_LONG, 8L, timeoutNanos % NANOS_PER_SECOND);
        long opId = opSeq++;
        long userData = UserData.encode(OperationType.TIMEOUT, opId);
        ioUring.prepareTimeout(sqe, timespec.address(), count, flags);
        ioUring.setUserData(sqe, userData);
        return userData;
    }

    private void requireOpcodeSupported(byte opcode, String name) {
        if (!ioUring.isOpcodeSupported(opcode)) {
            throw new UnsupportedOperationException("kernel does not support " + name + " (errno " + EOPNOTSUPP + ": " + NativeDispatcher.C.strerror(EOPNOTSUPP) + ")");
        }
    }

    private long getSqe(byte flags, boolean fixedFile) {
        long sqe = ioUring.getSqe();
        if (sqe == 0L) {
            throw new IllegalStateException("Submission queue ring is full; call submit() before preparing more operations");
        }
        if (fixedFile) flags |= SqeOptions.IOSQE_FIXED_FILE.value;
        if (flags != 0) {
            ioUring.setSqeFlag(sqe, flags);
        }
        return sqe;
    }

    public void submit() {
        ioUring.submit();
    }

    /**
     * Submits all queued SQEs and waits until at least {@code minComplete} CQEs are available.
     *
     * @param minComplete minimum number of completions to wait for
     * @return number of completions currently available in the CQ ring
     */
    public int submitAndWait(int minComplete) {
        return ioUring.submitAndWait(minComplete);
    }

    /**
     * Submits all queued SQEs, waits until at least {@code minComplete} CQEs are available,
     * and drains ready CQEs into {@code consumer}.
     *
     * @param minComplete minimum number of completions to wait for
     * @param consumer    zero-allocation completion callback
     * @return number of completions reaped
     */
    public int submitAndWaitForBatchResult(int minComplete, ResultConsumer consumer) {
        return ioUring.submitAndWaitForBatchResult(minComplete, Integer.MAX_VALUE, consumer);
    }

    /**
     * Submits all queued SQEs, waits until at least {@code minComplete} CQEs are available,
     * and reaps up to {@code maxReap} CQEs into {@code consumer}.
     *
     * @param minComplete minimum number of completions to wait for before returning
     * @param maxReap     maximum number of completions to reap
     * @param consumer    zero-allocation completion callback
     * @return number of completions reaped
     */
    public int submitAndWaitForBatchResult(int minComplete, int maxReap, ResultConsumer consumer) {
        return ioUring.submitAndWaitForBatchResult(minComplete, maxReap, consumer);
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

    /**
     * Waits until at least {@code minComplete} CQEs are available and reaps them into {@code consumer}.
     *
     * @param minComplete minimum number of completions to wait for
     * @param consumer    zero-allocation completion callback
     * @return number of completions reaped
     */
    public int waitForBatchResult(int minComplete, ResultConsumer consumer) {
        return ioUring.waitForBatchResult(minComplete, Integer.MAX_VALUE, consumer);
    }

    /**
     * Waits until at least {@code minComplete} CQEs are available and reaps up to {@code maxReap} CQEs into {@code consumer}.
     *
     * @param minComplete minimum number of completions to wait for
     * @param maxReap     maximum number of completions to reap
     * @param consumer    zero-allocation completion callback
     * @return number of completions reaped
     */
    public int waitForBatchResult(int minComplete, int maxReap, ResultConsumer consumer) {
        return ioUring.waitForBatchResult(minComplete, maxReap, consumer);
    }

    public Result waitForResult() {
        return ioUring.waitForResult();
    }

    public Result waitForResult(Result target) {
        return ioUring.waitForResult(target);
    }

    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        if (this.registeredBuffersArray != null && this.registeredBuffersArray.length > 0) {
            throw new IllegalStateException("Buffers are already registered; cannot register twice");
        }
        MemorySegment[] result = ioUring.registerBuffers(size, nrOfBuffers);
        registeredBuffers.clear();
        registeredBuffers.addAll(Arrays.asList(result));
        this.registeredBuffersArray = result;
        this.registeredBufferAddresses = new long[result.length];
        this.registeredBufferSizes = new long[result.length];
        this.bufferInUse = new boolean[nrOfBuffers];
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

    public int checkOutBuffer() {
        if (freeBufferTop == 0) {
            return -1;
        }
        int idx = freeBufferStack[--freeBufferTop];
        if (bufferInUse != null && idx >= 0 && idx < bufferInUse.length) {
            bufferInUse[idx] = true;
        }
        return idx;
    }

    public void checkInBuffer(int bufferIndex) {
        if (bufferInUse == null || bufferIndex < 0 || bufferIndex >= bufferInUse.length) {
            throw new IllegalArgumentException("Invalid bufferIndex: " + bufferIndex);
        }
        if (!bufferInUse[bufferIndex]) {
            throw new IllegalStateException("Buffer index " + bufferIndex + " is already checked in (double check-in)");
        }
        bufferInUse[bufferIndex] = false;
        freeBufferStack[freeBufferTop++] = bufferIndex;
    }

    boolean isRingFdRegistered() {
        return ioUring.isRingFdRegistered();
    }

    boolean isSqNeedWakeup() {
        return ioUring.isSqNeedWakeup();
    }

    int ringFd() {
        return ioUring.ringFd();
    }

    public int registerFiles(FileDescriptor... fileDescriptors) {
        int[] fds = new int[fileDescriptors.length];
        for (int i = 0; i < fileDescriptors.length; i++) {
            fds[i] = fileDescriptors[i].getFd();
        }
        int ret = ioUring.registerFiles(fds);
        registeredFileTable = fds.clone();
        return ret;
    }

    public int registerFilesUpdate(int offset, int[] fileDescriptors) {
        int ret = ioUring.registerFilesUpdate(offset, fileDescriptors);
        if (registeredFileTable.length >= offset + fileDescriptors.length) {
            System.arraycopy(fileDescriptors, 0, registeredFileTable, offset, fileDescriptors.length);
        }
        return ret;
    }

    // ------------------------------------------------------------------
    // Registration lifecycle: sparse fixed-file table + buffers
    // ------------------------------------------------------------------

    /**
     * Lazily registers a sparse fixed-file table of {@code capacity} empty ({@code -1}) slots.
     * Called automatically by {@link #registerFileAdd(FileDescriptor)} with
     * {@link #DEFAULT_FILE_TABLE_CAPACITY} slots.
     *
     * @throws IllegalArgumentException  if {@code capacity <= 0}
     * @throws IllegalStateException     if a file table is already registered on this ring
     */
    void initFileTable(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Invalid file table capacity: " + capacity);
        }
        if (registeredFileTable.length > 0) {
            throw new IllegalStateException("A registered-file table is already initialised (capacity " + registeredFileTable.length + ")");
        }
        int[] fds = new int[capacity];
        Arrays.fill(fds, -1);
        ioUring.registerFiles(fds);
        registeredFileTable = fds;
    }

    /**
     * Adds a file to the sparse registered-file table, lazily initialising the table with
     * {@link #DEFAULT_FILE_TABLE_CAPACITY} slots on first use. The first free slot is filled with
     * {@code IORING_REGISTER_FILES_UPDATE}.
     *
     * @return the slot index, usable as {@code indexFD} with the {@code prepare*}/{@code *Fixed}
     * methods and with {@code IOSQE_FIXED_FILE}
     * @throws IllegalStateException if the table is full
     */
    public int registerFileAdd(FileDescriptor fd) {
        Objects.requireNonNull(fd, "fd");
        if (registeredFileTable.length == 0) {
            initFileTable(DEFAULT_FILE_TABLE_CAPACITY);
        }
        int slot = -1;
        for (int i = 0; i < registeredFileTable.length; i++) {
            if (registeredFileTable[i] == -1) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            throw new IllegalStateException("Registered-file table is full (capacity " + registeredFileTable.length + "); unregister a slot first");
        }
        ioUring.registerFilesUpdate(slot, new int[]{fd.getFd()});
        registeredFileTable[slot] = fd.getFd();
        return slot;
    }

    /**
     * Frees a slot of the sparse registered-file table by updating it with {@code -1}
     * ({@code IORING_REGISTER_FILES_UPDATE}). The slot is immediately reusable.
     */
    public void unregisterFileSlot(int slot) {
        if (slot < 0 || slot >= registeredFileTable.length) {
            throw new IllegalArgumentException("File slot out of range: " + slot + " (table capacity " + registeredFileTable.length + ")");
        }
        if (registeredFileTable[slot] == -1) {
            throw new IllegalStateException("File slot " + slot + " is not registered");
        }
        ioUring.registerFilesUpdate(slot, new int[]{-1});
        registeredFileTable[slot] = -1;
    }

    /**
     * Unregisters the whole registered-file set ({@code IORING_UNREGISTER_FILES}) and resets the
     * slot tracking so a later {@link #registerFileAdd(FileDescriptor)} re-initialises the table.
     * No-op when no file table is registered.
     */
    public void unregisterFiles() {
        if (registeredFileTable.length == 0) {
            return;
        }
        int ret = ioUring.unregisterFiles();
        if (ret < 0) {
            throw new IllegalStateException("Failed to unregister files: " + NativeDispatcher.C.strerror(-ret) + " (errno " + (-ret) + ")");
        }
        registeredFileTable = new int[0];
    }

    /**
     * Unregisters all registered buffers ({@code IORING_UNREGISTER_BUFFERS}) and resets the
     * buffer tracking/check-out state so a later {@link #registerBuffers(int, int)} succeeds again.
     * No-op when no buffers are registered.
     */
    public void unregisterBuffers() {
        if (registeredBuffersArray.length == 0) {
            return;
        }
        int ret = ioUring.unregisterBuffers();
        if (ret < 0) {
            throw new IllegalStateException("Failed to unregister buffers: " + NativeDispatcher.C.strerror(-ret) + " (errno " + (-ret) + ")");
        }
        registeredBuffers.clear();
        registeredBuffersArray = new MemorySegment[0];
        registeredBufferAddresses = new long[0];
        registeredBufferSizes = new long[0];
        bufferInUse = null;
        freeBufferStack = new int[0];
        freeBufferTop = 0;
    }

    /**
     * Best-effort runtime update of registered buffers through {@code IORING_REGISTER_BUFFERS_UPDATE}
     * (kernel dependent; available since Linux 5.13 when the kernel implements the opcode).
     * On success the tracking arrays are updated so subsequent fixed-buffer operations use the new
     * memory. The replacement segments must stay valid until the buffers are re-registered or the
     * ring is closed.
     *
     * @throws IllegalStateException         if no buffers are registered
     * @throws IllegalArgumentException      if the range or a segment is invalid
     * @throws UnsupportedOperationException if the kernel rejects {@code IORING_REGISTER_BUFFERS_UPDATE}
     *                                       (the message carries the errno)
     */
    public void updateRegisteredBuffers(int offset, MemorySegment... buffers) {
        Objects.requireNonNull(buffers, "buffers");
        if (registeredBuffersArray.length == 0) {
            throw new IllegalStateException("No registered buffers to update; call registerBuffers() first");
        }
        if (buffers.length == 0) {
            return;
        }
        if (offset < 0 || (long) offset + buffers.length > registeredBuffersArray.length) {
            throw new IllegalArgumentException("Update range out of bounds: offset=" + offset + ", count=" + buffers.length
                    + ", registered=" + registeredBuffersArray.length);
        }
        for (int i = 0; i < buffers.length; i++) {
            MemorySegment buffer = Objects.requireNonNull(buffers[i], "buffers[" + i + "]");
            if (!buffer.isNative()) {
                throw new IllegalArgumentException("Buffers must be native memory (index " + i + ")");
            }
            if (!buffer.scope().isAlive()) {
                throw new IllegalStateException("Segment scope is already closed (index " + i + ")");
            }
            if (!buffer.isAccessibleBy(Thread.currentThread())) {
                throw new WrongThreadException("Segment is confined to another thread (index " + i + ")");
            }
        }
        int ret = ioUring.registerBuffersUpdate(offset, buffers);
        if (ret < 0) {
            throw new UnsupportedOperationException("kernel does not support IORING_REGISTER_BUFFERS_UPDATE for this configuration (errno "
                    + (-ret) + ": " + NativeDispatcher.C.strerror(-ret) + ")");
        }
        for (int i = 0; i < buffers.length; i++) {
            int index = offset + i;
            registeredBuffersArray[index] = buffers[i];
            registeredBufferAddresses[index] = buffers[i].address();
            registeredBufferSizes[index] = buffers[i].byteSize();
            registeredBuffers.set(index, buffers[i]);
        }
        ioUring.setRegisteredBuffers(registeredBuffersArray);
    }

    /** Number of queued-but-not-yet-completed operations on the underlying ring. */
    int inFlightCount() {
        return ioUring.inFlightCount();
    }

    /** Reads {@code stx_size} from a completed statx buffer (package-private helper for JuringFile). */
    long staticxSize(MemorySegment statxBuffer) {
        return ioUring.staticxSize(statxBuffer);
    }

    /** Whether the ring accepted a close. */
    public boolean isClosed() {
        return closed;
    }

    boolean supportsFallocate() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_FALLOCATE);
    }

    boolean supportsFtruncate() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_FTRUNCATE);
    }

    boolean supportsStatx() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_STATX);
    }

    boolean supportsTimeout() {
        return ioUring.isOpcodeSupported(IoUringDispatcher.IORING_OP_TIMEOUT);
    }

    /**
     * Closes the ring and releases all associated off-heap resources.
     * <p>
     * For rings initialized with {@link IoUringOptions#IORING_SETUP_SINGLE_ISSUER}, {@code close()}
     * must be invoked on the owner (submitting) thread so that kernel-registered ring file descriptors
     * are properly unregistered from the calling task table.
     */
    @Override
    public void close() {
        closed = true;
        ioUring.close();
        if (readBufferPool != null) {
            readBufferPool.close();
            readBufferPool = null;
        }
    }
}
