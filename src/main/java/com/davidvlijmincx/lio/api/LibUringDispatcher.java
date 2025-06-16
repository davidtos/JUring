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
                           PrepareReadFixed prepReadFixed) {

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
        prepOpenDirect.prepareOpenDirect(sqe, filePath, flags, mode, fileIndex);
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

}
