package com.davidvlijmincx.lio.api;

final class NativeDispatcher {

    static final LibCDispatcher C = LibCDispatcher.create();

    static IoUringDispatcher getUringInstance(int queueDepth, IoUringOptions... ioUringflags) {
        return IoUringDispatcher.create(queueDepth, 0, ioUringflags);
    }

    static IoUringDispatcher getUringInstance(int queueDepth, int sqThreadIdleMs, IoUringOptions... ioUringflags) {
        return IoUringDispatcher.create(queueDepth, sqThreadIdleMs, ioUringflags);
    }

    private NativeDispatcher() {
    }
}
