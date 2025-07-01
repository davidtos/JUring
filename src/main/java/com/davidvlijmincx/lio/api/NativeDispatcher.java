package com.davidvlijmincx.lio.api;

final class NativeDispatcher {

    static final LibCDispatcher C = LibCDispatcher.create();

    static LibUringDispatcher getUringInstance(int queueDepth, IoUringOptions... ioUringflags) {
        return LibUringDispatcher.create(queueDepth, ioUringflags);
    }

    private NativeDispatcher() {
    }
}
