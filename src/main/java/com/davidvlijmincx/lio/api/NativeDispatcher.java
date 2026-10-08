package com.davidvlijmincx.lio.api;

final class NativeDispatcher {

    static final LibCDispatcher C = LibCDispatcher.create();

    static IoUringDispatcher getUringInstance(int queueDepth, IoUringOptions... ioUringflags) {
        return IoUringDispatcher.create(queueDepth, ioUringflags);
    }

    private NativeDispatcher() {
    }
}
