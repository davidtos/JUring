package com.davidvlijmincx.lio.api;

import com.davidvlijmincx.lio.api.functions.*;
import java.lang.foreign.MemorySegment;

record LibUringDispatcher (GetSqe sqe,
                           SetSqeFlag setSqeFlag,
                           PrepOpenAt prepOpenAt,
                           PrepareOpenDirect prepOpenDirect,
                           PrepareClose prepClose,
                           PrepareCloseDirect prepCloseDirect,
                           PrepareRead prepRead,
                           PrepareReadFixed prepReadFixed,
                           PrepareWrite prepWrite,
                           PrepareWriteFixed prepWriteFixed,
                           Submit submit,
                           WaitCqe waitCqe,
                           PeekCqe peekCqe,
                           PeekBatchCqe peekBatchCqe,
                           CqeSeen cqeSeen,
                           QueueInit queueInit,
                           QueueExit queueExit,
                           SqeSetData sqeSetData,
                           RegisterBuffers registerBuffers,
                           RegisterFiles registerFiles,
                           RegisterFilesUpdate registerFilesUpdate) {

    private static final int AT_FDCWD = (int) -100L;

    MemorySegment getSqe(MemorySegment ring) {
        return sqe.getSqe(ring);
    }

    void setSqeFlag(MemorySegment sqe, byte flag) {
        setSqeFlag.setSqeFlag(sqe, flag);
    }

    void prepareOpen(MemorySegment sqe, MemorySegment filePath, int flags, int mode) {
        prepOpenAt.prepareOpen(sqe,AT_FDCWD,filePath, flags, mode);
    }

    void prepareOpenDirect(MemorySegment sqe, MemorySegment filePath, int flags, int mode, int fileIndex){
        prepOpenDirect.prepareOpenDirect(sqe,AT_FDCWD, filePath, flags, mode, fileIndex);
    }

    void prepareClose(MemorySegment sqe, int fd) {
        prepClose.prepareClose(sqe,fd);
    }

    void prepareCloseDirect(MemorySegment sqe, int fileIndex) {
        prepCloseDirect.prepareCloseDirect(sqe, fileIndex);
    }

    void prepareRead(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        prepRead.prepareRead(sqe, fd, buffer, buffer.byteSize(), offset);
    }

    void prepareReadFixed(MemorySegment sqe, int fd, MemorySegment buffer, long offset, int bufferIndex) {
        prepReadFixed.prepareReadFixed(sqe, fd, buffer, buffer.byteSize(), offset, bufferIndex);
    }

    void prepareWrite(MemorySegment sqe, int fd, MemorySegment buffer, long offset) {
        prepWrite.prepareWrite(sqe, fd, buffer, buffer.byteSize(), offset);
    }

    void prepareWriteFixed(MemorySegment sqe, int fd, MemorySegment buffer, long nbytes, long offset, int bufferIndex) {
        prepWriteFixed.prepareWriteFixed(sqe, fd, buffer, nbytes, offset, bufferIndex);
    }

    int submit(MemorySegment ring) {
        return submit.submit(ring);
    }

    int waitCqe(MemorySegment ring, MemorySegment cqePtr) {
        return waitCqe.waitCqe(ring, cqePtr);
    }

    int peekCqe(MemorySegment ring, MemorySegment cqePtr) {
        return peekCqe.peekCqe(ring, cqePtr);
    }

    int peekBatchCqe(MemorySegment ring, MemorySegment cqePtrPtr, int batchSize) {
        return peekBatchCqe.peekBatchCqe(ring, cqePtrPtr, batchSize);
    }

    void cqeSeen(MemorySegment ring, MemorySegment cqePointer) {
        cqeSeen.cqeSeen(ring, cqePointer);
    }

    int queueInit(int queueDepth, MemorySegment ring, int flags) {
        return queueInit.queueInit(queueDepth, ring, flags);
    }

    void queueExit(MemorySegment ring) {
        queueExit.queueExit(ring);
    }

    void sqeSetData(MemorySegment sqe, long userData) {
        sqeSetData.sqeSetData(sqe, userData);
    }

    int registerBuffers(MemorySegment ring, MemorySegment iovecs, int nrIovecs) {
        return registerBuffers.registerBuffers(ring, iovecs, nrIovecs);
    }

    int registerFiles(MemorySegment ring, MemorySegment fdArray, int count) {
        return registerFiles.registerFiles(ring, fdArray, count);
    }

    int registerFilesUpdate(MemorySegment ring, int offset, MemorySegment fdArray, int count) {
        return registerFilesUpdate.registerFilesUpdate(ring, offset, fdArray, count);
    }

}
